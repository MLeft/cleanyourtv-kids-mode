# Keep Home and the kids guard first-class (MiTV-ANSP0)

Use this when changing the launcher, Home or launch behavior, accessibility, or the kids-mode guard
on this fork's Xiaomi TV. MIUI force-stops third-party apps when the TV powers off and drops their
accessibility services; the recovery path is part of the feature, not deployment trivia.

## State that must hold

- CleanYourTV (`com.gathin.tvhome`) is the default Home and the Home key returns to it.
- The stock launcher `com.mitv.tvhome` stays installed, force-stopped and not Home. Never disable
  it: on this firmware that falls back to a black `com.android.tv.settings` FallbackHome, and the
  shell cannot re-enable that Home component (`SecurityException`). To get it out of the way use
  `am force-stop com.mitv.tvhome`, not `pm disable-user`.
- Both CleanYourTV and the kids-mode add-on hold `WRITE_SECURE_SETTINGS`, so they can write the
  accessibility list themselves. A manual `adb install` must run the same post-install steps as
  `launcher/build-and-install.sh`: the grant, the `GuardRevival` notification listener
  (`cmd notification allow_listener`), the Home activity and the device-idle whitelist.
- The kids-mode add-on ships four recovery layers: a foreground keeper, a BOOT_COMPLETED and
  USER_UNLOCKED receiver, a persisted periodic job, and an add/remove/add write of
  `enabled_accessibility_services` that clears Android's crashed-service mark.
- The kids-mode guard row repairs the service itself when the permission is held; the Accessibility
  screen is only the fallback without the grant.

## Checks before calling it done

Run these on the real TV, not only the emulator; after each the guard is bound and the keeper runs
within seconds:

1. Remove the guard from `enabled_accessibility_services`; it comes back.
2. Force-stop the kids-mode add-on; the launcher restores the guard and the keeper.
3. Force-stop CleanYourTV; GuardRevival starts its process again and the guard stays.
4. Power-cycle the TV. Network debugging (5555) is off after a reboot on this set: re-enable it
   afterwards. If this check cannot be run, say so in the hand-off instead of claiming it.

Also confirm `cmd package resolve-activity` returns CleanYourTV for the HOME intent, the stock
launcher is not Home, and both packages are in the device-idle whitelist.

## Record the result

Name the TV, the firmware and the four recovery results in the commit or PR note. If the recovery
path or the stock launcher's state changes, update the project memory `project-launcher-first-class`
and the TV environment memory. The general rules are in `AGENTS.md`.
