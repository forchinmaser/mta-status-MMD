# Running `prodDebug` on the Kompakt from Android Studio

## One-time setup

1. **Android Studio** — <https://developer.android.com/studio>. Accept the SDK /
   platform-tools / build-tools downloads in the setup wizard.
2. **JDK 17** is bundled with Studio; nothing to install.
3. **Open the project**: File → Open → select the `android/` folder (the folder
   itself, not a file inside it).
4. Studio will report the Gradle wrapper jar is missing and offer to generate and
   sync it — accept. `gradle/wrapper/gradle-wrapper.properties` is already pinned
   to **Gradle 8.7**; if Studio ever offers "Upgrade Gradle to 9.x", **decline**
   (AGP 8.5.2 does not support Gradle 9, and Gradle 9 turns on a configuration
   cache the Kotlin plugin cannot serialise).
5. Wait for "Gradle sync finished". Two modules should appear: `app` and `mmd-core`.

## Put the Kompakt in developer mode

1. Settings → About phone → tap **Build number** seven times.
2. Settings → System → Developer options → enable **USB debugging**.
3. Connect by USB. Accept the "Allow USB debugging?" RSA prompt on the phone
   (tick "Always allow from this computer").
4. Confirm the Mac sees it: **View → Tool Windows → Terminal**, then
   `adb devices` — the Kompakt should be listed as `device`, not `unauthorized`.

## Select and run the prodDebug variant

1. **View → Tool Windows → Build Variants**.
2. In the `app` row, set **Active Build Variant** to **`prodDebug`**.
   (Leave `mmd-core` on `debug`.)
3. Pick the Kompakt in the device dropdown in the toolbar.
4. Press **Run ▶** (or ⌃R). Studio builds, installs, and launches.

First run needs network on the phone: tapping TRAIN downloads the subway GTFS
feed (~10 MB) and parses it. The screen shows "Downloading subway schedule…" then
"Reading subway schedule…". Later launches read the parsed cache and are instant.

To test the UI with no network, switch the variant to `devDebug` — it installs as
a separate app (`.dev` suffix) and uses the bundled sample routes.

## Getting an APK file

**Build → Build Bundle(s)/APK(s) → Build APK(s)** →
`app/build/outputs/apk/prod/debug/app-prod-debug.apk`.

Command line equivalent:

    ./gradlew :app:assembleProdDebug
    adb install -r app/build/outputs/apk/prod/debug/app-prod-debug.apk

A debug APK is signed with a throwaway debug key — fine for your own device, not
for distribution (that needs a release keystore and `assembleProdRelease`).

## Common snags

| Symptom | Fix |
| --- | --- |
| `SDK location not found` | `echo "sdk.dir=$HOME/Library/Android/sdk" > android/local.properties` |
| `Unsupported provider … task completion listener` | Gradle 9 with AGP 8.5.2. `./gradlew --version` should say 8.7; if not, `./gradlew wrapper --gradle-version 8.7`. |
| `Unsupported class file major version` | Wrong JDK. File → Settings → Build Tools → Gradle → set Gradle JDK to the bundled JDK 17. |
| `adb devices` shows `unauthorized` | Unlock the phone, accept the USB-debugging prompt. |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | `adb uninstall com.example.transitkompakt`, install again. |
| `INSTALL_FAILED_OLDER_SDK` | Lower `minSdk` in `app/build.gradle.kts` to `adb shell getprop ro.build.version.sdk`. |
| Route lists stay empty on `prodDebug` | No network, or MTA feed blocked. Check `adb logcat -s GtfsImporter`. |
| Fonts look wrong | MMD ships Lato in `mmd-core/src/main/res/font`; confirm `:mmd-core` is a project dependency. |

## Pinning the paging keys

The app listens on DPAD up/down, volume up/down, page up/down and the soft keys.
If the Kompakt uses something else:

    adb logcat -s KompaktKeys

Open a route, press the key, read the `unmapped keyCode=NNN` line, and add NNN to
`PAGE_UP_KEYS` / `PAGE_DOWN_KEYS` in `app/src/main/java/.../ui/HardwareKeys.kt`.
