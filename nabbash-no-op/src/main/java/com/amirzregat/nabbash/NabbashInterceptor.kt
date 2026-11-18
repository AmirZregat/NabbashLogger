package com.amirzregat.nabbash

import java.io.IOException
import okhttp3.Interceptor
import okhttp3.Response

/** Release-build stand-in: passes every call straight through and records nothing. */
public class NabbashInterceptor : Interceptor {

    @Throws(IOException::class)
    override fun intercept(chain: Interceptor.Chain): Response = chain.proceed(chain.request())
}
