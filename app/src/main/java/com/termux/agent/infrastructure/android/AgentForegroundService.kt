package com.termux.agent.infrastructure.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.termux.agent.application.config.AppConfig
import com.termux.agent.domain.model.AdapterEvent
import com.termux.agent.domain.model.AgentState
import com.termux.agent.infrastructure.ssh.ConnectionState
import com.termux.agent.infrastructure.ssh.SshConfigStore
import com.termux.agent.infrastructure.ssh.SshSessionManager
import com.termux.agent.infrastructure.ssh.TermuxConnectionChecker
import com.termux.agent.presentation.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/**
 * Foreground service keeping the Termux SSH PTY session alive across app backgrounding
 * and surfacing interactive notifications for agent tool permissions and turn completion.
 */
class AgentForegroundService : Service() {
    private val executor = Executors.newSingleThreadExecutor()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observerJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureNotificationChannels()
        startForegroundWithState(SshSessionManager.connectionState.value)
        startObservingSessionEvents()
        when (intent?.action) {
            ACTION_DISCONNECT -> {
                observerJob?.cancel()
                observerJob = null
                stopForeground(STOP_FOREGROUND_REMOVE)
                cancelAllNotifications()
                executor.execute {
                    SshSessionManager.disconnect()
                    stopSelfResult(startId)
                }
                return START_NOT_STICKY
            }
            ACTION_RESOLVE_PERMISSION -> {
                val allow = intent.getBooleanExtra(EXTRA_ALLOW, true)
                val optionIndex = if (intent.hasExtra(EXTRA_OPTION_INDEX)) {
                    intent.getIntExtra(EXTRA_OPTION_INDEX, 0)
                } else null
                cancelPermissionNotification()
                executor.execute {
                    SshSessionManager.resolvePermission(allow, optionIndex)
                }
                return START_NOT_STICKY
            }
            ACTION_SEND_COMMAND -> {
                val command = intent.getStringExtra(EXTRA_COMMAND) ?: return START_NOT_STICKY
                executor.execute {
                    SshSessionManager.sendCommand(command)
                }
                return START_NOT_STICKY
            }
        }


        if (intent?.action == ACTION_CONNECT || intent == null) {
            executor.execute {
                val config = SshConfigStore.load(this)
                SshSessionManager.connect(
                    isTermuxInstalled = {
                        TermuxConnectionChecker.isTermuxInstalled(packageManager)
                    },
                    config = config
                )
            }
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        serviceScope.cancel()
        executor.shutdownNow()
        cancelAlertNotifications()
        thread(start = true, isDaemon = true, name = "termux-ssh-disconnect") {
            SshSessionManager.disconnect()
        }
        super.onDestroy()
    }

    private fun startObservingSessionEvents() {
        if (observerJob?.isActive == true) return
        observerJob = serviceScope.launch {
            launch {
                SshSessionManager.connectionState.collect { state ->
                    updateNotification(state)
                    if (state !is ConnectionState.Connected) {
                        cancelPermissionNotification()
                    }
                }
            }
            launch {
                SshSessionManager.adapterEvent.collect { event ->
                    handleAdapterEvent(event)
                }
            }
            launch {
                SshSessionManager.agentState.collect { state ->
                    if (state != AgentState.WAITING_PERMISSION) {
                        cancelPermissionNotification()
                    }
                }
            }
        }
    }

    private fun handleAdapterEvent(event: AdapterEvent?) {
        when (event) {
            is AdapterEvent.PermissionRequired -> showPermissionNotification(event)
            is AdapterEvent.TurnCompleted -> {
                cancelPermissionNotification()
                showTurnCompletedNotification(event)
            }
            else -> Unit
        }
    }

