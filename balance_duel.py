"""Both sides play the same simple policy — checks the game is winnable, not a coin flip."""
import random

FLEET_COST = 50.0
FLEET_PACK = 10.0
HOP, PACE, WIN = 8.0, 6.0, 12


class P:
    def __init__(self, i, base):
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


def run(seed, seconds=600):
    rnd = random.Random(seed)
    planets = [P(i, rnd.choice([1.0, 1.2, 1.5, 1.8])) for i in range(20)]
    for p in planets:
        for q in sorted([q for q in planets if q.id != p.id], key=lambda q: abs(q.id - p.id))[:3]:
            if q.id not in p.links:
                p.links.append(q.id)
                planets[q.id].links.append(p.id)

    planets[0].owner, planets[0].garrison = 0, 45
    planets[19].owner, planets[19].garrison = 1, 45
    orbit = [[0.0, 0.0] for _ in range(20)]
    credits = [200.0, 200.0]
    fleets = []
    t = 0.0
    timers = [10.0, 10.0]
    dt = 0.1
    history = []

    def counts():
        c = [0, 0]
        for p in planets:
            if p.owner >= 0:
                c[p.owner] += 1
        return c

    while t < seconds:
        t += dt
        c = counts()
        for p in planets:
            if p.owner < 0:
                continue
            damp = 1 / (1 + 0.08 * c[p.owner])
            blocked = 0.3 if orbit[p.id][1 - p.owner] > 0.5 else 1.0
            credits[p.owner] += p.production() * damp * blocked * PACE * dt

        for f in [f for f in fleets if t >= f[3]]:
            orbit[f[2]][f[0]] += f[1]
        fleets = [f for f in fleets if t < f[3]]

        for p in planets:
            enemy = next((o for o in range(2) if o != p.owner and orbit[p.id][o] > 0.5), -1)
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
                    p.capture, p.capture_by = 0.0, -1
                    p.vulnerable_until = t + 60
                    p.devast = min(1.0, p.devast + 0.25)
                    orbit[p.id][enemy] = max(0.0, orbit[p.id][enemy] - 10)

        for me in (0, 1):
            timers[me] -= dt
            if timers[me] > 0:
                continue
            timers[me] = 3.5
            if credits[me] >= FLEET_COST * 1.6:
                owned = [p for p in planets if p.owner == me]
                if owned:
                    fr = max(owned, key=lambda p: sum(1 for l in p.links if planets[l].owner != me))
                    credits[me] -= FLEET_COST
                    orbit[fr.id][me] += FLEET_PACK

            for p in planets:
                if p.owner != me:
                    continue
                pw = orbit[p.id][me]
                if pw < 12:
                    continue
                cands = [planets[l] for l in p.links if planets[l].owner != me]
                if not cands:
                    continue
                tgt = min(cands, key=lambda q: q.garrison + (orbit[q.id][q.owner] if q.owner >= 0 else 0))
                defence = tgt.garrison + (orbit[tgt.id][tgt.owner] if tgt.owner >= 0 else 0)
                if pw >= defence * 1.45 or tgt.garrison < 8:
                    sent = pw * 0.7
                    orbit[p.id][me] -= sent
                    fleets.append((me, sent, tgt.id, t + HOP))

        c = counts()
        if not history or t - history[-1][0] >= 30:
            history.append((round(t), c[0], c[1]))
        if c[0] >= WIN or c[1] >= WIN:
            return t, c, history

    return t, counts(), history


for seed in (1, 2, 3, 4):
    t, c, hist = run(seed)
    who = "игрок" if c[0] > c[1] else ("бот" if c[1] > c[0] else "ничья")
    print(f"seed {seed}: {t:.0f}s  игрок={c[0]} бот={c[1]}  победа: {who}")
    print("   прогресс:", " ".join(f"{h[1]}:{h[2]}" for h in hist[::2]))
