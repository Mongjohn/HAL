package com.evofactory.hal

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

internal data class BubbleSize(val width: Float, val height: Float)
internal data class BubbleBody(val size: BubbleSize, var x: Float, var y: Float, var vx: Float, var vy: Float)

internal data class ScreensaverBubbleFit(val clockScale: Float, val mediaWidth: Int, val mediaHeight: Int)

/** Wide media bubbles cannot pass horizontally, so their vertical dashboard order is physical. */
internal fun screensaverBubbleFit(width: Int, height: Int, clock: BubbleSize, players: Int, density: Float): ScreensaverBubbleFit {
    val gap = min(12f * density, min(width, height) / 16f)
    val clockBudget = if (players > 0) (width - 3 * gap) / 2f else width.toFloat()
    val scale = minOf(1f, clockBudget.coerceAtLeast(1f) / clock.width.coerceAtLeast(1f),
        (height - min(48f * density, height / 4f)).coerceAtLeast(1f) / clock.height.coerceAtLeast(1f))
    if (players == 0) return ScreensaverBubbleFit(scale, 0, 0)
    val mediaWidth = min(width - clock.width * scale - gap, max(416f * density, width / 2f + 1f))
    val rowGap = min(12f * density, height / (players * 4f))
    val available = height - min(48f * density, height / 4f) - rowGap * (players - 1)
    return ScreensaverBubbleFit(scale, mediaWidth.toInt().coerceAtLeast(1),
        min(108f * density, available / players).toInt().coerceAtLeast(1))
}

/** Continuous rectangle collisions: no tunnelling, overlap correction, or per-bubble timer. */
internal class ScreensaverBubbleMotion {
    var bodies: List<BubbleBody> = emptyList()
        private set
    private var viewport = BubbleSize(0f, 0f)
    private var sizes = emptyList<BubbleSize>()
    private var identities = emptyList<String>()
    private val rememberedBodies = linkedMapOf<String, BubbleBody>()
    private var density = 1f
    private val interval = DoubleArray(2)

    fun configure(width: Float, height: Float, next: List<BubbleSize>, pixelDensity: Float,
        ids: List<String> = next.indices.map(Int::toString)) {
        require(ids.size == next.size && ids.distinct().size == ids.size)
        val screen = BubbleSize(width, height)
        if (screen == viewport && next == sizes && density == pixelDensity && identities == ids) return
        identities.zip(bodies).forEach { (id, body) -> rememberedBodies[id] = body }
        viewport = screen
        sizes = next
        identities = ids
        density = pixelDensity
        if (next.isEmpty()) { bodies = emptyList(); return }
        val clock = next.first()
        val media = next.drop(1)
        val gap = minOf(12f * density, min(width, height) / 16f, height / (media.size.coerceAtLeast(1) * 4f))
        val columnHeight = media.sumOf { it.height.toDouble() }.toFloat() + gap * (media.size - 1).coerceAtLeast(0)
        val sideRoom = if (media.isEmpty()) (width - clock.width).coerceAtLeast(0f)
            else (width - media.first().width - clock.width - gap).coerceAtLeast(0f)
        var y = (height - columnHeight) / 2f
        val initial = listOf(BubbleBody(clock, sideRoom * 0.17f, (height - clock.height).coerceAtLeast(0f) * 0.29f,
            5.2f * density, 3.3f * density)) + media.mapIndexed { index, size ->
            BubbleBody(size, clock.width + gap + sideRoom * (0.65f + (index % 3) * 0.1f), y,
                -(4.1f + (index % 3) * 0.4f) * density, (if (index % 2 == 0) 2.9f else -3.4f) * density)
                .also { y += size.height + gap }
        }
        bodies = initial.mapIndexed { index, seed ->
            rememberedBodies[ids[index]]?.let { old ->
                old.copy(size = seed.size,
                    x = old.x.coerceIn(0f, (width - seed.size.width).coerceAtLeast(0f)),
                    y = old.y.coerceIn(0f, (height - seed.size.height).coerceAtLeast(0f)))
            } ?: seed
        }
        // Metadata remeasurement is not a new scene. Leave valid positions/velocities untouched.
        // Genuine membership/order/viewport changes may require packing, but never reseed velocity.
        val mediaBodies = bodies.drop(1)
        if (mediaBodies.zipWithNext().any { (a, b) -> a.y + a.size.height > b.y }) {
            var bottom = 0f
            mediaBodies.forEach { body -> body.y = max(body.y, bottom); bottom = body.y + body.size.height }
            var top = height
            mediaBodies.asReversed().forEach { body -> body.y = min(body.y, top - body.size.height); top = body.y }
        }
        val clockBody = bodies.first()
        fun overlaps(a: BubbleBody, b: BubbleBody) = a.x < b.x + b.size.width && b.x < a.x + a.size.width &&
            a.y < b.y + b.size.height && b.y < a.y + a.size.height
        if (mediaBodies.any { overlaps(clockBody, it) }) {
            // Resolve only an obstructed clock; keep all media trajectories where space permits.
            val left = mediaBodies.minOf { it.x } - clock.width
            val right = mediaBodies.maxOf { it.x + it.size.width }
            when {
                left >= 0f -> clockBody.x = min(clockBody.x, left)
                right + clock.width <= width -> clockBody.x = max(clockBody.x, right)
                else -> {
                    clockBody.x = min(clockBody.x, width - clock.width - media.first().width - gap).coerceAtLeast(0f)
                    mediaBodies.forEach { it.x = max(it.x, clockBody.x + clock.width + gap).coerceAtMost(width - it.size.width) }
                }
            }
        }
        ids.zip(bodies).forEach { (id, body) -> rememberedBodies[id] = body }
        // Keep short visibility interruptions warm, without growing for every view ever configured.
        rememberedBodies.keys.filter { it !in ids }.dropLast(32).forEach(rememberedBodies::remove)
    }

