package dev.materii.gloom.api.translation

import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
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
import java.util.UUID

/**
 * Tencent Transmart (interactive translation) anonymous web endpoint.
 * POST https://transmart.qq.com/api/imt
 */
class TransmartTranslateService(
    private val client: HttpClient,
    private val json: Json
): TranslationService {

    override val id = "transmart"

    override suspend fun translate(text: String, source: String, target: String): String {
        val body = try {
            client.post("https://transmart.qq.com/api/imt") {
                contentType(ContentType.Application.Json)
                setBody(
                    buildJsonObject {
                        put("header", buildJsonObject {
                            put("fn", "auto_translation")
                            put(
                                "client_key",
                                "browser-chrome-110.0.0-Mac OS-${UUID.randomUUID()}-${System.currentTimeMillis()}"
                            )
                        })
                        put("type", "plain")
                        put("model_category", "normal")
                        put("source", buildJsonObject {
                            put("text_list", json.toJsonArray(listOf(text)))
                            put("lang", mapLang(source, isTarget = false))
                        })
                        put("target", buildJsonObject {
                            put("lang", mapLang(target, isTarget = true))
                        })
                    }
                )
                timeout { requestTimeoutMillis = TIMEOUT }
            }.bodyAsText()
        } catch (e: TranslationException) {
            throw e
        } catch (e: Exception) {
            throw TranslationException.failed("Transmart request failed: ${e.message}", e)
        }

        val root = parseObject(json, body, "Transmart")
        val retCode = root["header"]?.jsonObject?.get("ret_code")?.jsonPrimitive?.content
        if (retCode != "succ") throw TranslationException.failed("Transmart error: $retCode")
        val translations = root["auto_translation"]?.jsonArray
            ?.mapNotNull { it.jsonPrimitive.content }?.joinToString("")?.trim()
        if (translations.isNullOrBlank()) throw TranslationException.empty()
        return translations
    }

    companion object {

        private const val TIMEOUT = 15_000L

        fun mapLang(code: String, isTarget: Boolean): String {
            val normalized = code.trim().lowercase().replace('_', '-')
            if (isTarget && (normalized == "auto" || normalized.isBlank())) {
                throw TranslationException.failed("Transmart requires a target language")
            }
            return when {
                normalized == "zh-cn" || normalized == "zh" || normalized == "zh-hans" || normalized == "zh-chs" ->
                    if (isTarget && normalized == "zh-cn") "zh" else "zh"
                normalized == "zh-tw" || normalized == "zh-hant" || normalized == "zh-cht" ->
                    if (isTarget) "zh-tw" else "zh"
                else -> normalized
            }
        }
    }
}

/**
 * Volcengine (ByteDance) anonymous web translation endpoint.
 * POST https://translate.volcengine.com/crx/translate/v1/
 */
class VolcengineTranslateService(
    private val client: HttpClient,
    private val json: Json
): TranslationService {

    override val id = "volcengine"

    override suspend fun translate(text: String, source: String, target: String): String {
        val body = try {
            client.post("https://translate.volcengine.com/crx/translate/v1/") {
                contentType(ContentType.Application.Json)
                setBody(
                    buildJsonObject {
                        put("source_language", TransmartTranslateService.mapLang(source, isTarget = false))
                        put("target_language", mapTarget(target))
                        put("text", text)
                    }
                )
                timeout { requestTimeoutMillis = TIMEOUT }
            }.bodyAsText()
        } catch (e: TranslationException) {
            throw e
        } catch (e: Exception) {
            throw TranslationException.failed("Volcengine request failed: ${e.message}", e)
        }

        val root = parseObject(json, body, "Volcengine")
        val status = root["base_resp"]?.jsonObject?.get("status_code")?.jsonPrimitive?.content
        if (status != "0") throw TranslationException.failed("Volcengine error: $status")
        val translated = root["translation"]?.jsonPrimitive?.content?.trim()
        if (translated.isNullOrBlank()) throw TranslationException.empty()
        return translated
    }

    private fun mapTarget(code: String): String {
        val normalized = code.trim().lowercase()
        return when {
            normalized == "zh-tw" || normalized == "zh-hant" || normalized == "zh-cht" -> "zh-Hant"
            normalized.startsWith("zh") -> "zh"
            else -> normalized
        }
    }

    companion object {
        private const val TIMEOUT = 15_000L
    }
}

/**
 * Yandex anonymous web translation endpoint (the one used by the Android app).
 * GET https://translate.yandex.net/api/v1/tr.json/translate?id=...&srv=android&...
 * Traditional Chinese targets are not supported and fail over to the next service.
 */
class YandexTranslateService(
    private val client: HttpClient,
    private val json: Json
): TranslationService {

    override val id = "yandex"

    override suspend fun translate(text: String, source: String, target: String): String {
        val targetLang = normalize(target)
        if (targetLang == "zh-TW" || targetLang == "zh-Hant") {
            throw TranslationException.failed("Yandex does not support Traditional Chinese")
        }

        val body = try {
            client.get("https://translate.yandex.net/api/v1/tr.json/translate") {
                parameter("id", "${UUID.randomUUID().toString().replace("-", "")}-0-0")
                parameter("srv", "android")
                parameter("target_lang", targetLang)
                parameter("text", text)
                val from = source.trim().lowercase()
                if (from.isNotBlank() && from != "auto") parameter("source_lang", from)
                timeout { requestTimeoutMillis = TIMEOUT }
            }.bodyAsText()
        } catch (e: TranslationException) {
            throw e
        } catch (e: Exception) {
            throw TranslationException.failed("Yandex request failed: ${e.message}", e)
        }

        val root = parseObject(json, body, "Yandex")
        val code = root["code"]?.jsonPrimitive?.content?.toIntOrNull()
        if (code != 200) throw TranslationException.failed("Yandex error code $code")
        val translated = root["text"]?.jsonArray
            ?.mapNotNull { it.jsonPrimitive.content }?.joinToString("")?.trim()
        if (translated.isNullOrBlank()) throw TranslationException.empty()
        return translated
    }

    private fun normalize(code: String): String {
        val normalized = code.trim().replace('_', '-').lowercase()
        return when {
            normalized == "zh-cn" || normalized == "zh" || normalized == "zh-hans" || normalized == "zh-chs" -> "zh"
            normalized == "zh-tw" || normalized == "zh-hant" || normalized == "zh-cht" -> "zh-TW"
            else -> normalized
        }
    }

    companion object {
        private const val TIMEOUT = 15_000L
    }
}

internal fun parseObject(json: Json, body: String, service: String): kotlinx.serialization.json.JsonObject =
    try {
        json.parseToJsonElement(body).jsonObject
    } catch (e: Exception) {
        throw TranslationException.failed("$service returned invalid data", e)
    }

internal fun Json.toJsonArray(values: List<String>): kotlinx.serialization.json.JsonArray =
    kotlinx.serialization.json.buildJsonArray {
        values.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) }
    }
