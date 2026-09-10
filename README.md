# Android YouTube Acquisition Smoke

Isolated, debug-only Android feasibility prototype. It does not contain or call
Auto Music Shorts V1/V2/cloud-mobile code.

## Scope

- Kotlin Android activity
- URL paste and Android `ACTION_SEND` (`text/plain`)
- `youtubedl-android` 0.18.1, arm64-v8a only
- refresh to latest stable yt-dlp before each test
- metadata fetch followed by one pre-merged video+audio format download
- no cookies, login, proxy/VPN configuration, or manual PO Token
- output only under the app's `cacheDir`
- Android `MediaMetadataRetriever` duration/media validation

The wrapper ships Python and QuickJS. Its README describes bundled Python 3.8,
while current upstream yt-dlp officially supports CPython 3.10+. Therefore the
real-device test is deliberately treated as compatibility evidence, not assumed
success.

## Suggested public test URLs

Use only videos you are authorized to download. These are suggested stable,
non-live public test candidates; replace any that becomes unavailable or
restricted.

1. `https://www.youtube.com/watch?v=jNQXAC9IVRw` — Me at the zoo
2. `https://www.youtube.com/watch?v=M7lc1UVf-VE` — YouTube API demo
3. `https://www.youtube.com/watch?v=aqz-KE-bpKQ` — Big Buck Bunny

Run all three on Wi-Fi. If all succeed, disable Wi-Fi and repeat at least one on
cellular data. Record Metadata, Download, file size, duration, and the displayed
failure category/error.

## Build

This PC intentionally receives no Android SDK/JDK/Gradle installation. The
GitHub Actions workflow builds the arm64 debug APK and uploads it as a 7-day
artifact.
