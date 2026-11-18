package com.amirzregat.nabbash.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RedactorTest {

    private val redactor = Redactor()

    private fun assertGone(text: String, vararg secrets: String) {
        for (secret in secrets) assertFalse("\"$secret\" survived in: $text", text.contains(secret))
    }

    // region headers

    @Test
    fun `sensitive headers are masked whatever their case`() {
        for (name in listOf("Authorization", "authorization", "COOKIE", "Set-Cookie", "X-Api-Key", "Proxy-Authorization", "x-auth-token")) {
            assertEquals(name, REDACTED, redactor.header(name, "secret-value"))
        }
    }

    @Test
    fun `ordinary headers are kept`() {
        assertEquals("application/json", redactor.header("Content-Type", "application/json"))
        assertEquals("gzip", redactor.header("Accept-Encoding", "gzip"))
    }

    @Test
    fun `extra headers from the configuration are masked`() {
        val custom = Redactor(extraHeaders = listOf("X-Session-Id"))
        assertEquals(REDACTED, custom.header("x-session-id", "abc"))
        assertEquals("abc", redactor.header("x-session-id", "abc"))
    }

    // endregion

    // region keys

    @Test
    fun `sensitive keys are recognised in every spelling`() {
        for (key in listOf("password", "Password", "user_password", "newPassword", "access_token", "refreshToken", "pushToken",
            "api_key", "apiKey", "API-KEY", "client_secret", "otp", "OTP", "pin", "mPin", "cvv", "card_number", "cardNumber",
            "sessionId", "JSESSIONID", "ssn", "private_key")) {
            assertTrue(key, redactor.isSensitiveKey(key))
        }
    }

    @Test
    fun `short keys do not match inside longer words`() {
        for (key in listOf("shipping", "opinion", "option", "spinner", "panel", "company", "page", "name", "id", "amount")) {
            assertFalse(key, redactor.isSensitiveKey(key))
        }
    }

    // endregion

    // region urls

    @Test
    fun `sensitive query parameters are masked and the rest is kept`() {
        val out = redactor.url("https://api.example.com/v1/login?api_key=live_51Habc&page=2&token=xyz987#top")
        assertGone(out, "live_51Habc", "xyz987")
        assertEquals("https://api.example.com/v1/login?api_key=$REDACTED&page=2&token=$REDACTED#top", out)
    }

    @Test
    fun `credentials in the url are dropped`() {
        val out = redactor.url("https://amir:hunter2@api.example.com/v1/me")
        assertEquals("https://api.example.com/v1/me", out)
    }

    @Test
    fun `credentials in the url are dropped even when field redaction is off`() {
        val off = Redactor(redactFields = false)
        val out = off.url("https://amir:hunter2@api.example.com/v1/me?token=abc")
        assertGone(out, "hunter2", "amir:")
        assertTrue(out, out.endsWith("?token=abc"))
    }

    @Test
    fun `url without a query is unchanged`() {
        assertEquals("https://api.example.com/v1/posts/1", redactor.url("https://api.example.com/v1/posts/1"))
    }

    // endregion

    // region bodies

    @Test
    fun `json values under sensitive keys are masked`() {
        val src = """{"user":"amir","password":"hunter2","otp":482913,"remember":true,"shipping":"Amman"}"""
        val out = redactor.body(src, "application/json; charset=utf-8")
        assertGone(out, "hunter2", "482913")
        assertEquals("""{"user":"amir","password":"$REDACTED","otp":"$REDACTED","remember":true,"shipping":"Amman"}""", out)
    }

    @Test
    fun `nested json and arrays are covered`() {
        val src = """{"data":{"items":[{"id":1,"access_token":"tok_AAA"},{"id":2,"access_token":"tok_BBB"}],"credentials":{"user":"u","key":"deep-value"}}}"""
        val out = redactor.body(src, "application/json")
        assertGone(out, "tok_AAA", "tok_BBB", "deep-value")
        assertTrue(out, out.contains("\"id\":1") && out.contains("\"id\":2"))
    }

    @Test
    fun `json null stays null and whitespace is preserved`() {
        val src = "{\n  \"token\" : null,\n  \"password\" : \"p@ss \\\"quoted\\\" zebra\",\n  \"name\" : \"Amir\"\n}"
        val out = redactor.body(src, "application/json")
        assertGone(out, "p@ss", "quoted", "zebra")
        assertTrue(out, out.contains("\"token\" : null"))
        assertTrue(out, out.contains("\"name\" : \"Amir\""))
    }

    @Test
    fun `json is detected without a content type`() {
        val out = redactor.body("""  {"secret":"s3cr3t-value"}""", null)
        assertGone(out, "s3cr3t-value")
    }

    @Test
    fun `json cut at the size limit is still masked`() {
        val out = redactor.body("""{"name":"Amir","password":"hunter2-and-then-the-body-was-cu""", "application/json")
        assertGone(out, "hunter2")
        assertTrue(out, out.contains("Amir"))
    }

    @Test
    fun `json escaped inside a string is masked`() {
        val src = """{"payload":"{\"password\":\"hunter2\",\"city\":\"Amman\"}"}"""
        val out = redactor.body(src, "application/json")
        assertGone(out, "hunter2")
        assertTrue(out, out.contains("Amman"))
    }

    @Test
    fun `form bodies are masked`() {
        val out = redactor.body("username=amir&password=hunter2&grant_type=password&otp=1234", "application/x-www-form-urlencoded")
        assertEquals("username=amir&password=$REDACTED&grant_type=password&otp=$REDACTED", out)
    }

    @Test
    fun `xml elements are masked`() {
        val out = redactor.body("<login><user>amir</user><password>hunter2</password></login>", "application/xml")
        assertGone(out, "hunter2")
        assertTrue(out, out.contains("<user>amir</user>"))
        assertTrue(out, out.contains("<password>$REDACTED</password>"))
    }

    @Test
    fun `bearer and basic credentials in free text are masked`() {
        val out = redactor.text("retrying with Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.payload.sig and Basic dXNlcjpwYXNzd29yZA==")
        assertGone(out, "eyJhbGciOiJIUzI1NiJ9", "dXNlcjpwYXNzd29yZA")
        assertTrue(out, out.contains("Bearer $REDACTED"))
        assertTrue(out, out.contains("Basic $REDACTED"))
    }

    @Test
    fun `a url echoed in a header value or a message is masked like the request url`() {
        val location = redactor.text(redactor.header("Location", "https://api.example.com/posts?api_key=live_51Habc&page=1/101"))
        assertEquals("https://api.example.com/posts?api_key=$REDACTED&page=1/101", location)

        val message = redactor.text("GET https://amir:hunter2@api.example.com/me?access_token=tok_AAA&lang=en failed, retry=2")
        assertGone(message, "hunter2", "amir:", "tok_AAA")
        assertEquals("GET https://api.example.com/me?access_token=$REDACTED&lang=en failed, retry=2", message)

        val cookieLike = redactor.text("sessionid=abc123xyz; Path=/; theme=dark")
        assertEquals("sessionid=$REDACTED; Path=/; theme=dark", cookieLike)
    }

    @Test
    fun `a url inside a json value is masked`() {
        val out = redactor.body("""{"next":"https://api.example.com/items?page=2&token=tok_BBB","count":2}""", "application/json")
        assertGone(out, "tok_BBB")
        assertEquals("""{"next":"https://api.example.com/items?page=2&token=$REDACTED","count":2}""", out)
    }

    @Test
    fun `url credentials in free text are dropped even when field redaction is off`() {
        val off = Redactor(redactFields = false)
        assertEquals("fetch https://db.example.com/x?token=abc", off.text("fetch https://root:toor@db.example.com/x?token=abc"))
    }

    @Test
    fun `ordinary key value text is kept`() {
        val text = "page=2&sort=name; width=320, a = b, mail me@example.com, see https://example.com/@amir"
        assertEquals(text, redactor.text(text))
    }

    @Test
    fun `plain text without secrets is unchanged`() {
        val text = "Loaded 12 accounts for the basic plan in 40 ms"
        assertEquals(text, redactor.text(text))
        assertEquals(text, redactor.body(text, "text/plain"))
    }

    @Test
    fun `extra body keys from the configuration are masked`() {
        val custom = Redactor(extraBodyKeys = listOf("nationalId"))
        val out = custom.body("""{"national_id":"9981012345","name":"Amir"}""", "application/json")
        assertGone(out, "9981012345")
        assertTrue(out, out.contains("Amir"))
    }

    @Test
    fun `switches turn masking off`() {
        val off = Redactor(redactHeaders = false, redactFields = false)
        assertEquals("Bearer abcdefghij", off.header("Authorization", "Bearer abcdefghij"))
        val body = """{"password":"hunter2"}"""
        assertEquals(body, off.body(body, "application/json"))
    }

    @Test
    fun `masking the same text twice changes nothing`() {
        val once = redactor.body("""{"password":"hunter2","list":[1,2,3]}""", "application/json")
        assertEquals(once, redactor.body(once, "application/json"))
    }

    // endregion
}
