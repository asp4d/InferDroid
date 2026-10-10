package dev.inferdroid.speech;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import dev.inferdroid.engine.EngineManager.WorkGuard;
import dev.inferdroid.engine.WorkGate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

/** Main-thread lifecycle and ownership; model loading and decoding stay off main. */
public final class SpeechManager implements AutoCloseable {
    public enum Phase { UNLOADED, IMPORTING, LOADING, READY, TRANSCRIBING, STOPPING }
    public interface Listener { void onSpeechChanged(State state); }
    public interface ImportOperation { void run(AtomicBoolean cancelled) throws Exception; }
    public static final class State {
        public final Phase phase;
        public final boolean loaded;
        public final boolean busy;
        public final boolean canCancel;
        public final String status;
        public final TranscriptionResult result;
        State(Phase phase, boolean loaded, boolean cancelled, String status, TranscriptionResult result) {
            this.phase = phase; this.loaded = loaded; this.status = status; this.result = result;
            busy = phase != Phase.UNLOADED && phase != Phase.READY;
            canCancel = busy && phase != Phase.STOPPING && !cancelled;
        }
    }
    private final SpeechEngine engine;
    private final WorkGuard guard;
    private final WorkGate gate;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor(
            task -> new Thread(task, "InferDroid-speech"));
    private final Set<Listener> listeners = new LinkedHashSet<>();
    private final ArrayList<Runnable> stopped = new ArrayList<>();
    private AtomicBoolean cancellation = new AtomicBoolean();
    private TranscriptionListener activeListener;
    private boolean stopping;
    private boolean closed;
    private State state = new State(Phase.UNLOADED, false, false,
            "Whisper tiny · CPU · model unloaded. Import the speech model to begin.", null);

    public SpeechManager(SpeechEngine engine, WorkGuard guard, WorkGate gate) {
        this.engine = engine; this.guard = guard; this.gate = gate;
    }
    public State getState() { return state; }
    public void attach(Listener listener) { listeners.add(listener); listener.onSpeechChanged(state); }
    public void detach(Listener listener) { listeners.remove(listener); }

    public boolean load() { return start(null, null, null); }
    public boolean transcribe(TranscriptionRequest request, TranscriptionListener listener) {
        return start(request, listener, null);
    }
    public boolean importModel(ImportOperation operation) { return start(null, null, operation); }

    private boolean start(TranscriptionRequest request, TranscriptionListener listener, ImportOperation importer) {
        if (closed || state.busy || !gate.acquire(this)) return false;
        AtomicBoolean token = new AtomicBoolean();
        cancellation = token;
        activeListener = listener;
        publish(importer != null ? Phase.IMPORTING : engine.isLoaded() && request != null ? Phase.TRANSCRIBING : Phase.LOADING,
                importer != null ? "Importing and verifying Whisper model…"
                        : engine.isLoaded() && request != null ? "Transcribing locally · Whisper tiny · CPU…" : "Loading Whisper tiny · CPU…", null);
        execute(() -> {
            long began = System.nanoTime();
            TranscriptionResult result;
            try {
                if (importer != null) {
                    engine.unload();
                    importer.run(token);
                    result = new TranscriptionResult("", "", 0, "Speech model imported and verified. Ready to load.");
                } else {
                    if (!token.get()) engine.load();
                    if (request == null || token.get()) result = new TranscriptionResult("", "", 0, "Whisper tiny loaded · CPU.");
                    else {
                        main.post(() -> { if (!stopping && !closed) publish(Phase.TRANSCRIBING,
                                "Transcribing locally · Whisper tiny · CPU…", null); });
                        result = engine.transcribe(request, token);
                    }
                }
            } catch (AudioDecoder.AudioException error) {
                result = TranscriptionResult.failure(error.tooLarge ? TranscriptionResult.Failure.AUDIO_TOO_LARGE
                        : TranscriptionResult.Failure.INVALID_AUDIO, error.getMessage());
            } catch (SpeechModelStore.ModelException error) {
                result = TranscriptionResult.failure(TranscriptionResult.Failure.MODEL_UNAVAILABLE, error.getMessage());
            } catch (Exception | LinkageError | OutOfMemoryError error) {
                // Never log transcript, audio bytes, client filename, or model paths.
                Log.e("InferDroid", "Speech operation failed: " + error.getClass().getSimpleName());
                result = TranscriptionResult.failure(TranscriptionResult.Failure.ENGINE,
                        "Speech operation failed: " + error.getClass().getSimpleName());
            }
            TranscriptionResult completed = token.get() ? cancelled() : result;
            long elapsed = (System.nanoTime() - began) / 1_000_000;
            main.post(() -> {
                activeListener = null;
                if (stopping || closed) { if (listener != null) listener.onComplete(cancelled()); return; }
                gate.release(this);
                String status = completed.success ? (importer != null ? completed.diagnostics
                        : request == null ? "Whisper tiny loaded · CPU. Ready for audio."
                        : "Transcribed in " + elapsed + " ms · CPU. Speech model remains loaded.")
                        : completed.failure == TranscriptionResult.Failure.CANCELLED ? "Speech request cancelled; native work drained."
                        : completed.diagnostics;
                publish(engine.isLoaded() ? Phase.READY : Phase.UNLOADED, status, completed);
                if (listener != null) listener.onComplete(completed);
            });
        });
        return true;
    }

    public void cancel() { if (state.canCancel && !closed) requestCancel(); }
    public void cancel(TranscriptionListener owner) {
        if (owner != null && owner == activeListener && !closed && !stopping) requestCancel();
    }
    private void requestCancel() {
        cancellation.set(true);
        publish(state.phase, "Cancellation requested. Waiting for the current speech decode to drain…", state.result);
    }
    private static TranscriptionResult cancelled() {
        return TranscriptionResult.failure(TranscriptionResult.Failure.CANCELLED, "Speech request cancelled.");
    }

    public void unload(Runnable callback) {
        if (callback != null) stopped.add(callback);
        if (stopping) return;
        stopping = true;
        gate.hold(this);
        cancellation.set(true);
        publish(Phase.STOPPING, "Stopping speech work and unloading Whisper…", state.result);
        execute(() -> {
            TranscriptionResult failure = null;
            try { engine.unload(); }
            catch (RuntimeException | LinkageError error) {
                failure = TranscriptionResult.failure(TranscriptionResult.Failure.ENGINE, "Speech unload failed.");
            }
            TranscriptionResult result = failure;
            main.post(() -> {
                stopping = false;
                gate.release(this);
                publish(Phase.UNLOADED, result == null ? "Speech model unloaded · CPU." : result.diagnostics, result);
                ArrayList<Runnable> callbacks = new ArrayList<>(stopped);
                stopped.clear();
                for (Runnable task : callbacks) task.run();
            });
        });
    }
    private void execute(Runnable task) {
        worker.execute(() -> {
            try {
                try { guard.begin(); } catch (RuntimeException error) { Log.e("InferDroid", "Speech wake lock acquisition failed"); }
                task.run();
            } finally {
                try { guard.end(); } catch (RuntimeException error) { Log.e("InferDroid", "Speech wake lock release failed"); }
            }
        });
    }
    private void publish(Phase phase, String status, TranscriptionResult result) {
        state = new State(phase, engine.isLoaded(), cancellation.get(), status, result);
        for (Listener listener : new ArrayList<>(listeners)) listener.onSpeechChanged(state);
    }
    @Override public void close() {
        if (closed) return;
        listeners.clear();
        unload(null);
        closed = true;
        worker.shutdown();
    }
}
