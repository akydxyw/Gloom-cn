package dev.materii.gloom.ui.widget.markdown

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Gate shared between the native WebView touch listeners and the pager that
 * hosts the README page.
 *
 * While a finger is down on a Markdown WebView (or anywhere on the README
 * page), [active] is true and the surrounding pager's user scrolling is
 * disabled: wide tables scroll horizontally inside the WebView (natively)
 * and must never be stolen by an accidental page change. Vertical page
 * scrolling, pull-to-refresh, tab clicks and programmatic page scrolls are
 * unaffected.
 */
object WebViewTouchGate {
    var active by mutableStateOf(false)
        private set

    fun set(active: Boolean) {
        this.active = active
    }
}
