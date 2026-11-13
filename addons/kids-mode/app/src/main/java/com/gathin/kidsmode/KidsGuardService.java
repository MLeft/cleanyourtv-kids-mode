package com.gathin.kidsmode;

import android.accessibilityservice.AccessibilityService;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * The guard: an accessibility service that counts screen-on time while kids mode is on and draws a
 * full-screen accessibility overlay when a limit is reached. The overlay stays above the launcher and
 * every app, so Home cannot dismiss it. A parent PIN opens the override menu; a chosen duration becomes
 * a countdown that pauses while the TV is off and locks again when it runs out.
 */
public final class KidsGuardService extends AccessibilityService {
    private static final String TAG = "KidsGuard";
    private static final long TICK_MS = 1000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private WindowManager windowManager;
    private View overlay;
    private TextView lockTitle;
    private TextView lockMessage;
    private TextView lockCountdown;
    private TextView lockHint;
    private KidsPinView pinView;
    private LinearLayout overrideMenu;
    private static volatile boolean connected;
    private boolean screenOn;
    private int lockState = KidsStore.LOCK_NONE;
    private long lastTickElapsed;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!connected) return;
            long nowElapsed = SystemClock.elapsedRealtime();
            long dt = Math.min(5000L, Math.max(0L, nowElapsed - lastTickElapsed));
            lastTickElapsed = nowElapsed;
            long nowWall = System.currentTimeMillis();
            KidsStore.rolloverIfNeeded(KidsGuardService.this);

            boolean counting = KidsStore.isEnabled(KidsGuardService.this)
                    && screenOn && !KidsStore.settingsOpen
                    && overlay == null && lockState == KidsStore.LOCK_NONE
                    && !KidsStore.isUnmanagedNow(KidsGuardService.this, nowWall);
            if (counting) {
                KidsStore.addUsage(KidsGuardService.this, dt);
                if (KidsStore.overrideRemainingMs(KidsGuardService.this) > 0) {
                    KidsStore.consumeOverride(KidsGuardService.this, dt);
                }
            }

            int reason = KidsStore.lockReason(KidsGuardService.this, nowWall);
            if (reason != KidsStore.LOCK_NONE && screenOn && !KidsStore.settingsOpen) {
                showLock(reason, nowWall);
            } else {
                hideLock();
            }
            if (overlay != null) {
                updateLock(nowWall);
                keepFocus();
            }
            handler.postDelayed(this, TICK_MS);
        }
    };

    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            long nowWall = System.currentTimeMillis();
            if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                screenOn = false;
                KidsStore.onScreenOff(KidsGuardService.this, nowWall);
                hideLock();
            } else if (Intent.ACTION_SCREEN_ON.equals(action)) {
                screenOn = true;
                KidsStore.onScreenOn(KidsGuardService.this, nowWall);
                lastTickElapsed = SystemClock.elapsedRealtime();
                evaluate(nowWall);
            }
        }
    };

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        if (connected) return;
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        screenOn = pm != null && pm.isInteractive();
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(screenReceiver, filter);
        }
        connected = true;
        lastTickElapsed = SystemClock.elapsedRealtime();
        handler.removeCallbacks(tick);
        handler.post(tick);
        if (KidsStore.isEnabled(this)) KidsGuardKeeper.start(this);
    }

    static boolean isConnected() {
        return connected;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // No window content is read; the service exists for the overlay and the screen-on timer.
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public void onDestroy() {
        connected = false;
        handler.removeCallbacks(tick);
        try {
            unregisterReceiver(screenReceiver);
        } catch (Exception ignored) {
        }
        hideLock();
        super.onDestroy();
    }

    private void evaluate(long nowWall) {
        if (!connected) return;
        int reason = KidsStore.lockReason(this, nowWall);
        if (reason != KidsStore.LOCK_NONE && screenOn && !KidsStore.settingsOpen) {
            showLock(reason, nowWall);
        } else {
            hideLock();
        }
    }

    private void showLock(int reason, long nowWall) {
        if (windowManager == null) return;
        if (overlay == null) {
            // The lock is a page over the TV home, not over the video that was playing: send Home
            // first so the app goes to the background (and normally pauses), then draw the overlay.
            performGlobalAction(GLOBAL_ACTION_HOME);
            overlay = LayoutInflater.from(this).inflate(R.layout.view_kids_lock, null);
            lockTitle = overlay.findViewById(R.id.kidsLockTitle);
            lockMessage = overlay.findViewById(R.id.kidsLockMessage);
            lockCountdown = overlay.findViewById(R.id.kidsLockCountdown);
            lockHint = overlay.findViewById(R.id.kidsLockHint);
            pinView = overlay.findViewById(R.id.kidsPinView);
            overrideMenu = overlay.findViewById(R.id.kidsOverrideMenu);
            pinView.setTitle(getString(R.string.kids_pin_enter));
            pinView.setOnComplete(this::onPin);
            bindOverrideMenu();
            overlay.setOnKeyListener((v, keyCode, event) -> {
                if (event.getAction() == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_BACK) {
                    return true;
                }
                return false;
            });
            WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT);
            params.gravity = Gravity.TOP | Gravity.START;
            try {
                windowManager.addView(overlay, params);
            } catch (Exception e) {
                Log.w(TAG, "could not add the lock overlay", e);
                overlay = null;
                return;
            }
        }
        boolean changed = lockState != reason;
        lockState = reason;
        if (changed) {
            lockTitle.setText(reason == KidsStore.LOCK_DAILY
                    ? R.string.kids_lock_daily_title
                    : reason == KidsStore.LOCK_BLOCKED
                    ? R.string.kids_lock_blocked_title : R.string.kids_lock_session_title);
            lockTitle.setVisibility(View.VISIBLE);
            lockMessage.setVisibility(View.VISIBLE);
            lockCountdown.setVisibility(View.VISIBLE);
            lockHint.setVisibility(View.VISIBLE);
            pinView.setVisibility(View.VISIBLE);
            pinView.clear();
            overrideMenu.setVisibility(View.GONE);
            pinView.focusFirst();
        }
        updateLock(nowWall);
    }

    private void hideLock() {
        if (overlay != null && windowManager != null) {
            try {
                windowManager.removeView(overlay);
            } catch (Exception ignored) {
            }
        }
        overlay = null;
        lockTitle = null;
        lockMessage = null;
        lockCountdown = null;
        lockHint = null;
        pinView = null;
        overrideMenu = null;
        lockState = KidsStore.LOCK_NONE;
        lastTickElapsed = SystemClock.elapsedRealtime();
    }

    private void updateLock(long nowWall) {
        if (overlay == null) return;
        if (lockState == KidsStore.LOCK_SESSION) {
            long left = KidsStore.cooldownUntilWall(this) - nowWall;
            if (left <= 0) {
                KidsStore.clearSession(this);
                int reason = KidsStore.lockReason(this, nowWall);
                if (reason != KidsStore.LOCK_NONE) {
                    showLock(reason, nowWall);
                } else {
                    hideLock();
                }
                return;
            }
            String text = KidsStore.formatDuration(left);
            lockMessage.setText(getString(R.string.kids_lock_session_message, text));
            lockCountdown.setText(text);
        } else if (lockState == KidsStore.LOCK_DAILY) {
            long left = Math.max(0, KidsStore.midnightWall() - nowWall);
            String text = KidsStore.formatDuration(left);
            lockMessage.setText(getString(R.string.kids_lock_daily_message, text));
            lockCountdown.setText(text);
        } else if (lockState == KidsStore.LOCK_BLOCKED) {
            long left = KidsStore.blockedRemainingMs(this, nowWall);
            String text = KidsStore.formatDuration(left);
            lockMessage.setText(getString(R.string.kids_lock_blocked_message, text));
            lockCountdown.setText(text);
        }
    }

    private void keepFocus() {
        if (overlay == null || overlay.hasWindowFocus()) return;
        overlay.requestFocus();
        if (overrideMenu != null && overrideMenu.getVisibility() == View.VISIBLE) {
            View first = overlay.findViewById(R.id.kidsOverride10);
            if (first != null) first.requestFocus();
        } else if (pinView != null) {
            pinView.focusFirst();
        }
    }

    private void onPin(String pin) {
        if (pinView == null) return;
        int[] wait = new int[1];
        int result = KidsStore.checkPin(this, pin, wait);
        if (result == KidsStore.PIN_OK) {
            showOverrideMenu();
        } else if (result == KidsStore.PIN_LOCKED) {
            pinView.clear();
            pinView.showError(getString(R.string.kids_pin_locked, wait[0]));
            pinView.focusFirst();
        } else {
            pinView.clear();
            pinView.showError(getString(R.string.kids_error_wrong));
            pinView.focusFirst();
        }
    }

    private void showOverrideMenu() {
        if (pinView == null || overrideMenu == null) return;
        // The lock title belongs to the PIN page; over the menu it pushed "Parent verified" down.
        lockTitle.setVisibility(View.GONE);
        pinView.setVisibility(View.GONE);
        lockMessage.setVisibility(View.GONE);
        lockCountdown.setVisibility(View.GONE);
        lockHint.setVisibility(View.GONE);
        overrideMenu.setVisibility(View.VISIBLE);
        View first = overlay.findViewById(R.id.kidsOverride10);
        if (first != null) first.requestFocus();
    }

    private void bindOverrideMenu() {
        int[] ids = {R.id.kidsOverride10, R.id.kidsOverride15, R.id.kidsOverride20,
                R.id.kidsOverride30, R.id.kidsOverride60};
        for (int i = 0; i < ids.length; i++) {
            final int minutes = KidsStore.OVERRIDE_OPTIONS[i];
            TextView button = overlay.findViewById(ids[i]);
            button.setText(getString(R.string.kids_override_minutes, minutes));
            button.setOnClickListener(v -> {
                KidsStore.grantOverride(this, minutes);
                hideLock();
                Toast.makeText(this, getString(R.string.kids_overridden, minutes),
                        Toast.LENGTH_SHORT).show();
            });
        }
        overlay.findViewById(R.id.kidsStayLocked).setOnClickListener(v -> {
            if (pinView == null || overrideMenu == null) return;
            overrideMenu.setVisibility(View.GONE);
            lockTitle.setVisibility(View.VISIBLE);
            pinView.setVisibility(View.VISIBLE);
            lockMessage.setVisibility(View.VISIBLE);
            lockCountdown.setVisibility(View.VISIBLE);
            lockHint.setVisibility(View.VISIBLE);
            pinView.clear();
            pinView.focusFirst();
        });
        overlay.findViewById(R.id.kidsDisableMode).setOnClickListener(v -> {
            KidsStore.setEnabled(this, false);
            KidsGuardKeeper.stop(this);
            hideLock();
            Toast.makeText(this, getString(R.string.kids_disabled_toast), Toast.LENGTH_SHORT).show();
        });
    }
}
