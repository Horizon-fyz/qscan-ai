package com.quickanswer.app

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.pdf.PdfDocument
import android.os.Bundle
import android.util.Base64
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.quickanswer.app.databinding.ActivityExportBinding
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File
import kotlin.math.max
import kotlin.math.min

/**
 * 导出载体 Activity：从悬浮窗点击「导出」时启动，弹出格式选择，随后把答案落盘。
 *
 * - Word / Markdown / 纯文本：直接由答案原文生成，无需渲染；
 * - HTML / PNG / PDF：复用离线 answer.html（内嵌 KaTeX）离屏渲染后再导出，
 *   其中 HTML 会内联 KaTeX 样式与字体，得到可独立打开的单文件网页；PDF 由长图分页光栅化生成。
 * 之所以用 Activity 而非 Service：PDF/长图需要 WebView 与可测量尺寸的宿主窗口。
 */
class ExportActivity : AppCompatActivity() {

    private lateinit var binding: ActivityExportBinding
    private var web: WebView? = null
    private var progress: AlertDialog? = null
    private var done = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityExportBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val markdown = intent.getStringExtra(EXTRA_MARKDOWN).orEmpty()
        val title = intent.getStringExtra(EXTRA_TITLE)?.takeIf { it.isNotBlank() }
            ?: getString(R.string.export_default_title)

