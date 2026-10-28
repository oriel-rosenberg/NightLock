# NightLock

A personal Android app blocker for bedtime. It's sideloaded on one Samsung Galaxy and isn't meant for the Play Store.
Kotlin, Jetpack Compose, minSdk 26, targetSdk 37. No network permission.

## Status: milestone 2 of 4

| # | Milestone | State |
|---|-----------|-------|
| 1 | Block list, "Block now" switch, accessibility blocking, block screen | done |
| 2 | **Runs on its own:** schedules (crossing midnight), wind-down warning, survives reboots, setup checklist (incl. Samsung battery steps) | **this build** |
| 3 | **Hard to give in:** pause flow (delay, random text, timed re-block, escalation, nightly cap), strict mode + 24 h cooldown, emergency unlock, logs and stats | next |
| 4 | **Hard to escape:** blocking the Accessibility, Usage access and Date & time pages in Settings; an "Are you sure?" step when NightLock's App info page (Uninstall, Force stop) is opened during a block; clock-tampering detection | |

**Low power by design.** Nothing runs between screen changes: no foreground service, no polling, no
watchdog, no alarms. Whether a block is on is worked out from the clock each time the screen changes. While
the phone is awake, one timer fires at the next schedule change (a block starting while you're in a blocked app,
or a wind-down warning). It doesn't wake a sleeping phone; turning the screen on re-checks the clock instead,
since nobody is using an app while the screen is off. Android restarts the accessibility service after a
reboot by itself, so no "start at boot" permission is needed either. The trade-off: if the system kills the
accessibility service, nothing blocks until it restarts. The Samsung battery steps in the setup checklist make
that unlikely.

No uninstall protection: NightLock is not a device admin, so it can always be uninstalled normally. The
"Are you sure?" step only adds a pause before that.

The original plan had 6 milestones; they're grouped by what you test together. The onboarding checklist moved
into 2 because that's when the permissions it checks are added. The emergency unlock comes in 3, before
milestone 4 makes NightLock hard to switch off.

## Build

All the tools are in `C:\Dev\android-toolchain`, the same portable JDK and Android SDK the timetable app uses.
Nothing needs to be on PATH because `build.cmd` sets that up. Run these from this folder in PowerShell:

```powershell
.\build.cmd                                              # release APK -> nightlock.apk
.\build.cmd assembleRelease "-Pnightlock.testMode=true"  # TEST_MODE release -> nightlock.apk
.\build.cmd testDebugUnitTest                            # unit tests
```

Build output goes to `C:\Dev\android-toolchain\build\nightlock`, which keeps paths under Windows' 260-character limit.
**Install `nightlock.apk`, the release build.** It's signed with NightLock's private key (see *Security*).
`build.cmd` only updates it after a full `assembleRelease` run, which runs the unit tests and the manifest check
first; with skipped tasks (`-x`) it leaves the old file alone. Debug builds are for development only; never install one on the phone.

### TEST_MODE

The `-Pnightlock.testMode=true` flag sets `BuildConfig.TEST_MODE`. It shrinks every duration from minutes to
seconds: the wind-down warning now, and from milestone 3 pause delays, pause lengths and the strict-mode cooldown.
Schedule clock times (23:30) are never scaled. A TEST_MODE build
is called **NightLock TEST** on the home screen and shows a banner inside the app. To go back to normal
durations, build without the flag and install over it.

## Install and debug with adb

One-time phone setup: **Settings → About phone → Software information**, tap **Build number** 7 times, then
**Settings → Developer options → USB debugging** on. Plug in the phone and accept the "Allow USB debugging?" prompt.

Put adb on PATH for the current PowerShell window:

```powershell
$env:Path += ";C:\Dev\android-toolchain\sdk\platform-tools"
```

Then:

```powershell
adb devices                                   # the phone should be listed as "device"
adb install -r nightlock.apk                  # install or update (-r keeps settings)
adb logcat -c                                 # clear old log lines
adb logcat -s NightLock AndroidRuntime        # NightLock's log + crashes, live
adb shell am force-stop com.orirose.nightlock # restart from scratch
adb uninstall com.orirose.nightlock           # remove (also deletes its settings)
```

`adb logcat -s NightLock` shows lines like `Blocked com.google.android.youtube` and `Clock check: blocking=true, 3 apps`.

## Turning on the accessibility service

NightLock → **Open Accessibility settings** → **NightLock blocker** → on. On Samsung it's under
**Settings → Accessibility → Installed apps**.

