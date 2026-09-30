package com.karewinkcloud.agentweb.client.core

import org.commonmark.node.*
import org.commonmark.parser.*
import org.commonmark.parser.block.*
import org.commonmark.parser.delimiter.*

/** Small local extensions: no network dependencies, HTML execution or LaTeX engine. */
class TableBlock(val header: List<String>, val alignments: List<String>) : CustomBlock() {
    val rows = mutableListOf<List<String>>()
    fun tsv(): String {
        val parser = richMarkdownParser()
        fun cell(value: String): String = buildString {
            fun walk(node: Node) {
                when (node) {
                    is Text -> append(node.literal)
                    is Code -> append(node.literal)
                    is HtmlInline -> append(node.literal)
                    is SoftLineBreak, is HardLineBreak -> append(' ')
                    else -> { var child = node.firstChild; while (child != null) { walk(child); child = child.next } }
                }
            }
            // Prefix keeps punctuation at the cell start from becoming a block/list.
            walk(parser.parse("x$value"))
        }.removePrefix("x").replace('\t', ' ').replace('\n', ' ')
        return (listOf(header) + rows).joinToString("\n") { row ->
            header.indices.joinToString("\t") { cell(row.getOrElse(it) { "" }) }
        }
    }
}
class MathBlock : CustomBlock() { var literal = "" }
class Strike : CustomNode()

/** Pipes inside code spans and escaped pipes are cell text. */
fun tableCells(line: String): List<String> {
    val text = line.trim().removePrefix("|").let { if (it.endsWith("|") && !it.endsWith("\\|")) it.dropLast(1) else it }
    val cells = mutableListOf<String>()
    val cell = StringBuilder()
    var code = 0
    var i = 0
    while (i < text.length) {
        when {
            text[i] == '\\' && text.getOrNull(i + 1) == '|' -> { cell.append('|'); i += 2 }
            text[i] == '`' -> {
                val start = i
                while (i < text.length && text[i] == '`') i++
                val count = i - start
                if (code == 0) code = count else if (code == count) code = 0
                cell.append(text.substring(start, i))
            }
            text[i] == '|' && code == 0 -> { cells += cell.toString().trim(); cell.clear(); i++ }
            else -> cell.append(text[i++])
        }
    }
    cells += cell.toString().trim()
    return cells
}

private class TableParser(private val table: TableBlock) : AbstractBlockParser() {
    private var delimiter = true
    override fun getBlock() = table
    override fun tryContinue(state: ParserState): BlockContinue? =
        if (!state.isBlank && '|' in state.line.content) BlockContinue.atIndex(state.index) else BlockContinue.none()
    override fun addLine(line: SourceLine) {
        if (delimiter) delimiter = false else table.rows += tableCells(line.content.toString()).take(table.header.size)
    }
}
private class MathParser : AbstractBlockParser() {
    private val math = MathBlock()
    private var started = false
    private var closed = false
    override fun getBlock() = math
    override fun tryContinue(state: ParserState): BlockContinue? = if (closed) BlockContinue.none() else BlockContinue.atIndex(state.index)
    override fun addLine(line: SourceLine) {
        var text = line.content.toString()
        if (!started) { text = text.trimStart().removePrefix("$$"); started = true }
        val end = text.indexOf("$$")
        if (end >= 0) { text = text.take(end); closed = true }
        math.literal += text + "\n"
    }
}

