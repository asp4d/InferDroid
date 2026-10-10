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
import android.os.Handler;
import android.os.Looper;
import android.net.Uri;
import dev.inferdroid.engine.EngineManager;
import dev.inferdroid.engine.GenerationRequest;
import dev.inferdroid.engine.GenerationListener;
import dev.inferdroid.engine.GemmaInferenceEngine;
import dev.inferdroid.engine.WorkGate;
import dev.inferdroid.speech.SpeechManager;
import dev.inferdroid.speech.SpeechModelStore;
import dev.inferdroid.speech.SherpaSpeechEngine;
import dev.inferdroid.speech.TranscriptionRequest;
import dev.inferdroid.speech.TranscriptionListener;
import dev.inferdroid.server.TranscriptionGateway;
import dev.inferdroid.server.SynthesisGateway;
import dev.inferdroid.tts.TtsManager;
import dev.inferdroid.tts.TtsModelStore;
import dev.inferdroid.tts.SherpaTtsEngine;
import dev.inferdroid.tts.SynthesisRequest;
import dev.inferdroid.tts.SynthesisListener;
import dev.inferdroid.server.ChatGateway;
import dev.inferdroid.server.OpenAiServer;
import dev.inferdroid.server.ServerConfig;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/** Private foreground owner of the retained model and optional loopback server. */
public final class InferenceService extends Service implements EngineManager.Listener, SpeechManager.Listener, TtsManager.Listener {
    public interface ServerListener { void onServerChanged(boolean running, String status); }
    private static final String CHANNEL = "inference";
    private static final int NOTIFICATION = 1;
    private static final String START = "dev.inferdroid.START_INFERENCE_SERVICE";
    private static final String STOP = "dev.inferdroid.STOP_INFERENCE_SERVICE";
    private static final String CANCEL = "dev.inferdroid.CANCEL_INFERENCE";
    private final LocalBinder binder = new LocalBinder();
    private EngineManager manager;
    private SpeechManager speech;
    private SpeechModelStore speechModels;
    private TtsManager tts;
    private TtsModelStore ttsModels;
    private final WorkGate gate = new WorkGate();
    private NotificationManager notifications;
    private boolean foreground;
    private boolean wanted;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Set<ServerListener> serverListeners = new LinkedHashSet<>();
    private OpenAiServer server;
    private String serverStatus = "Local API stopped.";
    private boolean shuttingDown;

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

