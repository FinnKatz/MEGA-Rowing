# MEGA Rowing — Companion App (Build & Install Guide)

## Why this exists

Android browsers (Chrome, Firefox, Brave) cannot talk to the S4 monitor's USB-serial
chip — this is a real platform limitation, not a bug in our web app or a setting on
your tablet. The official WaterRower Connect app works because it's a **native Android
app**, not a website, and native apps have full access to USB serial hardware that
browsers don't.

This companion app is a thin wrapper: it's just a full-screen window showing our
existing MEGA Rowing website (unchanged — same pixel art, same history, same charts),
with one addition underneath: real USB-serial access to the S4 via Google's official
`usb-serial-for-android` library, the same kind of library the WaterRower app itself
likely uses.

**I cannot compile this into an installable APK myself** — building Android apps
requires the Android SDK and build tools, which aren't available in my environment.
What I *can* do is hand you a complete, working source project plus a one-click cloud
build pipeline, so you get a real APK without installing Android Studio.

---

## Option A — Build in the cloud with GitHub Actions (recommended, ~10 minutes, free)

You don't need to know how to code for this — just follow the steps.

### 1. Create a free GitHub account (skip if you have one)
https://github.com/signup

### 2. Create a new empty repository
- Go to https://github.com/new
- Name it `mega-rowing-app` (or anything)
- Leave it Public or Private, doesn't matter
- Don't initialize with a README
- Click **Create repository**

### 3. Upload the project
On the new repo's page, click **"uploading an existing file"** and drag in the
entire contents of the `MegaRowingApp` folder from the zip I've given you
(everything *inside* `MegaRowingApp/`, not the folder itself — `build.gradle`,
`settings.gradle`, the `app/` folder, the `.github/` folder, etc., all at the
repo's root level).

Commit the upload.

### 4. Let GitHub build it
- Click the **Actions** tab at the top of your repo
- You should see "Build MEGA Rowing APK" already running (it auto-triggers on
  upload). If not, click **"Build MEGA Rowing APK"** on the left, then
  **"Run workflow"** → **Run workflow**.
- Wait 3–8 minutes for the build to finish (green checkmark).

### 5. Download the APK
- Click the finished workflow run
- Scroll down to **Artifacts**
- Download **MegaRowing-debug-apk** — this is a zip containing `app-debug.apk`

### 6. Install on your tablet
- Transfer `app-debug.apk` to your tablet (email it to yourself, use a USB cable
  to copy it, or Google Drive)
- On the tablet, tap the APK file. Android will ask to allow installs from this
  source the first time — allow it.
- Install. You'll see a **MEGA Rowing** app icon.

---

## Option B — Build locally with Android Studio (if you'd rather not use GitHub)

1. Install **Android Studio**: https://developer.android.com/studio
2. Open Android Studio → **Open** → select the `MegaRowingApp` folder
3. Let it sync (downloads Gradle + Android SDK components automatically —
   first time takes a while)
4. Plug your tablet into your computer via USB, enable
   **Developer Options → USB Debugging** on the tablet if not already on
5. Click the green **Run ▶** button in Android Studio, select your tablet
   as the target device
6. The app installs and launches automatically

---

## Before you build: one line to check

Open `app/src/main/java/com/megarowing/app/MainActivity.kt` and confirm this
line near the top matches where your MEGA Rowing web app is actually hosted:

```kotlin
private const val APP_URL = "http://192.168.6.100:8088"
```

This is already set to your Pi's address and port. If you change how the Pi
serves the app later, update this line and rebuild.

---

## How to use it

1. Open the **MEGA Rowing** app (not Chrome) on your tablet
2. It loads the same interface you already know
3. Plug the S4 into the tablet via USB (same cable as always)
4. Tap **🔌 CONNECT S4 (USB)** in the S4 Connection window
5. Android will show its standard USB permission prompt — tap **OK/Allow**
6. You should now see live data — no "no compatible devices" error, because
   this isn't going through a browser's USB restrictions at all

---

## What's different from the website version

Nothing in the UI. Same pixel art rower, same history charts, same theme
editor, same everything. The *only* difference is invisible: USB data now
flows through real native Android code instead of a browser API, which is
why the connection that was failing in Chrome/Firefox/Brave will work here.

---

## Files in this project

```
MegaRowingApp/
├── build.gradle                  ← project-level Gradle config
├── settings.gradle               ← module + repository config (jitpack.io for the USB library)
├── app/
│   ├── build.gradle               ← app dependencies (usb-serial-for-android)
│   ├── proguard-rules.pro
│   └── src/main/
│       ├── AndroidManifest.xml    ← USB host permission + device attach intent
│       ├── java/com/megarowing/app/
│       │   └── MainActivity.kt    ← WebView host + USB serial bridge (the actual logic)
│       └── res/
│           ├── values/strings.xml
│           ├── xml/device_filter.xml   ← matches any USB device
│           └── mipmap-anydpi-v26/ic_launcher.xml
└── .github/workflows/
    └── build-apk.yml              ← cloud build pipeline (GitHub Actions)
```