    fun step(seconds: Double) {
        var remaining = seconds.coerceIn(0.0, 0.25)
        // At very high counts a contact chain may need several frames; never tunnel through it.
        repeat(128) {
            if (remaining <= 1e-9) return
            var earliest = remaining + 1.0
            var first = -1
            var second = -1
            var horizontal = false
            fun offer(time: Double, a: Int, b: Int, x: Boolean) {
                // Float layer coordinates can round a contact a fraction of a pixel past zero.
                if (time >= -1e-3 && time <= remaining + 1e-7 && time < earliest) {
                    earliest = time.coerceIn(0.0, remaining); first = a; second = b; horizontal = x
                }
            }
            bodies.forEachIndexed { index, body ->
                if (body.vx > 0) offer(((viewport.width - body.size.width - body.x) / body.vx).toDouble(), index, -1, true)
                else if (body.vx < 0) offer((-body.x / body.vx).toDouble(), index, -1, true)
                if (body.vy > 0) offer(((viewport.height - body.size.height - body.y) / body.vy).toDouble(), index, -1, false)
                else if (body.vy < 0) offer((-body.y / body.vy).toDouble(), index, -1, false)
            }
            for (a in bodies.indices) for (b in a + 1 until bodies.size) {
                val left = bodies[a]; val right = bodies[b]
                if (!collisionInterval(left.x, left.size.width, right.x, right.size.width, left.vx - right.vx)) continue
                val xEntry = interval[0]; val xExit = interval[1]
                if (!collisionInterval(left.y, left.size.height, right.y, right.size.height, left.vy - right.vy)) continue
                val yEntry = interval[0]; val yExit = interval[1]
                val entry = max(xEntry, yEntry)
                if (entry <= min(xExit, yExit)) offer(entry, a, b, xEntry >= yEntry)
            }
            val travel = if (first == -1) remaining else earliest
            bodies.forEach { body ->
                body.x = (body.x + body.vx * travel).toFloat().coerceIn(0f, (viewport.width - body.size.width).coerceAtLeast(0f))
                body.y = (body.y + body.vy * travel).toFloat().coerceIn(0f, (viewport.height - body.size.height).coerceAtLeast(0f))
            }
            if (first == -1) return
            remaining -= travel
            val a = bodies[first]
            if (second == -1) {
                if (horizontal) { a.x = if (a.vx < 0) 0f else viewport.width - a.size.width; a.vx = -a.vx }
                else { a.y = if (a.vy < 0) 0f else viewport.height - a.size.height; a.vy = -a.vy }
            } else {
                val b = bodies[second]
                if (horizontal) {
                    if (a.x < b.x) a.x = b.x - a.size.width else b.x = a.x - b.size.width
                    val velocity = a.vx; a.vx = b.vx; b.vx = velocity
                } else {
                    if (a.y < b.y) a.y = b.y - a.size.height else b.y = a.y - b.size.height
                    val velocity = a.vy; a.vy = b.vy; b.vy = velocity
                }
            }
        }
    }

    private fun collisionInterval(a: Float, aSize: Float, b: Float, bSize: Float, velocity: Float): Boolean {
        if (abs(velocity) < 1e-7f) {
            if (a + aSize <= b || b + bSize <= a) return false
            interval[0] = Double.NEGATIVE_INFINITY; interval[1] = Double.POSITIVE_INFINITY
            return true
        }
        val near = if (velocity > 0) b - a - aSize else b + bSize - a
        val far = if (velocity > 0) b + bSize - a else b - a - aSize
        interval[0] = near.toDouble() / velocity; interval[1] = far.toDouble() / velocity
        return true
    }
}
