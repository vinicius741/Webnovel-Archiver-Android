package com.vinicius741.webnovelarchiver.data.backup

object FileMimeTypes {
    fun forFilename(filename: String): String =
        when (filename.substringAfterLast('.', missingDelimiterValue = "").lowercase()) {
            "epub" -> "application/epub+zip"
            "json" -> "application/json"
            "zip" -> "application/zip"
            else -> "*/*"
        }
}
