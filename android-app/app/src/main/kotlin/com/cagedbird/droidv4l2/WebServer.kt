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

    private var latestJpeg: ByteArray? = null
    private val frameLock = Object()

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val method = session.method

        Log.d(TAG, "Request: $method $uri")

        return when {
            uri == "/" -> serveIndexHtml()
            uri == "/stream" -> serveMjpegStream()
            uri == "/offer" && method == Method.POST -> handleOffer(session)
            uri == "/candidate" && method == Method.POST -> handleCandidate(session)
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not Found")
        }
    }

    fun updateFrame(jpegData: ByteArray) {
        synchronized(frameLock) {
            latestJpeg = jpegData
            frameLock.notifyAll()
        }
    }

    private fun serveMjpegStream(): Response {
        return try {
            val pipedInputStream = java.io.PipedInputStream()
            val pipedOutputStream = java.io.PipedOutputStream(pipedInputStream)

            Thread {
                        try {
                            val boundary = "--BoundaryString"
                            pipedOutputStream.write(
                                    ("HTTP/1.0 200 OK\r\n" +
                                                    "Server: DroidV4L2\r\n" +
                                                    "Connection: close\r\n" +
                                                    "Max-Age: 0\r\n" +
                                                    "Expires: 0\r\n" +
                                                    "Cache-Control: no-cache, private\r\n" +
                                                    "Pragma: no-cache\r\n" +
                                                    "Content-Type: multipart/x-mixed-replace; boundary=$boundary\r\n\r\n")
                                            .toByteArray()
                            )

                            while (true) {
                                var jpeg: ByteArray?
                                synchronized(frameLock) {
                                    frameLock.wait(1000) // Wait for new frame
                                    jpeg = latestJpeg
                                }

                                if (jpeg != null) {
                                    pipedOutputStream.write((boundary + "\r\n").toByteArray())
                                    pipedOutputStream.write(
                                            ("Content-Type: image/jpeg\r\n" +
                                                            "Content-Length: " +
                                                            jpeg!!.size +
                                                            "\r\n\r\n")
                                                    .toByteArray()
                                    )
                                    pipedOutputStream.write(jpeg)
                                    pipedOutputStream.write("\r\n".toByteArray())
                                    pipedOutputStream.flush()
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "MJPEG stream ended", e)
                        }
                    }
                    .start()

            newChunkedResponse(
                    Response.Status.OK,
                    "multipart/x-mixed-replace; boundary=--BoundaryString",
                    pipedInputStream
            )
        } catch (e: IOException) {
            newFixedLengthResponse(
                    Response.Status.INTERNAL_ERROR,
                    MIME_PLAINTEXT,
                    "Error starting stream"
            )
        }
    }

    private fun serveIndexHtml(): Response {
        // Simple MJPEG viewer if stream requested
        // TODO: Update index.html to support MJPEG mode or separate page
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
