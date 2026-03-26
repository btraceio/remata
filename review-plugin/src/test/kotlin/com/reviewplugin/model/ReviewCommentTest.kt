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
    fun `effectiveStatus returns last thread status`() {
        val comment = json.decodeFromString(ReviewComment.serializer(), sampleJson)
        // Root status is "open" but last thread entry has "resolved"
        assertEquals(CommentStatus.resolved, comment.effectiveStatus())
    }

    @Test
    fun `effectiveStatus returns root status when no thread status`() {
        val comment = ReviewComment(
            id = "test1",
            author = "human",
            created = "2026-01-01T00:00:00Z",
            status = CommentStatus.open,
            anchor = Anchor(
                file = "test.java",
                commit = "abc",
                line_hint = 1,
                hunk = Hunk(emptyList(), listOf("line1"), emptyList())
            ),
            body = "test"
        )
        assertEquals(CommentStatus.open, comment.effectiveStatus())
    }

    @Test
    fun `deserialize minimal comment`() {
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
    }
}
