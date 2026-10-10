package dev.inferdroid.engine;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

/** Service-owned lifecycle. Commands/listeners run on main; inference does not. */
public final class EngineManager {
    public interface Listener { void onStateChanged(State state); }
    public interface WorkGuard { void begin(); void end(); }
    public enum Phase { UNLOADED, LOADING, READY, GENERATING, STOPPING }

    public static final class State {
        public final Phase phase;
        public final boolean busy;
        public final boolean loaded;
        public final boolean canCancel;
        public final String backend;
        public final String status;
        public final GenerationResult result;

        State(Phase phase, boolean loaded, boolean verified, boolean cancelled,
              String status, GenerationResult result) {
            this.phase = phase;
            this.busy = phase == Phase.LOADING || phase == Phase.GENERATING || phase == Phase.STOPPING;
            this.loaded = loaded;
            this.canCancel = phase == Phase.GENERATING && !cancelled;
            this.backend = phase == Phase.LOADING ? "Tensor G5 NPU requested · loading"
                    : loaded ? (verified ? "Tensor G5 NPU · generation verified"
                    : "Tensor G5 NPU initialized · first generation pending")
                    : "Tensor G5 NPU selected · model unloaded";
            this.status = status;
            this.result = result;
        }
    }

    private final InferenceEngine engine;
    private final WorkGuard guard;
    private final WorkGate gate;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(
            task -> new Thread(task, "InferDroid-engine"));
    private final ExecutorService control = Executors.newSingleThreadExecutor(
            task -> new Thread(task, "InferDroid-cancel"));
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Set<Listener> listeners = new LinkedHashSet<>();
    private AtomicBoolean cancelled = new AtomicBoolean();
    private GenerationListener activeListener;
    private final ArrayList<Runnable> stoppedCallbacks = new ArrayList<>();
    private State state = new State(Phase.UNLOADED, false, false, false,
            "Select the Tensor G5 model, then press Load model or Run.", null);
    private boolean verified;
    private boolean stopping;
    private boolean closed;

    public EngineManager(InferenceEngine engine, WorkGuard guard) {
        this(engine, guard, new WorkGate());
    }

    public EngineManager(InferenceEngine engine, WorkGuard guard, WorkGate gate) {
        this.engine = engine;
        this.guard = guard;
        this.gate = gate;
    }

    public State getState() { return state; }
    public void attach(Listener listener) {
        listeners.add(listener);
        listener.onStateChanged(state);
    }
    public void detach(Listener listener) { listeners.remove(listener); }

    public boolean load(String source, boolean verbose) {
        if (closed || state.busy || !gate.acquire(this)) return false;
        cancelled.set(false);
        if (!source.equals(engine.getModelSource())) verified = false;
        publish(Phase.LOADING, "Loading Gemma with Tensor G5 NPU requested…", null);
        execute(() -> {
            GenerationResult result;
            try {
                result = engine.load(source, verbose);
            } catch (Exception | LinkageError | OutOfMemoryError error) {
                result = failure(error);
            }
            GenerationResult completed = result;
            main.post(() -> {
                if (stopping || closed) return;
                gate.release(this);
                publish(engine.isLoaded() ? Phase.READY : Phase.UNLOADED,
                        completed.success ? "Model loaded. Ready; it stays loaded between requests."
                                : "Model load failed. See diagnostics; no CPU retry.", completed);
            });
        });
        return true;
    }

    public boolean generate(GenerationRequest request) {
        return generate(request, null);
    }

