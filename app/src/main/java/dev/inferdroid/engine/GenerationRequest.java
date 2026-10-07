package dev.inferdroid.engine;

/** Protocol-neutral request for this milestone's one text backend. */
public final class GenerationRequest {
    public final String modelSource;
    public final String prompt;
    public final boolean verbose;

    public GenerationRequest(String modelSource, String prompt, boolean verbose) {
        this.modelSource = modelSource;
        this.prompt = prompt;
        this.verbose = verbose;
    }
}
