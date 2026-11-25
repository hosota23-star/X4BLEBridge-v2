package com.x4tether.bridge

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.util.Locale
import java.util.zip.Deflater
import java.util.zip.GZIPInputStream

object HttpProxyEngine {

    data class ProxyResponse(
        val httpStatus: Int,
        val payload: ByteArray,
        val rawLength: Int,
        val isZlib: Boolean,
        val isError: Boolean,
        val hostSummary: String
    )

    fun executeJsonRequest(reqJson: String, isCancelled: () -> Boolean = { false }): ProxyResponse {
        val obj = try {
            JSONObject(reqJson)
        } catch (e: Exception) {
            return errorResp("Invalid request JSON: ${e.message}", "invalid-json")
        }

        val initialUrl = obj.optString("u", "").trim()
        if (initialUrl.isEmpty()) {
            return errorResp("Missing URL", "empty-url")
        }

        val method = obj.optString("m", "GET").trim().uppercase(Locale.US)
        if (method != "GET" && method != "POST") {
            return errorResp("Unsupported method: $method", "method")
        }

        val wantZlib = obj.optInt("z", 1) != 0
        val timeoutMs = obj.optInt("t", 25000).coerceIn(5000, 45000)
        val reqBody = if (obj.has("b") && !obj.isNull("b")) obj.optString("b", "") else ""
        val customHeaders = mutableMapOf<String, String>()
        val hdrObj = obj.optJSONObject("h")
        if (hdrObj != null) {
            val keys = hdrObj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val v = hdrObj.optString(k, "")
                if (k.isNotBlank() && v.isNotBlank()) {
                    customHeaders[k] = v
                }
            }
        }

        var curUrl = initialUrl
        var curMethod = method
        var curBody: ByteArray? = if (method == "POST" && reqBody.isNotEmpty()) reqBody.toByteArray(Charsets.UTF_8) else null
        var hostLabel = safeHost(curUrl)

