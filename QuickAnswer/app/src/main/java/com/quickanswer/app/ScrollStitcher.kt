package com.quickanswer.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import kotlin.math.abs

/**
 * 纵向滚动拼接器（解题模式「翻动拼接识别」用）。
 *
 * 逐帧比较两幅整屏截图的「行灰度描述子」：在上一帧与当前帧之间搜索纵向滚动位移 d
 * （即「上一帧第 y 行 ≈ 当前帧第 y+d 行」），把当前帧底部新增的 d 行作为切片输出。
 * 于是向上翻动长页面时，可自动去掉帧间重叠、逐段累积，避免把同一段内容重复交给视觉模型。
 *
 * 约定：
 *  - 仅供后台线程使用；
 *  - 返回的切片是新建位图，由调用方负责 recycle；
 *  - 纯色/空白行不参与匹配（其位移没有判别力），可有效抑制白底造成的伪匹配。
 */
class ScrollStitcher(
    /** 匹配时缩放到的工作宽度（越小越快、精度越低） */
    private val workWidth: Int = 360,
    /** 每行采样的列数 */
    private val sampleCols: Int = 20,
    /** 位移小于该值（工作坐标系像素）视为未滚动 */
    private val minScrollPx: Int = 10,
    /** 单次最大位移比例，超过则认为两帧无有效重叠 */
    private val maxScrollRatio: Float = 0.85f,
    /** 匹配接受阈值：参与匹配的采样点平均灰度差上限 */
    private val matchThreshold: Int = 22
) {

    private var prevDesc: IntArray? = null
    private var prevH = 0

    private val filterPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val maskPaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }

    /**
     * 送入一帧整屏截图。
     *
     * @param frame   当前帧（原分辨率）
     * @param maskRect 需要遮挡的区域（屏幕坐标，如悬浮球所在位置）；可为 null
     * @return 本帧新增的底部切片；无新增内容返回 null
     */
    fun feed(frame: Bitmap, maskRect: Rect?): Bitmap? {
        if (frame.width <= 0 || frame.height <= 0) return null

        // 1) 等比缩放到工作宽度，并在缩放图上完成遮挡与描述子提取
        val workH = (frame.height.toLong() * workWidth / frame.width).toInt().coerceAtLeast(1)
        val work = Bitmap.createBitmap(workWidth, workH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(work)
        canvas.drawBitmap(
            frame, null,
            RectF(0f, 0f, workWidth.toFloat(), workH.toFloat()),
            filterPaint
        )
        if (maskRect != null) {
            val sx = workWidth.toFloat() / frame.width
            val sy = workH.toFloat() / frame.height
            canvas.drawRect(
                maskRect.left * sx, maskRect.top * sy,
                maskRect.right * sx, maskRect.bottom * sy, maskPaint
            )
        }
        val desc = describe(work, workH)
        work.recycle()

        val pd = prevDesc
        val isFirst = pd == null || prevH != workH
        prevDesc = desc
        prevH = workH

        // 2) 首帧直接整帧作为第一段
        if (isFirst) return crop(frame, 0, frame.height, maskRect)

        // 3) 后续帧求滚动位移，只取新增的底部 d 行
        val d = findScroll(pd!!, desc, workH)
        if (d <= 0) return null
        val dOrig = (d.toLong() * frame.height / workH).toInt().coerceIn(1, frame.height - 1)
        return crop(frame, frame.height - dOrig, dOrig, maskRect)
    }

    /** 逐行采样灰度，生成行描述子（长度 = h * sampleCols）。 */
    private fun describe(bmp: Bitmap, h: Int): IntArray {
        val w = bmp.width
        val out = IntArray(h * sampleCols)
        val row = IntArray(w)
        for (y in 0 until h) {
            bmp.getPixels(row, 0, w, 0, y, w, 1)
            val base = y * sampleCols
            for (i in 0 until sampleCols) {
                val x = (i * (w - 1) / (sampleCols - 1)).coerceIn(0, w - 1)
                val c = row[x]
                out[base + i] =
                    (((c shr 16 and 0xFF) + (c shr 8 and 0xFF) + (c and 0xFF)) / 3)
            }
        }
        return out
    }

    /** 搜索纵向位移：返回工作坐标下的最佳 d（未找到有效重叠返回 0）。 */
    private fun findScroll(prev: IntArray, cur: IntArray, h: Int): Int {
        val maxD = (h * maxScrollRatio).toInt()
        if (maxD <= minScrollPx) return 0

        var bestD = 0
        var bestCost = Int.MAX_VALUE
        var d = minScrollPx
        while (d <= maxD) {
            var cost = 0L
            var n = 0
            var y = 0
            while (y + d < h) {
                if (rowTexture(prev, y) >= TEXTURE_MIN) {
                    cost += rowDiff(prev, y, cur, y + d)
                    n++
                }
                y += ROW_STEP
            }
            if (n >= MIN_ROWS) {
                val avg = (cost / n).toInt()
                if (avg < bestCost) {
                    bestCost = avg
                    bestD = d
                }
            }
            d++
        }
        return if (bestCost <= matchThreshold) bestD else 0
    }

    private fun rowDiff(a: IntArray, ya: Int, b: IntArray, yb: Int): Int {
        val ia = ya * sampleCols
        val ib = yb * sampleCols
        var s = 0
        for (i in 0 until sampleCols) s += abs(a[ia + i] - b[ib + i])
        return s
    }

    private fun rowTexture(a: IntArray, y: Int): Int {
        val i = y * sampleCols
        var mn = 255
        var mx = 0
        for (k in 0 until sampleCols) {
            val v = a[i + k]
            if (v < mn) mn = v
            if (v > mx) mx = v
        }
        return mx - mn
    }

    /** 从原帧裁出 [top, top+height) 行；若传入遮罩与裁剪区相交，则在结果中涂白遮挡。 */
    private fun crop(src: Bitmap, top: Int, height: Int, mask: Rect?): Bitmap {
        val out = Bitmap.createBitmap(src.width, height, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawBitmap(src, 0f, -top.toFloat(), filterPaint)
        if (mask != null && mask.bottom > top && mask.top < top + height) {
            c.drawRect(
                mask.left.toFloat(),
                (mask.top - top).toFloat(),
                mask.right.toFloat(),
                (mask.bottom - top).toFloat(),
                maskPaint
            )
        }
        return out
    }

    companion object {
        /** 行参与匹配所需的最小灰度极差（低于此值视为纯色行，跳过）。 */
        private const val TEXTURE_MIN = 18

        /** 匹配时纵向跨步（越大越快）。 */
        private const val ROW_STEP = 3

        /** 一次位移搜索至少需要参与匹配的行数。 */
        private const val MIN_ROWS = 12
    }
}
