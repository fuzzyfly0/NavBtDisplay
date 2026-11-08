# Nav BT Display

Shows turn-by-turn navigation hints on car stereos that only support
**Bluetooth audio (A2DP/AVRCP)** and no Android Auto.

Such stereos can display the "now playing" metadata (title / artist / album)
of the phone. This app reads the ongoing guidance notification of a
navigation app and publishes its text as "now playing" metadata, so the car
shows e.g.

```
Title:  300 m · Turn right onto Main St
Artist: 12 min · 5.3 km
Album:  Google Maps
```

> **Status: experimental.** Reading other apps' notifications is unofficial.
> Navigation apps can change their notification at any time, and Google Maps
> does not officially share guidance outside Android Auto.

## How it works

1. `NavListenerService` (a `NotificationListenerService`) receives the
   notifications of the supported navigation apps. Only *ongoing*
   (or `CATEGORY_NAVIGATION`) notifications that are not group summaries are
   considered guidance.
2. `NotificationDump`/`NavText` take `EXTRA_TITLE`, `EXTRA_BIG_TEXT`/`EXTRA_TEXT`,
   `EXTRA_SUB_TEXT` and `EXTRA_INFO_TEXT`. If the extras carry no text, the
   custom `RemoteViews` layout is inflated off-screen and its `TextView`s are read.
3. `NavPublisher` puts the text into a framework `MediaSession`.
   When the guidance notification disappears, the session is released.

## The hard part: the car only shows *one* player

Verified in AOSP source (`frameworks/base` `MediaSessionStack` and
`AudioPlayerStateMonitor`, `packages/modules/Bluetooth` `MediaPlayerList`):

* The Bluetooth AVRCP stack sends the car the metadata of the **media button
  session** (`onMediaKeyEventSessionChanged` → `setActivePlayer`).
* Android picks the media button session from the app that most recently
  **started an audio player** (any usage; only `isActive()` counts). An app that
  has a media session but plays no audio never wins against a music app that
  is actually playing.
* When the head of that list stops playing, the most recent app that is *still*
  playing moves back to the front.

So the app plays **digital silence** (`SilentAudio`, `USAGE_MEDIA`, no audio
focus request, so the music keeps playing unchanged) while it wants the
display, and stops it and releases its session to hand the display back.

### Display modes

| Mode | What the car shows | Downsides |
|---|---|---|
| **Flash** (default) | Each new maneuver for 8 s, then the music title again | You only see a hint when the maneuver changes, not the countdown to it. Each switch is a "player change" for the car; some head units show a source popup or reset the display. |
| **Takeover** | Navigation for the whole trip | Music title is not visible. Car buttons reach this app (see below). |
| **Passive** | Session only, no silent audio | Practically invisible while music plays; only useful for cars that let you pick the player in a Bluetooth browse menu. |

### Car buttons (play / pause / next / previous)

While this app holds the display, the car's buttons are delivered to *its*
session. They are forwarded to the music app's session if this app can see it
(`MediaSessionManager.getActiveSessions`, same Android profile only).
Otherwise (e.g. the music app runs in the **work profile**) the app hands the
display back and re-sends the key via `AudioManager.dispatchMediaKeyEvent`, so
it reaches the music app; in Takeover mode the display is then left to the
music app until the next maneuver.

Some head units send "play" by themselves when the active player changes;
that would be forwarded to the music app and may start paused music.

### Profiles

A notification listener only sees notifications of its own Android profile.
The navigation app and this app must be installed in the **same profile**
(personal or work). The music app may be in another profile.

## Supported navigation apps

Whether an app's notification contains usable text has to be checked on a real
device (Settings → *Debug* log, or `adb logcat -s NavBtDisplay`).

| App | Package(s) | Notification text |
|---|---|---|
| Google Maps | `com.google.android.apps.maps` | not yet verified |
| Waze | `com.waze` | not yet verified |
| OsmAnd | `net.osmand`, `net.osmand.plus` | not yet verified (OsmAnd also has an official AIDL navigation API, a more robust future option) |
| Organic Maps | `app.organicmaps` | not yet verified |
| CoMaps | `app.comaps`, `app.comaps.fdroid` (package names unverified) | not yet verified |
| HERE WeGo | `com.here.app.maps` | not yet verified |

## Testing

1. Install, open the app and grant notification access.
2. Connect the phone to the car, start music, press **Send test hint** –
   the car should show the test text for 10 s and then the music title again.
3. Start navigation in a supported app; the debug log shows what the app put
   into its notification and what was sent to the car.

Useful on the phone (via adb):

```
adb shell dumpsys media_session        # "Media button session" = what AVRCP shows
adb shell dumpsys bluetooth_manager    # AvrcpTargetService section: active player
adb logcat -s NavBtDisplay
```

## Build

```
./gradlew assembleDebug
```

Requires JDK 17+ and the Android SDK (compileSdk 37). No third-party libraries.

## License

GPL-3.0, see [LICENSE](LICENSE).
