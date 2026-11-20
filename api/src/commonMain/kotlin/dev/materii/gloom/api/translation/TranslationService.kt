package dev.materii.gloom.api.translation

/**
 * A single translation provider.
 */
interface TranslationService {

    /** Stable identifier of this service. */
    val id: String

    /**
     * Translate [text] from [source] (BCP-47 code or "auto") to [target].
     *
     * @throws TranslationException on failure.
     */
    suspend fun translate(text: String, source: String, target: String): String
}

/**
 * A translation service capable of translating multiple texts in one call.
 */
interface BatchTranslationService {

    /**
     * Translate every entry of [texts], returning translations in the same order.
     *
     * @throws TranslationException on failure.
     */
    suspend fun translateBatch(texts: List<String>, source: String, target: String): List<String>
}

/**
 * Reasons a translation request can fail, used by the UI to pick a localized message.
 */
enum class TranslationErrorKind {
    /** Required service configuration is missing. */
    NOT_CONFIGURED,

    /** Network/HTTP/parsing failure, [TranslationException.message] carries details. */
    REQUEST_FAILED,

    /** The service answered with an empty translation. */
    EMPTY_RESPONSE
}

class TranslationException(
    val kind: TranslationErrorKind,
    message: String,
    cause: Throwable? = null
): Exception(message, cause) {

    companion object {
        fun notConfigured(detail: String): TranslationException =
            TranslationException(TranslationErrorKind.NOT_CONFIGURED, detail)

        fun failed(detail: String, cause: Throwable? = null): TranslationException =
            TranslationException(TranslationErrorKind.REQUEST_FAILED, detail, cause)

        fun empty(detail: String = "Empty translation"): TranslationException =
            TranslationException(TranslationErrorKind.EMPTY_RESPONSE, detail)
    }
}
