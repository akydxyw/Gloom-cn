package dev.materii.gloom.api.translation

import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Alibaba Translate anonymous web endpoint (translate.alibaba.com).
 * Fetches a CSRF token per request, then submits a form-encoded translation query.
 */
class AlibabaTranslateService(
    private val client: HttpClient,
    private val json: Json
): TranslationService {

    override val id = "alibaba"

    override suspend fun translate(text: String, source: String, target: String): String {
        val sourceLang = mapLang(source, isTarget = false)
        val targetLang = mapLang(target, isTarget = true)
        if (sourceLang.isNotBlank() && sourceLang == targetLang) return text

        val csrf = try {
            client.get("$BASE/csrftoken") {
                timeout { requestTimeoutMillis = TIMEOUT }
            }.bodyAsText()
        } catch (e: Exception) {
            throw TranslationException.failed("Alibaba CSRF request failed: ${e.message}", e)
        }
        val csrfRoot = parseObject(json, csrf, "Alibaba CSRF")
        val token = csrfRoot["token"]?.jsonPrimitive?.content
        if (token.isNullOrBlank() || token.length > 128) {
            throw TranslationException.failed("Alibaba CSRF token invalid")
        }

        val form = listOf(
            "query" to text,
            "srcLang" to sourceLang,
            "tgtLang" to targetLang,
            "domain" to "general",
            "_csrf" to token
        ).joinToString("&") { (k, v) ->
            "${java.net.URLEncoder.encode(k, "UTF-8")}=${java.net.URLEncoder.encode(v, "UTF-8")}"
        }

        val body = try {
            client.post("$BASE/text") {
                header("X-XSRF-TOKEN_PROPERTY_ITEM", token)
                contentType(ContentType.Application.FormUrlEncoded)
                setBody(form)
                timeout { requestTimeoutMillis = TIMEOUT }
            }.bodyAsText()
        } catch (e: TranslationException) {
            throw e
        } catch (e: Exception) {
            throw TranslationException.failed("Alibaba request failed: ${e.message}", e)
        }

        val root = parseObject(json, body, "Alibaba")
        val success = root["success"]?.jsonPrimitive?.content?.toBooleanStrictOrNull()
        if (success != true) throw TranslationException.failed("Alibaba business error")
        val translated = root["data"]?.jsonObject?.get("translateText")?.jsonPrimitive?.content?.trim()
        if (translated.isNullOrBlank()) throw TranslationException.empty()
        return translated
    }

    private fun mapLang(code: String, isTarget: Boolean): String {
        val normalized = code.trim().lowercase().replace('_', '-')
        if (normalized == "auto" || normalized.isBlank()) {
            if (isTarget) throw TranslationException.failed("Alibaba requires a target language")
            return ""
        }
        return when {
            normalized == "zh-cn" || normalized == "zh" || normalized == "zh-hans" || normalized == "zh-chs" -> "zh"
            normalized == "zh-tw" || normalized == "zh-hant" || normalized == "zh-cht" -> "zh-tw"
            else -> normalized
        }
    }

    companion object {
        private const val BASE = "https://translate.alibaba.com/api/translate"
        private const val TIMEOUT = 15_000L
    }
}

/**
 * ModernMT anonymous web endpoint (webapi.modernmt.com) with the public
 * web-page verification hash — not a user API key.
 */
class ModernMtTranslateService(
    private val client: HttpClient,
    private val json: Json
): TranslationService {

    override val id = "modernmt"

    override suspend fun translate(text: String, source: String, target: String): String {
        val sourceLang = mapLang(source, isTarget = false)
        val targetLang = mapLang(target, isTarget = true)
        if (sourceLang.isNotBlank() && sourceLang == targetLang) return text

        val ts = System.currentTimeMillis().toString()
        val verify = md5Hex("webkey_E3sTuMjpP8Jez49GcYpDVH7r#$ts#${text.trim()}")

        val body = try {
            client.post(URL) {
                header("X-HTTP-Method-Override", "GET")
                contentType(ContentType.Application.Json)
                setBody(
                    buildJsonObject {
                        put("q", text)
                        put("source", sourceLang)
                        put("target", targetLang)
                        put("ts", ts)
                        put("verify", verify)
                        put("hints", "")
                        put("multiline", "true")
                    }
                )
                timeout { requestTimeoutMillis = TIMEOUT }
            }.bodyAsText()
        } catch (e: TranslationException) {
            throw e
        } catch (e: Exception) {
            throw TranslationException.failed("ModernMT request failed: ${e.message}", e)
        }

        val root = parseObject(json, body, "ModernMT")
        val status = root["status"]?.jsonPrimitive?.content?.toIntOrNull()
        if (status != 200) throw TranslationException.failed("ModernMT error status $status")
        val translated = root["data"]?.jsonObject?.get("translation")?.jsonPrimitive?.content?.trim()
        if (translated.isNullOrBlank()) throw TranslationException.empty()
        return translated
    }

    private fun mapLang(code: String, isTarget: Boolean): String {
        val normalized = code.trim().replace('_', '-').lowercase()
        if (normalized == "auto" || normalized.isBlank()) {
            if (isTarget) throw TranslationException.failed("ModernMT requires a target language")
            return ""
        }
        return when {
            normalized == "zh-cn" || normalized == "zh" || normalized == "zh-hans" || normalized == "zh-chs" -> "zh-CN"
            normalized == "zh-tw" || normalized == "zh-hant" || normalized == "zh-cht" -> "zh-TW"
            else -> normalized
        }
    }

    companion object {
        private const val URL = "https://webapi.modernmt.com/translate"
        private const val TIMEOUT = 15_000L
    }
}

