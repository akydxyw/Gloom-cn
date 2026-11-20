package dev.materii.gloom.api.translation

/**
 * Anonymous free translation services participating in the free pool,
 * modeled after FluentRead's free translation directory.
 *
 * [weight] drives the balanced routing mode: heavier services receive more traffic.
 */
enum class FreePoolService(
    val id: String,
    val weight: Int
) {
    MICROSOFT("microsoft", 5),
    TRANSMART("transmart", 3),
    VOLCENGINE("volcengine", 3),
    GOOGLE("google", 3),
    YOUDAO("youdao", 3),
    ICIBA("iciba", 3),
    ALIBABA("alibaba", 3),
    BILIBILI("bilibili", 2),
    MODERNMT("modernmt", 2),
    LINGVANEX("lingvanex", 2),
    YANDEX("yandex", 2),
    MYMEMORY("mymemory", 1),
    REVERSO("reverso", 1),
    APERTIUM("apertium", 1);

    companion object {

        fun fromId(id: String): FreePoolService? = entries.firstOrNull { it.id == id }
    }
}
