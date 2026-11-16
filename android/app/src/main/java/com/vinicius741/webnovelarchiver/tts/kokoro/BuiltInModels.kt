package com.vinicius741.webnovelarchiver.tts.kokoro

/** How a built-in model reaches the phone. */
internal sealed interface ModelPackage {
    val downloadBytes: Long

    /** Sum of the installed files, for progress and the free-space check. */
    val unpackedBytes: Long

    /** One sherpa-onnx `.tar.bz2` release asset whose top folder is the model's [BuiltInModel.dirName]. */
    data class Archive(
        val url: String,
        override val downloadBytes: Long,
        val sha256: String,
        override val unpackedBytes: Long,
        /** Files an install must contain, relative to the model directory. */
        val requiredFiles: List<String>,
    ) : ModelPackage

    /** Separate files, each pinned by size and SHA-256 and saved at [ModelFile.path]. */
    data class Files(
        val files: List<ModelFile>,
    ) : ModelPackage {
        override val downloadBytes: Long get() = files.sumOf { it.bytes }
        override val unpackedBytes: Long get() = downloadBytes
    }
}

internal data class ModelFile(
    val path: String,
    val url: String,
    val bytes: Long,
    val sha256: String,
)

/** The native library that runs a model. */
internal enum class BuiltInRuntime {
    /** sherpa-onnx's Kokoro support (the AAR). */
    SHERPA_KOKORO,

    /** Gradium's Phonon runtime, `libptts_ffi.so`, through JNA. */
    PHONON,
}

/** One of a built-in model's speakers. [speakerId] is its index in a Kokoro voices file, or Phonon's list position. */
internal data class KokoroVoice(
    val speakerId: Int,
    val code: String,
    val name: String,
    val language: String,
    val model: BuiltInModel = BuiltInModel.KOKORO_V019,
)

/**
 * The voice models the app can download and run itself, each a separate engine in Voice & Speech.
 * [dirName] is the model's folder under `tts_models` and its name on a narration server.
 */
internal enum class BuiltInModel(
    val engineId: String,
    val engineLabel: String,
    /** After a voice's name in lists: "Bella · Kokoro". */
    val family: String,
    val dirName: String,
    val runtime: BuiltInRuntime,
) {
    /** sherpa-onnx's first Kokoro export: the original built-in voice. */
    KOKORO_V019("builtin:kokoro-en-v0_19", "Kokoro (built in)", "Kokoro", "kokoro-en-v0_19", BuiltInRuntime.SHERPA_KOKORO),

    /** Kokoro v1.0 (sherpa-onnx export), American English voices, with its US lexicon in front of espeak-ng. */
    KOKORO_V1(
        "builtin:kokoro-multi-lang-v1_0",
        "Kokoro v1.0 (built in)",
        "Kokoro v1",
        "kokoro-multi-lang-v1_0",
        BuiltInRuntime.SHERPA_KOKORO,
    ),

    /** Gradium's Phonon 31M, full-precision weights. */
    PHONON("builtin:phonon-31m-en", "Phonon (built in)", "Phonon", "phonon-31m-en", BuiltInRuntime.PHONON),
    ;

    /** Prefix of this model's voice identifiers: the model version, so another model cannot pick the wrong speaker. */
    val idPrefix: String get() = "$dirName:"

    val voices: List<KokoroVoice> get() = BuiltInCatalog.voices.getValue(this)

    val defaultVoice: KokoroVoice get() = voices.first { it.code == BuiltInCatalog.defaults.getValue(this) }

    val modelPackage: ModelPackage get() = BuiltInCatalog.packages.getValue(this)

    /** What a clip depends on besides its text. The phone and a server running this model share it. */
    fun voiceKey(voice: KokoroVoice): String =
        when (runtime) {
            BuiltInRuntime.SHERPA_KOKORO -> "$engineId|speaker=${voice.speakerId}|text=${KokoroTextPlanning.VERSION}"
            BuiltInRuntime.PHONON -> "$engineId|voice=${voice.code}|text=${PhononTextPlanning.VERSION}"
        }

    /** The text this model is given for [text]; blank when nothing would be spoken. */
    fun spokenText(text: String): String =
        when (runtime) {
            BuiltInRuntime.SHERPA_KOKORO -> KokoroTextPlanning.spokenText(text)
            BuiltInRuntime.PHONON -> PhononTextPlanning.spokenText(text)
        }

    companion object {
        fun forEngine(enginePackage: String?): BuiltInModel? = entries.firstOrNull { it.engineId == enginePackage }

        fun forIdentifier(identifier: String?): KokoroVoice? {
            if (identifier == null) return null
            val model = entries.firstOrNull { identifier.startsWith(it.idPrefix) } ?: return null
            return model.voices.firstOrNull { model.idPrefix + it.code == identifier }
        }
    }
}