/**
 * Lingvanex anonymous web endpoint. The public website ships a demo bearer
 * token which is fetched from the translation page HTML.
 */
class LingvanexTranslateService(
    private val client: HttpClient,
    private val json: Json
): TranslationService {

    override val id = "lingvanex"

    override suspend fun translate(text: String, source: String, target: String): String {
        val sourceLang = mapLang(source, isTarget = false)
        val targetLang = mapLang(target, isTarget = true)

        val token = fetchBearerToken()

        val body = try {
            client.post(API_URL) {
                header("Authorization", token)
                contentType(ContentType.Application.Json)
                setBody(
                    buildJsonObject {
                        if (sourceLang.isNotBlank()) put("from", sourceLang)
                        put("to", targetLang)
                        put("text", text)
                        put("platform", "dp")
                    }
                )
                timeout { requestTimeoutMillis = TIMEOUT }
            }.bodyAsText()
        } catch (e: TranslationException) {
            throw e
        } catch (e: Exception) {
            throw TranslationException.failed("Lingvanex request failed: ${e.message}", e)
        }

        val root = parseObject(json, body, "Lingvanex")
        if (root.containsKey("err")) throw TranslationException.failed("Lingvanex business error")
        val translated = root["result"]?.jsonPrimitive?.content?.trim()
        if (translated.isNullOrBlank()) throw TranslationException.empty()
        return translated
    }

    private suspend fun fetchBearerToken(): String = try {
        val html = client.get(HOME_URL) {
            timeout { requestTimeoutMillis = TIMEOUT }
        }.bodyAsText()
        Regex("const\\s+API_BEARER_TOKEN\\s*=\\s*\"(Bearer [A-Za-z0-9_-]{1,256})\"")
            .find(html)?.groupValues?.get(1)
            ?: throw TranslationException.failed("Lingvanex token not found")
    } catch (e: TranslationException) {
        throw e
    } catch (e: Exception) {
        throw TranslationException.failed("Lingvanex page fetch failed: ${e.message}", e)
    }

    private fun mapLang(code: String, isTarget: Boolean): String {
        val normalized = code.trim().replace('_', '-').lowercase()
        if (normalized == "auto" || normalized.isBlank()) {
            if (isTarget) throw TranslationException.failed("Lingvanex requires a target language")
            return ""
        }
        return when (normalized) {
            "en" -> "en_GB"
            "zh-cn", "zh", "zh-hans", "zh-chs" -> "zh-Hans_CN"
            "zh-tw", "zh-hant", "zh-cht" -> "zh-Hant_TW"
            "ja" -> "ja_JP"
            "ko" -> "ko_KR"
            "de" -> "de_DE"
            "fr" -> "fr_FR"
            "es" -> "es_ES"
            "ru" -> "ru_RU"
            "pt" -> "pt_PT"
            "it" -> "it_IT"
            "ar" -> "ar_SA"
            "hi" -> "hi_IN"
            "tr" -> "tr_TR"
            "uk" -> "uk_UA"
            "vi" -> "vi_VN"
            "nl" -> "nl_NL"
            else -> throw TranslationException.failed("Lingvanex unsupported language: $normalized")
        }
    }

    companion object {
        private const val HOME_URL = "https://lingvanex.com/en/translate/"
        private const val API_URL = "https://api-b2b.backenster.com/b1/api/v3/translate"
        private const val TIMEOUT = 15_000L
    }
}
