package com.yakala.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.app.AlarmManager
import android.app.PendingIntent
import android.os.IBinder
import android.os.PowerManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.Calendar
import org.json.JSONArray
import org.json.JSONObject

class ListenService : Service(), SensorEventListener {

    private lateinit var sm: SensorManager
    private lateinit var nm: NotificationManager
    private var wakeLock: PowerManager.WakeLock? = null
    private var recognizer: SpeechRecognizer? = null
    private var listening = false
    private var lastShake = 0L
    private var lastG = 1.0
    private val peaks = mutableListOf<Long>()

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, f: Int, s: Int): Int = START_STICKY

    override fun onCreate() {
        super.onCreate()
        nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel("yakala_svc", "Yakala Dinleyici", NotificationManager.IMPORTANCE_LOW)
            )
        }
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(42, notif(Lang.t(lang(), "ready")), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(42, notif(Lang.t(lang(), "ready")))
        }
        sm = getSystemService(SENSOR_SERVICE) as SensorManager
        sm.registerListener(this, sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER), SensorManager.SENSOR_DELAY_GAME)
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "yakala:listen")
        wakeLock?.acquire()
    }

    private fun lang() = getSharedPreferences("yakala", MODE_PRIVATE).getString("lang", "tr")!!

    private fun speechLang() = when (lang()) {
        "en" -> "en-US"; "ru" -> "ru-RU"; "zh" -> "zh-CN"; else -> "tr-TR"
    }

    private fun notif(text: String): Notification {
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, "yakala_svc")
        else @Suppress("DEPRECATION") Notification.Builder(this)
        return b.setSmallIcon(R.drawable.ic_yakala).setContentTitle(text).setOngoing(true).build()
    }

    override fun onSensorChanged(e: SensorEvent) {
        val x = e.values[0]; val y = e.values[1]; val z = e.values[2]
        val g = Math.sqrt((x * x + y * y + z * z).toDouble()) / SensorManager.GRAVITY_EARTH
        val now = System.currentTimeMillis()
        if (g > 2.0 && lastG <= 2.0) {
            if (now - peaks.lastOrNull().orDefault(0) > 80) {
                peaks.removeAll { now - it > 2500 }
                peaks.add(now)
                if (peaks.size >= 4) {
                    peaks.clear()
                    if (listening) {
                        // dinleme sirasinda salla -> kaydi bitir ve KAYDET
                        lastShake = now
                        try { recognizer?.stopListening() } catch (_: Exception) {}
                    } else if (now - lastShake > 6000) {
                        lastShake = now
                        startListening()
                    }
                }
            }
        }
        lastG = g
    }

    private fun Long?.orDefault(d: Long) = this ?: d

    override fun onAccuracyChanged(s: Sensor?, a: Int) {}

    private fun beep(times: Int) {
        Thread {
            try {
                val t = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100)
                repeat(times) {
                    t.startTone(ToneGenerator.TONE_PROP_BEEP, 120)
                    Thread.sleep(180)
                }
                Thread.sleep(300)
                t.release()
            } catch (_: Exception) {}
        }.start()
    }

    private fun startListening() {
        if (listening) return
        listening = true
        beep(1)
        nm.notify(43, notif(Lang.t(lang(), "listen")))
        val sb = StringBuilder()
        try {
            recognizer = SpeechRecognizer.createSpeechRecognizer(this)
            val ri = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, speechLang())
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            }
            recognizer!!.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(p: android.os.Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(v: Float) {}
                override fun onBufferReceived(b: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onError(e: Int) { finish(sb) }
                override fun onResults(r: android.os.Bundle?) {
                    val m = r?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    if (!m.isNullOrEmpty()) sb.append(m[0]).append(" ")
                    finish(sb)
                }
                override fun onPartialResults(r: android.os.Bundle?) {
                    val m = r?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    if (!m.isNullOrEmpty()) nm.notify(43, notif("🎙️ " + m[0]))
                }
                override fun onEvent(p0: Int, p1: android.os.Bundle?) {}
            })
            recognizer!!.startListening(ri)
        } catch (e: Exception) {
            finish(sb)
        }
    }

    private fun stopListening(cancelled: Boolean) {
        if (!listening) return
        listening = false
        try { recognizer?.cancel() } catch (_: Exception) {}
        try { recognizer?.destroy() } catch (_: Exception) {}
        recognizer = null
        if (cancelled) {
            beep(3)
            nm.notify(43, notif(Lang.t(lang(), "cancelled")))
        }
    }

    private fun finish(sb: StringBuilder) {
        if (!listening) return
        listening = false
        try { recognizer?.stopListening() } catch (_: Exception) {}
        try { recognizer?.destroy() } catch (_: Exception) {}
        recognizer = null
        val text = sb.toString().trim()
        if (text.isNotEmpty()) {
            handleCommand(text)
            saveNoteIfPlain(text)
            beep(2)
            nm.notify(43, notif("✅ " + text.take(50)))
        } else {
            nm.notify(43, notif(Lang.t(lang(), "ready")))
        }
    }

    private fun saveNote(text: String) {
        val prefs = getSharedPreferences("yakala", MODE_PRIVATE)
        val o = JSONObject()
        o.put("id", System.currentTimeMillis())
        o.put("type", "text")
        o.put("text", "📳 " + text)
        o.put("pinned", false)
        o.put("createdAt", System.currentTimeMillis())
        val arr = JSONArray()
        arr.put(o)
        val raw = prefs.getString("notes", null)
        if (raw != null) {
            try {
                val old = JSONArray(raw)
                for (i in 0 until old.length()) arr.put(old.get(i))
            } catch (_: Exception) {}
        }
        prefs.edit().putString("notes", arr.toString()).apply()
    }

    override fun onDestroy() {
        try { sm.unregisterListener(this) } catch (_: Exception) {}
        try { wakeLock?.release() } catch (_: Exception) {}
        try { recognizer?.destroy() } catch (_: Exception) {}
        super.onDestroy()
    }

    private var lastCmd = ""

    private fun saveNoteIfPlain(text: String) {
        if (handleCommandMatch(text)) return
        saveNote(text)
    }

    private fun handleCommandMatch(text: String): Boolean {
        val low = normSayi(text.lowercase())
        return low.contains("alarm") || low.contains("hatırlat") ||
            ((low.contains("gönder") || low.contains("yolla") || low.contains("ilet")) &&
             getSharedPreferences("yakala", MODE_PRIVATE).getString("friendsCache", null) != null)
    }

    private fun handleCommand(tr: String): Boolean {
        val low = normSayi(tr.lowercase())
        if (low.contains("alarm")) {
            val t = parseTimeFrom(low) ?: System.currentTimeMillis() + 3600_000
            val cal = Calendar.getInstance().apply { timeInMillis = t }
            val msg = tr.replace("alarm ekle", "", true).replace("alarm kur", "", true).replace("alarm", "", true).trim().ifEmpty { tr }
            val i = Intent(this, ReminderReceiver::class.java).apply {
                putExtra("text", "⏰ " + msg); putExtra("id", msg.hashCode()); putExtra("alarm", true)
                putExtra("hour", cal.get(Calendar.HOUR_OF_DAY)); putExtra("minute", cal.get(Calendar.MINUTE))
            }
            sendBroadcast(i)
            return true
        }
        if (low.contains("hatırlat")) {
            val t = parseTimeFrom(low) ?: System.currentTimeMillis() + 3600_000
            val msg = tr.replace("hatırlatıcı ekle", "", true).replace("hatırlatıcı", "", true).replace("hatırlat", "", true).trim().ifEmpty { tr }
            val prefs = getSharedPreferences("yakala", MODE_PRIVATE)
            val o = JSONObject()
            o.put("id", System.currentTimeMillis()); o.put("type", "text")
            o.put("text", "⏰ " + msg); o.put("pinned", false); o.put("createdAt", System.currentTimeMillis())
            o.put("reminderTime", t)
            val arr = JSONArray(); arr.put(o)
            val raw = prefs.getString("notes", null)
            if (raw != null) { try { val oa = JSONArray(raw); for (x in 0 until oa.length()) arr.put(oa.get(x)) } catch (_: Exception) {} }
            prefs.edit().putString("notes", arr.toString()).apply()
            val id = o.optLong("id").toInt()
            val ri = Intent(this, ReminderReceiver::class.java).apply { putExtra("text", "⏰ " + msg); putExtra("id", id) }
            val pi = PendingIntent.getBroadcast(this, id, ri, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            (getSystemService(ALARM_SERVICE) as AlarmManager).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, pi)
            return true
        }
        if (low.contains("gönder") || low.contains("yolla") || low.contains("ilet")) {
            val prefs = getSharedPreferences("yakala", MODE_PRIVATE)
            val cache = prefs.getString("friendsCache", null) ?: return false
            val o = JSONObject(cache)
            val keys = o.keys()
            while (keys.hasNext()) {
                val uid = keys.next()
                val name = o.optString(uid)
                if (name.length > 2 && low.contains(name.lowercase())) {
                    var msg = tr.replace(name, "", true)
                    msg = msg.replace("gönder", "", true).replace("yolla", "", true).replace("ilet", "", true).trim().trimStart('-', ':', ' ')
                    if (msg.isEmpty()) msg = tr
                    val si = Intent(this, SendReceiver::class.java).apply {
                        putExtra("to", uid); putExtra("text", msg); putExtra("toName", name)
                    }
                    sendBroadcast(si)
                    return true
                }
            }
        }
        return false
    }

    private fun normSayi(t0: String): String {
        var t = t0
        val map = listOf("yirmi üç" to 23, "yirmi iki" to 22, "yirmi bir" to 21, "on dokuz" to 19, "on sekiz" to 18, "on yedi" to 17, "on altı" to 16, "on beş" to 15, "on dört" to 14, "on üç" to 13, "on iki" to 12, "on bir" to 11, "yirmi" to 20, "otuz" to 30, "kırk" to 40, "elli" to 50, "sıfır" to 0, "bir" to 1, "iki" to 2, "üç" to 3, "dört" to 4, "beş" to 5, "altı" to 6, "yedi" to 7, "sekiz" to 8, "dokuz" to 9, "on" to 10)
        for ((w, n) in map) t = t.replace(w, n.toString())
        return t
    }

    private fun parseTimeFrom(lowIn: String): Long? {
        val low = normSayi(lowIn)
        val m = Regex("saat\\s*(\\d{1,2})(?:[.:](\\d{2})|\\s+(\\d{1,2}))?").find(low)
            ?: Regex("(\\d{1,2})[.:](\\d{2})").find(low)
            ?: Regex("(\\d{1,2})\\s+(\\d{2})\\b").find(low)
        if (m != null) {
            val h = m.groupValues[1].toInt()
            val mi = m.groupValues.getOrNull(2)?.takeIf { it.isNotEmpty() }?.toInt()
                ?: m.groupValues.getOrNull(3)?.takeIf { it.isNotEmpty() }?.toInt() ?: 0
            if (h in 0..23 && mi in 0..59) {
                val cal = Calendar.getInstance().apply {
                    set(Calendar.HOUR_OF_DAY, h); set(Calendar.MINUTE, mi)
                    set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                    if (low.contains("yarın")) add(Calendar.DAY_OF_YEAR, 1)
                    else if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
                }
                return cal.timeInMillis
            }
        }
        val mm = Regex("(\\d{1,2})\\s*(dakika|dk)").find(low)
        if (mm != null) return System.currentTimeMillis() + mm.groupValues[1].toLong() * 60_000
        val hh = Regex("(\\d{1,2})\\s*saat").find(low)
        if (hh != null) return System.currentTimeMillis() + hh.groupValues[1].toLong() * 3_600_000
        return null
    }
}
