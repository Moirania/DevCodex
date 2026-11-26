package ai.devcodex.agent.m3l

import android.annotation.SuppressLint
import android.content.Context
import androidx.compose.animation.core.*
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.textmate.theme.FontStyle as TextMateFontStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.pow

/** Optional host header. The renderer performs no copy, fullscreen or icon actions. */
val LocalCodeBlockHeader = staticCompositionLocalOf<(@Composable (CodeBlockContent) -> Unit)?> { null }
val LocalCodeBlockDark = compositionLocalOf { true }

/** Snapshot for the host toolbar; bounds are read when an action is invoked. */
data class CodeBlockContent(
    val code: String,
    val label: String,
    val language: String,
    val highlightedLines: List<AnnotatedString>,
    val palette: CodeBlockPalette,
    val boundsInWindow: () -> Rect? = { null }
)

@Composable
@SuppressLint("ProduceStateDoesNotAssignValue")
internal fun CodeBlockView(code: String, info: String?, primaryTint: Color = Color.Black) {
    val context = LocalContext.current
    val darkCodeBlock = LocalCodeBlockDark.current
    val palette = codeBlockPalette(darkCodeBlock, primaryTint)
    val lines = code.replace("\r\n", "\n").replace('\r', '\n').split("\n")
    val fenceLabel = info?.trim()?.substringBefore(' ').orEmpty()
    val normalizedLanguage = normalizeCodeLanguage(fenceLabel)
    val language = normalizedLanguage.takeIf { hasGrammar(context, it) }
    val isDiff = fenceLabel.equals("diff", true) || normalizedLanguage.equals("diff", true)
    val codeLines = lines
    var blockCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }

    val lineCount = codeLines.size
    val maxLineNumberWidth = lineCount.toString().length
    val appContext = context.applicationContext
    val codeHScroll = rememberScrollState()
    // Diff 渲染：+ 绿 / - 红 / @@ 蓝，其余默认色（不走 TextMate）
    val diffLines: List<AnnotatedString>? = if (isDiff) codeLines.map { ln ->
        buildAnnotatedString {
            when {
                ln.startsWith("+") -> withStyle(SpanStyle(color = Color(0xFF2EA043))) { append(ln) }
                ln.startsWith("-") -> withStyle(SpanStyle(color = Color(0xFFD73A49))) { append(ln) }
                ln.startsWith("@@") -> withStyle(SpanStyle(color = Color(0xFF58A6FF))) { append(ln) }
                ln.startsWith("+++") || ln.startsWith("---") -> withStyle(SpanStyle(color = Color(0xFF8B949E))) { append(ln) }
                else -> append(ln)
            }
        }
    } else null
    val codeTheme = LocalCodeTheme.current
    val highlightedLines by produceState(
        initialValue = diffLines ?: codeLines.map { AnnotatedString(it) },
        codeLines,
        language,
        darkCodeBlock,
        codeTheme
    ) {
        if (diffLines != null) { value = diffLines; return@produceState }
        value = highlightCodeLinesAsync(codeLines, language, darkCodeBlock, codeTheme, appContext)
    }

    val header = LocalCodeBlockHeader.current
    val block = CodeBlockContent(
        code = code,
        label = fenceLabel,
        language = normalizedLanguage,
        highlightedLines = highlightedLines,
        palette = palette,
        boundsInWindow = { blockCoordinates?.takeIf { it.isAttached }?.boundsInWindow() }
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp)
            .onGloballyPositioned { blockCoordinates = it }
            .background(palette.background, RoundedCornerShape(8.dp))
    ) {
        if (header != null) {
            header(block)
        } else if (fenceLabel.isNotEmpty()) {
            BasicText(
                text = fenceLabel,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                style = TextStyle(color = palette.muted, fontSize = 12.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
            )
        } else {
            Spacer(Modifier.height(12.dp))
        }
        CodeBlockBody(
            highlightedLines = highlightedLines,
            maxLineNumberWidth = maxLineNumberWidth,
            textColor = palette.text,
            numberColor = palette.muted,
            horizontalState = codeHScroll,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 12.dp)
        )
    }
}

