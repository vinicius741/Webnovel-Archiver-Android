package com.vinicius741.webnovelarchiver.core

object FileMimeTypes {
    fun forFilename(filename: String): String {
        return when (filename.substringAfterLast('.', missingDelimiterValue = "").lowercase()) {
            "epub" -> "application/epub+zip"
            "json" -> "application/json"
            "zip" -> "application/zip"
            else -> "*/*"
        }
    }
}
