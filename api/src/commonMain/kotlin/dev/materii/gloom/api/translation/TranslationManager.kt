package dev.materii.gloom.api.translation

import dev.materii.gloom.domain.manager.PreferenceManager
import dev.materii.gloom.domain.manager.enums.FreePoolMode
import dev.materii.gloom.domain.manager.enums.TranslationEngine
import dev.materii.gloom.domain.manager.enums.TranslationTarget
import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ln
import kotlin.random.Random

/**
 * Facade over the translation services.
 *
 * Pool routing is intentionally simple and mirrors the state that was verified
 * working: walk the enabled services in order (balanced = weighted random
 * order), skip the ones cooling down after a failure, and return the first
 * success. No extra pacing, no sentence splitting.
 */
class TranslationManager(
    prefs: PreferenceManager,
    client: HttpClient,
    json: kotlinx.serialization.json.Json
) {

    private val prefs = prefs

    private val client = client

    private val json = json

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val cache = ConcurrentHashMap<String, String>()
    private val inFlight = ConcurrentHashMap<String, Deferred<String>>()
    private val cooldownUntil = ConcurrentHashMap<String, Long>()

    private val google by lazy { GoogleTranslateService(client, json) }
    private val microsoft by lazy { MicrosoftTranslateService(client, json) }
    private val myMemory by lazy { MyMemoryTranslateService(client, json) { prefs.mymemoryEmail } }
    private val deeplx by lazy { DeepLxTranslateService(client, json, { prefs.deeplxEndpoint }, { prefs.deeplxToken }) }
    private val openai by lazy {
        OpenAITranslateService(client, json, { prefs.openaiEndpoint }, { prefs.openaiKey }, { prefs.openaiModel })
    }
    private val transmart by lazy { TransmartTranslateService(client, json) }
    private val volcengine by lazy { VolcengineTranslateService(client, json) }
    private val yandex by lazy { YandexTranslateService(client, json) }
    private val youdao by lazy { YoudaoTranslateService(client, json) }
    private val iciba by lazy { IcibaTranslateService(client, json) }
    private val alibaba by lazy { AlibabaTranslateService(client, json) }
    private val modernMt by lazy { ModernMtTranslateService(client, json) }
    private val lingvanex by lazy { LingvanexTranslateService(client, json) }
    private val reverso by lazy { ReversoTranslateService(client, json) }
    private val apertium by lazy { ApertiumTranslateService(client, json) }
    private val bilibili by lazy { BilibiliTranslateService(client, json) }

    private fun serviceForPool(pool: FreePoolService): TranslationService = when (pool) {
        FreePoolService.MICROSOFT -> microsoft
        FreePoolService.GOOGLE -> google
        FreePoolService.MYMEMORY -> myMemory
        FreePoolService.TRANSMART -> transmart
        FreePoolService.VOLCENGINE -> volcengine
        FreePoolService.YANDEX -> yandex
        FreePoolService.YOUDAO -> youdao
        FreePoolService.ICIBA -> iciba
        FreePoolService.ALIBABA -> alibaba
        FreePoolService.MODERNMT -> modernMt
        FreePoolService.LINGVANEX -> lingvanex
        FreePoolService.REVERSO -> reverso
        FreePoolService.APERTIUM -> apertium
        FreePoolService.BILIBILI -> bilibili
    }

    /** The service matching the standalone engine preference. */
    fun serviceFor(engine: TranslationEngine): TranslationService = when (engine) {
        TranslationEngine.GOOGLE -> google
        TranslationEngine.MICROSOFT -> microsoft
        TranslationEngine.MYMEMORY -> myMemory
        TranslationEngine.DEEPLX -> deeplx
        TranslationEngine.OPENAI -> openai
        TranslationEngine.FREE_POOL -> microsoft
    }

    /** Cache key that keeps entries short without risking practical collisions. */
    private fun cacheKey(text: String): String {
        val target = prefs.translationTarget.code
        return "${prefs.translationEngine.name}|$target|${text.length}|${text.hashCode()}|${text.take(64)}"
    }

    /** Synchronous cache lookup for the UI layer. */
    fun peek(text: String): String? = cache[cacheKey(text.trim())]

    /** True when [text] already appears to be written in the configured target language. */
    private fun isTargetLanguage(text: String): Boolean {
        val target = prefs.translationTarget
        return when (target) {
            TranslationTarget.ZH_CN, TranslationTarget.ZH_TW ->
                CJK_REGEX.containsMatchIn(text)
            TranslationTarget.EN ->
                !NON_ASCII_REGEX.containsMatchIn(text)
            TranslationTarget.JA ->
                KANA_REGEX.containsMatchIn(text)
        }
    }

    /**
     * Translate a single text with the configured engine, using the cache and
     * de-duplicating identical concurrent requests.
     */
    suspend fun translate(text: String): String {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return text
        if (isTargetLanguage(trimmed)) return text

        val key = cacheKey(trimmed)
        cache[key]?.let { return it }

        val deferred = synchronized(inFlight) {
            inFlight.getOrPut(key) {
                scope.async { doTranslate(trimmed, key) }
            }
        }
        return try {
            deferred.await()
        } finally {
            if (deferred.isCompleted) inFlight.remove(key)
        }
    }

    /** Translate or return null on failure — for automatic translation paths. */
    suspend fun translateOrNull(text: String): String? = try {
        translate(text)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    private suspend fun doTranslate(text: String, key: String): String {
        val engine = prefs.translationEngine
        val translated = if (engine == TranslationEngine.FREE_POOL) {
            translateWithPool(text)
        } else {
            serviceFor(engine).translate(text, prefs.translationSource.ifBlank { "auto" }, prefs.translationTarget.code)
        }
        cache[key] = translated
        return translated
    }

    /**
     * Enabled pool services in attempt order.
     * BALANCED: weighted random shuffle (heavier services first more often).
     * SEQUENTIAL: fixed declaration order.
     */
    private fun buildCandidates(): List<Pair<FreePoolService, TranslationService>> {
        val disabled = prefs.freePoolDisabled.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        val enabled = FreePoolService.entries.filter { it.id !in disabled }
        if (enabled.isEmpty()) return emptyList()

        val ordered = when (prefs.freePoolMode) {
            FreePoolMode.BALANCED -> enabled
                .map { it to -ln(Random.nextDouble()) / it.weight.toDouble() }
                .sortedBy { it.second }
                .map { it.first }
            FreePoolMode.SEQUENTIAL -> enabled
        }
        return ordered.map { it to serviceForPool(it) }
    }

    /** Simple pool failover: skip cooling services, return the first success. */
    private suspend fun translateWithPool(text: String): String {
        val candidates = buildCandidates()
        if (candidates.isEmpty()) throw TranslationException.notConfigured("No free translation services enabled")

        val now = System.currentTimeMillis()
        val ready = candidates.filter { (pool, _) -> now >= (cooldownUntil[pool.id] ?: 0L) }
        // If everything is cooling down, probe the one expiring first anyway,
        // otherwise the pool could never recover.
        val chosen = ready.ifEmpty {
            listOf(candidates.minByOrNull { (pool, _) -> cooldownUntil[pool.id] ?: 0L }!!)
        }

        var lastError: Exception? = null
        for ((pool, service) in chosen) {
            try {
                return withTimeout(REQUEST_TIMEOUT_MS) {
                    service.translate(text, prefs.translationSource.ifBlank { "auto" }, prefs.translationTarget.code)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = if (e is TranslationException) e else TranslationException.failed(e.message ?: "failed", e)
                cooldownUntil[pool.id] = System.currentTimeMillis() + COOLDOWN_MS
            }
        }
        throw lastError ?: TranslationException.failed("All translation services failed")
    }

    /**
     * Send a short test request through [pool] to verify connectivity,
     * FluentRead-style "check connection". A failed check also puts the
     * service into a cooldown so the pool skips it right away.
     */
    suspend fun checkService(pool: FreePoolService): Boolean = try {
        withTimeout(CHECK_TIMEOUT_MS) {
            serviceForPool(pool).translate(
                "Hello! This is a short connection test.",
                prefs.translationSource.ifBlank { "auto" },
                prefs.translationTarget.code
            )
        }
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        cooldownUntil[pool.id] = System.currentTimeMillis() + 60_000
        false
    }

    /**
     * Translate [text] line by line. Table rows are separated by `\n` in the
     * extracted text, so every table row gets its own translation. A failed
     * line is skipped instead of failing the whole text.
     */
    suspend fun translateToLines(text: String): List<String> {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || isTargetLanguage(trimmed)) return emptyList()

        val out = mutableListOf<String>()
        for (line in trimmed.split('\n')) {
            val lineText = line.trim()
            if (lineText.isEmpty()) continue
            try {
                val translated = translate(lineText)
                if (translated.isNotBlank() && translated != lineText) out += translated
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Skip the failed line; the rest still get translated.
            }
        }
        return out
    }

    /**
     * Split rendered README HTML into indexed translatable units.
     * `pre` blocks (code) are intentionally left out — code must not be translated.
     * Table cells (`td`/`th`) are individual units so each cell's translation
     * lands inside that same cell. The resulting order matches the
     * `data-tr-idx` attribute inserted by [injectParagraphIndices], and matches
     * the DOM order inside the WebView.
     */
    fun extractIndexedParagraphs(html: String): List<IndexedParagraph> {
        val matches = BLOCK_OPEN_REGEX.findAll(html).toList()
        val out = mutableListOf<IndexedParagraph>()
        for (i in matches.indices) {
            val match = matches[i]
            // Tables are only bounds anchors — their cells are separate units.
            if (match.value.startsWith("<table", ignoreCase = true)) continue
            val start = match.range.last + 1
            val end = matches.getOrNull(i + 1)?.range?.first ?: html.length
            if (end <= start) continue
            val text = stripTags(html.substring(start, end))
            if (text.isNotBlank()) out += IndexedParagraph(i, text)
        }
        return out
    }

    private fun stripTags(html: String): String =
        html.replace(TAG_REGEX, "")
            .replace(WHITESPACE_REGEX, " ")
            .let { unescapeHtml(it) }
            .trim()

    /**
     * Number every block-level open tag with `data-tr-idx` so the JS side can
     * insert translations right below each source paragraph.
     */
    fun injectParagraphIndices(html: String): String {
        var i = 0
        return BLOCK_OPEN_REGEX.replace(html) { match ->
            val tag = match.value
            val withAttr = tag.replaceFirst(">", " data-tr-idx=\"$i\">")
            i++
            withAttr
        }
    }

    private fun unescapeHtml(input: String): String {
        if (!input.contains('&')) return input
        val sb = StringBuilder(input.length)
        var i = 0
        while (i < input.length) {
            val c = input[i]
            if (c != '&') {
                sb.append(c)
                i++
                continue
            }
            val semi = input.indexOf(';', i)
            if (semi == -1 || semi - i > 10) {
                sb.append(c)
                i++
                continue
            }
            val entity = input.substring(i + 1, semi)
            val decoded = when {
                entity == "amp" -> "&"
                entity == "lt" -> "<"
                entity == "gt" -> ">"
                entity == "quot" -> "\""
                entity == "nbsp" -> " "
                entity == "apos" -> "'"
                entity.startsWith("#x") || entity.startsWith("#X") ->
                    entity.substring(2).toIntOrNull(16)?.toChar()?.toString()
                entity.startsWith("#") -> entity.substring(1).toIntOrNull()?.toChar()?.toString()
                else -> null
            }
            if (decoded != null) {
                sb.append(decoded)
                i = semi + 1
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }

    companion object {
        private val SCRIPT_OR_STYLE_REGEX = Regex("(?is)<(script|style)[^>]*>.*?</\\1>")
        private val COMMENT_REGEX = Regex("(?s)<!--.*?-->")
        private val BR_REGEX = Regex("(?i)<br\\s*/?>")
        private val BLOCK_CLOSE_REGEX =
            Regex("(?i)</(p|div|h[1-6]|li|tr|blockquote|pre|section|article|figcaption|dd|dt|table|ul|ol|dl)>")
        private val TAG_REGEX = Regex("<[^>]+>")
        private val WHITESPACE_REGEX = Regex("\\s+")

        private val CJK_REGEX = Regex("[\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}\\p{IsHangul}]")
        private val KANA_REGEX = Regex("[\\p{IsHiragana}\\p{IsKatakana}]")
        private val NON_ASCII_REGEX = Regex("[^\\x00-\\x7F]")

        /** Elements that get their own translation: block paragraphs plus every table cell, so each cell's translation lands inside that cell. `table` is numbered only as a bounds anchor (no paragraph). Code blocks (`pre`) are excluded. */
        private val BLOCK_OPEN_REGEX = Regex("(?i)<(p|h[1-6]|li|blockquote|figcaption|dd|dt|table|td|th)(\\s[^>]*)?>")

        private const val REQUEST_TIMEOUT_MS = 12_000L
        private const val CHECK_TIMEOUT_MS = 10_000L
        private const val COOLDOWN_MS = 60_000L
    }
}

/**
 * A block-level paragraph of the README HTML, numbered in document order.
 * The same numbering is applied to the DOM inside the WebView via the
 * `data-tr-idx` attribute, so translations can be matched back to nodes.
 */
data class IndexedParagraph(
    val idx: Int,
    val text: String
)
