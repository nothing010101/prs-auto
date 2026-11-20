package com.luna.prsauto

import android.content.Context
import android.content.Intent
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Bus log sederhana: simpan baris log di memori, siarkan ke MainActivity,
 * dan tulis juga ke file prs-auto.log di penyimpanan internal aplikasi
 * (berguna untuk debug setelah sesi berjalan).
 */
object LogBus {
    const val ACTION = "com.luna.prsauto.LOG"
    const val EXTRA_LINE = "line"

    private val lines = ArrayList<String>()
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    @Synchronized
    fun i(ctx: Context?, msg: String) {
        val line = "[${timeFmt.format(Date())}] $msg"
        lines.add(line)
        if (lines.size > 300) lines.removeAt(0)
        try {
            ctx?.sendBroadcast(Intent(ACTION).putExtra(EXTRA_LINE, line))
        } catch (_: Exception) {
        }
        try {
            ctx?.openFileOutput("prs-auto.log", Context.MODE_APPEND)?.use {
                it.write((line + "\n").toByteArray(Charsets.UTF_8))
            }
        } catch (_: Exception) {
        }
    }

    @Synchronized
    fun snapshot(): List<String> = lines.toList()
}
