package com.cunhac.btcradar.data.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

object Net {
    private const val UA =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun getText(url: String): String = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url).header("User-Agent", UA).build()
        client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code} em $url")
            r.body?.string() ?: throw IOException("Resposta vazia em $url")
        }
    }

    suspend fun getJson(url: String): JsonElement = json.parseToJsonElement(getText(url))

    fun parse(text: String): JsonElement = json.parseToJsonElement(text)
}

// Atalhos para ler JSON sem criar classes para cada resposta.
fun JsonObject.str(key: String): String = this[key]?.jsonPrimitive?.contentOrNull ?: ""
fun JsonObject.dbl(key: String): Double = str(key).toDoubleOrNull() ?: 0.0
fun JsonObject.lng(key: String): Long = str(key).toLongOrNull() ?: 0L
fun JsonObject.arr(key: String): JsonArray = this[key]?.jsonArray ?: JsonArray(emptyList())
fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
fun JsonElement.dblAt(i: Int): Double = jsonArray[i].jsonPrimitive.content.toDouble()
fun JsonElement.lngAt(i: Int): Long = jsonArray[i].jsonPrimitive.content.toLong()
val JsonElement.o: JsonObject get() = jsonObject
