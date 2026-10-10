package dev.inferdroid.engine;

import java.util.concurrent.atomic.AtomicBoolean;

/** Independent of Activities, server protocols, and any future tool layer. */
public interface InferenceEngine {
    GenerationResult load(String modelSource, boolean verbose) throws Exception;
    boolean isLoaded();
    String getModelSource();
    String getLoadDiagnostics();
    GenerationResult generate(GenerationRequest request, AtomicBoolean cancelled) throws Exception;
    default GenerationResult generate(GenerationRequest request, AtomicBoolean cancelled,
                                      GenerationListener listener) throws Exception {
        GenerationResult result = generate(request, cancelled);
        if (listener != null && result.success) listener.onText(result.text);
        return result;
    }
    void cancel();
    /** Snapshot the current operation so a delayed control task cannot cancel the next one. */
    default Runnable cancellation() { return this::cancel; }
    void unload();
}
