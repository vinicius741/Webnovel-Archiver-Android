package com.vinicius741.webnovelarchiver.simulation

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import java.io.File

/** Every storage/recovery path sees the session directory, including applicationContext reads. */
internal class SimulationContext(
    base: Context,
    private val sessionId: String,
) : ContextWrapper(base) {
    override fun getApplicationContext(): Context = this

    override fun getFilesDir(): File = File(baseContext.filesDir, "dev_simulation/sessions/$sessionId/files").apply { mkdirs() }

    override fun getCacheDir(): File = File(baseContext.filesDir, "dev_simulation/sessions/$sessionId/cache").apply { mkdirs() }

    override fun getNoBackupFilesDir(): File = File(filesDir, "no_backup").apply { mkdirs() }

    override fun getExternalFilesDir(type: String?): File = File(filesDir, "external/${type.orEmpty()}").apply { mkdirs() }

    override fun getSharedPreferences(
        name: String,
        mode: Int,
    ): SharedPreferences = baseContext.getSharedPreferences("simulation_${sessionId}_$name", mode)
}
