# Ecualizador

Android equalizer for Bluetooth headphones. It shapes the sound of everything playing on the
phone, shows the headset's battery and lets you control playback from the same screen.

Written in Kotlin with the Android SDK only, no external libraries.

## What it does

- **System-wide equalizer** with a draggable curve, one handle per band.
- **Presets** for bass, vocals, treble, rock, pop and electronic, plus your own custom curve
  and a bass boost.
- **Bluetooth headset status:** which headset is connected, whether it is playing audio or
  connected for calls only, and its battery level when the headset reports it.
- **Now playing:** the current track from any music app, with playback controls.
- **Keeps working in the background** through a foreground service, and turns itself back on
  after the phone restarts.

## How it is built

| | |
|---|---|
| Language | Kotlin 2.0 |
| Android | minimum 9 (API 28), built for Android 15 (API 35) |
| Audio | the platform `Equalizer` and `BassBoost` effects |
| UI | native views with XML layouts, plus custom views for the curve and the battery |
| Dependencies | none beyond the Android SDK |
| Size | about 1,200 lines in 7 files |

Two details worth knowing:

- The effects are attached to the global audio session first. If the phone does not allow it,
  the app attaches to each session that music apps open instead.
- Presets are defined on 10 reference bands and adapted to the bands the phone actually has.
  The standard `Equalizer` is used instead of `DynamicsProcessing` because on some devices
  the vendor's implementation crashes the audio service.

The code lives in `app/src/main/java/com/davidagb/ecualizador/`:

| File | What it holds |
|---|---|
| `Eq.kt` | Equalizer state, presets and the live audio effects |
| `EqService.kt` | Foreground service that keeps the effects alive |
| `Headset.kt` | Connected Bluetooth headset and its battery |
| `MainActivity.kt` | The screen: headset, now playing and equalizer |
| `EqView.kt`, `BatteryView.kt` | Custom views for the curve and the battery |
| `Receivers.kt` | Restart after boot and media session access |

## Permissions

Bluetooth (to read the connected headset), audio settings, notifications and a foreground
service. Notification access is requested only to read what is playing.

## Build

With the Android SDK and Java 17:

```bash
./gradlew assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/`.

## Author

**David Albarrán** · [@DavidAGB](https://github.com/DavidAGB)
