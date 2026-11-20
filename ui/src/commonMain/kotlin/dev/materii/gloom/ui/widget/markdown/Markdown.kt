package dev.materii.gloom.ui.widget.markdown

import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.apollographql.apollo.api.http.internal.urlEncode
import com.benasher44.uuid.uuid4
import com.multiplatform.webview.jsbridge.rememberWebViewJsBridge
import com.multiplatform.webview.web.NativeWebView
import com.multiplatform.webview.web.WebView
import com.multiplatform.webview.web.rememberWebViewNavigator
import com.multiplatform.webview.web.rememberWebViewStateWithHTMLData
import dev.icerock.moko.resources.compose.readTextAsState
import dev.materii.gloom.Res
import dev.materii.gloom.api.translation.TranslationManager
import dev.materii.gloom.domain.manager.PreferenceManager
import dev.materii.gloom.ui.theme.CodeTheme
import dev.materii.gloom.ui.theme.gloomColorScheme
import dev.materii.gloom.ui.util.markdown.MarkdownRequestInterceptor
import dev.materii.gloom.ui.util.markdown.MarkdownUtil
import dev.materii.gloom.util.LocalLinkHandler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.koin.compose.koinInject

private var MarkdownTemplate = "" // Cache the loaded markdown html template
private val PrefersLightRx = "prefers-color-scheme:\\s*light".toRegex(RegexOption.IGNORE_CASE)
private val PrefersDarkRx = "prefers-color-scheme:\\s*dark".toRegex(RegexOption.IGNORE_CASE)

// GitHub image hosts are unreachable in some regions, so README images are
// rewritten to go through a public GitHub content mirror instead.
private const val IMAGE_MIRROR = "https://gh-proxy.com"
private val MirrorableHostRx = Regex(
    "https://(raw\\.githubusercontent\\.com|user-images\\.githubusercontent\\.com" +
        "|private-user-images\\.githubusercontent\\.com|avatars\\.githubusercontent\\.com)/"
)
private val CamoUrlRx = Regex("https://camo\\.githubusercontent\\.com/[0-9a-f]{64}/([0-9a-f]+)")

private fun hexDecode(input: String): String? = try {
    input.chunked(2).map { it.toInt(16).toChar() }.joinToString("")
} catch (_: Exception) {
    null
}

/**
 * Rewrites image URLs inside the rendered README HTML so they load reliably:
 * - GitHub's camo proxy URLs are decoded back to the original image URL
 * - GitHub image hosts (raw/user-images/...) are routed through a public mirror
 */
private fun mirrorImages(html: String): String {
    val decoded = CamoUrlRx.replace(html) { match ->
        val original = hexDecode(match.groupValues[1])
        when {
            original == null                     -> match.value
            MirrorableHostRx.containsMatchIn(original) -> "$IMAGE_MIRROR/$original"
            else                                 -> original
        }
    }
    return MirrorableHostRx.replace(decoded) { match -> "$IMAGE_MIRROR/${match.value}" }
}

/**
 * Wrapper around a WebView to automatically add styling
 * and fix a couple issues.
 *
 * When translation is enabled, block-level paragraphs are numbered with
 * `data-tr-idx` before injection; scrolling unlocks more paragraphs which are
 * translated lazily (FluentRead-style "translate as you scroll") and pushed
 * back into the WebView via `gloom.applyTranslations`, so each translated
 * paragraph appears right below its source.
 *
 * @see `shared/src/commonMain/moko-resources/assets/markdown`
 *
 * @param html The rendered HTML, returned from the GitHub API
 * @param modifier The [Modifier] used to style this [Markdown] component
 * @param scrollState Optional scroll state of the containing page; drives lazy translation
 */
