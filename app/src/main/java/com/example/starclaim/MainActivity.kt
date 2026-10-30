package com.example.starclaim

import android.os.Bundle
import android.os.SystemClock
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.example.starclaim.game.BOT
import com.example.starclaim.game.GameEngine
import com.example.starclaim.game.NEUTRAL
import com.example.starclaim.game.PLAYER
import com.example.starclaim.game.Planet
import com.example.starclaim.ui.MapView
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var mapView: MapView
    private lateinit var tvCredits: TextView
    private lateinit var tvPlanets: TextView
    private lateinit var tvSelTitle: TextView
    private lateinit var tvSelInfo: TextView
    private lateinit var tvHint: TextView
    private lateinit var btnBuild: Button
    private lateinit var btnAttack: Button

    private var engine = GameEngine(System.currentTimeMillis())
    private var selected = -1
    private var targeting = false
    private var resultShown = false
    private var lastFrameAt = 0L

    private val gameLoop = object : Runnable {
        override fun run() {
            val now = SystemClock.elapsedRealtime()
            if (lastFrameAt != 0L) {
                engine.tick((now - lastFrameAt) / 1000f)
            }
            lastFrameAt = now
            mapView.invalidate()
            refreshHud()
            if (engine.winner >= 0) {
                showResult()
            } else {
                mapView.postOnAnimation(this)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        mapView = findViewById(R.id.mapView)
        tvCredits = findViewById(R.id.tvCredits)
        tvPlanets = findViewById(R.id.tvPlanets)
        tvSelTitle = findViewById(R.id.tvSelTitle)
        tvSelInfo = findViewById(R.id.tvSelInfo)
        tvHint = findViewById(R.id.tvHint)
        btnBuild = findViewById(R.id.btnBuild)
        btnAttack = findViewById(R.id.btnAttack)

        mapView.engine = engine
        mapView.onPlanetTap = { id -> onPlanetTap(id) }

        btnBuild.setOnClickListener {
            if (engine.buildFleet(selected)) {
                toast("Флот +10 в орбите планеты ${selected + 1}")
            } else {
                toast("Нужна своя планета и 50 очков")
            }
            refreshHud()
        }

        btnAttack.setOnClickListener {
            val p = selectedPlanet()
            if (p == null || p.owner != PLAYER) {
                toast("Сначала выберите свою планету")
                return@setOnClickListener
            }
            if (engine.orbit[p.id][PLAYER] < 1f) {
                toast("В орбите нет флота — постройте его")
                return@setOnClickListener
            }
            targeting = true
            mapView.targeting = true
            tvHint.text = "Выберите соседнюю планету — туда уйдёт 70% флота"
            mapView.invalidate()
        }

        findViewById<Button>(R.id.btnRestart).setOnClickListener { restart() }

        maybeShowHelp()
        startLoop()
    }

    private fun selectedPlanet(): Planet? =
        if (selected in engine.planets.indices) engine.planets[selected] else null

    private fun onPlanetTap(id: Int) {
        if (targeting) {
            val from = selected
            if (from >= 0 && engine.sendFleet(from, id)) {
                toast("Флот вылетел")
                targeting = false
                mapView.targeting = false
                tvHint.text = "Флот в пути — 8 секунд до орбиты"
            } else {
                toast("Нужна соседняя планета и флот в орбите")
            }
            refreshHud()
            return
        }
        selected = id
        mapView.selected = id
        refreshHud()
        mapView.invalidate()
    }

    private fun maybeShowHelp() {
        val prefs = getSharedPreferences("starclaim", MODE_PRIVATE)
        if (prefs.getBoolean("help_shown", false)) return
        prefs.edit().putBoolean("help_shown", true).apply()
        AlertDialog.Builder(this)
            .setTitle("Как захватить планету")
            .setMessage(
                "1. Тапните свою зелёную планету\n" +
                "2. Несколько раз нажмите «Флот +10» — флот копится в ОРБИТЕ планеты (10 флота = 50 очков)\n" +
                "3. «Атаковать» → тапните соседнюю планету (подсвечены голубым). Улетает ВЕСЬ флот из орбиты\n" +
                "4. Один пакет (10) ничего не сделает: против гарнизона 30 нужно ≈40 флота\n" +
                "5. Когда гарнизон станет 0, вокруг планеты растёт кольцо захвата — до 100%\n" +
                "6. Победа: 12 планет из 20"
            )
            .setPositiveButton("Понятно", null)
            .show()
    }

    private fun startLoop() {
        mapView.removeCallbacks(gameLoop)
        lastFrameAt = 0L
        mapView.postOnAnimation(gameLoop)
    }

    private fun refreshHud() {
        tvCredits.text = String.format(Locale.US, "%.0f", engine.credits[PLAYER])
        tvPlanets.text = "${engine.ownedCount(PLAYER)} / ${engine.planets.size}"

        val p = selectedPlanet()
        if (p == null) {
            tvSelTitle.text = "Коснитесь планеты"
            tvSelInfo.text = "Зелёные — ваши, красные — бота, серые — ничейные"
            btnBuild.isEnabled = false
            btnAttack.isEnabled = false
            return
        }

        val ownerName = when (p.owner) {
            PLAYER -> "ваша"
            BOT -> "бота"
            else -> "ничейная"
        }
        tvSelTitle.text = "Планета ${p.id + 1} · ${p.type.title} · $ownerName"

        val orbitOwn = if (p.owner >= 0) engine.orbit[p.id][p.owner] else 0f
        val enemy = (0..1).filter { it != p.owner }.sumOf { engine.orbit[p.id][it].toDouble() }
        val parts = mutableListOf<String>()
        parts.add("гарнизон ${p.garrison.toInt()}")
        parts.add(String.format(Locale.US, "доход %.1f/с", p.production()))
        if (orbitOwn > 0.5f) parts.add("свой флот ${orbitOwn.toInt()}")
        if (enemy > 0.5f) parts.add("вражеский флот ${enemy.toInt()}")
        if (p.capture > 0.5f) parts.add("захват ${p.capture.toInt()}%")
        if (engine.time < p.vulnerableUntil) parts.add("уязвима")
        if (p.owner != PLAYER) {
            parts.add("нужно ≈ ${engine.fleetNeeded(p.id).toInt()}")
        }
        tvSelInfo.text = parts.joinToString(" · ")

        btnBuild.isEnabled = engine.canBuild(p.id)
        btnAttack.isEnabled = p.owner == PLAYER && engine.orbit[p.id][PLAYER] >= 1f

        if (engine.events.isNotEmpty()) {
            tvHint.text = engine.events.last()
        }
    }

    private fun showResult() {
        if (resultShown) return
        resultShown = true
        val win = engine.winner == PLAYER
        AlertDialog.Builder(this)
            .setTitle(if (win) "Победа!" else "Поражение")
            .setMessage(
                if (win) "Вы контролируете ${engine.ownedCount(PLAYER)} планет из ${engine.planets.size}."
                else "Бот захватил сектор. Попробуйте другую тактику."
            )
            .setPositiveButton("Ещё раз") { _, _ -> restart() }
            .setNegativeButton("Выход") { _, _ -> finish() }
            .setCancelable(false)
            .show()
    }

    private fun restart() {
        engine = GameEngine(System.nanoTime())
        mapView.engine = engine
        selected = -1
        mapView.selected = -1
        targeting = false
        mapView.targeting = false
        resultShown = false
        tvHint.text = "Стройте флот на своей планете, затем атакуйте соседнюю"
        refreshHud()
        startLoop()
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }
}
