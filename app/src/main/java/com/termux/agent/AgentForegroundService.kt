package com.termux.agent

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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/**
 * Foreground service keeping the Termux SSH PTY session alive across app backgrounding.
 */
class AgentForegroundService : Service() {
    private val executor = Executors.newSingleThreadExecutor()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var isObservingState = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundWithState(SshSessionManager.connectionState.value)
        if (!isObservingState) {
            isObservingState = true
            serviceScope.launch {
                SshSessionManager.connectionState.collect { state ->
                    updateNotification(state)
                }
            }
        }

        when (intent?.action) {
            ACTION_CONNECT, null -> {
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
            ACTION_SEND_COMMAND -> {
                val command = intent.getStringExtra(EXTRA_COMMAND) ?: return START_NOT_STICKY
                executor.execute {
                    SshSessionManager.sendCommand(command)
                }
            }
            ACTION_DISCONNECT -> {
                executor.execute {
                    SshSessionManager.disconnect()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        serviceScope.cancel()
        executor.shutdownNow()
        thread(start = true, isDaemon = true, name = "termux-ssh-disconnect") {
            SshSessionManager.disconnect()
        }
        super.onDestroy()
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

    private fun ensureNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_LOW
        )
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(state: ConnectionState): Notification {
        val text = when (state) {
            ConnectionState.Checking -> "Conectando por SSH a Termux..."
            is ConnectionState.Connected -> "Conectado (${state.host}:${state.port})"
            is ConnectionState.Error -> "Error: ${state.reason}"
        }
        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Termux Agent")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openAppIntent)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "termux_agent_session"
        private const val CHANNEL_NAME = "Sesión SSH de Termux"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_CONNECT = "com.termux.agent.action.CONNECT"
        const val ACTION_SEND_COMMAND = "com.termux.agent.action.SEND_COMMAND"
        const val ACTION_DISCONNECT = "com.termux.agent.action.DISCONNECT"
        const val EXTRA_COMMAND = "extra_command"

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
         * Dispatches a shell command line to the active PTY session inside the foreground service.
         */
        fun sendCommand(context: Context, command: String) {
            val intent = Intent(context, AgentForegroundService::class.java).apply {
                action = ACTION_SEND_COMMAND
                putExtra(EXTRA_COMMAND, command)
            }
            ContextCompat.startForegroundService(context, intent)
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
