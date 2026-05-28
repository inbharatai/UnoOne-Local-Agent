# UnoOne Verification Guide — Run on Xiaomi 14

> **CRITICAL: The project is NOT on your Desktop.**  
> **Open this exact path in Android Studio:**  
> `C:\Users\reetu\Desktop\UnoOne-Local-Agent\android-app\UnoOneAgent`  
> Do NOT open `C:\Users\reetu\Desktop\UnoOne` — that folder is empty.

## Goal

Confirm the UnoOne Android skeleton builds, installs, and runs correctly on your Xiaomi 14 **before any model integration**.

---

## Step 1: Open Project in Android Studio

1. Open **Android Studio**.
2. On the welcome screen, click **Open** (not "New Project").
3. In the file picker, paste this exact path:
   ```
   C:\Users\reetu\Desktop\UnoOne-Local-Agent\android-app\UnoOneAgent
   ```
4. Click **OK**.
5. Wait for the project to load.

---

## Step 2: Gradle Sync Checklist

Android Studio will automatically start a **Gradle sync** when you open the project. Watch the bottom status bar.

### What you should see:

| Check | Expected | Where to look |
|-------|----------|---------------|
| Gradle sync starts | Progress bar at bottom | Status bar says "Gradle: Build" |
| Downloads dependencies | May take 5-10 minutes first time | "Downloading..." messages in Build window |
| Sync finishes | Green checkmark | Status bar says "Gradle sync finished" |
| No red errors | Build window shows no errors | Build tab has no red text |

### If Gradle sync fails:

1. Look at the **Build** tab at the bottom.
2. Read the **first error message** (red text).
3. Common fixes:

#### Error: "Could not resolve plugin com.android.application"
- Go to **File → Settings → Appearance & Behavior → System Settings → HTTP Proxy**.
- Make sure **"No proxy"** is selected.
- Click **OK** and retry sync.

#### Error: "JDK version mismatch" or "Unsupported class file major version"
- Go to **File → Settings → Build, Execution, Deployment → Build Tools → Gradle**.
- Under **Gradle JDK**, select the Android Studio bundled JDK (should be JDK 17 or higher).
- Click **OK** and retry sync.

#### Error: "Compose compiler version mismatch"
- Open `app/build.gradle.kts`.
- Find `composeOptions { kotlinCompilerExtensionVersion = "1.5.13" }`.
- Make sure this matches your Kotlin version. For Kotlin 1.9.23, use `1.5.13`.
- If you changed Kotlin version, update this number to match.
- Click **Sync Now** in the notification bar.

#### Error: "Cannot find symbol Color" in Theme.kt
- This should already be fixed. If you see it, open `app/src/main/java/com/unoone/agent/ui/theme/Theme.kt`.
- Make sure line 16 has: `import androidx.compose.ui.graphics.Color`

#### Error: Any red text in any .kt file
- Click on the red text.
- Press **Alt + Enter**.
- Select **"Import class"** or **"Add dependency"** if offered.

---

## Step 3: Confirm Versions

After successful sync, check these versions:

| Component | Expected Version | How to check |
|-----------|-----------------|--------------|
| Android Studio | Latest stable (Koala or newer) | Help → About |
| Gradle | 8.7 | `gradle/wrapper/gradle-wrapper.properties` |
| Android Gradle Plugin (AGP) | 8.5.0 | `build.gradle.kts` (top level) |
| Kotlin | 1.9.23 | `build.gradle.kts` (top level) |
| Compile SDK | 34 | `app/build.gradle.kts` |
| Min SDK | 28 | `app/build.gradle.kts` |
| JDK | 17+ | File → Settings → Gradle → Gradle JDK |

---

## Step 4: Connect Xiaomi 14

### Enable Developer Options (if not already done)

1. On your Xiaomi 14, open **Settings**.
2. Scroll down and tap **About phone**.
3. Tap **MIUI version** 7 times quickly.
4. You will see: **"You are now a developer!"**

### Enable USB Debugging

1. Go back to **Settings** → **Additional settings**.
2. Tap **Developer options**.
3. Find **USB debugging** and turn it **ON**.
4. Tap **OK** on the warning.

### Connect to Laptop

1. Use a USB-C cable to connect your Xiaomi 14 to your laptop.
2. On the phone, you may see: **"Allow USB debugging?"**
3. Check **"Always allow from this computer"** and tap **OK**.

---

## Step 5: Confirm ADB Detects the Phone

1. Open **PowerShell** on your laptop (right-click Start button → Terminal or Windows PowerShell).
2. Type:
   ```powershell
   adb devices
   ```
3. You should see something like:
   ```
   List of devices attached
   abc123def456    device
   ```
4. If it says `unauthorized`, check your phone screen and approve the dialog.
5. If no device appears:
   - Unplug and replug the USB cable.
   - Try a different USB port.
   - Make sure you installed Android SDK Platform Tools (see setup-guide.md).

---

## Step 6: Run the App

1. In Android Studio, look at the **device dropdown** at the top toolbar.
2. It should show your Xiaomi 14 model name (e.g., `Xiaomi 14`).
3. Click the green **Run** button (▶) at the top, or press **Shift + F10**.
4. Android Studio will:
   - Build the APK
   - Install it on your phone
   - Launch it automatically
5. Wait for the build. First build may take 2-3 minutes.
6. Watch the **Run** window at the bottom for progress.

### Build errors?

If the build fails, read the first red error in the **Build** tab.

#### "Cannot find symbol" errors
- Usually a missing import. Press **Alt + Enter** on the red text and select **"Import class"**.

#### "Unresolved reference" for Compose functions
- Make sure `app/build.gradle.kts` has `implementation(platform("androidx.compose:compose-bom:2024.05.00"))`.

#### Any other error
- Copy the exact error message.
- Paste it into the chat so I can fix it.

---

## Step 7: App Opens — First Look

When the app opens on your Xiaomi 14, you should see:

1. **"UnoOne"** in large text at the top.
2. **"One private AI agent for every phone action."** as subtitle.
3. **"Offline Local"** badge below that.
4. A big **circular mic button** in the center.
5. A **text input field** with a **"Go"** button.
6. Four **quick action buttons**: Create Note, Open Chrome, Calendar, Open App.
7. **"Agent Flow Timeline"** section below.
8. **Bottom navigation tabs**: Agent, Notes, Skills, Logs, Settings.

If any of these are missing or the app crashes, check the **Logcat** tab in Android Studio and tell me the error.

---

## Step 8: Disable Battery Optimization

Before testing, make sure the phone does not kill the app:

1. On your Xiaomi 14, open **Settings** → **Apps** → **Manage apps**.
2. Search for **UnoOne**.
3. Tap **Battery saver**.
4. Select **No restrictions**.

---

## What to Do Next

After the app opens successfully, follow **docs/test-checklist.md** to run every test and record results.

Do **not** proceed to Sherpa-ONNX or Gemma integration until all tests pass.
