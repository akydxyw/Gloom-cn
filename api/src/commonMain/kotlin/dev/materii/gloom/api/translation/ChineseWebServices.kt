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
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

internal fun md5Hex(input: String): String =
    MessageDigest.getInstance("MD5")
        .digest(input.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

/**
 * Youdao anonymous web translation (dict.youdao.com jsonapi_s).
 * Chinese <-> English only; other directions fail over to the next pool service.
 */
class YoudaoTranslateService(
    private val client: HttpClient,
    private val json: Json
): TranslationService {

    override val id = "youdao"

    override suspend fun translate(text: String, source: String, target: String): String {
        val (from, to) = chineseEnglishDirection(source, target, text)

        return translateChunked(text, CHUNK) { chunk ->
            val timestamp = (System.currentTimeMillis() + ((chunk + KEYFROM).length % 10)).toString()
            val sign = md5Hex("$CLIENT$chunk$timestamp$SECRET${md5Hex(chunk + KEYFROM)}")

            val body = try {
                client.get(URL) {
                    parameter("q", chunk)
                    parameter("from", from)
                    parameter("to", to)
                    parameter("t", timestamp)
                    parameter("client", CLIENT)
                    parameter("sign", sign)
                    parameter("keyfrom", KEYFROM)
                    timeout { requestTimeoutMillis = TIMEOUT }
                }.bodyAsText()
            } catch (e: TranslationException) {
                throw e
            } catch (e: Exception) {
                throw TranslationException.failed("Youdao request failed: ${e.message}", e)
            }

            val root = parseObject(json, body, "Youdao")
            val direction = root["fanyi"]?.jsonObject?.get("type")?.jsonPrimitive?.content?.trim()?.lowercase()
            if (direction == "en2zh-chs" || direction == "zh-chs2en") {
                if (direction != "${from}2${to}".lowercase()) {
                    throw TranslationException.failed("Youdao returned a different direction")
                }
            }
            val translated = root["fanyi"]?.jsonObject?.get("tran")?.jsonPrimitive?.content?.trim()
            if (translated.isNullOrBlank()) throw TranslationException.empty()
            translated
        }
    }

    companion object {
        private const val URL = "https://dict.youdao.com/jsonapi_s?doctype=json&jsonversion=4"
        private const val KEYFROM = "webfanyi.webmain"
        private const val CLIENT = "webmain"

        // Public anonymous constant shipped in FluentRead's open-source repo.
        private const val SECRET = "t2he2k4m2g6QKRigK0KAmSpXKgAezyWg"
        private const val TIMEOUT = 15_000L
        private const val CHUNK = 500
    }
}

/**
 * Kingsoft PowerWord (iciba) anonymous web translation.
 * Response payload is AES-ECB encrypted with a public constant key.
 */
class IcibaTranslateService(
    private val client: HttpClient,
    private val json: Json
): TranslationService {

    override val id = "iciba"

    override suspend fun translate(text: String, source: String, target: String): String {
        val (from, to) = chineseEnglishDirection(source, target, text)

        return translateChunked(text, CHUNK) { chunk ->
            val digest = md5Hex(SIGN_PREFIX + chunk).substring(0, 16)
            val sign = aesEncrypt(digest, SIGN_KEY)

            val body = try {
                client.post(URL) {
                    contentType(ContentType.Application.FormUrlEncoded)
                    setBody(
                        listOf(
                            "from" to (if (from == "zh-CHS") "zh" else from),
                            "to" to (if (to == "zh-CHS") "zh" else to),
                            "q" to chunk,
                            "client" to CLIENT,
                            "auth_user" to AUTH_USER,
                            "sign" to sign
                        ).joinToString("&") { (k, v) ->
                            "${java.net.URLEncoder.encode(k, "UTF-8")}=${java.net.URLEncoder.encode(v, "UTF-8")}"
                        }
                    )
                    timeout { requestTimeoutMillis = TIMEOUT }
                }.bodyAsText()
            } catch (e: TranslationException) {
                throw e
            } catch (e: Exception) {
                throw TranslationException.failed("iciba request failed: ${e.message}", e)
            }

            val root = parseObject(json, body, "iciba")
            val content = root["content"]?.jsonPrimitive?.content
            if (content.isNullOrBlank()) throw TranslationException.failed("iciba returned no content")
            val plain = aesDecrypt(content, RESPONSE_KEY)
            val inner = try {
                json.parseToJsonElement(plain).jsonObject
            } catch (e: Exception) {
                throw TranslationException.failed("iciba payload invalid", e)
            }
            val errNo = inner["err_no"]?.jsonPrimitive?.content?.toIntOrNull() ?: -1
            if (errNo != 0) throw TranslationException.failed("iciba error code $errNo")
            val translated = inner["out"]?.jsonPrimitive?.content?.trim()
            if (translated.isNullOrBlank()) throw TranslationException.empty()
            translated
        }
    }

    companion object {
        private const val URL = "https://ifanyi.iciba.com/index.php?c=trans&m=fyV2"
        private const val CLIENT = "6"
        private const val AUTH_USER = "key_web_new_fanyi"

        // Public anonymous constants shipped in FluentRead's open-source repo.
        private const val SIGN_PREFIX = "6key_web_new_fanyi6dVjYLFyzfkFkk"
        private const val SIGN_KEY = "L4fBtD5fLC9FQw22"
        private const val RESPONSE_KEY = "aahc3TfyfCEmER33"
        private const val TIMEOUT = 15_000L
        private const val CHUNK = 1000
    }
}

/** Both services only support the Chinese<->English pair. */
internal fun chineseEnglishDirection(source: String, target: String, text: String): Pair<String, String> {
    fun normalize(value: String, isTarget: Boolean): String {
        val code = value.trim().lowercase().replace('_', '-')
        return when {
            code == "zh-cn" || code == "zh" || code == "zh-hans" || code == "zh-chs" -> "zh-CHS"
            code == "en" -> "en"
            code == "auto" -> {
                // Detect: CJK present -> Chinese, otherwise assume English.
                val looksChinese = Regex("[\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}]").containsMatchIn(text)
                if (isTarget) {
                    if (looksChinese) "en" else "zh-CHS"
                } else {
                    if (looksChinese) "zh-CHS" else "en"
                }
            }
            else -> throw TranslationException.failed("Service only supports Chinese<->English")
        }
    }

    val from = normalize(source, isTarget = false)
    val to = normalize(target, isTarget = true)
    if (from == to) throw TranslationException.failed("Same language direction")
    return from to to
}

internal suspend fun translateChunked(
    text: String,
    chunkSize: Int,
    translateChunk: suspend (String) -> String
): String {
    if (text.length <= chunkSize) return translateChunk(text)

    val chunks = mutableListOf<String>()
    val current = StringBuilder()
    for (paragraph in text.split('\n')) {
        if (current.length + paragraph.length + 1 > chunkSize && current.isNotEmpty()) {
            chunks += current.toString()
            current.clear()
        }
        if (current.isNotEmpty()) current.append('\n')
        current.append(paragraph)
    }
    if (current.isNotEmpty()) chunks += current.toString()

    val translated = StringBuilder()
    chunks.forEachIndexed { index, chunk ->
        if (index > 0) translated.append('\n')
        translated.append(translateChunk(chunk))
    }
    return translated.toString()
}

internal fun aesEncrypt(plain: String, key: String): String {
    val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
    cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key.toByteArray(Charsets.UTF_8), "AES"))
    return Base64.getEncoder().encodeToString(cipher.doFinal(plain.toByteArray(Charsets.UTF_8)))
}

internal fun aesDecrypt(encoded: String, key: String): String {
    val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
    cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key.toByteArray(Charsets.UTF_8), "AES"))
    return String(cipher.doFinal(Base64.getDecoder().decode(encoded)), Charsets.UTF_8)
}
