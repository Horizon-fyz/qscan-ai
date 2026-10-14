package com.quickanswer.app

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.SubscriptSpan
import android.text.style.SuperscriptSpan

/**
 * 轻量 LaTeX 渲染：把 DeepSeek 返回的公式文本转成可读富文本，零第三方依赖。
 *
 * 覆盖：数学定界符（$ \( \) \[ \]）剔除、常用命令 → Unicode 符号、
 * \frac / \sqrt 展开、上下标 ^{} _{} （用 SuperscriptSpan / SubscriptSpan 呈现）。
 * 无法识别的命令会退化为去掉反斜杠的纯文本，保证不出现乱码。
 */
object MathText {

    private val SYMBOLS: Map<String, String> = mapOf(
        "times" to "×", "div" to "÷", "cdot" to "·", "ast" to "∗",
        "pm" to "±", "mp" to "∓",
        "leq" to "≤", "le" to "≤", "geq" to "≥", "ge" to "≥",
        "neq" to "≠", "ne" to "≠", "equiv" to "≡", "approx" to "≈",
        "sim" to "∼", "simeq" to "≃", "cong" to "≅", "propto" to "∝",
        "infty" to "∞", "partial" to "∂", "nabla" to "∇",
        "rightarrow" to "→", "to" to "→", "leftarrow" to "←", "gets" to "←",
        "Rightarrow" to "⇒", "Leftarrow" to "⇐", "leftrightarrow" to "↔",
        "Leftrightarrow" to "⇔", "uparrow" to "↑", "downarrow" to "↓",
        "in" to "∈", "notin" to "∉", "ni" to "∋",
        "subset" to "⊂", "subseteq" to "⊆", "supset" to "⊃", "supseteq" to "⊇",
        "cup" to "∪", "cap" to "∩", "emptyset" to "∅", "varnothing" to "∅",
        "forall" to "∀", "exists" to "∃", "neg" to "¬", "land" to "∧", "lor" to "∨",
        "sum" to "∑", "prod" to "∏", "int" to "∫", "oint" to "∮",
        "perpendicular" to "⊥", "perp" to "⊥", "parallel" to "∥", "angle" to "∠",
        "triangle" to "△", "square" to "□", "circ" to "°", "degree" to "°",
        "because" to "∵", "therefore" to "∴", "star" to "⋆", "bullet" to "•",
        "alpha" to "α", "beta" to "β", "gamma" to "γ", "delta" to "δ",
        "epsilon" to "ε", "varepsilon" to "ε", "zeta" to "ζ", "eta" to "η",
        "theta" to "θ", "vartheta" to "ϑ", "iota" to "ι", "kappa" to "κ",
        "lambda" to "λ", "mu" to "μ", "nu" to "ν", "xi" to "ξ",
        "pi" to "π", "rho" to "ρ", "sigma" to "σ", "tau" to "τ",
        "upsilon" to "υ", "phi" to "φ", "varphi" to "φ", "chi" to "χ",
        "psi" to "ψ", "omega" to "ω",
        "Gamma" to "Γ", "Delta" to "Δ", "Theta" to "Θ", "Lambda" to "Λ",
        "Xi" to "Ξ", "Pi" to "Π", "Sigma" to "Σ", "Phi" to "Φ",
        "Psi" to "Ψ", "Omega" to "Ω",
        "ldots" to "…", "dots" to "…", "cdots" to "⋯", "vdots" to "⋮", "ddots" to "⋱",
        "quad" to " ", "qquad" to "  ", "space" to " ",
        "log" to "log", "ln" to "ln", "lg" to "lg", "exp" to "exp",
        "sin" to "sin", "cos" to "cos", "tan" to "tan", "cot" to "cot",
        "sec" to "sec", "csc" to "csc",
        "arcsin" to "arcsin", "arccos" to "arccos", "arctan" to "arctan",
        "lim" to "lim", "min" to "min", "max" to "max",
        "left" to "", "right" to "",
        "%" to "%", "&" to "&", "#" to "#", "$" to "$", "_" to "_",
        "{" to "{", "}" to "}"
    )

    fun render(raw: String): CharSequence {
        val out = SpannableStringBuilder()
        var i = 0
        while (i < raw.length) {
            when (val c = raw[i]) {
                '\\' -> i = command(raw, i, out)
                '^' -> i = script(raw, i + 1, out, superscript = true)
                '_' -> i = script(raw, i + 1, out, superscript = false)
                '$' -> i++          // 丢弃数学定界符
                '\r' -> i++         // 归一化换行
                '*' -> i = emphasis(raw, i, out)
                else -> { out.append(c); i++ }
            }
        }
        return out
    }

