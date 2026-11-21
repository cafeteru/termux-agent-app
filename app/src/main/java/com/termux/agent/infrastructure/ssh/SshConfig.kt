package com.termux.agent.infrastructure.ssh

import com.termux.agent.application.config.AppConfig

import android.content.Context
import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair
import java.io.ByteArrayOutputStream

enum class AuthMethod {
    PASSWORD,
    PUBLIC_KEY
}

data class SshConfig(
    val host: String = AppConfig.DEFAULT_SSH_HOST,
    val port: Int = AppConfig.DEFAULT_SSH_PORT,
    val username: String = AppConfig.DEFAULT_SSH_USER,
    val authMethod: AuthMethod = AuthMethod.PASSWORD,
    val password: String = "",
    val privateKeyPem: String = "",
    val publicKeyAuthorized: String = ""
)

object SshKeyManager {
    /**
     * Generates an ECDSA-256 SSH key pair returning (privateKeyPem, openSshPublicKey).
     */
    fun generateKeyPair(comment: String = "termux-agent"): Pair<String, String> {
        val jsch = JSch()
        val keyPair = KeyPair.genKeyPair(jsch, KeyPair.ECDSA, 256)
        return try {
            val privateOut = ByteArrayOutputStream()
            val publicOut = ByteArrayOutputStream()
            keyPair.writePrivateKey(privateOut)
            keyPair.writePublicKey(publicOut, comment)
            val privateKeyPem = String(privateOut.toByteArray(), Charsets.UTF_8)
            val publicKeyAuthorized = String(publicOut.toByteArray(), Charsets.UTF_8).trim()
            privateKeyPem to publicKeyAuthorized
        } finally {
            keyPair.dispose()
        }
    }

    /**
     * Builds a shell command to append the public key into Termux's ~/.ssh/authorized_keys.
     */
    fun buildAuthorizedKeysCommand(publicKey: String): String {
        val normalizedKey = publicKey.trim()
        return "mkdir -p ~/.ssh && chmod 700 ~/.ssh && echo '$normalizedKey' >> ~/.ssh/authorized_keys && chmod 600 ~/.ssh/authorized_keys"
    }
}

object SshConfigStore {
    private const val PREFS_NAME = "termux_ssh_config"
    private const val KEY_HOST = "host"
    private const val KEY_PORT = "port"
    private const val KEY_USERNAME = "username"
    private const val KEY_AUTH_METHOD = "auth_method"
    private const val KEY_PASSWORD = "password"
    private const val KEY_PRIVATE_KEY_PEM = "private_key_pem"
    private const val KEY_PUBLIC_KEY_AUTHORIZED = "public_key_authorized"
    private const val KEY_WORKSPACE_PATH = "workspace_path"

    /**
     * Loads persisted SSH settings, generating a key pair on first access if missing.
     */
    fun load(context: Context): SshConfig {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val host = prefs.getString(KEY_HOST, null)?.takeIf { it.isNotBlank() }
            ?: AppConfig.DEFAULT_SSH_HOST
        val rawPort = prefs.getInt(KEY_PORT, AppConfig.DEFAULT_SSH_PORT)
        val port = if (rawPort in 1..65535) rawPort else AppConfig.DEFAULT_SSH_PORT
        val username = prefs.getString(KEY_USERNAME, null)?.takeIf { it.isNotBlank() }
            ?: AppConfig.DEFAULT_SSH_USER
        val methodName = prefs.getString(KEY_AUTH_METHOD, AuthMethod.PASSWORD.name)
            ?: AuthMethod.PASSWORD.name
        val authMethod = runCatching { AuthMethod.valueOf(methodName) }
            .getOrDefault(AuthMethod.PASSWORD)
        val password = prefs.getString(KEY_PASSWORD, "") ?: ""
        var privateKeyPem = prefs.getString(KEY_PRIVATE_KEY_PEM, "") ?: ""
        var publicKeyAuthorized = prefs.getString(KEY_PUBLIC_KEY_AUTHORIZED, "") ?: ""

        val needsGeneratedKey = privateKeyPem.isBlank() || publicKeyAuthorized.isBlank()
        if (needsGeneratedKey) {
            val (generatedPrivate, generatedPublic) = SshKeyManager.generateKeyPair()
            privateKeyPem = generatedPrivate
            publicKeyAuthorized = generatedPublic
        }

        val config = SshConfig(
            host = host,
            port = port,
            username = username,
            authMethod = authMethod,
            password = password,
            privateKeyPem = privateKeyPem,
            publicKeyAuthorized = publicKeyAuthorized
        )

        if (needsGeneratedKey) {
            save(context, config)
        }
        return config
    }

    /**
     * Persists SSH connection settings to private SharedPreferences.
     */
    fun save(context: Context, config: SshConfig) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_HOST, config.host)
            .putInt(KEY_PORT, config.port)
            .putString(KEY_USERNAME, config.username)
            .putString(KEY_AUTH_METHOD, config.authMethod.name)
            .putString(KEY_PASSWORD, config.password)
            .putString(KEY_PRIVATE_KEY_PEM, config.privateKeyPem)
            .putString(KEY_PUBLIC_KEY_AUTHORIZED, config.publicKeyAuthorized)
            .apply()
    }

    /**
     * Loads the last selected project directory path, or blank if none was chosen yet.
     */
    fun loadWorkspacePath(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_WORKSPACE_PATH, "")?.trim().orEmpty()
    }

    /**
     * Persists the selected project directory path independently of SSH connection settings.
     */
    fun saveWorkspacePath(context: Context, workspacePath: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_WORKSPACE_PATH, workspacePath.trim())
            .apply()
    }
}
