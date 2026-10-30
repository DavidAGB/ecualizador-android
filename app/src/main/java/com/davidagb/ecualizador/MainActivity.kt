package com.davidagb.ecualizador

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.audiofx.AudioEffect
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import android.view.WindowInsets
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import kotlin.math.roundToInt

class MainActivity : Activity() {

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var headset: Headset
    private lateinit var audio: AudioManager
    private lateinit var sessionManager: MediaSessionManager
    private val listenerName by lazy { ComponentName(this, MediaListener::class.java) }

    // Medios
    private var watched: List<MediaController> = emptyList()
    private var controller: MediaController? = null
    private var mediaStarted = false
    private var seeking = false
    private var ticks = 0
    private var askedBluetooth = false

    // Vistas
    private lateinit var devicesBox: LinearLayout
    private lateinit var mediaBox: View
    private lateinit var mediaEmpty: TextView
    private lateinit var mediaAccess: TextView
    private lateinit var art: ImageView
    private lateinit var source: TextView
    private lateinit var title: TextView
    private lateinit var artist: TextView
    private lateinit var progress: SeekBar
    private lateinit var position: TextView
    private lateinit var duration: TextView
    private lateinit var playBtn: ImageView
    private lateinit var volume: SeekBar
    private lateinit var volumeValue: TextView
    private lateinit var eqSwitch: Switch
    private lateinit var eqStatus: TextView
    private lateinit var eqBody: View
    private lateinit var presets: LinearLayout
    private lateinit var eqView: EqView
    private lateinit var bass: SeekBar
    private lateinit var bassValue: TextView

    private val eqListener: () -> Unit = { renderEq() }

