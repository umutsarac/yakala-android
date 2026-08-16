package com.yakala.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.os.Build
import android.provider.AlarmClock

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val text = i.getStringExtra("text") ?: return
        val id = i.getIntExtra("id", 1)
        val alarm = i.getBooleanExtra("alarm", false)

        if (i.getBooleanExtra("openClock", false)) {
            try {
                val ci = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                    putExtra(AlarmClock.EXTRA_HOUR, i.getIntExtra("hour", 8))
                    putExtra(AlarmClock.EXTRA_MINUTES, i.getIntExtra("minute", 0))
                    putExtra(AlarmClock.EXTRA_MESSAGE, text)
                    putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                c.startActivity(ci)
            } catch (_: Exception) {}
            return
        }

        val nm = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            val chId = if (alarm) "yakala_alarm" else "yakala_rem"
            val ch = NotificationChannel(chId, if (alarm) "Alarmlar" else "Hatırlatmalar", NotificationManager.IMPORTANCE_HIGH)
            if (alarm) ch.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM), null)
            nm.createNotificationChannel(ch)
        }
        val b = if (Build.VERSION.SDK_INT >= 26)
            Notification.Builder(c, if (alarm) "yakala_alarm" else "yakala_rem")
        else @Suppress("DEPRECATION") Notification.Builder(c)
        b.setSmallIcon(R.drawable.ic_yakala)
            .setContentTitle(if (alarm) "⏰ ALARM" else "⏰ Yakala")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setAutoCancel(true)
        if (alarm) {
            b.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM))
            val ci = Intent(c, ReminderReceiver::class.java).apply {
                putExtra("openClock", true)
                putExtra("hour", i.getIntExtra("hour", 8))
                putExtra("minute", i.getIntExtra("minute", 0))
                putExtra("text", text)
            }
            val cpi = PendingIntent.getBroadcast(c, id + 5000, ci,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            b.addAction(0, "⏰ Saat uygulamasına ekle", cpi)
        }
        nm.notify(id, b.build())
    }
}
