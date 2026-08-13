package com.yakala.app

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.TimePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.ConnectivityManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.text.InputType
import android.text.TextWatcher
import android.util.Base64
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    private lateinit var prefs: android.content.SharedPreferences
    private val notes = mutableListOf<JSONObject>()
    private val visibleNotes = mutableListOf<JSONObject>()
    private lateinit var adapter: NoteAdapter
    private lateinit var input: EditText
    private lateinit var searchInput: EditText
    private lateinit var statusText: TextView
    private lateinit var voiceBtn: Button
    private lateinit var textBtn: Button

    private var recorder: MediaRecorder? = null
    private var recognizer: SpeechRecognizer? = null
    private var mode = 0
    private var currentFile: File? = null
    private var transcript = StringBuilder()
    private var recordStart = 0L
    private var player: MediaPlayer? = null
    private var pendingAction: (() -> Unit)? = null
    private var query = ""
    private val handler = Handler(Looper.getMainLooper())

    private val REQ_EXPORT = 71
    private val REQ_IMPORT = 72

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun roundBg(color: Int, radiusDp: Int = 14): GradientDrawable =
        GradientDrawable().apply { cornerRadius = dp(radiusDp).toFloat(); setColor(color) }

    private val isDark get() = prefs.getBoolean("dark", false)
    private val BG get() = Color.parseColor(if (isDark) "#141a22" else "#f6f7f9")
    private val SURFACE get() = Color.parseColor(if (isDark) "#1f2937" else "#ffffff")
    private val TITLE get() = Color.parseColor(if (isDark) "#e8b464" else "#c08a3e")
    private val TXT get() = Color.parseColor(if (isDark) "#f3f4f6" else "#1f2937")
    private val META get() = Color.parseColor(if (isDark) "#9aa5b1" else "#8a94a0")
    private val BTN_SOFT get() = Color.parseColor(if (isDark) "#3d4a5c" else "#64748b")
    private val AMBER_SOFT get() = Color.parseColor(if (isDark) "#d9a441" else "#e2a750")
    private val RED_SOFT get() = Color.parseColor("#d97b7b")
    private val GREEN_SOFT get() = Color.parseColor(if (isDark) "#7fd7a4" else "#4c9a6b")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("yakala", MODE_PRIVATE)

        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel("yakala_rem", "Hatırlatmalar", NotificationManager.IMPORTANCE_HIGH)
            )
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(10))
            setBackgroundColor(BG)
        }
        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        val title = TextView(this).apply {
            text = "⚡ Yakala"
            textSize = 28f
            setTextColor(TITLE)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val searchToggle = TextView(this).apply {
            text = "🔍"
            textSize = 22f
            setPadding(dp(10), dp(6), dp(10), dp(6))
            setOnClickListener {
                if (searchInput.visibility == View.VISIBLE) {
                    searchInput.visibility = View.GONE
                    searchInput.setText("")
                } else {
                    searchInput.visibility = View.VISIBLE
                    searchInput.requestFocus()
                    val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                    imm.showSoftInput(searchInput, InputMethodManager.SHOW_IMPLICIT)
                }
            }
        }
        val settingsBtn = TextView(this).apply {
            text = "⚙️"
            textSize = 24f
            setPadding(dp(10), dp(6), 0, dp(6))
            setOnClickListener { settingsDialog() }
        }
        titleRow.addView(title)
        titleRow.addView(searchToggle)
        titleRow.addView(settingsBtn)

        searchInput = EditText(this).apply {
            hint = "🔍 Ara..."
            setTextColor(TXT)
            setHintTextColor(META)
            background = roundBg(SURFACE)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            visibility = View.GONE
        }
        input = EditText(this).apply {
            hint = "Aklına ne geldi?\n(ilk satır başlık olur, zorunlu değil)"
            setTextColor(TXT)
            setHintTextColor(META)
            background = roundBg(SURFACE)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            minLines = 3
            maxLines = 6
            gravity = android.view.Gravity.TOP
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val lp = { LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = dp(5); marginEnd = dp(5)
        } }
        val save = Button(this).apply {
            text = "✓ Kaydet"
            setTextColor(Color.WHITE)
            background = roundBg(AMBER_SOFT)
            layoutParams = lp()
        }
        voiceBtn = Button(this).apply {
            text = "🎤 Ses"
            setTextColor(Color.WHITE)
            background = roundBg(BTN_SOFT)
            layoutParams = lp()
        }
        textBtn = Button(this).apply {
            text = "🗣️ Metin"
            setTextColor(Color.WHITE)
            background = roundBg(BTN_SOFT)
            layoutParams = lp()
        }
        row.addView(save)
        row.addView(voiceBtn)
        row.addView(textBtn)
        statusText = TextView(this).apply {
            textSize = 14f
            setTextColor(GREEN_SOFT)
            setPadding(0, dp(10), 0, dp(6))
        }
        val list = ListView(this).apply {
            divider = null
            dividerHeight = dp(12)
        }
        layout.addView(titleRow)
        layout.addView(searchInput)
        layout.addView(input)
        layout.addView(row)
        layout.addView(statusText)
        layout.addView(list)
        setContentView(layout)

        loadNotes()
        adapter = NoteAdapter()
        updateList()
        list.adapter = adapter

        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                query = s.toString()
                updateList()
            }
        })

        save.setOnClickListener {
            val t = input.text.toString().trim()
            if (t.isNotEmpty()) { addTextNote(t); input.setText("") }
        }
        voiceBtn.setOnClickListener {
            when (mode) {
                1 -> stopAudio()
                2 -> Toast.makeText(this, "Önce metin kaydını bitir", Toast.LENGTH_SHORT).show()
                else -> ensurePermission { startAudio() }
            }
        }
        textBtn.setOnClickListener {
            when (mode) {
                2 -> stopText()
                1 -> Toast.makeText(this, "Önce ses kaydını bitir", Toast.LENGTH_SHORT).show()
                else -> ensurePermission { startText() }
            }
        }
        list.setOnItemClickListener { _, _, pos, _ ->
            val n = visibleNotes[pos]
            if (n.optString("type") == "voice") playNote(n) else editDialog(n)
        }
        list.setOnItemLongClickListener { _, _, pos, _ -> optionsDialog(pos); true }

        handleIncomingIntent(intent)
        handleQuick(intent)
    }

    private inner class NoteAdapter : BaseAdapter() {
        override fun getCount() = visibleNotes.size
        override fun getItem(p: Int) = visibleNotes[p]
        override fun getItemId(p: Int) = p.toLong()

        override fun getView(p: Int, cv: View?, parent: ViewGroup): View {
            val n = visibleNotes[p]
            val isVoice = n.optString("type") == "voice"
            val src = if (isVoice) n.optString("transcript") else n.optString("text")
            val tags = Regex("#[\\p{L}0-9_]+").findAll(src).map { it.value }.toList()

            val card = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(18), dp(14), dp(18), dp(12))
                val gd = GradientDrawable()
                gd.cornerRadius = dp(18).toFloat()
                gd.setColor(SURFACE)
                if (!isDark) gd.setStroke(2, Color.parseColor("#e8eaee"))
                background = gd
                layoutParams = AbsListView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }

            val lines = src.split("\n", limit = 2)
            if (!isVoice && lines.size == 2 && lines[0].isNotBlank()) {
                val t = TextView(this@MainActivity).apply {
                    text = lines[0]
                    setTextColor(TXT)
                    textSize = 16f
                    paint.isFakeBoldText = true
                }
                val b = TextView(this@MainActivity).apply {
                    text = lines[1]
                    setTextColor(META)
                    textSize = 14f
                    maxLines = 3
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setPadding(0, dp(4), 0, 0)
                }
                card.addView(t)
                card.addView(b)
            } else {
                val c = TextView(this@MainActivity).apply {
                    text = (if (isVoice) "🎤 " else "") + src.ifBlank { "Sesli not" }
                    setTextColor(TXT)
                    textSize = 15f
                    maxLines = 3
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }
                card.addView(c)
            }

            val meta = TextView(this@MainActivity).apply {
                val badges = buildString {
                    if (n.optBoolean("pinned")) append("📌 ")
                    if (n.optLong("reminderTime") > System.currentTimeMillis()) append("⏰ ")
                    if (isVoice && n.optLong("duration") > 0) append("🎤 ${fmtDur(n.optLong("duration"))}  ")
                    tags.forEach { append("$it  ") }
                    append("• ${fmtDate(n.optLong("createdAt"))}")
                }
                text = badges
                setTextColor(if (tags.isNotEmpty()) AMBER_SOFT else META)
                textSize = 12f
                setPadding(0, dp(6), 0, 0)
            }
            card.addView(meta)
            return card
        }
    }

    private fun fmtDur(s: Long) = String.format("%02d:%02d", s / 60, s % 60)

    private fun fmtDate(ts: Long): String {
        if (ts <= 0) return ""
        val now = Calendar.getInstance()
        val d = Calendar.getInstance().apply { timeInMillis = ts }
        val sameDay = d.get(Calendar.DATE) == now.get(Calendar.DATE) &&
            d.get(Calendar.MONTH) == now.get(Calendar.MONTH) &&
            d.get(Calendar.YEAR) == now.get(Calendar.YEAR)
        val pattern = if (sameDay) "HH:mm" else "d MMM HH:mm"
        return SimpleDateFormat(pattern, Locale("tr")).format(Date(ts))
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
        handleQuick(intent)
    }

    private fun settingsDialog() {
        val items = arrayOf(
            if (isDark) "☀️ Açık temaya geç" else "🌙 Karanlık temaya geç",
            "📤 Yedekle (JSON)",
            "📥 Geri yükle",
            "ℹ️ Yakala v1.0"
        )
        AlertDialog.Builder(this)
            .setTitle("⚙️ Ayarlar")
            .setItems(items) { _, w ->
                when (w) {
                    0 -> { prefs.edit().putBoolean("dark", !isDark).apply(); recreate() }
                    1 -> exportNotes()
                    2 -> importNotes()
                    3 -> Toast.makeText(this, "⚡ Yakala v1.0", Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }

    private fun exportNotes() {
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
            putExtra(Intent.EXTRA_TITLE, "yakala-yedek.json")
        }
        startActivityForResult(i, REQ_EXPORT)
    }

    private fun importNotes() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
        }
        startActivityForResult(i, REQ_IMPORT)
    }

    private fun exportJson(): String {
        val arr = JSONArray()
        for (n in notes) {
            val c = JSONObject(n.toString())
            if (n.optString("type") == "voice") {
                val f = File(n.optString("audioPath"))
                if (f.exists()) c.put("audioData", Base64.encodeToString(f.readBytes(), Base64.DEFAULT))
            }
            arr.put(c)
        }
        return arr.toString()
    }

    private fun importJson(text: String) {
        val arr = JSONArray(text)
        var count = 0
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (o.optString("type") == "voice" && o.has("audioData")) {
                val bytes = Base64.decode(o.getString("audioData"), Base64.DEFAULT)
                val dir = File(filesDir, "notes"); dir.mkdirs()
                val f = File(dir, "ses_${System.currentTimeMillis()}_$i.m4a")
                f.writeBytes(bytes)
                o.put("audioPath", f.absolutePath)
                o.remove("audioData")
            }
            notes.add(0, o)
            count++
        }
        persist()
        Toast.makeText(this, "✅ $count not geri yüklendi", Toast.LENGTH_SHORT).show()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data == null) return
        val uri = data.data ?: return
        when (requestCode) {
            REQ_EXPORT -> {
                contentResolver.openOutputStream(uri)?.use { it.write(exportJson().toByteArray()) }
                Toast.makeText(this, "✅ Yedek kaydedildi", Toast.LENGTH_SHORT).show()
            }
            REQ_IMPORT -> {
                val text = contentResolver.openInputStream(uri)?.use { String(it.readBytes()) } ?: return
                try { importJson(text) } catch (e: Exception) {
                    Toast.makeText(this, "Dosya okunamadı: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun handleQuick(i: Intent?) {
        when (i?.getStringExtra("quick")) {
            "audio" -> ensurePermission { startAudio() }
            "stt" -> ensurePermission { startText() }
            "text" -> {
                input.requestFocus()
                val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
            }
        }
    }

    private fun handleIncomingIntent(i: Intent?) {
        if (i?.action == Intent.ACTION_SEND && i.type == "text/plain") {
            val shared = i.getStringExtra(Intent.EXTRA_TEXT) ?: ""
            if (shared.isNotBlank()) {
                addTextNote(shared)
                Toast.makeText(this, "✅ Paylaşılan metin kaydedildi", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun display(n: JSONObject): String {
        val base = if (n.optString("type") == "voice")
            "🎤 " + n.optString("transcript").ifBlank { "Sesli not" }
        else "📝 " + n.optString("text").replace("\n", " — ")
        val rem = if (n.optLong("reminderTime") > System.currentTimeMillis()) " ⏰" else ""
        return base + rem
    }

    private fun loadNotes() {
        notes.clear()
        val raw = prefs.getString("notes", null) ?: return
        try {
            val a = JSONArray(raw)
            for (i in 0 until a.length()) notes.add(a.getJSONObject(i))
        } catch (_: Exception) {}
    }

    private fun updateList() {
        visibleNotes.clear()
        val q = query.trim().lowercase()
        val filtered = notes.filter { q.isEmpty() || display(it).lowercase().contains(q) }
        val sorted = filtered.sortedWith(
            compareByDescending<JSONObject> { it.optBoolean("pinned") }
                .thenByDescending { it.optLong("createdAt") }
        )
        visibleNotes.addAll(sorted)
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
        o.put("pinned", false)
        o.put("createdAt", System.currentTimeMillis())
        notes.add(0, o)
        persist()
    }

    private fun editDialog(n: JSONObject) {
        val et = EditText(this).apply {
            setText(n.optString("text"))
            setTextColor(TXT)
            minLines = 3
            gravity = android.view.Gravity.TOP
        }
        AlertDialog.Builder(this)
            .setTitle("✏️ Notu düzenle")
            .setView(et)
            .setPositiveButton("Kaydet") { _, _ ->
                n.put("text", et.text.toString().trim())
                persist()
            }
            .setNegativeButton("Vazgeç", null)
            .show()
    }

    private fun optionsDialog(pos: Int) {
        val n = visibleNotes[pos]
        val isVoice = n.optString("type") == "voice"
        val pinned = n.optBoolean("pinned")
        val items = mutableListOf<String>()
        if (!isVoice) items.add("✏️ Düzenle")
        if (isVoice) items.add("🔊 Çal")
        items.add("⏰ Hatırlat")
        items.add(if (pinned) "📌 Sabitlemeyi kaldır" else "📌 Sabitle")
        items.add("🗑️ Sil")
        AlertDialog.Builder(this)
            .setTitle(display(n))
            .setItems(items.toTypedArray()) { _, which ->
                when (items[which]) {
                    "✏️ Düzenle" -> editDialog(n)
                    "🔊 Çal" -> playNote(n)
                    "⏰ Hatırlat" -> scheduleReminder(n)
                    "📌 Sabitle" -> { n.put("pinned", true); persist() }
                    "📌 Sabitlemeyi kaldır" -> { n.put("pinned", false); persist() }
                    "🗑️ Sil" -> deleteNote(n)
                }
            }
            .show()
    }

    private fun scheduleReminder(n: JSONObject) {
        val options = arrayOf(
            "⚡ 1 dakika sonra (test)",
            "⏱ 1 saat sonra",
            "🌅 09:00",
            "☀️ 13:00",
            "🌆 20:00",
            "📅 Tarih ve saat seç",
            "🗑 Hatırlatmayı kaldır"
        )
        AlertDialog.Builder(this)
            .setTitle("⏰ Ne zaman hatırlatayım?")
            .setItems(options) { _, w ->
                when (w) {
                    6 -> {
                        n.remove("reminderTime"); persist(); cancelAlarm(n)
                        Toast.makeText(this, "Hatırlatma kaldırıldı", Toast.LENGTH_SHORT).show()
                        return@setItems
                    }
                    5 -> { pickCustom(n); return@setItems }
                }
                val cal = Calendar.getInstance()
                when (w) {
                    0 -> cal.timeInMillis = System.currentTimeMillis() + 60_000
                    1 -> cal.timeInMillis = System.currentTimeMillis() + 3_600_000
                    2 -> setClassic(cal, 9)
                    3 -> setClassic(cal, 13)
                    4 -> setClassic(cal, 20)
                }
                n.put("reminderTime", cal.timeInMillis)
                persist()
                setAlarm(n)
                Toast.makeText(this, "⏰ Hatırlatma kuruldu", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun setClassic(cal: Calendar, hour: Int) {
        cal.set(Calendar.HOUR_OF_DAY, hour)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        if (cal.timeInMillis <= System.currentTimeMillis()) cal.add(Calendar.DAY_OF_YEAR, 1)
    }

    private fun pickCustom(n: JSONObject) {
        val cal = Calendar.getInstance()
        DatePickerDialog(this, { _, y, m, d ->
            cal.set(Calendar.YEAR, y); cal.set(Calendar.MONTH, m); cal.set(Calendar.DAY_OF_MONTH, d)
            TimePickerDialog(this, { _, hh, mm ->
                cal.set(Calendar.HOUR_OF_DAY, hh)
                cal.set(Calendar.MINUTE, mm)
                cal.set(Calendar.SECOND, 0)
                cal.set(Calendar.MILLISECOND, 0)
                if (cal.timeInMillis <= System.currentTimeMillis()) {
                    Toast.makeText(this, "Geçmiş zaman seçilemez", Toast.LENGTH_SHORT).show()
                    return@TimePickerDialog
                }
                n.put("reminderTime", cal.timeInMillis)
                persist()
                setAlarm(n)
                Toast.makeText(this, "⏰ Hatırlatma kuruldu", Toast.LENGTH_SHORT).show()
            }, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), true).show()
        }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
    }

    private fun setAlarm(n: JSONObject) {
        val time = n.optLong("reminderTime")
        if (time <= 0) return
        val id = n.optLong("id").toInt()
        val i = Intent(this, ReminderReceiver::class.java).apply {
            putExtra("text", display(n))
            putExtra("id", id)
        }
        val pi = PendingIntent.getBroadcast(
            this, id, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val am = getSystemService(ALARM_SERVICE) as AlarmManager
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, time, pi)
    }

    private fun cancelAlarm(n: JSONObject) {
        val id = n.optLong("id").toInt()
        val i = Intent(this, ReminderReceiver::class.java)
        val pi = PendingIntent.getBroadcast(
            this, id, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        (getSystemService(ALARM_SERVICE) as AlarmManager).cancel(pi)
    }

    private fun deleteNote(n: JSONObject) {
        cancelAlarm(n)
        if (n.optString("type") == "voice") File(n.optString("audioPath")).delete()
        notes.remove(n)
        persist()
    }

    private fun hasInternet(): Boolean {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = cm.activeNetwork ?: return false
        return cm.getNetworkCapabilities(net)?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }

    private fun ensurePermission(action: () -> Unit) {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) action()
        else { pendingAction = action; requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 100) }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            pendingAction?.invoke(); pendingAction = null
        } else if (requestCode != 101) {
            Toast.makeText(this, "Mikrofon izni gerekli", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startAudio() {
        try {
            val dir = File(filesDir, "notes"); dir.mkdirs()
            currentFile = File(dir, "ses_${System.currentTimeMillis()}.m4a")
            recorder = MediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setOutputFile(currentFile!!.absolutePath)
                prepare(); start()
            }
            mode = 1
            recordStart = System.currentTimeMillis()
            voiceBtn.text = "■ Bitir"
            voiceBtn.background = roundBg(RED_SOFT)
            statusText.text = "🔴 Kaydediliyor... bitirmek için ■ Bitir"
            tick()
        } catch (e: Exception) {
            Toast.makeText(this, "Kayıt hatası: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun tick() {
        if (mode != 1) return
        val s = (System.currentTimeMillis() - recordStart) / 1000
        statusText.text = "🔴 ${fmtDur(s)} — bitirmek için ■ Bitir"
        handler.postDelayed({ tick() }, 1000)
    }

    private fun stopAudio() {
        mode = 0
        try { recorder?.stop() } catch (_: Exception) {}
        recorder?.release(); recorder = null
        voiceBtn.text = "🎤 Ses"
        voiceBtn.background = roundBg(BTN_SOFT)
        val f = currentFile
        if (f != null && f.exists() && f.length() > 0) {
            val o = JSONObject()
            o.put("id", System.currentTimeMillis())
            o.put("type", "voice")
            o.put("audioPath", f.absolutePath)
            o.put("duration", (System.currentTimeMillis() - recordStart) / 1000)
            o.put("transcript", "")
            o.put("pinned", false)
            o.put("createdAt", System.currentTimeMillis())
            notes.add(0, o)
            persist()
            statusText.text = "✅ Sesli not kaydedildi"
        } else statusText.text = ""
    }

    private fun startText() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Toast.makeText(this, "Cihazda konuşma tanıma yok", Toast.LENGTH_LONG).show(); return
        }
        if (!hasInternet()) Toast.makeText(this, "⚠️ İnternet yok, çeviri çalışmaz", Toast.LENGTH_SHORT).show()
        transcript = StringBuilder()
        recognizer = SpeechRecognizer.createSpeechRecognizer(this)
        val ri = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "tr-TR")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }
        recognizer!!.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(p: Bundle?) { statusText.text = "🎙️ Dinliyorum, konuş..." }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(v: Float) {}
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(e: Int) {
                val msg = when (e) {
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "İnternet gerekli"
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Ses algılanmadı"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "İzin eksik"
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Meşgul"
                    else -> "Hata kodu: $e"
                }
                statusText.text = "⚠️ $msg"
            }
            override fun onResults(r: Bundle?) {
                val m = r?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!m.isNullOrEmpty()) { transcript.append(m[0]).append(" "); statusText.text = "📝 " + transcript.toString().trim() }
            }
            override fun onPartialResults(r: Bundle?) {
                val m = r?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!m.isNullOrEmpty()) statusText.text = "🎙️ " + (transcript.toString() + m[0]).trim()
            }
            override fun onEvent(p0: Int, p1: Bundle?) {}
        })
        recognizer!!.startListening(ri)
        mode = 2
        textBtn.text = "■ Bitir"
        textBtn.background = roundBg(RED_SOFT)
        statusText.text = "🎙️ Dinliyorum, konuş..."
    }

    private fun stopText() {
        mode = 0
        recognizer?.destroy(); recognizer = null
        textBtn.text = "🗣️ Metin"
        textBtn.background = roundBg(BTN_SOFT)
        val tr = transcript.toString().trim()
        if (tr.isNotEmpty()) {
            addTextNote(tr)
            statusText.text = "✅ Not kaydedildi"
        } else {
            statusText.text = "⚠️ Konuşma algılanmadı"
        }
    }

    private fun playNote(n: JSONObject) {
        try {
            player?.release()
            player = MediaPlayer()
            player!!.setDataSource(n.optString("audioPath"))
            player!!.prepare(); player!!.start()
            Toast.makeText(this, "🔊 Çalıyor", Toast.LENGTH_SHORT).show()
            player!!.setOnCompletionListener { player?.release(); player = null }
        } catch (e: Exception) {
            Toast.makeText(this, "Oynatılamadı", Toast.LENGTH_SHORT).show()
        }
    }
}
