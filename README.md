# Guiding Light for Android

The DOORS death-message screen (Guiding Light / Curious Light) as an Android app.
Android remake of the Electron desktop app: same tick-for-tick 30 Hz sequence engine,
same assets, but called through Android **Intents** and drawn as a system **overlay**
instead of fullscreen windows.

## Build

CI: `.github/workflows/build.yml` builds the debug APK on every push / PR (artifact
`guidinglight-debug-apk`) and attaches it to the release when you push a `v*` tag.

Open this folder in Android Studio (Gradle sync does the rest) or run `gradle assembleDebug`.
Plain framework Java, no dependencies. minSdk 26, targetSdk 35.

## First run

Open the app once and grant **Display over other apps**. (On Android 13+ it also asks for
notification permission, which only affects the "Stop" button in the notification.)

## Calling it

Everything goes through the same dispatcher, whichever way it arrives.

Deep link (browser, adb, other apps):

    guidinglight://show?mode=guiding&message=Hello%3BWorld    (message = URL-encoded "a;b;c")
    guidinglight://abort
    guidinglight://config

Explicit intent action `dev.khoichon.guidinglight.action.SHOW`
(extras: `mode` = guiding|curious, `message` = "a;b;c" **or** `messages` = String[]),
plus `...action.ABORT` and `...action.CONFIG`. With no extras the saved configuration is used.

    adb shell "am start -a dev.khoichon.guidinglight.action.SHOW --es mode curious --es message 'Oh... Hello.;I see.'"
    adb shell am start -a android.intent.action.VIEW -d "guidinglight://show?mode=guiding"   # message: percent-encode ; as %3B
    adb shell am broadcast -a dev.khoichon.guidinglight.action.SHOW -p dev.khoichon.guidinglight --es mode guiding

From another app:

    Intent i = new Intent("dev.khoichon.guidinglight.action.SHOW")
            .setPackage("dev.khoichon.guidinglight")
            .putExtra("mode", "curious")
            .putExtra("messages", new String[]{"Oh... Hello.", "I see."});
    startActivity(i);      // or sendBroadcast(i) to skip the activity launch entirely

- `startActivity` hits an invisible trampoline activity (`CallActivity`).
- `sendBroadcast` hits `CallReceiver`; give it the package or component (Android 8+ drops
  implicit broadcasts to manifest receivers). Good for Tasker / background callers.
- Calling again while a screen is up restarts the sequence.

## Link / intent builder

- In the app: the config screen builds the deep link live from the mode + messages and has
  copy buttons for the link, an `adb` command and Java Intent code.
- On the web: `docs/index.html` is the same builder as a static page (enable GitHub Pages on
  `/docs`); its "Open on this device" button fires the link on the phone you're browsing from.

## Overlay behaviour

- A `TYPE_APPLICATION_OVERLAY` window over whatever app is in front, never taking focus.
  It's hosted by a short-lived foreground service (needed for audio + surviving the caller)
  that stops itself after the fade-out (and the end chime in guiding mode).
- **Tap** advances the message. **Hold ~1 s** or the notification's **Stop** button dismisses
  (the desktop's ESC / Cmd+.).
- Backdrop, cover, static and vignettes fill the whole screen; the artwork and text use one
  uniform scale of the 480x360 Scratch stage, so they keep their proportions in portrait.

## Differences from the desktop build

- The You Died SVG was basically an embedded PNG plus a gradient shadow; both are baked into
  `assets/Sprites/you_died.png` (2664x1496), since Android can't draw SVG natively.
- The curious ambient pair was stereo; it is folded to mono (what Web Audio's hard-pan did)
  so per-ear volume gives the same hard left/right.
- The engine takes wall-clock time and catches its tick counter up, so a dropped frame never
  slows the sequence.
- Empty `message=` falls back to the saved messages for that mode (desktop fell back to the
  guiding defaults).
- Android may keep the status bar above overlay windows; there's no way around that for
  `TYPE_APPLICATION_OVERLAY`.

DOORS and the Guiding / Curious Light belong to LSPLASH. Fan tribute recreating the Scratch
death-screen projects; assets extracted from them.
