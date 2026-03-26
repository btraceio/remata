package com.reviewplugin.model

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class ReviewCommentTest {

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    private val sampleJson = """
    {
      "id": "a3f1c2d4",
      "schema_version": 1,
      "author": "human",
      "created": "2026-03-25T10:14:00Z",
      "status": "open",
      "anchor": {
        "file": "src/hotspot/share/prims/jvmtiEnv.cpp",
        "commit": "f3a9b1c",
        "line_hint": 142,
        "hunk": {
          "context_before": [
            "jvmtiError err;",
            "jint state;"
          ],
          "target": [
            "err = env->GetThreadState(thread, &state);",
            "if (err != JVMTI_ERROR_NONE) return err;"
          ],
          "context_after": [
            "if (state & JVMTI_THREAD_STATE_SUSPENDED) {"
          ]
        }
      },
      "resolved_anchor": null,
      "body": "This assumes the thread is suspended but that is not guaranteed here.",
      "thread": [
        {
          "id": "r1",
          "author": "agent",
          "created": "2026-03-25T10:22:00Z",
          "body": "Fixed — added JVMTI_ERROR_THREAD_NOT_SUSPENDED check at line 139.",
          "status": "resolved"
        }
      ]
    }
    """.trimIndent()

    // ── Deserialization ──────────────────────────────────────────────

    @Test
    fun `deserialize sample comment`() {
        val comment = json.decodeFromString(ReviewComment.serializer(), sampleJson)
        assertEquals("a3f1c2d4", comment.id)
        assertEquals(1, comment.schema_version)
        assertEquals("human", comment.author)
        assertEquals(CommentStatus.open, comment.status)
        assertEquals("src/hotspot/share/prims/jvmtiEnv.cpp", comment.anchor.file)
        assertEquals("f3a9b1c", comment.anchor.commit)
        assertEquals(142, comment.anchor.line_hint)
        assertEquals(2, comment.anchor.hunk.context_before.size)
        assertEquals(2, comment.anchor.hunk.target.size)
        assertEquals(1, comment.anchor.hunk.context_after.size)
        assertNull(comment.resolved_anchor)
        assertEquals(1, comment.thread.size)
        assertEquals("r1", comment.thread[0].id)
        assertEquals(CommentStatus.resolved, comment.thread[0].status)
    }

    @Test
    fun `round-trip serialization`() {
        val comment = json.decodeFromString(ReviewComment.serializer(), sampleJson)
        val serialized = json.encodeToString(ReviewComment.serializer(), comment)
        val deserialized = json.decodeFromString(ReviewComment.serializer(), serialized)
        assertEquals(comment, deserialized)
    }

    @Test
    fun `deserialize minimal comment with defaults`() {
        val minimal = """
        {
          "id": "abcd1234",
          "schema_version": 1,
          "author": "agent",
          "created": "2026-01-01T00:00:00Z",
          "status": "open",
          "anchor": {
            "file": "Main.java",
            "commit": "deadbeef",
            "line_hint": 10,
            "hunk": {
              "context_before": [],
              "target": ["System.out.println(\"hello\");"],
              "context_after": []
            }
          },
          "body": "Consider using a logger."
        }
        """.trimIndent()
        val comment = json.decodeFromString(ReviewComment.serializer(), minimal)
        assertEquals("abcd1234", comment.id)
        assertEquals(0, comment.thread.size)
        assertNull(comment.resolved_anchor)
        assertEquals(1, comment.schema_version)
    }

    @Test
    fun `deserialize unknown fields are ignored`() {
        val withExtras = """
        {
          "id": "test123",
          "schema_version": 1,
          "author": "agent",
          "created": "2026-01-01T00:00:00Z",
          "status": "open",
          "anchor": {
            "file": "Test.kt",
            "commit": "abc",
            "line_hint": 5,
            "hunk": {
              "context_before": [],
              "target": ["val x = 1"],
              "context_after": []
            }
          },
          "body": "test",
          "unknown_field": "should be ignored",
          "another_unknown": 42
        }
        """.trimIndent()
        val comment = json.decodeFromString(ReviewComment.serializer(), withExtras)
        assertEquals("test123", comment.id)
    }

    @Test
    fun `deserialize comment with resolved_anchor`() {
        val withResolved = """
        {
          "id": "res123",
          "schema_version": 1,
          "author": "human",
          "created": "2026-01-01T00:00:00Z",
          "status": "resolved",
          "anchor": {
            "file": "Old.java",
            "commit": "aaa",
            "line_hint": 10,
            "hunk": { "context_before": [], "target": ["old code"], "context_after": [] }
          },
          "resolved_anchor": {
            "file": "Old.java",
            "commit": "bbb",
            "line_hint": 12,
            "hunk": { "context_before": ["new ctx"], "target": ["new code"], "context_after": [] }
          },
          "body": "Fixed",
          "thread": []
        }
        """.trimIndent()
        val comment = json.decodeFromString(ReviewComment.serializer(), withResolved)
        assertNotNull(comment.resolved_anchor)
        assertEquals("bbb", comment.resolved_anchor!!.commit)
        assertEquals(12, comment.resolved_anchor!!.line_hint)
        assertEquals(listOf("new code"), comment.resolved_anchor!!.hunk.target)
    }

    // ── Status enum ──────────────────────────────────────────────────

    @Test
    fun `deserialize all status values`() {
        for (status in CommentStatus.entries) {
            val json_str = """
            {
              "id": "s${status.name}",
              "schema_version": 1,
              "author": "x",
              "created": "2026-01-01T00:00:00Z",
              "status": "${status.name}",
              "anchor": {
                "file": "f.java",
                "commit": "c",
                "line_hint": 1,
                "hunk": { "context_before": [], "target": ["x"], "context_after": [] }
              },
              "body": "test"
            }
            """.trimIndent()
            val comment = json.decodeFromString(ReviewComment.serializer(), json_str)
            assertEquals(status, comment.status)
        }
    }

    // ── effectiveStatus ──────────────────────────────────────────────

    @Test
    fun `effectiveStatus returns last thread status`() {
        val comment = json.decodeFromString(ReviewComment.serializer(), sampleJson)
        assertEquals(CommentStatus.resolved, comment.effectiveStatus())
    }

    @Test
    fun `effectiveStatus returns root status when no thread status`() {
        val comment = makeComment(
            status = CommentStatus.open,
            thread = listOf(
                ThreadEntry("t1", "agent", "2026-01-01T00:00:00Z", "info reply", null)
            )
        )
        assertEquals(CommentStatus.open, comment.effectiveStatus())
    }

    @Test
    fun `effectiveStatus returns root status with empty thread`() {
        val comment = makeComment(status = CommentStatus.open)
        assertEquals(CommentStatus.open, comment.effectiveStatus())
    }

    @Test
    fun `effectiveStatus uses last non-null thread status`() {
        val comment = makeComment(
            status = CommentStatus.open,
            thread = listOf(
                ThreadEntry("t1", "human", "2026-01-01T00:00:00Z", "resolved", CommentStatus.resolved),
                ThreadEntry("t2", "human", "2026-01-02T00:00:00Z", "info reply", null),
                ThreadEntry("t3", "human", "2026-01-03T00:00:00Z", "reopened? no, wontfix", CommentStatus.wontfix)
            )
        )
        assertEquals(CommentStatus.wontfix, comment.effectiveStatus())
    }

    @Test
    fun `effectiveStatus with resolved root but no thread`() {
        val comment = makeComment(status = CommentStatus.resolved)
        assertEquals(CommentStatus.resolved, comment.effectiveStatus())
    }

    // ── Thread entries ───────────────────────────────────────────────

    @Test
    fun `thread entry with null status`() {
        val entry = ThreadEntry("t1", "agent", "2026-01-01T00:00:00Z", "just a note", null)
        assertNull(entry.status)
    }

    @Test
    fun `thread entry serialization round trip`() {
        val entry = ThreadEntry("t1", "human", "2026-01-01T00:00:00Z", "resolved it", CommentStatus.resolved)
        val serialized = json.encodeToString(ThreadEntry.serializer(), entry)
        val deserialized = json.decodeFromString(ThreadEntry.serializer(), serialized)
        assertEquals(entry, deserialized)
    }

    @Test
    fun `thread entry with null status serializes correctly`() {
        val entry = ThreadEntry("t1", "agent", "2026-01-01T00:00:00Z", "info", null)
        val serialized = json.encodeToString(ThreadEntry.serializer(), entry)
        val deserialized = json.decodeFromString(ThreadEntry.serializer(), serialized)
        assertEquals(entry, deserialized)
        assertNull(deserialized.status)
    }

    // ── Hunk ─────────────────────────────────────────────────────────

    @Test
    fun `hunk with empty context lists`() {
        val hunk = Hunk(emptyList(), listOf("target"), emptyList())
        val serialized = json.encodeToString(Hunk.serializer(), hunk)
        val deserialized = json.decodeFromString(Hunk.serializer(), serialized)
        assertEquals(hunk, deserialized)
    }

    @Test
    fun `hunk with multiple target lines`() {
        val hunk = Hunk(
            listOf("before 1", "before 2"),
            listOf("target 1", "target 2", "target 3"),
            listOf("after 1")
        )
        val serialized = json.encodeToString(Hunk.serializer(), hunk)
        val deserialized = json.decodeFromString(Hunk.serializer(), serialized)
        assertEquals(3, deserialized.target.size)
        assertEquals("target 2", deserialized.target[1])
    }

    // ── Anchor ───────────────────────────────────────────────────────

    @Test
    fun `anchor with forward slashes in file path`() {
        val anchor = Anchor(
            file = "src/main/java/Test.java",
            commit = "abc123",
            line_hint = 42,
            hunk = Hunk(emptyList(), listOf("code"), emptyList())
        )
        val serialized = json.encodeToString(Anchor.serializer(), anchor)
        assertTrue(serialized.contains("src/main/java/Test.java"))
        val deserialized = json.decodeFromString(Anchor.serializer(), serialized)
        assertEquals("src/main/java/Test.java", deserialized.file)
    }

    // ── Edge cases ───────────────────────────────────────────────────

    @Test
    fun `comment with unicode body`() {
        val comment = makeComment(body = "This has unicode: 日本語 and émojis 🎉")
        val serialized = json.encodeToString(ReviewComment.serializer(), comment)
        val deserialized = json.decodeFromString(ReviewComment.serializer(), serialized)
        assertEquals(comment.body, deserialized.body)
    }

    @Test
    fun `comment with multiline body`() {
        val comment = makeComment(body = "Line 1\nLine 2\nLine 3")
        val serialized = json.encodeToString(ReviewComment.serializer(), comment)
        val deserialized = json.decodeFromString(ReviewComment.serializer(), serialized)
        assertTrue(deserialized.body.contains("\n"))
        assertEquals(3, deserialized.body.lines().size)
    }

    @Test
    fun `comment with empty body`() {
        val comment = makeComment(body = "")
        val serialized = json.encodeToString(ReviewComment.serializer(), comment)
        val deserialized = json.decodeFromString(ReviewComment.serializer(), serialized)
        assertEquals("", deserialized.body)
    }

    @Test
    fun `comment with special characters in body`() {
        val comment = makeComment(body = """He said "hello" and <b>bold</b> & more""")
        val serialized = json.encodeToString(ReviewComment.serializer(), comment)
        val deserialized = json.decodeFromString(ReviewComment.serializer(), serialized)
        assertEquals(comment.body, deserialized.body)
    }

    @Test
    fun `comment with long thread`() {
        val entries = (1..50).map {
            ThreadEntry("t$it", if (it % 2 == 0) "human" else "agent", "2026-01-01T00:00:00Z", "Reply #$it", null)
        }
        val comment = makeComment(thread = entries)
        val serialized = json.encodeToString(ReviewComment.serializer(), comment)
        val deserialized = json.decodeFromString(ReviewComment.serializer(), serialized)
        assertEquals(50, deserialized.thread.size)
        assertEquals("Reply #50", deserialized.thread[49].body)
    }

    // ── Helpers ──────────────────────────────────────────────────────

    private fun makeComment(
        id: String = "test01",
        status: CommentStatus = CommentStatus.open,
        body: String = "test comment",
        thread: List<ThreadEntry> = emptyList()
    ) = ReviewComment(
        id = id,
        author = "human",
        created = "2026-01-01T00:00:00Z",
        status = status,
        anchor = Anchor(
            file = "test.java",
            commit = "abc",
            line_hint = 1,
            hunk = Hunk(emptyList(), listOf("code"), emptyList())
        ),
        body = body,
        thread = thread
    )
}
