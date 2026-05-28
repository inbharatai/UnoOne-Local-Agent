# UnoOne Local Agent — Laptop Setup Guide

## Who is this for?

You have never built a native Android app before. This guide tells you exactly what to click, what to install, what folder to create, what command to run, and how to test each part on your laptop and Xiaomi 14 phone.

---

## Step 1: Install Android Studio

1. Open your web browser.
2. Go to **https://developer.android.com/studio**.
3. Click the big green **"Download Android Studio"** button.
4. Choose **Windows** (64-bit).
5. Run the downloaded `.exe` file.
6. In the setup wizard:
   - Click **Next**.
   - Keep **Android Studio** checked. Click **Next**.
   - Choose an install location (default is fine: `C:\Program Files\Android\Android Studio`). Click **Next**.
   - Click **Install**.
   - Wait for it to finish. Click **Finish**.
7. Android Studio will launch. If it asks about importing settings, choose **"Do not import settings"** and click **OK**.
8. On the welcome screen, click **"More Actions"** → **"SDK Manager"**.
9. In the SDK Platforms tab, check **"Android 14.0 (API Level 34)"**.
10. In the SDK Tools tab, check:
    - **Android SDK Build-Tools 34**
    - **Android SDK Platform-Tools**
    - **Android SDK Command-line Tools**
    - **CMake**
    - **NDK (Side by side)** — choose the latest stable version
11. Click **Apply**, then **OK**. Wait for downloads to finish.

---

## Step 2: Verify Android Studio Bundled JDK

Android Studio comes with its own JDK. You do not need to install a separate one.

1. In Android Studio, go to **File** → **Settings** (or press `Ctrl + Alt + S`).
2. Navigate to **Build, Execution, Deployment** → **Build Tools** → **Gradle**.
3. Look at **Gradle JDK**. It should say something like `jbr-17` or `Embedded JDK`.
4. If it says `JDK 17` or higher, you are good. If not, click the dropdown and select the Android Studio bundled JDK.

---

## Step 3: Verify Android SDK and ADB

1. Open **File Explorer**.
2. Navigate to: `C:\Users\reetu\AppData\Local\Android\Sdk\platform-tools`
3. You should see `adb.exe` in this folder.
4. Copy this path.
5. Right-click the **Start** button → **System** → **Advanced system settings**.
6. Click **Environment Variables**.
7. Under **User variables**, find **Path** and click **Edit**.
8. Click **New** and paste the platform-tools path.
9. Click **OK** on all dialogs.
10. Open **PowerShell** (right-click Start → Terminal or Windows PowerShell).
11. Type: `adb version`
12. You should see something like `Android Debug Bridge version 1.0.xxx`

---

## Step 4: Install Git and Git LFS

1. Go to **https://git-scm.com/download/win**.
2. Download the 64-bit Git for Windows setup.
3. Run the installer. Accept all defaults (just keep clicking **Next**).
4. After install, open PowerShell and type: `git --version`
5. You should see something like `git version 2.43.x`.
6. Install Git LFS:
   - In PowerShell, type: `git lfs install`
   - You should see `Git LFS initialized.`

---

## Step 5: Install GitHub CLI

1. Go to **https://cli.github.com/**.
2. Click **"Download for Windows"**.
3. Run the installer.
4. Open PowerShell and type: `gh --version`
5. You should see the version number.

---

## Step 6: Install Python 3.11 or 3.12

1. Go to **https://www.python.org/downloads/**.
2. Click **Download Python 3.12.x**.
3. Run the installer.
4. **IMPORTANT**: Check the box **"Add python.exe to PATH"** at the bottom of the first screen.
5. Click **Install Now**.
6. Open PowerShell and type: `python --version`
7. You should see `Python 3.12.x`.

---

## Step 7: Install uv (Python package manager)

1. Open PowerShell.
2. Type:
   ```powershell
   powershell -ExecutionPolicy ByPass -c "irm https://astral.sh/uv/install.ps1 | iex"
   ```
3. Close and reopen PowerShell.
4. Type: `uv --version`
5. You should see a version number.

---

## Step 8: Install Hugging Face CLI

1. In PowerShell, type:
   ```powershell
   uv pip install huggingface-hub
   ```
2. Type: `huggingface-cli --version`
3. You should see the version.

---

## Step 9: Install scrcpy (Phone Mirroring)

1. Go to **https://github.com/Genymobile/scrcpy/releases**.
2. Download the latest Windows release (e.g., `scrcpy-win64-v2.4.zip`).
3. Extract the zip to a folder like `C:\tools\scrcpy`.
4. Add that folder to your **Path** environment variable (same way you added ADB).
5. Open PowerShell and type: `scrcpy --version`
6. You should see the version.

