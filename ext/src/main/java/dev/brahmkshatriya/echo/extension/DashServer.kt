package dev.brahmkshatriya.echo.extension

import kotlinx.coroutines.runBlocking
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

class DashServer(
    val fetch: suspend (id: String, quality: String) -> String,
) {
    private var currentPort: Int? = null
    private var serverSocket: ServerSocket? = null
    private val clientThreads = mutableListOf<Thread>()
    private val manifestCache = ConcurrentHashMap<String, CacheEntry>()


    fun start(port: Int) {
        if (currentPort == port) return
        stop()
        currentPort = port
        val newSocket = ServerSocket(port)
        serverSocket = newSocket
        println("Socket server running on http://localhost:$port")

        val acceptThread = Thread {
            try {
                while (!newSocket.isClosed) {
                    val client = newSocket.accept()
                    handleClient(client)
                }
            } catch (e: Exception) {
                if (!newSocket.isClosed) e.printStackTrace()
            }
        }

        acceptThread.isDaemon = true
        acceptThread.start()
        clientThreads.add(acceptThread)
    }

    fun stop() {
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }

        serverSocket = null
        currentPort = null

        clientThreads.forEach {
            try {
                it.interrupt()
            } catch (_: Exception) {
            }
        }
        clientThreads.clear()

        println("DashServer stopped.")
    }

    private fun handleClient(socket: Socket) {
        val thread = Thread {
            try {
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                val writer = PrintWriter(socket.getOutputStream())

                val requestLine = reader.readLine() ?: return@Thread
                val parts = requestLine.split(" ")

                if (parts.size < 2) {
                    sendResponse(writer, 400, "Bad Request")
                    socket.close()
                    return@Thread
                }

                val pathWithQuery = parts[1]
                val uri = URI("http://localhost$pathWithQuery")

                if (uri.path != "/manifest") {
                    sendResponse(writer, 404, "Not Found")
                    socket.close()
                    return@Thread
                }

                runBlocking {
                    runCatching {
                        handleManifestRequest(uri, writer)
                    }.onFailure { ex ->
                        ex.printStackTrace()
                        sendResponse(writer, 500, "Internal Error\n${ex.stackTraceToString()}")
                    }
                }

                socket.close()
            } catch (_: Exception) {
                try {
                    socket.close()
                } catch (_: Exception) {
                }
            }
        }

        thread.isDaemon = true
        thread.start()
        clientThreads.add(thread)
    }

    private suspend fun handleManifestRequest(uri: URI, writer: PrintWriter) {
        val queryParams = parseQuery(uri)
        val id = queryParams["id"]
        val quality = queryParams["quality"]

        if (id == null || quality == null) {
            sendResponse(writer, 400, "Missing id or quality")
            return
        }

        val cacheKey = "$id|$quality"
        val now = System.currentTimeMillis()

        manifestCache[cacheKey]?.let { cached ->
            if (now - cached.timestamp < CACHE_TTL_MS) {
                println("CACHE HIT for $cacheKey")
                sendResponse(writer, 200, cached.manifest)
                return
            }
        }

        println("CACHE MISS for $cacheKey — calling remote server")

        val manifest = fetch(id, quality)
        manifestCache[cacheKey] = CacheEntry(manifest, now)
        sendResponse(writer, 200, manifest)
    }

    private fun sendResponse(writer: PrintWriter, code: Int, body: String) {
        writer.apply {
            println("HTTP/1.1 $code OK")
            println("Content-Type: text/plain")
            println("Content-Length: ${body.toByteArray().size}")
            println()
            print(body)
            flush()
        }
    }

    private fun parseQuery(uri: URI): Map<String, String> {
        val query = uri.query ?: return emptyMap()
        return query.split("&")
            .mapNotNull {
                val p = it.split("=")
                if (p.size == 2) p[0] to p[1] else null
            }.toMap()
    }

    data class CacheEntry(
        val manifest: String,
        val timestamp: Long,
    )

    companion object {
        const val CACHE_TTL_MS = 30 * 60 * 1000L
    }
}