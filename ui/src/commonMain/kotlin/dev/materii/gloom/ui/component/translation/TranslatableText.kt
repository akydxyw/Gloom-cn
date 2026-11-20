package dev.materii.gloom.ui.component.translation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import dev.materii.gloom.api.translation.TranslationErrorKind
import dev.materii.gloom.api.translation.TranslationException
import dev.materii.gloom.api.translation.TranslationManager
import dev.materii.gloom.domain.manager.PreferenceManager
import dev.materii.gloom.Res
import dev.icerock.moko.resources.compose.stringResource
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * Text with its translation rendered underneath.
 *
 * When automatic translation is on (preference [PreferenceManager.autoTranslate]),
 * the translation is requested as soon as the text is composed — "translate what
 * you see". Otherwise a small translate button is offered for manual requests.
 */
@Composable
fun TranslatableText(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    translatedStyle: TextStyle = MaterialTheme.typography.bodyMedium.copy(
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
    )
) {
    val prefs: PreferenceManager = koinInject()

    if (!enabled) {
        Text(text = text, style = style, modifier = modifier)
        return
    }

    if (prefs.autoTranslate) {
        val translated = rememberTranslation(text, enabled)
        Column(modifier = modifier) {
            Text(text = text, style = style)
            if (translated != null && translated != text) {
                Text(text = translated, style = translatedStyle)
            }
        }
        return
    }

    ManualTranslatableText(
        text = text,
        modifier = modifier,
        style = style,
        translatedStyle = translatedStyle
    )
}

@Composable
private fun ManualTranslatableText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle,
    translatedStyle: TextStyle
) {
    val manager: TranslationManager = koinInject()
    val scope = rememberCoroutineScope()
    var translated by remember(text) { mutableStateOf<String?>(null) }
    var loading by remember(text) { mutableStateOf(false) }
    var errorKind by remember(text) { mutableStateOf<TranslationErrorKind?>(null) }
    var errorDetail by remember(text) { mutableStateOf<String?>(null) }

    Column(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = text,
                style = style,
                modifier = Modifier.weight(1f)
            )

            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp
                )
            } else {
                IconButton(
                    onClick = {
                        scope.launch {
                            loading = true
                            errorKind = null
                            errorDetail = null
                            try {
                                translated = manager.translate(text)
                            } catch (e: TranslationException) {
                                errorKind = e.kind
                                errorDetail = e.message
                            } catch (e: Exception) {
                                errorKind = TranslationErrorKind.REQUEST_FAILED
                                errorDetail = e.message
                            }
                            loading = false
                        }
                    },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Translate,
                        contentDescription = stringResource(Res.strings.cd_translate),
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }

        translated?.let {
            Text(text = it, style = translatedStyle)
        }

        errorKind?.let { kind ->
            Text(
                text = when (kind) {
                    TranslationErrorKind.NOT_CONFIGURED ->
                        stringResource(Res.strings.translation_error_not_configured)
                    else ->
                        stringResource(Res.strings.translation_error, errorDetail ?: "")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}
