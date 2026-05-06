package dev.kouko.intellijdbtree.toolwindow

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefBrowserBuilder
import dev.kouko.intellijdbtree.jcef.CefLocalRequestHandler
import dev.kouko.intellijdbtree.jcef.CefStreamResourceHandler
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefLoadHandlerAdapter
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingConstants
import javax.swing.SwingUtilities

/**
 * Tool-window content for intellij-dbtree.
 *
 * Hosts a JCEF browser that renders the bundled React lineage UI from
 * the plugin's classpath at `$RESOURCE_DIR/index.html`.
 *
 * Phase A0: just embed the React app — no Kotlin↔JS communication, the
 * frontend renders its own demo fixture. Phase A1 adds the
 * `setLineageInfo` push and `JBCefJSQuery` callback handlers.
 */
class LineagePanel(project: Project) : Disposable {

    private val log = Logger.getInstance(LineagePanel::class.java)
    private val mainPanel = JPanel(BorderLayout())

    private val cefClient = if (JBCefApp.isSupported()) JBCefApp.getInstance().createClient() else null
    private val isSandbox = System.getProperty("idea.plugin.in.sandbox.mode") == "true"
    private val browser: JBCefBrowser? = cefClient?.let {
        JBCefBrowserBuilder()
            .setClient(it)
            .setEnableOpenDevToolsMenuItem(isSandbox)
            .setOffScreenRendering(false)
            .build()
    }

    init {
        Disposer.register(project, this)
        if (!JBCefApp.isSupported()) {
            mainPanel.add(unsupportedMessage(), BorderLayout.CENTER)
            log.warn("JCEF is not supported in this IDE; LineagePanel disabled.")
        } else {
            initBrowser()
        }
    }

    val component: JComponent get() = mainPanel

    private fun initBrowser() {
        val browser = this.browser ?: return
        registerResourceHandlers()
        installLoadLogger()
        SwingUtilities.invokeLater {
            mainPanel.removeAll()
            mainPanel.add(browser.component, BorderLayout.CENTER)
            browser.loadURL("$BASE_URL/$INDEX_FILE")
            mainPanel.revalidate()
            mainPanel.repaint()
        }
    }

    private fun installLoadLogger() {
        val client = cefClient ?: return
        val cefBrowser = browser?.cefBrowser ?: return
        client.addLoadHandler(
            object : CefLoadHandlerAdapter() {
                override fun onLoadStart(b: CefBrowser?, frame: CefFrame?, transitionType: org.cef.network.CefRequest.TransitionType?) {
                    log.info("JCEF onLoadStart: ${b?.url}")
                }

                override fun onLoadEnd(b: CefBrowser?, frame: CefFrame?, httpStatusCode: Int) {
                    log.info("JCEF onLoadEnd: ${b?.url} (status=$httpStatusCode)")
                }

                override fun onLoadError(
                    b: CefBrowser?,
                    frame: CefFrame?,
                    errorCode: org.cef.handler.CefLoadHandler.ErrorCode?,
                    errorText: String?,
                    failedUrl: String?,
                ) {
                    log.warn("JCEF onLoadError: $failedUrl  err=$errorCode  text=$errorText")
                }
            },
            cefBrowser,
        )
    }

    private fun registerResourceHandlers() {
        val handler = CefLocalRequestHandler()
        for ((file, mime) in BUNDLED_FILES) {
            handler.addResource(file) {
                val stream = javaClass.classLoader.getResourceAsStream("$RESOURCE_DIR/$file")
                if (stream == null) {
                    log.warn("Bundled frontend file not found on classpath: $RESOURCE_DIR/$file")
                    null
                } else {
                    log.info("Serving bundled file: $file ($mime)")
                    CefStreamResourceHandler(stream, mime, this@LineagePanel)
                }
            }
        }
        val client = cefClient ?: return
        val cefBrowser = browser?.cefBrowser ?: return
        client.addRequestHandler(handler, cefBrowser)
    }

    private fun unsupportedMessage(): JComponent {
        return JLabel(
            "<html><center>JCEF is not available in this IDE.<br>" +
                "Enable the bundled JCEF runtime via <i>Help → Find Action → Choose Boot Java Runtime</i> " +
                "and pick a runtime <b>with JCEF</b>.</center></html>",
            SwingConstants.CENTER,
        )
    }

    override fun dispose() {
        browser?.dispose()
        cefClient?.dispose()
        mainPanel.removeAll()
    }

    companion object {
        private const val RESOURCE_DIR = "lineage-panel-dist"
        private const val INDEX_FILE = "index.html"

        // JCEF needs an absolute URL to navigate. The CefRequestHandler intercepts
        // every request and returns the matching classpath stream, so the host is
        // a synthetic local-only marker — no DNS / network lookup happens.
        private const val BASE_URL = "https://intellij-dbtree.local"

        // Vite is configured to emit flat names (vite.config.ts).
        // If you add new bundled assets (icons, fonts), register them here.
        private val BUNDLED_FILES: List<Pair<String, String>> = listOf(
            "index.html" to "text/html",
            "index.js" to "text/javascript",
            "index.css" to "text/css",
            "favicon.svg" to "image/svg+xml",
        )
    }
}
