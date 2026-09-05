package com.vinicius741.webnovelarchiver.data.storage

import com.vinicius741.webnovelarchiver.domain.model.ChapterRewriteManifestModel

/**
 * Typed health of one story's rewrite manifest (R08). [Fenced] blocks every write for that story:
 * overwriting a document this process cannot read would silently drop the records it still holds.
 */
sealed interface RewriteManifestRead {
    data class Ok(
        val manifest: ChapterRewriteManifestModel,
    ) : RewriteManifestRead

    data object Absent : RewriteManifestRead

    data class Fenced(
        val reason: Reason,
        val detail: String,
    ) : RewriteManifestRead {
        enum class Reason {
            Corrupt,
            UnsupportedVersion,
            IoFailure,
        }
    }
}
