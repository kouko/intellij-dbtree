package dev.kouko.intellijdbtree.toolwindow

import com.intellij.ide.ui.LafManager
import com.intellij.ide.ui.LafManagerListener
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.JBColor
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefBrowserBase
import com.intellij.ui.jcef.JBCefBrowserBuilder
import com.intellij.ui.jcef.JBCefJSQuery
import dev.kouko.intellijdbtree.jcef.CefLocalRequestHandler
import dev.kouko.intellijdbtree.jcef.CefStreamResourceHandler
import dev.kouko.intellijdbtree.lineage.ColumnEdgesDelta
import dev.kouko.intellijdbtree.lineage.ColumnSpec
import dev.kouko.intellijdbtree.lineage.LineageInfoListener
import dev.kouko.intellijdbtree.lineage.LineageInfoService
import dev.kouko.intellijdbtree.lineage.LineageJson
import dev.kouko.intellijdbtree.lineage.LineagePayload
import dev.kouko.intellijdbtree.lineage.ManifestService
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.Json
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefLoadHandlerAdapter
import java.awt.BorderLayout
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingConstants
import javax.swing.SwingUtilities

/**
 * Tool-window content for intellij-dbtree.
 *
 * Owns the Kotlin <-> JS bridge:
 *   - Pushes lineage payloads (and IDE theme) to the React UI via
 *     `executeJavaScript("window.setLineageInfo(...)")` / theme setter.
 *   - Receives NODE_CLICK / HOP_CHANGE / REFRESH events from the React UI
 *     through a `JBCefJSQuery` exposed at `window.kotlinCallback`.
 */
class LineagePanel(private val project: Project) : Disposable {

    private val log = Logger.getInstance(LineagePanel::class.java)
    private val mainPanel = JPanel(BorderLayout())

    private val cefClient = if (JBCefApp.isSupported()) JBCefApp.getInstance().createClient() else null
    private val isSandbox = System.getProperty("idea.plugin.in.sandbox.mode") == "true"
    private val browser: JBCefBrowser? = cefClient?.let {
        JBCefBrowserBuilder()
            .setClient(it)
            .setEnableOpenDevToolsMenuItem(isSandbox)
            .setOffScreenRendering(true)
            .build()
    }
    private val jsQuery: JBCefJSQuery? =
        browser?.let { JBCefJSQuery.create(it as JBCefBrowserBase) }

    private val pageReady = AtomicBoolean(false)
    private val pending = AtomicReference<LineagePayload?>(null)

    init {
        Disposer.register(project, this)
        if (!JBCefApp.isSupported()) {
            mainPanel.add(unsupportedMessage(), BorderLayout.CENTER)
            log.warn("JCEF is not supported in this IDE; LineagePanel disabled.")
        } else {
            initBrowser()
            installJsToKotlinBridge()
            subscribeToLineageEvents()
            subscribeToThemeChanges()
            triggerInitialLineage()
        }
    }

    val component: JComponent get() = mainPanel

    // ---- Browser bootstrap ------------------------------------------------------

    private fun initBrowser() {
        val browser = this.browser ?: return
        registerResourceHandlers()
        installLoadHandler()
        SwingUtilities.invokeLater {
            mainPanel.removeAll()
            mainPanel.add(browser.component, BorderLayout.CENTER)
            browser.loadURL("$BASE_URL/$INDEX_FILE")
            mainPanel.revalidate()
            mainPanel.repaint()
        }
    }

    private fun installLoadHandler() {
        val client = cefClient ?: return
        val cefBrowser = browser?.cefBrowser ?: return
        val query = jsQuery
        client.addLoadHandler(
            object : CefLoadHandlerAdapter() {
                override fun onLoadStart(b: CefBrowser?, frame: CefFrame?, transitionType: org.cef.network.CefRequest.TransitionType?) {
                    log.info("JCEF onLoadStart: ${b?.url}")
                    pageReady.set(false)
                }

                override fun onLoadEnd(b: CefBrowser?, frame: CefFrame?, httpStatusCode: Int) {
                    if (b == null || frame?.parent != null) return
                    log.info("JCEF onLoadEnd: ${b.url} (status=$httpStatusCode)")
                    if (query != null) {
                        b.executeJavaScript(
                            "window.kotlinCallback = function(v) { ${query.inject("v")} };",
                            b.url, 0,
                        )
                    }
                    pushTheme(currentTheme())
                    pushHostState()
                    pageReady.set(true)
                    pending.get()?.let { pushPayload(it) }
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
                    CefStreamResourceHandler(stream, mime, this@LineagePanel)
                }
            }
        }
        val client = cefClient ?: return
        val cefBrowser = browser?.cefBrowser ?: return
        client.addRequestHandler(handler, cefBrowser)
    }

