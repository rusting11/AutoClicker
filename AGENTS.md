# AutoClicker

Single-module Android app (Groovy Gradle DSL) that simulates repeated screen taps via an AccessibilityService. Package: `com.example.autoclicker`.

## Build / verify

- Build debug APK: `.\gradlew.bat :app:assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`
- Install to a connected device: `.\gradlew.bat :app:installDebug`
- Verification is `:app:assembleDebug` + `:app:lintDebug`; there are no `src/test` or `src/androidTest` source sets, so `test` runs nothing.
- `java` is not on PATH. Always use `gradlew.bat`; it resolves the Android Studio JBR (`D:\Program Files\Android\Android Studio\jbr`). Don't call a system `java`/`gradle`.
- The accessibility-service behavior (gestures, overlay) cannot be verified by building; it needs a real device/emulator with the service enabled manually in system settings.

## Toolchain and network constraints

- Gradle 9.4.1 (wrapper) + Android Gradle Plugin 9.2.1; `compileSdk`/`targetSdk` 36, `minSdk` 26, Java 17.
- Repositories are China mirrors on purpose: Aliyun Maven in `settings.gradle`, Tencent Gradle mirror in `gradle/wrapper/gradle-wrapper.properties`. Keep them; switching to `services.gradle.org`/plain `google()` can fail.
- `local.properties` contains a machine-specific `sdk.dir`; don't rewrite or commit it.
- Dependencies are declared inline in `app/build.gradle` (no version catalog); only AppCompat and Material.

## Architecture

- `MainActivity.java`: all UI, validation, `SharedPreferences` (`autoclicker` prefs), 1-second status polling; implements `ClickAccessibilityService.Listener`.
- `ClickAccessibilityService.java`: singleton (`getInstance()`, static `Listener`) that dispatches taps with `dispatchGesture`, runs the click loop, and shows the position-picker overlay (`TYPE_ACCESSIBILITY_OVERLAY`).
- `app/src/main/res/xml/accessibility_service_config.xml` enables `canPerformGestures`; the service is declared in `AndroidManifest.xml`.

## Behavior constraints (enforced in MainActivity)

- Click count `0` means infinite.
- Interval is clamped to at least 50 ms; tap duration adapts 20–50 ms from the interval, and the loop uses absolute-time scheduling, reaching roughly 15–20 clicks/s at the minimum interval.
- Start delay is limited to 0–60 seconds.

## Editing notes

- All user-facing copy is Chinese and files are UTF-8. Use UTF-8-aware tools; the PowerShell console may display mojibake due to its codepage even though files are fine. Don't "repair" text unless the file itself is invalid.
- Most UI text is hardcoded in `res/layout/activity_main.xml`; `res/values/strings.xml` only holds the app name and accessibility-service labels.
- `.gradle/`, `build/`, `app/build/`, and `.idea/` are generated build/IDE output.
