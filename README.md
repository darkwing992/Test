# Phone to TV

An Android app that mirrors your phone's screen **and sound** to your TV over Wi-Fi.
No Chromecast or extra hardware needed — the TV just needs a web browser.

## How it works

1. The phone app captures the screen (Android MediaProjection) and the sound other apps
   are playing (Android 10+ AudioPlaybackCapture).
2. It runs a small web server on the phone. Video is sent as JPEG frames and sound as raw
   48 kHz stereo audio over a WebSocket.
3. The TV opens the address shown in the app (e.g. `http://192.168.1.23:8080`) in its web
   browser and plays both. Old frames are dropped, so it stays close to live.

## Using it

1. Install the APK on your phone (Android 10 or newer). See "Getting the APK" below.
2. Put the phone and TV on the **same Wi-Fi** (or turn on the phone's hotspot and join it from the TV).
3. Open **Phone to TV**, tap **Start casting**, allow screen recording and the audio permission.
4. On the TV, open the web browser (Silk on Fire TV, the TV's built-in browser, a game console,
   or a laptop plugged in by HDMI) and type the address shown on the phone.
5. Press **OK/Start** on the TV page once — browsers need one click before they'll play sound.

Tips:
- "Smooth" quality gives higher frame rate on weaker Wi-Fi; "Sharp" gives 1080p.
- Turn the phone volume down if you don't want sound from both.
- Apps with DRM (Netflix, Disney+, etc.) block capture: they show black and/or stay silent.
  Use their own Cast button for those.

## Getting the APK

Every push builds the app with GitHub Actions. Open the **Actions** tab → latest
"Build APK" run → download **PhoneToTV-apk**, unzip, and open the `.apk` on your phone
(allow "install unknown apps" when asked).

To build locally with the Android SDK installed: `./gradlew assembleRelease`
(output in `app/build/outputs/apk/release/`).

## Project layout

- `app/src/main/java/com/castapp/tv/CastService.java` — screen + audio capture, foreground service
- `app/src/main/java/com/castapp/tv/StreamServer.java` — dependency-free HTTP + WebSocket server
- `app/src/main/java/com/castapp/tv/MainActivity.java` — the phone UI
- `app/src/main/assets/receiver.html` — the page the TV opens
