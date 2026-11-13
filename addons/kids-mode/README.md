# Kids mode

Session and daily screen-time limits with a parent PIN, built as an optional add-on for
[CleanYourTV](https://github.com/Gathin23/cleanyourtv). It is a normal Android TV app, package
`com.gathin.kidsmode`; the launcher only decides whether to show it as a top-bar button or in the app library.

## What it does

- **Session limit**: after the configured minutes of screen-on time, the TV goes Home and a full-screen lock
  appears. The lock shows a rest countdown; when it ends a new session starts automatically.
- **Daily limit**: after the configured minutes today, the lock stays until midnight (the lock shows the time
  left). The daily total resets at 00:00.
- **No-control window**: a start and an end time, set on a 30-minute step, during which the timer pauses, no
  lock appears and the launcher's app library opens without the PIN. A window that ends earlier than it starts
  crosses midnight (22:00 to 07:00). Off by default; the summary reads “No control until 20:00” while it runs.
- **Parent PIN**: four digits, stored as a salted SHA-256 hash. The PIN gates the settings screen and unlocks
  any lock. Five wrong entries lock the keypad for 60 seconds.
- **Self-healing guard**: a foreground keeper, a boot receiver and a persisted 15-minute job put the guard's
  accessibility service back after MIUI force-stops the add-on on a power cycle or standby. It also cycles
  add/remove/add so Android drops the "crashed" mark a force stop leaves behind. With
  `WRITE_SECURE_SETTINGS` granted once over ADB, the add-on does this itself; without it, tap the guard row
  to open the Accessibility settings.
- **Temporary override**: on a lock screen, enter the PIN and choose 10, 15, 20, 30 or 60 minutes. The chosen
  duration is a countdown of screen-on time; when it runs out the lock returns. Choose again to extend.
  “Turn off kids mode” is also in that menu.
- **Home first**: before the lock page appears the guard sends Home, so the app that was playing goes to the
  background (and normally pauses) instead of continuing behind the lock.
- **Home/Back cannot dismiss the lock**: it is a full-screen `TYPE_ACCESSIBILITY_OVERLAY` above every app.
- The timer runs while the TV screen is on. Turning the TV off for at least the configured rest starts a new
  session; the override countdown pauses while the screen is off.
- Settings ranges: session 0/15/20/30/45/60/90 min, rest 5/10/15/20/30/45/60 min, daily
  0/30/60/90/120/180/240 min (0 = unlimited); the no-control window runs from 00:00 to 23:30 in 30-minute steps.

## Install

Requires JDK 17+ and Android SDK 34, the same as the launcher.

```sh
cd addons/kids-mode
./gradlew assembleRelease
adb connect <tv ip>:5555
adb install -r app/build/outputs/apk/release/app-release.apk
adb shell pm grant com.gathin.kidsmode android.permission.WRITE_SECURE_SETTINGS
adb shell am start -n com.gathin.kidsmode/.KidsModeActivity
```

The grant is a development permission, allowed over ADB once; it lets the add-on put its own guard back
after the firmware kills it. Without it the guard row opens the Accessibility settings instead. The
add-on keeps a low-priority "Kids mode is on" notification while the mode is on; that is the foreground
keeper, not an ad.

On the TV, enable **Kids mode guard** in **Settings → Accessibility**. To enable it over ADB while keeping every
service already enabled, append the component instead of replacing the list:

```sh
adb shell settings put secure enabled_accessibility_services "<existing>:com.gathin.kidsmode/com.gathin.kidsmode.KidsGuardService"
adb shell settings put secure accessibility_enabled 1
```

The reference launcher shows the app as a round logo button in the top bar when its package is listed in
`top_bar_apps` (this repository's config.xml already does that):

```xml
<string-array name="top_bar_apps" translatable="false">
    <item>com.gathin.kidsmode</item>
</string-array>
```

Without that entry, open **Kids mode** from the app library.

## Shell setup and tests

The receiver is exported only to callers holding `WRITE_SECURE_SETTINGS` (the adb shell):

```sh
adb shell am broadcast -a com.gathin.kidsmode.ADMIN -n com.gathin.kidsmode/.KidsAdminReceiver \
  --es pin 1234 --ei session_min 30 --ei wait_min 20 --ei daily_min 90 --ez enabled true
```

Useful extras: `--ez reset true` clears today's usage, `--ez clear_pin true` removes the PIN and returns to the
defaults, `--ez ensure_guard true` puts the guard back and starts the keeper, and `--ei override_seconds 5`
shortens an override for an expiry test. A no-control window is set in minutes past midnight (`-1` clears it):

```sh
adb shell am broadcast -a com.gathin.kidsmode.ADMIN -n com.gathin.kidsmode/.KidsAdminReceiver \
  --ei unmanaged_start_min 1080 --ei unmanaged_end_min 1200   # 18:00 to 20:00
```

## Permissions

- `WRITE_SECURE_SETTINGS`: a development permission, granted once over ADB. It lets the add-on put its own
  accessibility service back after the firmware force-stops it. Without it the guard row opens the
  Accessibility settings and everything else still works.
- `RECEIVE_BOOT_COMPLETED`: starts the keeper and restores the guard after a power cycle.
- `FOREGROUND_SERVICE`: keeps the add-on's process alive while kids mode is on; the low-priority
  "Kids mode is on" notification is this keeper.
- Accessibility service: draws the accessibility overlay and receives screen on/off. It retrieves no window
  content and filters no keys.

## Data

- No network, storage, analytics or telemetry. State is private SharedPreferences; the PIN is a salted
  SHA-256 hash.
- This is app-level parental control, not a tamper-proof kiosk: someone with access to the TV's system settings
  can switch the service off or clear the app's data.

## Tested on

Xiaomi MiTV-ANSP0 (小米电视5 Pro 65", Android TV 9) with the launcher in this repository.

The add-on and the launcher changes are MIT licensed, like the rest of this repository.
