package dev.inferdroid.engine;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Single request owner; survives Activity recreation without retaining its UI. */
public final class EngineManager {
    public interface Listener { void onStateChanged(State state); }

    public static final class State {
        public final boolean busy;
        public final String status;
        public final GenerationResult result;

        State(boolean busy, String status, GenerationResult result) {
            this.busy = busy;
            this.status = status;
            this.result = result;
        }
    }

    private final InferenceEngine engine;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private State state = new State(false, "Select the Tensor G5 model, then press Run.", null);
    private Listener listener;

    public EngineManager(InferenceEngine engine) { this.engine = engine; }

    // UI subscription/state policy stays in Java. These methods run on main.
    public void attach(Listener listener) {
        this.listener = listener;
        listener.onStateChanged(state);
    }

    public void detach(Listener listener) {
        if (this.listener == listener) this.listener = null;
    }

    public boolean generate(GenerationRequest request) {
        if (state.busy) return false;
        publish(new State(true, "Loading Gemma and running with NPU requested…", null));
        worker.execute(() -> {
            long start = System.nanoTime();
            GenerationResult result;
            try {
                result = engine.generate(request);
            } catch (Exception | LinkageError | OutOfMemoryError error) {
                String message = error.getClass().getSimpleName() + ": " + error.getMessage();
                Log.e("InferDroid", "Inference failed: " + message);
                result = new GenerationResult(false, "", message
                        + "\nNPU initialization failed; no CPU retry was attempted."
                        + "\nNative crashes, if any, are preserved in adb logcat.");
            }
            GenerationResult completed = result;
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            String status = result.success
                    ? "Generated in " + elapsedMs + " ms. Google Tensor / EdgeTPU loaded; inspect delegate logs."
                    : "Failed after " + elapsedMs + " ms. See diagnostics and logcat.";
            main.post(() -> publish(new State(false, status, completed)));
        });
        return true;
    }

    private void publish(State next) {
        state = next;
        if (listener != null) listener.onStateChanged(next);
    }
}
