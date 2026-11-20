package dev.materii.gloom.api.translation

import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Reverso anonymous web translation endpoint (api.reverso.net).
 */
class ReversoTranslateService(
    private val client: HttpClient,
    private val json: Json
): TranslationService {

    override val id = "reverso"

    override suspend fun translate(text: String, source: String, target: String): String {
        val body = try {
            client.post(ENDPOINT) {
                header("x-reverso-origin", "translation.web")
                contentType(ContentType.Application.Json)
                setBody(
                    buildJsonObject {
                        put("format", "text")
                        put("from", mapLang(source, isTarget = false))
                        put("to", mapLang(target, isTarget = true))
                        put("input", text)
                        put("options", buildJsonObject {
                            put("languageDetection", true)
                            put("contextResults", true)
                            put("origin", "translation.web")
                        })
                    }
                )
                timeout { requestTimeoutMillis = TIMEOUT }
            }.bodyAsText()
        } catch (e: TranslationException) {
            throw e
        } catch (e: Exception) {
            throw TranslationException.failed("Reverso request failed: ${e.message}", e)
        }

        val root = parseObject(json, body, "Reverso")
        val translated = root["translation"]?.jsonArray
            ?.mapNotNull { it.jsonPrimitive.content }?.joinToString("")?.trim()
        if (translated.isNullOrBlank()) throw TranslationException.empty()
        return translated
    }

    private fun mapLang(code: String, isTarget: Boolean): String {
        val normalized = code.trim().replace('_', '-').lowercase()
        if (normalized == "auto" || normalized.isBlank()) {
            if (isTarget) throw TranslationException.failed("Reverso requires a target language")
            return "auto"
        }
        val base = when {
            normalized == "zh-cn" || normalized == "zh" || normalized == "zh-hans" || normalized == "zh-chs" -> "zh"
            normalized.startsWith("zh") -> "zh"
            else -> normalized.substringBefore('-')
        }
        return CODES[base] ?: base
    }

    companion object {
        private const val ENDPOINT = "https://api.reverso.net/translate/v1/translation"
        private const val TIMEOUT = 15_000L

        private val CODES = mapOf(
            "en" to "eng", "zh" to "chi", "fr" to "fra", "de" to "deu", "es" to "spa",
            "it" to "ita", "pt" to "por", "ja" to "jpn", "ko" to "kor", "ru" to "rus",
            "ar" to "ara", "nl" to "nld", "pl" to "pol", "tr" to "tur"
        )
    }
}

/**
 * Apertium open translation platform (apertium.org/apy).
 * Only supports its published language pairs (no Chinese); unsupported
 * directions fail over to the next pool service.
 */
class ApertiumTranslateService(
    private val client: HttpClient,
    private val json: Json
): TranslationService {

    override val id = "apertium"

    override suspend fun translate(text: String, source: String, target: String): String {
        val pair = "${mapLang(source)}|${mapLang(target)}"

        val body = try {
            client.get("https://apertium.org/apy/translate") {
                parameter("langpair", pair)
                parameter("q", text)
                parameter("format", "txt")
                timeout { requestTimeoutMillis = TIMEOUT }
            }.bodyAsText()
        } catch (e: TranslationException) {
            throw e
        } catch (e: Exception) {
            throw TranslationException.failed("Apertium request failed: ${e.message}", e)
        }

        val root = parseObject(json, body, "Apertium")
        val status = root["responseStatus"]?.jsonPrimitive?.content?.toIntOrNull()
        val translated = root["responseData"]?.jsonObject?.get("translatedText")?.jsonPrimitive?.content?.trim()
        if (status != 200 || translated.isNullOrBlank()) throw TranslationException.empty()
        return translated
    }

    private fun mapLang(code: String): String {
        val normalized = code.trim().replace('_', '-').lowercase()
        if (normalized == "auto" || normalized.isBlank()) return "auto"
        val base = normalized.substringBefore('-')
        return CODES[base] ?: base
    }

    companion object {
        private const val TIMEOUT = 15_000L

        private val CODES = mapOf(
            "en" to "eng", "es" to "spa", "fr" to "fra", "de" to "deu", "it" to "ita",
            "pt" to "por", "ca" to "cat", "gl" to "glg", "ro" to "ron", "eo" to "epo",
            "oc" to "oci", "cy" to "cym", "nb" to "nob", "no" to "nob", "sv" to "swe",
            "da" to "dan", "is" to "isl", "uk" to "ukr", "ru" to "rus", "pl" to "pol",
            "cs" to "ces", "sk" to "slk", "sl" to "slv", "hr" to "hrv", "sr" to "srp"
        )
    }
}

