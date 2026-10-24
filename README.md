# Wear OS Barometer

Altitude above sea level on the watch, as a tile and as an app screen. Tested on a Pixel Watch 4 (Wear OS on Android 17). The UI is in Russian.

<img src="screenshots/tile.png" width="200" alt="Tile"/> <img src="screenshots/app.png" width="200" alt="App"/>

## Where the altitude comes from

A bare barometer on the standard atmosphere (P0 = 1013.25 hPa) is off by however much the weather has moved the pressure. 1 hPa near sea level is 8.3 m. During the anticyclone on 2026-10-23, sea level pressure in Moscow was 1029.8 hPa, and the standard formula read 125 m low.

So the barometer only handles relative height: its noise is about 0.01 hPa, which is centimeters. Anchors hold the absolute level. Each anchor gives an estimate of the weather offset `b`, and a one-dimensional Kalman filter combines them by their accuracy:

```
altitude = isa(P) - b
```

| Anchor | Needs network | Error |
|---|---|---|
| Manual calibration (long-press the value, turn the crown, tap to save) | no | 1 m |
| GNSS height above the geoid (`mslAltitudeMeters`, L1+L5 on this watch) | no | receiver estimate, fixes worse than 25 m are dropped |
| QNH from Open-Meteo (`pressure_msl`) | yes | 8-10 m |
| Terrain elevation from Open-Meteo at the fix | yes | 25 m |

Without anchors the uncertainty grows by about 3 m per hour. It is shown under the value: `±12 м · GPS`. Above 15 m the value gets a `~` prefix, above 50 m also a `?`. In the mountains without network, GNSS and manual calibration remain.

Manual calibration is for a point with a known altitude, like a summit marker or a pass sign, when GNSS is poor and there is no network. It is trusted to 1 m, so it overrides the other anchors. To go back to the sensors, long-press the value to enter calibration and long-press again: the manual value is dropped and GNSS and weather set the altitude anew. Leaving the screen without a tap discards an unsaved calibration.

GNSS altitude is paired with the pressure at the moment of the fix, not the current one. Otherwise a climb in progress would leak into the weather correction.

## Tile and app

The tile refreshes once a minute. Each refresh samples the barometer for about a second and shows the altitude with the saved offset. Every 30 minutes the tile starts a WorkManager job while it is on screen. With location allowed "all the time", the job turns GNSS on for one fresh fix (up to 30 s) and records the barometer while it waits, so the fix gets the pressure of its own moment. Without background location it uses the last known location, which usually only gives a weather update. The tile redraws if anything arrived. Tapping the tile opens the app.

The app screen reads the barometer 4 times a second and keeps GNSS on while it is open. Location permissions and manual calibration live here: after the regular location permission it asks for "all the time", and if that was declined, a hint at the bottom opens the app's permission settings.

Coordinates are sent to Open-Meteo: rounded to 0.01° (about 1 km) for the weather, exact for terrain elevation. No API key is needed.

## Build

Needs the Android SDK (compileSdk 35) and JDK 17.

```bash
./gradlew testDebugUnitTest assembleDebug
adb -s <watch> install -r app/build/outputs/apk/debug/app-debug.apk
```

The altitude code is in `app/src/main/java/com/example/wearbarometer/altitude/`. The filter and all the math are plain Kotlin without Android, so the tests run on the JVM.

## License

MIT, see [LICENSE](LICENSE).
