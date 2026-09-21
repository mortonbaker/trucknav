package com.morton.trucknav.settings

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AtomicFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

object VehicleImage {
    const val MAX_BYTES = 2 * 1024 * 1024
    private lateinit var file: AtomicFile
    private val state = MutableStateFlow<Bitmap?>(null)
    val bitmap = state.asStateFlow()
    @Synchronized fun init(context: Context) {
        if (::file.isInitialized) return
        file = AtomicFile(File(context.filesDir, "vehicle.png"))
        if (file.baseFile.exists()) state.value = file.openRead().use { BitmapFactory.decodeStream(it) }
    }
    @Synchronized fun put(bytes: ByteArray) {
        require(bytes.isNotEmpty() && bytes.size <= MAX_BYTES) { "PNG/JPEG must be at most 2 MB" }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outMimeType in setOf("image/png", "image/jpeg") && bounds.outWidth > 0 && bounds.outHeight > 0) { "PNG or JPEG required" }
        require(bounds.outWidth <= 16384 && bounds.outHeight <= 16384) { "Image dimensions exceed 16384 px" }
        val opts = BitmapFactory.Options().apply { inSampleSize = 1 }
        while (maxOf(bounds.outWidth, bounds.outHeight) / opts.inSampleSize > 1024) opts.inSampleSize *= 2
        val original = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts))
        val result = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        val scale = minOf(256f / original.width, 256f / original.height)
        val w = original.width * scale; val h = original.height * scale
        Canvas(result).drawBitmap(original, null, RectF((256-w)/2, (256-h)/2, (256+w)/2, (256+h)/2), Paint(Paint.FILTER_BITMAP_FLAG))
        original.recycle()
        val stream = file.startWrite()
        try {
            check(result.compress(Bitmap.CompressFormat.PNG, 100, stream))
            file.finishWrite(stream)
        } catch (e: Exception) { file.failWrite(stream); result.recycle(); throw e }
        state.value = result
        com.morton.trucknav.nav.NavLog.log("vehicle", "updated 256x256")
    }
    @Synchronized fun bytes(): ByteArray? = if (file.baseFile.exists()) file.openRead().use { it.readBytes() } else null
    @Synchronized fun delete() {
        file.delete()
        state.value = null
        com.morton.trucknav.nav.NavLog.log("vehicle", "restored default")
    }
}
