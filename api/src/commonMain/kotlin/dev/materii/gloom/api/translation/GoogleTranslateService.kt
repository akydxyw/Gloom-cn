package dev.materii.gloom.api.translation

import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/**
 * Free Google Translate web endpoint (`translate.googleapis.com/translate_a/single`).
 * No API key required. Well suited for short texts; long texts are split by the caller.
 */
class GoogleTranslateService(
    private val client: HttpClient,
    private val json: Json
): TranslationService {

    override val id = "google"

    override suspend fun translate(text: String, source: String, target: String): String {
        val body = try {
            client.get("https://translate.googleapis.com/translate_a/single") {
                parameter("client", "gtx")
                parameter("sl", source.ifBlank { "auto" })
                parameter("tl", target)
                parameter("dt", "t")
                parameter("q", text)
                timeout { requestTimeoutMillis = REQUEST_TIMEOUT }
            }.bodyAsText()
        } catch (e: TranslationException) {
            throw e
        } catch (e: Exception) {
            throw TranslationException.failed("Google request failed: ${e.message}", e)
        }

        val root = try {
            json.parseToJsonElement(body).jsonArray
        } catch (e: Exception) {
            throw TranslationException.failed("Google returned invalid data", e)
        }

        val sentences = root.firstOrNull()?.jsonArray
            ?: throw TranslationException.failed("Google returned no sentences")
        val translated = sentences.joinToString("") { entry ->
            entry.jsonArray.firstOrNull()?.jsonPrimitive?.content ?: ""
        }.trim()

        if (translated.isBlank()) throw TranslationException.empty()

        // Skip when Google detected the text is already in the target language.
        val detected = root.getOrNull(2)?.jsonPrimitive?.content
        if (detected != null && detected.substringBefore('-') == target.substringBefore('-')) {
            return text
        }

        return translated
    }

    companion object {
        private const val REQUEST_TIMEOUT = 15_000L
    }
}
