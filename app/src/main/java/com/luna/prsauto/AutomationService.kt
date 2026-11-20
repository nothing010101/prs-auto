package com.luna.prsauto

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

/**
 * Otomasi tugas Follow Instagram di pr.social lewat AccessibilityService.
 *
 * Alur per tugas:
 *  tasks -> filter Instagram -> kartu tugas -> [Mulai] -> buka profil IG
 *  -> [Ikuti] -> tunggu [Diikuti] -> screenshot (MediaProjection)
 *  -> Back -> [Tambah tangkapan layar] -> picker: item pertama -> [Selesai]
 *  -> tunggu "Tangkapan layar terkirim" -> [Tugas berikutnya] -> hitung mundur
 *  -> [Mulai] tugas berikutnya (diulang sampai max tugas / BERHENTI).
 *
 * Semua langkah berjalan di worker thread; UI thread tidak pernah diblokir
 * lama. Satu langkah gagal 3x berturut-turut -> berhenti dengan aman.
 */
class AutomationService : AccessibilityService() {

    companion object {
        const val CMD_ACTION = "com.luna.prsauto.CMD"
        const val CMD_START = "start"
        const val CMD_STOP = "stop"
        const val EXTRA_CMD = "cmd"

        private const val NOTIF_CHANNEL = "prs_auto"
        private const val NOTIF_ID = 1001
        private const val CHROME_PKG = "com.android.chrome"

        /**
         * True sejak onServiceConnected() sampai service mati. Ini KONEKSI
         * HIDUP, bukan sekadar setelan AKTIF — MainActivity membacanya
         * sebelum MULAI, supaya perintah yang tidak sampai ke service
         * tidak gagal diam-diam.
         */
        @Volatile
        var isConnected = false
    }

    private class StepFail(message: String) : Exception(message)
    private class Stopped : Exception()

    private val running = AtomicBoolean(false)
    private var workerThread: HandlerThread? = null
    private var workerHandler: Handler? = null

    private var doneCount = 0
    private var earnedUsd = 0.0
    private var consecutiveFails = 0
    private var currentTarget = ""

