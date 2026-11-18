package com.amirzregat.nabbash

import android.util.Log
import com.amirzregat.nabbash.internal.HttpCapture
import com.amirzregat.nabbash.internal.NabbashRuntime
import java.io.IOException
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Records every call made through an OkHttp client, which covers Retrofit as well:
 *
 * ```
 * OkHttpClient.Builder()
 *     .addInterceptor(NabbashInterceptor())
 *     .build()
 * ```
 *
 * The request and response are passed through untouched. Recording can never fail a
 * call: if capturing goes wrong the call simply continues without being logged.
 * In a build where Nabbash is disabled this interceptor does nothing at all.
 */
public class NabbashInterceptor : Interceptor {

    @Throws(IOException::class)
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val core = NabbashRuntime.core ?: return chain.proceed(request)

        val startedAt = System.currentTimeMillis()
        val startNanos = System.nanoTime()
        val max = core.effective.maxBodyChars
        val sent = quietly { HttpCapture.request(request, max) }

        val response = try {
            chain.proceed(request)
        } catch (failure: Throwable) {
            quietly {
                core.recordNetwork(
                    time = startedAt,
                    method = request.method,
                    url = request.url.toString(),
                    requestHeaders = HttpCapture.headers(request.headers),
                    requestBody = sent?.text,
                    requestSize = sent?.size,
                    status = null,
                    responseHeaders = emptyList(),
                    responseBody = null,
                    responseSize = null,
                    durationMs = elapsedMs(startNanos),
                    error = HttpCapture.describe(failure),
                    requestContentType = sent?.contentType,
                )
            }
            throw failure
        }

        quietly {
            val received = try {
                HttpCapture.response(response, max)
            } catch (e: IOException) {
                HttpCapture.Captured("[body could not be read: ${HttpCapture.describe(e)}]", null, null)
            }
            core.recordNetwork(
                time = startedAt,
                method = request.method,
                url = request.url.toString(),
                requestHeaders = HttpCapture.headers(request.headers),
                requestBody = sent?.text,
                requestSize = sent?.size,
                status = response.code,
                responseHeaders = HttpCapture.headers(response.headers),
                responseBody = received.text,
                responseSize = received.size,
                durationMs = elapsedMs(startNanos),
                error = null,
                requestContentType = sent?.contentType,
                responseContentType = received.contentType,
            )
        }
        return response
    }

    private fun elapsedMs(startNanos: Long): Long = (System.nanoTime() - startNanos) / 1_000_000L

    private inline fun <T> quietly(block: () -> T): T? =
        try {
            block()
        } catch (t: Throwable) {
            Log.w("Nabbash", "capture skipped", t)
            null
        }
}
