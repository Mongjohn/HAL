package com.evofactory.hal

import org.junit.Assert.*
import org.junit.Test

class ScreensaverBubblesTest {
    @Test fun retainedDrawingCallbacksDoNotIndexRemovedOrReorderedPlayers() {
        val scene = ScreensaverBubbleMotion()
        val clock = BubbleSize(100f, 100f)
        val player = BubbleSize(180f, 80f)
        scene.configure(1000f, 600f, listOf(clock, player, player), 1f, listOf("clock", "a", "b"))
        val drawA = { scene.bodyFor("a") }
        val drawB = { scene.bodyFor("b") }
        assertSame(scene.bodies[1], drawA())
        scene.configure(1000f, 600f, listOf(clock, player, player), 1f, listOf("clock", "b", "a"))
        assertSame(scene.bodies[2], drawA())
        assertSame(scene.bodies[1], drawB())
        scene.configure(1000f, 600f, listOf(clock), 1f, listOf("clock"))
        assertNull(drawA())
        assertNull(drawB())
        assertSame(scene.bodies[0], scene.bodyFor("clock"))
        scene.configure(1000f, 600f, emptyList(), 1f, emptyList())
        assertNull(scene.bodyFor("clock"))
    }

    @Test fun narrowPlayersBounceVerticallyEvenWhenVisuallyFarApart() {
        val scene = ScreensaverBubbleMotion()
        scene.configure(1000f, 600f, listOf(BubbleSize(100f, 100f), BubbleSize(180f, 80f), BubbleSize(220f, 80f)), 1f)
        scene.bodies[0].apply { x = 450f; y = 450f; vx = 0f; vy = 0f }
        scene.bodies[1].apply { x = 0f; y = 100f; vx = 0f; vy = 20f }
        scene.bodies[2].apply { x = 780f; y = 190f; vx = 0f; vy = -20f }
        scene.step(0.25)
        assertEquals(-20f, scene.bodies[1].vy, 0f)
        assertEquals(20f, scene.bodies[2].vy, 0f)
        assertGeometry(scene, 1000f, 600f)
    }

    @Test fun clockPassesThroughPlayerOrderingEnvelopeUntilVisibleEdgesTouch() {
        val scene = ScreensaverBubbleMotion()
        scene.configure(1000f, 600f, listOf(BubbleSize(100f, 100f), BubbleSize(180f, 80f)), 1f)
        scene.bodies[0].apply { x = 100f; y = 100f; vx = 20f; vy = 0f }
        scene.bodies[1].apply { x = 300f; y = 100f; vx = -20f; vy = 0f }
        repeat(9) { scene.step(0.25) }
        assertEquals(20f, scene.bodies[0].vx, 0f)
        scene.step(0.25)
        assertEquals(-20f, scene.bodies[0].vx, 0f)
        assertEquals(20f, scene.bodies[1].vx, 0f)
        assertGeometry(scene, 1000f, 600f)
    }

    @Test fun mixedContentWidthsStayOrderedAndBoundedOverLongRuns() {
        val scene = ScreensaverBubbleMotion()
        val sizes = listOf(BubbleSize(260f, 240f), BubbleSize(180f, 90f), BubbleSize(420f, 108f), BubbleSize(240f, 80f))
        scene.configure(960f, 540f, sizes, 1f)
        repeat(7200) { scene.step(1.0 / 30); assertGeometry(scene, 960f, 540f) }
        val velocity = scene.bodies.map { it.vx to it.vy }
        scene.configure(960f, 540f, sizes.mapIndexed { index, size -> if (index == 1) size.copy(width = 320f) else size }, 1f)
        assertEquals(velocity, scene.bodies.map { it.vx to it.vy })
        repeat(1200) { scene.step(1.0 / 30); assertGeometry(scene, 960f, 540f) }
    }

    private fun scene(width: Int, height: Int, count: Int, density: Float = 1f): ScreensaverBubbleMotion {
        val clock = BubbleSize(260f * density, 240f * density)
        val fit = screensaverBubbleFit(width, height, clock, count, density)
        val sizes = listOf(BubbleSize(clock.width * fit.clockScale, clock.height * fit.clockScale)) +
            List(count) { BubbleSize(fit.mediaWidth.toFloat(), fit.mediaHeight.toFloat()) }
        return ScreensaverBubbleMotion().apply { configure(width.toFloat(), height.toFloat(), sizes, density) }
    }

