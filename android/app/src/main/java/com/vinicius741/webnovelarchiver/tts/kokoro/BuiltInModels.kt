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
 * The voice models the app can download and run itself, each a separate engine in Settings › Listening.
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

    /**
     * No longer offered as an engine (settings move to [replacement]). The model stays defined so
     * its generated audio, queued jobs, an installed copy (removable in Settings) and servers work.
     */
    val retired: Boolean get() = this == KOKORO_V019

    /** The model a retired one's settings move to. */
    val replacement: BuiltInModel? get() = if (this == KOKORO_V019) KOKORO_V1 else null

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
 * which would otherwise be read out or leave a gap, and accents ([LatinAccents]).
 */
internal object PhononTextPlanning {
    /** Part of Phonon's cache key: bump it whenever [spokenText] changes. */
    const val VERSION = 2

    private val WHITESPACE = Regex("""\s+""")

    fun spokenText(text: String): String =
        LatinAccents
            .fold(text)
            .replace('*', ' ')
            .replace(WHITESPACE, " ")
            .trim()
}

private object BuiltInCatalog {
    private const val US = "en-US"
    private const val UK = "en-GB"

    /**
     * One of Phonon's English voices: [code] is its file name on disk and on servers (the catalog's
     * name without spaces), [id] its file in the catalog. Keep `scripts/narration-server/setup.sh`'s
     * list the same.
     */
    private class PhononVoice(
        val code: String,
        val label: String,
        val language: String,
        val id: String,
        val sha256: String,
    ) {
        val file: ModelFile get() = ModelFile("voices/$code.safetensors", "$VOICES_REPO/$id.safetensors", VOICE_BYTES, sha256)
    }

