package com.gathin.kidsmode;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

import java.util.function.Consumer;

/** Parent settings for kids mode: limits, the rest after a session, and the PIN. */
public final class KidsModeActivity extends Activity {
    private boolean settingsShown;
    private TextView enabledChoice;
    private TextView sessionChoice;
    private TextView waitChoice;
    private TextView dailyChoice;
    private TextView serviceChoice;
    private TextView usageText;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (KidsStore.hasPin(this)) {
            showPinGate();
        } else {
            showSettings();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        KidsStore.settingsOpen = true;
        if (settingsShown) refresh();
    }

    @Override
    protected void onPause() {
        KidsStore.settingsOpen = false;
        super.onPause();
    }

    private void showPinGate() {
        setContentView(R.layout.activity_kids_pin);
        KidsPinView gate = findViewById(R.id.kidsPinGate);
        gate.setTitle(getString(R.string.kids_pin_enter));
        gate.setOnComplete(pin -> {
            int[] wait = new int[1];
            int result = KidsStore.checkPin(this, pin, wait);
            if (result == KidsStore.PIN_OK) {
                showSettings();
            } else if (result == KidsStore.PIN_LOCKED) {
                gate.clear();
                gate.showError(getString(R.string.kids_pin_locked, wait[0]));
                gate.focusFirst();
            } else {
                gate.clear();
                gate.showError(getString(R.string.kids_error_wrong));
                gate.focusFirst();
            }
        });
    }

    private void showSettings() {
        setContentView(R.layout.activity_kids_mode);
        settingsShown = true;
        enabledChoice = findViewById(R.id.kidsEnabledChoice);
        sessionChoice = findViewById(R.id.sessionLimitChoice);
        waitChoice = findViewById(R.id.waitLimitChoice);
        dailyChoice = findViewById(R.id.dailyLimitChoice);
        serviceChoice = findViewById(R.id.serviceChoice);
        usageText = findViewById(R.id.kidsUsageText);

        enabledChoice.setOnClickListener(v -> toggleMode());
        sessionChoice.setOnClickListener(v -> showChoice(R.string.kids_session_limit,
                KidsStore.SESSION_OPTIONS, KidsStore.sessionLimitMin(this),
                value -> KidsStore.setSessionLimit(this, value)));
        waitChoice.setOnClickListener(v -> showChoice(R.string.kids_wait_limit,
                KidsStore.WAIT_OPTIONS, KidsStore.waitMin(this),
                value -> KidsStore.setWaitMin(this, value)));
        dailyChoice.setOnClickListener(v -> showChoice(R.string.kids_daily_limit,
                KidsStore.DAILY_OPTIONS, KidsStore.dailyLimitMin(this),
                value -> KidsStore.setDailyLimit(this, value)));
        findViewById(R.id.changePinChoice).setOnClickListener(v -> promptNewPin(null));
        findViewById(R.id.resetUsageChoice).setOnClickListener(v -> confirmReset());
        serviceChoice.setOnClickListener(v -> openAccessibility());

        refresh();
        enabledChoice.requestFocus();
    }

    private void toggleMode() {
        if (KidsStore.isEnabled(this)) {
            KidsStore.setEnabled(this, false);
            Toast.makeText(this, R.string.kids_disabled_toast, Toast.LENGTH_SHORT).show();
            refresh();
            return;
        }
        if (!KidsStore.hasPin(this)) {
            promptNewPin(this::toggleMode);
            return;
        }
        if (!KidsStore.isAccessibilityEnabled(this)) {
            AlertDialog dialog = new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                    .setTitle(R.string.kids_accessibility_title)
                    .setMessage(R.string.kids_accessibility_message)
                    .setPositiveButton(R.string.kids_open_accessibility,
                            (d, which) -> openAccessibility())
                    .setNegativeButton(android.R.string.cancel, null)
                    .create();
            showCentered(dialog, 560, null);
            return;
        }
        KidsStore.setEnabled(this, true);
        Toast.makeText(this, R.string.kids_enabled_toast, Toast.LENGTH_SHORT).show();
        refresh();
    }

