package com.cagedbird.droidv4l2

import android.content.Context
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import java.io.IOException

class WebServer(private val context: Context, port: Int, private val webRtcManager: WebRtcManager) :
        NanoHTTPD(port) {

    private val TAG = "WebServer"

    init {
        try {
            start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
            Log.i(TAG, "WebServer started on port $port")
        } catch (e: IOException) {
            Log.e(TAG, "Failed to start WebServer", e)
        }
    }

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val method = session.method

        Log.d(TAG, "Request: $method $uri")

        return when {
            uri == "/" -> serveIndexHtml()
            uri == "/offer" && method == Method.POST -> handleOffer(session)
            uri == "/candidate" && method == Method.POST -> handleCandidate(session)
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not Found")
        }
    }

    private fun serveIndexHtml(): Response {
        return try {
            val inputStream = context.assets.open("index.html")
            val html = inputStream.bufferedReader().use { it.readText() }
            newFixedLengthResponse(Response.Status.OK, MIME_HTML, html)
        } catch (e: IOException) {
            newFixedLengthResponse(
                    Response.Status.INTERNAL_ERROR,
                    MIME_PLAINTEXT,
                    "Internal Error: index.html not found"
            )
        }
    }

    private fun handleOffer(session: IHTTPSession): Response {
        val map = HashMap<String, String>()
        session.parseBody(map)
        val json =
                map["postData"]
                        ?: return newFixedLengthResponse(
                                Response.Status.BAD_REQUEST,
                                MIME_PLAINTEXT,
                                "Missing body"
                        )

        val answer = webRtcManager.handleOffer(json)

        return if (answer != null) {
            newFixedLengthResponse(Response.Status.OK, "application/json", answer)
        } else {
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "WebRTC Error")
        }
    }

    private fun handleCandidate(session: IHTTPSession): Response {
        val map = HashMap<String, String>()
        session.parseBody(map)
        val json =
                map["postData"]
                        ?: return newFixedLengthResponse(
                                Response.Status.BAD_REQUEST,
                                MIME_PLAINTEXT,
                                "Missing body"
                        )

        // TODO: Pass candidate to WebRTC Manager

        return newFixedLengthResponse(Response.Status.OK, "application/json", "{\"status\":\"ok\"}")
    }
}
