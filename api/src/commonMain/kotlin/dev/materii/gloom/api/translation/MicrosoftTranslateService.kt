package dev.materii.gloom.api.translation

import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.add
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Free Microsoft translation via the Edge web endpoint
 * `https://edge.microsoft.com/translate/translatetext` — no token required.
 * The endpoint always runs an HTML tag aligner, so plain text must be escaped first
 * and the response decoded back.
 *
 * Supports true batch translation: one request for many texts.
 */
class MicrosoftTranslateService(
    private val client: HttpClient,
    private val json: Json
): TranslationService, BatchTranslationService {

    override val id = "microsoft"

    override suspend fun translate(text: String, source: String, target: String): String =
        translateBatch(listOf(text), source, target).first()

    override suspend fun translateBatch(
        texts: List<String>,
        source: String,
        target: String
    ): List<String> {
        if (texts.isEmpty()) return emptyList()

        // Split into groups that respect reasonable request sizes.
        val groups = mutableListOf<MutableList<String>>()
        var current = mutableListOf<String>()
        var currentChars = 0
        for (text in texts) {
            val len = text.length.coerceAtLeast(1)
            if (current.isNotEmpty() && (current.size >= MAX_TEXTS || currentChars + len > MAX_CHARS)) {
                groups += current
                current = mutableListOf()
                currentChars = 0
            }
            current += text
            currentChars += len
        }
        if (current.isNotEmpty()) groups += current

        val out = mutableListOf<String>()
        for (group in groups) {
            out += request(group, source, target)
        }
        return out
    }

    private suspend fun request(
        texts: List<String>,
        source: String,
        target: String
    ): List<String> {
        val body = try {
            client.post(TRANSLATE_URL) {
                url {
                    parameters.append("from", if (source.isBlank() || source == "auto") "" else source)
                    parameters.append("to", target)
                    parameters.append("isEnterpriseClient", "false")
                }
                contentType(ContentType.Application.Json)
                setBody(buildJsonArray {
                    texts.forEach { add(kotlinx.serialization.json.JsonPrimitive(escapeHtml(it))) }
                })
                timeout { requestTimeoutMillis = REQUEST_TIMEOUT }
            }.bodyAsText()
        } catch (e: TranslationException) {
            throw e
        } catch (e: Exception) {
            throw TranslationException.failed("Microsoft request failed: ${e.message}", e)
        }

        val result = try {
            json.parseToJsonElement(body).jsonArray
        } catch (e: Exception) {
            throw TranslationException.failed("Microsoft returned invalid data", e)
        }

        if (result.size != texts.size) {
            throw TranslationException.failed("Microsoft batch size mismatch")
        }

        return result.map { item ->
            val translations = item.jsonObject["translations"]?.jsonArray
            val text = translations?.firstOrNull()?.jsonObject?.get("text")?.jsonPrimitive?.content
            if (text.isNullOrBlank()) throw TranslationException.empty()
            decodeHtml(text).trim()
        }
    }

    companion object {
        private const val TRANSLATE_URL = "https://edge.microsoft.com/translate/translatetext"
        private const val REQUEST_TIMEOUT = 15_000L
        private const val MAX_TEXTS = 50
        private const val MAX_CHARS = 8_000

        private fun escapeHtml(text: String): String = text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")

        private fun decodeHtml(text: String): String = text
            .replace(Regex("&#(?:0*39);|&#x0*27;"), "'")
            .replace("&quot;", "\"")
            .replace("&gt;", ">")
            .replace("&lt;", "<")
            .replace("&amp;", "&")
    }
}