/**
 * Text as Phonon should receive it. Its runtime splits sentences and spells out numbers itself, and
 * reads quotes as part of the delivery, so only what web novels add for emphasis goes: asterisks,
 * which would otherwise be read out or leave a gap.
 */
internal object PhononTextPlanning {
    /** Part of Phonon's cache key: bump it whenever [spokenText] changes. */
    const val VERSION = 1

    private val WHITESPACE = Regex("""\s+""")

    fun spokenText(text: String): String = text.replace('*', ' ').replace(WHITESPACE, " ").trim()
}

private object BuiltInCatalog {
    private const val US = "en-US"
    private const val UK = "en-GB"

    val voices: Map<BuiltInModel, List<KokoroVoice>> =
        mapOf(
            // Speaker order of kokoro-en-v0_19's voices.bin, as documented by sherpa-onnx.
            BuiltInModel.KOKORO_V019 to
                listOf(
                    KokoroVoice(0, "af", "Default (American, female)", US),
                    KokoroVoice(1, "af_bella", "Bella (American, female)", US),
                    KokoroVoice(2, "af_nicole", "Nicole (American, female)", US),
                    KokoroVoice(3, "af_sarah", "Sarah (American, female)", US),
                    KokoroVoice(4, "af_sky", "Sky (American, female)", US),
                    KokoroVoice(5, "am_adam", "Adam (American, male)", US),
                    KokoroVoice(6, "am_michael", "Michael (American, male)", US),
                    KokoroVoice(7, "bf_emma", "Emma (British, female)", UK),
                    KokoroVoice(8, "bf_isabella", "Isabella (British, female)", UK),
                    KokoroVoice(9, "bm_george", "George (British, male)", UK),
                    KokoroVoice(10, "bm_lewis", "Lewis (British, male)", UK),
                ),
            // kokoro-multi-lang-v1_0's American English speakers, by their index in its voices.bin
            // (the model's `speaker2id` metadata). British voices are left out: the app loads the US
            // lexicon, which would give them American pronunciations.
            BuiltInModel.KOKORO_V1 to
                listOf(
                    "af_alloy" to "Alloy",
                    "af_aoede" to "Aoede",
                    "af_bella" to "Bella",
                    "af_heart" to "Heart",
                    "af_jessica" to "Jessica",
                    "af_kore" to "Kore",
                    "af_nicole" to "Nicole",
                    "af_nova" to "Nova",
                    "af_river" to "River",
                    "af_sarah" to "Sarah",
                    "af_sky" to "Sky",
                    "am_adam" to "Adam",
                    "am_echo" to "Echo",
                    "am_eric" to "Eric",
                    "am_fenrir" to "Fenrir",
                    "am_liam" to "Liam",
                    "am_michael" to "Michael",
                    "am_onyx" to "Onyx",
                    "am_puck" to "Puck",
                    "am_santa" to "Santa",
                ).mapIndexed { index, (code, name) ->
                    val gender = if (code.startsWith("af_")) "female" else "male"
                    KokoroVoice(index, code, "$name (American, $gender)", US, BuiltInModel.KOKORO_V1)
                },
            // The owner's picks from Phonon's catalog, named as the files on disk and on servers.
            BuiltInModel.PHONON to
                listOf("Harper", "Everly", "Bryony", "Hadley", "Juniper").mapIndexed { index, name ->
                    KokoroVoice(index, name, "$name (American, female)", US, BuiltInModel.PHONON)
                },
        ).mapValues { (model, list) -> list.map { it.copy(model = model) } }

