package com.vinicius741.webnovelarchiver.tts.kokoro

/**
 * The built-in models' installers and loaded runtimes, one of each per [BuiltInModel]; `AppContainer`
 * owns the one instance. A model loads only while something speaks with it, so having several
 * installed costs disk, not memory.
 */
class BuiltInEngines internal constructor(
    private val installers: Map<BuiltInModel, KokoroModelInstaller>,
) {
    private val runtimes: Map<BuiltInModel, KokoroRuntime> =
        BuiltInModel.entries.associateWith { model -> KokoroRuntime({ installers[model]?.isInstalled == true }, model) }

    internal fun runtime(model: BuiltInModel): KokoroRuntime = runtimes.getValue(model)

    internal fun installer(model: BuiltInModel): KokoroModelInstaller? = installers[model]

    internal fun isInstalled(model: BuiltInModel): Boolean = installers[model]?.isInstalled == true

    /** Engine ids of the built-in models ready to speak, for [com.vinicius741.webnovelarchiver.tts.TtsEngineCatalog]. */
    internal val installedEngines: Set<String>
        get() =
            BuiltInModel.entries
                .filter(::isInstalled)
                .map { it.engineId }
                .toSet()

    companion object {
        /** No model installed: tests and callers that never speak with a built-in voice. */
        fun none(): BuiltInEngines = BuiltInEngines(emptyMap())
    }
}
