package com.gathin.kidsmode;

import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Locale;

/**
 * Kids mode state: a session limit, the rest that follows it, a daily total, and a parent PIN.
 * Everything is stored in one SharedPreferences file; the accessibility guard reads it every tick,
 * so a change made in the settings screen takes effect within a second.
 */
final class KidsStore {
    static final String PREFS = "kids_mode";
    static final int DEFAULT_SESSION_MIN = 30;
    static final int DEFAULT_WAIT_MIN = 20;
    static final int DEFAULT_DAILY_MIN = 90;
    static final int[] SESSION_OPTIONS = {0, 15, 20, 30, 45, 60, 90};
    static final int[] WAIT_OPTIONS = {5, 10, 15, 20, 30, 45, 60};
    static final int[] DAILY_OPTIONS = {0, 30, 60, 90, 120, 180, 240};
    static final int[] OVERRIDE_OPTIONS = {10, 15, 20, 30, 60};
    static final int DEFAULT_LIBRARY_GRACE_MIN = 5;
    static final int[] LIBRARY_GRACE_OPTIONS = {0, 1, 5, 10, 15};

    static final int LOCK_NONE = 0;
    static final int LOCK_SESSION = 1;
    static final int LOCK_DAILY = 2;

    static final int PIN_OK = 0;
    static final int PIN_WRONG = 1;
    static final int PIN_LOCKED = 2;

    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_SESSION_MIN = "session_min";
    private static final String KEY_WAIT_MIN = "wait_min";
    private static final String KEY_DAILY_MIN = "daily_min";
    private static final String KEY_PIN_SALT = "pin_salt";
    private static final String KEY_PIN_HASH = "pin_hash";
    private static final String KEY_FAIL_COUNT = "fail_count";
    private static final String KEY_FAIL_UNTIL = "fail_until";
    private static final String KEY_DAY = "day";
    private static final String KEY_USED_TODAY = "used_today_ms";
    private static final String KEY_SESSION_USED = "session_used_ms";
    private static final String KEY_LAST_OFF = "last_off_wall";
    private static final String KEY_COOLDOWN_UNTIL = "cooldown_until_wall";
    private static final String KEY_OVERRIDE_REMAINING = "override_remaining_ms";
    private static final String KEY_LIBRARY_GRACE_MIN = "library_grace_min";
    private static final String KEY_LIBRARY_GRACE_UNTIL = "library_grace_until";

    /** Set while the settings screen is in front; the guard neither counts nor locks then. */
    static volatile boolean settingsOpen;