@Composable
fun Markdown(
    html: String,
    modifier: Modifier = Modifier,
    scrollState: androidx.compose.foundation.ScrollState? = null
) {
    val linkHandler = LocalLinkHandler.current
    val prefs: PreferenceManager = koinInject()
    val translationManager: TranslationManager = koinInject()

    val interceptor = MarkdownRequestInterceptor(linkHandler)
    val navigator = rememberWebViewNavigator(requestInterceptor = interceptor)
    val bridge = rememberWebViewJsBridge(navigator)

    val id = remember { uuid4().toString() }
    val isLight = MaterialTheme.colorScheme.background.luminance() > 0.5f
    val translateEnabled = prefs.autoTranslate && prefs.translateReadme

    val indexedParagraphs = remember(html, translateEnabled) {
        if (translateEnabled) translationManager.extractIndexedParagraphs(html) else emptyList()
    }

    val markdown = remember(html, indexedParagraphs) {
        val prepared = if (indexedParagraphs.isNotEmpty()) {
            translationManager.injectParagraphIndices(html)
        } else {
            html
        }
        mirrorImages(prepared)
            // TODO: Allow for jumping to headings, this will require special handling
//            .replace("<a href=\"#", "<a href=\"gloom://github.com/?anchor=")
            .replace("\\", "\\\\")
            .replace("\n", "\\n")
            .replace("\"", "\\\"")

            // This is a weird hack to get around how Android determines 'prefers-color-scheme'
            // media queries. Since we theme independently of Android, we have to calculate
            // this ourselves. To do this, we use 'min-width: 0' as a stand in for 'true' and
            // 'min-width: -1' as a stand-in for false.
            .replace(PrefersLightRx, "min-width: ${if (isLight) 0 else -1}")
            .replace(PrefersDarkRx, "min-width: ${if (!isLight) 0 else -1}")

            .urlEncode() // So that we can safely pass it to the JavaScript side
    }

    val template = MarkdownTemplate.ifBlank {
        val file by Res.assets.markdown.template_html.readTextAsState()
        MarkdownTemplate = file ?: ""; MarkdownTemplate
    }

    val colorScheme = MaterialTheme.colorScheme
    val gloomColorScheme = MaterialTheme.gloomColorScheme
    val state = rememberWebViewStateWithHTMLData(
        data = MarkdownUtil.injectAppTheme(
            template,
            isLight,
            colorScheme,
            gloomColorScheme,
            CodeTheme.getDefault()
        )
    )

    // --- Lazy paragraph translation (FluentRead-style: translate as you scroll) ---
    val translated = remember(html) { mutableStateMapOf<Int, List<String>>() }
    var visibleCount by remember(html) { mutableIntStateOf(indexedParagraphs.size) }

    LaunchedEffect(scrollState, indexedParagraphs.size) {
        val state = scrollState ?: return@LaunchedEffect
        snapshotFlow { state.value to state.maxValue }.collect { (offset, max) ->
            val nearBottom = max > 0 && offset >= max - NEAR_BOTTOM_PX
            val target = if (nearBottom) {
                indexedParagraphs.size
            } else {
                INITIAL_BATCH + (offset / SCROLL_PER_BATCH) * BATCH_SIZE
            }
            if (target > visibleCount) {
                visibleCount = target.coerceAtMost(indexedParagraphs.size)
            }
        }
    }

    LaunchedEffect(visibleCount, indexedParagraphs) {
        if (indexedParagraphs.isEmpty()) return@LaunchedEffect
        val todo = indexedParagraphs
            .take(visibleCount)
            .filter { it.idx !in translated }
        if (todo.isEmpty()) return@LaunchedEffect

        // Translations are pushed to the WebView in small batches — one
        // loadUrl per paragraph makes the page flash on every reflow.
        val pending = java.util.concurrent.ConcurrentHashMap<Int, List<String>>()
        val completed = java.util.concurrent.atomic.AtomicInteger(0)

        fun pushPending() {
            if (pending.isEmpty()) return
            val payload = pending.entries.joinToString(",", prefix = "{", postfix = "}") { (idx, lines) ->
                "\"$idx\":${lines.joinToString(",", prefix = "[", postfix = "]") { jsonQuote(it) }}"
            }
            pending.clear()
            navigator.loadUrl("javascript:gloom.applyTranslations($payload)")
        }

        coroutineScope {
            val limiter = Semaphore(TRANSLATION_CONCURRENCY)
            todo.map { paragraph ->
                limiter.withPermit {
                    async(start = CoroutineStart.LAZY) {
                        val lines = try {
                            translationManager.translateToLines(paragraph.text)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            emptyList()
                        }
                        if (lines.isNotEmpty()) {
                            translated[paragraph.idx] = lines
                            pending[paragraph.idx] = lines
                        }
                        if (completed.incrementAndGet() % PUSH_BATCH == 0 || completed.get() == todo.size) {
                            pushPending()
                        }
                    }
                }
            }.awaitAll()
            pushPending()
        }
    }

    // Inject the markdown exactly once per content; re-injecting wipes
    // already-applied translations and makes the page flash.
    var lastInjected by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(state.isLoading, markdown) {
        val target = "javascript:gloom.load(\"$id\", \"$markdown\")"
        if (!state.isLoading && lastInjected != target) {
            lastInjected = target
            navigator.loadUrl(target)
        }
    }

    // Re-apply translations that completed before the WebView finished loading.
    LaunchedEffect(state.isLoading) {
        if (state.isLoading || translated.isEmpty()) return@LaunchedEffect
        val payload = translated.entries.joinToString(",", prefix = "{", postfix = "}") { (idx, lines) ->
            "\"$idx\":${lines.joinToString(",", prefix = "[", postfix = "]") { jsonQuote(it) }}"
        }
        navigator.loadUrl("javascript:gloom.applyTranslations($payload)")
    }

    // A recycled WebView would keep rendering the previous item's body:
    // every item shares the same HTML template (the body is injected later
    // via JS), so the library's content watcher never re-fires on reuse.
    // Keying on the markdown forces a brand-new WebView whenever the
    // content differs, which kills the reuse pollution at the root.
    key(markdown) {
        WebView(
            state = state,
            navigator = navigator,
            webViewJsBridge = bridge,
            onCreated = { view: NativeWebView ->
                onWebViewCreated(view)
            },
            onDispose = { WebViewTouchGate.set(false) },
            modifier = Modifier
                    .background(Color.Transparent)
                    .then(modifier)
        )
    }
}

private fun jsonQuote(value: String): String {
    val sb = StringBuilder("\"")
    for (ch in value) {
        when (ch) {
            '\\' -> sb.append("\\\\")
            '"'  -> sb.append("\\\"")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            else -> if (ch.code < 0x20) sb.append("\\u%04x".format(ch.code)) else sb.append(ch)
        }
    }
    sb.append("\"")
    return sb.toString()
}

private const val INITIAL_BATCH = 8
private const val BATCH_SIZE = 6
private const val SCROLL_PER_BATCH = 900
private const val NEAR_BOTTOM_PX = 800
private const val TRANSLATION_CONCURRENCY = 2

/** Translated paragraphs are pushed to the WebView in batches of this size. */
private const val PUSH_BATCH = 4

/**
 * [T] should correspond to [NativeWebView] but I can't use that here directly
 */
expect fun <T> onWebViewCreated(view: T)
