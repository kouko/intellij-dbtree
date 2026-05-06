package dev.kouko.intellijdbtree.jcef

import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.callback.CefCallback
import org.cef.handler.CefRequestHandlerAdapter
import org.cef.handler.CefResourceHandler
import org.cef.handler.CefResourceHandlerAdapter
import org.cef.handler.CefResourceRequestHandler
import org.cef.handler.CefResourceRequestHandlerAdapter
import org.cef.misc.BoolRef
import org.cef.network.CefRequest
import java.net.URI

private typealias CefResourceProvider = () -> CefResourceHandler?

/**
 * Routes JCEF requests to provider lambdas keyed by URL filename.
 *
 * Pattern adapted from the archived `ramonvermeulen/dbt-toolkit`
 * (GPL-3.0). We ship the React frontend as classpath resources under
 * `lineage-panel-dist/` and let the browser load `index.html` etc. through
 * here so the bundle works inside the plugin jar.
 */
class CefLocalRequestHandler : CefRequestHandlerAdapter() {
    private val resources: MutableMap<String, CefResourceProvider> = HashMap()

    private val rejectingResourceHandler: CefResourceHandler =
        object : CefResourceHandlerAdapter() {
            override fun processRequest(request: CefRequest, callback: CefCallback): Boolean {
                callback.cancel()
                return false
            }
        }

    private val resourceRequestHandler =
        object : CefResourceRequestHandlerAdapter() {
            override fun getResourceHandler(
                browser: CefBrowser?,
                frame: CefFrame?,
                request: CefRequest,
            ): CefResourceHandler {
                return try {
                    val fileName = URI.create(request.url).toURL().path.split("/").last()
                    resources[fileName]?.invoke() ?: rejectingResourceHandler
                } catch (_: RuntimeException) {
                    rejectingResourceHandler
                }
            }
        }

    fun addResource(resourcePath: String, resourceProvider: CefResourceProvider) {
        resources[resourcePath] = resourceProvider
    }

    override fun getResourceRequestHandler(
        browser: CefBrowser?,
        frame: CefFrame?,
        request: CefRequest?,
        isNavigation: Boolean,
        isDownload: Boolean,
        requestInitiator: String?,
        disableDefaultHandling: BoolRef?,
    ): CefResourceRequestHandler = resourceRequestHandler
}