fun richMarkdownParser(): Parser = Parser.builder().includeSourceSpans(IncludeSourceSpans.BLOCKS)
    .customBlockParserFactory(object : AbstractBlockParserFactory() {
        override fun tryStart(state: ParserState, matched: MatchedBlockParser): BlockStart? {
            if (state.indent >= 4) return BlockStart.none()
            val line = state.line.content.toString().substring(state.index)
            val math = line.trim()
            if (math.startsWith("$$") && (math.drop(2).indexOf("$$").let { it < 0 || math.drop(it + 4).isBlank() }))
                return BlockStart.of(MathParser()).atIndex(state.index)
            val paragraph = matched.paragraphLines?.content?.toString() ?: return BlockStart.none()
            if ('|' !in paragraph || paragraph.contains('\n')) return BlockStart.none()
            val align = tableCells(line)
            val header = tableCells(paragraph)
            if (align.size != header.size || align.isEmpty() || align.any { !it.matches(Regex(":?-{1,}:?")) }) return BlockStart.none()
            return BlockStart.of(TableParser(TableBlock(header, align))).atIndex(state.index).replaceActiveBlockParser()
        }
    })
    .customDelimiterProcessor(object : DelimiterProcessor {
        override fun getOpeningCharacter() = '~'
        override fun getClosingCharacter() = '~'
        override fun getMinLength() = 2
        override fun process(open: DelimiterRun, close: DelimiterRun): Int {
            if (open.length() < 2 || close.length() < 2) return 0
            val strike = Strike()
            var node = open.opener.next
            while (node != null && node !== close.closer) { val next = node.next; strike.appendChild(node); node = next }
            open.opener.insertAfter(strike)
            return 2
        }
    }).build()

/** Plain-text reading of common LaTeX: symbols, fractions, roots and Unicode super/subscripts. */
fun readableMath(input: String): String =
    MathReader(input.take(32_768)).read(false).text.replace(Regex(" {2,}"), " ").trim()

private val mathSymbols = mapOf(
    "alpha" to "α", "beta" to "β", "gamma" to "γ", "delta" to "δ", "epsilon" to "ε", "varepsilon" to "ε", "zeta" to "ζ",
    "eta" to "η", "theta" to "θ", "kappa" to "κ", "lambda" to "λ", "mu" to "μ", "nu" to "ν", "xi" to "ξ", "pi" to "π",
    "rho" to "ρ", "sigma" to "σ", "tau" to "τ", "phi" to "φ", "varphi" to "φ", "chi" to "χ", "psi" to "ψ", "omega" to "ω",
    "Gamma" to "Γ", "Delta" to "Δ", "Theta" to "Θ", "Lambda" to "Λ", "Pi" to "Π", "Sigma" to "Σ", "Phi" to "Φ", "Omega" to "Ω",
    "infty" to "∞", "times" to "×", "cdot" to "·", "div" to "÷", "pm" to "±", "mp" to "∓", "leq" to "≤", "le" to "≤",
    "geq" to "≥", "ge" to "≥", "neq" to "≠", "ne" to "≠", "approx" to "≈", "equiv" to "≡", "sim" to "∼", "propto" to "∝",
    "rightarrow" to "→", "to" to "→", "leftarrow" to "←", "Rightarrow" to "⇒", "Leftrightarrow" to "⇔", "mapsto" to "↦",
    "sum" to "∑", "prod" to "∏", "int" to "∫", "oint" to "∮", "partial" to "∂", "nabla" to "∇", "in" to "∈", "notin" to "∉",
    "subset" to "⊂", "subseteq" to "⊆", "cup" to "∪", "cap" to "∩", "forall" to "∀", "exists" to "∃", "emptyset" to "∅",
    "ldots" to "…", "cdots" to "⋯", "dots" to "…", "degree" to "°", "circ" to "∘", "angle" to "∠", "perp" to "⊥",
    "sin" to "sin", "cos" to "cos", "tan" to "tan", "log" to "log", "ln" to "ln", "exp" to "exp", "lim" to "lim",
    "max" to "max", "min" to "min", "quad" to " ", "qquad" to " ", "," to " ", ";" to " ", ":" to " ", "!" to "",
    " " to " ", "left" to "", "right" to "", "displaystyle" to "", "{" to "{", "}" to "}", "%" to "%", "$" to "$", "\\" to "\n")
