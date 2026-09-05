package com.vinicius741.webnovelarchiver.domain.model

import com.google.gson.annotations.SerializedName

/**
 * Persisted AI cover-draft metadata. Lives in the kept `domain.model` package with explicit
 * [SerializedName] wire names so minified builds keep writing the same JSON debug builds do, and
 * older `{"prompt":...,"mediaType":...}` documents stay readable (R27).
 */
data class AiCoverDraftMeta(
    @SerializedName("prompt") val prompt: String = "",
    @SerializedName("mediaType") val mediaType: String? = null,
    /** Generation image filename; null on legacy documents = discover `<story>.<ext>` (R09). */
    @SerializedName("imageFile") val imageFile: String? = null,
)
