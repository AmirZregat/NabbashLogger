package com.amirzregat.nabbash.internal

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.GzipSink
import okio.buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpCaptureTest {

    private val json = "application/json; charset=utf-8".toMediaType()
    private val get = Request.Builder().url("https://api.example.com/v1/items").build()

    private fun response(request: Request = get, code: Int = 200) =
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("OK")

    @Test
    fun `request body is captured as text`() {
        val request = Request.Builder().url("https://api.example.com/v1/login")
            .post("""{"user":"amir"}""".toRequestBody(json)).build()
        val captured = HttpCapture.request(request, 1000)
        assertEquals("""{"user":"amir"}""", captured.text)
        assertEquals(15L, captured.size)
        assertEquals("application/json; charset=utf-8", captured.contentType)
    }

    @Test
    fun `request without a body captures nothing`() {
        val captured = HttpCapture.request(get, 1000)
        assertNull(captured.text)
        assertNull(captured.size)
    }

    @Test
    fun `binary and multipart request bodies are described, not stored`() {
        val png = Request.Builder().url("https://api.example.com/upload")
            .post(ByteArray(2048) { it.toByte() }.toRequestBody("image/png".toMediaType())).build()
        val binary = HttpCapture.request(png, 1000)
        assertTrue(binary.text, binary.text!!.startsWith("[body not captured: binary, image/png"))
        assertEquals(2048L, binary.size)

        val form = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("password", "hunter2").addFormDataPart("name", "amir").build()
        val multipart = HttpCapture.request(Request.Builder().url("https://api.example.com/form").post(form).build(), 1000)
        assertTrue(multipart.text, multipart.text!!.startsWith("[body not captured: multipart, 2 parts"))
        assertTrue(multipart.text, !multipart.text!!.contains("hunter2"))
    }

    @Test
    fun `request body longer than the limit is cut just past it`() {
        val request = Request.Builder().url("https://api.example.com/v1/big")
            .post("x".repeat(5000).toRequestBody("text/plain".toMediaType())).build()
        val captured = HttpCapture.request(request, 100)
        assertEquals(101, captured.text!!.length)
        assertEquals(5000L, captured.size)
    }

    @Test
    fun `response body is captured and the app can still read it`() {
        val response = response().body("""{"ok":true}""".toResponseBody(json)).build()
        val captured = HttpCapture.response(response, 1000)
        assertEquals("""{"ok":true}""", captured.text)
        assertEquals(11L, captured.size)
        assertEquals("""{"ok":true}""", response.body!!.string())
    }

    @Test
    fun `gzip response is decoded`() {
        val text = """{"items":[1,2,3],"note":"compressed"}"""
        val zipped = Buffer()
        GzipSink(zipped).buffer().use { it.writeUtf8(text) }
        val response = response().header("Content-Encoding", "gzip")
            .body(zipped.readByteString().toResponseBody(json)).build()
        assertEquals(text, HttpCapture.response(response, 1000).text)
    }

    @Test
    fun `binary and streaming responses are described, not stored`() {
        val image = response().body(ByteArray(512).toResponseBody("image/jpeg".toMediaType())).build()
        assertTrue(HttpCapture.response(image, 1000).text!!.startsWith("[body not captured: binary, image/jpeg"))

        val stream = response().body("data: 1\n\n".toResponseBody("text/event-stream".toMediaType())).build()
        assertTrue(HttpCapture.response(stream, 1000).text!!.startsWith("[body not captured: event stream"))

        val brotli = response().header("Content-Encoding", "br").body("xx".toResponseBody(json)).build()
        assertTrue(HttpCapture.response(brotli, 1000).text!!.startsWith("[body not captured: encoded with br"))
    }

    @Test
    fun `untyped binary response is not stored as text`() {
        val response = response().body(byteArrayOf(0, 1, 2, 3, -1, -2, 0, 7).toResponseBody(null)).build()
        assertTrue(HttpCapture.response(response, 1000).text!!.startsWith("[body not captured: binary"))
    }

    @Test
    fun `responses that cannot have a body capture nothing`() {
        val noContent = response(code = 204).body("".toResponseBody(json)).build()
        assertNull(HttpCapture.response(noContent, 1000).text)
        val head = response(Request.Builder().url("https://api.example.com/x").head().build())
            .body("".toResponseBody(json)).build()
        assertNull(HttpCapture.response(head, 1000).text)
    }

    @Test
    fun `long response is cut just past the limit and stays readable`() {
        val long = "y".repeat(5000)
        val response = response().body(long.toResponseBody("text/plain".toMediaType())).build()
        assertEquals(101, HttpCapture.response(response, 100).text!!.length)
        assertEquals(long, response.body!!.string())
    }

    @Test
    fun `headers keep duplicates in order`() {
        val request = Request.Builder().url("https://api.example.com/")
            .addHeader("Accept", "a").addHeader("Accept", "b").addHeader("X-Trace", "t").build()
        assertEquals(listOf("Accept" to "a", "Accept" to "b", "X-Trace" to "t"), HttpCapture.headers(request.headers))
    }

    @Test
    fun `error description names the exception`() {
        assertEquals("java.net.UnknownHostException: nabbash.invalid", HttpCapture.describe(java.net.UnknownHostException("nabbash.invalid")))
    }
}
