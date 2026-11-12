package com.evofactory.hal

import org.junit.Assert.assertEquals
import org.junit.Test

class DockTileKeysTest {
    @Test
    fun defaultsToFirstAndFollowsTheSavedOrderAfterReordering() {
        val apps = listOf("video", "music")
        assertEquals(listOf(ALL_APPS_TILE_KEY) + apps, dockTileKeys(apps, apps, true))

        val order = listOf("video", ALL_APPS_TILE_KEY, "music")
        assertEquals(order, dockTileKeys(apps, order, true))
        assertEquals(apps, dockTileKeys(apps, order, false))
        assertEquals(listOf(ALL_APPS_TILE_KEY, "music"), dockTileKeys(listOf("music"), order, true))
    }
}
