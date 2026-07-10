package com.vinicius741.webnovelarchiver.data.diagnostics

data class DiagnosticAppInfo(
    val versionName: String,
    val versionCode: Long,
    val buildType: String,
    val sdkInt: Int,
)

data class DiagnosticEvent(
    val timestampMillis: Long,
    val priority: Int,
    val throwableType: String?,
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

data class DiagnosticExportPayload(
    val format: String = "webnovel-archiver-diagnostics",
    val version: Int = 1,
    val generatedAtMillis: Long,
    val app: DiagnosticAppInfo,
    val storageIssues: List<DiagnosticStorageIssue>,
    val queue: DiagnosticQueueSummary,
    val warningAndErrorEvents: List<DiagnosticEvent>,
)
