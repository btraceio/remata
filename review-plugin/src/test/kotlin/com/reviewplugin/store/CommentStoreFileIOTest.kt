package com.reviewplugin.store

import com.reviewplugin.model.*
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Tests for the file I/O contract of the .review/comments/ directory.
 * These are pure file-system tests — no IntelliJ platform required.
 */
class CommentStoreFileIOTest {

    @get:Rule
    val tempDir = TemporaryFolder()

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    private lateinit var commentsDir: File

    @Before
    fun setup() {
        commentsDir = File(tempDir.root, ".review/comments")
        commentsDir.mkdirs()
    }

    // ── Write & read round-trip ──────────────────────────────────────

    @Test
    fun `write and read a comment file`() {
        val comment = makeComment("abc123")
        writeComment(comment)

        val file = File(commentsDir, "abc123.json")
        assertTrue(file.exists())

        val read = json.decodeFromString(ReviewComment.serializer(), file.readText())
        assertEquals("abc123", read.id)
        assertEquals("human", read.author)
        assertEquals(CommentStatus.open, read.status)
    }

    @Test
    fun `atomic write via tmp rename`() {
        val comment = makeComment("atomtest")
        val tmpFile = File(commentsDir, "atomtest.json.tmp")
        val finalFile = File(commentsDir, "atomtest.json")

        tmpFile.writeText(json.encodeToString(ReviewComment.serializer(), comment))
        assertTrue(tmpFile.exists())
        assertFalse(finalFile.exists())

        tmpFile.renameTo(finalFile)
        assertFalse(tmpFile.exists())
        assertTrue(finalFile.exists())

        val read = json.decodeFromString(ReviewComment.serializer(), finalFile.readText())
        assertEquals("atomtest", read.id)
    }

    // ── Multiple comments ────────────────────────────────────────────

    @Test
    fun `load all comments from directory`() {
        writeComment(makeComment("c1"))
        writeComment(makeComment("c2"))
        writeComment(makeComment("c3"))

        val files = commentsDir.listFiles()?.filter { it.extension == "json" } ?: emptyList()
        assertEquals(3, files.size)

        val comments = files.map { json.decodeFromString(ReviewComment.serializer(), it.readText()) }
        val ids = comments.map { it.id }.toSet()
        assertEquals(setOf("c1", "c2", "c3"), ids)
    }

    @Test
    fun `filtering by file path`() {
        writeComment(makeComment("c1", file = "src/Main.java"))
        writeComment(makeComment("c2", file = "src/Main.java"))
        writeComment(makeComment("c3", file = "src/Other.java"))

        val all = loadAll()
        val mainComments = all.filter { it.anchor.file == "src/Main.java" }
        assertEquals(2, mainComments.size)
    }

    @Test
    fun `filtering by status`() {
        writeComment(makeComment("c1", status = CommentStatus.open))
        writeComment(makeComment("c2", status = CommentStatus.resolved))
        writeComment(makeComment("c3", status = CommentStatus.wontfix))
        writeComment(makeComment("c4", status = CommentStatus.open))

        val all = loadAll()
        assertEquals(2, all.count { it.status == CommentStatus.open })
        assertEquals(1, all.count { it.status == CommentStatus.resolved })
        assertEquals(1, all.count { it.status == CommentStatus.wontfix })
    }

    // ── Delete ───────────────────────────────────────────────────────

    @Test
    fun `delete comment file`() {
        writeComment(makeComment("del1"))
        val file = File(commentsDir, "del1.json")
        assertTrue(file.exists())

        file.delete()
        assertFalse(file.exists())
        assertEquals(0, loadAll().size)
    }

    // ── Malformed files ──────────────────────────────────────────────

    @Test
    fun `skip malformed json files`() {
        writeComment(makeComment("good1"))
        File(commentsDir, "bad1.json").writeText("not valid json {{{")
        File(commentsDir, "bad2.json").writeText("{}")

        val loaded = loadAllSafe()
        assertEquals(1, loaded.size)
        assertEquals("good1", loaded[0].id)
    }

    @Test
    fun `skip files with unknown schema version`() {
        val comment = makeComment("v2test")
        val modified = json.encodeToString(ReviewComment.serializer(), comment)
            .replace("\"schema_version\": 1", "\"schema_version\": 99")
        File(commentsDir, "v2test.json").writeText(modified)

        val loaded = loadAllSafe()
        assertEquals(0, loaded.size)
    }

