package dev.materii.gloom.ui.widget.markdown

import android.annotation.SuppressLint
import android.view.MotionEvent
import android.view.ViewGroup
import android.webkit.WebSettings
import com.benasher44.uuid.uuid4
import com.multiplatform.webview.web.NativeWebView

@SuppressLint("ClickableViewAccessibility")
actual fun <T> onWebViewCreated(view: T) {
    val nativeView = view as NativeWebView
    val id = uuid4().toString()

    nativeView.setBackgroundColor(android.graphics.Color.TRANSPARENT)
    nativeView.isVerticalScrollBarEnabled = false
    nativeView.isHorizontalScrollBarEnabled = false
    nativeView.layoutParams = ViewGroup.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    )

    // Flag every touch on the WebView so the hosting pager disables its own
    // drag gesture for the duration (see WebViewTouchGate): wide tables scroll
    // horizontally here natively and must never be stolen by an accidental
    // page change. The listener never consumes the events, so vertical page
    // scrolling and pull-to-refresh keep working unchanged.
    nativeView.setOnTouchListener { _, event ->
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> WebViewTouchGate.set(true)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> WebViewTouchGate.set(false)
        }
        false
    }

    // Prioritize Chromium rasterization so horizontally scrolled table regions
    // render quickly instead of staying blank.
    @Suppress("DEPRECATION")
    nativeView.settings.setRenderPriority(WebSettings.RenderPriority.HIGH)

    nativeView.settings.javaScriptEnabled = true
}