package com.evofactory.hal

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.Executor
import org.junit.Assert.*
import org.junit.Test

class RuntimeOwnershipTest {
    @Test fun laterVideoItemNeedsItsOwnFrameWhileKeepingThePreviousPictureVisible() {
        val state = VideoFrameState()
        assertFalse(state.displayed); assertFalse(state.currentItemReady)
        state.rendered()
        assertTrue(state.displayed); assertTrue(state.currentItemReady)
        state.nextItem()
        assertTrue(state.displayed); assertFalse(state.currentItemReady)
        state.rendered()
        assertTrue(state.currentItemReady)
        state.reset()
        assertFalse(state.displayed); assertFalse(state.currentItemReady)
    }
    @Test fun snapshotWritesCoalesceAndFailedReplacementPreservesLastGoodFile() {
        val directory = Files.createTempDirectory("hal-snapshot").toFile()
        try {
            val tasks = ArrayDeque<Runnable>()
            val errors = mutableListOf<Exception>()
            val writer = SnapshotPersistence(Executor { tasks.add(it) }, errors::add)
            val file = File(directory, "camera_one.jpg").apply { writeText("original") }
            writer.save(file, 100) { it.writeText("old") }
            writer.save(file, 200) { it.writeText("new") }
            assertEquals(1, tasks.size)
            tasks.removeFirst().run()
            assertEquals("new", file.readText())
            writer.save(file, 300) { it.writeText("partial"); throw IOException("full") }
            tasks.removeFirst().run()
            assertEquals("new", file.readText())
            assertEquals(1, errors.size)
            // A failed timestamp can be retried; it was not marked saved before IO succeeded.
            writer.save(file, 300) { it.writeText("recovered") }
            tasks.removeFirst().run()
            assertEquals("recovered", file.readText())
            assertEquals(listOf(file), directory.listFiles()!!.toList())
        } finally { directory.deleteRecursively() }
    }

    @Test fun pruningRejectsLateRemovedCameraAndOldTimestampCannotReplaceNewerPicture() {
        val directory = Files.createTempDirectory("hal-prune").toFile()
        try {
            val tasks = ArrayDeque<Runnable>()
            val writer = SnapshotPersistence(Executor { tasks.add(it) }) { throw AssertionError(it) }
            val removed = File(directory, "camera_removed.jpg").apply { writeText("old") }
            val kept = File(directory, "camera_kept.jpg")
            writer.save(removed, 100) { it.writeText("late") }
            writer.prune(directory, setOf(kept))
            writer.save(removed, 200) { fail("Removed stream was saved") }
            writer.save(kept, 200) { it.writeText("latest") }
            writer.save(kept, 100) { fail("Older picture replaced a pending newer one") }
            tasks.removeFirst().run()
            assertFalse(removed.exists())
            assertEquals("latest", kept.readText())
            writer.save(kept, 100) { fail("Older picture replaced a saved newer one") }
            assertTrue(tasks.isEmpty())
        } finally { directory.deleteRecursively() }
    }

    @Test fun logSanitizingCoversAllSinksAndNestedThrowablePayloads() {
        val error = IOException("rtsp://person:private-camera@camera/live?signature=private-signature",
            IllegalStateException("Authorization: Bearer private-token password=private-password"))
        val safe = logMessage("https://host/image?unusual=private-query&x=private-x", error)
        for (secret in listOf("private-camera", "private-signature", "private-token", "private-password", "private-query", "private-x")) {
            assertFalse(secret, safe.contains(secret))
        }
        assertTrue(safe.contains("IOException"))
        assertTrue(safe.contains("RuntimeOwnershipTest"))
        assertFalse(sanitizeLog("{\"token\":\"secret-json\",\"password\":\"escaped\\\"secret\"}").contains("secret"))
    }

    @Test fun decodedPreferenceOnlyChangesForRelevantRawValueIncludingNull() {
        var calls = 0
        val value = PreferenceValue { raw -> calls++; raw?.uppercase() }
        assertNull(value.read(null)); assertNull(value.read(null)); assertEquals(1, calls)
        assertEquals("A", value.read("a")); assertEquals("A", value.read("a")); assertEquals(2, calls)
        assertNull(value.read(null)); assertEquals(3, calls)
    }
}