        try {
            for (hop in 0..6) {
                if (isCancelled()) {
                    return errorResp("Cancelled", hostLabel)
                }
                val parsed = URL(curUrl)
                validatePublicTarget(parsed)
                hostLabel = parsed.host ?: hostLabel

                val conn = (parsed.openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = false
                    requestMethod = curMethod
                    connectTimeout = (timeoutMs / 2).coerceIn(5000, 15000)
                    readTimeout = timeoutMs
                    useCaches = false
                    setRequestProperty("Accept-Encoding", "gzip")
                    setRequestProperty("User-Agent", "X4BLEBridge/2.0 (Android; XTEINK X4 Pro)")
                    for ((hk, hv) in customHeaders) {
                        setRequestProperty(hk, hv)
                    }
                }

                try {
                    if (curMethod == "POST") {
                        conn.doOutput = true
                        val outBytes = curBody ?: ByteArray(0)
                        if (!customHeaders.keys.any { it.equals("Content-Type", ignoreCase = true) }) {
                            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                        }
                        conn.setFixedLengthStreamingMode(outBytes.size)
                        conn.outputStream.use { os ->
                            os.write(outBytes)
                            os.flush()
                        }
                    }

                    val code = conn.responseCode
                    if (code in intArrayOf(301, 302, 303, 307, 308)) {
                        val loc = conn.getHeaderField("Location")?.trim()
                        if (!loc.isNullOrEmpty() && hop < 6) {
                            curUrl = URL(parsed, loc).toString()
                            if (code != 307 && code != 308) {
                                curMethod = "GET"
                                curBody = null
                            }
                            continue
                        }
                    }

                    val rawStream: InputStream? = try {
                        if (code >= 400) (conn.errorStream ?: conn.inputStream) else conn.inputStream
                    } catch (_: Exception) {
                        conn.errorStream
                    }

                    val rawBytes = if (rawStream != null) {
                        val encoding = conn.contentEncoding?.lowercase(Locale.US) ?: ""
                        val decodedStream = if (encoding.contains("gzip")) GZIPInputStream(rawStream) else rawStream
                        decodedStream.use { readBounded(it, BleConstants.MAX_HTTP_BODY_BYTES, isCancelled) }
                    } else {
                        ByteArray(0)
                    }

                    if (isCancelled()) {
                        return errorResp("Cancelled", hostLabel)
                    }

                    if (wantZlib && rawBytes.size >= 140) {
                        val compressed = compressZlib(rawBytes)
                        if (compressed.size + 8 < rawBytes.size) {
                            return ProxyResponse(
                                httpStatus = code.coerceIn(0, 65535),
                                payload = compressed,
                                rawLength = rawBytes.size,
                                isZlib = true,
                                isError = false,
                                hostSummary = hostLabel
                            )
                        }
                    }

                    return ProxyResponse(
                        httpStatus = code.coerceIn(0, 65535),
                        payload = rawBytes,
                        rawLength = rawBytes.size,
                        isZlib = false,
                        isError = false,
                        hostSummary = hostLabel
                    )
                } finally {
                    conn.disconnect()
                }
            }
            return errorResp("Too many redirects", hostLabel)
        } catch (e: SecurityException) {
            return errorResp("Blocked URL: ${e.message}", hostLabel)
        } catch (e: Exception) {
            val msg = "${e.javaClass.simpleName}: ${e.message ?: "network error"}"
            return errorResp(msg.take(180), hostLabel)
        }
    }

    private fun validatePublicTarget(url: URL) {
        val proto = url.protocol.lowercase(Locale.US)
        if (proto != "https" && proto != "http") {
            throw SecurityException("Only http/https allowed")
        }
        val host = url.host ?: throw SecurityException("Missing host")
        val addrs = InetAddress.getAllByName(host)
        if (addrs.isEmpty()) throw SecurityException("DNS resolution empty")
        for (addr in addrs) {
            if (addr.isLoopbackAddress ||
                addr.isSiteLocalAddress ||
                addr.isLinkLocalAddress ||
                addr.isAnyLocalAddress ||
                addr.isMulticastAddress
            ) {
                throw SecurityException("Private/local address forbidden")
            }
        }
    }

    private fun readBounded(ins: InputStream, maxBytes: Int, isCancelled: () -> Boolean): ByteArray {
        val bos = ByteArrayOutputStream(4096)
        val buf = ByteArray(4096)
        var total = 0
        while (true) {
            if (isCancelled()) break
            val n = ins.read(buf)
            if (n < 0) break
            if (n == 0) continue
            val allowed = (maxBytes - total).coerceAtLeast(0)
            if (allowed == 0) break
            val toWrite = n.coerceAtMost(allowed)
            bos.write(buf, 0, toWrite)
            total += toWrite
            if (total >= maxBytes) break
        }
        return bos.toByteArray()
    }

    private fun compressZlib(input: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.BEST_COMPRESSION, false) // RFC 1950 zlib stream
        return try {
            deflater.setInput(input)
            deflater.finish()
            val bos = ByteArrayOutputStream(input.size / 2)
            val buf = ByteArray(2048)
            while (!deflater.finished()) {
                val count = deflater.deflate(buf)
                if (count <= 0) break
                bos.write(buf, 0, count)
            }
            bos.toByteArray()
        } finally {
            deflater.end()
        }
    }

    private fun safeHost(u: String): String {
        return try {
            URL(u).host ?: "unknown"
        } catch (_: Exception) {
            "unknown"
        }
    }

    private fun errorResp(message: String, host: String): ProxyResponse {
        val bytes = message.toByteArray(Charsets.UTF_8)
        return ProxyResponse(
            httpStatus = 0,
            payload = bytes,
            rawLength = bytes.size,
            isZlib = false,
            isError = true,
            hostSummary = host
        )
    }
}
