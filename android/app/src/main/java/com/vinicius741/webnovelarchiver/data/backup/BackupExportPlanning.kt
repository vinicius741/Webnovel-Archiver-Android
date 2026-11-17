package com.vinicius741.webnovelarchiver.data.backup

object BackupExportPlanning {
    fun validateFullBackup(librarySize: Int): String? {
        if (librarySize == 0) return "Your library is empty"
        return null
    }
}
