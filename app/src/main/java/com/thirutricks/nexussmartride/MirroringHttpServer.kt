package com.thirutricks.nexussmartride

import android.content.Context
import android.util.Log
import java.io.*
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

/**
 * Lightweight HTTP server running on port 1880 serving the 800x360 map JPEG stream
 * to the Ampere Nexus scooter cluster display (Option B).
 *
 * Replicates the Lighttpd / react-native-static-server behavior uncovered from Ampere Connect.
 */
class MirroringHttpServer(
    private val context: Context,
    private val port: Int = DEFAULT_PORT
) {

    companion object {
        private const val TAG = "MirroringHttpServer"
        const val DEFAULT_PORT = 1880
    }

    private var serverSocket: ServerSocket? = null
    private var isRunning = false
    private val threadPool = Executors.newFixedThreadPool(4)
    private var serverThread: Thread? = null

    val streamDir: File by lazy {
        File(context.filesDir, "www").apply {
            if (!exists()) mkdirs()
        }
    }

    val streamFile: File by lazy {
        File(streamDir, "stream.jpeg")
    }

    val streamTempFile: File by lazy {
        File(streamDir, "stream_temp.jpeg")
    }

    @Synchronized
    fun start() {
        if (isRunning) return
        try {
            serverSocket = ServerSocket(port)
            isRunning = true
            Log.i(TAG, "Mirroring HTTP server listening on port $port (serving ${streamDir.absolutePath})")

            serverThread = Thread {
                while (isRunning) {
                    try {
                        val client = serverSocket?.accept() ?: break
                        threadPool.execute { handleClient(client) }
                    } catch (e: Exception) {
                        if (isRunning) {
                            Log.w(TAG, "Error accepting client connection: ${e.message}")
                        }
                    }
                }
            }.apply {
                isDaemon = true
                name = "MirroringHttpServerThread"
                start()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start HTTP server on port $port: ${e.message}")
        }
    }

    @Synchronized
    fun stop() {
        if (!isRunning) return
        isRunning = false
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing server socket: ${e.message}")
        }
        serverSocket = null
        serverThread?.interrupt()
        serverThread = null
        Log.i(TAG, "Mirroring HTTP server stopped")
    }

    private fun handleClient(client: Socket) {
        try {
            client.soTimeout = 3000
            val input = BufferedReader(InputStreamReader(client.getInputStream()))
            val output = BufferedOutputStream(client.getOutputStream())

            val requestLine = input.readLine() ?: return
            val parts = requestLine.split(" ")
            val path = if (parts.size > 1) parts[1] else "/"

            // Consume rest of headers
            var headerLine = input.readLine()
            while (!headerLine.isNullOrEmpty()) {
                headerLine = input.readLine()
            }

            if (path == "/" || path == "/stream.jpeg" || path == "/stream.jpg" ||
                path.startsWith("/stream.jpeg") || path.startsWith("/stream.jpg") ||
                path.endsWith(".jpeg") || path.endsWith(".jpg")) {
                serveStreamJpeg(output)
            } else {
                sendNotFound(output)
            }
            output.flush()
        } catch (e: Exception) {
            // Client closed early or timed out; common in fast polling
        } finally {
            try { client.close() } catch (_: Exception) {}
        }
    }

    private fun serveStreamJpeg(output: OutputStream) {
        val file = streamFile
        val bytes = if (file.exists() && file.length() > 0L) {
            try { file.readBytes() } catch (_: Exception) { null }
        } else null

        val finalBytes = bytes ?: generatePlaceholderFrame()
        try {
            val header = "HTTP/1.1 200 OK\r\n" +
                         "Content-Type: image/jpeg\r\n" +
                         "Content-Length: ${finalBytes.size}\r\n" +
                         "Connection: close\r\n" +
                         "Access-Control-Allow-Origin: *\r\n" +
                         "Cache-Control: no-cache, no-store, must-revalidate\r\n" +
                         "Pragma: no-cache\r\n" +
                         "Expires: 0\r\n\r\n"

            output.write(header.toByteArray(Charsets.US_ASCII))
            output.write(finalBytes)
        } catch (e: Exception) {
            // Stream file may be locked during atomic rename; silent catch
        }
    }

    private fun generatePlaceholderFrame(): ByteArray {
        val bmp = android.graphics.Bitmap.createBitmap(800, 360, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmp)
        canvas.drawColor(android.graphics.Color.BLACK)
        val paint = android.graphics.Paint().apply {
            color = android.graphics.Color.WHITE
            textSize = 28f
            textAlign = android.graphics.Paint.Align.CENTER
            isAntiAlias = true
        }
        canvas.drawText("Ampere Nexus SmartRide Connected", 400f, 170f, paint)
        paint.textSize = 18f
        paint.color = android.graphics.Color.CYAN
        canvas.drawText("Screen Mirroring Active • Streaming 800x360", 400f, 210f, paint)
        val stream = ByteArrayOutputStream()
        bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 75, stream)
        bmp.recycle()
        return stream.toByteArray()
    }

    private fun sendNotFound(output: OutputStream) {
        val body = "404 Not Found"
        val header = "HTTP/1.1 404 Not Found\r\n" +
                     "Content-Type: text/plain\r\n" +
                     "Content-Length: ${body.length}\r\n" +
                     "Connection: close\r\n\r\n"
        output.write(header.toByteArray(Charsets.US_ASCII))
        output.write(body.toByteArray(Charsets.US_ASCII))
    }
}
