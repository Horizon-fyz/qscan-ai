package com.quickanswer.app

import android.content.Context
import android.os.Environment
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 导出格式。 */
enum class ExportFormat(val label: String, val ext: String) {
    WORD("Word 文档", "docx"),
    PDF("PDF 文档", "pdf"),
    MARKDOWN("Markdown", "md"),
    TEXT("纯文本", "txt"),
    HTML("网页 HTML", "html"),
    PNG("长图 PNG", "png");

    /** 是否需要经 WebView 渲染后再落盘（PDF/HTML/PNG）。 */
    val needsWebView: Boolean
        get() = this == PDF || this == HTML || this == PNG
}

/**
 * 导出落盘与文本转换工具：统一导出目录、去重命名、markdown 转纯文本等。
 * 全部使用系统 API，无第三方依赖。
 */
object DocExport {

    /** 导出根目录：优先公共「下载/AI智能助手」，不可写时回退到应用外部私有目录。 */
    fun exportDir(context: Context): File {
        val fallback = File(context.getExternalFilesDir(null), "AI智能助手")
        val mounted = Environment.getExternalStorageState() == Environment.MEDIA_MOUNTED
        if (mounted) {
            val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val target = File(downloads, "AI智能助手")
            if (ensureDir(target) && target.canWrite()) return target
        }
        ensureDir(fallback)
        return fallback
    }

    private fun ensureDir(dir: File): Boolean = dir.exists() || runCatching { dir.mkdirs() }.getOrDefault(false)

    /** 时间戳（用于默认文件名）。 */
    fun timestamp(): String =
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())

    /** 生成不与既有文件重名的文件；base 不含扩展名。 */
    fun uniqueFile(dir: File, base: String, ext: String): File {
        val safe = sanitizeName(base)
        var file = File(dir, "$safe.$ext")
        var i = 1
        while (file.exists()) {
            file = File(dir, "${safe}_$i.$ext")
            i++
        }
        return file
    }

    /** 过滤文件系统非法字符，并限制长度。 */
    fun sanitizeName(name: String): String {
        val trimmed = name.trim().ifBlank { "导出" }
        return trimmed.replace(Regex("[\\\\/:*?\"<>|\\r\\n\\t]"), "_").take(60).trim().ifBlank { "导出" }
    }

    fun saveText(file: File, text: String) {
        file.writeText(text, Charsets.UTF_8)
    }

    /**
     * 去掉常见 markdown 标记，得到适合放进 Word / 纯文本的正文。
     * 公式保留 `$...$` 原文（便于后续核对），只剥离结构性符号。
     */
    fun stripMarkdown(md: String): String {
        var s = md.replace("\r\n", "\n")
        // 代码块围栏：保留内容，去掉 ``` 行
        s = s.replace(Regex("(?m)^\\s*```[^\\n]*$"), "")
        // 标题 # ## ###
        s = s.replace(Regex("(?m)^\\s{0,3}#{1,6}\\s*"), "")
        // 引用 >
        s = s.replace(Regex("(?m)^\\s*>\\s?"), "")
        // 无序列表 - * +
        s = s.replace(Regex("(?m)^\\s*[-*+]\\s+"), "• ")
        // 有序列表保留原编号（1. 2. ...）
        // 图片 ![alt](url) → alt
        s = s.replace(Regex("!\\[([^\\]]*)\\]\\([^)]*\\)"), "$1")
        // 链接 [text](url) → text (url)
        s = s.replace(Regex("\\[([^\\]]*)\\]\\(([^)]*)\\)"), "$1 ($2)")
        // 加粗/斜体/删除线
        s = s.replace(Regex("\\*\\*\\*([^*]+)\\*\\*\\*"), "$1")
        s = s.replace(Regex("\\*\\*([^*]+)\\*\\*"), "$1")
        s = s.replace(Regex("__([^_]+)__"), "$1")
        s = s.replace(Regex("\\*([^*\\n]+)\\*"), "$1")
        s = s.replace(Regex("_([^_\\n]+)_"), "$1")
        s = s.replace(Regex("~~([^~]+)~~"), "$1")
        // 行内代码
        s = s.replace(Regex("`([^`]+)`"), "$1")
        // 水平线
        s = s.replace(Regex("(?m)^\\s*([-*_])\\s*(\\1\\s*){2,}$"), "————————")
        return s.trim()
    }
}
