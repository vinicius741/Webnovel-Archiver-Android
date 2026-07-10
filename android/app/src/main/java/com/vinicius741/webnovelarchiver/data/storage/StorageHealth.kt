package com.vinicius741.webnovelarchiver.data.storage

enum class StorageHealthKind {
    Corrupt,
    UnsupportedSchema,
    IoFailure,
    LibraryIndexRecovered,
}

data class StorageHealthIssue(
    val document: String,
    val kind: StorageHealthKind,
    val detail: String,
    val recoveredStoryCount: Int = 0,
)

data class StorageHealthSnapshot(
    val issues: List<StorageHealthIssue> = emptyList(),
) {
    val requiresUserAttention: Boolean get() = issues.isNotEmpty()
}
