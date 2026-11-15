package com.termux.agent

import android.content.pm.PackageManager
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
}
