package com.yakala.app

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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
        val input = EditText(this).apply {
            hint = "Aklına ne geldi?"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
        }
        val save = Button(this).apply {
            text = "✓ Kaydet"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#f59e0b"))
        }
        val list = ListView(this)
        layout.addView(title)
        layout.addView(input)
        layout.addView(save)
        layout.addView(list)
        setContentView(layout)

        val prefs = getSharedPreferences("yakala", MODE_PRIVATE)
        fun notes(): MutableList<String> =
            prefs.getString("notes", "")!!.split("|||").filter { it.isNotBlank() }.toMutableList()

        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, notes())
        list.adapter = adapter

        fun refresh() {
            val all = notes()
            adapter.clear()
            adapter.addAll(all)
            adapter.notifyDataSetChanged()
        }

        fun addNote(t: String) {
            if (t.isBlank()) return
            val all = notes()
            all.add(0, t)
            prefs.edit().putString("notes", all.joinToString("|||")).apply()
            refresh()
        }

        save.setOnClickListener {
            addNote(input.text.toString())
            input.setText("")
        }

        // Paylaş menüsünden gelen metni not yap
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            addNote(intent.getStringExtra(Intent.EXTRA_TEXT) ?: "")
        }
    }
}
