package dev.materii.gloom.domain.manager.enums

/**
 * Translation engine selection.
 */
enum class TranslationEngine {
    /** Free-pool failover: requests are routed across the enabled free services. */
    FREE_POOL,

    /** Google free web endpoint, no key required (may be unreachable without a proxy). */
    GOOGLE,

    /** Microsoft Edge free endpoint, no key required. */
    MICROSOFT,

    /** MyMemory public API, no key required. */
    MYMEMORY,

    /** DeepLX, public endpoint by default or a custom self-hosted one. */
    DEEPLX,

    /** Custom OpenAI-compatible chat completions endpoint. */
    OPENAI
}

/**
 * Routing strategy of the free translation pool.
 */
enum class FreePoolMode {
    /** Weighted random across enabled services, faster/healthier services get more traffic. */
    BALANCED,

    /** Strictly follow the configured service order, falling back on failure. */
    SEQUENTIAL
}

/**
 * Supported translation target languages.
 *
 * [code] is the BCP-47-ish code used by most services,
 * [deeplCode] is the uppercase code DeepL(X) expects.
 */
enum class TranslationTarget(
    val code: String,
    val deeplCode: String
) {
    ZH_CN("zh-CN", "ZH"),
    ZH_TW("zh-TW", "ZH-TW"),
    EN("en", "EN"),
    JA("ja", "JA");

    /** Primary subtag, e.g. "zh" for "zh-CN". */
    val primary: String
        get() = code.substringBefore('-')
}
