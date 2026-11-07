package com.vinicius741.webnovelarchiver.data.diagnostics

data class DiagnosticAppInfo(
    val versionName: String,
    val versionCode: Long,
    val buildType: String,
    val sdkInt: Int,
)

data class DiagnosticStorageIssue(
    val document: String,
    val kind: String,
    val recoveredStoryCount: Int,
)

data class DiagnosticQueueSummary(
    val total: Int,
    val byStatus: Map<String, Int>,
    val byErrorCategory: Map<String, Int>,
)

data class DiagnosticLibrarySummary(
    val stories: Int,
    val chapters: Int,
    val downloadedChapters: Int,
    val bySource: Map<String, Int>,
    val byStatus: Map<String, Int>,
)