    // ---- Kotlin -> JS push --------------------------------------------------------

    private fun subscribeToLineageEvents() {
        project.messageBus.connect(this).subscribe(
            LineageInfoService.TOPIC,
            object : LineageInfoListener {
                override fun lineagePayloadChanged(payload: LineagePayload) {
                    pending.set(payload)
                    if (pageReady.get()) pushPayload(payload)
                }

                override fun selectedModelChanged(uniqueId: String) {
                    if (pageReady.get()) pushSelected(uniqueId)
                }

                override fun modelColumnsUpdated(uniqueId: String, columns: List<ColumnSpec>) {
                    if (pageReady.get()) pushModelColumns(uniqueId, columns)
                }

                override fun columnEdgesAppended(delta: ColumnEdgesDelta) {
                    if (pageReady.get()) pushColumnEdgesDelta(delta)
                }
            },
        )
    }

    private fun subscribeToThemeChanges() {
        ApplicationManager.getApplication().messageBus.connect(this).subscribe(
            LafManagerListener.TOPIC,
            LafManagerListener {
                if (pageReady.get()) pushTheme(currentTheme())
            },
        )
    }

    private fun triggerInitialLineage() {
        ApplicationManager.getApplication().executeOnPooledThread {
            val openFile = ApplicationManager.getApplication().runReadAction<com.intellij.openapi.vfs.VirtualFile?> {
                FileEditorManager.getInstance(project).selectedFiles.firstOrNull()
            }
            if (openFile != null) {
                project.service<LineageInfoService>().onActiveFileChanged(openFile)
            } else {
                // No active file at startup: route through refreshFromDisk so
                // a missing/broken manifest publishes a status banner instead
                // of silently warming the cache and leaving the panel blank.
                project.service<LineageInfoService>().refreshFromDisk()
            }
        }
    }

    private fun pushPayload(payload: LineagePayload) {
        val browser = this.browser ?: return
        val json = LineageJson.encodeToString(LineagePayload.serializer(), payload)
        val script = "window.setLineageInfo && window.setLineageInfo(JSON.parse(${jsStringLiteral(json)}));"
        SwingUtilities.invokeLater {
            browser.cefBrowser.executeJavaScript(script, browser.cefBrowser.url, 0)
        }
    }

    private fun pushSelected(uniqueId: String) {
        val browser = this.browser ?: return
        val script = "window.setSelectedModel && window.setSelectedModel(${jsStringLiteral(uniqueId)});"
        SwingUtilities.invokeLater {
            browser.cefBrowser.executeJavaScript(script, browser.cefBrowser.url, 0)
        }
    }

    private fun pushModelColumns(uniqueId: String, columns: List<ColumnSpec>) {
        val browser = this.browser ?: return
        val json = LineageJson.encodeToString(
            kotlinx.serialization.builtins.ListSerializer(ColumnSpec.serializer()),
            columns,
        )
        val script = "window.applyModelColumns && window.applyModelColumns(" +
            "${jsStringLiteral(uniqueId)}, JSON.parse(${jsStringLiteral(json)}));"
        SwingUtilities.invokeLater {
            browser.cefBrowser.executeJavaScript(script, browser.cefBrowser.url, 0)
        }
    }

    private fun pushColumnEdgesDelta(delta: ColumnEdgesDelta) {
        val browser = this.browser ?: return
        val json = LineageJson.encodeToString(ColumnEdgesDelta.serializer(), delta)
        val script = "window.applyColumnEdgesDelta && window.applyColumnEdgesDelta(" +
            "JSON.parse(${jsStringLiteral(json)}));"
        SwingUtilities.invokeLater {
            browser.cefBrowser.executeJavaScript(script, browser.cefBrowser.url, 0)
        }
    }

    /** Inject the current host state (default hops + theme) into a fresh page. */
    private fun pushHostState() {
        val browser = this.browser ?: return
        val s = project.service<LineageInfoService>().snapshot()
        val state = HostState(
            upHops = s.upHops,
            downHops = s.downHops,
        )
        val json = LineageJson.encodeToString(HostState.serializer(), state)
        val script = "window.__DBTREE_HOST_STATE__ = JSON.parse(${jsStringLiteral(json)}); " +
            "window.applyHostState && window.applyHostState(window.__DBTREE_HOST_STATE__);"
        SwingUtilities.invokeLater {
            browser.cefBrowser.executeJavaScript(script, browser.cefBrowser.url, 0)
        }
    }

