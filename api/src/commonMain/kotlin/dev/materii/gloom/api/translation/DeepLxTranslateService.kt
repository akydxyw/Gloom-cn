package dev.materii.gloom.api.translation

import dev.materii.gloom.domain.manager.enums.TranslationTarget
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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * DeepLX translation. Uses the public endpoint when [endpoint] is blank,
 * or a custom self-hosted instance otherwise. An optional bearer token is supported.
 */
class DeepLxTranslateService(
    private val client: HttpClient,
    private val json: Json,
    private val endpoint: () -> String,
    private val token: () -> String
): TranslationService {

    override val id = "deeplx"

    override suspend fun translate(text: String, source: String, target: String): String {
        val url = endpoint().trim().ifBlank { PUBLIC_ENDPOINT }
        val body = try {
            client.post(url) {
                val t = token().trim()
                if (t.isNotEmpty()) header(HttpHeaders.Authorization, "Bearer $t")
                contentType(ContentType.Application.Json)
                setBody(
                    buildJsonObject {
                        put("text", text)
                        put("source_lang", "auto")
                        put("target_lang", deeplTarget(target))
                    }
                )
                timeout { requestTimeoutMillis = REQUEST_TIMEOUT }
            }.bodyAsText()
        } catch (e: TranslationException) {
            throw e
        } catch (e: Exception) {
            throw TranslationException.failed("DeepLX request failed: ${e.message}", e)
        }

        val root = try {
            json.parseToJsonElement(body).jsonObject
        } catch (e: Exception) {
            throw TranslationException.failed("DeepLX returned invalid data", e)
        }

        val code = root["code"]?.jsonPrimitive?.content?.toIntOrNull() ?: -1
        if (code != 200) {
            throw TranslationException.failed("DeepLX error code $code")
        }

        val translated = root["data"]?.jsonPrimitive?.content?.trim()
        if (translated.isNullOrBlank()) throw TranslationException.empty()
        return translated
    }

    private fun deeplTarget(target: String): String =
        TranslationTarget.entries.firstOrNull { it.code == target }?.deeplCode
            ?: target.uppercase()

    companion object {
        private const val PUBLIC_ENDPOINT = "https://api.deeplx.org/translate"
        private const val REQUEST_TIMEOUT = 20_000L
    }
}