private suspend fun highlightCodeLinesAsync(
    lines: List<String>,
    language: String?,
    dark: Boolean,
    codeTheme: String,
    context: Context,
): List<AnnotatedString> = withContext(Dispatchers.Default) {
    try {
        highlightCodeLines(lines, language, dark, codeTheme, context)
    } catch (error: Throwable) {
        lines.map { AnnotatedString(it) }
    }
}

@Composable
fun CodeBlockBody(
    highlightedLines: List<AnnotatedString>,
    maxLineNumberWidth: Int,
    textColor: Color,
    numberColor: Color,
    horizontalState: ScrollState,
    showHorizontalBar: Boolean = true,
    fillHeight: Boolean = false,
    modifier: Modifier = Modifier
) {
    val hBarVisible by rememberScrollbarVisibility(horizontalState)
    val hBarActive by rememberScrollActivity(horizontalState)
    val numberStyle = codeLineStyle(numberColor)
    val textStyle = codeLineStyle(textColor)
    val scrollbarThumb = if (!textColor.isDark()) {
        Color.White.copy(alpha = 0.4f)
    } else {
        Color.Black.copy(alpha = 0.4f)
    }

    Box(modifier) {
        Row(
            modifier = if (fillHeight) Modifier.fillMaxSize() else Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = if (fillHeight) Modifier.fillMaxHeight() else Modifier.wrapContentHeight()
            ) {
                highlightedLines.forEachIndexed { index, _ ->
                    BasicText(
                        text = (index + 1).toString().padStart(maxLineNumberWidth, ' '),
                        style = numberStyle,
                        modifier = Modifier.width(numberColumnWidth(maxLineNumberWidth))
                    )
                }
                if (fillHeight) {
                    Spacer(Modifier.weight(1f))
                }
            }

            Box(
                modifier = if (fillHeight) Modifier.weight(1f).fillMaxHeight()
                else Modifier.weight(1f)
            ) {
                Column(
                    modifier = Modifier
                        // v0.4.15：移动端软换行替代横向滚动（装机两次反馈"行尾截断"）。
                        // 每行 BasicText 自然换行，行号列仍在左侧（VSCode 软换行式观感）
                        .then(if (fillHeight) Modifier.fillMaxHeight() else Modifier.wrapContentHeight())
                ) {
                    highlightedLines.forEach { line ->
                        BasicText(
                            text = line.ifEmpty { AnnotatedString(" ") },
                            style = textStyle,
                        modifier = Modifier
                        )
                    }
                    if (fillHeight) {
                        Spacer(Modifier.weight(1f))
                    }
                }
            }
        }

        if (showHorizontalBar) {
            CodeHorizontalScrollbar(
                state = horizontalState,
                visible = hBarActive && hBarVisible,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 2.dp)
                    .padding(top = SCROLLBAR_GAP),
                thumbColor = scrollbarThumb
            )
        }
    }
}

private fun numberColumnWidth(digits: Int): Dp =
    (CODE_FONT_SIZE.value * 0.62f * digits).dp + CODE_GUTTER

private fun codeLineStyle(color: Color) = TextStyle(
    color = color,
    fontSize = CODE_FONT_SIZE,
    lineHeight = CODE_LINE_HEIGHT,
    fontFamily = FontFamily.Monospace
)

private fun Color.isDark(): Boolean {
    val r = if (red <= 0.04045f) red / 12.92f else ((red + 0.055f) / 1.055f).pow(2.4f)
    val g = if (green <= 0.04045f) green / 12.92f else ((green + 0.055f) / 1.055f).pow(2.4f)
    val b = if (blue <= 0.04045f) blue / 12.92f else ((blue + 0.055f) / 1.055f).pow(2.4f)
    return (0.2126f * r + 0.7152f * g + 0.0722f * b) < 0.5f
}

