package com.vinicius741.webnovelarchiver.tts

import com.vinicius741.webnovelarchiver.domain.model.TtsSettings
import com.vinicius741.webnovelarchiver.narration.NarrationServer
import com.vinicius741.webnovelarchiver.tts.kokoro.KokoroVoice
import com.vinicius741.webnovelarchiver.tts.kokoro.KokoroVoices

/** Where the Voice & Speech sample is generated. */
internal sealed interface TtsSamplePlace {
    /** In real time on this phone, with the chosen engine. */
    data object Phone : TtsSamplePlace

    /** On [servers], as [speaker], before playing. */
    data class Computers(
        val servers: List<NarrationServer>,
        val speaker: KokoroVoice,
    ) : TtsSamplePlace

    /** Nowhere: the voice cannot be heard until [failure] is fixed. */
    data class Unavailable(
        val failure: TtsSampleFailure,
    ) : TtsSamplePlace
}

internal object TtsSamplePlanning {
    /**
     * Where the sample of [settings] is generated. A built-in model that is not on this phone
     * ([modelOnPhone] false) generates on the chosen [computers]; it is never swapped for another
     * voice, since the sample is there to hear this one.
     */
    fun place(
        settings: TtsSettings,
        modelOnPhone: Boolean,
        computers: List<NarrationServer>,
    ): TtsSamplePlace {
        val model = KokoroVoices.model(settings.enginePackage) ?: return TtsSamplePlace.Phone
        if (modelOnPhone) return TtsSamplePlace.Phone
        if (computers.isEmpty()) return TtsSamplePlace.Unavailable(TtsSampleFailure.NoComputer(model.family))
        return TtsSamplePlace.Computers(computers, KokoroVoices.voiceFor(model, settings.voiceIdentifier))
    }

    /**
     * The computers the sample generates on: the user's pick among [servers] when it still names one,
     * else the computers chapters generate on, else every computer.
     */
    fun chosenComputers(
        servers: List<NarrationServer>,
        picked: Set<String>?,
        generateOnIds: List<String>,
    ): List<NarrationServer> {
        val ids = servers.map { it.id }.toSet()
        val choice =
            picked?.filter { it in ids }?.takeIf { it.isNotEmpty() }
                ?: generateOnIds.filter { it in ids }.takeIf { it.isNotEmpty() }
                ?: return servers
        return servers.filter { it.id in choice }
    }

    /** The pick after tapping [serverId]'s chip; the last chosen computer stays chosen. */
    fun toggle(
        chosen: List<NarrationServer>,
        serverId: String,
    ): Set<String> {
        val ids = chosen.map { it.id }.toSet()
        return when {
            serverId !in ids -> ids + serverId
            ids.size == 1 -> ids
            else -> ids - serverId
        }
    }

    /** The computers named in the status line. */
    fun where(names: List<String>): String = names.joinToString(" + ")

    /** The status line while the computers generate. */
    fun progressLine(state: TtsSampleState.Generating): String =
        "Generating on ${state.where}… ${state.done} of ${state.total} ${if (state.total == 1) "sentence" else "sentences"}"
}
