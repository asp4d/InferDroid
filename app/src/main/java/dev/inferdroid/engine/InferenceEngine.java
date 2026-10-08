package dev.inferdroid.engine;

import java.util.concurrent.atomic.AtomicBoolean;

/** Independent of Activities, server protocols, and any future tool layer. */
public interface InferenceEngine {
    GenerationResult load(String modelSource, boolean verbose) throws Exception;
    boolean isLoaded();
    String getModelSource();
    String getLoadDiagnostics();
    GenerationResult generate(GenerationRequest request, AtomicBoolean cancelled) throws Exception;
    void cancel();
    void unload();
}