        if (markdown.isBlank()) {
            Toast.makeText(this, getString(R.string.export_empty), Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        showFormatChooser(markdown, title)
    }

    private fun showFormatChooser(markdown: String, title: String) {
        val formats = ExportFormat.entries.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.export_choose_title)
            .setItems(formats.map { it.label }.toTypedArray()) { _, which ->
                exportAs(markdown, formats[which], title)
            }
            .setNegativeButton(R.string.export_cancel) { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }

    private fun exportAs(markdown: String, format: ExportFormat, title: String) {
        showProgress()
        if (format.needsWebView) {
            renderThenExport(markdown, format, title)
        } else {
            // 文本类格式无需渲染，直接落盘
            Thread {
                val file = writeTextLike(markdown, format, title)
                runOnUiThread { onExportFinished(file) }
            }.start()
        }
    }

    private fun writeTextLike(markdown: String, format: ExportFormat, title: String): File? = runCatching {
        val dir = DocExport.exportDir(this)
        val base = "$title-${DocExport.timestamp()}"
        when (format) {
            ExportFormat.MARKDOWN -> {
                val f = DocExport.uniqueFile(dir, base, format.ext)
                DocExport.saveText(f, markdown)
                f
            }
            ExportFormat.TEXT -> {
                val f = DocExport.uniqueFile(dir, base, format.ext)
                DocExport.saveText(f, DocExport.stripMarkdown(markdown))
                f
            }
            ExportFormat.WORD -> {
                val f = DocExport.uniqueFile(dir, base, format.ext)
                DocxWriter.write(f, DocExport.stripMarkdown(markdown))
                f
            }
            else -> null
        }
    }.getOrNull()

    // ------------------------------------------------------------- WebView 渲染

    private fun ensureWebView(): WebView {
        web?.let { return it }
        val wv = WebView(this)
        wv.settings.javaScriptEnabled = true
        wv.settings.allowFileAccess = true
        wv.settings.textZoom = 100
        wv.setBackgroundColor(Color.WHITE)
        // 离屏渲染：软件层保证 draw(canvas) 能拿到完整内容位图
        wv.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
        wv.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        binding.webHost.addView(wv)
        web = wv
        return wv
    }

    private fun renderThenExport(markdown: String, format: ExportFormat, title: String) {
        val wv = ensureWebView()
        wv.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                val arg = JSONObject.quote(markdown)
                wv.evaluateJavascript("window.renderAnswer($arg, ''); 1") {
                    // 留出一帧布局时间，确保 KaTeX 完成后尺寸稳定
                    wv.postDelayed({ dispatch(format, title) }, 350L)
                }
            }
        }
        wv.loadUrl(ANSWER_PAGE)
    }

    private fun dispatch(format: ExportFormat, title: String) {
        val wv = web ?: return
        when (format) {
            ExportFormat.HTML -> exportHtml(wv, title)
            ExportFormat.PNG, ExportFormat.PDF -> exportBitmap(format, title)
            else -> onExportFinished(null)
        }
    }

    /** 生成独立单文件 HTML：内联 KaTeX 样式（含字体 data URI）与答案正文。 */
    private fun exportHtml(wv: WebView, title: String) {
        wv.evaluateJavascript("document.getElementById('content').innerHTML") { result ->
            val inner = jsString(result)
            val file = runCatching {
                val dir = DocExport.exportDir(this)
                val f = DocExport.uniqueFile(dir, "$title-${DocExport.timestamp()}", ExportFormat.HTML.ext)
                DocExport.saveText(f, buildStandaloneHtml(title, inner))
                f
            }.getOrNull()
            onExportFinished(file)
        }
    }

    /** HTML / PNG / PDF 共用的像素渲染入口：先量高，再测量布局后取长图。 */
    private fun exportBitmap(format: ExportFormat, title: String) {
        val wv = web ?: return
        wv.evaluateJavascript("Math.ceil(document.documentElement.scrollHeight)") { result ->
            val cssHeight = result.trim('"').toFloatOrNull() ?: 0f
            val widthPx = exportWidthPx()
            val density = resources.displayMetrics.density
            val rawHeight = (cssHeight * density).toInt().coerceAtLeast(1)
            // 内存保护：限制总像素，超出则只导出可视上限内的内容
            val maxHeight = (MAX_PIXELS / widthPx).coerceAtLeast(1)
            val heightPx = min(rawHeight.toLong(), maxHeight).toInt()

            val bitmap = renderBitmap(wv, widthPx, heightPx)
            val file = runCatching {
                val dir = DocExport.exportDir(this)
                val f = DocExport.uniqueFile(dir, "$title-${DocExport.timestamp()}", format.ext)
                if (format == ExportFormat.PNG) {
                    f.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                } else {
                    writePdf(bitmap, f)
                }
                f
            }.getOrNull()
            bitmap.recycle()
            onExportFinished(file)
        }
    }

    private fun renderBitmap(wv: WebView, widthPx: Int, heightPx: Int): Bitmap {
        val widthSpec = View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY)
        val heightSpec = View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.EXACTLY)
        wv.measure(widthSpec, heightSpec)
        wv.layout(0, 0, widthPx, heightPx)

        val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        wv.draw(canvas)
        return bitmap
    }

    /** 长图按 A4 比例分页写入 PDF（光栅化，文字不可选中，但版式与长图一致）。 */
    private fun writePdf(bitmap: Bitmap, file: File) {
        val pageWidth = PDF_PAGE_WIDTH
        val scale = pageWidth.toFloat() / bitmap.width
        val pageHeight = (pageWidth * (PDF_A4_H / PDF_A4_W)).toInt()
        val contentHeight = (bitmap.height * scale).toInt()

        val doc = PdfDocument()
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        var top = 0
        var pageIndex = 1
        while (top < contentHeight) {
            val pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageIndex).create()
            val page = doc.startPage(pageInfo)
            val srcTop = (top / scale).toInt().coerceIn(0, bitmap.height)
            val srcBottom = ((top + pageHeight) / scale).toInt().coerceIn(0, bitmap.height)
            if (srcBottom > srcTop) {
                val src = Rect(0, srcTop, bitmap.width, srcBottom)
                val dstHeight = max(1, (src.height() * scale).toInt())
                val dst = Rect(0, 0, pageWidth, dstHeight)
                page.canvas.drawBitmap(bitmap, src, dst, paint)
            }
            doc.finishPage(page)
            top += pageHeight
            pageIndex++
        }
        file.outputStream().use { doc.writeTo(it) }
        doc.close()
    }

    private fun exportWidthPx(): Int =
        min(resources.displayMetrics.widthPixels, MAX_EXPORT_WIDTH)

    private fun buildStandaloneHtml(title: String, inner: String): String {
        val baseCss = BASE_CSS
        val katexCss = runCatching { inlineKatexCss(readAsset("katex/katex.min.css")) }.getOrDefault("")
        return """<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>${escape(title)}</title>
<style>$baseCss</style>
<style>$katexCss</style>
</head>
<body>
<div id="content">$inner</div>
</body>
</html>"""
    }

    /** 把 KaTeX 样式中的 url(fonts/xxx) 替换为内联 data URI，使导出的 HTML 可离线独立打开。 */
    private fun inlineKatexCss(css: String): String {
        var out = css
        val fonts = runCatching { assets.list("katex/fonts") }.getOrNull() ?: return out
        for (name in fonts) {
            val data = runCatching {
                Base64.encodeToString(assets.open("katex/fonts/$name").use { it.readBytes() }, Base64.NO_WRAP)
            }.getOrNull() ?: continue
            val uri = "data:font/woff2;base64,$data"
            out = out.replace("url(fonts/$name)", "url($uri)")
            out = out.replace("url(\"fonts/$name\")", "url($uri)")
            out = out.replace("url('fonts/$name')", "url($uri)")
        }
        return out
    }

    private fun escape(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun readAsset(path: String): String =
        assets.open(path).bufferedReader(Charsets.UTF_8).use { it.readText() }

    private fun jsString(json: String?): String {
        if (json == null) return ""
        return runCatching { JSONTokener(json).nextValue() as? String ?: "" }.getOrDefault("")
    }

    // ------------------------------------------------------------- 进度与结果

    private fun showProgress() {
        if (isFinishing || done) return
        progress = AlertDialog.Builder(this)
            .setMessage(R.string.export_running)
            .setCancelable(false)
            .create()
        progress?.show()
    }

    private fun dismissProgress() {
        progress?.dismiss()
        progress = null
    }

    private fun onExportFinished(file: File?) {
        dismissProgress()
        if (isFinishing) return
        if (file == null || !file.exists()) {
            Toast.makeText(this, getString(R.string.export_failed), Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        done = true
        AlertDialog.Builder(this)
            .setTitle(R.string.export_done_title)
            .setMessage(getString(R.string.export_done_msg, file.parent, file.name))
            .setPositiveButton(R.string.export_open) { _, _ -> FileUtils.openFile(this, file); finish() }
            .setNeutralButton(R.string.fm_share) { _, _ -> FileUtils.shareFile(this, file); finish() }
            .setNegativeButton(R.string.export_done_close) { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }

    override fun onDestroy() {
        web?.let {
            (it.parent as? ViewGroup)?.removeView(it)
            it.stopLoading()
            it.destroy()
        }
        web = null
        progress?.dismiss()
        progress = null
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_MARKDOWN = "markdown"
        private const val EXTRA_TITLE = "title"
        private const val ANSWER_PAGE = "file:///android_asset/answer.html"

        /** 导出位图的最大宽度（px），避免文档过宽 */
        private const val MAX_EXPORT_WIDTH = 1080

        /** 单张导出位图的总像素上限（约 8MP，≈32MB 内存） */
        private const val MAX_PIXELS = 8_000_000L

        /** PDF 页宽（pt）与 A4 长短边比 */
        private const val PDF_PAGE_WIDTH = 1240
        private const val PDF_A4_W = 595f
        private const val PDF_A4_H = 842f

        private const val BASE_CSS = """
html, body { margin: 0; padding: 0; }
body {
  font-family: -apple-system, "Noto Sans CJK SC", "PingFang SC", "Microsoft YaHei", sans-serif;
  font-size: 15px; line-height: 1.7; color: #1A1A1A;
  word-break: break-word; overflow-wrap: anywhere; padding: 16px 18px 24px;
  background: #FFFFFF;
}
#content > *:first-child { margin-top: 0; }
p { margin: 0 0 10px; }
h1, h2, h3, h4 { color: #37352F; margin: 16px 0 8px; line-height: 1.4; }
h1 { font-size: 19px; } h2 { font-size: 17px; } h3 { font-size: 16px; } h4 { font-size: 15px; }
ul, ol { margin: 0 0 10px; padding-left: 22px; }
li { margin: 2px 0; } li > p { margin: 0; }
strong { color: #37352F; font-weight: 600; }
a { color: #5645D4; text-decoration: none; }
hr { border: none; border-top: 1px solid #E5E3DF; margin: 14px 0; }
blockquote { margin: 10px 0; padding: 6px 12px; border-left: 3px solid #E5E3DF; color: #5D5B54; background: #FAFAF9; }
code { font-family: "SFMono-Regular", Consolas, monospace; font-size: 13px; background: #F6F5F4; border-radius: 4px; padding: 1px 4px; color: #37352F; }
pre { background: #F6F5F4; border: 1px solid #E5E3DF; border-radius: 8px; padding: 10px 12px; overflow-x: auto; margin: 0 0 10px; }
pre code { background: none; padding: 0; font-size: 13px; }
table { border-collapse: collapse; width: 100%; margin: 0 0 10px; font-size: 14px; }
th, td { border: 1px solid #E5E3DF; padding: 6px 8px; text-align: left; }
th { background: #F6F5F4; font-weight: 600; color: #37352F; }
.katex-display { margin: 10px 0; overflow-x: auto; overflow-y: hidden; padding: 2px 0; }
.katex { font-size: 1.06em; }
.katex-error { color: #E03131 !important; }
"""

        /** 从悬浮窗/其他组件启动导出。 */
        fun launch(context: Context, markdown: String, title: String?) {
            val intent = Intent(context, ExportActivity::class.java).apply {
                putExtra(EXTRA_MARKDOWN, markdown)
                title?.let { putExtra(EXTRA_TITLE, it) }
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }
}