    private fun assertGeometry(scene: ScreensaverBubbleMotion, width: Float, height: Float) {
        for ((index, body) in scene.bodies.withIndex()) {
            assertTrue("$index left", body.x >= -0.05f)
            assertTrue("$index right", body.x + body.size.width <= width + 0.05f)
            assertTrue("$index top", body.y >= -0.05f)
            assertTrue("$index bottom", body.y + body.size.height <= height + 0.05f)
        }
        for (a in scene.bodies.indices) for (b in a + 1 until scene.bodies.size) {
            val first = scene.bodies[a]; val second = scene.bodies[b]
            val x = minOf(first.x + first.size.width, second.x + second.size.width) - maxOf(first.x, second.x)
            val y = minOf(first.y + first.size.height, second.y + second.size.height) - maxOf(first.y, second.y)
            assertTrue("overlap $a/$b: $x x $y", x <= 0.05f || y <= 0.05f)
        }
        scene.bodies.drop(1).zipWithNext().forEach { (a, b) ->
            assertTrue("media switched order", a.y + a.size.height <= b.y + 0.05f)
        }
    }

    @Test fun mediaContentBudgetFitsDenseAndSmallDisplaysWithoutHalfScreenMinimum() {
        for ((width, height) in listOf(960 to 540, 3840 to 2160, 320 to 180)) for (count in 1..12) {
            val fit = screensaverBubbleFit(width, height, BubbleSize(260f, 240f), count, 1f)
            assertTrue(fit.mediaWidth in 1..minOf(width, 416))
            assertTrue(fit.mediaHeight > 0)
            assertTrue(fit.mediaHeight * count <= height)
            assertGeometry(scene(width, height, count), width.toFloat(), height.toFloat())
        }
        assertTrue(screensaverBubbleFit(960, 540, BubbleSize(260f, 240f), 2, 1f).mediaWidth < 480)
    }

    @Test fun collidingBubblesExchangeDirectionWithoutOverlap() {
        val scene = ScreensaverBubbleMotion()
        scene.configure(1000f, 600f, listOf(BubbleSize(100f, 100f), BubbleSize(600f, 100f)), 1f)
        scene.bodies[0].apply { x = 100f; y = 100f; vx = 10f; vy = 0f }
        scene.bodies[1].apply { x = 300f; y = 100f; vx = -10f; vy = 0f }
        repeat(21) { scene.step(0.25); assertGeometry(scene, 1000f, 600f) }
        assertEquals(-10f, scene.bodies[0].vx, 0f)
        assertEquals(10f, scene.bodies[1].vx, 0f)
    }

    @Test fun outlineReachesBothDisplayEdgesAndReflects() {
        val scene = ScreensaverBubbleMotion()
        scene.configure(400f, 300f, listOf(BubbleSize(100f, 100f)), 1f)
        val body = scene.bodies.single()
        body.apply { x = 0f; y = 0f; vx = -10f; vy = -10f }
        scene.step(0.1)
        assertTrue(body.vx > 0 && body.vy > 0)
        body.apply { x = 300f; y = 200f; vx = 10f; vy = 10f }
        scene.step(0.1)
        assertTrue(body.vx < 0 && body.vy < 0)
        assertGeometry(scene, 400f, 300f)
    }

    @Test fun independentMotionRemainsOrderedAndSeparatedAcrossLongRunsAndResize() {
        for (count in listOf(0, 1, 3, 6, 10)) {
            val scene = scene(960, 540, count)
            val start = scene.bodies.map { it.x to it.y }
            repeat(6000) { scene.step(1.0 / 30); assertGeometry(scene, 960f, 540f) }
            val late = scene.bodies.map { it.x to it.y }
            repeat(1200) { scene.step(1.0 / 30); assertGeometry(scene, 960f, 540f) }
            assertNotEquals("motion stalled in a contact chain", late, scene.bodies.map { it.x to it.y })
            assertNotEquals(start, scene.bodies.map { it.x to it.y })
            val fit = screensaverBubbleFit(640, 360, BubbleSize(260f, 240f), count, 1f)
            scene.configure(640f, 360f, listOf(BubbleSize(260f * fit.clockScale, 240f * fit.clockScale)) +
                List(count) { BubbleSize(fit.mediaWidth.toFloat(), fit.mediaHeight.toFloat()) }, 1f)
            repeat(600) { scene.step(1.0 / 30); assertGeometry(scene, 640f, 360f) }
        }
    }

