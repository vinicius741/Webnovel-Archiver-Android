package com.vinicius741.webnovelarchiver.simulation

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.vinicius741.webnovelarchiver.app.DevEnvironment
import com.vinicius741.webnovelarchiver.app.WebnovelArchiverApp
import com.vinicius741.webnovelarchiver.feature.browser.CloudflareSolveActivity
import com.vinicius741.webnovelarchiver.feature.browser.SourceAccessRetryCoordinator
import kotlinx.coroutines.launch

/** Emulator runner entry point. It cannot open a URL or operate outside an enabled session. */
class SimulationControlActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!DevEnvironment.requested || intent.getStringExtra("command") != "verify") {
            finish()
            return
        }
        lifecycleScope.launch {
            try {
                val container = (application as WebnovelArchiverApp).awaitContainer()
                container.awaitRepositoryReady()
                if (container.simulation != null) {
                    CloudflareSolveActivity.launch(
                        this@SimulationControlActivity,
                        "${SimulationFixtures.STORY_URL}/chapter/99000101/the-missing-hour",
                        SourceAccessRetryCoordinator.NO_RETRY,
                    )
                }
            } finally {
                finish()
            }
        }
    }
}
