package dev.kouko.intellijdbtree.jcef

import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.Disposer
import org.cef.callback.CefCallback
import org.cef.handler.CefResourceHandler
import org.cef.misc.IntRef
import org.cef.misc.StringRef
import org.cef.network.CefRequest
import org.cef.network.CefResponse
import java.io.IOException
import java.io.InputStream

/**
 * Streams a single classpath resource into a JCEF response, with the right
 * MIME type. Disposed once the stream is exhausted or the request is cancelled.
 *
 * Adapted from `ramonvermeulen/dbt-toolkit` (GPL-3.0).
 */
class CefStreamResourceHandler(
    private val stream: InputStream,
    private val mimeType: String,
    parent: Disposable,
) : CefResourceHandler, Disposable {

    init {
        Disposer.register(parent, this)
    }

    override fun processRequest(request: CefRequest, callback: CefCallback): Boolean {
        callback.Continue()
        return true
    }

    override fun getResponseHeaders(
        response: CefResponse,
        responseLength: IntRef,
        redirectUrl: StringRef,
    ) {
        response.mimeType = mimeType
        response.status = 200
    }

    override fun readResponse(
        dataOut: ByteArray,
        bytesToRead: Int,
        bytesRead: IntRef,
        callback: CefCallback,
    ): Boolean {
        try {
            val read = stream.read(dataOut, 0, bytesToRead)
            bytesRead.set(read)
            if (read != -1) return true
        } catch (e: IOException) {
            callback.cancel()
        }
        bytesRead.set(0)
        Disposer.dispose(this)
        return false
    }

    override fun cancel() {
        Disposer.dispose(this)
    }

    override fun dispose() {
        try {
            stream.close()
        } catch (e: IOException) {
            Logger.getInstance(CefStreamResourceHandler::class.java)
                .warn("Failed to close the resource stream", e)
        }
    }
}
