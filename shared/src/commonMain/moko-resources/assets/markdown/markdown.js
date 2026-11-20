window.onerror = (msg, url, line, column, error) => {
    window.gloom._sendMessage("error", { message: msg });
};

class Gloom {
    constructor() {}

    /**
     * Sends a message to the native app
     *
     * @param {string} name The name used to distinguish between messages
     * @param {object} message Message object sent to the client
     */
    _sendMessage(name, message) {
        const payload = message;
        payload.name = name;
        payload.id = this._id;

        window.kmpJsBridge.callNative("MDMessage", JSON.stringify(payload));
    }

    /**
     * Unescape `raw.githubusercontent.com` paths which contain escaped query parameters
     *
     * This is taken from the GitHub Android app
     */
    _unescapeImageURLs() {
        const images = Array.from(document.querySelectorAll("img"));
        for (const image of images) {
            if (!image.src) continue;

            const imgSrc = new URL(image.src);
            if (imgSrc.host === "raw.githubusercontent.com") {
                const pathWithQueryParams = decodeURIComponent(imgSrc.pathname);
                const newPath = new URL(pathWithQueryParams, "https://raw.githubusercontent.com");

                // add back any query parameters from the original URL
                const searchParams = imgSrc.searchParams;
                for (const [key, value] of searchParams) {
                    newPath.searchParams.append(key, value);
                }

                image.src = newPath.href;
            }
        }
    }

    /**
     * Sometimes images from the /blob/ route just don't work, this fixes that
     * by redirecting to the /raw/ route
     *
     * This is taken from the GitHub Android app
     */
    _applyImageLoadingWorkaround() {
        document.querySelectorAll("source, img").forEach((node) => {
            ["srcset", "src"].forEach((attrName) => {
                const attr = node.getAttribute(attrName);
                if (
                attr !== null &&
                attr.startsWith("https://github.com") &&
                attr.indexOf("/blob/") !== -1
                ) {
                    node.setAttribute(attrName, attr.replace("/blob/", "/raw/"));
                }
            });
        });
    }

    /**
     * Load the markdown html into our template, this should
     * only be called after content has loaded in the webview
     *
     * @see dev.materii.gloom.ui.widget.markdown.Markdown in the ui module
     *
     * @param {string} id Unique UUID for this markdown WebView, generated from the app
     * @param {string} html The rendered markdown HTML
     */
    load(id, html) {
        this._id = id;
        const content = document.getElementById("content");

        if (content.innerHTML == content) return;

        content.innerHTML = html;

        this._sendMessage("loaded", { message: "Loaded" });
        this._unescapeImageURLs();
        this._applyImageLoadingWorkaround();
    }

    /**
     * Insert translations below their source paragraphs / inside their source
     * table cells.
     *
     * @see dev.materii.gloom.api.translation.TranslationManager which numbers
     * the translatable elements with `data-tr-idx` before the HTML is injected.
     *
     * @param {Object<string, string|string[]>} map Mapping of element index to
     *   translated text, or to an array of translated lines (one per source line).
     */
    applyTranslations(map) {
        if (!this._trNodes) this._trNodes = {};
        for (const key in map) {
            if (!Object.prototype.hasOwnProperty.call(map, key)) continue;
            const idx = String(parseInt(key, 10));
            let node = this._trNodes[idx];
            if (!node || !node.isConnected) {
                node = document.querySelector(`#content [data-tr-idx="${idx}"]`);
                if (!node) continue;
                this._trNodes[idx] = node;
            }
            if (this._alreadyTranslated(node)) continue;

            const value = map[key];
            const lines = (Array.isArray(value) ? value : [value]).filter(
                (line) => typeof line === "string" && line.trim()
            );
            if (!lines.length) continue;

            const isCell = node.tagName === "TD" || node.tagName === "TH";
            const fragment = document.createDocumentFragment();
            for (const line of lines) {
                const div = document.createElement("div");
                div.className = "gloom-tr";
                div.textContent = line;
                fragment.appendChild(div);
            }
            if (isCell) {
                // Table cells: translation goes inside the very same cell,
                // right under the source text.
                node.appendChild(fragment);
            } else {
                node.parentNode.insertBefore(fragment, node.nextSibling);
            }
        }
    }

    _alreadyTranslated(node) {
        if (node.tagName === "TD" || node.tagName === "TH") {
            return !!node.querySelector(":scope > .gloom-tr");
        }
        return !!(
            node.nextElementSibling &&
            node.nextElementSibling.classList &&
            node.nextElementSibling.classList.contains("gloom-tr")
        );
    }
}

const start = () => {
    window.gloom = new Gloom();
};

if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", start);
} else {
    start();
}

if (typeof exports !== "undefined") {
    exports.Gloom == Gloom;
}
