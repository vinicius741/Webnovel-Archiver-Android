package com.vinicius741.webnovelarchiver.domain.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/*
 * DownloadJobTransitions is the single owner of a job's lifecycle fields. A direct assignment
 * elsewhere is how status and retry deadline drifted apart before (f889b39), so production code
 * may construct jobs with these fields (named arguments) but never reassign them outside the owner.
 *
 * This is a text scan, not a type-aware rule. It flags any assignment to a lifecycle field name,
 * with a receiver (`job.status = …`) or bare inside a scope function (`apply { status = … }`), in
 * files that handle jobs. A name whose innermost open bracket is `(` is a named argument (creating
 * or copying a job), not an assignment. `Story.status` takes a DownloadStatus and is exempt.
 */
class DownloadJobTransitionOwnershipTest {
    private val owner = "domain/download/DownloadJobTransitions.kt"
    private val assignment =
        Regex("""(?<![\w])(status|error|errorCategory|errorCode|nextRetryAt|retryCount)\s*(?:\+=|-=|=(?!=))""")
    private val declaration = Regex("""\b(val|var)\s+$""")

    /** 1-based line numbers of lifecycle-field assignments in Kotlin [source]. */
    private fun assignmentLines(source: String): List<Int> {
        val brackets = ArrayDeque<Char>()
        val found = mutableListOf<Int>()
        source.lines().forEachIndexed { index, rawLine ->
            val line = rawLine.substringBefore("//")
            val trimmed = line.trim()
            val matches = if (trimmed.startsWith("*")) emptyList() else assignment.findAll(line).toList()
            var cursor = 0

            fun advanceTo(end: Int) {
                while (cursor < end) {
                    when (val c = line[cursor]) {
                        '(', '{', '[' -> brackets.addLast(c)
                        ')', '}', ']' -> brackets.removeLastOrNull()
                    }
                    cursor++
                }
            }
            matches.forEach { match ->
                advanceTo(match.range.first)
                val namedArgument = brackets.lastOrNull() == '('
                val declared = declaration.containsMatchIn(line.substring(0, match.range.first))
                val field = match.groupValues[1]
                val value = line.substring(match.range.last + 1)
                val storyStatus = field == "status" && "DownloadStatus." in value
                if (!namedArgument && !declared && !storyStatus) found += index + 1
            }
            advanceTo(line.length)
        }
        return found
    }

    @Test
    fun scannerCatchesEveryAssignmentShape() {
        val source =
            """
            fun f(job: DownloadJob) {
                job.status = DownloadJobStatus.Pending.wire
                job.status = "pending"
                job.error = "failure"
                job.retryCount += 1
                queue.first().nextRetryAt = null
                queue.find { it.id == id }?.errorCategory = "network"
                job.apply { status = DownloadJobStatus.Pending.wire; nextRetryAt = null }
                job.apply {
                    errorCode =
                        "X"
                }
                save(job.apply { status = "failed" })
            }
            """.trimIndent()

        assertEquals(listOf(2, 3, 4, 5, 6, 7, 8, 8, 10, 13), assignmentLines(source))
    }

    @Test
    fun scannerIgnoresCreationComparisonsDeclarationsAndStoryStatus() {
        val source =
            """
            fun f(job: DownloadJob) {
                val created = DownloadJob(status = DownloadJobStatus.Pending.wire, nextRetryAt = null)
                val copied =
                    job.copy(
                        status =
                            label(
                                job,
                            ),
                        retryCount = job.retryCount,
                    )
                if (job.status == DownloadJobStatus.Pending.wire) return
                val error = classified.message
                story.status = if (story.downloadedChapters > 0) DownloadStatus.partial else DownloadStatus.idle
                // job.status = DownloadJobStatus.Failed.wire
                 * job.error = message
            }
            """.trimIndent()

        assertEquals(emptyList<Int>(), assignmentLines(source))
    }

    @Test
    fun onlyTheTransitionOwnerAssignsJobLifecycleFields() {
        val root = File("src/main/java/com/vinicius741/webnovelarchiver")
        assertTrue("Run from the app module: ${root.absolutePath}", root.isDirectory)

        val offenders =
            root
                .walkTopDown()
                .filter { it.isFile && it.extension == "kt" && it.relativeTo(root).invariantSeparatorsPath != owner }
                // Other types reuse names like status and retryCount; files that touch jobs are in scope.
                .filter { file -> file.readText().let { "DownloadJob" in it || "nextRetryAt" in it } }
                .flatMap { file ->
                    val path = file.relativeTo(root).invariantSeparatorsPath
                    val lines = file.readLines()
                    assignmentLines(file.readText()).map { "$path:$it: ${lines[it - 1].trim()}" }
                }.toList()

        assertEquals("Route job lifecycle changes through DownloadJobTransitions", emptyList<String>(), offenders)
    }
}
