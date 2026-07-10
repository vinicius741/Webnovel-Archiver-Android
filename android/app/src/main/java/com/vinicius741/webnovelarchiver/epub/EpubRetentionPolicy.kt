package com.vinicius741.webnovelarchiver.epub

data class EpubStorageEntry(
    val path: String,
    val bytes: Long,
    val lastModified: Long,
)

data class EpubCleanupPlan(
    val delete: List<EpubStorageEntry>,
    val referenced: List<EpubStorageEntry>,
    val retainedLegacy: List<EpubStorageEntry>,
) {
    val bytesBefore: Long = (delete + referenced + retainedLegacy).sumOf { it.bytes }
    val reclaimableBytes: Long = delete.sumOf { it.bytes }
}

data class EpubCleanupResult(
    val filesBefore: Int,
    val filesDeleted: Int,
    val bytesBefore: Long,
    val bytesReclaimed: Long,
    val failedPaths: List<String>,
)

/** Pure retention policy: current story references are immutable; only oldest legacy files expire. */
object EpubRetentionPolicy {
    const val DEFAULT_LEGACY_FILES_TO_KEEP = 3

    fun plan(
        entries: List<EpubStorageEntry>,
        referencedPaths: Set<String>,
        legacyFilesToKeep: Int = DEFAULT_LEGACY_FILES_TO_KEEP,
    ): EpubCleanupPlan {
        require(legacyFilesToKeep >= 0) { "Legacy EPUB retention must not be negative" }
        val referenced = entries.filter { it.path in referencedPaths }
        val legacy = entries.filterNot { it.path in referencedPaths }.sortedByDescending { it.lastModified }
        return EpubCleanupPlan(
            delete = legacy.drop(legacyFilesToKeep),
            referenced = referenced,
            retainedLegacy = legacy.take(legacyFilesToKeep),
        )
    }

    fun result(
        plan: EpubCleanupPlan,
        deletedPaths: Set<String>,
    ): EpubCleanupResult {
        val deleted = plan.delete.filter { it.path in deletedPaths }
        return EpubCleanupResult(
            filesBefore = plan.delete.size + plan.referenced.size + plan.retainedLegacy.size,
            filesDeleted = deleted.size,
            bytesBefore = plan.bytesBefore,
            bytesReclaimed = deleted.sumOf { it.bytes },
            failedPaths = plan.delete.map { it.path }.filterNot { it in deletedPaths },
        )
    }
}
