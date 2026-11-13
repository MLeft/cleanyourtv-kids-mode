package com.gathin.kidsmode;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * After a power cycle MIUI force-stops third-party apps and drops their accessibility services.
 * When kids mode was on, this puts the keeper, the job and the guard back; USER_UNLOCKED covers a
 * TV that finishes booting before the user unlocks it.
 */
public final class KidsBootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_USER_UNLOCKED.equals(action)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) return;
        if (!KidsStore.hasPin(context) || !KidsStore.isEnabled(context)) return;
        KidsGuardJob.schedule(context);
        KidsGuardKeeper.start(context);
        KidsStore.ensureGuard(context);
    }
}
