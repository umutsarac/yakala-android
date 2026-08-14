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
import android.os.IBinder
import android.os.PowerManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
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
            saveNote(text)
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
}