    private fun startForegroundWithState(state: ConnectionState) {
        val notification = buildNotification(state)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(state: ConnectionState) {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.notify(NOTIFICATION_ID, buildNotification(state))
    }

    private fun showPermissionNotification(event: AdapterEvent.PermissionRequired) {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val agentName = SshSessionManager.activeAdapter.value.name
        val title = "Permiso requerido ($agentName)"
        val contentText = if (event.command.isNotBlank()) {
            "${event.tool}: ${event.command}"
        } else {
            "${event.tool} solicita confirmación"
        }

        val openAppIntent = PendingIntent.getActivity(
            this,
            REQUEST_CODE_OPEN_PERMISSION,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val allowIntent = PendingIntent.getService(
            this,
            REQUEST_CODE_ALLOW,
            Intent(this, AgentForegroundService::class.java).apply {
                action = ACTION_RESOLVE_PERMISSION
                putExtra(EXTRA_ALLOW, true)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val denyIntent = PendingIntent.getService(
            this,
            REQUEST_CODE_DENY,
            Intent(this, AgentForegroundService::class.java).apply {
                action = ACTION_RESOLVE_PERMISSION
                putExtra(EXTRA_ALLOW, false)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ALERTS_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(title)
            .setContentText(contentText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(contentText))
            .setContentIntent(openAppIntent)
            .addAction(android.R.drawable.ic_media_play, "Permitir", allowIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Denegar", denyIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setOnlyAlertOnce(true)
            .setAutoCancel(false)
            .build()

        manager.notify(NOTIFICATION_ID_PERMISSION, notification)
    }

    private fun showTurnCompletedNotification(event: AdapterEvent.TurnCompleted) {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val agentName = SshSessionManager.activeAdapter.value.name
        val title = "Tarea completada ($agentName)"
        val text = event.summary.ifBlank { "El agente ha completado su turno." }

        val openAppIntent = PendingIntent.getActivity(
            this,
            REQUEST_CODE_OPEN_COMPLETED,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ALERTS_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openAppIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
            .build()

        manager.notify(NOTIFICATION_ID_COMPLETED, notification)
    }

    private fun cancelPermissionNotification() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.cancel(NOTIFICATION_ID_PERMISSION)
    }

    private fun cancelAlertNotifications() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.cancel(NOTIFICATION_ID_PERMISSION)
        manager.cancel(NOTIFICATION_ID_COMPLETED)
    }

    private fun cancelAllNotifications() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.cancel(NOTIFICATION_ID)
        manager.cancel(NOTIFICATION_ID_PERMISSION)
        manager.cancel(NOTIFICATION_ID_COMPLETED)
    }

    private fun ensureNotificationChannels() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val sessionChannel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_LOW
        )
        val alertsChannel = NotificationChannel(
            CHANNEL_ALERTS_ID,
            CHANNEL_ALERTS_NAME,
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Alertas interactivas de confirmación de herramientas y finalización de turnos."
        }
        manager.createNotificationChannel(sessionChannel)
        manager.createNotificationChannel(alertsChannel)
    }

    private fun buildNotification(state: ConnectionState): Notification {
        val text = when (state) {
            ConnectionState.Checking -> "Conectando por SSH a Termux..."
            is ConnectionState.Connected -> "Conectado (${state.host}:${state.port})"
            is ConnectionState.Error -> "Error: ${state.reason}"
            ConnectionState.Disconnected -> "Desconectado de Termux"
        }
        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val disconnectIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, AgentForegroundService::class.java).apply {
                action = ACTION_DISCONNECT
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Termux Agent")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openAppIntent)
            .addAction(0, "Desconectar", disconnectIntent)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val CHANNEL_ID = "termux_agent_session"
        const val CHANNEL_NAME = "Sesión SSH de Termux"
        const val CHANNEL_ALERTS_ID = "termux_agent_alerts"
        const val CHANNEL_ALERTS_NAME = "Alertas de Agente (Permisos y Finalización)"

        const val NOTIFICATION_ID = 1001
        const val NOTIFICATION_ID_PERMISSION = 2001
        const val NOTIFICATION_ID_COMPLETED = 2002

        private const val REQUEST_CODE_OPEN_PERMISSION = 100
        private const val REQUEST_CODE_ALLOW = 101
        private const val REQUEST_CODE_DENY = 102
        private const val REQUEST_CODE_OPEN_COMPLETED = 103

        const val ACTION_CONNECT = "com.termux.agent.action.CONNECT"
        const val ACTION_SEND_COMMAND = "com.termux.agent.action.SEND_COMMAND"
        const val ACTION_DISCONNECT = "com.termux.agent.action.DISCONNECT"
        const val ACTION_RESOLVE_PERMISSION = "com.termux.agent.action.RESOLVE_PERMISSION"
        const val EXTRA_COMMAND = "extra_command"
        const val EXTRA_ALLOW = "extra_allow"
        const val EXTRA_OPTION_INDEX = "extra_option_index"

        /**
         * Starts the foreground service and initiates an SSH PTY connection using saved settings.
         */
        fun connect(context: Context) {
            val intent = Intent(context, AgentForegroundService::class.java).apply {
                action = ACTION_CONNECT
            }
            ContextCompat.startForegroundService(context, intent)
        }

        /**
         * Dispatches a shell command line to the active PTY session without blocking the UI thread.
         */
        @Suppress("UNUSED_PARAMETER")
        fun sendCommand(context: Context, command: String) {
            SshSessionManager.sendCommandAsync(command)
        }

        /**
         * Dispatches raw terminal bytes (ESC, TAB, CTRL, ALT, arrows) without blocking the UI thread.
         */
        @Suppress("UNUSED_PARAMETER")
        fun sendRaw(context: Context, raw: String) {
            SshSessionManager.sendRawAsync(raw)
        }

        /**
         * Dispatches a permission resolution decision (allow / deny) to the active agent adapter.
         */
        fun resolvePermission(context: Context, allow: Boolean, optionIndex: Int? = null) {
            val intent = Intent(context, AgentForegroundService::class.java).apply {
                action = ACTION_RESOLVE_PERMISSION
                putExtra(EXTRA_ALLOW, allow)
                optionIndex?.let { putExtra(EXTRA_OPTION_INDEX, it) }
            }
            context.startService(intent)
        }

        /**
         * Requests disconnecting the active PTY session and stopping the foreground service.
         */
        fun disconnect(context: Context) {
            val intent = Intent(context, AgentForegroundService::class.java).apply {
                action = ACTION_DISCONNECT
            }
            context.startService(intent)
        }
    }
}
