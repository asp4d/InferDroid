package dev.inferdroid;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import dev.inferdroid.engine.EngineManager;
import dev.inferdroid.engine.GenerationRequest;
import dev.inferdroid.engine.GemmaInferenceEngine;

/** Private foreground owner of the retained model; no network server. */
public final class InferenceService extends Service implements EngineManager.Listener {
    private static final String CHANNEL = "inference";
    private static final int NOTIFICATION = 1;
    private static final String START = "dev.inferdroid.START_INFERENCE_SERVICE";
    private static final String STOP = "dev.inferdroid.STOP_INFERENCE_SERVICE";
    private static final String CANCEL = "dev.inferdroid.CANCEL_INFERENCE";
    private final LocalBinder binder = new LocalBinder();
    private EngineManager manager;
    private NotificationManager notifications;
    private boolean foreground;
    private boolean wanted;

    public final class LocalBinder extends Binder {
        public InferenceService getService() { return InferenceService.this; }
    }

    @Override public void onCreate() {
        super.onCreate();
        notifications = getSystemService(NotificationManager.class);
        NotificationChannel channel = new NotificationChannel(CHANNEL,
                getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW);
        channel.setDescription(getString(R.string.notification_channel_description));
        notifications.createNotificationChannel(channel);

        PowerManager.WakeLock wake = getSystemService(PowerManager.class)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "InferDroid:inference");
        wake.setReferenceCounted(false);
        manager = new EngineManager(new GemmaInferenceEngine(this), new EngineManager.WorkGuard() {
            @Override public void begin() { wake.acquire(10 * 60 * 1000L); }
            @Override public void end() { if (wake.isHeld()) wake.release(); }
        });
        manager.attach(this);
    }

    @Override public IBinder onBind(Intent intent) { return binder; }
    public EngineManager getManager() { return manager; }

    public boolean load(String model, boolean verbose) {
        if (manager.getState().busy) return false;
        ensureForeground();
        return manager.load(model, verbose);
    }

    public boolean generate(GenerationRequest request) {
        if (manager.getState().busy) return false;
        ensureForeground();
        return manager.generate(request);
    }

    public void cancel() { manager.cancel(); }

    public void unload() {
        manager.unload(() -> {
            wanted = false;
            foreground = false;
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
        });
    }

    private void ensureForeground() {
        wanted = true;
        try {
            startForegroundService(new Intent(this, InferenceService.class).setAction(START));
            promote();
        } catch (RuntimeException error) {
            wanted = false;
            throw error;
        }
    }

    private void promote() {
        Notification notification = notification(manager.getState());
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION, notification);
        }
        foreground = true;
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? "" : intent.getAction();
        if (STOP.equals(action)) unload();
        else if (CANCEL.equals(action)) cancel();
        else if (wanted) promote();
        else stopSelf(startId);
        return START_NOT_STICKY;
    }

    @Override public void onStateChanged(EngineManager.State state) {
        if (!foreground) return;
        if (state.phase == EngineManager.Phase.UNLOADED) {
            wanted = false;
            foreground = false;
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
        } else {
            notifications.notify(NOTIFICATION, notification(state));
        }
    }

    private Notification notification(EngineManager.State state) {
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher_monochrome)
                .setContentTitle(getString(R.string.notification_title))
                .setContentText(state.status)
                .setSubText(state.backend)
                .setContentIntent(open)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        if (state.canCancel) builder.addAction(new Notification.Action.Builder(
                null, getString(R.string.cancel), action(CANCEL, 1)).build());
        builder.addAction(new Notification.Action.Builder(
                null, getString(R.string.unload), action(STOP, 2)).build());
        return builder.build();
    }

    private PendingIntent action(String action, int requestCode) {
        return PendingIntent.getService(this, requestCode,
                new Intent(this, InferenceService.class).setAction(action),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    @Override public void onDestroy() {
        foreground = false;
        wanted = false;
        manager.detach(this);
        manager.close();
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }
}
