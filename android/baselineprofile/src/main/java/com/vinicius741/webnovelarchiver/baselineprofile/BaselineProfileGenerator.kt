package com.vinicius741.webnovelarchiver.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Generates the Baseline Profile that release and beta ship (app/src/release/generated). Run it with
 * `android/gradlew -p android :app:generateReleaseBaselineProfile` on the emulator; it installs the
 * profiling build (`.profiling` application id, its own library) and never touches the debug app.
 *
 * [startup] covers a cold start onto the Library and also feeds the startup profile (dex layout);
 * [libraryToReader] covers the main reading journey.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule
    val rule = BaselineProfileRule()

    // The baselineprofile plugin passes the target app's application id (the `.profiling` one).
    private val targetPackage =
        requireNotNull(InstrumentationRegistry.getArguments().getString("androidx.benchmark.targetPackageName")) {
            "androidx.benchmark.targetPackageName was not passed as an instrumentation argument"
        }

    @Before
    fun seedFixtureLibrary() = FixtureLibrary.prepare(targetPackage)

    @Test
    fun startup() =
        rule.collect(packageName = targetPackage, includeInStartupProfile = true) {
            coldStartToLibrary()
        }

    @Test
    fun libraryToReader() =
        rule.collect(packageName = targetPackage) {
            coldStartToLibrary()
            scrollLibrary()
            openFirstStory()
            openChaptersTab()
            openReaderAndScroll()
        }
}
