package com.yakala.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class MainActivity : Activity() {

    private lateinit var prefs: android.content.SharedPreferences
    private val notes = mutableListOf<JSONObject>()
    private val displayItems = mutableListOf<String>()
    private lateinit var adapter: ArrayAdapter<String>
    private lateinit var input: EditText
    private lateinit var statusText: TextView

    private var recorder: MediaRecorder? = null
    private var recognizer: SpeechRecognizer? = null
    private var isRecording = false
    private var currentFile: File? = null
    private var transcript = StringBuilder()
    private var recordStart = 0L
    private var player: MediaPlayer? = null
    private var pendingAction: (() -> Unit)? = null
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("yakala", MODE_PRIVATE)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 60, 40, 40)
            setBackgroundColor(Color.parseColor("#0f172a"))
        }
        val title = TextView(this).apply {
            text = "⚡ Yakala"
            textSize = 30f
            setTextColor(Color.parseColor("#f59e0b"))
        }
        input = EditText(this).apply {
            hint = "Aklına ne geldi?"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
        }
        val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val save = Button(this).apply {
            text = "✓ Kaydet"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#f59e0b"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val voice = Button(this).apply {
            text = "🎤 Sesli"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#1e293b"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        btnRow.addView(save)
        btnRow.addView(voice)
        statusText = TextView(this).apply {
            textSize = 15f
            setTextColor(Color.parseColor("#22c55e"))
            setPadding(0, 16, 0, 16)
        }
        val list = ListView(this)
        layout.addView(title)
        layout.addView(input)
        layout.addView(btnRow)
        layout.addView(statusText)
        layout.addView(list)
        setContentView(layout)

        loadNotes()
        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, displayItems)
        updateList()
        list.adapter = adapter

        save.setOnClickListener {
            val t = input.text.toString().trim()
            if (t.isNotEmpty()) {
                addTextNote(t)
                input.setText("")
            }
        }
        voice.setOnClickListener {
            if (isRecording) stopRecording() else ensurePermission { startRecording() }
        }
        list.setOnItemClickListener { _, _, pos, _ ->
            val n = notes[pos]
            if (n.optString("type") == "voice") playNote(n)
        }
        list.setOnItemLongClickListener { _, _, pos, _ ->
            confirmDelete(pos)
            true
        }

        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            val shared = intent.getStringExtra(Intent.EXTRA_TEXT) ?: ""
            if (shared.isNotBlank()) addTextNote(shared)
        }
    }

    private fun display(n: JSONObject): String =
        if (n.optString("type") == "voice")
            "🎤 " + n.optString("transcript").ifBlank { "Sesli not" }
        else n.optString("text")

    private fun loadNotes() {
        notes.clear()
        val raw = prefs.getString("notes", null) ?: return
        try {
            val a = JSONArray(raw)
            for (i in 0 until a.length()) notes.add(a.getJSONObject(i))
        } catch (_: Exception) {}
    }

    private fun updateList() {
        displayItems.clear()
        displayItems.addAll(notes.map { display(it) })
        adapter.notifyDataSetChanged()
    }

    private fun persist() {
        val arr = JSONArray()
        notes.forEach { arr.put(it) }
        prefs.edit().putString("notes", arr.toString()).apply()
        updateList()
    }

    private fun addTextNote(t: String) {
        val o = JSONObject()
        o.put("id", System.currentTimeMillis())
        o.put("type", "text")
        o.put("text", t)
        o.put("createdAt", System.currentTimeMillis())
        notes.add(0, o)
        persist()
    }

    private fun ensurePermission(action: () -> Unit) {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) action()
        else {
            pendingAction = action
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 100)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            pendingAction?.invoke()
            pendingAction = null
        } else {
            Toast.makeText(this, "Mikrofon izni gerekli", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startRecording() {
        try {
            val dir = File(filesDir, "notes")
            dir.mkdirs()
            currentFile = File(dir, "ses_${System.currentTimeMillis()}.m4a")
            transcript = StringBuilder()

            recorder = MediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setOutputFile(currentFile!!.absolutePath)
                prepare()
                start()
            }

            if (SpeechRecognizer.isRecognitionAvailable(this)) {
                recognizer = SpeechRecognizer.createSpeechRecognizer(this)
                val ri = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "tr-TR")
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                }
                recognizer!!.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(p: Bundle?) {}
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(v: Float) {}
                    override fun onBufferReceived(b: ByteArray?) {}
                    override fun onEndOfSpeech() {}
                    override fun onError(e: Int) {}
                    override fun onResults(r: Bundle?) {
                        val m = r?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        if (!m.isNullOrEmpty()) transcript.append(m[0]).append(" ")
                    }
                    override fun onPartialResults(r: Bundle?) {
                        val m = r?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        if (!m.isNullOrEmpty()) statusText.text = "🎙️ " + (transcript.toString() + m[0]).trim()
                    }
                    override fun onEvent(p0: Int, p1: Bundle?) {}
                })
                recognizer!!.startListening(ri)
            }

            isRecording = true
            recordStart = System.currentTimeMillis()
            statusText.text = "🎙️ Kaydediliyor... konuş"
            tick()
        } catch (e: Exception) {
            Toast.makeText(this, "Kayıt başlatılamadı: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun tick() {
        if (!isRecording) return
        val s = (System.currentTimeMillis() - recordStart) / 1000
        statusText.text = "🎙️ ${String.format("%02d:%02d", s / 60, s % 60)} " + transcript.toString().trim()
        handler.postDelayed({ tick() }, 1000)
    }

    private fun stopRecording() {
        isRecording = false
        try { recorder?.stop() } catch (_: Exception) {}
        recorder?.release()
        recorder = null
        handler.postDelayed({
            val tr = transcript.toString().trim()
            recognizer?.destroy()
            recognizer = null
            val f = currentFile
            if (f != null && f.exists() && f.length() > 0) {
                val o = JSONObject()
                o.put("id", System.currentTimeMillis())
                o.put("type", "voice")
                o.put("audioPath", f.absolutePath)
                o.put("duration", (System.currentTimeMillis() - recordStart) / 1000)
                o.put("transcript", tr)
                o.put("createdAt", System.currentTimeMillis())
                notes.add(0, o)
                persist()
            }
            statusText.text = if (tr.isNotEmpty()) "📝 $tr" else ""
        }, 600)
    }

    private fun playNote(n: JSONObject) {
        try {
            player?.release()
            player = MediaPlayer()
            player!!.setDataSource(n.optString("audioPath"))
            player!!.prepare()
            player!!.start()
            Toast.makeText(this, "🔊 Çalıyor", Toast.LENGTH_SHORT).show()
            player!!.setOnCompletionListener {
                player?.release()
                player = null
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Oynatılamadı", Toast.LENGTH_SHORT).show()
        }
    }

    private fun confirmDelete(pos: Int) {
        AlertDialog.Builder(this)
            .setTitle("Notu sil")
            .setMessage(display(notes[pos]))
            .setPositiveButton("Sil") { _, _ ->
                val n = notes[pos]
                if (n.optString("type") == "voice") File(n.optString("audioPath")).delete()
                notes.removeAt(pos)
                persist()
            }
            .setNegativeButton("Vazgeç", null)
            .show()
    }
}