    private KidsStore() {
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static boolean hasPin(Context c) {
        SharedPreferences p = prefs(c);
        return !TextUtils.isEmpty(p.getString(KEY_PIN_SALT, null))
                && !TextUtils.isEmpty(p.getString(KEY_PIN_HASH, null));
    }

    static boolean isEnabled(Context c) {
        return hasPin(c) && prefs(c).getBoolean(KEY_ENABLED, false);
    }

    static void setEnabled(Context c, boolean enabled) {
        SharedPreferences.Editor editor = prefs(c).edit().putBoolean(KEY_ENABLED, enabled);
        if (!enabled) editor.putLong(KEY_LIBRARY_GRACE_UNTIL, 0);
        editor.apply();
    }

    static int sessionLimitMin(Context c) {
        return Math.max(0, prefs(c).getInt(KEY_SESSION_MIN, DEFAULT_SESSION_MIN));
    }

    static int waitMin(Context c) {
        return Math.max(1, prefs(c).getInt(KEY_WAIT_MIN, DEFAULT_WAIT_MIN));
    }

    static int dailyLimitMin(Context c) {
        return Math.max(0, prefs(c).getInt(KEY_DAILY_MIN, DEFAULT_DAILY_MIN));
    }

    static void setSessionLimit(Context c, int minutes) {
        prefs(c).edit().putInt(KEY_SESSION_MIN, Math.max(0, minutes)).apply();
    }

    static void setWaitMin(Context c, int minutes) {
        prefs(c).edit().putInt(KEY_WAIT_MIN, Math.max(1, minutes)).apply();
    }

    static void setDailyLimit(Context c, int minutes) {
        prefs(c).edit().putInt(KEY_DAILY_MIN, Math.max(0, minutes)).apply();
    }

    static int libraryGraceMin(Context c) {
        return Math.max(0, prefs(c).getInt(KEY_LIBRARY_GRACE_MIN, DEFAULT_LIBRARY_GRACE_MIN));
    }

    static void setLibraryGraceMin(Context c, int minutes) {
        prefs(c).edit().putInt(KEY_LIBRARY_GRACE_MIN, Math.max(0, minutes)).apply();
    }

    static boolean isLibraryGraceActive(Context c) {
        return isEnabled(c) && System.currentTimeMillis() < prefs(c).getLong(KEY_LIBRARY_GRACE_UNTIL, 0);
    }

    static void grantLibraryGrace(Context c) {
        int minutes = libraryGraceMin(c);
        prefs(c).edit().putLong(KEY_LIBRARY_GRACE_UNTIL,
                minutes > 0 ? System.currentTimeMillis() + minutes * 60_000L : 0).apply();
    }

    static void clearLibraryGrace(Context c) {
        prefs(c).edit().putLong(KEY_LIBRARY_GRACE_UNTIL, 0).apply();
    }

    static void setPin(Context c, String pin) {
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        String saltText = Base64.encodeToString(salt, Base64.NO_WRAP);
        prefs(c).edit()
                .putString(KEY_PIN_SALT, saltText)
                .putString(KEY_PIN_HASH, hash(salt, pin))
                .putInt(KEY_FAIL_COUNT, 0)
                .putLong(KEY_FAIL_UNTIL, 0)
                .putLong(KEY_LIBRARY_GRACE_UNTIL, 0)
                .apply();
    }

    private static String hash(byte[] salt, String pin) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(salt);
            md.update((byte) ':');
            md.update(pin.getBytes(StandardCharsets.UTF_8));
            byte[] out = md.digest();
            StringBuilder sb = new StringBuilder(out.length * 2);
            for (byte b : out) sb.append(String.format(Locale.ROOT, "%02x", b & 0xff));
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    /** remainingSeconds receives the lockout when PIN_LOCKED is returned. */
    static int checkPin(Context c, String pin, int[] remainingSeconds) {
        if (!hasPin(c)) return PIN_WRONG;
        long now = System.currentTimeMillis();
        SharedPreferences p = prefs(c);
        long failUntil = p.getLong(KEY_FAIL_UNTIL, 0);
        if (failUntil > now) {
            if (remainingSeconds != null) {
                remainingSeconds[0] = (int) ((failUntil - now + 999) / 1000);
            }
            return PIN_LOCKED;
        }
        byte[] salt = Base64.decode(p.getString(KEY_PIN_SALT, ""), Base64.NO_WRAP);
        String expected = p.getString(KEY_PIN_HASH, "");
        String actual = hash(salt, pin);
        if (MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8))) {
            clearPinFailures(c);
            return PIN_OK;
        }
        int fails = p.getInt(KEY_FAIL_COUNT, 0) + 1;
        if (fails >= 5) {
            p.edit().putInt(KEY_FAIL_COUNT, 0).putLong(KEY_FAIL_UNTIL, now + 60_000L).apply();
            if (remainingSeconds != null) remainingSeconds[0] = 60;
            return PIN_LOCKED;
        }
        p.edit().putInt(KEY_FAIL_COUNT, fails).apply();
        return PIN_WRONG;
    }

    static int pinWaitSeconds(Context c) {
        long left = prefs(c).getLong(KEY_FAIL_UNTIL, 0) - System.currentTimeMillis();
        return left > 0 ? (int) ((left + 999) / 1000) : 0;
    }

    static void clearPinFailures(Context c) {
        prefs(c).edit().putInt(KEY_FAIL_COUNT, 0).putLong(KEY_FAIL_UNTIL, 0).apply();
    }

    private static String today() {
        return LocalDate.now().toString();
    }

    /** A new day clears every limit and lock; it runs from the guard tick and from the settings reads. */
    static void rolloverIfNeeded(Context c) {
        SharedPreferences p = prefs(c);
        String today = today();
        if (!today.equals(p.getString(KEY_DAY, null))) {
            p.edit().putString(KEY_DAY, today)
                    .putLong(KEY_USED_TODAY, 0)
                    .putLong(KEY_SESSION_USED, 0)
                    .putLong(KEY_LAST_OFF, 0)
                    .putLong(KEY_COOLDOWN_UNTIL, 0)
                    .putLong(KEY_OVERRIDE_REMAINING, 0)
                    .apply();
        }
    }

    static long dailyUsedMs(Context c) {
        rolloverIfNeeded(c);
        return Math.max(0, prefs(c).getLong(KEY_USED_TODAY, 0));
    }

    static long sessionUsedMs(Context c) {
        rolloverIfNeeded(c);
        return Math.max(0, prefs(c).getLong(KEY_SESSION_USED, 0));
    }

    static long overrideRemainingMs(Context c) {
        return Math.max(0, prefs(c).getLong(KEY_OVERRIDE_REMAINING, 0));
    }

    static long cooldownUntilWall(Context c) {
        return prefs(c).getLong(KEY_COOLDOWN_UNTIL, 0);
    }

    /** Adds screen-on time to both counters. Called once a second while the guard is counting. */
    static void addUsage(Context c, long ms) {
        if (ms <= 0) return;
        rolloverIfNeeded(c);
        SharedPreferences p = prefs(c);
        p.edit()
                .putLong(KEY_USED_TODAY, p.getLong(KEY_USED_TODAY, 0) + ms)
                .putLong(KEY_SESSION_USED, p.getLong(KEY_SESSION_USED, 0) + ms)
                .apply();
    }

    static void consumeOverride(Context c, long ms) {
        SharedPreferences p = prefs(c);
        long left = p.getLong(KEY_OVERRIDE_REMAINING, 0);
        if (left <= 0) return;
        p.edit().putLong(KEY_OVERRIDE_REMAINING, Math.max(0, left - ms)).apply();
    }

    /** A parent-granted extra viewing budget; counts down only while the screen is on. */
    static void grantOverride(Context c, int minutes) {
        grantOverrideMs(c, Math.max(0, minutes) * 60_000L);
    }

    /** Same budget in milliseconds, for the shell-only test entry. */
    static void grantOverrideMs(Context c, long ms) {
        clearPinFailures(c);
        prefs(c).edit()
                .putLong(KEY_OVERRIDE_REMAINING, Math.max(0, ms))
                .putLong(KEY_COOLDOWN_UNTIL, 0)
                .apply();
    }

    static void onScreenOn(Context c, long nowWall) {
        rolloverIfNeeded(c);
        SharedPreferences p = prefs(c);
        long lastOff = p.getLong(KEY_LAST_OFF, 0);
        if (lastOff > 0 && nowWall > lastOff
                && nowWall - lastOff >= waitMin(c) * 60_000L) {
            p.edit().putLong(KEY_SESSION_USED, 0).putLong(KEY_COOLDOWN_UNTIL, 0).apply();
        }
        p.edit().putLong(KEY_LAST_OFF, 0).apply();
    }

    static void onScreenOff(Context c, long nowWall) {
        prefs(c).edit().putLong(KEY_LAST_OFF, nowWall).apply();
    }

    static void resetUsage(Context c) {
        prefs(c).edit()
                .putLong(KEY_USED_TODAY, 0)
                .putLong(KEY_SESSION_USED, 0)
                .putLong(KEY_LAST_OFF, 0)
                .putLong(KEY_COOLDOWN_UNTIL, 0)
                .putLong(KEY_OVERRIDE_REMAINING, 0)
                .apply();
    }

    /** Removes the PIN and turns kids mode off, back to the defaults, for a clean hand-over. */
    static void clearPin(Context c) {
        prefs(c).edit()
                .remove(KEY_PIN_SALT)
                .remove(KEY_PIN_HASH)
                .putBoolean(KEY_ENABLED, false)
                .putInt(KEY_SESSION_MIN, DEFAULT_SESSION_MIN)
                .putInt(KEY_WAIT_MIN, DEFAULT_WAIT_MIN)
                .putInt(KEY_DAILY_MIN, DEFAULT_DAILY_MIN)
                .putInt(KEY_LIBRARY_GRACE_MIN, DEFAULT_LIBRARY_GRACE_MIN)
                .putInt(KEY_FAIL_COUNT, 0)
                .putLong(KEY_FAIL_UNTIL, 0)
                .putLong(KEY_LIBRARY_GRACE_UNTIL, 0)
                .putLong(KEY_USED_TODAY, 0)
                .putLong(KEY_SESSION_USED, 0)
                .putLong(KEY_LAST_OFF, 0)
                .putLong(KEY_COOLDOWN_UNTIL, 0)
                .putLong(KEY_OVERRIDE_REMAINING, 0)
                .apply();
    }

    static void clearSession(Context c) {
        prefs(c).edit().putLong(KEY_SESSION_USED, 0).putLong(KEY_COOLDOWN_UNTIL, 0).apply();
    }

    /**
     * What should be locked right now. Sets the rest deadline when the session limit is first hit;
     * once the deadline passes the session starts over.
     */
    static int lockReason(Context c, long nowWall) {
        rolloverIfNeeded(c);
        if (!isEnabled(c)) return LOCK_NONE;
        SharedPreferences p = prefs(c);
        if (p.getLong(KEY_OVERRIDE_REMAINING, 0) > 0) return LOCK_NONE;

        int daily = dailyLimitMin(c);
        if (daily > 0 && p.getLong(KEY_USED_TODAY, 0) >= daily * 60_000L) {
            return LOCK_DAILY;
        }

        int session = sessionLimitMin(c);
        if (session > 0 && p.getLong(KEY_SESSION_USED, 0) >= session * 60_000L) {
            long cooldown = p.getLong(KEY_COOLDOWN_UNTIL, 0);
            if (cooldown == 0) {
                cooldown = nowWall + waitMin(c) * 60_000L;
                p.edit().putLong(KEY_COOLDOWN_UNTIL, cooldown).apply();
            }
            if (nowWall < cooldown) return LOCK_SESSION;
            p.edit().putLong(KEY_SESSION_USED, 0).putLong(KEY_COOLDOWN_UNTIL, 0).apply();
        }
        return LOCK_NONE;
    }

    static long midnightWall() {
        return LocalDate.now().plusDays(1).atStartOfDay(ZoneId.systemDefault())
                .toInstant().toEpochMilli();
    }

    static String formatDuration(long ms) {
        long total = Math.max(0, ms) / 1000;
        long h = total / 3600;
        long m = (total % 3600) / 60;
        long s = total % 60;
        if (h > 0) return String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s);
        return String.format(Locale.ROOT, "%02d:%02d", m, s);
    }

    static int usedTodayMinutes(Context c) {
        return (int) (dailyUsedMs(c) / 60_000L);
    }

    static int sessionMinutes(Context c) {
        return (int) (sessionUsedMs(c) / 60_000L);
    }

    static boolean isAccessibilityEnabled(Context c) {
        if (Settings.Secure.getInt(c.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, 0) != 1) {
            return false;
        }
        String enabled = Settings.Secure.getString(c.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (enabled == null) return false;
        ComponentName component = new ComponentName(c, KidsGuardService.class);
        return enabled.contains(component.flattenToString())
                || enabled.contains(component.flattenToShortString());
    }

    /** The line shown in the launcher's top-bar entry and at the top of the settings screen. */
    static String summary(Context c) {
        if (!hasPin(c)) {
            return c.getString(R.string.kids_enabled_choice, c.getString(R.string.kids_not_set));
        }
        if (!isEnabled(c)) {
            return c.getString(R.string.kids_enabled_choice, c.getString(R.string.kids_off));
        }
        int reason = lockReason(c, System.currentTimeMillis());
        if (reason == LOCK_DAILY) {
            return c.getString(R.string.kids_enabled_choice, c.getString(R.string.kids_daily_used_up));
        }
        if (reason == LOCK_SESSION) {
            return c.getString(R.string.kids_enabled_choice, c.getString(R.string.kids_wait_short));
        }
        int daily = dailyLimitMin(c);
        if (daily > 0) {
            int left = Math.max(0, daily - usedTodayMinutes(c));
            return c.getString(R.string.kids_enabled_choice, c.getString(R.string.kids_usage_short, left));
        }
        return c.getString(R.string.kids_enabled_choice, c.getString(R.string.kids_on));
    }
}
