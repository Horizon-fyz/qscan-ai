package com.quickanswer.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * 基于 MediaProjection 的整屏截取。
 * 复用同一个 VirtualDisplay，按需抓取最新一帧，避免每次截屏都弹权限框。
 */
class ScreenCapture(
    context: Context,
    private val projection: MediaProjection,
    private val width: Int,
    private val height: Int,
    private val dpi: Int
) {
    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null

    fun start() {
        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        imageReader = reader
        virtualDisplay = projection.createVirtualDisplay(
            "QuickAnswerCapture",
            width,
            height,
            dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            Handler(Looper.getMainLooper())
        )
    }

    /** 抓取当前屏幕的一帧，返回整屏 Bitmap；失败返回 null。 */
    fun capture(): Bitmap? {
        val reader = imageReader ?: return null
        val image = reader.acquireLatestImage() ?: return null
        try {
            val plane = image.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * width
            val rawWidth = width + rowPadding / pixelStride
            val raw = Bitmap.createBitmap(rawWidth, height, Bitmap.Config.ARGB_8888)
            raw.copyPixelsFromBuffer(buffer)
            return if (rawWidth != width) {
                Bitmap.createBitmap(raw, 0, 0, width, height).also { raw.recycle() }
            } else {
                raw
            }
        } catch (e: Exception) {
            Log.e(TAG, "capture failed", e)
            return null
        } finally {
            image.close()
        }
    }

    fun release() {
        runCatching { virtualDisplay?.release() }
        runCatching { imageReader?.close() }
        virtualDisplay = null
        imageReader = null
    }

    companion object {
        private const val TAG = "ScreenCapture"
    }
}
