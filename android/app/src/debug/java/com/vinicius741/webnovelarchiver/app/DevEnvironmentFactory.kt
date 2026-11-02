package com.vinicius741.webnovelarchiver.app

import android.content.Context
import com.vinicius741.webnovelarchiver.simulation.OfflineSimulation
import com.vinicius741.webnovelarchiver.simulation.SimulationConfig
import java.io.File

internal object DevEnvironmentFactory {
    fun requested(context: Context): Boolean = File(context.filesDir, "dev_simulation/enabled").exists()

    fun create(context: Context): DevEnvironment? {
        if (!DevEnvironment.requested) return null
        try {
            val config =
                File(context.filesDir, "dev_simulation/session.json").reader().use { input ->
                    context.assets
                        .open("simulation/catalog.json")
                        .reader()
                        .use { catalog -> SimulationConfig.parse(input, catalog) }
                }
            return OfflineSimulation(context, config)
        } catch (error: Exception) {
            com.vinicius741.webnovelarchiver.data.storage.AtomicFileWrites.writeText(
                File(context.filesDir, "dev_simulation/startup_error.json"),
                "{\"phase\":\"failed\",\"error\":\"invalid_simulation_session\"}",
            )
            throw IllegalStateException(
                "Simulation could not start. External access stays disabled. Run scripts/simulate.py stop.",
                error,
            )
        }
    }
}
