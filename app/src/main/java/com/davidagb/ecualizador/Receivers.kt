package com.davidagb.ecualizador

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.service.notification.NotificationListenerService

/** Registra un receptor que escucha difusiones de otras apps y del sistema. */
fun Context.listen(receiver: BroadcastReceiver, filter: IntentFilter) {
    if (Build.VERSION.SDK_INT >= 33) {
        registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
    } else {
        registerReceiver(receiver, filter)
    }
}

/** Vuelve a encender el ecualizador tras reiniciar el teléfono o actualizar la app. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Eq.load(context)
        if (Eq.enabled) context.startForegroundService(Intent(context, EqService::class.java))
    }
}

/**
 * No hace nada por sí mismo: tener el acceso a notificaciones concedido es lo que
 * permite a la app leer las sesiones de medios (qué suena y en qué app).
 */
class MediaListener : NotificationListenerService()