    /** 处理 Markdown 加粗 **...**；不成对出现的 * 原样保留 */
    private fun emphasis(s: String, start: Int, out: SpannableStringBuilder): Int {
        if (start + 1 < s.length && s[start + 1] == '*') {
            val end = s.indexOf("**", start + 2)
            if (end >= 0) {
                val from = out.length
                out.append(render(s.substring(start + 2, end)))
                val to = out.length
                if (to > from) {
                    out.setSpan(StyleSpan(Typeface.BOLD), from, to, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                return end + 2
            }
        }
        out.append('*')
        return start + 1
    }

    /** 处理 \\ 开头的命令，返回处理后的新下标 */
    private fun command(s: String, start: Int, out: SpannableStringBuilder): Int {
        var i = start + 1
        if (i >= s.length) { out.append('\\'); return i }
        val c = s[i]
        if (!c.isLetter()) {
            return when (c) {
                '\\' -> { out.append('\n'); i + 1 }         // \\ 换行
                '(', ')', '[', ']' -> i + 1                  // 数学定界符，剔除
                ',', ';', ':', '!' -> i + 1                  // 细间距，剔除
                ' ' -> { out.append(' '); i + 1 }
                else -> { out.append(c); i + 1 }             // \{ \} \% \& \# \$ \_ 等转义
            }
        }
        val nameStart = i
        while (i < s.length && s[i].isLetter()) i++
        val name = s.substring(nameStart, i)
        when (name) {
            "frac", "dfrac", "tfrac", "cfrac" -> {
                var j = i
                if (j < s.length && s[j] == '{') {
                    val (num, nj) = group(s, j); j = nj
                    if (j < s.length && s[j] == '{') {
                        val (den, dj) = group(s, j)
                        out.append('(').append(render(num)).append(")/(").append(render(den)).append(')')
                        return dj
                    }
                }
                out.append(name)
                return i
            }
            "sqrt" -> {
                var j = i
                if (j < s.length && s[j] == '[') {           // 可选根指数
                    val end = s.indexOf(']', j)
                    if (end > j) {
                        out.append(render(s.substring(j + 1, end))).append('√')
                        j = end + 1
                    }
                }
                if (j < s.length && s[j] == '{') {
                    val (inner, nj) = group(s, j)
                    out.append('√').append('(').append(render(inner)).append(')')
                    return nj
                }
                out.append('√')
                return j
            }
            "text", "mathrm", "mathbf", "mathit", "mathsf", "mathtt",
            "operatorname", "mbox", "textbf", "textit" -> {
                if (i < s.length && s[i] == '{') {
                    val (inner, ni) = group(s, i)
                    out.append(render(inner))
                    return ni
                }
                return i
            }
            else -> {
                out.append(SYMBOLS[name] ?: name)
                return i
            }
        }
    }

    /** 处理上下标：^{...} / ^x / _{} / _x */
    private fun script(s: String, idx: Int, out: SpannableStringBuilder, superscript: Boolean): Int {
        if (idx >= s.length) return idx
        val (content, next) = scriptArg(s, idx)
        if (content.isNotEmpty()) {
            val from = out.length
            out.append(content)
            val to = out.length
            out.setSpan(
                if (superscript) SuperscriptSpan() else SubscriptSpan(),
                from, to, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            out.setSpan(RelativeSizeSpan(0.7f), from, to, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return next
    }

    private fun scriptArg(s: String, idx: Int): Pair<CharSequence, Int> = when {
        s[idx] == '{' -> {
            val (g, ni) = group(s, idx)
            render(g) to ni
        }
        s[idx] == '\\' -> {
            var j = idx + 1
            if (j < s.length && s[j].isLetter()) {
                val st = j
                while (j < s.length && s[j].isLetter()) j++
                (SYMBOLS[s.substring(st, j)] ?: s.substring(st, j)) to j
            } else if (j < s.length) {
                s[j].toString() to (j + 1)
            } else {
                "" to j
            }
        }
        else -> s[idx].toString() to (idx + 1)
    }

    /** 读取一对花括号内的内容（支持嵌套），返回内容与结束后的新下标 */
    private fun group(s: String, openIndex: Int): Pair<String, Int> {
        var i = openIndex + 1
        var depth = 1
        val sb = StringBuilder()
        while (i < s.length && depth > 0) {
            when (val ch = s[i]) {
                '{' -> { depth++; sb.append(ch) }
                '}' -> { depth--; if (depth > 0) sb.append(ch) }
                else -> sb.append(ch)
            }
            i++
        }
        return sb.toString() to i
    }
}
