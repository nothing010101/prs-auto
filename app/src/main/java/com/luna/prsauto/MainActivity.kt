package com.luna.prsauto

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * Activity utama PRS Auto: checklist persiapan (aksesibilitas, baterai,
 * autostart — wajib di Xiaomi/HyperOS), pengaturan sesi, tombol
 * MULAI/BERHENTI, dan log.
 *
 * Alur MULAI:
 *  1. Simpan pengaturan sesi.
 *  2. Pastikan AccessibilityService aktif.
 *  3. Minta izin screenshot (MediaProjection) sekali per sesi.
 *  4. Buka Chrome ke pr.social/tasks.
 *  5. Kirim perintah "start" ke AutomationService.
 */
class MainActivity : Activity() {

    private lateinit var statusText: TextView
    private lateinit var checkA11y: TextView
    private lateinit var checkBattery: TextView
    private lateinit var logText: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var etMaxTasks: EditText
    private lateinit var etDelayMin: EditText
    private lateinit var etDelayMax: EditText

    private val logReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val line = intent?.getStringExtra(LogBus.EXTRA_LINE) ?: return
            runOnUiThread {
                logText.append(line + "\n")
                logScroll.post { logScroll.fullScroll(ScrollView.FOCUS_DOWN) }
                if (line.contains("MULAI")) statusText.text = "Status: berjalan"
                if (line.contains("BERHENTI") || line.contains("Sesi selesai")) {
                    statusText.text = "Status: berhenti"
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        checkA11y = findViewById(R.id.checkA11y)
        checkBattery = findViewById(R.id.checkBattery)
        logText = findViewById(R.id.logText)
        logScroll = findViewById(R.id.logScroll)
        etMaxTasks = findViewById(R.id.etMaxTasks)
        etDelayMin = findViewById(R.id.etDelayMin)
        etDelayMax = findViewById(R.id.etDelayMax)

        etMaxTasks.setText(Prefs.maxTasks(this).toString())
        etDelayMin.setText(Prefs.delayMinSec(this).toString())
        etDelayMax.setText(Prefs.delayMaxSec(this).toString())

        findViewById<Button>(R.id.btnA11y).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<Button>(R.id.btnBattery).setOnClickListener {
            requestNoBatteryRestriction()
        }
        findViewById<Button>(R.id.btnAutostart).setOnClickListener {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", packageName, null)
            }
            startActivity(intent)
            Toast.makeText(
                this,
                "Di Info aplikasi: izinkan Autostart, dan pastikan Baterai = Tanpa batasan.",
                Toast.LENGTH_LONG
            ).show()
        }
        findViewById<Button>(R.id.btnStart).setOnClickListener { onStartClicked() }
        findViewById<Button>(R.id.btnStop).setOnClickListener { onStopClicked() }

        // Notifikasi status (Android 13+). Bukan fatal kalau ditolak.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1002
            )
        }

        for (line in LogBus.snapshot()) logText.append(line + "\n")
    }

    override fun onResume() {
        super.onResume()
        refreshChecklist()
        val filter = IntentFilter(LogBus.ACTION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(logReceiver, filter, RECEIVER_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(logReceiver, filter)
        }
    }

    override fun onPause() {
        try {
            unregisterReceiver(logReceiver)
        } catch (_: Exception) {
        }
        super.onPause()
    }

    private fun refreshChecklist() {
        checkA11y.text = if (isAccessibilityEnabled()) {
            "1. Aksesibilitas: AKTIF ✓"
        } else {
            "1. Aksesibilitas: BELUM AKTIF — tekan tombol di bawah, cari PRS Auto, aktifkan."
        }
        checkBattery.text = if (isBatteryUnrestricted()) {
            "2. Baterai: Tanpa batasan ✓"
        } else {
            "2. Baterai: MASIH DIBATASI — HyperOS bisa mematikan service di tengah jalan. Tekan tombol di bawah."
        }
    }

    private fun isAccessibilityEnabled(): Boolean {
        val enabled = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabled.contains(ComponentName(this, AutomationService::class.java).flattenToString()) ||
            enabled.contains("com.luna.prsauto")
    }

    private fun isBatteryUnrestricted(): Boolean {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    private fun requestNoBatteryRestriction() {
        try {
            val intent = Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
            ).apply { data = Uri.fromParts("package", packageName, null) }
            startActivity(intent)
        } catch (_: Exception) {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", packageName, null)
            }
            startActivity(intent)
        }
    }

    private fun onStartClicked() {
        val maxTasks = etMaxTasks.text.toString().toIntOrNull() ?: 20
        val delayMin = etDelayMin.text.toString().toIntOrNull() ?: 5
        val delayMax = etDelayMax.text.toString().toIntOrNull() ?: 15
        Prefs.save(this, maxTasks, delayMin, delayMax)

        if (!isAccessibilityEnabled()) {
            Toast.makeText(
                this,
                "Aktifkan dulu Aksesibilitas untuk PRS Auto.",
                Toast.LENGTH_LONG
            ).show()
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        }
        if (!isBatteryUnrestricted()) {
            Toast.makeText(
                this,
                "Disarankan: atur Baterai ke Tanpa batasan dulu (HyperOS).",
                Toast.LENGTH_LONG
            ).show()
        }
        if (!ScreenCapture.isReady()) {
            val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            startActivityForResult(mgr.createScreenCaptureIntent(), REQ_SCREEN_CAPTURE)
            return
        }
        startRun()
    }

    private fun startRun() {
        // Buka Chrome ke halaman tugas pr.social.
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://pr.social/tasks")).apply {
                setPackage("com.android.chrome")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (_: Exception) {
            try {
                startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse("https://pr.social/tasks")).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                )
            } catch (_: Exception) {
            }
        }
        sendBroadcast(Intent(AutomationService.CMD_ACTION).putExtra(AutomationService.EXTRA_CMD, AutomationService.CMD_START))
        statusText.text = "Status: berjalan"
        LogBus.i(this, "MULAI ditekan (max ${Prefs.maxTasks(this)} tugas).")
    }

    private fun onStopClicked() {
        sendBroadcast(Intent(AutomationService.CMD_ACTION).putExtra(AutomationService.EXTRA_CMD, AutomationService.CMD_STOP))
        statusText.text = "Status: berhenti"
        LogBus.i(this, "BERHENTI ditekan.")
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_SCREEN_CAPTURE) {
            if (resultCode == RESULT_OK && data != null) {
                ScreenCapture.resultCode = resultCode
                ScreenCapture.resultData = data
                LogBus.i(this, "Izin screenshot diberikan.")
                startRun()
            } else {
                Toast.makeText(
                    this,
                    "Izin screenshot ditolak — otomasi butuh izin ini.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    companion object {
        private const val REQ_SCREEN_CAPTURE = 1001
    }
}
