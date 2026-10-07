package dev.inferdroid.engine;

/** Independent of Activities, server protocols, and any future tool layer. */
public interface InferenceEngine {
    GenerationResult generate(GenerationRequest request) throws Exception;
}