    private val mediaCallback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) = pick()
        override fun onMetadataChanged(metadata: MediaMetadata?) = pick()
        override fun onSessionDestroyed() = pick()
    }

    private val sessionsChanged = MediaSessionManager.OnActiveSessionsChangedListener { watch(it.orEmpty()) }

    private val volumeReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = renderVolume()
    }

    private val tick = object : Runnable {
        override fun run() {
            renderProgress()
            if (++ticks % 40 == 0) headset.refresh()  // batería cada ~20 s
            ui.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        Eq.load(this)

        audio = getSystemService(AudioManager::class.java)
        sessionManager = getSystemService(MediaSessionManager::class.java)
        headset = Headset(this) { renderDevices() }

        devicesBox = findViewById(R.id.devices)
        mediaBox = findViewById(R.id.media)
        mediaEmpty = findViewById(R.id.mediaEmpty)
        mediaAccess = findViewById(R.id.mediaAccess)
        art = findViewById(R.id.art)
        source = findViewById(R.id.source)
        title = findViewById(R.id.title)
        artist = findViewById(R.id.artist)
        progress = findViewById(R.id.progress)
        position = findViewById(R.id.position)
        duration = findViewById(R.id.duration)
        playBtn = findViewById(R.id.play)
        volume = findViewById(R.id.volume)
        volumeValue = findViewById(R.id.volumeValue)
        eqSwitch = findViewById(R.id.eqSwitch)
        eqStatus = findViewById(R.id.eqStatus)
        eqBody = findViewById(R.id.eqBody)
        presets = findViewById(R.id.presets)
        eqView = findViewById(R.id.eqView)
        bass = findViewById(R.id.bass)
        bassValue = findViewById(R.id.bassValue)
        art.clipToOutline = true

        findViewById<View>(R.id.root).setOnApplyWindowInsetsListener { v, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val b = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                v.setPadding(b.left, b.top, b.right, b.bottom)
            } else {
                @Suppress("DEPRECATION")
                v.setPadding(
                    insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight, insets.systemWindowInsetBottom
                )
            }
            insets
        }

        setupMedia()
        setupEq()
        askPermissions()
        if (Eq.enabled) Eq.setEnabled(this, true)
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        headset.start()
        startMedia()
        listen(volumeReceiver, IntentFilter("android.media.VOLUME_CHANGED_ACTION"))
        renderVolume()
        Eq.listeners += eqListener
        renderEq()
        ui.post(tick)
    }

    override fun onResume() {
        super.onResume()
        // Al volver de Ajustes pudo haberse concedido algún permiso.
        headset.start()
        startMedia()
    }

    override fun onStop() {
        ui.removeCallbacks(tick)
        Eq.listeners -= eqListener
        unregisterReceiver(volumeReceiver)
        stopMedia()
        headset.stop()
        Eq.save()
        super.onStop()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        headset.start()
    }

    /** Las apps de música abren esta pantalla desde su botón "Ecualizador". */
    private fun handleIntent(i: Intent?) {
        if (i?.action != AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL) return
        Eq.openSession(i.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, -1), i.getStringExtra(AudioEffect.EXTRA_PACKAGE_NAME))
    }

    private fun askPermissions() {
        val wanted = buildList {
            if (Build.VERSION.SDK_INT >= 31) add(Manifest.permission.BLUETOOTH_CONNECT)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (wanted.isNotEmpty()) requestPermissions(wanted.toTypedArray(), 1)
    }

    private fun askBluetooth() {
        val perm = Manifest.permission.BLUETOOTH_CONNECT
        if (askedBluetooth && !shouldShowRequestPermissionRationale(perm)) {
            // Denegado de forma permanente: solo queda Ajustes.
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
        } else {
            askedBluetooth = true
            requestPermissions(arrayOf(perm), 2)
        }
    }

    // ---- Audífonos ----

    private fun renderDevices() {
        devicesBox.removeAllViews()
        when {
            !headset.available -> hint("Este teléfono no tiene Bluetooth.")
            !headset.permitted -> {
                hint("Para ver tus audífonos y su batería, permite el acceso a dispositivos cercanos.")
                button("Permitir Bluetooth") { askBluetooth() }
            }
            !headset.enabled -> {
                hint("El Bluetooth está apagado.")
                button("Abrir ajustes de Bluetooth") { startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
            }
            headset.devices.isEmpty() -> hint("No hay audífonos conectados. Conéctalos por Bluetooth y aparecerán aquí.")
            else -> headset.devices.forEach(::addDevice)
        }
    }

    private fun addDevice(d: Headset.Device) {
        val row = layoutInflater.inflate(R.layout.item_device, devicesBox, false)
        row.findViewById<TextView>(R.id.name).text = d.name
        row.findViewById<TextView>(R.id.status).text = listOfNotNull(
            when {
                d.playing -> "Reproduciendo audio"
                d.media -> "Conectado"
                else -> "Conectado solo para llamadas"
            },
            if (d.battery < 0) "batería no reportada" else null,
        ).joinToString(" · ")
        row.findViewById<TextView>(R.id.batteryText).text = if (d.battery >= 0) "${d.battery}%" else "—"
        row.findViewById<BatteryView>(R.id.battery).level = d.battery
        if (devicesBox.childCount > 0) (row.layoutParams as LinearLayout.LayoutParams).topMargin = dp(10)
        devicesBox.addView(row)
    }

    private fun hint(text: String) {
        val t = TextView(this, null, 0, R.style.Hint)
        t.text = text
        devicesBox.addView(t, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
    }

    private fun button(text: String, action: () -> Unit) {
        val b = TextView(this, null, 0, R.style.Btn)
        b.text = text
        b.setOnClickListener { action() }
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(40))
        lp.topMargin = dp(12)
        devicesBox.addView(b, lp)
    }

    // ---- Reproduciendo ----

    private fun setupMedia() {
        mediaAccess.setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        findViewById<View>(R.id.prev).setOnClickListener { controller?.transportControls?.skipToPrevious() }
        findViewById<View>(R.id.next).setOnClickListener { controller?.transportControls?.skipToNext() }
        playBtn.setOnClickListener {
            val c = controller ?: return@setOnClickListener
            if (c.playbackState?.state == PlaybackState.STATE_PLAYING) c.transportControls.pause()
            else c.transportControls.play()
        }
        progress.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                if (fromUser) position.text = time(p * 1000L)
            }
            override fun onStartTrackingTouch(s: SeekBar) { seeking = true }
            override fun onStopTrackingTouch(s: SeekBar) {
                controller?.transportControls?.seekTo(s.progress * 1000L)
                seeking = false
            }
        })
        volume.max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        volume.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                if (fromUser) {
                    audio.setStreamVolume(AudioManager.STREAM_MUSIC, p, 0)
                    renderVolume()
                }
            }
            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) {}
        })
    }

    private fun hasNotificationAccess() =
        getSystemService(NotificationManager::class.java).isNotificationListenerAccessGranted(listenerName)

    private fun startMedia() {
        if (mediaStarted || !hasNotificationAccess()) {
            renderMedia()
            return
        }
        try {
            sessionManager.addOnActiveSessionsChangedListener(sessionsChanged, listenerName, ui)
            mediaStarted = true
            watch(sessionManager.getActiveSessions(listenerName))
        } catch (e: SecurityException) {
            renderMedia()
        }
    }

    private fun stopMedia() {
        if (!mediaStarted) return
        sessionManager.removeOnActiveSessionsChangedListener(sessionsChanged)
        watch(emptyList())
        mediaStarted = false
    }

    private fun watch(list: List<MediaController>) {
        watched.forEach { it.unregisterCallback(mediaCallback) }
        watched = list
        list.forEach { it.registerCallback(mediaCallback, ui) }
        pick()
    }

    /** Muestra la sesión que está sonando; si ninguna suena, la más reciente. */
    private fun pick() {
        val usable = watched.filter { it.metadata != null }
        controller = usable.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: usable.firstOrNull()
        renderMedia()
    }

    private fun renderMedia() {
        val access = hasNotificationAccess()
        mediaAccess.visibility = if (access) View.GONE else View.VISIBLE
        val c = controller
        val m = c?.metadata
        if (!access || c == null || m == null) {
            mediaBox.visibility = View.GONE
            mediaEmpty.visibility = View.VISIBLE
            mediaEmpty.text = if (access) {
                "Nada en reproducción. Pon música en cualquier app y aparecerá aquí."
            } else {
                "Para ver qué suena y controlarlo, la app necesita acceso a las notificaciones."
            }
            return
        }
        mediaBox.visibility = View.VISIBLE
        mediaEmpty.visibility = View.GONE

        val d = m.description
        title.text = m.getString(MediaMetadata.METADATA_KEY_TITLE) ?: d.title ?: "Sin título"
        val by = listOfNotNull(
            m.getString(MediaMetadata.METADATA_KEY_ARTIST)
                ?: m.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
                ?: d.subtitle?.toString(),
            m.getString(MediaMetadata.METADATA_KEY_ALBUM),
        ).filter { it.isNotBlank() }.distinct().joinToString(" · ")
        artist.text = by
        artist.visibility = if (by.isEmpty()) View.GONE else View.VISIBLE
        source.text = appLabel(c.packageName).uppercase()

        val bmp: Bitmap? = m.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: m.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: m.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
            ?: d.iconBitmap
        if (bmp != null) {
            art.setPadding(0, 0, 0, 0)
            art.setImageBitmap(bmp)
        } else {
            art.setPadding(dp(16), dp(16), dp(16), dp(16))
            art.setImageDrawable(runCatching { packageManager.getApplicationIcon(c.packageName) }.getOrNull())
        }

        val state = c.playbackState
        playBtn.setImageResource(if (state?.state == PlaybackState.STATE_PLAYING) R.drawable.ic_pause else R.drawable.ic_play)
        progress.isEnabled = (state?.actions ?: 0L) and PlaybackState.ACTION_SEEK_TO != 0L
        renderProgress()
    }

    private fun renderProgress() {
        val c = controller ?: return
        val st = c.playbackState
        val dur = c.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        var pos = st?.position ?: 0L
        if (st != null && st.state == PlaybackState.STATE_PLAYING && st.lastPositionUpdateTime > 0) {
            pos += ((SystemClock.elapsedRealtime() - st.lastPositionUpdateTime) * st.playbackSpeed).toLong()
        }
        pos = if (dur > 0) pos.coerceIn(0, dur) else pos.coerceAtLeast(0)
        duration.text = if (dur > 0) time(dur) else "--:--"
        if (seeking) return
        position.text = time(pos)
        progress.max = (dur / 1000).toInt().coerceAtLeast(1)
        progress.progress = (pos / 1000).toInt()
    }

    private fun renderVolume() {
        val v = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        val max = volume.max.coerceAtLeast(1)
        volume.progress = v
        volumeValue.text = "${v * 100 / max}%"
    }

    // ---- Ecualizador ----

    private fun setupEq() {
        for (name in Eq.presetNames) {
            val chip = layoutInflater.inflate(R.layout.item_chip, presets, false) as RadioButton
            chip.text = name
            chip.tag = name
            chip.setOnClickListener { Eq.usePreset(name) }
            presets.addView(chip)
        }
        eqSwitch.setOnCheckedChangeListener { _, on -> if (on != Eq.enabled) Eq.setEnabled(this, on) }
        eqView.onChange = { i, g -> Eq.setBand(i, g) }
        eqView.onCommit = { Eq.save() }
        bass.max = Eq.MAX_BASS / 10
        bass.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                if (fromUser) Eq.setBass(p * 10)
            }
            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) = Eq.save()
        })
        findViewById<View>(R.id.reset).setOnClickListener {
            Eq.setBass(0)
            Eq.usePreset(Eq.FLAT)
        }
    }

    private fun renderEq() {
        eqSwitch.isChecked = Eq.enabled
        eqStatus.text = when {
            !Eq.enabled -> "Apagado. Actívalo para aplicar el sonido a tus audífonos."
            !Eq.attached -> "Iniciando…"
            Eq.globalOk -> "Activo en todo el audio del teléfono."
            Eq.apps.isNotEmpty() -> "Activo en: " + Eq.apps.joinToString { appLabel(it) }
            else -> "Activo. El sistema no permite el modo global" +
                (Eq.error?.let { " ($it)" } ?: "") +
                "; se aplicará cuando una app de música abra su sesión de audio."
        }
        eqBody.alpha = if (Eq.enabled) 1f else 0.5f
        eqView.setGains(Eq.gains)
        bass.progress = Eq.bass / 10
        bassValue.text = "${Eq.bass * 100 / Eq.MAX_BASS}%"
        for (i in 0 until presets.childCount) {
            val chip = presets.getChildAt(i) as RadioButton
            chip.isChecked = chip.tag == Eq.preset
        }
    }

    // ---- Utilidades ----

    private fun appLabel(pkg: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (e: PackageManager.NameNotFoundException) {
        pkg
    }

    private fun time(ms: Long): String {
        val s = ms / 1000
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()
}
