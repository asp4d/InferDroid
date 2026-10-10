package dev.inferdroid.tts;

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

/** Main-thread lifecycle and ownership; model loading and synthesis stay off main. */
public final class TtsManager implements AutoCloseable {
    public enum Phase { UNLOADED, IMPORTING, LOADING, READY, SYNTHESIZING, STOPPING }
    public interface Listener { void onTtsChanged(State state); }
    public interface ImportOperation { void run(AtomicBoolean cancelled) throws Exception; }
    public static final class State {
        public final Phase phase;
        public final boolean loaded;
        public final boolean busy;
        public final boolean canCancel;
        public final String status;
        public final SynthesisResult result;
        State(Phase phase, boolean loaded, boolean cancelled, String status, SynthesisResult result) {
            this.phase = phase; this.loaded = loaded; this.status = status; this.result = result;
            busy = phase != Phase.UNLOADED && phase != Phase.READY;
            canCancel = busy && phase != Phase.STOPPING && !cancelled;
        }
    }
    private final TtsEngine engine;
    private final WorkGuard guard;
    private final WorkGate gate;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor(
            task -> new Thread(task, "InferDroid-tts"));
    private final Set<Listener> listeners = new LinkedHashSet<>();
    private final ArrayList<Runnable> stopped = new ArrayList<>();
    private AtomicBoolean cancellation = new AtomicBoolean();
    private SynthesisListener activeListener;
    private boolean stopping;
    private boolean closed;
    private State state = new State(Phase.UNLOADED, false, false,
            "Supertonic 3 · CPU · model unloaded. Import the TTS model to begin.", null);

    public TtsManager(TtsEngine engine, WorkGuard guard, WorkGate gate) {
        this.engine = engine; this.guard = guard; this.gate = gate;
    }
    public State getState() { return state; }
    public void attach(Listener listener) { listeners.add(listener); listener.onTtsChanged(state); }
    public void detach(Listener listener) { listeners.remove(listener); }

    public boolean load() { return start(null, null, null); }
    public boolean synthesize(SynthesisRequest request, SynthesisListener listener) {
        return start(request, listener, null);
    }
    public boolean importModel(ImportOperation operation) { return start(null, null, operation); }

    private boolean start(SynthesisRequest request, SynthesisListener listener, ImportOperation importer) {
        if (closed || state.busy || !gate.acquire(this)) return false;
        AtomicBoolean token = new AtomicBoolean();
        cancellation = token;
        activeListener = listener;
        publish(importer != null ? Phase.IMPORTING : engine.isLoaded() && request != null ? Phase.SYNTHESIZING : Phase.LOADING,
                importer != null ? "Importing and verifying Supertonic model…"
                        : engine.isLoaded() && request != null ? "Synthesizing locally · Supertonic 3 · CPU…" : "Loading Supertonic 3 · CPU…", null);
        execute(() -> {
            long began = System.nanoTime();
            SynthesisResult result;
            try {
                if (importer != null) {
                    engine.unload();
                    importer.run(token);
                    result = new SynthesisResult(null, "TTS model imported and verified. Ready to load.");
                } else {
                    if (!token.get()) engine.load();
                    if (request == null || token.get()) result = new SynthesisResult(null, "Supertonic 3 loaded · CPU.");
                    else {
                        main.post(() -> { if (!stopping && !closed) publish(Phase.SYNTHESIZING,
                                "Synthesizing locally · Supertonic 3 · CPU…", null); });
                        result = engine.synthesize(request, token);
                    }
                }
            } catch (SherpaTtsEngine.OutputLimitException error) {
                result = SynthesisResult.failure(SynthesisResult.Failure.AUDIO_TOO_LARGE, error.getMessage());
            } catch (TtsModelStore.ModelException error) {
                result = SynthesisResult.failure(SynthesisResult.Failure.MODEL_UNAVAILABLE, error.getMessage());
            } catch (Exception | LinkageError | OutOfMemoryError error) {
                // Never log input text, audio bytes, client filename, or model paths.
                Log.e("InferDroid", "TTS operation failed: " + error.getClass().getSimpleName());
                result = SynthesisResult.failure(SynthesisResult.Failure.ENGINE,
                        "TTS operation failed: " + error.getClass().getSimpleName());
            }
            SynthesisResult completed = token.get() ? cancelled() : result;
            long elapsed = (System.nanoTime() - began) / 1_000_000;
            main.post(() -> {
                activeListener = null;
                if (stopping || closed) { if (listener != null) listener.onComplete(cancelled()); return; }
                gate.release(this);
                String status = completed.success ? (importer != null ? completed.diagnostics
                        : request == null ? "Supertonic 3 loaded · CPU. Ready for text."
                        : "Synthesized in " + elapsed + " ms · CPU. TTS model remains loaded.")
                        : completed.failure == SynthesisResult.Failure.CANCELLED ? "TTS request cancelled; native work drained."
                        : completed.diagnostics;
                publish(engine.isLoaded() ? Phase.READY : Phase.UNLOADED, status, completed);
                if (listener != null) listener.onComplete(completed);
            });
        });
        return true;
    }

    public void cancel() { if (state.canCancel && !closed) requestCancel(); }
    public void cancel(SynthesisListener owner) {
        if (owner != null && owner == activeListener && !closed && !stopping) requestCancel();
    }
    private void requestCancel() {
        cancellation.set(true);
        publish(state.phase, "Cancellation requested. Waiting for the current TTS synthesis to drain…", state.result);
    }
    private static SynthesisResult cancelled() {
        return SynthesisResult.failure(SynthesisResult.Failure.CANCELLED, "TTS request cancelled.");
    }

    public void unload(Runnable callback) {
        if (callback != null) stopped.add(callback);
        if (stopping) return;
        stopping = true;
        gate.hold(this);
        cancellation.set(true);
        publish(Phase.STOPPING, "Stopping TTS work and unloading Supertonic…", state.result);
        execute(() -> {
            SynthesisResult failure = null;
            try { engine.unload(); }
            catch (RuntimeException | LinkageError error) {
                failure = SynthesisResult.failure(SynthesisResult.Failure.ENGINE, "TTS unload failed.");
            }
            SynthesisResult result = failure;
            main.post(() -> {
                stopping = false;
                gate.release(this);
                publish(Phase.UNLOADED, result == null ? "TTS model unloaded · CPU." : result.diagnostics, result);
                ArrayList<Runnable> callbacks = new ArrayList<>(stopped);
                stopped.clear();
                for (Runnable task : callbacks) task.run();
            });
        });
    }
    private void execute(Runnable task) {
        worker.execute(() -> {
            try {
                try { guard.begin(); } catch (RuntimeException error) { Log.e("InferDroid", "TTS wake lock acquisition failed"); }
                task.run();
            } finally {
                try { guard.end(); } catch (RuntimeException error) { Log.e("InferDroid", "TTS wake lock release failed"); }
            }
        });
    }
    private void publish(Phase phase, String status, SynthesisResult result) {
        state = new State(phase, engine.isLoaded(), cancellation.get(), status, result);
        for (Listener listener : new ArrayList<>(listeners)) listener.onTtsChanged(state);
    }
    @Override public void close() {
        if (closed) return;
        listeners.clear();
        unload(null);
        closed = true;
        worker.shutdown();
    }
}