---

## Step 10: Open UnoOne Project in Android Studio

1. Open Android Studio.
2. On the welcome screen, click **"Open"**.
3. Navigate to: `C:\Users\reetu\Desktop\UnoOne-Local-Agent\android-app\UnoOneAgent`
4. Click **OK**.
5. Android Studio will load the project. This may take a few minutes the first time as it downloads Gradle and dependencies.
6. You may see a prompt **"Gradle files have changed"** — click **Sync Now**.
7. Wait for the sync to complete. Look at the bottom status bar. It should say **"Sync finished"**.

---

## Step 11: Build the Project

1. In Android Studio, look at the top toolbar.
2. Click **Build** → **Make Project** (or press `Ctrl + F9`).
3. Wait for the build. If it succeeds, you will see **"Build completed successfully"** at the bottom.
4. If you see errors, check the **Build** tab at the bottom and read the error message.

### Common Build Fixes

- **"JDK version mismatch"**: Go to File → Settings → Gradle → Gradle JDK → select JDK 17.
- **"Compose compiler version mismatch"**: Check `app/build.gradle.kts` → `composeOptions.kotlinCompilerExtensionVersion` matches your Kotlin version.

---

## Step 12: Set Up Xiaomi 14 for Development

### Enable Developer Options

1. On your Xiaomi 14, open **Settings**.
2. Scroll down and tap **About phone**.
3. Find **MIUI version** and tap it **7 times** quickly.
4. You will see a toast message: **"You are now a developer!"**

### Enable USB Debugging

1. Go back to **Settings** → **Additional settings**.
2. Tap **Developer options**.
3. Find **USB debugging** and turn it **ON**.
4. Tap **OK** on the warning dialog.

### Enable Install via USB

1. Still in **Developer options**.
2. Find **Install via USB** and turn it **ON**.
3. You may need to sign in with your Mi Account.

### Disable Battery Optimization for Test App

1. Open **Settings** → **Apps** → **Manage apps**.
2. Search for **UnoOne** after you install it.
3. Tap **Battery saver** → **No restrictions**.

---

## Step 13: Connect Xiaomi 14 to Laptop

1. Use a USB-C cable to connect your Xiaomi 14 to your laptop.
2. On the phone, a dialog will appear: **"Allow USB debugging?"**
3. Check **"Always allow from this computer"** and tap **OK**.
4. Open PowerShell on your laptop.
5. Type: `adb devices`
6. You should see something like:
   ```
   List of devices attached
   xxxxxxxx    device
   ```
7. If it says `unauthorized`, check the phone screen and approve the dialog.

---

## Step 14: Run Blank App on Xiaomi 14

1. In Android Studio, make sure your Xiaomi 14 appears in the device dropdown at the top toolbar.
2. Click the green **Run** button (▶) or press `Shift + F10`.
3. Android Studio will build an APK and install it on your phone.
4. The app will open automatically.
5. You should see the **UnoOne** home screen with:
   - "UnoOne" title
   - "Offline Local" badge
   - Big mic button
   - Text input
   - Quick actions
   - Bottom tabs: Agent, Notes, Skills, Logs, Settings

---

## Step 15: Mirror Phone Screen (Optional but Recommended)

1. Make sure your phone is still connected via USB.
2. Open PowerShell.
3. Type: `scrcpy`
4. Your phone screen will appear in a window on your laptop.
5. This is great for debugging without constantly picking up the phone.

---

## Step 16: Verify Enough Storage for Models

On your Xiaomi 14:
1. Open **Settings** → **About phone** → **Storage**.
2. Make sure you have at least **5 GB free**.
3. Models you will push later:
   - Gemma 2B: ~2.5 GB
   - Sherpa ASR: ~150 MB
   - Sherpa TTS: ~100 MB
   - VAD: ~1 MB

---

## What You Have Now

Your laptop is ready. Your phone is ready. The UnoOne project is open in Android Studio and builds successfully.

**Next step**: Download model files and push them to your phone, or start implementing features.

---

## Quick Reference Commands

| Task | Command |
|------|---------|
| Check ADB | `adb devices` |
| Install APK | `adb install app-debug.apk` |
| Push models | `.\scripts\adb-push-models\push-models.bat` |
| Mirror screen | `scrcpy` |
| Build debug | `gradlew assembleDebug` |
| Build release | `gradlew assembleRelease` |
