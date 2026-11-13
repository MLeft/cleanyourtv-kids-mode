package com.gathin.kidsmode;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

/**
 * Keeps kids mode's process alive while the mode is on and puts the guard's accessibility service
 * back when the system drops it. MIUI force-stops third-party apps on a power cycle and takes
 * their accessibility services off the enabled list; this foreground service plus
 * {@link KidsBootReceiver} and {@link KidsGuardJob} is the add-on's own way back in, so it does
 * not depend on the launcher staying alive.
 */
public final class KidsGuardKeeper extends Service {
    private static final String CHANNEL = "kids_guard_keeper";
    private static final int NOTIFICATION_ID = 4401;
    private static final long TICK_MS = 5000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!KidsStore.isEnabled(KidsGuardKeeper.this)) {
                stopSelf();
                return;
            }
            if (!KidsStore.isAccessibilityEnabled(KidsGuardKeeper.this)
                    || !KidsGuardService.isConnected()) {
                KidsStore.ensureGuard(KidsGuardKeeper.this);
            }
            handler.postDelayed(this, TICK_MS);
        }
    };

    static void start(Context context) {
        Intent intent = new Intent(context, KidsGuardKeeper.class);
        if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent);
        else context.startService(intent);
    }

    static void stop(Context context) {
        context.stopService(new Intent(context, KidsGuardKeeper.class));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager != null && Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(CHANNEL,
                    getString(R.string.kids_keeper_channel), NotificationManager.IMPORTANCE_MIN);
            channel.setShowBadge(false);
            channel.setSound(null, null);
            channel.enableVibration(false);
            manager.createNotificationChannel(channel);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIFICATION_ID, notification());
        handler.removeCallbacks(tick);
        handler.post(tick);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(tick);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private Notification notification() {
        Intent open = new Intent(this, KidsModeActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pending = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
                .setContentTitle(getString(R.string.kids_keeper_title))
                .setContentText(getString(R.string.kids_keeper_text))
                .setOngoing(true)
                .setContentIntent(pending)
                .build();
    }
}
