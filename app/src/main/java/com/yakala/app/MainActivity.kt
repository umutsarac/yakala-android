package com.yakala.app

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.Dialog
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.TimePickerDialog
import android.content.ClipData
import android.content.ClipboardManager
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
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    private val API_KEY = "AIzaSyDL4NWpuudvTu-ggKEX_pw_sVkwkGUlOzA"
    private val DEFAULT_SERVER = "https://yakala-7ba1c-default-rtdb.europe-west1.firebasedatabase.app"

    private lateinit var prefs: android.content.SharedPreferences
    private val notes = mutableListOf<JSONObject>()
    private val visibleNotes = mutableListOf<JSONObject>()
    private lateinit var adapter: NoteAdapter
    private val fNotes = mutableListOf<JSONObject>()
    private val sentNotes = mutableListOf<JSONObject>()
    private val fDisplay = mutableListOf<String>()
    private lateinit var fAdapter: android.widget.ArrayAdapter<String>
    private lateinit var input: EditText
    private lateinit var searchInput: EditText
    private lateinit var statusText: TextView
    private lateinit var voiceBtn: TextView
    private lateinit var textBtn: TextView
    private lateinit var navRow: LinearLayout
    private lateinit var adSlot: android.widget.FrameLayout
    private var favOnly = false
    private var tab = "home"
    private var fullMine = false
    private lateinit var inputCard: LinearLayout
    private lateinit var btnRow: LinearLayout
    private lateinit var listMine: ListView
    private lateinit var listFriends: ListView
    private lateinit var headerMine: LinearLayout
    private lateinit var headerFriends: LinearLayout
    private lateinit var mineToggle: TextView
    private lateinit var mineTitle: TextView
    private lateinit var mineDelete: TextView
    private lateinit var frTitle: TextView
    private lateinit var frToggle: TextView
    private lateinit var frDelete: TextView
    private lateinit var friendsPanel: android.widget.ScrollView
    private val storeArc = mutableListOf<JSONObject>()
    private lateinit var arcAdapter: NoteAdapter
    private val expandedFriends = mutableSetOf<String>()
    private var fullFr = false

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

    private val myFriends = mutableMapOf<String, JSONObject>()
    private val myGrants = mutableMapOf<String, String>()
    private val seenInbox = HashSet<String>()
    private val handledRequests = HashSet<String>()
    private val scheduledSends = mutableListOf<JSONObject>()
    private val prefsListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "notes") { loadNotes(); updateList() }
    }

    private val REQ_EXPORT = 71
    private val REQ_IMPORT = 72
    private val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun roundBg(color: Int, radiusDp: Int = 14) =
        GradientDrawable().apply { cornerRadius = dp(radiusDp).toFloat(); setColor(color) }

    private val isDark get() = prefs.getBoolean("dark", true)
    private val BG get() = Color.parseColor(if (isDark) "#0b1020" else "#f6f7f9")
    private val SURFACE2 get() = Color.parseColor(if (isDark) "#1b2240" else "#eef0f8")
    private val BORDER get() = Color.parseColor(if (isDark) "#252c4d" else "#e5e7f0")
    private val ACCENT get() = Color.parseColor("#6d5df6")
    private fun grad(colors: IntArray, rDp: Int) = android.graphics.drawable.GradientDrawable(
        android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT, colors
    ).apply { cornerRadius = dp(rDp).toFloat() }
    private fun tint(hex: Int, a: Int) = Color.argb(a, Color.red(hex), Color.green(hex), Color.blue(hex))
    private val SURFACE get() = Color.parseColor(if (isDark) "#151b33" else "#ffffff")
    private val TITLE get() = Color.parseColor(if (isDark) "#e8b464" else "#c08a3e")
    private val TXT get() = Color.parseColor(if (isDark) "#eef0ff" else "#1f2937")
    private val META get() = Color.parseColor(if (isDark) "#8f96c0" else "#8a94a0")
    private val BTN_SOFT get() = Color.parseColor(if (isDark) "#3d4a5c" else "#64748b")
    private val AMBER_SOFT get() = Color.parseColor(if (isDark) "#d9a441" else "#e2a750")
    private val RED_SOFT get() = Color.parseColor("#d97b7b")
    private val GREEN_SOFT get() = Color.parseColor(if (isDark) "#7fd7a4" else "#4c9a6b")

    private fun L(k: String) = Lang.t(prefs.getString("lang", "tr")!!, k)

    private val serverUrl get() = DEFAULT_SERVER
    private val myUid get() = prefs.getString("uid", null)
    private val myCode: String get() {
        var c = prefs.getString("myCode", null)
        if (c == null) { c = (100000..999999).random().toString(); prefs.edit().putString("myCode", c).apply() }
        return c
    }
    private val myName get() = prefs.getString("myName", null) ?: "Yakala-$myCode"

    private fun apiPost(url: String, body: String): String? = try {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.doOutput = true
        c.connectTimeout = 6000; c.readTimeout = 6000
        c.setRequestProperty("Content-Type", "application/json")
        c.outputStream.use { it.write(body.toByteArray()) }
        if (c.responseCode in 200..299) c.inputStream.readBytes().toString(Charsets.UTF_8) else null
    } catch (e: Exception) { null }

    private fun ensureAuthSync() {
        val tok = prefs.getString("idToken", null)
        val exp = prefs.getLong("tokExp", 0)
        if (tok != null && System.currentTimeMillis() < exp - 120_000) return
        val refresh = prefs.getString("refreshToken", null)
        var raw: String? = null
        if (refresh != null) {
            raw = apiPost("https://securetoken.googleapis.com/v1/token?key=$API_KEY",
                "{\"grant_type\":\"refresh_token\",\"refresh_token\":\"$refresh\"}")
        }
        if (raw == null) {
            raw = apiPost("https://identitytoolkit.googleapis.com/v1/accounts:signUp?key=$API_KEY", "{}")
        }
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

    private fun ensureIdentitySync() {
        ensureAuthSync()
        val u = myUid ?: return
        if (!prefs.getBoolean("codeReg", false)) {
            var code = myCode
            val existing = conn("/codes/$code", "GET")
            if (existing != null && existing.trim('"') != u) {
                code = (100000..999999).random().toString()
                prefs.edit().putString("myCode", code).apply()
            }
            conn("/codes/$code", "PUT", "\"$u\"")
            conn("/users/$u/code", "PUT", "\"$code\"")
            prefs.edit().putBoolean("codeReg", true).apply()
        }
        conn("/users/$u/name", "PUT", "\"$myName\"")
    }

    private fun conn(path: String, method: String, body: String? = null): String? {
        return try {
        ensureAuthSync()
        val tok = prefs.getString("idToken", null) ?: return null
        val c = URL("$serverUrl$path.json?auth=$tok").openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 6000; c.readTimeout = 6000
        if (body != null) {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toByteArray()) }
        }
        var s2: String? = if (c.responseCode in 200..299)
            c.inputStream.readBytes().toString(Charsets.UTF_8) else null
        if (s2 != null && s2.trimStart().startsWith("<")) s2 = null
        c.disconnect(); s2
    } catch (e: Exception) { null }
    }

    private val pollHandler = Handler(Looper.getMainLooper())
    private val pollRunnable = object : Runnable {
        override fun run() { poll(); pollHandler.postDelayed(this, 20000) }
    }

    private fun poll() {
        Thread {
            ensureIdentitySync()
            val u = myUid ?: return@Thread
            val rq = conn("/users/$u/requests", "GET")
            val gr = conn("/users/$u/grants", "GET")
            val ib = conn("/users/$u/inbox", "GET")
            val fr = conn("/users/$u/friends", "GET")
            pollHandler.post {
                handleFriends(fr); handleGrants(gr); handleRequests(rq); handleInbox(ib)
            }
        }.start()
    }

    private fun parseMap(raw: String?): Map<String, JSONObject> {
        val out = mutableMapOf<String, JSONObject>()
        if (raw == null || raw == "null") return out
        try {
            val o = JSONObject(raw)
            val k = o.keys()
            while (k.hasNext()) { val key = k.next(); out[key] = o.getJSONObject(key) }
        } catch (_: Exception) {}
        return out
    }

    private fun handleFriends(raw: String?) { myFriends.clear(); myFriends.putAll(parseMap(raw)) }
    private fun handleGrants(raw: String?) {
        myGrants.clear()
        for ((k, v) in parseMap(raw)) myGrants[k] = v.optString("status", "ask")
    }

    private fun handleRequests(raw: String?) {
        for ((uid, v) in parseMap(raw)) {
            if (uid in handledRequests) continue
            handledRequests.add(uid)
            val name = v.optString("name", "Kullanıcı")
            AlertDialog.Builder(this)
                .setTitle("👥 Arkadaşlık isteği")
                .setMessage("$name seni arkadaş olarak eklemek istiyor.\nİzin seviyesi seç:")
                .setPositiveButton("✅ Tam izin (karşılıklı)") { _, _ -> acceptFriend(uid, name, "full") }
                .setNeutralButton("❓ Her seferinde sor") { _, _ -> acceptFriend(uid, name, "ask") }
                .setNegativeButton("Reddet") { _, _ ->
                    Thread { conn("/users/${myUid}/requests/$uid", "DELETE") }.start()
                }
                .show()
        }
    }

    private fun acceptFriend(uid: String, name: String, status: String) {
        val me = myUid ?: return
        Thread {
            conn("/users/$me/friends/$uid", "PUT", JSONObject().put("name", name).put("status", status).toString())
            conn("/users/$uid/grants/$me", "PUT", JSONObject().put("status", status).put("name", myName).toString())
            conn("/users/$me/grants/$uid", "PUT", JSONObject().put("status", status).put("name", name).toString())
            conn("/users/$uid/friends/$me", "PUT", JSONObject().put("name", myName).put("status", status).toString())
            conn("/users/$me/requests/$uid", "DELETE")
        }.start()
        Toast.makeText(this, "✅ $name eklendi (karşılıklı)", Toast.LENGTH_SHORT).show()
    }

    private fun handleInbox(raw: String?) {
        var newPending = false
        val map = parseMap(raw)
        for ((key, v) in map) {
            if (key in seenInbox) continue
            seenInbox.add(key)
            v.put("id", key)
            fNotes.add(0, v)
            showReminderNotif(v)
            if (v.optBoolean("pending")) newPending = true
            val remAt = v.optLong("reminderAt")
            if (remAt > System.currentTimeMillis()) scheduleFriendReminder(v, remAt)
            Thread { conn("/users/${myUid}/inbox/$key", "DELETE") }.start()
        }
        if (map.isNotEmpty()) {
            saveFNotes(); updateFList()
            if (newPending) Toast.makeText(this, "📬 Yeni not isteği var", Toast.LENGTH_SHORT).show()
        }
    }

    private fun loadSent() {
        sentNotes.clear()
        val raw = prefs.getString("sent", null) ?: return
        try {
            val a = JSONArray(raw)
            for (i in 0 until a.length()) sentNotes.add(a.getJSONObject(i))
        } catch (_: Exception) {}
    }

    private fun saveSent() {
        val a = JSONArray()
        sentNotes.forEach { a.put(it) }
        prefs.edit().putString("sent", a.toString()).apply()
    }

    private fun addSent(to: String, toName: String, text: String, remAt: Long, remKind: String) {
        val o = JSONObject().apply {
            put("to", to); put("toName", toName); put("text", text)
            put("time", System.currentTimeMillis())
            if (remAt > 0) { put("reminderAt", remAt); put("reminderKind", remKind) }
        }
        sentNotes.add(0, o); saveSent()
    }

    private fun loadFNotes() {
        fNotes.clear()
        val raw = prefs.getString("fnotes", null) ?: return
        try {
            val a = JSONArray(raw)
            for (i in 0 until a.length()) fNotes.add(a.getJSONObject(i))
        } catch (_: Exception) {}
    }

    private fun saveFNotes() {
        val a = JSONArray()
        fNotes.forEach { a.put(it) }
        prefs.edit().putString("fnotes", a.toString()).apply()
    }

    private fun updateFList() {
        fDisplay.clear()
        fDisplay.addAll(fNotes.map { n ->
            val p = if (n.optBoolean("pending")) "⏳ " else if (n.optLong("reminderAt") > 0) "🔔 " else ""
            "$p👤 ${n.optString("fromName")}: ${n.optString("text")}"
        })
        if (::fAdapter.isInitialized) fAdapter.notifyDataSetChanged()
    }

    private fun friendDialog(pos: Int) {
        val n = fNotes[pos]
        if (n.optBoolean("pending")) {
            AlertDialog.Builder(this)
                .setTitle("👤 " + n.optString("fromName"))
                .setMessage(n.optString("text"))
                .setPositiveButton("✅ Kabul et") { _, _ ->
                    n.put("pending", false); saveFNotes(); updateFList()
                }
                .setNegativeButton("❌ Reddet") { _, _ ->
                    fNotes.removeAt(pos); saveFNotes(); updateFList()
                }
                .show()
        } else {
            AlertDialog.Builder(this)
                .setTitle("👤 " + n.optString("fromName"))
                .setMessage(n.optString("text"))
                .setPositiveButton("🗑 Sil") { _, _ ->
                    fNotes.removeAt(pos); saveFNotes(); updateFList()
                }
                .setNegativeButton(L("cancel"), null)
                .show()
        }
    }

    private fun clipText(): String {
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        val c = cm.primaryClip
        return if (c != null && c.itemCount > 0) c.getItemAt(0).text?.toString() ?: "" else ""
    }

    private fun friendsDialog() {
        poll()
        sheet("👥 " + L("friends")) { root ->
            root.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = roundBg(SURFACE2, 14)
                setPadding(dp(14), dp(12), dp(14), dp(12))
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) }
                addView(TextView(this@MainActivity).apply { text = "🪪 " + L("myCode") + ": $myCode"; textSize = 14f; setTextColor(TXT) })
                addView(TextView(this@MainActivity).apply { text = "Arkadaşlarınla kodunu paylaş"; textSize = 11f; setTextColor(META); setPadding(0, dp(4), 0, 0) })
                setOnClickListener {
                    (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager)
                        .setPrimaryClip(ClipData.newPlainText("code", myCode))
                    Toast.makeText(this@MainActivity, L("copied") + ": $myCode", Toast.LENGTH_SHORT).show()
                }
            })
            root.addView(sheetRow("➕", L("addFriend")) { addFriendDialog() })
            root.addView(sheetRow("🔗", L("share")) { shareInvite() })
            root.addView(sheetRow("👥", L("myFriends") + " (${myFriends.size})", if (myFriends.isEmpty()) "•" else "›") { friendsListDialog() })
            root.addView(sheetRow("✏️", L("server")) { setupServerDialog() })
            root.addView(TextView(this).apply {
                text = "⚡ " + "Davet et"; setTextColor(Color.WHITE); textSize = 14f
                gravity = android.view.Gravity.CENTER
                background = grad(intArrayOf(0xFF6a5af9.toInt(), 0xFF9a4df0.toInt()), 14)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(44)).apply { topMargin = dp(4); bottomMargin = dp(8) }
                setOnClickListener { shareInvite() }
            })
        }
    }

    private fun addFriendDialog() {
        val et = EditText(this).apply {
            hint = "Arkadaşın kodu (6 hane)"
            setText(Regex("\\d{6}").find(clipText())?.value ?: "")
        }
        AlertDialog.Builder(this)
            .setTitle("➕ Kod ile arkadaş bul")
            .setView(et)
            .setPositiveButton("🔍 Ara") { _, _ ->
                val code = Regex("\\d{6}").find(et.text.toString())?.value ?: ""
                if (code.isEmpty() || code == myCode) {
                    Toast.makeText(this, "Geçersiz kod", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                Toast.makeText(this, "🔍 Aranıyor...", Toast.LENGTH_SHORT).show()
                Thread {
                    ensureIdentitySync()
                    val uidRaw = conn("/codes/$code", "GET")
                    val uid = uidRaw?.trim('"')
                    val nameRaw = if (uid != null) conn("/users/$uid/name", "GET") else null
                    pollHandler.post {
                        if (uid == null || nameRaw == null || nameRaw == "null") {
                            Toast.makeText(this, "❌ Bu kodda kullanıcı bulunamadı", Toast.LENGTH_LONG).show()
                        } else {
                            val name = nameRaw.trim('"')
                            if (name.contains("<") || name.isEmpty() || name.length > 40) {
                                Toast.makeText(this, L("notFound"), Toast.LENGTH_LONG).show()
                                return@post
                            }
                            AlertDialog.Builder(this)
                                .setTitle("👤 $name bulundu")
                                .setMessage("Arkadaş olarak eklensin mi?")
                                .setPositiveButton("✅ Ekle") { _, _ -> inviteFriend(uid, name) }
                                .setNegativeButton("Vazgeç", null)
                                .show()
                        }
                    }
                }.start()
            }
            .setNegativeButton("Vazgeç", null)
            .show()
    }

    private fun inviteFriend(uid: String, name: String) {
        val me = myUid ?: return
        Thread {
            conn("/users/$uid/requests/$me", "PUT", JSONObject().put("name", myName).toString())
            conn("/users/$me/friends/$uid", "PUT",
                JSONObject().put("name", name).put("status", "ask").toString())
            pollHandler.post {
                Toast.makeText(this, "📨 $name'a istek gönderildi", Toast.LENGTH_SHORT).show()
            }
        }.start()
    }

    private fun shareInvite() {
        val url = "https://umutsarac.github.io/yakala-android/invite.html?code=$myCode&name=$myName"
        val i = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "⚡ Yakala ile birbirimize not gönderelim! Kodum: $myCode\n$url")
        }
        startActivity(Intent.createChooser(i, "Daveti paylaş"))
    }

    private fun setupServerDialog() {
        val ll = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(16), dp(24), 0)
        }
        val nameEt = EditText(this).apply {
            hint = "Görünen adın"
            setText(if (myName.startsWith("Yakala-")) "" else myName)
        }
        ll.addView(nameEt)
        AlertDialog.Builder(this)
            .setTitle("🪪 Görünen adın")
            .setView(ll)
            .setPositiveButton("Kaydet") { _, _ ->
                prefs.edit()
                    .putString("myName", nameEt.text.toString().trim().ifEmpty { "Yakala-$myCode" })
                    .putBoolean("codeReg", false)
                    .apply()
                Thread { ensureIdentitySync() }.start()
                Toast.makeText(this, "✅ Kaydedildi", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(L("cancel"), null)
            .show()
    }

    private fun friendsListDialog() {
        if (myFriends.isEmpty()) { Toast.makeText(this, "Henüz arkadaş yok", Toast.LENGTH_SHORT).show(); return }
        val uids = myFriends.keys.toList()
        val names = uids.map { c ->
            val st = if (myFriends[c]?.optString("status") == "full") "tam izin" else "sorar"
            "${myFriends[c]?.optString("name") ?: c} ($st)"
        }
        AlertDialog.Builder(this).setTitle("👥 Arkadaşlarım").setItems(names.toTypedArray()) { _, w ->
            val uid = uids[w]
            val name = myFriends[uid]?.optString("name") ?: "Arkadaş"
            val me = myUid ?: return@setItems
            AlertDialog.Builder(this)
                .setTitle(name)
                .setItems(arrayOf("✅ Tam izin (karşılıklı)", "❓ Her seferinde sor", "🗑 Arkadaşı sil")) { _, s ->
                    Thread {
                        when (s) {
                            0 -> {
                                conn("/users/$me/friends/$uid", "PUT", JSONObject().put("name", name).put("status", "full").toString())
                                conn("/users/$uid/grants/$me", "PUT", JSONObject().put("status", "full").put("name", myName).toString())
                                conn("/users/$me/grants/$uid", "PUT", JSONObject().put("status", "full").put("name", name).toString())
                                conn("/users/$uid/friends/$me", "PUT", JSONObject().put("name", myName).put("status", "full").toString())
                            }
                            1 -> {
                                conn("/users/$me/friends/$uid", "PUT", JSONObject().put("name", name).put("status", "ask").toString())
                                conn("/users/$uid/grants/$me", "PUT", JSONObject().put("status", "ask").put("name", myName).toString())
                                conn("/users/$me/grants/$uid", "PUT", JSONObject().put("status", "ask").put("name", name).toString())
                                conn("/users/$uid/friends/$me", "PUT", JSONObject().put("name", myName).put("status", "ask").toString())
                            }
                            2 -> {
                                conn("/users/$me/friends/$uid", "DELETE")
                                conn("/users/$uid/grants/$me", "DELETE")
                                conn("/users/$me/grants/$uid", "DELETE")
                                conn("/users/$uid/friends/$me", "DELETE")
                            }
                        }
                        pollHandler.post { poll() }
                    }.start()
                }
                .show()
        }.show()
    }

    private fun sendToFriend(text: String) {
        if (myFriends.isEmpty()) { Toast.makeText(this, "Önce arkadaş ekle", Toast.LENGTH_SHORT).show(); return }
        val uids = myFriends.keys.toList()
        val names = uids.map { c -> "${myFriends[c]?.optString("name") ?: c}" }
        AlertDialog.Builder(this).setTitle("📤 Kime gönderelim?").setItems(names.toTypedArray()) { _, w ->
            val uid = uids[w]
            val tname = myFriends[uid]?.optString("name") ?: "Arkadaş"
            AlertDialog.Builder(this).setTitle("$tname — not ne zaman ulaşsın?").setItems(arrayOf("⚡ Hemen gönder", "⏰ Zaman seçerek gönder")) { _, d ->
                if (d == 0) askReminder(uid, text, 0L)
                else pickTime { at -> askReminder(uid, text, at) }
            }.show()
        }.show()
    }

    private fun askReminder(uid: String, text: String, deliveryAt: Long) {
        val tname = myFriends[uid]?.optString("name") ?: "Arkadaş"
        AlertDialog.Builder(this).setTitle("🔔 $tname için hatırlatıcı eklensin mi?").setItems(arrayOf("🔔 Hatırlatıcı ekle", "⏰ Alarm ekle", "➡️ Hatırlatıcısız gönder")) { _, r ->
            when (r) {
                0 -> pickTime { remAt -> doSend(uid, text, deliveryAt, remAt, "reminder") }
                1 -> pickTime { remAt -> doSend(uid, text, deliveryAt, remAt, "alarm") }
                else -> doSend(uid, text, deliveryAt, 0L)
            }
        }.show()
    }

    private fun pickTime(onPick: (Long) -> Unit) {
        val cal = Calendar.getInstance()
        DatePickerDialog(this, { _, y, m, d ->
            cal.set(Calendar.YEAR, y); cal.set(Calendar.MONTH, m); cal.set(Calendar.DAY_OF_MONTH, d)
            TimePickerDialog(this, { _, hh, mm ->
                cal.set(Calendar.HOUR_OF_DAY, hh); cal.set(Calendar.MINUTE, mm)
                cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
                if (cal.timeInMillis <= System.currentTimeMillis() + 60_000) {
                    Toast.makeText(this, "Gelecek bir zaman seç", Toast.LENGTH_SHORT).show(); return@TimePickerDialog
                }
                onPick(cal.timeInMillis)
            }, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), true).show()
        }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
    }

    private fun doSend(uid: String, text: String, deliveryAt: Long, reminderAt: Long, remKind: String = "reminder") {
        val tname = myFriends[uid]?.optString("name") ?: "Arkadaş"
        if (deliveryAt <= 0L) {
            postInbox(uid, text, "now", reminderAt, remKind)
            addSent(uid, tname, text, reminderAt, remKind)
            Toast.makeText(this, "📤 $tname'a gönderildi" + (if (reminderAt > 0) " + 🔔 ${fmtDate(reminderAt)}" else ""), Toast.LENGTH_SHORT).show()
        } else {
            val job = JSONObject().apply {
                put("to", uid); put("text", text); put("at", deliveryAt); put("reminderAt", reminderAt); put("remKind", remKind)
            }
            scheduledSends.add(job); saveScheduled()
            val si = Intent(this, SendReceiver::class.java).apply {
                putExtra("to", uid); putExtra("text", text)
                putExtra("remAt", reminderAt); putExtra("remKind", remKind)
                putExtra("toName", tname)
            }
            val spi = PendingIntent.getBroadcast(this, deliveryAt.toInt(), si,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            (getSystemService(ALARM_SERVICE) as AlarmManager)
                .setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, deliveryAt, spi)
            Toast.makeText(this, "⏰ ${fmtDate(deliveryAt)}'de ulaşacak" + (if (reminderAt > 0) " + 🔔 ${fmtDate(reminderAt)}" else ""), Toast.LENGTH_LONG).show()
        }
    }

    private fun postInbox(uid: String, text: String, kind: String, reminderAt: Long, remKind: String = "reminder") {
        val pending = myGrants[uid] != "full"
        val o = JSONObject().apply {
            put("from", myCode); put("fromName", myName)
            put("text", text); put("time", System.currentTimeMillis())
            put("pending", pending); put("kind", kind)
            if (reminderAt > 0) { put("reminderAt", reminderAt); put("reminderKind", remKind) }
        }
        Thread { conn("/users/$uid/inbox", "POST", o.toString()) }.start()
    }

    private val sendTicker = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            val due = scheduledSends.filter { it.optLong("at") <= now }.toList()
            due.forEach { job ->
                postInbox(job.optString("to"), job.optString("text"), "timed", job.optLong("reminderAt"))
                scheduledSends.remove(job)
            }
            if (due.isNotEmpty()) saveScheduled()
            handler.postDelayed(this, 15000)
        }
    }

    private fun loadScheduled() {
        scheduledSends.clear()
        val raw = prefs.getString("scheduled", null) ?: return
        try { val a = JSONArray(raw); for (i in 0 until a.length()) scheduledSends.add(a.getJSONObject(i)) } catch (_: Exception) {}
    }

    private fun saveScheduled() {
        val a = JSONArray(); scheduledSends.forEach { a.put(it) }
        prefs.edit().putString("scheduled", a.toString()).apply()
    }

    private fun handleInviteIntent(i: Intent?) {
        val u = i?.data ?: return
        if (u.scheme == "yakala" && u.host == "invite") {
            val code = u.getQueryParameter("code") ?: return
            val name = u.getQueryParameter("name") ?: "Arkadaş"
            if (code == myCode) return
            Thread {
                ensureIdentitySync()
                val uidRaw = conn("/codes/$code", "GET")
                val uid = uidRaw?.trim('"') ?: return@Thread
                pollHandler.post { inviteFriend(uid, name) }
            }.start()
        }
    }

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
            setBackgroundColor(BG)
            setPadding(0, dp(14), 0, 0)
        }
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(14), 0, dp(14), dp(6))
        }
        val brand = TextView(this).apply {
            text = "⚡ Y A K A L A"; textSize = 16f; setTextColor(TXT)
            paint.isFakeBoldText = true
            gravity = android.view.Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val themeBtn = TextView(this).apply {
            text = if (isDark) "☀️" else "🌙"; textSize = 15f
            gravity = android.view.Gravity.CENTER
            background = roundBg(ACCENT, 18)
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(36))
            setOnClickListener { prefs.edit().putBoolean("dark", !isDark).apply(); recreate() }
        }
        topBar.addView(brand); topBar.addView(themeBtn)
        val iconRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER
            setPadding(0, dp(4), 0, dp(10))
        }
        fun iconBtn(emoji: String, onClick: () -> Unit): TextView = TextView(this).apply {
            text = emoji; textSize = 16f
            gravity = android.view.Gravity.CENTER
            background = roundBg(SURFACE2, 20)
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginStart = dp(6); marginEnd = dp(6) }
            setOnClickListener { onClick() }
        }
        iconRow.addView(iconBtn("👥") { friendsDialog() })
        iconRow.addView(iconBtn("🌐") { langDialog() })
        iconRow.addView(iconBtn("🔍") {
            if (searchInput.visibility == View.VISIBLE) { searchInput.visibility = View.GONE; searchInput.setText("") }
            else { searchInput.visibility = View.VISIBLE; searchInput.requestFocus() }
        })
        iconRow.addView(iconBtn("⚙️") { settingsDialog() })
        searchInput = EditText(this).apply {
            hint = L("search")
            setTextColor(TXT); setHintTextColor(META)
            background = roundBg(SURFACE, 16)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(dp(16), 0, dp(16), dp(10)) }
        }
        inputCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundBg(SURFACE, 20)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(dp(16), 0, dp(16), dp(12)) }
        }
        input = EditText(this).apply {
            hint = L("hint")
            setTextColor(TXT); setHintTextColor(META)
            setBackgroundColor(Color.TRANSPARENT)
            minLines = 2; maxLines = 5
            gravity = android.view.Gravity.TOP
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setPadding(0, 0, 0, dp(8))
        }
        val chipRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val chipCols = listOf(0xFF16a34a.toInt(), 0xFF0ea5e9.toInt(), 0xFFeab308.toInt(), 0xFFef4444.toInt(), 0xFFf59e0b.toInt())
        listOf("✅", "📞", "💡", "📅", "⭐").forEachIndexed { i, sym ->
            val c = TextView(this).apply {
                text = sym; textSize = 14f
                gravity = android.view.Gravity.CENTER
                background = roundBg(tint(chipCols[i], 60), 17)
                layoutParams = LinearLayout.LayoutParams(dp(34), dp(34)).apply { marginEnd = dp(8) }
                setOnClickListener {
                    val cur = input.text.toString()
                    input.setText(if (cur.isEmpty()) "$sym " else "$cur\n$sym ")
                    input.setSelection(input.text.length)
                }
            }
            chipRow.addView(c)
        }
        inputCard.addView(input)
        inputCard.addView(chipRow)
        btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(16), 0, dp(16), dp(12))
        }
        fun gBtn(t: String, c1: Int, c2: Int, onClick: () -> Unit): TextView = TextView(this).apply {
            text = t; setTextColor(Color.WHITE); textSize = 14f
            paint.isFakeBoldText = true
            gravity = android.view.Gravity.CENTER
            background = grad(intArrayOf(c1, c2), 16)
            layoutParams = LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(5); marginEnd = dp(5) }
            setOnClickListener { onClick() }
        }
        val save = gBtn("＋ KAYDET", 0xFF6a5af9.toInt(), 0xFF9a4df0.toInt()) { }
        voiceBtn = gBtn(L("voice"), 0xFF0891b2.toInt(), 0xFF1d4ed8.toInt()) { }
        textBtn = gBtn(L("stt"), 0xFFf97316.toInt(), 0xFFec4899.toInt()) { }
        btnRow.addView(save); btnRow.addView(voiceBtn); btnRow.addView(textBtn)
        statusText = TextView(this).apply {
            textSize = 13f; setTextColor(GREEN_SOFT)
            setPadding(dp(18), 0, dp(18), dp(4))
        }
        fun header(t: String, right: String, onRight: () -> Unit): LinearLayout {
            val h = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(dp(18), dp(6), dp(18), dp(6))
            }
            h.addView(TextView(this).apply {
                text = t; textSize = 12f; setTextColor(META)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            h.addView(TextView(this).apply {
                text = right; textSize = 11f; setTextColor(ACCENT)
                setOnClickListener { onRight() }
            })
            return h
        }
        listMine = ListView(this).apply { divider = null; dividerHeight = dp(10) }
        listFriends = ListView(this).apply { divider = null; dividerHeight = dp(8) }
        adSlot = android.widget.FrameLayout(this).apply {
            background = roundBg(SURFACE2, 12)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(50)
            ).apply { setMargins(dp(16), dp(6), dp(16), dp(6)) }
            addView(TextView(this@MainActivity).apply {
                text = "📢 Reklam Alanı (AdMob hazır)"; textSize = 11f; setTextColor(META)
                gravity = android.view.Gravity.CENTER
            })
        }
        navRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(6), dp(10), dp(8))
        }
        layout.addView(topBar)
        layout.addView(iconRow)
        layout.addView(searchInput)
        layout.addView(inputCard)
        layout.addView(btnRow)
        layout.addView(statusText)
        headerMine = header("📒 " + L("myNotes"), "Tümünü gör ›") { fullMine = !fullMine; applyLayout() }
        mineTitle = headerMine.getChildAt(0) as TextView
        mineToggle = headerMine.getChildAt(1) as TextView
        mineDelete = TextView(this).apply {
            text = "🗑"; textSize = 13f; setTextColor(META)
            setPadding(dp(10), 0, 0, 0)
            setOnClickListener { confirmClearMine() }
        }
        headerMine.addView(mineDelete)
        layout.addView(headerMine, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        layout.addView(listMine, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        headerFriends = header("👥 " + L("frNotes"), "Tümünü gör ›") { fullFr = !fullFr; applyLayout() }
        frTitle = headerFriends.getChildAt(0) as TextView
        frToggle = headerFriends.getChildAt(1) as TextView
        frDelete = TextView(this).apply {
            text = "🗑"; textSize = 13f; setTextColor(META)
            setPadding(dp(10), 0, 0, 0)
            setOnClickListener { confirmClearFriends() }
        }
        headerFriends.addView(frDelete)
        layout.addView(headerFriends, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        layout.addView(listFriends, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 0.6f))
        friendsPanel = android.widget.ScrollView(this).apply {
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        layout.addView(friendsPanel)
        layout.addView(adSlot)
        layout.addView(navRow)
        buildNav()
        applyLayout()
        setContentView(layout)

        loadNotes()
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)
        adapter = NoteAdapter(visibleNotes)
        arcAdapter = NoteAdapter(storeArc)
        updateList()
        listMine.adapter = adapter

        loadFNotes()
        loadSent()
        fAdapter = object : android.widget.ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, fDisplay) {
            override fun getView(position: Int, cv: View?, parent: ViewGroup): View {
                val v = super.getView(position, cv, parent)
                (v as TextView).setTextColor(TXT)
                return v
            }
        }
        updateFList()
        listFriends.adapter = fAdapter
        listFriends.setOnItemClickListener { _, _, pos, _ ->
            if (tab == "store") {
                val n = storeArc[pos]
                if (n.optString("type") == "voice") playNote(n) else editDialog(n)
            } else friendDialog(pos)
        }

        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) { query = s.toString(); updateList() }
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
        listMine.setOnItemClickListener { _, _, pos, _ ->
            val n = visibleNotes[pos]
            if (n.optString("type") == "voice") playNote(n) else editDialog(n)
        }
        listMine.setOnItemLongClickListener { _, _, pos, _ -> optionsDialog(pos); true }

        handleIncomingIntent(intent)
        handleQuick(intent)
        handleInviteIntent(intent)

        pollHandler.postDelayed(pollRunnable, 3000)
        Thread { ensureIdentitySync() }.start()
        if (prefs.getBoolean("shake", false)) {
            val si = Intent(this, ListenService::class.java)
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(si) else startService(si)
        }
        if (prefs.getBoolean("sync", false)) {
            val si2 = Intent(this, SyncService::class.java)
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(si2) else startService(si2)
        }
        loadScheduled()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
        handleQuick(intent)
        handleInviteIntent(intent)
    }

    private inner class NoteAdapter(private val src: MutableList<JSONObject>) : BaseAdapter() {
        override fun getCount() = src.size
        override fun getItem(p: Int) = src[p]
        override fun getItemId(p: Int) = p.toLong()

        override fun getView(p: Int, cv: View?, parent: ViewGroup): View {
            val n = src[p]
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
                card.addView(TextView(this@MainActivity).apply {
                    text = lines[0]; setTextColor(TXT); textSize = 16f; paint.isFakeBoldText = true
                })
                card.addView(TextView(this@MainActivity).apply {
                    text = lines[1]; setTextColor(META); textSize = 14f
                    maxLines = 3; ellipsize = android.text.TextUtils.TruncateAt.END
                    setPadding(0, dp(4), 0, 0)
                })
            } else {
                card.addView(TextView(this@MainActivity).apply {
                    text = (if (isVoice) "🎤 " else "") + src.ifBlank { "Sesli not" }
                    setTextColor(TXT); textSize = 15f
                    maxLines = 3; ellipsize = android.text.TextUtils.TruncateAt.END
                })
            }
            card.addView(TextView(this@MainActivity).apply {
                text = buildString {
                    if (n.optBoolean("pinned")) append("⭐ ")
                    if (n.optBoolean("archived")) append("📦 ")
                    if (n.optLong("reminderTime") > System.currentTimeMillis()) append("⏰ ")
                    if (isVoice && n.optLong("duration") > 0) append("🎤 ${fmtDur(n.optLong("duration"))}  ")
                    tags.forEach { append("$it  ") }
                    append("• ${fmtDate(n.optLong("createdAt"))}")
                }
                setTextColor(if (tags.isNotEmpty()) AMBER_SOFT else META)
                textSize = 12f
                setPadding(0, dp(6), 0, 0)
            })
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
        return SimpleDateFormat(if (sameDay) "HH:mm" else "d MMM HH:mm", Locale("tr")).format(Date(ts))
    }

    private fun applyLayout() {
        val isHome = tab == "home"
        val isNotes = tab == "notes"
        val isStore = tab == "store"
        val isFriends = tab == "friends"
        inputCard.visibility = if (isHome) View.VISIBLE else View.GONE
        btnRow.visibility = if (isHome) View.VISIBLE else View.GONE
        statusText.visibility = if (isHome) View.VISIBLE else View.GONE
        val listsVis = if (isNotes || isStore) View.VISIBLE else View.GONE
        headerMine.visibility = listsVis
        listMine.visibility = listsVis
        headerFriends.visibility = listsVis
        listFriends.visibility = listsVis
        friendsPanel.visibility = if (isFriends) View.VISIBLE else View.GONE
        if (isNotes) {
            mineTitle.text = "📒 " + L("myNotes")
            mineToggle.text = if (fullMine) "Gizle ›" else "Tümünü gör ›"
            mineDelete.visibility = View.VISIBLE
            frTitle.text = "👥 " + L("frNotes")
            frToggle.text = if (fullFr) "Gizle ›" else "Tümünü gör ›"
            frDelete.visibility = View.VISIBLE
            headerFriends.visibility = if (fullMine) View.GONE else View.VISIBLE
            listFriends.visibility = if (fullMine) View.GONE else View.GONE.let { if (fullMine) View.GONE else View.VISIBLE }
            headerMine.visibility = if (fullFr) View.GONE else View.VISIBLE
            listMine.visibility = if (fullFr) View.GONE else View.VISIBLE
            listFriends.adapter = fAdapter
        } else if (isStore) {
            mineTitle.text = "⭐ FAVORİLER"
            mineToggle.text = ""
            mineDelete.visibility = View.GONE
            frTitle.text = "📦 ARŞİV"
            frToggle.text = ""
            frDelete.visibility = View.GONE
            listFriends.adapter = arcAdapter
        }
        val clp = inputCard.layoutParams as LinearLayout.LayoutParams
        if (isHome) { clp.height = 0; clp.weight = 1f } else { clp.height = LinearLayout.LayoutParams.WRAP_CONTENT; clp.weight = 0f }
        inputCard.layoutParams = clp
        val ilp = input.layoutParams as LinearLayout.LayoutParams
        if (isHome) { ilp.height = 0; ilp.weight = 1f } else { ilp.height = LinearLayout.LayoutParams.WRAP_CONTENT; ilp.weight = 0f }
        input.layoutParams = ilp
        if (isFriends) buildFriendsPanel()
        updateList()
    }

    private fun parseTimeFrom(low: String): Long? {
        var m = Regex("saat\\s*(\\d{1,2})(?:[.:](\\d{2}))?").find(low)
        if (m == null) m = Regex("(\\d{1,2})[.:](\\d{2})").find(low)
        if (m != null) {
            val h = m.groupValues[1].toInt()
            val mi = if (m.groupValues[2].isEmpty()) 0 else m.groupValues[2].toInt()
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

    private fun processCommand(tr: String): Boolean {
        val low = tr.lowercase()
        for ((uid, fo) in myFriends) {
            val name = fo.optString("name")
            if (name.length > 2 && low.contains(name.lowercase()) && (low.contains("gönder") || low.contains("yolla") || low.contains("ilet"))) {
                var msg = tr.replace(Regex(Regex.escape(name), RegexOption.IGNORE_CASE), "")
                msg = msg.replace(Regex("(?i)(gönder|yolla|ilet|arkadaşa|arkadasa)"), "").trim().trimStart('-', ':', ' ')
                if (msg.isEmpty()) msg = tr
                doSend(uid, msg, 0L, 0L)
                statusText.text = "📤 $name: $msg"
                return true
            }
        }
        if (low.contains("alarm")) {
            val t = parseTimeFrom(low) ?: System.currentTimeMillis() + 3600_000
            val msg = tr.replace(Regex("(?i)alarm(\\s+ekle|\\s+kur)?"), "").trim().ifEmpty { tr }
            val i = Intent(this, ReminderReceiver::class.java).apply {
                putExtra("text", "⏰ " + msg); putExtra("id", msg.hashCode()); putExtra("alarm", true)
            }
            val pi = PendingIntent.getBroadcast(this, msg.hashCode(), i,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            (getSystemService(ALARM_SERVICE) as AlarmManager).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, pi)
            statusText.text = "⏰ ALARM ${fmtDate(t)}: $msg"
            return true
        }
        if (low.contains("hatırlat")) {
            val t = parseTimeFrom(low) ?: System.currentTimeMillis() + 3600_000
            val msg = tr.replace(Regex("(?i)hatırlatıcı|hatirlatici|hatırlat(\\s+ekle|\\s+kur)?"), "").trim().ifEmpty { tr }
            val o = JSONObject()
            o.put("id", System.currentTimeMillis()); o.put("type", "text")
            o.put("text", "⏰ " + msg); o.put("pinned", false); o.put("createdAt", System.currentTimeMillis())
            o.put("reminderTime", t)
            notes.add(0, o); persist(); setAlarm(o)
            statusText.text = "⏰ ${fmtDate(t)}: $msg"
            return true
        }
        return false
    }

    private fun confirmClearMine() {
        AlertDialog.Builder(this).setTitle("🗑 " + L("myNotes"))
            .setMessage(L("del") + "?")
            .setPositiveButton("✅") { _, _ ->
                notes.removeAll { !it.optBoolean("archived") }
                persist()
            }
            .setNegativeButton(L("cancel"), null).show()
    }

    private fun confirmClearFriends() {
        AlertDialog.Builder(this).setTitle("🗑 " + L("frNotes"))
            .setMessage(L("del") + "?")
            .setPositiveButton("✅") { _, _ ->
                fNotes.clear(); saveFNotes(); updateFList()
            }
            .setNegativeButton(L("cancel"), null).show()
    }

    private fun buildFriendsPanel() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), 0)
        }
        if (myFriends.isEmpty()) {
            box.addView(TextView(this).apply {
                text = L("noFriends"); textSize = 14f; setTextColor(META)
                gravity = android.view.Gravity.CENTER
                setPadding(0, dp(40), 0, 0)
            })
        }
        myFriends.forEach { (uid, fo) ->
            val name = fo.optString("name")
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                background = roundBg(SURFACE, 16)
                setPadding(dp(14), dp(12), dp(14), dp(12))
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) }
            }
            row.addView(TextView(this).apply {
                text = "👤 $name"; textSize = 14f; setTextColor(TXT)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            row.addView(TextView(this).apply {
                text = "📝"; textSize = 15f
                setPadding(dp(8), dp(4), dp(8), dp(4))
                setOnClickListener { composeToFriend(uid, name) }
            })
            row.setOnClickListener {
                if (name in expandedFriends) expandedFriends.remove(name) else expandedFriends.add(name)
                buildFriendsPanel()
            }
            box.addView(row)
            if (name in expandedFriends) {
                val theirs = fNotes.filter { it.optString("fromName") == name }
                val mine = sentNotes.filter { it.optString("toName") == name }
                if (theirs.isEmpty() && mine.isEmpty()) {
                    box.addView(TextView(this).apply {
                        text = "• • •"; textSize = 12f; setTextColor(META)
                        setPadding(dp(24), 0, 0, dp(8))
                    })
                }
                val all = mutableListOf<JSONObject>()
                theirs.forEach { n -> n.put("dir", "in"); all.add(n) }
                mine.forEach { n -> n.put("dir", "out"); all.add(n) }
                all.sortByDescending { it.optLong("time") }
                all.forEach { n ->
                    val isIn = n.optString("dir") == "in"
                    val card = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        background = roundBg(if (isIn) SURFACE2 else tint(ACCENT, 40), 12)
                        setPadding(dp(14), dp(10), dp(14), dp(10))
                        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(6); marginStart = dp(12) }
                    }
                    card.addView(TextView(this).apply {
                        text = (if (isIn) "📥" else "📤") + " " + (if (n.optBoolean("pending")) "⏳ " else "") + n.optString("text")
                        textSize = 13f; setTextColor(TXT)
                    })
                    val rem = n.optLong("reminderAt")
                    card.addView(TextView(this).apply {
                        text = fmtDate(n.optLong("time")) + (if (rem > 0) (if (n.optString("reminderKind") == "alarm") " • ⏰ alarm" else " • 🔔 hatırlatıcı") else "")
                        textSize = 10f; setTextColor(META)
                        setPadding(0, dp(4), 0, 0)
                    })
                    if (isIn) {
                        val idx = fNotes.indexOf(n)
                        card.setOnClickListener { friendDialog(idx) }
                    }
                    box.addView(card)
                }
            }
        }
        friendsPanel.removeAllViews()
        friendsPanel.addView(box)
    }

    private fun composeToFriend(uid: String, name: String) {
        val et = EditText(this).apply { hint = L("hint"); setTextColor(TXT); minLines = 2 }
        AlertDialog.Builder(this)
            .setTitle("📤 $name")
            .setView(et)
            .setPositiveButton("📤") { _, _ ->
                val t = et.text.toString().trim()
                if (t.isNotEmpty()) doSend(uid, t, 0L, 0L)
            }
            .setNegativeButton(L("cancel"), null)
            .show()
    }

    private fun buildNav() {
        navRow.removeAllViews()
        fun item(icon: String, label: String, key: String, onClick: () -> Unit) {
            val v = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = android.view.Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                background = if (tab == key) roundBg(ACCENT, 14) else null
                setPadding(0, dp(6), 0, dp(6))
                setOnClickListener { onClick() }
            }
            v.addView(TextView(this).apply { text = icon; textSize = 15f; gravity = android.view.Gravity.CENTER; setTextColor(if (tab == key) Color.WHITE else META) })
            v.addView(TextView(this).apply { text = label; textSize = 10f; gravity = android.view.Gravity.CENTER; setTextColor(if (tab == key) Color.WHITE else META) })
            navRow.addView(v)
        }
        item("🏠", "Ana Sayfa", "home") {
            tab = "home"; fullMine = false; applyLayout(); buildNav()
        }
        item("📋", "Notlarım", "notes") {
            tab = "notes"; applyLayout(); buildNav()
        }
        val fab = TextView(this).apply {
            text = "＋"; textSize = 22f; setTextColor(Color.WHITE)
            gravity = android.view.Gravity.CENTER
            background = roundBg(ACCENT, 24)
            layoutParams = LinearLayout.LayoutParams(dp(52), dp(52)).apply { marginStart = dp(6); marginEnd = dp(6) }
            setOnClickListener {
                tab = "home"
                applyLayout()
                buildNav()
                input.requestFocus()
                val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
            }
        }
        navRow.addView(fab)
        item("🗂", "Arşiv", "store") {
            tab = "store"; applyLayout(); buildNav()
        }
        item("👥", "Arkadaşlar", "friends") {
            tab = "friends"; applyLayout(); buildNav()
        }
    }

    private fun langDialog() {
        val flags = mapOf("tr" to "🇹🇷", "en" to "🇺🇸", "ru" to "🇷🇺", "zh" to "🇨🇳")
        val cur = prefs.getString("lang", "tr")
        sheet(L("lang")) { root ->
            Lang.LANGS.forEach { c ->
                root.addView(sheetRow(flags[c] ?: "🌐", Lang.NAMES[c]!!, if (c == cur) "✓" else "") {
                    prefs.edit().putString("lang", c).apply()
                    recreate()
                })
            }
        }
    }

    private fun roundTopBg(color: Int, rDp: Int) = GradientDrawable().apply {
        val r = dp(rDp).toFloat()
        cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
        setColor(color)
    }

    private fun sheet(title: String, builder: (LinearLayout) -> Unit) {
        val dlg = Dialog(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundTopBg(SURFACE, 24)
            setPadding(dp(20), dp(10), dp(20), dp(16))
        }
        root.addView(View(this).apply {
            background = roundBg(META, 2)
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(4)).apply {
                gravity = android.view.Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(12)
            }
        })
        root.addView(TextView(this).apply {
            text = title; textSize = 18f; setTextColor(TXT)
            paint.isFakeBoldText = true
            setPadding(0, 0, 0, dp(12))
        })
        builder(root)
        root.addView(TextView(this).apply {
            text = "✕ " + L("cancel"); setTextColor(Color.WHITE); textSize = 14f
            gravity = android.view.Gravity.CENTER
            background = grad(intArrayOf(0xFF6a5af9.toInt(), 0xFF9a4df0.toInt()), 14)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(44)).apply { topMargin = dp(6) }
            setOnClickListener { dlg.dismiss() }
        })
        val scroll = android.widget.ScrollView(this).apply { addView(root) }
        dlg.setContentView(scroll)
        dlg.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setGravity(android.view.Gravity.BOTTOM)
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        dlg.show()
    }

    private fun sheetRow(icon: String, label: String, right: String = "›", onClick: () -> Unit): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = android.view.Gravity.CENTER_VERTICAL
        background = roundBg(SURFACE2, 14)
        setPadding(dp(14), dp(12), dp(14), dp(12))
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) }
        setOnClickListener { onClick() }
        addView(TextView(this@MainActivity).apply { text = icon; textSize = 16f; setPadding(0, 0, dp(10), 0) })
        addView(TextView(this@MainActivity).apply {
            text = label; textSize = 14f; setTextColor(TXT)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        addView(TextView(this@MainActivity).apply { text = right; textSize = 13f; setTextColor(ACCENT) })
    }

    private fun settingsDialog() {
        sheet(L("settings")) { root ->
            root.addView(sheetRow(if (isDark) "☀️" else "🌙", if (isDark) L("toLight") else L("toDark"), if (isDark) "AÇIK" else "KAPALI") {
                prefs.edit().putBoolean("dark", !isDark).apply(); recreate()
            })
            root.addView(sheetRow("📳", L("shakeOn").substringAfter(" ").substringBefore(":"), if (prefs.getBoolean("shake", false)) "AÇIK" else "KAPALI") { toggleShake() })
            root.addView(sheetRow("🔄", "Arka plan eşitleme", if (prefs.getBoolean("sync", false)) "AÇIK" else "KAPALI") { toggleSync() })
            root.addView(sheetRow("☁️", L("backup")) { exportNotes() })
            root.addView(sheetRow("💾", L("restore")) { importNotes() })
            root.addView(sheetRow("ℹ️", "Yakala v2.4", "•") { Toast.makeText(this, "⚡ Yakala v2.4 🔐", Toast.LENGTH_SHORT).show() })
        }
    }

    private fun toggleSync() {
        val on = prefs.getBoolean("sync", false)
        if (on) {
            stopService(Intent(this, SyncService::class.java))
            prefs.edit().putBoolean("sync", false).apply()
        } else {
            prefs.edit().putBoolean("sync", true).apply()
            val i = Intent(this, SyncService::class.java)
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
        }
        settingsDialog()
    }

    private fun toggleShake() {
        val on = prefs.getBoolean("shake", false)
        if (on) {
            stopService(Intent(this, ListenService::class.java))
            prefs.edit().putBoolean("shake", false).apply()
        } else {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 100); return
            }
            prefs.edit().putBoolean("shake", true).apply()
            val i = Intent(this, ListenService::class.java)
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
        }
        settingsDialog()
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
                Toast.makeText(this, L("bSaved"), Toast.LENGTH_SHORT).show()
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
        storeArc.clear()
        val q = query.trim().lowercase()
        if (tab == "store") {
            visibleNotes.addAll(notes.filter { it.optBoolean("pinned") && !it.optBoolean("archived") }
                .sortedWith(compareByDescending<JSONObject> { it.optLong("createdAt") })
                .filter { q.isEmpty() || display(it).lowercase().contains(q) })
            storeArc.addAll(notes.filter { it.optBoolean("archived") }
                .sortedWith(compareByDescending<JSONObject> { it.optLong("createdAt") })
                .filter { q.isEmpty() || display(it).lowercase().contains(q) })
            if (::arcAdapter.isInitialized) arcAdapter.notifyDataSetChanged()
        } else {
            visibleNotes.addAll(notes.filter { !it.optBoolean("archived") }
                .filter { q.isEmpty() || display(it).lowercase().contains(q) }
                .sortedWith(compareByDescending<JSONObject> { it.optBoolean("pinned") }
                    .thenByDescending { it.optLong("createdAt") }))
        }
        if (::adapter.isInitialized) adapter.notifyDataSetChanged()
        updateFList()
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
        o.put("type", "text"); o.put("text", t)
        o.put("pinned", false); o.put("createdAt", System.currentTimeMillis())
        notes.add(0, o)
        persist()
    }

    private fun editDialog(n: JSONObject) {
        val et = EditText(this).apply {
            setText(n.optString("text")); setTextColor(TXT)
            minLines = 3; gravity = android.view.Gravity.TOP
        }
        AlertDialog.Builder(this).setTitle("✏️ Notu düzenle").setView(et)
            .setPositiveButton("Kaydet") { _, _ -> n.put("text", et.text.toString().trim()); persist() }
            .setNegativeButton("Vazgeç", null).show()
    }

    private fun optionsDialog(pos: Int) {
        val n = visibleNotes[pos]
        val isVoice = n.optString("type") == "voice"
        val pinned = n.optBoolean("pinned")
        val items = mutableListOf<String>()
        if (!isVoice) items.add("✏️ Düzenle")
        if (isVoice) items.add("🔊 Çal")
        items.add("📤 Arkadaşa gönder")
        items.add("⏰ Hatırlat")
        items.add(if (pinned) "⭐ Favorilerden çıkar" else "⭐ Favorilere ekle")
        items.add(if (n.optBoolean("archived")) "📦 Arşivden çıkar" else "📦 Arşive ekle")
        items.add("🗑️ Sil")
        AlertDialog.Builder(this).setTitle(display(n)).setItems(items.toTypedArray()) { _, which ->
            when (items[which]) {
                "✏️ Düzenle" -> editDialog(n)
                "🔊 Çal" -> playNote(n)
                "📤 Arkadaşa gönder" -> sendToFriend(n.optString("text").ifBlank { n.optString("transcript") })
                "⏰ Hatırlat" -> scheduleReminder(n)
                "⭐ Favorilere ekle" -> { n.put("pinned", true); persist() }
                "⭐ Favorilerden çıkar" -> { n.put("pinned", false); persist() }
                "📦 Arşive ekle" -> { n.put("archived", true); persist() }
                "📦 Arşivden çıkar" -> { n.put("archived", false); persist() }
                "🗑️ Sil" -> deleteNote(n)
            }
        }.show()
    }

    private fun scheduleReminder(n: JSONObject) {
        val options = arrayOf(
            "⚡ 1 dakika sonra (test)", "⏱ 1 saat sonra",
            "🌅 09:00", "☀️ 13:00", "🌆 20:00",
            "📅 Tarih ve saat seç", "🗑 Hatırlatmayı kaldır"
        )
        AlertDialog.Builder(this).setTitle("⏰ Ne zaman hatırlatayım?")
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
                persist(); setAlarm(n)
                Toast.makeText(this, "⏰ Hatırlatma kuruldu", Toast.LENGTH_SHORT).show()
            }.show()
    }

    private fun setClassic(cal: Calendar, hour: Int) {
        cal.set(Calendar.HOUR_OF_DAY, hour); cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
        if (cal.timeInMillis <= System.currentTimeMillis()) cal.add(Calendar.DAY_OF_YEAR, 1)
    }

    private fun pickCustom(n: JSONObject) {
        val cal = Calendar.getInstance()
        DatePickerDialog(this, { _, y, m, d ->
            cal.set(Calendar.YEAR, y); cal.set(Calendar.MONTH, m); cal.set(Calendar.DAY_OF_MONTH, d)
            TimePickerDialog(this, { _, hh, mm ->
                cal.set(Calendar.HOUR_OF_DAY, hh); cal.set(Calendar.MINUTE, mm)
                cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
                if (cal.timeInMillis <= System.currentTimeMillis()) {
                    Toast.makeText(this, "Geçmiş zaman seçilemez", Toast.LENGTH_SHORT).show()
                    return@TimePickerDialog
                }
                n.put("reminderTime", cal.timeInMillis)
                persist(); setAlarm(n)
                Toast.makeText(this, "⏰ Hatırlatma kuruldu", Toast.LENGTH_SHORT).show()
            }, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), true).show()
        }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
    }

    private fun setAlarm(n: JSONObject) {
        val time = n.optLong("reminderTime")
        if (time <= 0) return
        val id = n.optLong("id").toInt()
        val i = Intent(this, ReminderReceiver::class.java).apply {
            putExtra("text", display(n)); putExtra("id", id)
        }
        val pi = PendingIntent.getBroadcast(this, id, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        (getSystemService(ALARM_SERVICE) as AlarmManager)
            .setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, time, pi)
    }

    private fun cancelAlarm(n: JSONObject) {
        val id = n.optLong("id").toInt()
        val i = Intent(this, ReminderReceiver::class.java)
        val pi = PendingIntent.getBroadcast(this, id, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        (getSystemService(ALARM_SERVICE) as AlarmManager).cancel(pi)
    }

    private fun showReminderNotif(v: JSONObject) {
        val id = v.optString("id").hashCode() + 1000
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val b = if (Build.VERSION.SDK_INT >= 26)
            android.app.Notification.Builder(this, "yakala_rem")
        else @Suppress("DEPRECATION") android.app.Notification.Builder(this)
        b.setSmallIcon(R.drawable.ic_yakala)
            .setContentTitle("📬 " + v.optString("fromName"))
            .setContentText(v.optString("text"))
            .setStyle(android.app.Notification.BigTextStyle().bigText(v.optString("text")))
            .setAutoCancel(true)
        nm.notify(id, b.build())
    }

    private fun scheduleFriendReminder(v: JSONObject, at: Long) {
        val id = v.optString("id").hashCode()
        val i = Intent(this, ReminderReceiver::class.java).apply {
            putExtra("text", "🔔 " + v.optString("fromName") + ": " + v.optString("text"))
            putExtra("id", id)
            putExtra("alarm", v.optString("reminderKind") == "alarm")
        }
        val pi = PendingIntent.getBroadcast(this, id, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        (getSystemService(ALARM_SERVICE) as AlarmManager)
            .setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
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
            Toast.makeText(this, L("mic"), Toast.LENGTH_SHORT).show()
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
            voiceBtn.text = L("stop")
            voiceBtn.background = grad(intArrayOf(0xFFdc2626.toInt(), 0xFFef4444.toInt()), 16)
            statusText.text = L("rec")
            tick()
        } catch (e: Exception) {
            Toast.makeText(this, "Kayıt hatası: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun tick() {
        if (mode != 1) return
        val s = (System.currentTimeMillis() - recordStart) / 1000
        statusText.text = "🔴 ${fmtDur(s)} " + L("stop")
        handler.postDelayed({ tick() }, 1000)
    }

    private fun stopAudio() {
        mode = 0
        try { recorder?.stop() } catch (_: Exception) {}
        recorder?.release(); recorder = null
        voiceBtn.text = L("voice")
        voiceBtn.background = grad(intArrayOf(0xFF0891b2.toInt(), 0xFF1d4ed8.toInt()), 16)
        val f = currentFile
        if (f != null && f.exists() && f.length() > 0) {
            val o = JSONObject()
            o.put("id", System.currentTimeMillis())
            o.put("type", "voice"); o.put("audioPath", f.absolutePath)
            o.put("duration", (System.currentTimeMillis() - recordStart) / 1000)
            o.put("transcript", ""); o.put("pinned", false)
            o.put("createdAt", System.currentTimeMillis())
            notes.add(0, o)
            persist()
            statusText.text = L("vsaved")
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
            override fun onReadyForSpeech(p: Bundle?) { statusText.text = L("listen") }
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
        textBtn.text = L("stop")
        textBtn.background = grad(intArrayOf(0xFFdc2626.toInt(), 0xFFef4444.toInt()), 16)
        statusText.text = L("listen")
    }

    private fun stopText() {
        mode = 0
        recognizer?.destroy(); recognizer = null
        textBtn.text = L("stt")
        textBtn.background = grad(intArrayOf(0xFFf97316.toInt(), 0xFFec4899.toInt()), 16)
        val tr = transcript.toString().trim()
        if (tr.isNotEmpty()) {
            if (!processCommand(tr)) { addTextNote(tr); statusText.text = L("saved") }
        }
        else statusText.text = L("noSpeech")
    }

    private fun playNote(n: JSONObject) {
        try {
            player?.release()
            player = MediaPlayer()
            player!!.setDataSource(n.optString("audioPath"))
            player!!.prepare(); player!!.start()
            Toast.makeText(this, L("playing"), Toast.LENGTH_SHORT).show()
            player!!.setOnCompletionListener { player?.release(); player = null }
        } catch (e: Exception) {
            Toast.makeText(this, L("noPlay"), Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
        super.onDestroy()
    }
}
