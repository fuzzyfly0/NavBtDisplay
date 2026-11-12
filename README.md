# Nav BT Display

Shows turn-by-turn navigation hints on car stereos that only support
**Bluetooth audio (A2DP/AVRCP)** and no Android Auto.

Such stereos can display the "now playing" metadata (title / artist / album)
of the phone. This app reads the ongoing guidance notification of a
navigation app and publishes its text as "now playing" metadata, so the car
shows e.g.

```
Title:  300 m · Turn right          (distance + maneuver)
Artist: Main St                     (street / direction)
Album:  12 min · 5.3 km · ETA 18:34 · Then turn left (trip summary + next maneuver)
```

If a part is missing, the next one moves up (e.g. no street → summary as
artist), so the title always carries the most important information; fields
without information stay empty. Fields that only contain an app name
("Mapy.cz") are ignored. If the text has no direction at all (only the street),
the direction is taken from the name of the notification's icon resource
(e.g. `ic_turn_right`), if the app uses resource icons. The display is
refreshed every time the navigation app updates its notification.

> **Status: experimental.** Reading other apps' notifications is unofficial.
> Navigation apps can change their notification at any time, and Google Maps
> does not officially share guidance outside Android Auto.

## How it works

1. `NavListenerService` (a `NotificationListenerService`) receives the
   notifications of the supported navigation apps. Only *ongoing*
   (or `CATEGORY_NAVIGATION`) notifications that are not group summaries are
   considered guidance.
2. `NotificationDump`/`NavText` take `EXTRA_TITLE`, `EXTRA_TEXT` (or the first
   line of `EXTRA_BIG_TEXT`), `EXTRA_SUB_TEXT` and `EXTRA_INFO_TEXT`. If the
   extras carry no maneuver, the custom `RemoteViews` layout is inflated off-screen
   and its `TextView`s are read. Heuristics (English, German, Czech) then split
   the text into distance, maneuver, street/direction and trip summary.
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
| **Takeover** (default) | Navigation for the whole trip, distance counting down | Music title is not visible. Car buttons reach this app (see below). |
| **Flash** | Each new maneuver for 8 s, then the music title again | You only see a hint when the maneuver changes, not the countdown to it. Each switch is a "player change" for the car; some head units show a source popup or reset the display. |
| **Passive** | Session only, no silent audio | Practically invisible while music plays; only useful for cars that let you pick the player in a Bluetooth browse menu. |

While the app holds the display it watches the music app's session. When the
track changes, playback resumes or another music app starts, the music app has
usually started a new player and taken the display; the app then re-claims it
after about 1 s instead of waiting for the next maneuver.

The silent player is not restarted while a voice prompt
(`USAGE_ASSISTANCE_NAVIGATION_GUIDANCE`) is playing; the re-claim waits until
the prompt has ended (at most 15 s).

**Music title in the first line (optional, Takeover only):** the first line
alternates between the maneuver (6 s) and `♪ Artist – Title` of the playing
music (3 s). Lines 2 and 3 keep showing the navigation; a new maneuver always
starts with the navigation phase.

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
| Mapy.com | `cz.seznam.mapy` | not yet verified |
| Blitzer.de (warnings) | `de.blitzer`, `de.blitzer.plus`, `net.graphmasters.blitzerdex` | not yet verified |

Warning apps such as Blitzer.de run next to the navigation. A notification line
that looks like a warning ("Blitzer", "Gefahr", "Stau", "50 km/h", …) is shown
as `⚠ …` for 10 s in every mode, also without guidance; then the guidance (or
the music) returns. Updates of the same warning (counting-down distance) only
refresh the text; the status notification ("Blitzer.de ist aktiv") is ignored.

Other apps (Waze, OsmAnd, Organic Maps, CoMaps, HERE WeGo) are left out of
the first version. OsmAnd would be best supported through its official AIDL
navigation API rather than its notification.

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