@Composable
@SuppressLint("UnusedBoxWithConstraintsScope")
private fun CodeHorizontalScrollbar(
    state: ScrollState,
    visible: Boolean,
    modifier: Modifier = Modifier,
    thumbColor: Color = SCROLLBAR_THUMB
) {
    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(SCROLLBAR_FADE_MILLIS, easing = FastOutSlowInEasing),
        label = "hBarAlpha"
    )
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(SCROLLBAR_TRACK_HEIGHT)
    ) {
        val density = LocalDensity.current
        val viewportDp = maxWidth
        val viewportPx = with(density) { viewportDp.toPx() }
        val contentPx = viewportPx + state.maxValue
        if (contentPx > 0f && viewportPx > 0f) {
            val thumbWidth = viewportPx * (viewportPx / contentPx).coerceAtLeast(SCROLLBAR_MIN_FRACTION)
            val travel = viewportPx - thumbWidth
            val fraction = if (state.maxValue > 0) state.value.toFloat() / state.maxValue else 0f
            Box(
                modifier = Modifier
                    .graphicsLayer {
                        this.alpha = alpha
                        translationX = travel * fraction
                        scaleX = (thumbWidth / viewportPx).coerceAtLeast(SCROLLBAR_MIN_FRACTION)
                        transformOrigin = TransformOrigin(0f, 0.5f)
                    }
                    .fillMaxHeight()
                    .fillMaxWidth()
                    .background(color = thumbColor, shape = RoundedCornerShape(50))
            )
        }
    }
}

@Composable
private fun rememberScrollbarVisibility(state: ScrollState): State<Boolean> {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(state) {
        snapshotFlow { state.maxValue > 0 }.collect { hasRange ->
            visible = hasRange
        }
    }
    return rememberUpdatedState(visible)
}

@Composable
private fun rememberScrollActivity(vararg states: ScrollState): State<Boolean> {
    var active by remember { mutableStateOf(false) }
    LaunchedEffect(*states) {
        var hideJob: Job? = null
        var previous = states.map { it.value }
        snapshotFlow { states.map { it.value to it.isScrollInProgress } }
            .collect { snapshot ->
                val scrolling = snapshot.any { it.second }
                val moved = snapshot.indices.any { snapshot[it].first != previous[it] }
                previous = snapshot.map { it.first }
                if (scrolling || moved) {
                    hideJob?.cancel()
                    hideJob = null
                    if (!active) active = true
                    if (!scrolling) {
                        hideJob = launch { delay(SCROLLBAR_HIDE_DELAY); active = false }
                    }
                } else if (active && hideJob == null) {
                    hideJob = launch { delay(SCROLLBAR_HIDE_DELAY); active = false }
                }
            }
    }
    return rememberUpdatedState(active)
}


private val CODE_FONT_SIZE = 13.sp
private val CODE_LINE_HEIGHT = 22.sp
private val CODE_GUTTER = 14.dp
private const val SCROLLBAR_HIDE_DELAY = 700L
private const val SCROLLBAR_FADE_MILLIS = 220
private val SCROLLBAR_GAP = 8.dp
private val SCROLLBAR_TRACK_HEIGHT = 5.dp
private val SCROLLBAR_THUMB = Color(0x66FFFFFF)
private const val SCROLLBAR_MIN_FRACTION = 0.12f

private fun normalizeCodeLanguage(label: String): String {
    return when (label.lowercase().substringBefore(' ').trim()) {
        "py", "python3" -> "python"
        "js", "mjs", "cjs", "node" -> "javascript"
        "ts", "mts", "cts" -> "typescript"
        "kt", "kts" -> "kotlin"
        "c++", "cc", "cxx", "hpp" -> "cpp"
        "c#", "cs" -> "csharp"
        "golang" -> "go"
        "rs" -> "rust"
        // v0.4.15 修：此前 sh→shellscript / bash→powershell 映射的文件根本不存在（assets 只有
        // shell.tmLanguage.json）→ 全部 fallback 无高亮（装机截图实锤"代码块全部同色"）
        "sh", "zsh", "shell", "shellscript", "bash", "console", "terminal" -> "shell"
        "pwsh", "ps1", "powershell" -> "powershell"
        "htm" -> "html"
        "yml" -> "yaml"
        "pl", "perl" -> "perl"
        "hs" -> "haskell"
        "ex", "exs" -> "elixir"
        "erl" -> "erlang"
        "clj", "cljs" -> "clojure"
        "jl" -> "julia"
        "sol" -> "solidity"
        "gql" -> "graphql"
        "f90", "f95", "f03" -> "fortran-free-form"
        "cr" -> "crystal"
        "ml", "mli" -> "ocaml"
        "fs", "fsi", "fsx" -> "fsharp"
        "rkt" -> "racket"
        "lisp", "cl", "el" -> "common-lisp"
        "adb", "ads" -> "ada"
        "purs", "rescript" -> "purescript"
        "hx" -> "haxe"
        "wl", "nb", "mathematica" -> "wolfram"
        "tex" -> "latex"
        "md", "mkd" -> "markdown"
        "dockerfile" -> "docker"
        ".env", "dotenv" -> "dotenv"
        "tf", "tfvars" -> "terraform"
        "el", "emacs", "gnuemacs" -> "emacs-lisp"
        "proto" -> "proto"
        "make" -> "make"
        "objc" -> "objective-c"
        "objcpp" -> "objective-cpp"
        "coffee", "coffeescript" -> "coffee"
        "htmles" -> "html-derivative"
        "cshtml" -> "razor"
        "restructuredtext", "rst" -> "rst"
        "f#" -> "fsharp"
        "vb", "vbnet" -> "vb"
        "plaintext", "txt" -> "text"
        else -> label.lowercase().substringBefore(' ').trim()
    }
}

