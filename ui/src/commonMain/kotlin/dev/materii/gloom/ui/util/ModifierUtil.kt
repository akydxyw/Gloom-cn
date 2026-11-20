package dev.materii.gloom.ui.util

import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.toSize
import dev.icerock.moko.resources.StringResource
import dev.icerock.moko.resources.compose.stringResource

inline fun Modifier.thenIf(predicate: Boolean, block: Modifier.() -> Modifier) =
    if (predicate) then(Modifier.block()) else this

/**
 * Clears view focus (and dismisses the keyboard) whenever a press lands
 * outside of the composable identified by [targetCoords]. Presses on the
 * target itself leave the focus untouched.
 *
 * Apply this modifier to a common ancestor of both the target and the
 * rest of the content.
 *
 * @param targetCoords Layout coordinates of the focusable target, e.g. a search field
 */
@Composable
fun Modifier.clearFocusOnOutsidePress(targetCoords: LayoutCoordinates?): Modifier {
    val focusManager = LocalFocusManager.current
    var selfCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }

    return this
        .onGloballyPositioned { selfCoords = it }
        .pointerInput(targetCoords) {
            awaitPointerEventScope {
                while (true) {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val self = selfCoords
                    val insideTarget = self != null && targetCoords != null &&
                        targetCoords.isAttached &&
                        Rect(
                            self.localPositionOf(targetCoords, Offset.Zero),
                            targetCoords.size.toSize()
                        ).contains(down.position)
                    if (!insideTarget) {
                        focusManager.clearFocus()
                    }
                }
            }
        }
}

@Composable
fun Modifier.contentDescription(descRes: StringResource, mergeDescendants: Boolean = false) =
    contentDescription(stringResource(descRes), mergeDescendants)

@Composable
fun Modifier.contentDescription(
    descRes: StringResource,
    vararg args: Any,
    mergeDescendants: Boolean = false
) =
    contentDescription(stringResource(descRes, *args), mergeDescendants)

fun Modifier.contentDescription(desc: String, mergeDescendants: Boolean = false) =
    semantics(mergeDescendants) {
        contentDescription = desc
    }