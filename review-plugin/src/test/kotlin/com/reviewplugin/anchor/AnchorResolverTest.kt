package com.reviewplugin.anchor

import com.reviewplugin.model.*
import org.junit.Assert.*
import org.junit.Test

class AnchorResolverTest {

    @Test
    fun `exactMatch finds target at hint position`() {
        val lines = listOf(
            "line 1",
            "line 2",
            "target line A",
            "target line B",
            "line 5"
        )
        val target = listOf("target line A", "target line B")

        // line_hint is 1-based, so hint=3 means index 2
        val resolver = createResolver()
        val result = resolver.exactMatch(lines, 3, target)
        assertEquals(2, result) // 0-based index
    }

    @Test
    fun `exactMatch returns null when lines do not match`() {
        val lines = listOf("line 1", "line 2", "different line", "line 4")
        val target = listOf("target line A")

        val resolver = createResolver()
        assertNull(resolver.exactMatch(lines, 3, target))
    }

    @Test
    fun `exactMatch handles trimmed comparison`() {
        val lines = listOf("  target line  ", "  other  ")
        val target = listOf("target line")

        val resolver = createResolver()
        assertEquals(0, resolver.exactMatch(lines, 1, target))
    }

    @Test
    fun `exactMatch returns null when hint is out of bounds`() {
        val lines = listOf("line 1")
        val target = listOf("line 1", "line 2")

        val resolver = createResolver()
        assertNull(resolver.exactMatch(lines, 1, target))
    }

    @Test
    fun `fuzzySearch finds shifted target`() {
        // Original file had target at lines 3-4 (0-based 2-3)
        // Now 2 lines were inserted at the top, pushing target to lines 5-6 (0-based 4-5)
        val lines = listOf(
            "new line 1",
            "new line 2",
            "context before 1",
            "context before 2",
            "target line A",
            "target line B",
            "context after 1"
        )
        val hunk = Hunk(
            context_before = listOf("context before 1", "context before 2"),
            target = listOf("target line A", "target line B"),
            context_after = listOf("context after 1")
        )

        val resolver = createResolver()
        val result = resolver.fuzzySearch(lines, hunk)
        assertNotNull(result)
        assertEquals(4, result) // 0-based index of "target line A"
    }

    @Test
    fun `fuzzySearch returns null when no match`() {
        val lines = listOf("completely", "different", "content", "here")
        val hunk = Hunk(
            context_before = listOf("context before"),
            target = listOf("target line"),
            context_after = listOf("context after")
        )

        val resolver = createResolver()
        assertNull(resolver.fuzzySearch(lines, hunk))
    }

    @Test
    fun `parseDiffAndRemap handles line offset after hunk`() {
        // A diff that adds 2 lines at the beginning
        val diff = """
            diff --git a/test.java b/test.java
            index abc..def 100644
            --- a/test.java
            +++ b/test.java
            @@ -1,3 +1,5 @@
            +new line 1
            +new line 2
             existing line 1
             existing line 2
             existing line 3
        """.trimIndent()

        val resolver = createResolver()
        // Old line 5 should now be at new line 7 (5 + 2 offset), 0-based = 6
        val result = resolver.parseDiffAndRemap(diff, 5)
        assertNotNull(result)
        assertEquals(6, result) // 0-based: old line 5 → new line 7 → index 6
    }

    @Test
    fun `parseDiffAndRemap returns null for modified line in hunk`() {
        val diff = """
            diff --git a/test.java b/test.java
            @@ -3,2 +3,2 @@
            -old line 3
            +new line 3
             unchanged line 4
        """.trimIndent()

        val resolver = createResolver()
        // Old line 3 is in the modified hunk
        val result = resolver.parseDiffAndRemap(diff, 3)
        assertNull(result) // Can't remap, falls in modified hunk
    }

    @Test
    fun `parseDiffAndRemap handles no diff`() {
        val resolver = createResolver()
        // When diff is empty, line stays the same
        val result = resolver.parseDiffAndRemap("", 10)
        // Empty diff means no hunks found, line is after all hunks
        assertNotNull(result)
        assertEquals(9, result) // 0-based
    }

    private fun createResolver(): AnchorResolver {
        // For unit tests that don't need Project, we use reflection or test only internal methods
        return AnchorResolver(MockProject())
    }

    /**
     * Minimal mock for unit-testing resolver internals that don't need a real Project.
     */
    private class MockProject : com.intellij.openapi.project.Project {
        override fun getName(): String = "mock"
        override fun getBasePath(): String = "/tmp/mock"
        override fun getBaseDir(): com.intellij.openapi.vfs.VirtualFile? = null
        override fun getProjectFilePath(): String? = null
        override fun getProjectFile(): com.intellij.openapi.vfs.VirtualFile? = null
        override fun getWorkspaceFile(): com.intellij.openapi.vfs.VirtualFile? = null
        override fun getLocationHash(): String = "mock"
        override fun isOpen(): Boolean = true
        override fun isInitialized(): Boolean = true
        override fun isDefault(): Boolean = false
        override fun dispose() {}

        @Suppress("UNCHECKED_CAST")
        override fun <T : Any?> getService(serviceClass: Class<T>): T? = null
        override fun <T : Any?> getComponent(interfaceClass: Class<T>): T? = null
        override fun hasComponent(interfaceClass: Class<*>): Boolean = false
        override fun isDisposed(): Boolean = false
        override fun getDisposed(): com.intellij.openapi.util.Condition<*> =
            com.intellij.openapi.util.Condition<Any> { false }
        override fun getMessageBus(): com.intellij.util.messages.MessageBus =
            throw UnsupportedOperationException()
        override fun <T : Any?> getUserData(key: com.intellij.openapi.util.Key<T>): T? = null
        override fun <T : Any?> putUserData(key: com.intellij.openapi.util.Key<T>, value: T?) {}
        override fun save() {}
        override fun getExtensionArea(): com.intellij.openapi.extensions.ExtensionsArea =
            throw UnsupportedOperationException()
        override fun getPicoContainer(): com.intellij.util.pico.DefaultPicoContainer =
            throw UnsupportedOperationException()
    }
}