    val defaults: Map<BuiltInModel, String> =
        mapOf(BuiltInModel.KOKORO_V019 to "af", BuiltInModel.KOKORO_V1 to "af_heart", BuiltInModel.PHONON to "Harper")

    private const val PHONON_MODEL = "https://huggingface.co/gradium/phonon-31m-en/resolve/92741c5b07d085ff840e26fea8ce43110b87852f"
    private const val PHONON_VOICES = "https://huggingface.co/gradium/phonon-voices/resolve/da6c9ec0d47be8df5c87c730a3aa97187f3229c3/voices"
    private const val VOICE_BYTES = 256_136L

    private fun phononVoice(
        name: String,
        id: String,
        sha256: String,
    ) = ModelFile("voices/$name.safetensors", "$PHONON_VOICES/$id.safetensors", VOICE_BYTES, sha256)

    val packages: Map<BuiltInModel, ModelPackage> =
        mapOf(
            BuiltInModel.KOKORO_V019 to
                ModelPackage.Archive(
                    url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/kokoro-en-v0_19.tar.bz2",
                    downloadBytes = 319_625_534L,
                    sha256 = "912804855a04745fa77a30be545b3f9a5d15c4d66db00b88cbcd4921df605ac7",
                    unpackedBytes = 369_315_617L,
                    requiredFiles = listOf("model.onnx", "voices.bin", "tokens.txt", "espeak-ng-data/phontab"),
                ),
            BuiltInModel.KOKORO_V1 to
                ModelPackage.Archive(
                    url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/kokoro-multi-lang-v1_0.tar.bz2",
                    downloadBytes = 349_886_476L,
                    sha256 = "60703707bc05ccd48d60e1eb723f38b07d6e474795a0b6a588d7220400e0f7a4",
                    unpackedBytes = 401_239_297L,
                    requiredFiles = listOf("model.onnx", "voices.bin", "tokens.txt", "lexicon-us-en.txt", "espeak-ng-data/phontab"),
                ),
            BuiltInModel.PHONON to
                ModelPackage.Files(
                    listOf(
                        ModelFile(
                            "config.json",
                            "$PHONON_MODEL/config.json",
                            4_634L,
                            "b5d0f88353638370d94035b7d73d7d6577c0554d78f8d7ecfd40490aae39ae85",
                        ),
                        ModelFile(
                            "tokenizer.json",
                            "$PHONON_MODEL/tokenizer.json",
                            277_967L,
                            "691a00068fa7b989f2f3e5ae5daa2d1a5bb319f2d5360deafcfb19a8bb6e7936",
                        ),
                        ModelFile(
                            "model.safetensors",
                            "$PHONON_MODEL/model.safetensors",
                            102_823_920L,
                            "8a04cf833cfe69b67c2ec968b2d31244655b9accc84ade9c0ebcc176f2074607",
                        ),
                        phononVoice("Harper", "4SZHfMpw-p46Ywgs", "6b3da6e2d796a20c51af892c482bd6509fb88267a221aff83c870843dee2a123"),
                        phononVoice("Everly", "vjFyp2BcCyTlUmYb", "958ea94da14f46dd48c7f64adac11ed2ad2aa45f7ccd165dfebab5272664402e"),
                        phononVoice("Bryony", "gfP44RlHwr2kEwk7", "bcc207a73b7885e45b49ab633d3377a10158292e3a7cef9cd6764ad107f1cb9b"),
                        phononVoice("Hadley", "bXwrkHhURL1VFJdG", "a879938aa9a2df0a8220bdadef88137929f71e887bd91e7db205bd5fa11661d2"),
                        phononVoice("Juniper", "NIWIOv5vTi5eRMj8", "444378eb06b7c492b86e8fc7d13e4db1a3c0cf3e68891a960bf6f0a41a13ef35"),
                    ),
                ),
        )
}
