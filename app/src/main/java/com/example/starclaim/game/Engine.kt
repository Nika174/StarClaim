package com.example.starclaim.game

import kotlin.math.min

/**
 * Deterministic simulation. Everything advances from tick(dt) — no randomness
 * after map generation, so the same seed + same inputs always replay identically.
 */
class GameEngine(seed: Long, private val planetCount: Int = 20) {

    companion object {
        const val FLEET_COST = 50f
        const val FLEET_PACK = 10f
        const val HOP_SECONDS = 8f
        const val WIN_PLANETS = 12
        const val INCOME_PACE = 6f
    }

    val planets: List<Planet> = MapGen.generate(seed, planetCount)
    val fleets = mutableListOf<Fleet>()
    /** orbit[planetId][owner] — power parked in orbit */
    val orbit: Array<FloatArray> = Array(planets.size) { FloatArray(2) }
    val credits = floatArrayOf(200f, 200f)
    var time = 0f
    var winner = -1
    var botTimer = 10f
    val events = ArrayDeque<String>()

    init {
        val startP = planets.minByOrNull { it.x } ?: planets.first()
        val startB = planets.maxByOrNull { it.x } ?: planets.last()
        startP.owner = PLAYER
        startP.garrison = 45f
        startP.level = 2
        if (startB.id != startP.id) {
            startB.owner = BOT
            startB.garrison = 45f
            startB.level = 2
        }
    }

    fun ownedCount(owner: Int): Int = planets.count { it.owner == owner }

    fun tick(dt: Float) {
        if (winner >= 0) return
        val step = dt.coerceIn(0f, 0.25f)
        time += step
        income(step)
        moveFleets()
        resolveOrbits(step)
        bot(step)
        checkWinner()
    }

    private fun income(dt: Float) {
        val counts = IntArray(2)
        for (p in planets) if (p.owner >= 0) counts[p.owner]++

        for (p in planets) {
            val o = p.owner
            if (o < 0) continue
            val damp = 1f / (1f + 0.08f * counts[o])
            val blockaded = if (orbit[p.id][1 - o] > 0.5f) 0.3f else 1f
            credits[o] += p.production() * damp * blockaded * INCOME_PACE * dt
        }
    }

    private fun moveFleets() {
        val arrived = fleets.filter { time >= it.arriveAt }
        if (arrived.isEmpty()) return
        for (f in arrived) {
            orbit[f.to][f.owner] += f.power
        }
        fleets.removeAll { time >= it.arriveAt }
    }

    private fun resolveOrbits(dt: Float) {
        for (p in planets) {
            val enemyOwner = (0..1).firstOrNull { it != p.owner && orbit[p.id][it] > 0.5f } ?: -1
            if (enemyOwner < 0) {
                if (p.capture > 0f) {
                    p.capture = (p.capture - 20f * dt).coerceAtLeast(0f)
                    if (p.capture <= 0f) p.captureBy = NEUTRAL
                }
                continue
            }

            val vulnerable = if (time < p.vulnerableUntil) 0.5f else 1f
            val attacker = orbit[p.id][enemyOwner]
            val ownOrbit = if (p.owner >= 0) orbit[p.id][p.owner] else 0f
            val defender = p.garrison + ownOrbit

            // attacker loses to defensive fire, defender loses to bombardment
            val attLoss = min(attacker, defender * 0.5f * dt)
            val defLoss = attacker * 0.8f * dt * vulnerable

            orbit[p.id][enemyOwner] = (attacker - attLoss).coerceAtLeast(0f)

            var remaining = defLoss
            if (p.owner >= 0 && orbit[p.id][p.owner] > 0f) {
                val take = min(orbit[p.id][p.owner], remaining)
                orbit[p.id][p.owner] -= take
                remaining -= take
            }
            p.garrison = (p.garrison - remaining).coerceAtLeast(0f)

            if (p.garrison <= 0f && orbit[p.id][enemyOwner] > 1f) {
                p.capture += 12f * dt * vulnerable
                p.captureBy = enemyOwner
                if (p.capture >= 100f) capturePlanet(p, enemyOwner)
            }
        }
    }

    private fun capturePlanet(p: Planet, newOwner: Int) {
        p.owner = newOwner
        p.garrison = 15f
        p.capture = 0f
        p.captureBy = NEUTRAL
        p.vulnerableUntil = time + 60f
        p.devastation = (p.devastation + 0.25f).coerceAtMost(1f)
        orbit[p.id][newOwner] = (orbit[p.id][newOwner] - 10f).coerceAtLeast(0f)
        events.addLast(
            if (newOwner == PLAYER) "Захвачена планета ${p.id + 1}"
            else "Бот захватил планету ${p.id + 1}"
        )
        while (events.size > 4) events.removeFirst()
    }

    // ---------------- player actions ----------------

    fun canBuild(planetId: Int): Boolean =
        planetId in planets.indices &&
            planets[planetId].owner == PLAYER &&
            credits[PLAYER] >= FLEET_COST

    fun buildFleet(planetId: Int): Boolean {
        if (!canBuild(planetId)) return false
        credits[PLAYER] -= FLEET_COST
        orbit[planetId][PLAYER] += FLEET_PACK
        return true
    }

    fun canSend(from: Int, to: Int): Boolean =
        from in planets.indices && to in planets.indices &&
            planets[from].owner == PLAYER &&
            planets[from].links.contains(to) &&
            orbit[from][PLAYER] >= 1f

    /** Sends 70% of the fleet parked at [from] towards the neighbouring [to]. */
    fun sendFleet(from: Int, to: Int): Boolean {
        if (!canSend(from, to)) return false
        val power = orbit[from][PLAYER] * 0.7f
        if (power < 1f) return false
        orbit[from][PLAYER] -= power
        fleets.add(Fleet(PLAYER, power, from, to, time, time + HOP_SECONDS))
        return true
    }

    // ---------------- bot ----------------

    private fun bot(dt: Float) {
        botTimer -= dt
        if (botTimer > 0f) return
        botTimer = 3.5f

        if (credits[BOT] >= FLEET_COST * 1.6f) {
            val owned = planets.filter { it.owner == BOT }
            if (owned.isNotEmpty()) {
                val frontier = owned.maxByOrNull { p -> p.links.count { planets[it].owner != BOT } }
                    ?: owned.first()
                credits[BOT] -= FLEET_COST
                orbit[frontier.id][BOT] += FLEET_PACK
            }
        }

        for (p in planets) {
            if (p.owner != BOT) continue
            val power = orbit[p.id][BOT]
            if (power < 12f) continue

            val target = p.links.asSequence()
                .map { planets[it] }
                .filter { it.owner != BOT }
                .minByOrNull { it.garrison + (it.owner.let { o -> if (o >= 0) orbit[it.id][o] else 0f }) }
                ?: continue

            val defence = target.garrison + (if (target.owner >= 0) orbit[target.id][target.owner] else 0f)
            if (power >= defence * 1.45f || target.garrison < 8f) {
                val sent = power * 0.7f
                orbit[p.id][BOT] -= sent
                fleets.add(Fleet(BOT, sent, p.id, target.id, time, time + HOP_SECONDS))
            }
        }
    }

    private fun checkWinner() {
        val p = ownedCount(PLAYER)
        val b = ownedCount(BOT)
        if (p >= WIN_PLANETS) winner = PLAYER
        else if (b >= WIN_PLANETS) winner = BOT
        else if (b == 0) winner = PLAYER
        else if (p == 0) winner = BOT
    }
}
