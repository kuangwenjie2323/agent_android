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

const val NOT_SENT_MESSAGE = "The connection broke before the message left the phone. It was not sent; it is back in the box."
const val SERVER_UNREACHABLE_MESSAGE = "Your computer cannot be reached right now: it may be asleep, offline or restarting."
const val OFFLINE_MESSAGE = "You are offline. The message was not sent; it is still in the box."
const val TOO_LARGE_MESSAGE = "This message is too large to send. Send fewer or smaller pictures or files."

private val UNREACHABLE_STATUSES = setOf(502, 503, 504, 520, 521, 522, 523, 524, 525, 526, 530)

/** A failure before any byte of a request left the phone: the server cannot have received it. */
fun Throwable.failedBeforeSending(): Boolean = this is java.net.UnknownHostException || this is javax.net.ssl.SSLHandshakeException ||
    this is java.net.ConnectException || this is java.net.NoRouteToHostException ||
    (this is java.net.SocketTimeoutException && message?.contains("connect", ignoreCase = true) == true)

fun parseApiError(status: Int, body: String): ClientError {
    val json = runCatching { wireJson.parseToJsonElement(body) as? JsonObject }.getOrNull()
    val code = json?.string("code") ?: json?.obj("error")?.string("code")
        ?: json?.string("error")?.takeIf { it.matches(Regex("[a-z_]+")) }
    val message = when {
        code in setOf("invalid_protocol_range", "protocol_major_mismatch", "protocol_version_mismatch") ->
            "This server and app use incompatible protocol versions. Update the app or check the server URL."
        status == 401 -> "Your session has ended. Sign in again in Settings."
        status == 403 -> "This account does not have access to this request."
        status == 413 -> TOO_LARGE_MESSAGE
        // Cloudflare's own errors when the tunnel to the home computer is down (530) or the origin fails.
        status in UNREACHABLE_STATUSES && json?.string("code") == null -> SERVER_UNREACHABLE_MESSAGE
        else -> json?.string("message") ?: json?.obj("error")?.string("message") ?: json?.string("error") ?: "The server returned HTTP $status."
    }
    return ClientError(message, code, json?.boolean("retryable"))
}
