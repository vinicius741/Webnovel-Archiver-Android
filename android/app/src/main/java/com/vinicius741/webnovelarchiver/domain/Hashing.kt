package com.vinicius741.webnovelarchiver.domain

import java.io.File
import java.security.MessageDigest

/** One SHA-256 hex encoder for content addressing, stable keys, and verification reports. */
fun sha256Hex(bytes: ByteArray): String =
    MessageDigest
        .getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

fun sha256Hex(text: String): String = sha256Hex(text.encodeToByteArray())

/** Streaming variant for large files; identical result to hashing the whole file at once. */
fun sha256Hex(file: File): String =
    MessageDigest
        .getInstance("SHA-256")
        .apply {
            file.inputStream().use { input ->
                val buffer = ByteArray(8192)
                var count = input.read(buffer)
                while (count >= 0) {
                    update(buffer, 0, count)
                    count = input.read(buffer)
                }
            }
        }.digest()
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