private val textCommands = setOf("text", "mathrm", "mathbf", "mathit", "mathsf", "mathtt", "operatorname", "boldsymbol", "mathcal")
private val superscripts = ("0⁰1¹2²3³4⁴5⁵6⁶7⁷8⁸9⁹+⁺-⁻−⁻=⁼(⁽)⁾aᵃbᵇcᶜdᵈeᵉfᶠgᵍhʰiⁱjʲkᵏlˡmᵐnⁿoᵒpᵖrʳsˢtᵗuᵘvᵛwʷxˣyʸzᶻ" +
    "⁰⁰¹¹²²³³⁴⁴⁵⁵⁶⁶⁷⁷⁸⁸⁹⁹").chunked(2).associate { it[0] to it[1] }
private val subscripts = "0₀1₁2₂3₃4₄5₅6₆7₇8₈9₉+₊-₋−₋=₌(₍)₎aₐeₑhₕiᵢjⱼkₖlₗmₘnₙoₒpₚrᵣsₛtₜuᵤvᵥxₓ"
    .chunked(2).associate { it[0] to it[1] }

private class MathReader(val s: String) {
    class Group(val text: String, val closed: Boolean)
    var i = 0

    fun read(inGroup: Boolean): Group {
        val out = StringBuilder()
        while (i < s.length) {
            val c = s[i]
            when {
                c == '}' && inGroup -> { i++; return Group(out.toString(), true) }
                c == '{' -> { i++; val g = read(true); out.append(if (g.closed) g.text else "{" + g.text) }
                c == '\\' -> out.append(command())
                c == '^' || c == '_' -> { i++; out.append(script(c == '^', argument())) }
                else -> { out.append(c); i++ }
            }
        }
        return Group(out.toString(), false)
    }

    fun argument(): String {
        while (i < s.length && s[i] == ' ') i++
        if (i >= s.length) return ""
        return when (s[i]) {
            '{' -> { i++; val g = read(true); if (g.closed) g.text else "{" + g.text }
            '\\' -> command()
            else -> s[i++].toString()
        }
    }

    fun command(): String {
        i++
        if (i >= s.length) return "\\"
        val name = if (s[i].isLetter()) { val from = i; while (i < s.length && s[i].isLetter()) i++; s.substring(from, i) } else s[i++].toString()
        return when (name) {
            "frac", "dfrac", "tfrac" -> { val top = argument(); val bottom = argument(); "${wrap(top)}/${wrap(bottom)}" }
            "sqrt" -> {
                if (i < s.length && s[i] == '[') { val close = s.indexOf(']', i); if (close > 0) i = close + 1 }
                "√" + wrap(argument())
            }
            in textCommands -> argument()
            else -> mathSymbols[name] ?: "\\$name"
        }
    }

    fun wrap(value: String): String = value.trim().let { if (it.length > 1 && it.any { c -> c in " +-−*/=±" }) "($it)" else it }

    fun script(up: Boolean, value: String): String {
        val table = if (up) superscripts else subscripts
        val text = value.trim()
        if (text.isNotEmpty() && text.all { it in table }) return text.map { table.getValue(it) }.joinToString("")
        return (if (up) "^" else "_") + (if (text.length > 1) "($text)" else text)
    }
}

/** Renders the few HTML tags models commonly emit in Markdown as text; unknown markup stays visible. */
private val htmlBreak = Regex("<\\s*/?\\s*br\\s*/?\\s*>", RegexOption.IGNORE_CASE)
private val htmlFormatting = Regex("</?(?:b|i|u|em|strong|kbd|sup|sub|span|small|mark|center|font|p|div)(?:\\s[^<>]*)?>", RegexOption.IGNORE_CASE)
fun simpleHtmlText(html: String, lineBreak: String = "\n"): String = html.replace(htmlBreak, lineBreak).replace(htmlFormatting, "")

