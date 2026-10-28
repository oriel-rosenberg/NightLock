# NightLock

A personal Android app blocker for bedtime (Kotlin, Jetpack Compose), sideloaded on the user's Samsung Galaxy.
README.md has the milestone plan, the permissions table, adb commands and the code layout.

## Build and test

- `.\build.cmd` builds the release `nightlock.apk` into this folder; `.\build.cmd testDebugUnitTest` runs the
  unit tests. Add `"-Pnightlock.testMode=true"` (quoted, for PowerShell) for a TEST_MODE build.
- The toolchain (JDK, Android SDK, adb, Gradle cache) lives in `C:\Dev\android-toolchain`, shared with the
  timetable app. Nothing is on PATH; `build.cmd` sets the environment. Build output goes to
  `C:\Dev\android-toolchain\build\nightlock`.
- There is no phone or emulator on this PC. The user installs and tests each milestone themselves.

## Working rules

- Build in the milestones listed in README.md and stop after each one so the user can test it.
- After each milestone, and before handing over an APK, have the `code-reviewer` agent review the changed
  files, and deal with what it finds.
- Raise `versionCode` in `app/build.gradle.kts` for every APK handed to the user.
- No network access ever: no INTERNET permission (the manifest strips it), no library that needs it.
- The accessibility service reads only which package each window belongs to. Milestone 4 may also read
  window titles / class names of the Settings app, and nothing else. Never read text, view IDs or input
  from other apps; never add gestures, key events, screenshots or text-change events. `SecurityTest`
  enforces the accessibility config and scans the code for content-reading calls; the `securityCheckReleaseManifest`
  task in `app/build.gradle.kts` enforces the merged manifest's permissions, exported components and
  backup flag. Widening any of them means changing those checks and README.md's Security section on
  purpose, with the user's OK.
- Release builds are signed only with the private key in `C:\Dev\android-toolchain\keys\nightlock`
  (never the debug key; without the key the APK is unsigned and `build.cmd` refuses it). Never copy
  that folder into the project. Never install a debug build on the user's phone.
- Minimal RAM, CPU, battery and storage: nothing may run between screen changes (no foreground service, no
  polling, no periodic watchdog, no alarms, no boot receiver). Work blocking state out from the clock on
  demand; the only timer is the service's handler for the next schedule change, which runs only while the
  phone is awake, with screen-on and time-change broadcasts re-checking the clock.
- No device admin / uninstall protection; the user chose an "Are you sure?" step instead (milestone 4).
- Every user-visible duration goes through TEST_MODE scaling once durations exist (milestone 2 onwards).
