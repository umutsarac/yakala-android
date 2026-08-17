package com.yakala.app

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONArray
import org.json.JSONObject

class SyncService : Service() {
    private val API_KEY = "AIzaSyDL4NWpuudvTu-ggKEX_pw_sVkwkGUlOzA"
    private val DEFAULT_SERVER = "https://yakala-7ba1c-default-rtdb.europe-west1.firebasedatabase.app"
    private val handler = Handler(Looper.getMainLooper())
    private val seen = mutableSetOf<String>()
    private var wake: PowerManager.WakeLock? = null

    private val tick = object : Runnable {
        override fun run() {
            Thread { doSync() }.start()
            handler.postDelayed(this, 15000)
        }
    }

    override fun onBind(i: Intent?): android.os.IBinder? = null
    override fun onStartCommand(i: Intent?, f: Int, s: Int) = START_STICKY

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel("yakala_sync", "Eşitleme", NotificationManager.IMPORTANCE_LOW))
            nm.createNotificationChannel(NotificationChannel("yakala_rem", "Hatırlatmalar", NotificationManager.IMPORTANCE_HIGH))
            val ch = NotificationChannel("yakala_alarm", "Alarmlar", NotificationManager.IMPORTANCE_HIGH)
            ch.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM), null)
            nm.createNotificationChannel(ch)
        }
        val n = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, "yakala_sync")
        else @Suppress("DEPRECATION") Notification.Builder(this)
        n.setSmallIcon(R.drawable.ic_yakala).setContentTitle("🔄 Yakala eşitleme aktif").setOngoing(true)
        if (Build.VERSION.SDK_INT >= 29) startForeground(44, n.build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(44, n.build())
        loadSeen()
        handler.post(tick)
        wake = (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "yakala:sync")
        wake?.acquire()
    }

    private fun loadSeen() {
        val raw = getSharedPreferences("yakala", MODE_PRIVATE).getString("seenInbox", null) ?: return
        try { val a = JSONArray(raw); for (i in 0 until a.length()) seen.add(a.getString(i)) } catch (_: Exception) {}
    }

    private fun saveSeen() {
        val a = JSONArray(); seen.forEach { a.put(it) }
        getSharedPreferences("yakala", MODE_PRIVATE).edit().putString("seenInbox", a.toString()).apply()
    }

    private fun doSync() {
        try {
            val prefs = getSharedPreferences("yakala", MODE_PRIVATE)
            ensureAuth(prefs)
            val tok = prefs.getString("idToken", null) ?: return
            val me = prefs.getString("uid", null) ?: return
            val raw = get("$DEFAULT_SERVER/users/$me/inbox.json?auth=$tok") ?: return
            if (raw == "null") return
            val o = JSONObject(raw)
            val keys = o.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val v = o.getJSONObject(key)
                if (v.optLong("activateAt") > System.currentTimeMillis()) continue
                if (key in seen) continue
                seen.add(key); saveSeen()
                v.put("id", key)
                appendFNote(prefs, v)
                notifyNote(v)
                val remAt = v.optLong("reminderAt")
                if (remAt > System.currentTimeMillis()) scheduleRem(v, remAt)
                del("$DEFAULT_SERVER/users/$me/inbox/$key.json?auth=$tok")
            }
        } catch (_: Exception) {}
    }

    private fun appendFNote(prefs: SharedPreferences, v: JSONObject) {
        val arr = JSONArray()
        arr.put(v)
        val raw = prefs.getString("fnotes", null)
        if (raw != null) { try { val oa = JSONArray(raw); for (i in 0 until oa.length()) arr.put(oa.get(i)) } catch (_: Exception) {} }
        prefs.edit().putString("fnotes", arr.toString()).apply()
    }

    private fun notifyNote(v: JSONObject) {
        val id = v.optString("id").hashCode() + 1000
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, "yakala_rem")
        else @Suppress("DEPRECATION") Notification.Builder(this)
        b.setSmallIcon(R.drawable.ic_yakala)
            .setContentTitle("📬 " + v.optString("fromName"))
            .setContentText(v.optString("text"))
            .setStyle(Notification.BigTextStyle().bigText(v.optString("text")))
            .setAutoCancel(true)
        nm.notify(id, b.build())
    }

    private fun scheduleRem(v: JSONObject, at: Long) {
        val id = v.optString("id").hashCode()
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = at }
        val i = Intent(this, ReminderReceiver::class.java).apply {
            putExtra("text", "🔔 " + v.optString("fromName") + ": " + v.optString("text"))
            putExtra("id", id)
            putExtra("alarm", v.optString("reminderKind") == "alarm")
            putExtra("hour", cal.get(java.util.Calendar.HOUR_OF_DAY))
            putExtra("minute", cal.get(java.util.Calendar.MINUTE))
        }
        val pi = PendingIntent.getBroadcast(this, id, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        (getSystemService(ALARM_SERVICE) as AlarmManager).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
    }

    private fun ensureAuth(prefs: SharedPreferences) {
        val tok = prefs.getString("idToken", null)
        val exp = prefs.getLong("tokExp", 0)
        if (tok != null && System.currentTimeMillis() < exp - 120_000) return
        val refresh = prefs.getString("refreshToken", null)
        var raw: String? = null
        if (refresh != null) raw = post("https://securetoken.googleapis.com/v1/token?key=$API_KEY",
            "{\"grant_type\":\"refresh_token\",\"refresh_token\":\"$refresh\"}")
        if (raw == null) raw = post("https://identitytoolkit.googleapis.com/v1/accounts:signUp?key=$API_KEY", "{}")
        if (raw != null) {
            try {
                val o = JSONObject(raw)
                val t = o.optString("idToken", o.optString("id_token"))
                val u = o.optString("localId", o.optString("user_id"))
                val r = o.optString("refreshToken", o.optString("refresh_token"))
                if (t.isNotEmpty() && u.isNotEmpty()) {
                    prefs.edit().putString("idToken", t).putString("uid", u)
                        .putString("refreshToken", r)
                        .putLong("tokExp", System.currentTimeMillis() + 3600_000).apply()
                }
            } catch (_: Exception) {}
        }
    }

    private fun get(url: String): String? = try {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 6000; c.readTimeout = 6000
        var s: String? = if (c.responseCode in 200..299) c.inputStream.readBytes().toString(Charsets.UTF_8) else null
        if (s != null && s.trimStart().startsWith("<")) s = null
        s
    } catch (e: Exception) { null }

    private fun del(url: String) = try {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = "DELETE"; c.connectTimeout = 6000; c.readTimeout = 6000
        c.responseCode
    } catch (e: Exception) { 0 }

    private fun post(url: String, body: String): String? = try {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.doOutput = true
        c.connectTimeout = 6000; c.readTimeout = 6000
        c.setRequestProperty("Content-Type", "application/json")
        c.outputStream.use { it.write(body.toByteArray()) }
        if (c.responseCode in 200..299) c.inputStream.readBytes().toString(Charsets.UTF_8) else null
    } catch (e: Exception) { null }

    override fun onDestroy() {
        try { wake?.release() } catch (_: Exception) {}
        super.onDestroy()
    }
}
