package com.vinicius741.webnovelarchiver.domain.model

import com.google.gson.annotations.SerializedName

/**
 * Persisted AI cover-draft metadata. Lives in the kept `domain.model` package with explicit
 * [SerializedName] wire names so minified builds keep writing the same JSON debug builds do, and
 * older `{"prompt":...,"mediaType":...}` documents stay readable.
 */
data class AiCoverDraftMeta(
    @SerializedName("prompt") val prompt: String = "",
    @SerializedName("mediaType") val mediaType: String? = null,
    /** Generation image filename; null on legacy documents = discover `<story>.<ext>`. */
    @SerializedName("imageFile") val imageFile: String? = null,
    /** True while a background result has not been looked at; absent on older documents = seen. */
    @SerializedName("unseen") val unseen: Boolean = false,
    /** Raw story id, so unseen results can be listed without reversing the lossy file name. */
    @SerializedName("storyId") val storyId: String? = null,
)