    @Test
    fun `ignore non-json files`() {
        writeComment(makeComment("good"))
        File(commentsDir, "notes.txt").writeText("not a comment")
        File(commentsDir, "abc.json.tmp").writeText("partial write")

        val jsonFiles = commentsDir.listFiles()?.filter { it.extension == "json" } ?: emptyList()
        assertEquals(1, jsonFiles.size)
    }

    // ── Update (resolve) ─────────────────────────────────────────────

    @Test
    fun `resolve a comment by rewriting file`() {
        val original = makeComment("res1", status = CommentStatus.open)
        writeComment(original)

        val entry = ThreadEntry("r1", "agent", "2026-01-02T00:00:00Z", "Fixed", CommentStatus.resolved)
        val updated = original.copy(
            status = CommentStatus.resolved,
            thread = original.thread + entry
        )
        writeComment(updated)

        val read = json.decodeFromString(ReviewComment.serializer(), File(commentsDir, "res1.json").readText())
        assertEquals(CommentStatus.resolved, read.status)
        assertEquals(1, read.thread.size)
        assertEquals(CommentStatus.resolved, read.thread[0].status)
    }

    @Test
    fun `append multiple thread entries`() {
        var comment = makeComment("thr1")
        writeComment(comment)

        for (i in 1..5) {
            val entry = ThreadEntry("r$i", "agent", "2026-01-0${i}T00:00:00Z", "Reply $i", null)
            comment = comment.copy(thread = comment.thread + entry)
            writeComment(comment)
        }

        val read = json.decodeFromString(ReviewComment.serializer(), File(commentsDir, "thr1.json").readText())
        assertEquals(5, read.thread.size)
        assertEquals("Reply 3", read.thread[2].body)
    }

    // ── Path normalization ───────────────────────────────────────────

    @Test
    fun `path normalization with backslashes`() {
        val comment = makeComment("path1", file = "src/main/java/Test.java")
        writeComment(comment)

        val all = loadAll()
        val normalized = "src\\main\\java\\Test.java".replace('\\', '/')
        val found = all.filter { it.anchor.file.replace('\\', '/') == normalized }
        assertEquals(1, found.size)
    }

    // ── Concurrent writes (simulated) ────────────────────────────────

    @Test
    fun `multiple comments with unique ids dont collide`() {
        val ids = (1..100).map { String.format("%08x", it) }
        ids.forEach { writeComment(makeComment(it)) }

        val files = commentsDir.listFiles()?.filter { it.extension == "json" } ?: emptyList()
        assertEquals(100, files.size)

        val loaded = loadAll()
        assertEquals(100, loaded.size)
        assertEquals(ids.toSet(), loaded.map { it.id }.toSet())
    }

    // ── Helpers ──────────────────────────────────────────────────────

    private fun makeComment(
        id: String,
        status: CommentStatus = CommentStatus.open,
        file: String = "src/Test.java"
    ) = ReviewComment(
        id = id,
        author = "human",
        created = "2026-01-01T00:00:00Z",
        status = status,
        anchor = Anchor(file = file, commit = "abc", line_hint = 10, hunk = Hunk(emptyList(), listOf("code"), emptyList())),
        body = "Test comment for $id"
    )

    private fun writeComment(comment: ReviewComment) {
        val file = File(commentsDir, "${comment.id}.json")
        file.writeText(json.encodeToString(ReviewComment.serializer(), comment))
    }

    private fun loadAll(): List<ReviewComment> {
        return commentsDir.listFiles()
            ?.filter { it.extension == "json" }
            ?.map { json.decodeFromString(ReviewComment.serializer(), it.readText()) }
            ?: emptyList()
    }

    private fun loadAllSafe(): List<ReviewComment> {
        return commentsDir.listFiles()
            ?.filter { it.extension == "json" }
            ?.mapNotNull { file ->
                try {
                    val comment = json.decodeFromString(ReviewComment.serializer(), file.readText())
                    if (comment.schema_version != 1) null else comment
                } catch (_: Exception) {
                    null
                }
            }
            ?: emptyList()
    }
}
