package dev.materii.gloom.api.translation

import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Custom translation service backed by any OpenAI-compatible Chat Completions endpoint
 * (OpenAI, DeepSeek, Ollama, one-api, new-api, ...).
 *
 * Endpoint normalization follows common conventions:
 *  - `https://host`            -> `https://host/v1/chat/completions`
 *  - `https://host/v1`         -> `https://host/v1/chat/completions`
 *  - a full path is used as-is
 */
class OpenAITranslateService(
    private val client: HttpClient,
    private val json: Json,
    private val endpoint: () -> String,
    private val apiKey: () -> String,
    private val model: () -> String
): TranslationService {

    override val id = "openai"

    override suspend fun translate(text: String, source: String, target: String): String {
        val base = endpoint().trim()
        if (base.isBlank()) throw TranslationException.notConfigured("Custom AI endpoint is not set")
        if (model().isBlank()) throw TranslationException.notConfigured("Custom AI model is not set")

        val body = try {
            client.post(normalizeEndpoint(base)) {
                // Many local/served instances (Ollama, one-api, LiteLLM...) need no key;
                // only send the header when a key was actually configured.
                val key = apiKey().trim()
                if (key.isNotEmpty()) header(HttpHeaders.Authorization, "Bearer $key")
                // Local model inference can be slow — give it a generous budget
                // that overrides the shorter client-wide default.
                timeout {
                    connectTimeoutMillis = 8_000
                    requestTimeoutMillis = REQUEST_TIMEOUT
                }
                contentType(ContentType.Application.Json)
                setBody(
                    buildJsonObject {
                        put("model", model().trim())
                        put("stream", false)
                        put("temperature", 0.2)
                        putJsonArray("messages") {
                            add(
                                buildJsonObject {
                                    put("role", "system")
                                    put("content", systemPrompt(source, target))
                                }
                            )
                            add(
                                buildJsonObject {
                                    put("role", "user")
                                    put("content", text)
                                }
                            )
                        }
                    }
                )
                timeout { requestTimeoutMillis = REQUEST_TIMEOUT }
            }.bodyAsText()
        } catch (e: TranslationException) {
            throw e
        } catch (e: Exception) {
            throw TranslationException.failed("AI request failed: ${e.message}", e)
        }

        val root = try {
            json.parseToJsonElement(body).jsonObject
        } catch (e: Exception) {
            throw TranslationException.failed("AI service returned invalid data", e)
        }

        root["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content?.let {
            throw TranslationException.failed("AI service error: $it")
        }

        val translated = root["choices"]
            ?.jsonArray?.firstOrNull()
            ?.jsonObject?.get("message")
            ?.jsonObject?.get("content")
            ?.jsonPrimitive?.content?.trim()
        if (translated.isNullOrBlank()) throw TranslationException.empty()
        return translated
    }

    private fun systemPrompt(source: String, target: String): String {
        val from = if (source.isBlank() || source.equals("auto", true)) {
            "the source language (auto-detect)"
        } else {
            source
        }
        return "You are a professional translation engine. " +
            "Translate the user's text from $from to $target. " +
            "Output ONLY the translation with no explanations, notes or surrounding quotes. " +
            "Preserve line breaks, markdown structure, inline code and code blocks. " +
            "Keep proper nouns, URLs and code identifiers unchanged. " +
            "If the text is already in $target, return it unchanged."
    }

    companion object {

        fun normalizeEndpoint(base: String): String {
            var url = base.trim().trimEnd('/')
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                url = "https://$url"
            }
            // Extract the path (after host, before query/fragment) without URLBuilder APIs.
            val withoutScheme = url.substringAfter("://")
            val rawPath = withoutScheme
                .substringAfter('/', "")
                .substringBefore('?')
                .substringBefore('#')
            val segments = rawPath.split('/').filter { it.isNotBlank() }
            return when {
                segments.isEmpty() -> "$url/v1/chat/completions"
                segments.size == 1 && segments[0] == "v1" -> "$url/chat/completions"
                else -> url
            }
        }

        /** Local model inference can be slow; give it a generous budget. */
        private const val REQUEST_TIMEOUT = 90_000L
    }
}
