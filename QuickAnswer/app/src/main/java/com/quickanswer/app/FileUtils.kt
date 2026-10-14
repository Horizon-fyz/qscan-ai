package com.quickanswer.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 文件通用操作：MIME 推断、格式化、打开、分享、复制/移动/删除。 */
object FileUtils {

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

    fun mimeType(file: File): String {
        if (file.isDirectory) return "resource/folder"
        val ext = file.extension.lowercase(Locale.ROOT)
        if (ext.isEmpty()) return "*/*"
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
    }

    fun formatSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format(Locale.US, "%.1f MB", mb)
        return String.format(Locale.US, "%.2f GB", mb / 1024.0)
    }

    fun formatDate(time: Long): String = dateFormat.format(Date(time))

    fun dirSize(dir: File): Long {
        var total = 0L
        val children = dir.listFiles() ?: return 0L
        for (child in children) {
            total += if (child.isDirectory) dirSize(child) else child.length()
        }
        return total
    }

    fun uriFor(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

    /** 用系统默认应用打开文件（FileProvider + 临时读权限）。返回是否成功拉起。 */
    fun openFile(context: Context, file: File): Boolean {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uriFor(context, file), mimeType(file))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching {
            context.startActivity(intent)
            true
        }.getOrDefault(false)
    }

    /** 分享文件。 */
    fun shareFile(context: Context, file: File) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType(file)
            putExtra(Intent.EXTRA_STREAM, uriFor(context, file))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(intent, context.getString(R.string.fm_share)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(chooser) }
    }

    /** 复制（recursive 覆盖目录）。返回是否成功。 */
    fun copy(src: File, dst: File): Boolean = runCatching {
        if (src.isDirectory) {
            if (!dst.exists()) dst.mkdirs()
            src.listFiles()?.forEach { child -> copy(child, File(dst, child.name)) }
        } else {
            dst.parentFile?.let { if (!it.exists()) it.mkdirs() }
            src.inputStream().use { input -> dst.outputStream().use { output -> input.copyTo(output) } }
        }
        true
    }.getOrDefault(false)

    /** 移动（先尝试 rename，失败则复制后删除）。 */
    fun move(src: File, dst: File): Boolean {
        if (runCatching { src.renameTo(dst) }.getOrDefault(false)) return true
        if (!copy(src, dst)) return false
        return delete(src)
    }

    fun delete(file: File): Boolean {
        if (file.isDirectory) file.listFiles()?.forEach { delete(it) }
        return runCatching { file.delete() }.getOrDefault(false)
    }

    /** 在目标目录内生成不重名的副本路径。 */
    fun uniqueChild(dir: File, name: String): File {
        val base: String
        val ext: String
        val dot = name.lastIndexOf('.')
        if (dot > 0) {
            base = name.substring(0, dot)
            ext = name.substring(dot)
        } else {
            base = name
            ext = ""
        }
        var target = File(dir, name)
        var i = 1
        while (target.exists()) {
            target = File(dir, "$base($i)$ext")
            i++
        }
        return target
    }
}
