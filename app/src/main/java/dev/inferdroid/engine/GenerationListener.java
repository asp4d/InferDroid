package dev.inferdroid.engine;

/** Callbacks may run off main. Consumers must return promptly and never block inference. */
public interface GenerationListener {
    void onText(String delta);
    void onComplete(GenerationResult result);
}
