package com.karewinkcloud.agentweb.client.core

import kotlinx.serialization.json.*

val wireJson = Json { ignoreUnknownKeys = true }
fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)?.contentOrNull
fun JsonObject.boolean(key: String): Boolean? = (get(key) as? JsonPrimitive)?.let {
    it.booleanOrNull ?: it.intOrNull?.let { value -> value != 0 }
}
fun JsonObject.long(key: String): Long? = (get(key) as? JsonPrimitive)?.longOrNull
fun JsonObject.obj(key: String): JsonObject? = get(key) as? JsonObject
fun JsonObject.array(key: String): JsonArray = get(key) as? JsonArray ?: JsonArray(emptyList())
fun JsonObject.objects(key: String): List<JsonObject> = array(key).mapNotNull { it as? JsonObject }

data class ClientError(val message: String, val code: String? = null, val retryable: Boolean? = null)

class ApiException(val status: Int, val problem: ClientError, val directSendRejected: Boolean = false) : Exception(problem.message)
class StreamProtocolException(message: String) : Exception(message)

fun parseApiError(status: Int, body: String): ClientError {
    val json = runCatching { wireJson.parseToJsonElement(body) as? JsonObject }.getOrNull()
    val code = json?.string("code") ?: json?.obj("error")?.string("code")
        ?: json?.string("error")?.takeIf { it.matches(Regex("[a-z_]+")) }
    val message = when {
        code in setOf("invalid_protocol_range", "protocol_major_mismatch", "protocol_version_mismatch") ->
            "This server and app use incompatible protocol versions. Update the app or check the server URL."
        status == 401 -> "Your session has ended. Sign in again in Settings."
        status == 403 -> "This account does not have access to this request."
        else -> json?.string("message") ?: json?.obj("error")?.string("message") ?: json?.string("error") ?: "The server returned HTTP $status."
    }
    return ClientError(message, code, json?.boolean("retryable"))
}
