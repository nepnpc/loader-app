# Loader — Deployment Guide

## 1. Deploy the Backend (Railway — free)

1. Create a free account at https://railway.app
2. New Project → Deploy from GitHub Repo
3. Point it to the `backend/` folder (or push just that folder)
4. Railway auto-detects Python + `Procfile`
5. Set env var if needed: `PORT` is set automatically
6. Copy the generated URL, e.g. `https://loader-api-production.up.railway.app`

**Alternative: Render (free)**
- New Web Service → Python environment
- Build command: `pip install -r requirements.txt`
- Start command: `uvicorn main:app --host 0.0.0.0 --port $PORT`

**Local testing:**
```bash
cd backend
pip install -r requirements.txt
uvicorn main:app --reload --port 8000
# API at http://localhost:8000
```

---

## 2. Build the Android APK

### Requirements
- Android Studio Hedgehog or newer  
  OR Java 17 + Android SDK (command line)

### Steps
1. Open `android-app/` in Android Studio
2. In `MainActivity.kt` line ~28, set `DEFAULT_BASE_URL` to your Railway URL
3. **Build → Build Bundle(s)/APK(s) → Build APK(s)**
4. APK is at `app/build/outputs/apk/release/app-release.apk`

**Command-line build (Windows):**
```bat
cd android-app
gradlew.bat assembleRelease
```

**Sign the APK** (required for sideloading):
```bash
keytool -genkey -v -keystore loader.jks -keyalg RSA -keysize 2048 -validity 10000 -alias loader
# Then in build.gradle add signingConfigs block with your keystore details
```

---

## 3. Host the APK + Website

### Option A — GitHub Pages (recommended, free)
1. Create a repo `loader-app` on GitHub
2. Push everything to it
3. Upload the APK to **Releases** (not git-tracked)
4. Enable GitHub Pages on `website/` folder (or `docs/`)
5. Update the `href` in `website/index.html` with your release URL

### Option B — Netlify Drop
- Drag & drop the `website/` folder at https://app.netlify.com/drop
- Upload APK separately to a file host (e.g., GitHub Releases)

---

## 4. Install on Android Device

1. On your Android phone, open the website URL in Chrome
2. Tap **Download APK**
3. When prompted, tap **Open** → Allow from unknown sources
4. Install → Open → tap server icon → enter your backend URL

---

## Architecture

```
Android App  ──POST /info──►  FastAPI Backend  ──yt-dlp──►  YouTube / any site
             ◄──formats────
             ──GET /download──►  Backend  ──proxy stream──►  CDN
             ◄──streaming file────────────────────────────
```

- Downloads stream directly through your backend — no temp files
- Supports YouTube, Twitter/X, Instagram, TikTok, Vimeo, and 1000+ more sites via yt-dlp
- yt-dlp is updated frequently; update it on the server with `pip install -U yt-dlp`

---

## Updating yt-dlp on Railway

In Railway dashboard → your service → Shell:
```bash
pip install -U yt-dlp
# Restart the service
```