    private void refresh() {
        if (enabledChoice == null) return;
        boolean hasPin = KidsStore.hasPin(this);
        boolean enabled = KidsStore.isEnabled(this);
        enabledChoice.setText(getString(R.string.kids_enabled_choice,
                getString(!hasPin ? R.string.kids_not_set
                        : enabled ? R.string.kids_on : R.string.kids_off)));

        int session = KidsStore.sessionLimitMin(this);
        int wait = KidsStore.waitMin(this);
        int daily = KidsStore.dailyLimitMin(this);
        sessionChoice.setText(getString(R.string.kids_session_limit_state, formatMinutes(session)));
        waitChoice.setText(getString(R.string.kids_wait_limit_state, formatMinutes(wait)));
        dailyChoice.setText(getString(R.string.kids_daily_limit_state, formatMinutes(daily)));
        waitChoice.setEnabled(session > 0);
        waitChoice.setAlpha(session > 0 ? 1f : 0.5f);

        serviceChoice.setText(getString(KidsStore.isAccessibilityEnabled(this)
                ? R.string.kids_service_on : R.string.kids_service_off));

        int used = KidsStore.usedTodayMinutes(this);
        int sessionUsed = KidsStore.sessionMinutes(this);
        if (daily > 0) {
            long leftMs = Math.max(0, daily * 60_000L - KidsStore.dailyUsedMs(this));
            String left = getString(R.string.kids_minutes, (int) (leftMs / 60_000L));
            usageText.setText(getString(R.string.kids_usage, used, left, sessionUsed));
        } else {
            usageText.setText(getString(R.string.kids_usage_unlimited, used, sessionUsed));
        }
    }

    private String formatMinutes(int minutes) {
        return minutes <= 0 ? getString(R.string.kids_unlimited)
                : getString(R.string.kids_minutes, minutes);
    }

    private void showChoice(int title, int[] options, int current, Consumer<Integer> onPick) {
        String[] labels = new String[options.length];
        int checked = 0;
        for (int i = 0; i < options.length; i++) {
            labels[i] = options[i] <= 0 ? getString(R.string.kids_unlimited)
                    : getString(R.string.kids_minutes, options[i]);
            if (options[i] == current) checked = i;
        }
        AlertDialog dialog = new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle(title)
                .setSingleChoiceItems(labels, checked, (d, which) -> {
                    d.dismiss();
                    onPick.accept(options[which]);
                    refresh();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        showCentered(dialog, 560, null);
    }

    private void confirmReset() {
        AlertDialog dialog = new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle(R.string.kids_reset_confirm_title)
                .setMessage(R.string.kids_reset_confirm_message)
                .setPositiveButton(R.string.kids_ok, (d, which) -> {
                    KidsStore.resetUsage(this);
                    Toast.makeText(this, R.string.kids_reset_done, Toast.LENGTH_SHORT).show();
                    refresh();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        showCentered(dialog, 560, null);
    }

    /** Asks for a new PIN twice; after is run only when both entries match. */
    private void promptNewPin(Runnable after) {
        showPinDialog(R.string.kids_pin_set_title, first ->
                showPinDialog(R.string.kids_pin_confirm_title, second -> {
                    if (!first.equals(second)) {
                        Toast.makeText(this, R.string.kids_pin_mismatch, Toast.LENGTH_SHORT).show();
                        promptNewPin(after);
                        return;
                    }
                    KidsStore.setPin(this, first);
                    Toast.makeText(this, R.string.kids_pin_saved, Toast.LENGTH_SHORT).show();
                    refresh();
                    if (after != null) after.run();
                }));
    }

    private void showPinDialog(int title, Consumer<String> onDone) {
        KidsPinView pin = new KidsPinView(this);
        pin.setTitle(getString(title));
        AlertDialog dialog = new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setView(pin)
                .setCancelable(true)
                .create();
        pin.setOnComplete(value -> {
            dialog.dismiss();
            onDone.accept(value);
        });
        showCentered(dialog, 440, pin::focusFirst);
    }

    /** Dialogs on a fullscreen TV theme otherwise sit at the top-left; pin them to the middle. */
    private void showCentered(AlertDialog dialog, int widthDp, Runnable afterShow) {
        dialog.setOnShowListener(d -> {
            Window window = dialog.getWindow();
            if (window != null) {
                window.setGravity(Gravity.CENTER);
                WindowManager.LayoutParams params = window.getAttributes();
                params.width = Math.round(widthDp * getResources().getDisplayMetrics().density);
                params.height = WindowManager.LayoutParams.WRAP_CONTENT;
                window.setAttributes(params);
            }
            if (afterShow != null) afterShow.run();
        });
        dialog.show();
    }

    private void openAccessibility() {
        try {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, R.string.kids_accessibility_message, Toast.LENGTH_LONG).show();
        }
    }
}