data class CodeBlockPalette(
    val background: Color,
    val text: Color,
    val muted: Color,
    val keyword: Color,
    val string: Color,
    val number: Color,
    val comment: Color,
    val type: Color,
    val function: Color,
    val operator: Color,
    val annotation: Color,
    val property: Color
)

/** 代码块配色方案（Compose 状态：LocalCodeTheme 变化触发代码块重组实时换色） */
val LocalCodeTheme = androidx.compose.runtime.compositionLocalOf { "github" }

/** 兼容旧路径（设置页写入） */
internal object CodeThemeHolder {
    @Volatile var current: String = "github"
}


/** 深色调色板按主题字符串（非 Composable：协程高亮里用） */
/** v0.4.11 浅色版代码主题：修"浅色模式下 codeTheme 选择不生效"——9 主题全部提供浅色配色 */
internal fun lightPaletteForTheme(theme: String): CodeBlockPalette = when (theme) {
    "dracula" -> LightCodeBlockPalette.copy(
        background = Color(0xFFFDF6F3), text = Color(0xFF44475A),
        keyword = Color(0xFF7C3AED), string = Color(0xFFB8860B), number = Color(0xFF8250DF),
        comment = Color(0xFF9497AE), type = Color(0xFFB45309),
    )
    "onedark" -> LightCodeBlockPalette.copy(
        background = Color(0xFFFAFAFA), text = Color(0xFF383A42),
        keyword = Color(0xFFA626A4), string = Color(0xFF50A14F), number = Color(0xFF986801),
        comment = Color(0xFFA0A1A7), type = Color(0xFFC18401),
    )
    "solar" -> LightCodeBlockPalette.copy(
        background = Color(0xFFFDF6E3), text = Color(0xFF657B83),
        keyword = Color(0xFF859900), string = Color(0xFF2AA198), number = Color(0xFFD33682),
        comment = Color(0xFF93A1A1), type = Color(0xFFB58900),
    )
    "monokai" -> LightCodeBlockPalette.copy(
        background = Color(0xFFFAFAF2), text = Color(0xFF3B3A35),
        keyword = Color(0xFFF92672), string = Color(0xFF497D14), number = Color(0xFF7A3EC8),
        comment = Color(0xFF9E987F), type = Color(0xFF0F7D8C),
    )
    "nord" -> LightCodeBlockPalette.copy(
        background = Color(0xFFF2F4F8), text = Color(0xFF2E3440),
        keyword = Color(0xFF5E81AC), string = Color(0xFF7EA15C), number = Color(0xFF9C6B96),
        comment = Color(0xFF7B88A1), type = Color(0xFF4C7A89),
    )
    "gruvbox" -> LightCodeBlockPalette.copy(
        background = Color(0xFFFBF1C7), text = Color(0xFF3C3836),
        keyword = Color(0xFF9D0006), string = Color(0xFF797403), number = Color(0xFF8F3F71),
        comment = Color(0xFF928374), type = Color(0xFFB57614),
    )
    "vscode" -> LightCodeBlockPalette.copy(
        background = Color(0xFFF8F8F8), text = Color(0xFF333333),
        keyword = Color(0xFF0000FF), string = Color(0xFFA31515), number = Color(0xFF098658),
        comment = Color(0xFF008000), type = Color(0xFF267F99),
    )
    "highcontrast" -> LightCodeBlockPalette.copy(
        background = Color(0xFFFFFFFF), text = Color(0xFF000000),
        keyword = Color(0xFF0000C0), string = Color(0xFF8A0014), number = Color(0xFF005A5A),
        comment = Color(0xFF5A5A5A), type = Color(0xFF00477A),
    )
    else -> LightCodeBlockPalette  // github
}