enum class TokenKind { PLAIN, KEYWORD, STRING, NUMBER, COMMENT }
data class CodeToken(val text: String, val kind: TokenKind)
private val keywords = ("val var fun class object interface data sealed suspend override private public internal protected " +
    "import package return if else when for while do break continue true false null None True False def lambda with as " +
    "from try catch except finally raise throw async await function const let export default new this super extends " +
    "implements static void int float double boolean string String bool struct enum impl trait fn pub use mod mut self " +
    "match type func go defer select chan range map make nil switch case include define sizeof typedef unsigned " +
    "SELECT FROM WHERE JOIN LEFT RIGHT INNER ON AS INSERT INTO UPDATE DELETE CREATE TABLE VALUES SET AND OR NOT " +
    "GROUP BY ORDER LIMIT HAVING DISTINCT null true false then fi done esac echo in local let").split(' ').toSet()
private val tokenPattern = Regex("(?s)(/\\*.*?(?:\\*/|$)|<!--.*?(?:-->|$)|//[^\\n]*|#[^\\n]*|--[^\\n]*)|(?:\"(?:\\\\.|[^\"\\\\])*\"?|'(?:\\\\.|[^'\\\\])*'?|`(?:\\\\.|[^`\\\\])*`?)|\\b(?:0[xX][0-9a-fA-F]+|\\d+(?:\\.\\d+)?)\\b|[\\p{L}_$][\\p{L}\\p{N}_$]*")
fun codeTokens(code: String, language: String): List<CodeToken> {
    val lang = language.lowercase()
    if (code.length > 200_000 || lang !in setOf("kotlin", "kt", "python", "py", "javascript", "js", "typescript", "ts", "jsx", "tsx",
            "json", "bash", "sh", "shell", "sql", "html", "xml", "css", "go", "rust", "rs", "java", "c", "cpp", "c++", "yaml", "yml"))
        return listOf(CodeToken(code, TokenKind.PLAIN))
    return buildList {
        var offset = 0
        for (match in tokenPattern.findAll(code)) {
            if (match.range.first > offset) add(CodeToken(code.substring(offset, match.range.first), TokenKind.PLAIN))
            val t = match.value
            val comment = (t.startsWith('#') && lang in setOf("py", "python", "bash", "sh", "shell", "yaml", "yml")) ||
                (t.startsWith("--") && lang == "sql") || t.startsWith("<!--") || t.startsWith("/*") ||
                (t.startsWith("//") && lang !in setOf("py", "python", "bash", "sh", "shell", "yaml", "yml"))
            val before = code.substring((match.range.first - 2).coerceAtLeast(0), match.range.first)
            var after = match.range.last + 1
            while (code.getOrNull(after) == ' ' || code.getOrNull(after) == '\t') after++
            val named = ((lang == "html" || lang == "xml") && (before.endsWith('<') || before == "</")) ||
                (lang in setOf("css", "yaml", "yml") && code.getOrNull(after) == ':')
            add(CodeToken(t, when { comment -> TokenKind.COMMENT; t.first() in "\"'`" -> TokenKind.STRING
                t.first().isDigit() -> TokenKind.NUMBER; t in keywords || (lang == "sql" && t.uppercase() in keywords) || named -> TokenKind.KEYWORD
                else -> TokenKind.PLAIN }))
            offset = match.range.last + 1
        }
        if (offset < code.length) add(CodeToken(code.substring(offset), TokenKind.PLAIN))
    }
}
enum class DiffKind { CONTEXT, ADD, REMOVE, HEADER }
data class DiffLine(val gutter: String, val text: String, val kind: DiffKind)
fun diffLines(diff: String) = diff.lines().map { line -> when {
    line.startsWith("+++") || line.startsWith("---") || line.startsWith("@@") || line.startsWith("diff ") -> DiffLine(" ", line, DiffKind.HEADER)
    line.startsWith('+') -> DiffLine("+", line.drop(1), DiffKind.ADD)
    line.startsWith('-') -> DiffLine("−", line.drop(1), DiffKind.REMOVE)
    else -> DiffLine(" ", line.removePrefix(" "), DiffKind.CONTEXT)
} }
