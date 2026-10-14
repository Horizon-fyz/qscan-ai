package com.quickanswer.app

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 无第三方依赖的最小 OOXML（.docx）写入器。
 *
 * .docx 本质是一个 ZIP 包，Word / WPS / Google Docs 只要求其中三个部件即可打开：
 *   [Content_Types].xml、_rels/.rels、word/document.xml
 * 这里把纯文本按行拆成段落，识别 `#` 标题做加粗与字号（直接写在 run 属性上，免去 styles.xml）。
 * 公式等复杂排版不做保真，导出的是「可编辑的纯文本 + 层级标题」版本。
 */
object DocxWriter {

    /** 半磅值（w:sz），1 磅 = 2 半磅。 */
    private const val SZ_BODY = 24    // 12pt
    private const val SZ_H1 = 36      // 18pt
    private const val SZ_H2 = 32      // 16pt
    private const val SZ_H3 = 28      // 14pt

    fun write(file: File, plainText: String) {
        val documentXml = buildDocument(plainText)
        ZipOutputStream(file.outputStream().buffered()).use { zip ->
            zip.putNextEntry(ZipEntry("[Content_Types].xml"))
            zip.write(CONTENT_TYPES.toByteArray(Charsets.UTF_8))
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("_rels/.rels"))
            zip.write(RELS.toByteArray(Charsets.UTF_8))
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("word/document.xml"))
            zip.write(documentXml.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
    }

    private fun buildDocument(text: String): String {
        val body = StringBuilder()
        val lines = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        for (raw in lines) {
            val line = raw.trimEnd()
            val trimmed = line.trimStart()
            val heading = when {
                trimmed.startsWith("### ") -> 3
                trimmed.startsWith("## ") -> 2
                trimmed.startsWith("# ") -> 1
                else -> 0
            }
            val content = if (heading > 0) trimmed.drop(heading + 1) else line
            body.append(paragraph(content, heading))
        }
        if (body.isEmpty()) body.append(paragraph("", 0))
        return """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
<w:body>$body<w:sectPr><w:pgSz w:w="11906" w:h="16838"/><w:pgMar w:top="1134" w:right="1134" w:bottom="1134" w:left="1134"/></w:sectPr></w:body>
</w:document>"""
    }

    private fun paragraph(text: String, heading: Int): String {
        val size = when (heading) {
            1 -> SZ_H1
            2 -> SZ_H2
            3 -> SZ_H3
            else -> SZ_BODY
        }
        val bold = if (heading > 0) "<w:b/>" else ""
        val rPr = "<w:rPr>$bold<w:sz w:val=\"$size\"/><w:szCs w:val=\"$size\"/></w:rPr>"
        val run = if (text.isEmpty()) {
            "<w:r>$rPr</w:r>"
        } else {
            "<w:r>$rPr<w:t xml:space=\"preserve\">${escapeXml(text)}</w:t></w:r>"
        }
        val spacing = if (heading > 0) "<w:spacing w:before=\"160\" w:after=\"80\"/>" else ""
        return "<w:p><w:pPr>$spacing</w:pPr>$run</w:p>"
    }

    fun escapeXml(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s) {
            when (c) {
                '&' -> sb.append("&amp;")
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                '"' -> sb.append("&quot;")
                '\'' -> sb.append("&apos;")
                // 去掉 XML 1.0 不允许的控制字符，避免 docx 打不开
                else -> if (c.code < 0x20 && c != '\t' && c != '\n') Unit else sb.append(c)
            }
        }
        return sb.toString()
    }

    private const val CONTENT_TYPES = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/></Types>"""

    private const val RELS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>"""
}