    /**
     * The English voices of Phonon's catalog: the owner's first five picks, then American, British and
     * character voices. Their order is only the list's; a saved voice is found by its code.
     */
    private val PHONON_VOICES =
        listOf(
            PhononVoice(
                "Harper",
                "Harper (American, female)",
                US,
                "4SZHfMpw-p46Ywgs",
                "6b3da6e2d796a20c51af892c482bd6509fb88267a221aff83c870843dee2a123",
            ),
            PhononVoice(
                "Everly",
                "Everly (American, female)",
                US,
                "vjFyp2BcCyTlUmYb",
                "958ea94da14f46dd48c7f64adac11ed2ad2aa45f7ccd165dfebab5272664402e",
            ),
            PhononVoice(
                "Bryony",
                "Bryony (British, female)",
                UK,
                "gfP44RlHwr2kEwk7",
                "bcc207a73b7885e45b49ab633d3377a10158292e3a7cef9cd6764ad107f1cb9b",
            ),
            PhononVoice(
                "Hadley",
                "Hadley (American, female)",
                US,
                "bXwrkHhURL1VFJdG",
                "a879938aa9a2df0a8220bdadef88137929f71e887bd91e7db205bd5fa11661d2",
            ),
            PhononVoice(
                "Juniper",
                "Juniper (American, female)",
                US,
                "NIWIOv5vTi5eRMj8",
                "444378eb06b7c492b86e8fc7d13e4db1a3c0cf3e68891a960bf6f0a41a13ef35",
            ),
            PhononVoice(
                "Zoey",
                "Zoey (American, female)",
                US,
                "NbpkqMVS3CJeq2j8",
                "97c7f8c253413574406ab80dd23f49c3ebb2517163b00a9bf86b9c4cf43e7e23",
            ),
            PhononVoice(
                "Delia",
                "Delia (American, female)",
                US,
                "nGzMHMPAWGaJFHOo",
                "a0c3dcb91b2247eca56536be9ae8e8becc1c807b1136c5fe101552d6a155b24a",
            ),
            PhononVoice(
                "Easton",
                "Easton (American, male)",
                US,
                "LMDIYE0Oa377Qh6Z",
                "45c582673cb20edb04ddcca2b5a8865ddfb0a82599efadb269021e87d20fbdd4",
            ),
            PhononVoice(
                "Micah",
                "Micah (American, male)",
                US,
                "40QrMVgAxFiUnP9f",
                "0de1098d1051686a138d068e83ff09deb5053328bc74ec9541a1fdb90bfc24da",
            ),
            PhononVoice(
                "Miles",
                "Miles (American, male)",
                US,
                "d9XCyhjo4JhkHF1d",
                "eab20e4f159b48cfbc01b1885804e24f681f80e7a64f909182763a4e3361bee8",
            ),
            PhononVoice(
                "Preston",
                "Preston (American, male)",
                US,
                "qIxlZ7Ei8KvWlyQH",
                "6e04b84d3ae8751b22bca0a4f63936f9cb648f4f2182b2d0788071ea1c0b68a1",
            ),
            PhononVoice(
                "Russell",
                "Russell (American, male)",
                US,
                "_6Aslh2DxfmnRLmP",
                "beae9e4030efce0a935fcb0f807dc0784d598bcaa87ad4aacb047436b1eaa411",
            ),
            PhononVoice(
                "Trevor",
                "Trevor (American, male)",
                US,
                "0zLApsZfVLLNaEGl",
                "dc1341c8937af5534e6bbcc5c0fc5e7c10312229b59061228074f93683fe2261",
            ),
            PhononVoice(
                "Elodie-Rose",
                "Elodie-Rose (British, female)",
                UK,
                "y9bQqIDnyxwqK01t",
                "21eade9d83b2e66b9364e1b6cd999a3a6704c000a71f27f34e05ace5cdfd58bb",
            ),
            PhononVoice(
                "Freya",
                "Freya (British, female)",
                UK,
                "GgfEkEJtxZR7gnpy",
                "eb3cd47418349e618408c16bea7153988b3ad04b41a6033039dd474a9a4dcd23",
            ),
            PhononVoice(
                "Imogen",
                "Imogen (British, female)",
                UK,
                "gDT1nz7Ie36ZhL-C",
                "1980ab3c35fa98a25b5f3b5e643fd852e14ca8a12a4a26b06aaf1abf2a8c68b1",
            ),
            PhononVoice(
                "Lottie",
                "Lottie (British, female)",
                UK,
                "m9MxpY6IUGXtWp76",
                "a906ebf17e43fded12b3d6bd077abb2df33bc86eff51c1beeb82e5d416527db3",
            ),
            PhononVoice(
                "Maeve",
                "Maeve (British, female)",
                UK,
                "6PWnV0Nq4wu7RVBT",
                "56494ee67d533ad227ff0dfd0b1c13ff5aa91f1ad6e596e66c2986ba3005e844",
            ),
            PhononVoice(
                "Pippa",
                "Pippa (British, female)",
                UK,
                "uem82D50GRv2Dwma",
                "d41a8f01d6e33450f1ce062d332914da10beee99e585dab531750706f187c48a",
            ),
            PhononVoice(
                "Alfie",
                "Alfie (British, male)",
                UK,
                "X6NceqF285Gr04gb",
                "4de75497fff3030168e8aa6dca23ffeb332b1bc8c25d0bf3507ceadac953ae9c",
            ),
            PhononVoice(
                "Edwin",
                "Edwin (British, male)",
                UK,
                "zUCxzrOrugTSis03",
                "ff1d0735b8b45710462b1d1f846da040f970c6eb62c7c2f36dbfb8e5bde74f1e",
            ),
            PhononVoice(
                "Hugh",
                "Hugh (British, male)",
                UK,
                "YyKcxPG7iIgS3jsK",
                "3169d74b47a54d1eb15e4c243f56393abe9fe90c7e1c308ecf96ee67618b152a",
            ),
            PhononVoice(
                "Spencer",
                "Spencer (British, male)",
                UK,
                "JOW0vchyswkQUMDS",
                "cc9ac1107b0ffce282374a19beb7bfa95847724e6b7afbac991b4ae4277d5e6d",
            ),
            PhononVoice(
                "Teddy",
                "Teddy (British, male)",
                UK,
                "Zn7g6NxLqXlJb7Qi",
                "baea0ea9506f3237a5ebfc61afb7c72dbd189c5603b199ef42fbb00dbda379e0",
            ),
            PhononVoice(
                "Toby",
                "Toby (British, male)",
                UK,
                "dME3IWyZBvmh1n1q",
                "09ab315c7f4b72b4d71e66cb756184cedfb1f14d222d096e115bf95bfe7b29dc",
            ),
            PhononVoice(
                "Azgorath",
                "Azgorath (character)",
                US,
                "dGyGDKo2osj7h3s7",
                "83900e020c14946426614c6553c5a138ba6277e1aaa69832223bd0f0f8b73893",
            ),
            PhononVoice(
                "CaptainSilasBlackwater",
                "Captain Silas Blackwater (character)",
                UK,
                "xPDpsV3F592srkjA",
                "a09f615b0084980ebc4076d71550bf73ccd8d4df076bbbee4a9beb523a1379d9",
            ),
            PhononVoice(
                "FairyGodmother",
                "Fairy Godmother (character)",
                US,
                "QXNr0SvkukLB4wPq",
                "f390c09f5e279c0f2171995a534b92f5ca5c83443906879da68fc27445f89d89",
            ),
            PhononVoice(
                "FennickTheYoungElf",
                "Fennick the Young Elf (character)",
                US,
                "cXoIXJ2llS8S0WOW",
                "a05944e4c69d61d10d66f689175ba0b081930df2d77b22f04d00ac40d3d69ecb",
            ),
            PhononVoice(
                "GrubTheOgre",
                "Grub the Ogre (character)",
                US,
                "cYtsRSxV5q9IMehn",
                "b30fa847686ec1eb6be8c5bea4b6a5c5076ea66c3ff053cf98210a155d8aad1e",
            ),
            PhononVoice(
                "MasterAldric",
                "Master Aldric (character)",
                UK,
                "gSgG5Tor0GPEnqzC",
                "a83430cc022b85c83753f859f9e44f406651646dab994fced8ef7a9a88005971",
            ),
            PhononVoice(
                "MorwennaOfTheDeep",
                "Morwenna of the Deep (character)",
                US,
                "nOykTmcH9ydsGGIc",
                "2ff2db91e70648623df8d42c0dcd7c336b2f14cb4362d602a20f292a4d862154",
            ),
            PhononVoice(
                "OldMortlake",
                "Old Mortlake (character)",
                UK,
                "lX3LavMNdlIbgEHH",
                "d3f9429205e5906dcd3c78edaf7c9d5148c7e389e34c82c86f72f154adb5d923",
            ),
            PhononVoice(
                "SirBramwell",
                "Sir Bramwell (character)",
                UK,
                "y2eiKxtQo2B67deU",
                "b3cea6789aeb58822af16ba08c4e5105d7febb840dcb67b47f426c19d1a8f209",
            ),
            PhononVoice(
                "TheSnowQueen",
                "The Snow Queen (character)",
                US,
                "NIWoEurJHMJjyUTe",
                "bc8a8fcb95bb4f32d2d4376aa69d81b186b3ba2f8e9b6cb6a403b2b194590e85",
            ),
            PhononVoice(
                "TheThingUnderTheStairs",
                "The Thing Under the Stairs (character)",
                US,
                "GXaSqMEmOg12OIYb",
                "bfa37bac98aefbb9de80f792c47d01a0f9e3b94e73776169040c249c8b722759",
            ),
            PhononVoice(
                "VyrmathraxTheAncient",
                "Vyrmathrax the Ancient (character)",
                US,
                "xO8xQo3BrB8FuI6J",
                "63ab4b15427844a804480a1603774262e8903dee33cb279e2749860dbfaedd10",
            ),
        )

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
            // Every English voice in Phonon's catalog, named as the files on disk and on servers.
            BuiltInModel.PHONON to
                PHONON_VOICES.mapIndexed { index, voice ->
                    KokoroVoice(index, voice.code, voice.label, voice.language, BuiltInModel.PHONON)
                },
        ).mapValues { (model, list) -> list.map { it.copy(model = model) } }

    val defaults: Map<BuiltInModel, String> =
        mapOf(BuiltInModel.KOKORO_V019 to "af", BuiltInModel.KOKORO_V1 to "af_heart", BuiltInModel.PHONON to "Harper")

    private const val PHONON_MODEL = "https://huggingface.co/gradium/phonon-31m-en/resolve/92741c5b07d085ff840e26fea8ce43110b87852f"
    private const val VOICES_REPO = "https://huggingface.co/gradium/phonon-voices/resolve/da6c9ec0d47be8df5c87c730a3aa97187f3229c3/voices"
    private const val VOICE_BYTES = 256_136L

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
                    ) + PHONON_VOICES.map { it.file },
                ),
        )
}
