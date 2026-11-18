package com.amirzregat.nabbashlogger

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.amirzregat.nabbash.Nabbash
import com.amirzregat.nabbash.NabbashInterceptor
import com.amirzregat.nabbash.NabbashLevel
import com.amirzregat.nabbashlogger.ui.theme.NabbashLoggerTheme
import java.io.IOException
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/** The whole integration: one interceptor on the app's OkHttp client. */
private val client: OkHttpClient = OkHttpClient.Builder()
    .addInterceptor(NabbashInterceptor())
    .build()

private const val API = "https://jsonplaceholder.typicode.com"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            NabbashLoggerTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    SampleScreen(Modifier.padding(innerPadding))
                }
            }
        }
    }
}

@Composable
fun SampleScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Nabbash sample", style = MaterialTheme.typography.headlineSmall)
        Text(
            if (Nabbash.isEnabled) {
                "Debug build: Nabbash is recording. Make a few calls, then shake the device or tap Open console."
            } else {
                "Release build: Nabbash is the no-op artifact. Nothing is recorded and the console does not exist."
            },
            style = MaterialTheme.typography.bodyMedium,
        )

        SampleButton("GET a post") { send(Request.Builder().url("$API/posts/1").build()) }
        SampleButton("POST a login (secrets get redacted)") {
            val body = """{"username":"amir","password":"hunter2","otp":"493201","device":{"name":"Pixel","pushToken":"abc123"}}"""
            send(
                Request.Builder()
                    .url("$API/posts?api_key=live_51Habc&page=1")
                    .header("Authorization", "Bearer eyJhbGciOiJIUzI1NiJ9.sample.signature")
                    .header("X-Session-Id", "s-88213")
                    .post(body.toRequestBody("application/json".toMediaType()))
                    .build(),
            )
        }
        SampleButton("GET a missing page (404)") { send(Request.Builder().url("$API/nope/404").build()) }
        SampleButton("Call a host that does not exist") { send(Request.Builder().url("https://nabbash.invalid/ping").build()) }
        SampleButton("Log a message") {
            Nabbash.log("Cart updated: 3 items, total 42.50", label = "Cart", level = NabbashLevel.INFO)
        }
        SampleButton("Log an error with a stack trace") {
            Nabbash.log(
                "Could not parse the profile response",
                label = "Profile",
                level = NabbashLevel.ERROR,
                throwable = IllegalStateException("Expected BEGIN_OBJECT but was STRING"),
            )
        }

        Button(onClick = { Nabbash.open(context) }, modifier = Modifier.fillMaxWidth()) { Text("Open console") }
    }
}

@Composable
private fun SampleButton(text: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) { Text(text) }
}

private fun send(request: Request) {
    client.newCall(request).enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) = Unit
        override fun onResponse(call: Call, response: Response) = response.close()
    })
}
