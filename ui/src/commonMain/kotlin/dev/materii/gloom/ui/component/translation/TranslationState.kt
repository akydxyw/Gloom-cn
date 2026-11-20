package dev.materii.gloom.ui.component.translation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.materii.gloom.api.translation.TranslationManager
import org.koin.compose.koinInject

/**
 * Translate [text] automatically while [enabled]. Returns the cached/translated
 * value, the original text when it already is in the target language, or null
 * while pending/failed.
 */
@Composable
fun rememberTranslation(
    text: String,
    enabled: Boolean
): String? {
    val manager: TranslationManager = koinInject()
    var result by remember(text) { mutableStateOf(manager.peek(text)) }

    LaunchedEffect(text, enabled) {
        if (!enabled || result != null || text.isBlank()) return@LaunchedEffect
        result = manager.translateOrNull(text)
    }

    return result
}
