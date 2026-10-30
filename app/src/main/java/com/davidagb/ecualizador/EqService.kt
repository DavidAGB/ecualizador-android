package com.davidagb.ecualizador

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.audiofx.AudioEffect
import android.os.Build
import android.os.IBinder

/** Mantiene vivos los efectos de audio mientras el ecualizador está encendido. */
class EqService : Service() {

    private lateinit var nm: NotificationManager
    private var shown = ""

    private val sessions = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val id = i.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, AudioEffect.ERROR_BAD_VALUE)
            when (i.action) {
                AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION ->
                    Eq.openSession(id, i.getStringExtra(AudioEffect.EXTRA_PACKAGE_NAME))
                AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION -> Eq.closeSession(id)
            }
        }
    }

    private val update: () -> Unit = {
        val n = text()
        if (n != shown) nm.notify(ID, notification())
    }

    override fun onCreate() {
        super.onCreate()
        Eq.load(this)
        nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Ecualizador activo", NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
            }
        )
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(ID, notification())
        }
        listen(sessions, IntentFilter().apply {
            addAction(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION)
            addAction(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION)
        })
        Eq.listeners += update
        Eq.attach()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Eq.setEnabled(this, false)
            return START_NOT_STICKY
        }
        if (!Eq.enabled) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        Eq.listeners -= update
        unregisterReceiver(sessions)
        Eq.detach()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun text(): String = "${Eq.preset}|" + when {
        Eq.globalOk -> "Aplicado a todo el audio"
        Eq.apps.isNotEmpty() -> "Aplicado a ${Eq.apps.size} app(s) de música"
        else -> "Esperando a una app de música"
    }

    private fun notification(): Notification {
        val (title, body) = text().also { shown = it }.split("|")
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, EqService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_eq)
            .setContentTitle("Ecualizador · $title")
            .setContentText(body)
            .setContentIntent(open)
            .setOngoing(true)
            .setShowWhen(false)
            .addAction(
                Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_stat_eq), "Apagar", stop).build()
            )
            .build()
    }

    companion object {
        private const val CHANNEL = "eq"
        private const val ID = 1
        private const val ACTION_STOP = "com.davidagb.ecualizador.STOP"
    }
}