**"Restricted setting" / switch greyed out (Android 13+).** Android blocks accessibility for apps that weren't installed
from a store, mainly ones installed by tapping an APK file on the phone. An `adb install` usually doesn't trigger it. Fix:
**Settings → Apps → NightLock → ⋮ (top right) → Allow restricted settings** (NightLock's **Open App info** button goes there), then
turn the service on again. The ⋮ entry only appears after you've tried to turn the service on once.

Via adb instead (this replaces the list of enabled services, so check first that it's empty or only has NightLock):

```powershell
adb shell settings get secure enabled_accessibility_services
adb shell settings put secure enabled_accessibility_services com.orirose.nightlock/com.orirose.nightlock.block.BlockerService
```

## Permissions

| Permission | Why |
|---|---|
| Accessibility service (`BIND_ACCESSIBILITY_SERVICE`) | How NightLock sees which app is on screen. Only the system can bind to it. Because the system keeps it bound, it may also open the block screen from the background, which Android otherwise forbids. |
| `QUERY_ALL_PACKAGES` | Since Android 11 apps only see a filtered list of other apps. Needed to list every app in the pickers and to find the phone, clock, launcher and Settings packages that must never be blocked. |
| `POST_NOTIFICATIONS` | Android 13+ asks you once. Only used for the wind-down warning; saying no just turns the warning off. |
| `INTERNET` (removed) | The manifest strips it with `tools:node="remove"`, so no library can add it. |

Deliberately **not** used: exact alarms, start at boot, foreground service, usage access, display over other apps,
device admin. The battery "Unrestricted" setting in the checklist is a Samsung setting you change, not a permission.

## Security

An accessibility service *can* be very powerful: in general, Android lets one read the screen, see what you type,
and tap for you. Two layers keep NightLock's use of it small.

**1. What Android allows it (the config).** NightLock is only told when windows change. It never asks to hear
typing or text changes, and it has no gestures, key interception, screenshots or magnification. One thing
can't be narrowed: to learn *which app* a window belongs to, it needs "retrieve window content", and Android
grants that as all-or-nothing. In principle that access could also read text in other apps or press their buttons.

**2. What the code does with it.** For each window on screen NightLock reads its package name
("com.google.android.youtube"), plus the window's ID, type (app or system) and whether it's picture-in-picture.
It never looks inside a window: no text, no buttons. The only thing it does is press Home. The tests scan
the code for the usual ways of reading text, looking at buttons, pressing things, changing the service's
own settings or using another system action, and fail the build if one appears. This is a best-effort guard
backed by a code review at every milestone; it can't rule out every conceivable trick on its own. A release
APK is only built after those tests pass.

**Nothing else can change that code on your phone** (see *Only you can update it* below), so layer 2 holds.
The build also checks the final APK's manifest, including what libraries add: it fails on any new permission,
any component other apps could reach, or backups being enabled. Today other apps can reach NightLock's launcher
icon, its accessibility service (which only the system can connect to), and a start-up speed helper from
Compose (which only adb and the system can use).

**Apps can't make themselves unblockable.** The "always allowed" essentials are only preinstalled apps, the
apps you chose as defaults (home screen, phone, SMS), and the app that set your next alarm. An app that merely
claims to be a dialer or settings app doesn't count. NightLock checks these again right before every block, so
an app that just became your default home screen or phone app is never covered. **If you use a third-party alarm
app, add it to the allowlist** to be sure its ringing screen always shows.
NightLock's links to Settings go only to a preinstalled app; if none can open the page, nothing opens.

**Nothing can leave the phone.** No internet permission (stripped even if a library asks for it), nothing shared
with other apps. Settings stay in NightLock's private storage, with Android backup off. Logs hold package names
only, and only adb can read them.

**Only you can update it.** Android installs an update over NightLock only if it's signed with the same key, and an
update keeps NightLock's accessibility access. Release builds are signed with a private 4096-bit RSA key with a
random password, kept in `C:\Dev\android-toolchain\keys\nightlock`. Without that key no one can push a modified
NightLock onto your phone. **Back that folder up somewhere private.** If you lose it, you have to uninstall
NightLock (losing its settings) before you can install a new version.

**Libraries:** AndroidX (Google) plus Kotlin's standard library, coroutines and kotlinx-serialization (JetBrains),
all from Google's and Maven Central's repositories. Their file hashes aren't pinned, so the build trusts those repositories.

**Never install a debug build on your phone.** Debug builds are signed with Android's standard development key, not
the private one, so while one is installed the "only you can update it" guarantee doesn't hold. A debug build also
can't install over the release build.

**What's left, honestly:**
- **USB debugging** lets a computer you plug into install apps and read logs. Turn it off after installing
  (Developer options → USB debugging) and back on only for updates.
- **Your PC:** someone with access to it and to the key folder could build a malicious NightLock. They would still
  need your unlocked phone with USB debugging on to install it.
- **Banking apps** can see that a non-store app has an accessibility service on, and some refuse to run or show a
  warning because of it. That's their policy, not a sign that NightLock is doing anything.
- **Android's Advanced Protection mode** switches off accessibility services that aren't accessibility tools,
  NightLock's included.

## How blocking works

- `BlockerService` (accessibility) listens for `TYPE_WINDOW_STATE_CHANGED` and `TYPE_WINDOWS_CHANGED`. It checks the
  package of the event and of every visible app window (`flagRetrieveInteractiveWindows`), so both halves of split screen
  and Samsung pop-up windows are covered (pop-ups tested on a Galaxy A54). Each window's package is read once and remembered, so a busy app can't slow it down.
- When it finds a blocked app on screen, it opens `BlockActivity` full screen. Back, or **Go to home screen**, opens the
  launcher. The block screen has its own task and is hidden from Recents, so it can't lead back to the blocked app.
- 1.5 s later the service checks again. If a blocked app is somehow still visible (split screen, or the block screen
  failed to open), it presses Home.
- **Never blocked**, whatever the block list says: NightLock, the launcher, System UI, phone/dialer/in-call, the clock app
  (its alarm screen must be able to show), and Settings. These are locked in the allowlist. Messages and Maps start in
  the allowlist and can be removed. You can add any app to it. The allowlist can only be edited while no block is on.

**Schedules.** Each has a name, the days it *starts* on, a start and end time, an optional wind-down warning, and
either the main block list or its own list. An end time before the start (23:30–07:00) runs into the next morning:
a Monday 23:30–07:00 schedule blocks from Monday 23:30 to Tuesday 07:00. Times are the phone's wall-clock times.
Schedules that touch (one ends at 07:00, another starts at 07:00) count as one block, and the block screen shows
the final end time. When a block ends while the block screen is up, it closes by itself.

## Testing milestone 2

1. Install over the old version (`adb install -r nightlock.apk`); your block list stays.
2. Open **Setup checklist**: the blocker service should be ✓. Tap **Allow notifications** and allow. Battery and
   "Never sleeping apps" are recommended.
3. **Add schedule** starting 3 minutes from now, ending 10 minutes from now, today ticked, warning 1 min... the
   shortest choice is 5 min, so either start it 6+ minutes from now or use a TEST_MODE build (warning in seconds).
4. Open YouTube and leave it open. At the start time the block screen should appear without you touching anything.
   The block screen shows the schedule name and its end time.
5. Before the start time, the wind-down notification should appear (if the start is far enough away).
6. At the end time the block screen closes by itself, and YouTube works again.
7. **Overnight:** make a schedule like 23:00–00:30 today and check the home screen says it ends *tomorrow 00:30*.
8. **Reboot:** with a schedule running, restart the phone, unlock it and open YouTube. It should be blocked.
9. **Own list:** give a schedule its own list with one different app, and check only that app is blocked during it.

## Known gaps in this build

These are fixed in later milestones or accepted as limitations:

- **Easy to undo for now.** Anyone can switch off *Block now*, edit or switch off a schedule, untick apps, or turn off
  the accessibility service. Strict mode (3) and Settings-page blocking (4) close those holes.
- **Changing the phone's clock** moves the schedules with it. Clock-tampering detection comes in milestone 4.
- **No fallback** if the accessibility service gets killed. This is deliberate, to save battery (see *Low power by design*).
- **Picture-in-picture** windows of blocked apps are left alone. Covering them would trap you on the block screen, because
  PiP floats above it. Opening the PiP window to full size is blocked as normal.
- **Secure Folder / work profile**: apps installed only there don't show in the picker. A blocked package is still blocked there.

## Code layout

```
app/src/main/java/com/orirose/nightlock/
  NightLockApp.kt          Application; holds ConfigRepository
  MainActivity.kt          navigation between the settings screens
  Durations.kt             every duration, scaled to seconds in TEST_MODE
  Format.kt                times, days and durations as text
  data/Config.kt           Config (one JSON document in DataStore), what's blocked when + ConfigRepository
  data/Schedule.kt         Schedule and its runs (Occurrence); crossing midnight (unit tested)
  data/Apps.kt             installed-app loading, Essentials (never-blocked apps)
  block/BlockRules.kt      which packages are blocked at a moment (unit tested)
  block/BlockerService.kt  accessibility service: detects blocked apps, schedule timer, opens the block screen
  block/BlockActivity.kt   the block screen
  block/Warnings.kt        the wind-down notification
  ui/                      Compose screens (home, schedule editor, app lists, setup checklist), theme, view model
```