        manager = new EngineManager(new GemmaInferenceEngine(this), workGuard("InferDroid:inference"), gate);
        speechModels = new SpeechModelStore(this);
        speech = new SpeechManager(new SherpaSpeechEngine(speechModels), workGuard("InferDroid:speech"), gate);
        ttsModels = new TtsModelStore(this);
        tts = new TtsManager(new SherpaTtsEngine(ttsModels), workGuard("InferDroid:tts"), gate);
        tts.attach(this);
        speech.attach(this);
        manager.attach(this);
    }

    private EngineManager.WorkGuard workGuard(String tag) {
        PowerManager.WakeLock wake = getSystemService(PowerManager.class)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, tag);
        wake.setReferenceCounted(false);
        return new EngineManager.WorkGuard() {
            @Override public void begin() { wake.acquire(10 * 60 * 1000L); }
            @Override public void end() { if (wake.isHeld()) wake.release(); }
        };
    }

    @Override public IBinder onBind(Intent intent) { return binder; }
    public EngineManager getManager() { return manager; }
    public SpeechManager getSpeechManager() { return speech; }
    public boolean hasSpeechModel() { return speechModels.isAvailable(); }
    public TtsManager getTtsManager() { return tts; }
    public boolean hasTtsModel() { return ttsModels.isAvailable(); }
    public boolean isBusy() { return gate.isBusy() || manager.getState().busy || speech.getState().busy || tts.getState().busy; }

    public boolean isServerRunning() { return server != null && server.isRunning(); }
    public void attachServer(ServerListener listener) {
        serverListeners.add(listener);
        listener.onServerChanged(isServerRunning(), serverStatus);
    }
    public void detachServer(ServerListener listener) { serverListeners.remove(listener); }

    public void startServer(ServerConfig config, String modelSource, boolean verbose) throws IOException {
        if (isServerRunning()) return;
        if (checkSelfPermission(android.Manifest.permission.INTERNET) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("Network permission is off. In Android settings, open InferDroid → Permissions → Network and allow it; localhost sockets require it too.");
        }
        if (shuttingDown || manager.getState().phase == EngineManager.Phase.STOPPING) {
            throw new IllegalStateException("Wait for the service to finish stopping.");
        }
        ensureForeground();
        OpenAiServer candidate = new OpenAiServer(config, modelSource, verbose, new ChatGateway() {
            @Override public boolean generate(GenerationRequest request, GenerationListener listener) throws Exception {
                FutureTask<Boolean> admission = new FutureTask<>(() ->
                        !shuttingDown && isServerRunning() && manager.generate(request, listener));
                main.post(admission);
                try {
                    return admission.get(5, TimeUnit.SECONDS);
                } catch (Exception error) {
                    admission.cancel(false);
                    cancel(listener);
                    throw error;
                }
            }
            @Override public void cancel(GenerationListener listener) {
                main.post(() -> manager.cancel(listener));
            }
        }, new TranscriptionGateway() {
            @Override public boolean isAvailable() { return speechModels.isAvailable(); }
            @Override public boolean transcribe(TranscriptionRequest request, TranscriptionListener listener) throws Exception {
                FutureTask<Boolean> admission = new FutureTask<>(() ->
                        !shuttingDown && isServerRunning() && speech.transcribe(request, listener));
                main.post(admission);
                try { return admission.get(5, TimeUnit.SECONDS); }
                catch (Exception error) { admission.cancel(false); cancel(listener); throw error; }
            }
            @Override public void cancel(TranscriptionListener listener) { main.post(() -> speech.cancel(listener)); }
        }, new SynthesisGateway() {
            @Override public boolean isAvailable() { return ttsModels.isAvailable(); }
            @Override public boolean synthesize(SynthesisRequest request, SynthesisListener listener) throws Exception {
                FutureTask<Boolean> admission = new FutureTask<>(() ->
                        !shuttingDown && isServerRunning() && tts.synthesize(request, listener));
                main.post(admission);
                try { return admission.get(5, TimeUnit.SECONDS); }
                catch (Exception error) { admission.cancel(false); cancel(listener); throw error; }
            }
            @Override public void cancel(SynthesisListener owner) { main.post(() -> tts.cancel(owner)); }
        }, () -> main.post(() -> {
            // A stopped older server must not close a newly started listener.
            if (server != null && !server.isRunning()) {
                server = null;
                serverStatus = "Local API stopped unexpectedly. Start it again from the app.";
                publishServer();
                stopIfIdle();
            }
        }));
        try {
            candidate.start();
            server = candidate;
            serverStatus = "Listening at http://127.0.0.1:" + candidate.getPort() + "/v1 · "
                    + (config.requireKey ? "API key required" : "API key disabled")
                    + (config.cors ? " · CORS enabled" : "");
            publishServer();
        } catch (IOException | RuntimeException error) {
            candidate.close();
            serverStatus = "Cannot start local API on port " + config.port + ": " + error.getMessage();
            publishServer();
            stopIfIdle();
            throw error;
        }
    }

    public void stopServer() {
        closeServer();
        publishServer();
        stopIfIdle();
    }

    private void closeServer() {
        if (server != null) server.close();
        server = null;
        serverStatus = "Local API stopped.";
    }

    private void publishServer() {
        for (ServerListener listener : new ArrayList<>(serverListeners)) listener.onServerChanged(isServerRunning(), serverStatus);
        if (foreground) notifications.notify(NOTIFICATION, notification(manager.getState()));
    }

    private void stopIfIdle() {
        if (!isServerRunning() && manager.getState().phase == EngineManager.Phase.UNLOADED
                && speech.getState().phase == SpeechManager.Phase.UNLOADED
                && tts.getState().phase == TtsManager.Phase.UNLOADED) {
            wanted = false;
            foreground = false;
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
        }
    }

    public boolean load(String model, boolean verbose) {
        if (shuttingDown || isBusy()) return false;
        ensureForeground();
        return manager.load(model, verbose);
    }

    public boolean generate(GenerationRequest request) {
        if (shuttingDown || isBusy()) return false;
        ensureForeground();
        return manager.generate(request);
    }

    public boolean importSpeechModel(Uri folder) {
        if (shuttingDown || isBusy() || isServerRunning()) return false;
        ensureForeground();
        return speech.importModel(token -> speechModels.importTree(folder, token));
    }
    public boolean loadSpeech() {
        if (shuttingDown || isBusy()) return false;
        ensureForeground();
        return speech.load();
    }
    public boolean transcribe(TranscriptionRequest request) {
        if (shuttingDown || isBusy()) return false;
        ensureForeground();
        return speech.transcribe(request, null);
    }
    public void unloadSpeech() { speech.unload(this::stopIfIdle); }
    public boolean importTtsModel(Uri folder) {
        if (shuttingDown || isBusy() || isServerRunning()) return false;
        ensureForeground();
        return tts.importModel(token -> ttsModels.importTree(folder, token));
    }
    public boolean loadTts() {
        if (shuttingDown || isBusy()) return false;
        ensureForeground();
        return tts.load();
    }
    public boolean synthesize(SynthesisRequest request) {
        if (shuttingDown || isBusy()) return false;
        ensureForeground();
        return tts.synthesize(request, null);
    }
    public void unloadTts() { tts.unload(this::stopIfIdle); }
    public void cancel() { manager.cancel(); speech.cancel(); tts.cancel(); }

    public void unload() {
        if (shuttingDown) return;
        shuttingDown = true;
        closeServer();
        publishServer();
        int[] remaining = {3};
        Runnable stopped = () -> {
            if (--remaining[0] != 0) return;
            wanted = false;
            foreground = false;
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            shuttingDown = false;
        };
        manager.unload(stopped);
        speech.unload(stopped);
        tts.unload(stopped);
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
        if (state.phase == EngineManager.Phase.UNLOADED && !isServerRunning()
                && speech.getState().phase == SpeechManager.Phase.UNLOADED
                && tts.getState().phase == TtsManager.Phase.UNLOADED) {
            stopIfIdle();
        } else {
            notifications.notify(NOTIFICATION, notification(state));
        }
    }

    @Override public void onSpeechChanged(SpeechManager.State state) {
        if (!foreground) return;
        stopIfIdle();
        if (foreground) notifications.notify(NOTIFICATION, notification(manager.getState()));
    }

    @Override public void onTtsChanged(TtsManager.State state) {
        if (!foreground) return;
        stopIfIdle();
        if (foreground) notifications.notify(NOTIFICATION, notification(manager.getState()));
    }

    private Notification notification(EngineManager.State state) {
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        SpeechManager.State audio = speech.getState();
        TtsManager.State synthesis = tts.getState();
        String details = state.status + "\n" + audio.status + "\n" + synthesis.status;
        boolean showTts = synthesis.busy || synthesis.loaded && !state.loaded && !audio.loaded;
        String current = showTts ? synthesis.status : audio.busy || audio.loaded && !state.loaded ? audio.status : state.status;
        Notification.Builder builder = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher_monochrome)
                .setContentTitle(getString(R.string.notification_title))
                .setContentText(isServerRunning() ? serverStatus : current)
                .setStyle(new Notification.BigTextStyle().bigText(isServerRunning()
                        ? serverStatus + "\n" + details : details))
                .setSubText(showTts ? "Supertonic 3 · CPU" : audio.busy || audio.loaded && !state.loaded ? "Whisper tiny · CPU" : state.backend)
                .setContentIntent(open)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        if (state.canCancel || audio.canCancel || synthesis.canCancel) builder.addAction(new Notification.Action.Builder(
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
        shuttingDown = true;
        closeServer();
        serverListeners.clear();
        manager.detach(this);
        speech.detach(this);
        tts.detach(this);
        manager.close();
        speech.close();
        tts.close();
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }
}
