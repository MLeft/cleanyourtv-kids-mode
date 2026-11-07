package com.gathin.kidsmode;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Shell-only setup and test entry, protected by WRITE_SECURE_SETTINGS in the manifest.
 * Example:
 *   adb shell am broadcast -a com.gathin.kidsmode.ADMIN -n com.gathin.kidsmode/.KidsAdminReceiver \
 *       --ez enabled true --ei session_min 1 --ei wait_min 1 --ei daily_min 5 --es pin 1234
 */
public final class KidsAdminReceiver extends BroadcastReceiver {
    private static final String TAG = "KidsAdmin";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!"com.gathin.kidsmode.ADMIN".equals(intent.getAction())) return;
        KidsStore.rolloverIfNeeded(context);

        if (intent.getBooleanExtra("clear_pin", false)) {
            KidsStore.clearPin(context);
        }
        if (intent.hasExtra("pin")) {
            String pin = intent.getStringExtra("pin");
            if (pin != null && pin.matches("\\d{4}")) KidsStore.setPin(context, pin);
        }
        if (intent.hasExtra("session_min")) {
            KidsStore.setSessionLimit(context, intent.getIntExtra("session_min", 30));
        }
        if (intent.hasExtra("wait_min")) {
            KidsStore.setWaitMin(context, intent.getIntExtra("wait_min", 20));
        }
        if (intent.hasExtra("daily_min")) {
            KidsStore.setDailyLimit(context, intent.getIntExtra("daily_min", 90));
        }
        if (intent.getBooleanExtra("reset", false)) {
            KidsStore.resetUsage(context);
        }
        if (intent.hasExtra("enabled")) {
            KidsStore.setEnabled(context, intent.getBooleanExtra("enabled", false));
        }
        if (intent.hasExtra("override_minutes")) {
            KidsStore.grantOverride(context, intent.getIntExtra("override_minutes", 10));
        }
        if (intent.hasExtra("override_seconds")) {
            KidsStore.grantOverrideMs(context, intent.getIntExtra("override_seconds", 10) * 1000L);
        }
        if (intent.getBooleanExtra("library_grace", false)) {
            KidsStore.grantLibraryGrace(context);
        }
        if (intent.hasExtra("unmanaged_start_min")) {
            KidsStore.setUnmanagedStart(context, intent.getIntExtra("unmanaged_start_min", -1));
        }
        if (intent.hasExtra("unmanaged_end_min")) {
            KidsStore.setUnmanagedEnd(context, intent.getIntExtra("unmanaged_end_min", -1));
        }
        if (intent.getBooleanExtra("open", false)) {
            Intent open = new Intent(context, KidsModeActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(open);
        }
        Log.i(TAG, "admin: " + intent.getExtras());
    }
}
