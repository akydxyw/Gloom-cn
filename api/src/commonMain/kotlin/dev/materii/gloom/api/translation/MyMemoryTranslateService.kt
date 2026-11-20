package dev.materii.gloom.api.translation

import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.intOrNull

/**
 * MyMemory public translation API (`api.mymemory.translated.net`).
 * Free without a key; an optional contact e-mail raises the daily quota.
 * Requests are limited to ~500 bytes, so long texts are chunked here.
 */
class MyMemoryTranslateService(
    private val client: HttpClient,
    private val json: Json,
    private val email: () -> String
): TranslationService {

    override val id = "mymemory"

    override suspend fun translate(text: String, source: String, target: String): String {
        val chunks = splitByBytes(text, MAX_CHUNK_BYTES)
        val translated = chunks.mapIndexed { index, chunk ->
            request(chunk, source, target, isFirstChunk = index == 0 && chunks.size == 1)
        }
        return translated.joinToString(" ").trim()
    }

    private suspend fun request(
        text: String,
        source: String,
        target: String,
        isFirstChunk: Boolean
    ): String {
        val body = try {
            client.get("https://api.mymemory.translated.net/get") {
                parameter("q", text)
                parameter(
                    "langpair",
                    "${source.ifBlank { "autodetect" }}|$target"
                )
                val mail = email().trim()
                if (mail.isNotEmpty()) parameter("de", mail)
                timeout { requestTimeoutMillis = REQUEST_TIMEOUT }
            }.bodyAsText()
        } catch (e: TranslationException) {
            throw e
        } catch (e: Exception) {
            throw TranslationException.failed("MyMemory request failed: ${e.message}", e)
        }

        val root = try {
            json.parseToJsonElement(body).jsonObject
        } catch (e: Exception) {
            throw TranslationException.failed("MyMemory returned invalid data", e)
        }

        val status = root["responseStatus"]?.jsonPrimitive?.intOrNull ?: -1
        if (status != 200) {
            val details = root["responseDetails"]?.jsonPrimitive?.content
            throw TranslationException.failed("MyMemory error: ${details ?: status}")
        }

        val translated = root["responseData"]
            ?.jsonObject?.get("translatedText")?.jsonPrimitive?.content?.trim()
        if (translated.isNullOrBlank()) throw TranslationException.empty()
        // MyMemory returns the literal string "QUERY LENGTH LIMIT EXCEEDED..." etc. on abuse.
        if (translated.endsWith("EXCEEDED") || translated.startsWith("MYMEMORY WARNING")) {
            throw TranslationException.failed("MyMemory quota exceeded")
        }
        return translated
    }

    private fun splitByBytes(text: String, maxBytes: Int): List<String> {
        if (text.utf8Size() <= maxBytes) return listOf(text)

        val chunks = mutableListOf<String>()
        val current = StringBuilder()
        var currentBytes = 0
        for (word in text.split(' ')) {
            val wordBytes = word.utf8Size() + if (current.isEmpty()) 0 else 1
            if (current.isNotEmpty() && currentBytes + wordBytes > maxBytes) {
                chunks += current.toString()
                current.clear()
                currentBytes = 0
            }
            if (current.isNotEmpty()) {
                current.append(' ')
                currentBytes += 1
            }
            current.append(word)
            currentBytes += word.utf8Size()
        }
        if (current.isNotEmpty()) chunks += current.toString()
        return chunks
    }

    private fun String.utf8Size(): Int = encodeToByteArray().size

    companion object {
        private const val REQUEST_TIMEOUT = 15_000L
        private const val MAX_CHUNK_BYTES = 450
    }
}
