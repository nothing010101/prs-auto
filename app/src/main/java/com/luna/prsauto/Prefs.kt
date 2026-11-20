package com.luna.prsauto

import android.content.Context

/** Pengaturan sesi, disimpan di SharedPreferences. */
object Prefs {
    private const val NAME = "prs_auto"

    const val KEY_MAX_TASKS = "max_tasks"
    const val KEY_DELAY_MIN = "delay_min_sec"
    const val KEY_DELAY_MAX = "delay_max_sec"

    fun maxTasks(ctx: Context): Int =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).getInt(KEY_MAX_TASKS, 20)

    fun delayMinSec(ctx: Context): Int =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).getInt(KEY_DELAY_MIN, 5)

    fun delayMaxSec(ctx: Context): Int =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).getInt(KEY_DELAY_MAX, 15)

    fun save(ctx: Context, maxTasks: Int, delayMin: Int, delayMax: Int) {
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit()
            .putInt(KEY_MAX_TASKS, maxTasks.coerceIn(1, 200))
            .putInt(KEY_DELAY_MIN, delayMin.coerceIn(0, 300))
            .putInt(KEY_DELAY_MAX, maxOf(delayMax, delayMin).coerceIn(0, 300))
            .apply()
    }
}