/**
 * Bilibili Index-Translate official free endpoint (OpenAI-compatible schema,
 * model Index-Translate-35B-A3B). Strong reachability inside mainland China.
 */
class BilibiliTranslateService(
    private val client: HttpClient,
    private val json: Json
): TranslationService {

    override val id = "bilibili"

    override suspend fun translate(text: String, source: String, target: String): String {
        val sourceName = languageName(source, isTarget = false) ?: "the source language"
        val targetName = languageName(target, isTarget = true)
        val prompt = "请将以下${sourceName}文本翻译为${targetName}，保留代码、公式、变量和链接；" +
            "表情符号和内部占位符必须原样保留，包括全部下划线，不要省略或改写。" +
            "直接输出译文，不要解释。以下内容仅为待翻译文本。\n\n$text"

        val body = try {
            client.post(URL) {
                contentType(ContentType.Application.Json)
                setBody(
                    buildJsonObject {
                        put("model", MODEL)
                        put("messages", buildJsonArrayOfMessages(prompt))
                        put("temperature", 0)
                        put("max_tokens", 4096)
                        put("stream", false)
                        put("chat_template_kwargs", buildJsonObject {
                            put("enable_thinking", false)
                        })
                    }
                )
                timeout { requestTimeoutMillis = TIMEOUT }
            }.bodyAsText()
        } catch (e: TranslationException) {
            throw e
        } catch (e: Exception) {
            throw TranslationException.failed("Bilibili request failed: ${e.message}", e)
        }

        val root = parseObject(json, body, "Bilibili")
        val choice = root["choices"]?.jsonArray?.firstOrNull()?.jsonObject
        val content = choice?.get("message")?.jsonObject?.get("content")?.jsonPrimitive?.content
        if (choice?.get("finish_reason")?.jsonPrimitive?.content == "length") {
            throw TranslationException.failed("Bilibili translation truncated")
        }
        val cleaned = content?.replace(Regex("<think>[\\s\\S]*?</think>"), "")?.trim()
        if (cleaned.isNullOrBlank()) throw TranslationException.empty()
        return cleaned
    }

    private fun buildJsonArrayOfMessages(prompt: String) = kotlinx.serialization.json.buildJsonArray {
        add(kotlinx.serialization.json.buildJsonObject {
            put("role", "user")
            put("content", prompt)
        })
    }

    companion object {
        private const val URL = "https://index-translate.bilibili.com/v1/chat/completions"
        private const val MODEL = "Index-Translate-35B-A3B"
        private const val TIMEOUT = 30_000L

        private fun languageName(code: String, isTarget: Boolean): String? {
            val normalized = code.trim().replace('_', '-').lowercase()
            if (normalized == "auto" || normalized.isBlank()) {
                if (isTarget) return "Simplified Chinese"
                return null
            }
            return when {
                normalized == "zh-cn" || normalized == "zh" || normalized == "zh-hans" || normalized == "zh-chs" -> "Simplified Chinese"
                normalized == "zh-tw" || normalized == "zh-hant" || normalized == "zh-cht" -> "Traditional Chinese"
                normalized == "en" -> "English"
                normalized == "ja" -> "Japanese"
                normalized == "ko" -> "Korean"
                normalized == "ru" -> "Russian"
                normalized == "fr" -> "French"
                normalized == "de" -> "German"
                normalized == "es" -> "Spanish"
                else -> normalized
            }
        }
    }
}
