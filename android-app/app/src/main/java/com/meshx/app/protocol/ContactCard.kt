package com.meshx.app.protocol

import com.meshx.app.model.MAX_NODE_ID
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Text that goes inside the QR codes the app shows and scans.
 *
 *   meshx://contact?id=2&name=Fatima     a person (their node id and name)
 *   meshx://net?key=secret-key           the network key that all nodes of one mesh share
 */
sealed class QrCard {
    data class Person(val id: Int, val name: String) : QrCard()
    data class Network(val key: String) : QrCard()

    fun encode(): String = when (this) {
        is Person -> "meshx://contact?id=$id&name=${escape(name)}"
        is Network -> "meshx://net?key=${escape(key)}"
    }

    companion object {
        /** Returns null when the text is not a MeshX QR code. */
        fun parse(text: String): QrCard? {
            val trimmed = text.trim()
            if (!trimmed.startsWith("meshx://")) return null
            val kind = trimmed.removePrefix("meshx://").substringBefore('?')
            val query = trimmed.substringAfter('?', "")
            val values = HashMap<String, String>()
            for (pair in query.split('&')) {
                val key = pair.substringBefore('=')
                if (key.isEmpty() || !pair.contains('=')) continue
                values[key] = unescape(pair.substringAfter('=')) ?: return null
            }
            return when (kind) {
                "contact" -> {
                    val id = values["id"]?.toIntOrNull() ?: return null
                    if (id !in 1..MAX_NODE_ID) return null
                    Person(id, Protocol.cleanName(values["name"] ?: ""))
                }
                "net" -> {
                    val key = values["key"] ?: return null
                    if (ConfigKeys.problem(ConfigKeys.NET_KEY, key) != null) return null
                    Network(key)
                }
                else -> null
            }
        }

        private fun escape(value: String): String = URLEncoder.encode(value, "UTF-8")

        private fun unescape(value: String): String? = try {
            URLDecoder.decode(value, "UTF-8")
        } catch (e: IllegalArgumentException) {
            null
        }
    }
}
