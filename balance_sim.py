"""Headless port of StarClaim's tick loop — sanity-check the balance numbers.
Not shipped code; mirrors Engine.kt formulas to see if captures actually happen."""

import random

FLEET_COST = 50.0
FLEET_PACK = 10.0
HOP = 8.0
PACE = 6.0
WIN = 12


class P:
    def __init__(self, i, base=1.0):
        self.id = i
        self.base = base
        self.owner = -1
        self.garrison = 30.0
        self.level = 1
        self.capture = 0.0
        self.capture_by = -1
        self.vulnerable_until = 0.0
        self.devast = 0.0
        self.links = []

    def production(self):
        return self.base * (1 + 0.15 * (self.level - 1)) * (1 - 0.3 * self.devast)


def run(seed=1, seconds=240, player_active=True, verbose=True):
    rnd = random.Random(seed)
    n = 20
    planets = [P(i, rnd.choice([1.0, 1.2, 1.5, 1.8])) for i in range(n)]
    for p in planets:
        near = sorted([q for q in planets if q.id != p.id],
                      key=lambda q: abs(q.id - p.id))[:3]
        for q in near:
            if q.id not in p.links:
                p.links.append(q.id)
                planets[q.id].links.append(p.id)

    planets[0].owner = 0
    planets[0].garrison = 45
    planets[19].owner = 1
    planets[19].garrison = 45

    orbit = [[0.0, 0.0] for _ in range(n)]
    credits = [200.0, 200.0]
    fleets = []  # (owner, power, to, arrive)
    t = 0.0
    bot_timer = 10.0

    def counts():
        c = [0, 0]
        for p in planets:
            if p.owner >= 0:
                c[p.owner] += 1
        return c

    log = []
    dt = 0.1
    while t < seconds:
        t += dt
        c = counts()

        # income
        for p in planets:
            o = p.owner
            if o < 0:
                continue
            damp = 1 / (1 + 0.08 * c[o])
            blocked = 0.3 if orbit[p.id][1 - o] > 0.5 else 1.0
            credits[o] += p.production() * damp * blocked * PACE * dt

        # fleets
        arrived = [f for f in fleets if t >= f[3]]
        for f in arrived:
            orbit[f[2]][f[0]] += f[1]
        fleets = [f for f in fleets if t < f[3]]

        # combat
        for p in planets:
            enemy = -1
            for o in range(2):
                if o != p.owner and orbit[p.id][o] > 0.5:
                    enemy = o
                    break
            if enemy < 0:
                p.capture = max(0.0, p.capture - 20 * dt)
                if p.capture == 0:
                    p.capture_by = -1
                continue

            vuln = 0.5 if t < p.vulnerable_until else 1.0
            attacker = orbit[p.id][enemy]
            own = orbit[p.id][p.owner] if p.owner >= 0 else 0.0
            defender = p.garrison + own

            att_loss = min(attacker, defender * 0.5 * dt)
            def_loss = attacker * 0.8 * dt * vuln
            orbit[p.id][enemy] = max(0.0, attacker - att_loss)

            rem = def_loss
            if p.owner >= 0 and orbit[p.id][p.owner] > 0:
                take = min(orbit[p.id][p.owner], rem)
                orbit[p.id][p.owner] -= take
                rem -= take
            p.garrison = max(0.0, p.garrison - rem)

            if p.garrison <= 0 and orbit[p.id][enemy] > 1:
                p.capture += 12 * dt * vuln
                p.capture_by = enemy
                if p.capture >= 100:
                    p.owner = enemy
                    p.garrison = 15
                    p.capture = 0
                    p.capture_by = -1
                    p.vulnerable_until = t + 60
                    p.devast = min(1.0, p.devast + 0.25)
                    orbit[p.id][enemy] = max(0.0, orbit[p.id][enemy] - 10)
                    log.append((round(t, 1), enemy, p.id))

        # bot
        bot_timer -= dt
        if bot_timer <= 0:
            bot_timer = 3.5
            if credits[1] >= FLEET_COST * 1.6:
                owned = [p for p in planets if p.owner == 1]
                if owned:
                    fr = max(owned, key=lambda p: sum(1 for l in p.links if planets[l].owner != 1))
                    credits[1] -= FLEET_COST
                    orbit[fr.id][1] += FLEET_PACK

            for p in planets:
                if p.owner != 1:
                    continue
                pw = orbit[p.id][1]
                if pw < 12:
                    continue
                cands = [planets[l] for l in p.links if planets[l].owner != 1]
                if not cands:
                    continue
                tgt = min(cands, key=lambda q: q.garrison + (orbit[q.id][q.owner] if q.owner >= 0 else 0))
                defence = tgt.garrison + (orbit[tgt.id][tgt.owner] if tgt.owner >= 0 else 0)
                if pw >= defence * 1.45 or tgt.garrison < 8:
                    sent = pw * 0.7
                    orbit[p.id][1] -= sent
                    fleets.append((1, sent, tgt.id, t + HOP))

        c = counts()
        if c[0] >= WIN or c[1] >= WIN:
            return t, c, log

    return t, counts(), log


for seed in (1, 2, 3):
    t, c, log = run(seed, seconds=300)
    print(f"seed {seed}: через {t:.0f}s  планеты игрок={c[0]} бот={c[1]}  захватов={len(log)}")
    if log:
        print("   первые захваты:", log[:5])