internal fun darkPaletteForTheme(theme: String): CodeBlockPalette = when (theme) {
    "dracula" -> DarkCodeBlockPalette.copy(
        background = Color(0xFF282A36), text = Color(0xFFF8F8F2),
        keyword = Color(0xFFFF79C6), string = Color(0xFFF1FA8C), number = Color(0xFFBD93F9),
        comment = Color(0xFF6272A4), type = Color(0xFFFFB86C),
    )
    "onedark" -> DarkCodeBlockPalette.copy(
        background = Color(0xFF282C34), text = Color(0xFFABB2BF),
        keyword = Color(0xFFC678DD), string = Color(0xFF98C379), number = Color(0xFFD19A66),
        comment = Color(0xFF5C6370), type = Color(0xFFE5C07B),
    )
    "solar" -> DarkCodeBlockPalette.copy(
        background = Color(0xFF002B36), text = Color(0xFF839496),
        keyword = Color(0xFF859900), string = Color(0xFF2AA198), number = Color(0xFFD33682),
        comment = Color(0xFF586E75), type = Color(0xFFB58900),
    )
    "monokai" -> DarkCodeBlockPalette.copy(
        background = Color(0xFF272822), text = Color(0xFFF8F8F2),
        keyword = Color(0xFFF92672), string = Color(0xFFE6DB74), number = Color(0xFFAE81FF),
        comment = Color(0xFF75715E), type = Color(0xFF66D9EF),
    )
    "nord" -> DarkCodeBlockPalette.copy(
        background = Color(0xFF2E3440), text = Color(0xFFD8DEE9),
        keyword = Color(0xFF81A1C1), string = Color(0xFFA3BE8C), number = Color(0xFFB48EAD),
        comment = Color(0xFF616E88), type = Color(0xFF8FBCBB),
    )
    "gruvbox" -> DarkCodeBlockPalette.copy(
        background = Color(0xFF282828), text = Color(0xFFEBDBB2),
        keyword = Color(0xFFFB4934), string = Color(0xFFB8BB26), number = Color(0xFFD3869B),
        comment = Color(0xFF928374), type = Color(0xFFFABD2F),
    )
    "vscode" -> DarkCodeBlockPalette.copy(
        background = Color(0xFF1E1E1E), text = Color(0xFFD4D4D4),
        keyword = Color(0xFF569CD6), string = Color(0xFFCE9178), number = Color(0xFFB5CEA8),
        comment = Color(0xFF6A9955), type = Color(0xFF4EC9B0),
    )
    "highcontrast" -> DarkCodeBlockPalette.copy(
        background = Color(0xFF000000), text = Color(0xFFFFFFFF),
        keyword = Color(0xFFFFFF00), string = Color(0xFF00FF00), number = Color(0xFFFFFF66),
        comment = Color(0xFFAAAAAA), type = Color(0xFF00FFFF),
    )
    else -> DarkCodeBlockPalette
}

internal val DarkCodeBlockPalette = CodeBlockPalette(
    background = Color(0xFF161B22),
    text = Color(0xFFC9D1D9),
    muted = Color(0xFF8B949E),
    keyword = Color(0xFFC792EA),
    string = Color(0xFFC3E88D),
    number = Color(0xFFF78C6C),
    comment = Color(0xFF7C8495),
    type = Color(0xFFFFCB6B),
    function = Color(0xFF82AAFF),
    operator = Color(0xFF89DDFF),
    annotation = Color(0xFFFF5370),
    property = Color(0xFFF07178)
)

