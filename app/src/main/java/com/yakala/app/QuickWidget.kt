package com.yakala.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

class QuickWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        for (id in ids) {
            val views = RemoteViews(context.packageName, R.layout.widget_quick)
            views.setOnClickPendingIntent(R.id.w_title, pending(context, "text"))
            views.setOnClickPendingIntent(R.id.w_text, pending(context, "text"))
            views.setOnClickPendingIntent(R.id.w_audio, pending(context, "audio"))
            views.setOnClickPendingIntent(R.id.w_stt, pending(context, "stt"))
            mgr.updateAppWidget(id, views)
        }
    }

    private fun pending(context: Context, mode: String): PendingIntent {
        val i = Intent(context, MainActivity::class.java).apply {
            putExtra("quick", mode)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        return PendingIntent.getActivity(
            context, mode.hashCode(), i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
