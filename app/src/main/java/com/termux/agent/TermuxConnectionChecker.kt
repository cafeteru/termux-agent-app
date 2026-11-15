package com.termux.agent

import android.content.pm.PackageManager
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.jcraft.jsch.UIKeyboardInteractive
import com.jcraft.jsch.UserInfo
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

sealed interface ConnectionState {
    data object Checking : ConnectionState
    data class Connected(val host: String, val port: Int, val serverBanner: String) : ConnectionState
    data class Error(val reason: String) : ConnectionState
}

object TermuxConnectionChecker {
    private const val CLIENT_SSH_BANNER = "SSH-2.0-TermuxAgent_0.0.1\r\n"

    /**
     * Checks whether the Termux package is installed and visible to this app.
     */
    @Suppress("DEPRECATION")
    fun isTermuxInstalled(
        packageManager: PackageManager,
        packageName: String = AppConfig.TERMUX_PACKAGE_NAME
    ): Boolean {
        return try {
            packageManager.getPackageInfo(packageName, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    /**
     * Verifies Termux presence and performs an SSH banner handshake against the target endpoint.
     */
    fun checkConnection(
        isTermuxInstalled: () -> Boolean,
        host: String = AppConfig.DEFAULT_SSH_HOST,
        port: Int = AppConfig.DEFAULT_SSH_PORT,
        timeoutMs: Int = AppConfig.DEFAULT_CONNECT_TIMEOUT_MS
    ): ConnectionState {
        if (!isTermuxInstalled()) {
            return ConnectionState.Error(
                "Termux (${AppConfig.TERMUX_PACKAGE_NAME}) no está instalado."
            )
        }

        return try {
            Socket().use { socket ->
                socket.soTimeout = timeoutMs
                socket.connect(InetSocketAddress(host, port), timeoutMs)
                val line = socket.getInputStream().bufferedReader().readLine()
                if (line != null && line.startsWith("SSH-")) {
                    runCatching {
                        val output = socket.getOutputStream()
                        output.write(CLIENT_SSH_BANNER.toByteArray(Charsets.US_ASCII))
                        output.flush()
                    }
                    ConnectionState.Connected(host, port, line.trim())
                } else {
                    ConnectionState.Error(
                        "Respuesta inesperada en $host:$port (no es un servidor SSH): ${line ?: "sin datos"}"
                    )
                }
            }
        } catch (e: ConnectException) {
            ConnectionState.Error(
                "Conexión rechazada en $host:$port (${e.message ?: "ConnectException"}). Asegúrate de ejecutar 'sshd' en Termux."
            )
        } catch (e: SocketTimeoutException) {
            ConnectionState.Error(
                "Tiempo de espera agotado al conectar a $host:$port (${e.message ?: "Timeout"})."
            )
        } catch (e: Exception) {
            ConnectionState.Error(
                "Error al conectar a $host:$port (${e.javaClass.simpleName}): ${e.message ?: "Error desconocido"}"
            )
        }
    }

    /**
     * Verifies Termux presence and authenticates a real SSH session using password or public key.
     */
    fun checkConnectionWithAuth(
        isTermuxInstalled: () -> Boolean,
        config: SshConfig,
        timeoutMs: Int = AppConfig.DEFAULT_CONNECT_TIMEOUT_MS
    ): ConnectionState {
        if (!isTermuxInstalled()) {
            return ConnectionState.Error(
                "Termux (${AppConfig.TERMUX_PACKAGE_NAME}) no está instalado."
            )
        }
        if (config.port !in 1..65535) {
            return ConnectionState.Error(
                "Puerto SSH inválido (${config.port}). Usa un valor entre 1 y 65535."
            )
        }
        if (config.authMethod == AuthMethod.PASSWORD && config.password.isBlank()) {
            return ConnectionState.Error(
                "Introduce la contraseña de Termux (configurada con 'passwd') o cambia a Clave SSH."
            )
        }
        if (config.authMethod == AuthMethod.PUBLIC_KEY && config.privateKeyPem.isBlank()) {
            return ConnectionState.Error(
                "No hay clave SSH configurada. Pulsa 'Regenerar clave'."
            )
        }

        var session: Session? = null
        return try {
            val jsch = JSch()
            if (config.authMethod == AuthMethod.PUBLIC_KEY) {
                jsch.addIdentity(
                    "termux-agent-key",
                    config.privateKeyPem.toByteArray(Charsets.UTF_8),
                    null,
                    null
                )
            }
            val username = config.username.ifBlank { AppConfig.DEFAULT_SSH_USER }
            session = jsch.getSession(username, config.host, config.port).apply {
                if (config.authMethod == AuthMethod.PASSWORD) {
                    setPassword(config.password)
                    setConfig("PreferredAuthentications", "password,keyboard-interactive")
                    userInfo = PasswordUserInfo(config.password)
                } else {
                    setConfig("PreferredAuthentications", "publickey")
                }
                setConfig("StrictHostKeyChecking", "no")
                connect(timeoutMs)
            }
            val serverBanner = session.serverVersion ?: "SSH-2.0"
            ConnectionState.Connected(config.host, config.port, serverBanner)
        } catch (e: Exception) {
            mapSshError(e, config)
        } finally {
            session?.disconnect()
        }
    }

    private fun mapSshError(e: Exception, config: SshConfig): ConnectionState.Error {
        val causes = generateSequence<Throwable>(e) { it.cause }.toList()
        val messages = causes.mapNotNull { it.message }.joinToString(" | ")

        return when {
            causes.any { it is ConnectException } ||
                messages.contains("Connection refused", ignoreCase = true) -> {
                ConnectionState.Error(
                    "Conexión rechazada en ${config.host}:${config.port}. Asegúrate de ejecutar 'sshd' en Termux."
                )
            }
            causes.any { it is SocketTimeoutException } ||
                messages.contains("timeout", ignoreCase = true) ||
                messages.contains("timed out", ignoreCase = true) -> {
                ConnectionState.Error(
                    "Tiempo de espera agotado al conectar a ${config.host}:${config.port}."
                )
            }
            messages.contains("Auth fail", ignoreCase = true) ||
                messages.contains("Auth cancel", ignoreCase = true) -> {
                val hint = if (config.authMethod == AuthMethod.PASSWORD) {
                    "Contraseña rechazada por Termux. Verifica tu contraseña ejecutando 'passwd' en Termux."
                } else {
                    "Clave pública rechazada. Copia el comando de abajo y pégalo en Termux para autorizar la clave en ~/.ssh/authorized_keys."
                }
                ConnectionState.Error(hint)
            }
            else -> {
                ConnectionState.Error(
                    "Error SSH al conectar a ${config.host}:${config.port} (${e.javaClass.simpleName}): ${e.message ?: "Error desconocido"}"
                )
            }
        }
    }

    private class PasswordUserInfo(private val passwordValue: String) : UserInfo, UIKeyboardInteractive {
        override fun getPassphrase(): String? = null
        override fun getPassword(): String = passwordValue
        override fun promptPassword(message: String?): Boolean = true
        override fun promptPassphrase(message: String?): Boolean = false
        override fun promptYesNo(message: String?): Boolean = true
        override fun showMessage(message: String?) = Unit
        override fun promptKeyboardInteractive(
            destination: String?,
            name: String?,
            instruction: String?,
            prompt: Array<out String>?,
            echo: BooleanArray?
        ): Array<String>? {
            if (prompt.isNullOrEmpty()) return emptyArray()
            return if (prompt.size == 1 && echo?.getOrNull(0) != true) {
                arrayOf(passwordValue)
            } else {
                null
            }
        }
    }
}
