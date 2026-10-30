package com.davidagb.ecualizador

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.media.AudioManager
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.util.Log
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Estado del ecualizador y efectos de audio vivos.
 *
 * El estado (bandas, preset, graves) lo editan la actividad y se guarda en preferencias.
 * Los efectos solo existen mientras EqService está en marcha (attach/detach).
 * Primero se intenta la sesión 0 (todo el audio); si el sistema no la permite,
 * se engancha a cada sesión que abren las apps de música.
 *
 * Se usa el Equalizer/BassBoost estándar y no DynamicsProcessing: en el Galaxy A14
 * la librería libdynproc del fabricante tumba el servicio de audio al configurarse.
 */
object Eq {

    private const val TAG = "Eq"
    private const val PRIORITY = 100
    private const val MAX_FAILURES = 2

    const val FLAT = "Plano"
    const val CUSTOM = "Personalizado"
    const val MAX_BASS = 1000

    // Los presets se definen sobre 10 bandas de referencia y se adaptan a las del teléfono.
    private val REF_FREQS = floatArrayOf(31f, 62f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f)
    private val REF_PRESETS: List<Pair<String, FloatArray>> = listOf(
        FLAT to floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f),
        "Graves" to floatArrayOf(6f, 5f, 4f, 2f, 0f, 0f, 0f, 0f, 0f, 0f),
        "Voces" to floatArrayOf(-2f, -1f, 0f, 2f, 4f, 4f, 3f, 1f, 0f, -1f),
        "Agudos" to floatArrayOf(0f, 0f, 0f, 0f, 0f, 1f, 2f, 4f, 5f, 6f),
        "Rock" to floatArrayOf(5f, 4f, 2f, -1f, -2f, -1f, 1f, 3f, 4f, 5f),
        "Pop" to floatArrayOf(-1f, 1f, 3f, 4f, 3f, 0f, -1f, -1f, 0f, 1f),
        "Electrónica" to floatArrayOf(5f, 4f, 1f, 0f, -2f, 1f, 0f, 1f, 4f, 5f),
        "Hip-hop" to floatArrayOf(6f, 5f, 2f, 3f, -1f, -1f, 1f, -1f, 2f, 3f),
        "Clásica" to floatArrayOf(4f, 3f, 2f, 1f, -1f, -1f, 0f, 2f, 3f, 4f),
        "Podcast" to floatArrayOf(-4f, -3f, -1f, 1f, 3f, 4f, 4f, 2f, 0f, -2f),
    )
    val presetNames: List<String> = REF_PRESETS.map { it.first } + CUSTOM

    // Bandas del ecualizador del teléfono (valores típicos hasta poder consultarlo).
    var freqs = floatArrayOf(60f, 230f, 910f, 3600f, 14000f); private set
    var maxDb = 15f; private set
    val bands get() = freqs.size

    var enabled = false; private set
    var preset = FLAT; private set
    var bass = 0; private set
    var gains = FloatArray(bands); private set
    private var custom = FloatArray(bands)

    // Motor
    private class Fx(val pkg: String?, val eq: Equalizer?, val boost: BassBoost?) {
        fun release() {
            eq?.release()
            boost?.release()
        }
    }

    private var global: Fx? = null
    private val sessions = LinkedHashMap<Int, Fx>()
    private var failures = 0
    var attached = false; private set
    val globalOk get() = global != null
    var error: String? = null; private set
    val apps: List<String> get() = sessions.values.mapNotNull { it.pkg }.distinct()

    val listeners = mutableSetOf<() -> Unit>()

    private var prefs: SharedPreferences? = null

    fun load(ctx: Context) {
        if (prefs != null) return
        val p = ctx.applicationContext.getSharedPreferences("eq", Context.MODE_PRIVATE)
        prefs = p
        val stored = p.getString("freqs", null)?.split(",")?.mapNotNull { it.toFloatOrNull() }
        if (!stored.isNullOrEmpty()) {
            freqs = stored.toFloatArray()
            maxDb = p.getFloat("maxDb", maxDb)
        } else {
            probe(ctx)
        }
        enabled = p.getBoolean("enabled", false)
        preset = p.getString("preset", FLAT) ?: FLAT
        bass = p.getInt("bass", 0)
        gains = parse(p.getString("gains", null))
        custom = parse(p.getString("custom", null))
    }

    /** Lee las bandas reales con un ecualizador sobre una sesión vacía (no toca el audio). */
    private fun probe(ctx: Context) {
        try {
            val session = ctx.getSystemService(AudioManager::class.java).generateAudioSessionId()
            val eq = Equalizer(0, session)
            freqs = FloatArray(eq.numberOfBands.toInt()) { eq.getCenterFreq(it.toShort()) / 1000f }
            val range = eq.bandLevelRange
            maxDb = min(-range[0].toInt(), range[1].toInt()) / 100f
            eq.release()
            prefs?.edit()?.putString("freqs", freqs.joinToString(","))?.putFloat("maxDb", maxDb)?.apply()
        } catch (e: Exception) {
            Log.w(TAG, "No se pudieron consultar las bandas", e)
        }
    }

    fun save() {
        prefs?.edit()
            ?.putBoolean("enabled", enabled)
            ?.putString("preset", preset)
            ?.putInt("bass", bass)
            ?.putString("gains", gains.joinToString(","))
            ?.putString("custom", custom.joinToString(","))
            ?.apply()
    }

    private fun parse(s: String?): FloatArray {
        val v = s?.split(",")?.mapNotNull { it.toFloatOrNull() }
        return if (v != null && v.size == bands) v.toFloatArray() else FloatArray(bands)
    }

    /** Lleva un preset de 10 bandas a las bandas del teléfono (interpolación en escala logarítmica). */
    private fun resample(ref: FloatArray): FloatArray = FloatArray(bands) { i ->
        val f = ln(freqs[i])
        val j = REF_FREQS.indexOfFirst { ln(it) >= f }
        val g = when (j) {
            -1 -> ref.last()
            0 -> ref.first()
            else -> {
                val a = ln(REF_FREQS[j - 1])
                val b = ln(REF_FREQS[j])
                ref[j - 1] + (ref[j] - ref[j - 1]) * (f - a) / (b - a)
            }
        }
        ((g * 2).roundToInt() / 2f).coerceIn(-maxDb, maxDb)
    }

    // ---- Edición ----

    fun setEnabled(ctx: Context, on: Boolean) {
        enabled = on
        save()
        val svc = Intent(ctx, EqService::class.java)
        if (on) ctx.startForegroundService(svc) else ctx.stopService(svc)
        changed()
    }

    fun setBand(i: Int, db: Float) {
        gains[i] = db
        gains.copyInto(custom)
        preset = CUSTOM
        applyAll()
        changed()
    }

    fun setBass(strength: Int) {
        bass = strength.coerceIn(0, MAX_BASS)
        applyAll()
        changed()
    }

    fun usePreset(name: String) {
        val src = if (name == CUSTOM) custom else REF_PRESETS.firstOrNull { it.first == name }?.second?.let(::resample) ?: return
        gains = src.copyOf()
        preset = name
        applyAll()
        save()
        changed()
    }

    // ---- Motor (lo maneja EqService) ----

    fun attach() {
        if (attached) return
        attached = true
        error = null
        failures = 0
        global = create(0, null)
        changed()
    }

    fun detach() {
        global?.release()
        global = null
        sessions.values.forEach { it.release() }
        sessions.clear()
        attached = false
        changed()
    }

    /** Una app de música anunció su sesión de audio. */
    fun openSession(id: Int, pkg: String?) {
        if (!attached || id <= 0) return
        sessions.remove(id)?.release()
        // Con la sesión 0 activa no se duplica el efecto sobre la app.
        sessions[id] = (if (globalOk) null else create(id, pkg)) ?: Fx(pkg, null, null)
        changed()
    }

    fun closeSession(id: Int) {
        sessions.remove(id)?.release() ?: return
        changed()
    }

    private fun create(session: Int, pkg: String?): Fx? {
        // Si el sistema rechaza los efectos varias veces seguidas, no se insiste.
        if (failures >= MAX_FAILURES) return null
        val eq = try {
            Equalizer(PRIORITY, session)
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo crear el ecualizador en la sesión $session", e)
            error = e.message ?: e.javaClass.simpleName
            failures++
            return null
        }
        failures = 0
        val boost = try {
            BassBoost(PRIORITY, session)
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo crear el refuerzo de graves en la sesión $session", e)
            null
        }
        val fx = Fx(pkg, eq, boost)
        eq.setControlStatusListener { _, control -> if (control) apply(fx) }
        apply(fx)
        return fx
    }

    private fun apply(fx: Fx) {
        try {
            fx.eq?.let { eq ->
                for (i in 0 until min(bands, eq.numberOfBands.toInt())) {
                    eq.setBandLevel(i.toShort(), (gains[i] * 100).roundToInt().toShort())
                }
                eq.enabled = true
            }
            fx.boost?.let { b ->
                if (b.strengthSupported) b.setStrength(bass.toShort())
                b.enabled = bass > 0
            }
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo aplicar el ecualizador", e)
            error = e.message ?: e.javaClass.simpleName
        }
    }

    private fun applyAll() {
        global?.let(::apply)
        sessions.values.forEach(::apply)
    }

    private fun changed() = listeners.toList().forEach { it() }
}