    private val cmdReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.getStringExtra(EXTRA_CMD)) {
                CMD_START -> startRun()
                CMD_STOP -> stopRun("Dihentikan dari aplikasi")
            }
        }
    }

    // ------------------------------------------------------------------
    // Siklus hidup service
    // ------------------------------------------------------------------

    override fun onServiceConnected() {
        super.onServiceConnected()
        isConnected = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(cmdReceiver, IntentFilter(CMD_ACTION), RECEIVER_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(cmdReceiver, IntentFilter(CMD_ACTION))
        }
        LogBus.i(this, "PRS Auto service terhubung. Tekan MULAI di aplikasi.")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Loop utama memakai polling; event hanya dicatat ringan.
    }

    override fun onInterrupt() {
        isConnected = false
        stopRun("Service di-interrupt sistem")
    }

    override fun onDestroy() {
        isConnected = false
        try {
            unregisterReceiver(cmdReceiver)
        } catch (_: Exception) {
        }
        stopWorker()
        super.onDestroy()
    }

    // ------------------------------------------------------------------
    // MULAI / BERHENTI
    // ------------------------------------------------------------------

    private fun startRun() {
        // Baris ini membedakan dua kegagalan diam-diam: kalau baris ini
        // TIDAK muncul di log, broadcast MULAI tidak pernah sampai ke
        // service (service belum terhubung — matikan lalu nyalakan lagi
        // Aksesibilitas). Kalau baris ini muncul tapi "PRS Auto MULAI"
        // tidak, service mati di bawah (dulu: startForeground tanpa
        // try/catch, exception membunuh service tanpa jejak).
        log("Perintah MULAI diterima.")
        if (running.get()) {
            log("Sudah jalan, abaikan MULAI ganda.")
            return
        }
        if (!ScreenCapture.isReady()) {
            log("Izin screenshot belum diberikan. Tekan MULAI lagi setelah menyetujui dialog screenshot.")
            return
        }
        running.set(true)
        doneCount = 0
        earnedUsd = 0.0
        consecutiveFails = 0
        try {
            startForegroundWithType()
        } catch (e: Exception) {
            running.set(false)
            log("Gagal start foreground service: ${e.message}. Coba tekan MULAI lagi dari aplikasi (jangan dari Chrome).")
            return
        }
        workerThread = HandlerThread("prs-auto-worker").also { it.start() }
        workerHandler = Handler(workerThread!!.looper)
        workerHandler?.post { runAutomation() }
        log("PRS Auto MULAI — max ${Prefs.maxTasks(this)} tugas.")
    }

    private fun stopRun(reason: String) {
        if (!running.getAndSet(false)) return
        log("PRS Auto BERHENTI: $reason")
        stopWorker()
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (_: Exception) {
        }
        ScreenCapture.release()
    }

    private fun stopWorker() {
        try {
            workerThread?.quitSafely()
        } catch (_: Exception) {
        }
        workerThread = null
        workerHandler = null
    }

    private fun startForegroundWithType() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            NOTIF_CHANNEL, "PRS Auto",
            NotificationManager.IMPORTANCE_LOW
        )
        nm.createNotificationChannel(channel)
        val notif = Notification.Builder(this, NOTIF_CHANNEL)
            .setContentTitle("PRS Auto berjalan")
            .setContentText("Mengotomasi tugas pr.social…")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .build()
        // Android 14: MediaProjection wajib dari foreground service
        // bertipe mediaProjection.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIF_ID, notif,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            @Suppress("DEPRECATION")
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun updateNotif(text: String) {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val notif = Notification.Builder(this, NOTIF_CHANNEL)
                .setContentTitle("PRS Auto berjalan")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setOngoing(true)
                .build()
            nm.notify(NOTIF_ID, notif)
        } catch (_: Exception) {
        }
    }

    // ------------------------------------------------------------------
    // Loop utama
    // ------------------------------------------------------------------

    private fun runAutomation() {
        try {
            log("Menunggu halaman tugas pr.social…")
            if (!waitForTrue(20_000) { tasksPageVisible() }) {
                throw StepFail("Halaman tugas tidak ketemu. Buka Chrome ke pr.social/tasks lalu MULAI lagi.")
            }
            var firstTask = true
            while (running.get() && doneCount < Prefs.maxTasks(this)) {
                try {
                    if (firstTask) {
                        ensureInstagramFilter()
                        pickTask()
                        firstTask = false
                    }
                    clickMulaiAndWaitLink()
                    openProfile()
                    followProfile()
                    takeScreenshotStep()
                    backToPrSocial()
                    clickAddScreenshot()
                    pickFirstInPicker()
                    waitSubmitted()
                    doneCount++
                    consecutiveFails = 0
                    val msg = "Tugas selesai: $doneCount/${Prefs.maxTasks(this)}" +
                        " (+$$${"%.4f".format(earnedUsd)})"
                    log(msg)
                    updateNotif(msg)
                    if (doneCount >= Prefs.maxTasks(this)) break
                    advanceToNextTask()
                } catch (e: StepFail) {
                    consecutiveFails++
                    log("Gagal: ${e.message} (gagal beruntun $consecutiveFails/3)")
                    if (consecutiveFails >= 3) {
                        log("Berhenti: 3 gagal beruntun.")
                        break
                    }
                    recover()
                    firstTask = true
                }
            }
            log("Sesi selesai. Total: $doneCount tugas, +$$${"%.4f".format(earnedUsd)}.")
        } catch (e: Stopped) {
            log("Dihentikan di tengah jalan. Total: $doneCount tugas.")
        } catch (e: Exception) {
            log("Error tak terduga: ${e.message}")
        } finally {
            running.set(false)
            try {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } catch (_: Exception) {
            }
            ScreenCapture.release()
            updateNotif("PRS Auto berhenti.")
        }
    }

    // ------------------------------------------------------------------
    // Langkah-langkah
    // ------------------------------------------------------------------

    private fun tasksPageVisible(): Boolean {
        val root = rootInActiveWindow ?: return false
        // Judul "Tugas" + salah satu filter platform terlihat.
        return findText(root, "Tugas", Match.EXACT) != null &&
            (findText(root, "Semua", Match.EXACT) != null ||
                findText(root, "Ikuti di Instagram", Match.CONTAINS) != null)
    }

    private fun ensureInstagramFilter() {
        val root = rootInActiveWindow ?: return
        if (findText(root, "Ikuti di Instagram", Match.CONTAINS) != null) {
            log("Filter Instagram sudah aktif.")
            return
        }
        // Tombol filter Instagram menampilkan jumlah seperti "2.7K".
        val btn = findByRegex(root, Regex("\\d+(\\.\\d+)?K"))?.let { clickableAncestor(it) }
        if (btn == null) {
            log("Tombol filter IG tidak ketemu, lanjut apa adanya.")
            return
        }
        click(btn)
        val ok = waitForTrue(12_000) {
            rootInActiveWindow?.let {
                findText(it, "Ikuti di Instagram", Match.CONTAINS) != null
            } == true
        }
        log(if (ok) "Filter Instagram aktif." else "Filter IG diklik, kartu IG belum terlihat.")
    }

    private fun pickTask() {
        val card = waitFor(20_000) {
            rootInActiveWindow
                ?.let { findText(it, "Ikuti di Instagram", Match.CONTAINS) }
                ?.let { clickableAncestor(it) }
        } ?: throw StepFail("Kartu tugas IG tidak ketemu")
        click(card)
        val opened = waitForTrue(12_000) {
            rootInActiveWindow?.let {
                findText(it, "Sedang dikerjakan", Match.CONTAINS) != null ||
                    findText(it, "Mulai", Match.STARTS_WITH) != null
            } == true
        }
        if (!opened) throw StepFail("Modal tugas tidak terbuka")
        currentTarget = readTargetFromModal()
        log("Tugas: $currentTarget")
    }

    private fun readTargetFromModal(): String {
        val root = rootInActiveWindow ?: return ""
        val node = findText(root, "Sedang dikerjakan", Match.CONTAINS)
            ?: return ""
        val text = node.text?.toString() ?: ""
        val at = text.indexOf('@')
        if (at < 0) return ""
        return text.substring(at).trim().split(Regex("\\s"))[0]
    }

    private fun clickMulaiAndWaitLink() {
        val root = rootInActiveWindow
        // Kalau tugas sudah dimulai sebelumnya, link profil langsung ada.
        if (root != null && findText(root, "www.instagram.com/", Match.STARTS_WITH) != null) {
            log("Tugas sudah dimulai sebelumnya, lanjut buka profil.")
            return
        }
        val mulai = waitFor(15_000) {
            rootInActiveWindow
                ?.let { findText(it, "Mulai", Match.STARTS_WITH) }
                ?.let { clickableAncestor(it) }
        } ?: throw StepFail("Tombol Mulai tidak ketemu")
        // Catat bayaran dari teks tombol ("Mulai · $0.0028").
        parsePay(mulai.text?.toString())
        click(mulai)
        val linkOk = waitForTrue(12_000) {
            rootInActiveWindow?.let {
                findText(it, "www.instagram.com/", Match.STARTS_WITH) != null
            } == true
        }
        if (!linkOk) throw StepFail("Link profil tidak muncul setelah Mulai")
    }

    private fun openProfile() {
        val root = rootInActiveWindow ?: throw StepFail("Tidak ada jendela aktif")
        val urlNode = findText(root, "www.instagram.com/", Match.STARTS_WITH)
            ?: throw StepFail("Link profil tidak ketemu")
        // Ikon ↗ ada di baris yang sama dengan URL; cari tombol klik di
        // sekitarnya. Fallback: klik teks "Buka profil dan ikuti".
        var clicked = false
        val parent = urlNode.parent
        if (parent != null) {
            for (i in 0 until parent.childCount) {
                val child = parent.getChild(i) ?: continue
                if (child.isClickable && child.text.isNullOrEmpty()) {
                    clicked = click(child)
                    if (clicked) break
                }
            }
        }
        if (!clicked) {
            val header = findText(root, "Buka profil dan ikuti", Match.CONTAINS)
            if (header != null) clicked = click(clickableAncestor(header) ?: header)
        }
        if (!clicked) throw StepFail("Tidak bisa membuka profil IG")
        val targetName = currentTarget.trimStart('@')
        val ok = waitForTrue(20_000) {
            val r = rootInActiveWindow ?: return@waitForTrue false
            r.packageName == CHROME_PKG &&
                findText(r, targetName, Match.EXACT) != null &&
                (findText(r, "Ikuti", Match.EXACT) != null ||
                    findText(r, "Diikuti", Match.STARTS_WITH) != null)
        }
        if (!ok) throw StepFail("Profil IG $currentTarget tidak terbuka")
        log("Profil IG terbuka: $currentTarget")
    }

    private fun followProfile() {
        if (findMainDiikuti() != null) {
            log("Sudah mengikuti $currentTarget, lanjut screenshot.")
            return
        }
        val btn = findMainIkutiButton()
            ?: throw StepFail("Tombol Ikuti utama tidak ketemu")
        click(btn)
        var ok = waitForTrue(20_000) { findMainDiikuti() != null }
        if (!ok) {
            // Coba klik sekali lagi (IG kadang butuh klik kedua).
            val btn2 = findMainIkutiButton()
            if (btn2 != null) {
                click(btn2)
                ok = waitForTrue(15_000) { findMainDiikuti() != null }
            }
        }
        if (!ok) throw StepFail("Tombol tidak jadi Diikuti setelah 2x klik")
        log("Follow OK — $currentTarget Diikuti.")
    }

    /**
     * Tombol "Ikuti" UTAMA profil: yang paling atas di layar dan BUKAN di
     * bawah judul "Disarankan untuk Anda" (tombol suggested accounts).
     */
    private fun findMainIkutiButton(): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        val suggTop = findText(root, "Disarankan untuk Anda", Match.EXACT)
            ?.let { boundsOf(it).top } ?: Int.MAX_VALUE
        return findAllText(root, "Ikuti", Match.EXACT)
            .filter { boundsOf(it).centerY() < suggTop }
            .minByOrNull { boundsOf(it).top }
    }

    /** Tombol "Diikuti" utama (teks diawali "Diikuti", kadang + ⌄). */
    private fun findMainDiikuti(): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        val suggTop = findText(root, "Disarankan untuk Anda", Match.EXACT)
            ?.let { boundsOf(it).top } ?: Int.MAX_VALUE
        return findAllText(root, "Diikuti", Match.STARTS_WITH)
            .filter { boundsOf(it).centerY() < suggTop }
            .minByOrNull { boundsOf(it).top }
    }

    private fun takeScreenshotStep() {
        sleepChunked(600)
        val uri = ScreenCapture.capture(this)
            ?: throw StepFail("Screenshot gagal (tidak ada frame)")
        log("Screenshot disimpan: $uri")
    }

    private fun backToPrSocial() {
        performGlobalAction(GLOBAL_ACTION_BACK)
        val ok = waitForTrue(15_000) {
            rootInActiveWindow?.let {
                findText(it, "Tambah tangkapan layar", Match.STARTS_WITH) != null ||
                    findText(it, "Tambahkan tangkapan layar", Match.CONTAINS) != null
            } == true
        }
        if (!ok) throw StepFail("Tidak kembali ke modal pr.social")
    }

    private fun clickAddScreenshot() {
        val btn = waitFor(10_000) {
            rootInActiveWindow?.let { root ->
                findText(root, "Tambah tangkapan layar", Match.EXACT)
                    ?.let { clickableAncestor(it) }
                    ?: findText(root, "Tambahkan tangkapan layar", Match.CONTAINS)
                        ?.let { clickableAncestor(it) }
            }
        } ?: throw StepFail("Tombol Tambah tangkapan layar tidak ketemu")
        click(btn)
        val ok = waitForTrue(15_000) {
            rootInActiveWindow?.let {
                findText(it, "Telusuri Google Foto", Match.CONTAINS) != null ||
                    findText(it, "Koleksi", Match.EXACT) != null
            } == true
        }
        if (!ok) throw StepFail("File picker tidak terbuka")
        log("File picker terbuka.")
    }

    /**
     * Screenshot terbaru selalu ada di posisi pertama (paling kiri atas)
     * grid tab Foto. Pilih item pertama, lalu tap "Selesai".
     */
    private fun pickFirstInPicker() {
        sleepChunked(1200)
        val root = rootInActiveWindow ?: throw StepFail("Picker tidak ada")
        val fotoTab = findText(root, "Foto", Match.EXACT)
        val gridTop = fotoTab?.let { boundsOf(it).bottom } ?: 0
        val candidates = ArrayList<AccessibilityNodeInfo>()
        collectAll(root) { n ->
            val b = boundsOf(n)
            if (n.className == "android.widget.ImageView" && n.isClickable &&
                b.top > gridTop && b.height() > 100 && b.width() > 100
            ) candidates.add(n)
        }
        if (candidates.isEmpty()) {
            // Fallback: node klik pertama di bawah tab, apa pun kelasnya.
            collectAll(root) { n ->
                val b = boundsOf(n)
                if (n.isClickable && b.top > gridTop && b.height() > 150 && b.width() > 150) {
                    candidates.add(n)
                }
            }
        }
        val first = candidates
            .sortedWith(compareBy({ boundsOf(it).top }, { boundsOf(it).left }))
            .firstOrNull() ?: throw StepFail("Item pertama picker tidak ketemu")
        click(first)
        log("Item pertama picker dipilih.")
        // Setelah memilih, tap "Selesai". Kalau tidak muncul (tap langsung
        // memilih file), lanjut ke tunggu upload.
        val selesai = waitFor(8_000) {
            rootInActiveWindow
                ?.let { findText(it, "Selesai", Match.EXACT) }
                ?.let { clickableAncestor(it) }
        }
        if (selesai != null) {
            click(selesai)
            log("Tombol Selesai ditekan.")
        } else {
            log("Tombol Selesai tidak muncul — lanjut tunggu upload.")
        }
    }

    private fun waitSubmitted() {
        val ok = waitForTrue(60_000) {
            rootInActiveWindow?.let {
                findText(it, "Tangkapan layar terkirim", Match.CONTAINS) != null ||
                    findText(it, "Sedang ditinjau", Match.CONTAINS) != null
            } == true
        }
        if (!ok) throw StepFail("Upload tidak terkonfirmasi (60 dtk)")
        log("Upload terkirim, sedang ditinjau.")
    }

    private fun advanceToNextTask() {
        // Kasus 1: tombol "Tugas berikutnya" (setelah upload).
        val nextBtn = waitFor(8_000) {
            rootInActiveWindow
                ?.let { findText(it, "Tugas berikutnya", Match.CONTAINS) }
                ?.let { clickableAncestor(it) }
        }
        if (nextBtn != null) {
            click(nextBtn)
        }
        // Kasus 2: modal tugas berikutnya sudah terbuka (hitung mundur).
        val mulai = waitFor(20_000) {
            rootInActiveWindow
                ?.let { findText(it, "Mulai", Match.STARTS_WITH) }
                ?.let { clickableAncestor(it) }
        } ?: throw StepFail("Modal tugas berikutnya tidak muncul")
        // Tunggu sampai teksnya jadi "Mulai · $..." (hitung mundur selesai).
        val ready = waitForTrue(20_000) {
            rootInActiveWindow?.let {
                val t = findText(it, "Mulai", Match.STARTS_WITH)?.text?.toString() ?: ""
                t.startsWith("Mulai · $") || t.startsWith("Mulai ·")
            } == true
        }
        if (!ready) throw StepFail("Hitung mundur tugas berikutnya tidak selesai")
        // Jeda acak antar tugas (konfigurabel).
        val min = Prefs.delayMinSec(this)
        val max = Prefs.delayMaxSec(this)
        val delay = if (max > min) Random.nextInt(min, max + 1) else min
        log("Jeda $delay dtk sebelum tugas berikutnya…")
        sleepChunked(delay * 1000L)
    }

    private fun recover() {
        try {
            performGlobalAction(GLOBAL_ACTION_BACK)
            sleepChunked(1000)
            performGlobalAction(GLOBAL_ACTION_BACK)
            sleepChunked(1000)
        } catch (_: Exception) {
        }
    }

    // ------------------------------------------------------------------
    // Helper: cari node
    // ------------------------------------------------------------------

    private enum class Match { EXACT, STARTS_WITH, CONTAINS }

    private fun textMatches(text: CharSequence?, query: String, match: Match): Boolean {
        val t = text?.toString() ?: return false
        return when (match) {
            Match.EXACT -> t == query
            Match.STARTS_WITH -> t.startsWith(query)
            Match.CONTAINS -> t.contains(query)
        }
    }

    private fun findText(
        root: AccessibilityNodeInfo,
        query: String,
        match: Match,
    ): AccessibilityNodeInfo? {
        if (textMatches(root.text, query, match) ||
            textMatches(root.contentDescription, query, match)
        ) return root
        for (i in 0 until root.childCount) {
            val child = root.getChild(i) ?: continue
            val found = findText(child, query, match)
            if (found != null) return found
        }
        return null
    }

    private fun findAllText(
        root: AccessibilityNodeInfo,
        query: String,
        match: Match,
    ): List<AccessibilityNodeInfo> {
        val out = ArrayList<AccessibilityNodeInfo>()
        collectAll(root) { n ->
            if (textMatches(n.text, query, match) ||
                textMatches(n.contentDescription, query, match)
            ) out.add(n)
        }
        return out
    }

    private fun findByRegex(root: AccessibilityNodeInfo, regex: Regex): AccessibilityNodeInfo? {
        var found: AccessibilityNodeInfo? = null
        collectAll(root) { n ->
            if (found == null && n.text != null && regex.containsMatchIn(n.text.toString())) {
                found = n
            }
        }
        return found
    }

    private fun collectAll(root: AccessibilityNodeInfo, visit: (AccessibilityNodeInfo) -> Unit) {
        visit(root)
        for (i in 0 until root.childCount) {
            val child = root.getChild(i) ?: continue
            collectAll(child, visit)
        }
    }

    private fun clickableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var cur: AccessibilityNodeInfo? = node
        var hops = 0
        while (cur != null && hops < 6) {
            if (cur.isClickable) return cur
            cur = cur.parent
            hops++
        }
        return null
    }

    private fun boundsOf(node: AccessibilityNodeInfo): Rect {
        val r = Rect()
        node.getBoundsInScreen(r)
        return r
    }

    // ------------------------------------------------------------------
    // Helper: klik / tunggu / tidur
    // ------------------------------------------------------------------

    private fun click(node: AccessibilityNodeInfo): Boolean {
        if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        val ancestor = clickableAncestor(node)
        if (ancestor != null && ancestor !== node &&
            ancestor.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        ) return true
        // Fallback: tap lewat gesture di tengah bounds.
        val target = ancestor ?: node
        val b = boundsOf(target)
        if (b.isEmpty) return false
        return tapAt(b.centerX().toFloat(), b.centerY().toFloat())
    }

    private fun tapAt(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 80))
            .build()
        val done = AtomicBoolean(false)
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                done.set(true)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                done.set(false)
            }
        }, null)
        val deadline = System.currentTimeMillis() + 1500
        while (!done.get() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50)
        }
        return done.get()
    }

    private fun <T> waitFor(timeoutMs: Long, condition: () -> T?): T? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (running.get() && System.currentTimeMillis() < deadline) {
            val result = try {
                condition()
            } catch (_: Exception) {
                null
            }
            if (result != null) return result
            sleepChunked(400)
        }
        return null
    }

    private fun waitForTrue(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (running.get() && System.currentTimeMillis() < deadline) {
            val ok = try {
                condition()
            } catch (_: Exception) {
                false
            }
            if (ok) return true
            sleepChunked(400)
        }
        return false
    }

    /** Tidur yang bisa dibatalkan BERHENTI (dicek tiap 100 ms). */
    private fun sleepChunked(ms: Long) {
        var left = ms
        while (left > 0 && running.get()) {
            val step = minOf(100L, left)
            Thread.sleep(step)
            left -= step
        }
        if (!running.get()) throw Stopped()
    }

    private fun parsePay(buttonText: String?) {
        if (buttonText == null) return
        val m = Regex("\\\$0\\.(\\d{4})").find(buttonText) ?: return
        try {
            earnedUsd += ("0." + m.groupValues[1]).toDouble()
        } catch (_: Exception) {
        }
    }

    private fun log(msg: String) {
        LogBus.i(this, msg)
    }
}