internal val LightCodeBlockPalette = CodeBlockPalette(
    background = Color.White,
    text = Color(0xFF1F2328),
    muted = Color(0xFF6B7280),
    keyword = Color(0xFFCF222E),
    string = Color(0xFF0A3069),
    number = Color(0xFF0550AE),
    comment = Color(0xFF6E7781),
    type = Color(0xFF953800),
    function = Color(0xFF8250DF),
    operator = Color(0xFF0550AE),
    annotation = Color(0xFF953800),
    property = Color(0xFF116329)
)

// 浅色用 primary 6% 叠白而非纯白：跟主题联动，又不压过语法高亮
@Composable
fun codeBlockPalette(dark: Boolean, primaryTint: Color): CodeBlockPalette {
    val theme = LocalCodeTheme.current
    if (!dark) {
        // v0.4.11：浅色也按 codeTheme 出配色（GitHub 浅色仍走 primary 6% 融白，其余主题用官方浅色底）
        return if (theme == "github") LightCodeBlockPalette.copy(
            background = primaryTint.copy(alpha = 0.06f).compositeOver(Color.White)
        ) else lightPaletteForTheme(theme)
    }
    // 深色：按预设方案（MChat 式多方案）
    return when (theme) {
        "dracula" -> DarkCodeBlockPalette.copy(
            background = Color(0xFF282A36), text = Color(0xFFF8F8F2),
            keyword = Color(0xFFFF79C6), string = Color(0xFFF1FA8C), number = Color(0xFFBD93F9),
            comment = Color(0xFF6272A4), type = Color(0xFFFFB86C),
        )
        "onedark" -> DarkCodeBlockPalette.copy(
            background = Color(0xFF282C34), text = Color(0xFFABB2BF),
            keyword = Color(0xFFC678DD), string = Color(0xFF98C379), number = Color(0xFFD19A66),
            comment = Color(0xFF5C6370), type = Color(0xFFE5C07B),
        )
        "solar" -> DarkCodeBlockPalette.copy(
            background = Color(0xFF002B36), text = Color(0xFF839496),
            keyword = Color(0xFF859900), string = Color(0xFF2AA198), number = Color(0xFFD33682),
            comment = Color(0xFF586E75), type = Color(0xFFB58900),
        )
        "highcontrast" -> darkPaletteForTheme("highcontrast")
        "monokai" -> darkPaletteForTheme("monokai")
        "nord" -> darkPaletteForTheme("nord")
        "gruvbox" -> darkPaletteForTheme("gruvbox")
        "vscode" -> darkPaletteForTheme("vscode")
        else -> DarkCodeBlockPalette  // github
    }
}

private fun highlightCodeLines(
    lines: List<String>,
    language: String?,
    dark: Boolean,
    codeTheme: String,
    context: Context,
): List<AnnotatedString> {
    val plain = lines.map { AnnotatedString(it) }
    if (language == null) return plain
    return try {
        val palette = if (dark) darkPaletteForTheme(codeTheme) else lightPaletteForTheme(codeTheme)
        val grammar = codeGrammar(context, language) ?: return plain
        val tokens = codeTokens(grammar, lines)
        val theme = codeTheme(dark, palette)
        val defaultColor = palette.text

        lines.mapIndexed { index, line ->
            if (line.isEmpty()) return@mapIndexed AnnotatedString("")
            val resolved = tokens.getOrNull(index).orEmpty().map { token ->
                Pair(token, resolveStyle(theme, token.scopes))
            }
            buildAnnotatedString {
                append(line)
                resolved.forEach { (token, style) ->
                    val start = token.start.coerceIn(0, line.length)
                    val end = token.end.coerceIn(start, line.length)
                    if (end <= start) return@forEach
                    val resolvedColor = style?.foreground
                        ?.takeIf { it != 0L }
                        ?.let { Color(textMateForegroundColor(it)) }
                    val italic = style?.fontStyle?.any { it == TextMateFontStyle.ITALIC } == true
                    if (resolvedColor == null && !italic) return@forEach
                    addStyle(
                        SpanStyle(
                            color = resolvedColor ?: defaultColor,
                            fontStyle = if (italic) FontStyle.Italic else null
                        ),
                        start,
                        end
                    )
                }
            }
        }
    } catch (error: Throwable) {
        plain
    }
}