    @Test fun unchangedLayoutKeepsPositionsAndVelocitiesDuringMetadataUpdates() {
        val scene = scene(960, 540, 3)
        repeat(40) { scene.step(1.0 / 30) }
        val bodies = scene.bodies
        val snapshot = bodies.map { listOf(it.x, it.y, it.vx, it.vy) }
        scene.configure(960f, 540f, bodies.map { it.size }, 1f)
        assertSame(bodies, scene.bodies)
        assertEquals(snapshot, scene.bodies.map { listOf(it.x, it.y, it.vx, it.vy) })
    }

    @Test fun highDensityAndCornerContactsRemainBoundedWithoutMediaPassing() {
        val scene = scene(3840, 2160, 6, 4f)
        repeat(7200) { scene.step(1.0 / 30); assertGeometry(scene, 3840f, 2160f) }
    }

    @Test fun contentSizeUpdatesPreserveTrajectoriesInsteadOfReseedingTheScene() {
        val scene = scene(960, 540, 3)
        repeat(100) { scene.step(1.0 / 30) }
        val previous = scene.bodies.map { listOf(it.x, it.y, it.vx, it.vy) }
        val sizes = scene.bodies.mapIndexed { index, body ->
            if (index == 0) body.size.copy(width = body.size.width + 2f) else body.size
        }
        scene.configure(960f, 540f, sizes, 1f)
        assertEquals(previous, scene.bodies.map { listOf(it.x, it.y, it.vx, it.vy) })
        scene.step(0.1)
        scene.bodies.forEachIndexed { index, body ->
            assertEquals(previous[index][0] + previous[index][2] * 0.1f, body.x, 0.001f)
            assertEquals(previous[index][1] + previous[index][3] * 0.1f, body.y, 0.001f)
        }
    }

    @Test fun briefPlayerVisibilityChangesRestoreTheSamePlayerMotionByIdentity() {
        val scene = ScreensaverBubbleMotion()
        val sizes = listOf(BubbleSize(260f, 240f)) + List(3) { BubbleSize(481f, 108f) }
        val ids = listOf("clock", "player.a", "player.b", "player.c")
        scene.configure(960f, 540f, sizes, 1f, ids)
        repeat(60) { scene.step(1.0 / 30) }
        val before = scene.bodies.map { listOf(it.x, it.y, it.vx, it.vy) }
        scene.configure(960f, 540f, listOf(sizes[0], sizes[1], sizes[3]), 1f, listOf(ids[0], ids[1], ids[3]))
        assertEquals(before[3], scene.bodies[2].let { listOf(it.x, it.y, it.vx, it.vy) })
        scene.configure(960f, 540f, sizes, 1f, ids)
        assertEquals(before, scene.bodies.map { listOf(it.x, it.y, it.vx, it.vy) })
        assertGeometry(scene, 960f, 540f)
    }

    @Test fun realPlayerOrderChangesKeepVelocitiesWhileMaintainingSeparation() {
        val scene = ScreensaverBubbleMotion()
        val sizes = listOf(BubbleSize(260f, 240f)) + List(3) { BubbleSize(481f, 108f) }
        val ids = listOf("clock", "player.a", "player.b", "player.c")
        scene.configure(960f, 540f, sizes, 1f, ids)
        repeat(100) { scene.step(1.0 / 30) }
        val velocity = ids.zip(scene.bodies.map { it.vx to it.vy }).toMap()
        val reordered = listOf("clock", "player.c", "player.a", "player.b")
        scene.configure(960f, 540f, sizes, 1f, reordered)
        reordered.zip(scene.bodies).forEach { (id, body) -> assertEquals(velocity[id], body.vx to body.vy) }
        repeat(600) { scene.step(1.0 / 30); assertGeometry(scene, 960f, 540f) }
    }
}