    public boolean generate(GenerationRequest request, GenerationListener listener) {
        if (closed || state.busy || !gate.acquire(this)) {
            Log.i("InferDroid", "Rejected request: engine is busy or stopping");
            return false;
        }
        AtomicBoolean cancellation = new AtomicBoolean();
        cancelled = cancellation;
        activeListener = listener;
        boolean needsLoad = !engine.isLoaded() || !request.modelSource.equals(engine.getModelSource());
        if (needsLoad) verified = false;
        publish(needsLoad ? Phase.LOADING : Phase.GENERATING,
                needsLoad ? "Loading Gemma with Tensor G5 NPU requested…"
                        : "Generating on the loaded Tensor G5 engine…", null);
        execute(() -> {
            long start = System.nanoTime();
            GenerationResult result;
            try {
                GenerationResult loaded = engine.load(request.modelSource, request.verbose);
                if (!loaded.success) {
                    result = loaded;
                } else if (cancellation.get()) {
                    result = new GenerationResult(false, true, "", "Request cancelled before generation.");
                } else {
                    main.post(() -> {
                        if (!stopping && !closed) publish(Phase.GENERATING,
                                "Generating on the loaded Tensor G5 engine…", null);
                    });
                    result = engine.generate(request, cancellation, listener);
                    result = result.withDiagnostics(engine.getLoadDiagnostics()
                            + "\nRequest diagnostics:\n" + result.diagnostics);
                }
            } catch (Exception | LinkageError | OutOfMemoryError error) {
                result = failure(error);
            }
            GenerationResult completed = result;
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            main.post(() -> {
                activeListener = null;
                if (stopping || closed) {
                    if (listener != null) listener.onComplete(new GenerationResult(
                            false, true, completed.text, "Inference service stopped."));
                    return;
                }
                gate.release(this);
                if (completed.success) verified = true;
                String status = completed.success
                        ? "Generated in " + elapsedMs + " ms. Model remains loaded on Tensor G5."
                        : completed.cancelled ? "Generation cancelled. Model remains loaded."
                        : "Failed after " + elapsedMs + " ms. See diagnostics; no CPU retry.";
                Log.i("InferDroid", status);
                publish(engine.isLoaded() ? Phase.READY : Phase.UNLOADED, status, completed);
                if (listener != null) listener.onComplete(completed);
            });
        });
        return true;
    }

    public void cancel() {
        if (!state.canCancel || closed) return;
        requestCancellation();
    }

    /** Only the caller that owns the active request may cancel it. Also works during loading. */
    public void cancel(GenerationListener listener) {
        if (listener == null || listener != activeListener || closed || stopping) return;
        requestCancellation();
    }

    private void requestCancellation() {
        cancelled.set(true);
        publish(state.phase, "Cancellation requested; waiting for the native task to stop…", state.result);
        cancelNative();
    }

    private Future<?> cancelNative() {
        Runnable cancellation = engine.cancellation();
        return control.submit(() -> {
            try {
                cancellation.run();
            } catch (LinkageError | RuntimeException error) {
                Log.e("InferDroid", "Cancellation failed", error);
            }
        });
    }

    public void unload(Runnable onStopped) {
        if (onStopped != null) stoppedCallbacks.add(onStopped);
        if (stopping) return;
        stopping = true;
        gate.hold(this);
        cancelled.set(true);
        Future<?> cancellation = cancelNative();
        publish(Phase.STOPPING, "Stopping active work and unloading the model…", state.result);
        execute(() -> {
            GenerationResult error = null;
            try {
                // The worker has drained generation; also drain its concurrent
                // cancel call before releasing the native engine and its file.
                cancellation.get();
                engine.unload();
            } catch (Exception | LinkageError problem) {
                error = failure(problem);
            }
            GenerationResult completed = error;
            main.post(() -> {
                verified = false;
                stopping = false;
                gate.release(this);
                publish(Phase.UNLOADED, completed == null
                        ? "Model unloaded. Inference service stopped." : "Unload failed; see diagnostics.", completed);
                ArrayList<Runnable> callbacks = new ArrayList<>(stoppedCallbacks);
                stoppedCallbacks.clear();
                for (Runnable callback : callbacks) callback.run();
            });
        });
    }

    public void close() {
        if (closed) return;
        listeners.clear();
        unload(null);
        closed = true;
        worker.shutdown(); // Drains cleanup without blocking main.
        control.shutdown();
    }

    private void execute(Runnable task) {
        worker.execute(() -> {
            try {
                try { guard.begin(); }
                catch (RuntimeException error) { Log.e("InferDroid", "Wake lock acquisition failed", error); }
                task.run();
            } finally {
                try { guard.end(); }
                catch (RuntimeException error) { Log.e("InferDroid", "Wake lock release failed", error); }
            }
        });
    }

    private GenerationResult failure(Throwable error) {
        String message = error.getClass().getSimpleName() + ": " + error.getMessage();
        Log.e("InferDroid", message);
        return new GenerationResult(false, "", message
                + "\nNo CPU retry was attempted. Native crashes are preserved in adb logcat.");
    }

    private void publish(Phase phase, String status, GenerationResult result) {
        state = new State(phase, engine.isLoaded(), verified, cancelled.get(), status, result);
        for (Listener listener : new ArrayList<>(listeners)) listener.onStateChanged(state);
    }
}
