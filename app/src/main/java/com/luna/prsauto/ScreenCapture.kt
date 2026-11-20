package com.luna.prsauto

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Screenshot BENAR-BENAR (bukan gambar ulang halaman) via MediaProjection.
 *
 * - Izin diminta sekali per sesi oleh MainActivity (dialog sistem "Start now").
 * - Token izin (resultCode + Intent) disimpan di sini dan dipakai ulang
 *   selama proses aplikasi masih hidup.
 * - Hasil disimpan sebagai JPEG ke MediaStore Pictures/PRS Auto, supaya
 *   screenshot terbaru muncul paling atas di file picker Google Foto.
 * - Resolusi = resolusi layar perangkat (di Xiaomi Pad 6: 2880x1800).
 */
object ScreenCapture {

    @Volatile
    var resultCode: Int = Activity.RESULT_CANCELED

    @Volatile
    var resultData: Intent? = null

    @Volatile
    private var projection: MediaProjection? = null

    fun isReady(): Boolean = resultData != null

    /**
     * Buat (atau pakai ulang) MediaProjection. Harus dipanggil dari thread
     * utama karena getMediaProjection memakai Handler main thread.
     */
    private fun ensureProjection(context: Context): Boolean {
        if (projection != null) return true
        val data = resultData ?: return false
        if (Looper.myLooper() == Looper.getMainLooper()) {
            val mgr = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE)
                as MediaProjectionManager
            projection = mgr.getMediaProjection(resultCode, data)
            return projection != null
        }
        val latch = CountDownLatch(1)
        var ok = false
        Handler(Looper.getMainLooper()).post {
            try {
                val mgr = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE)
                    as MediaProjectionManager
                projection = mgr.getMediaProjection(resultCode, data)
                ok = projection != null
            } catch (_: Exception) {
                ok = false
            } finally {
                latch.countDown()
            }
        }
        latch.await(5, TimeUnit.SECONDS)
        return ok
    }

    /**
     * Tangkap satu frame layar penuh. Dipanggil dari worker thread.
     * Mengembalikan Uri MediaStore dari file JPEG yang disimpan, atau null
     * kalau gagal (mis. tidak ada frame dalam 2,5 detik).
     */
    fun capture(context: Context): Uri? {
        if (!ensureProjection(context)) return null
        val proj = projection ?: return null

        val dm = context.resources.displayMetrics
        val width = dm.widthPixels
        val height = dm.heightPixels
        val density = dm.densityDpi

        var reader: ImageReader? = null
        var display: VirtualDisplay? = null
        try {
            reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            val latch = CountDownLatch(1)
            val frameRef = AtomicReference<Image?>(null)
            reader.setOnImageAvailableListener({ r ->
                try {
                    val img = r.acquireLatestImage()
                    if (img != null && frameRef.get() == null) {
                        frameRef.set(img)
                        latch.countDown()
                    } else {
                        img?.close()
                    }
                } catch (_: Exception) {
                }
            }, Handler(Looper.getMainLooper()))
            display = proj.createVirtualDisplay(
                "prs-auto",
                width, height, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface, null, null
            )
            latch.await(2500, TimeUnit.MILLISECONDS)
            var image = frameRef.get()
            if (image == null) {
                try {
                    image = reader.acquireLatestImage()
                } catch (_: Exception) {
                }
            }
            if (image == null) return null
            try {
                val bitmap = imageToBitmap(image)
                return saveBitmap(context, bitmap)
            } finally {
                image.close()
            }
        } catch (_: Exception) {
            return null
        } finally {
            try {
                display?.release()
            } catch (_: Exception) {
            }
            try {
                reader?.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun imageToBitmap(image: Image): Bitmap {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * image.width
        val fullWidth = image.width + rowPadding / pixelStride
        val bmp = Bitmap.createBitmap(fullWidth, image.height, Bitmap.Config.ARGB_8888)
        bmp.copyPixelsFromBuffer(buffer)
        return if (rowPadding == 0) {
            bmp
        } else {
            Bitmap.createBitmap(bmp, 0, 0, image.width, image.height)
        }
    }

    private fun saveBitmap(context: Context, bitmap: Bitmap): Uri? {
        val name = "prs_${System.currentTimeMillis()}.jpg"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/PRS Auto")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
        ) ?: return null
        try {
            resolver.openOutputStream(uri)?.use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
            }
            val done = ContentValues().apply {
                put(MediaStore.Images.Media.IS_PENDING, 0)
            }
            resolver.update(uri, done, null, null)
            return uri
        } catch (_: Exception) {
            try {
                resolver.delete(uri, null, null)
            } catch (_: Exception) {
            }
            return null
        }
    }

    /** Lepaskan MediaProjection (dipanggil saat sesi berhenti). */
    fun release() {
        try {
            projection?.stop()
        } catch (_: Exception) {
        }
        projection = null
    }
}
