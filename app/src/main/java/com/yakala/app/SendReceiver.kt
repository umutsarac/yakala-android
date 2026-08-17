package com.yakala.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

class SendReceiver : BroadcastReceiver() {
    private val API_KEY = "AIzaSyDL4NWpuudvTu-ggKEX_pw_sVkwkGUlOzA"
    private val DEFAULT_SERVER = "https://yakala-7ba1c-default-rtdb.europe-west1.firebasedatabase.app"

    override fun onReceive(c: Context, i: Intent) {
        val res = goAsync()
        val to = i.getStringExtra("to")
        val text = i.getStringExtra("text")
        if (to == null || text == null) { res.finish(); return }
        val remAt = i.getLongExtra("remAt", 0L)
        val remKind = i.getStringExtra("remKind") ?: "reminder"
        Thread {
            try {
                val prefs = c.getSharedPreferences("yakala", Context.MODE_PRIVATE)
                ensureAuth(prefs)
                val tok = prefs.getString("idToken", null)
                val me = prefs.getString("uid", null)
                val server = prefs.getString("server", null) ?: DEFAULT_SERVER
                if (tok != null && me != null) {
                    val gr = get("$server/users/$me/grants/$to.json?auth=$tok")
                    val pending = !(gr != null && gr != "null" && JSONObject(gr).optString("status") == "full")
                    val myCode = prefs.getString("myCode", "")
                    val myName = prefs.getString("myName", null) ?: "Yakala-$myCode"
                    val o = JSONObject().apply {
                        put("from", myCode); put("fromName", myName)
                        put("text", text); put("time", System.currentTimeMillis())
                        put("pending", pending); put("kind", "timed")
                        if (remAt > 0) { put("reminderAt", remAt); put("reminderKind", remKind) }
                    }
                    val actAt = i.getLongExtra("actAt", 0L)
                    if (actAt > 0) o.put("activateAt", actAt)
                    val key = "s" + System.currentTimeMillis()
                    putReq("$server/users/$to/inbox/$key.json?auth=$tok", o.toString())
                    val so = JSONObject().apply {
                        put("to", to); put("toName", i.getStringExtra("toName") ?: "")
                        put("text", text); put("time", System.currentTimeMillis())
                        if (remAt > 0) { put("reminderAt", remAt); put("reminderKind", remKind) }
                    }
                    val arr2 = org.json.JSONArray()
                    arr2.put(so)
                    val oldSent = prefs.getString("sent", null)
                    if (oldSent != null) { try { val oa = org.json.JSONArray(oldSent); for (x in 0 until oa.length()) arr2.put(oa.get(x)) } catch (_: Exception) {} }
                    prefs.edit().putString("sent", arr2.toString()).apply()
                }
            } catch (_: Exception) {}
            res.finish()
        }.start()
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

    private fun post(url: String, body: String): String? = try {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.doOutput = true
        c.connectTimeout = 6000; c.readTimeout = 6000
        c.setRequestProperty("Content-Type", "application/json")
        c.outputStream.use { it.write(body.toByteArray()) }
        if (c.responseCode in 200..299) c.inputStream.readBytes().toString(Charsets.UTF_8) else null
    } catch (e: Exception) { null }

    private fun putReq(url: String, body: String): String? = try {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = "PUT"; c.doOutput = true
        c.connectTimeout = 6000; c.readTimeout = 6000
        c.setRequestProperty("Content-Type", "application/json")
        c.outputStream.use { it.write(body.toByteArray()) }
        if (c.responseCode in 200..299) c.inputStream.readBytes().toString(Charsets.UTF_8) else null
    } catch (e: Exception) { null }

    private fun get(url: String): String? = try {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 6000; c.readTimeout = 6000
        if (c.responseCode in 200..299) c.inputStream.readBytes().toString(Charsets.UTF_8) else null
    } catch (e: Exception) { null }
}