    private fun pushTheme(theme: String) {
        val browser = this.browser ?: return
        val script = "window.__DBTREE_THEME__ = ${jsStringLiteral(theme)}; " +
            "window.setIdeTheme && window.setIdeTheme(${jsStringLiteral(theme)});"
        SwingUtilities.invokeLater {
            browser.cefBrowser.executeJavaScript(script, browser.cefBrowser.url, 0)
        }
    }

    private fun currentTheme(): String = if (JBColor.isBright()) "light" else "dark"

    private fun jsStringLiteral(s: String): String {
        val escaped = s
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
        return "\"$escaped\""
    }

    // ---- JS -> Kotlin handler -----------------------------------------------------

    private fun installJsToKotlinBridge() {
        val query = jsQuery ?: return
        query.addHandler { payload -> onJsCallback(payload); null }
    }

    private fun onJsCallback(raw: String) {
        val event = try {
            jsCallbackJson.decodeFromString(JsCallback.serializer(), raw)
        } catch (e: Exception) {
            log.warn("Failed to decode JS callback payload: $raw", e)
            return
        }
        when (event.event) {
            "NODE_CLICK" -> handleNodeClick(event.uniqueId)
            "HOP_CHANGE" -> handleHopChange(event.upHops, event.downHops)
            "REFRESH" -> project.service<LineageInfoService>().refreshFromDisk()
            "COLUMN_CLICK" -> handleColumnClick(event.uniqueId, event.column)
            "REQUEST_COLUMNS" -> handleRequestColumns(event.uniqueId)
            else -> log.info("Unhandled JS event: ${event.event}")
        }
    }

    private fun handleColumnClick(uniqueId: String?, column: String?) {
        if (uniqueId.isNullOrBlank() || column.isNullOrBlank()) return
        project.service<LineageInfoService>().onColumnClicked(uniqueId, column)
    }

    private fun handleNodeClick(uniqueId: String?) {
        if (uniqueId.isNullOrBlank()) return
        ApplicationManager.getApplication().executeOnPooledThread {
            val manifest = project.service<ManifestService>().ensureLoaded() ?: return@executeOnPooledThread
            val path: Path? = manifest.lookupOriginalPath(uniqueId)?.let { rel ->
                manifest.dbtProjectDir.resolve(rel)
            }
            if (path == null) {
                log.info("NODE_CLICK: no file path for $uniqueId (likely a source)")
                return@executeOnPooledThread
            }
            val vf = LocalFileSystem.getInstance().refreshAndFindFileByPath(path.toString())
                ?: return@executeOnPooledThread
            ApplicationManager.getApplication().invokeLater(
                { FileEditorManager.getInstance(project).openFile(vf, true) },
                ModalityState.defaultModalityState(),
            )
        }
    }

    private fun handleHopChange(upHops: Int?, downHops: Int?) {
        if (upHops == null || downHops == null) return
        project.service<LineageInfoService>().setHops(upHops, downHops)
    }

    private fun handleRequestColumns(uniqueId: String?) {
        if (uniqueId.isNullOrBlank()) return
        project.service<LineageInfoService>().onRequestColumns(uniqueId)
    }

    // ---- Disposable ---------------------------------------------------------------

    private fun unsupportedMessage(): JComponent {
        return JLabel(
            "<html><center>JCEF is not available in this IDE.<br>" +
                "Enable the bundled JCEF runtime via <i>Help → Find Action → Choose Boot Java Runtime</i> " +
                "and pick a runtime <b>with JCEF</b>.</center></html>",
            SwingConstants.CENTER,
        )
    }

    override fun dispose() {
        jsQuery?.dispose()
        browser?.dispose()
        cefClient?.dispose()
        mainPanel.removeAll()
    }

    @Serializable
    private data class JsCallback(
        val event: String,
        @SerialName("unique_id") val uniqueId: String? = null,
        val column: String? = null,
        @SerialName("up_hops") val upHops: Int? = null,
        @SerialName("down_hops") val downHops: Int? = null,
    )

    @Serializable
    private data class HostState(
        @SerialName("up_hops") val upHops: Int,
        @SerialName("down_hops") val downHops: Int,
    )

    companion object {
        private const val RESOURCE_DIR = "lineage-panel-dist"
        private const val INDEX_FILE = "index.html"
        private const val BASE_URL = "https://intellij-dbtree.local"

        private val BUNDLED_FILES: List<Pair<String, String>> = listOf(
            "index.html" to "text/html",
            "index.js" to "text/javascript",
            "index.css" to "text/css",
            "favicon.svg" to "image/svg+xml",
        )

        private val jsCallbackJson = Json { ignoreUnknownKeys = true }
    }
}
