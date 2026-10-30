package com.example.starclaim.game

import kotlin.math.hypot

const val NEUTRAL = -1
const val PLAYER = 0
const val BOT = 1

enum class PlanetType(val title: String, val base: Float) {
    TERRA("Терра", 1.0f),
    GAS("Газовый гигант", 1.8f),
    ICE("Ледяная", 1.2f),
    CORE("Ядро", 1.5f)
}

class Planet(
    val id: Int,
    val type: PlanetType,
    val x: Float,
    val y: Float
) {
    var owner: Int = NEUTRAL
    var garrison: Float = 30f
    var level: Int = 1
    var capture: Float = 0f
    var captureBy: Int = NEUTRAL
    var vulnerableUntil: Float = 0f
    var devastation: Float = 0f
    val links = mutableListOf<Int>()

    /** points per second at 1x pace */
    fun production(): Float =
        type.base * (1f + 0.15f * (level - 1)) * (1f - 0.3f * devastation)
}

class Fleet(
    val owner: Int,
    var power: Float,
    val from: Int,
    val to: Int,
    val departAt: Float,
    val arriveAt: Float
) {
    fun progress(now: Float): Float {
        val span = (arriveAt - departAt).coerceAtLeast(0.001f)
        return ((now - departAt) / span).coerceIn(0f, 1f)
    }
}

object MapGen {

    fun generate(seed: Long, count: Int = 20): List<Planet> {
        val rnd = java.util.Random(seed)
        val pts = mutableListOf<Pair<Float, Float>>()

        fun place(minDist: Float): Boolean {
            var guard = 0
            while (pts.size < count && guard < 20000) {
                guard++
                val x = 0.07f + rnd.nextFloat() * 0.86f
                val y = 0.07f + rnd.nextFloat() * 0.86f
                if (pts.all { (a, b) -> hypot(a - x, b - y) >= minDist }) pts.add(x to y)
            }
            return pts.size == count
        }

        var dist = 0.15f
        while (!place(dist) && dist > 0.05f) dist -= 0.01f

        val types = ArrayList<PlanetType>()
        types.add(PlanetType.CORE)
        repeat((count - 2).coerceAtLeast(0)) {
            types.add(
                when (rnd.nextInt(10)) {
                    0, 1, 2 -> PlanetType.GAS
                    3, 4 -> PlanetType.ICE
                    else -> PlanetType.TERRA
                }
            )
        }
        if (count > 1) types.add(PlanetType.CORE)

        val planets = pts.mapIndexed { i, (x, y) ->
            Planet(i, types.getOrElse(i) { PlanetType.TERRA }, x, y)
        }

        // link each planet to its 3 nearest neighbours
        for (p in planets) {
            val near = planets.asSequence()
                .filter { it.id != p.id }
                .sortedBy { hypot(it.x - p.x, it.y - p.y) }
                .take(3)
                .toList()
            for (n in near) {
                if (!p.links.contains(n.id)) {
                    p.links.add(n.id)
                    planets[n.id].links.add(p.id)
                }
            }
        }

        connectComponents(planets)
        return planets
    }

    private fun connectComponents(planets: List<Planet>) {
        while (true) {
            val visited = HashSet<Int>()
            val queue = ArrayDeque<Int>()
            visited.add(0)
            queue.add(0)
            while (queue.isNotEmpty()) {
                val id = queue.removeFirst()
                for (n in planets[id].links) if (visited.add(n)) queue.add(n)
            }
            if (visited.size == planets.size) return

            var bestA = -1
            var bestB = -1
            var bestD = Float.MAX_VALUE
            for (a in visited) {
                for (b in planets.indices) {
                    if (b in visited) continue
                    val d = hypot(planets[a].x - planets[b].x, planets[a].y - planets[b].y)
                    if (d < bestD) {
                        bestD = d
                        bestA = a
                        bestB = b
                    }
                }
            }
            if (bestA < 0) return
            planets[bestA].links.add(bestB)
            planets[bestB].links.add(bestA)
        }
    }
}
