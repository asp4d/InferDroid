package dev.inferdroid.tts;

import java.util.concurrent.atomic.AtomicBoolean;

public interface TtsEngine {
    boolean isLoaded();
    void load() throws Exception;
    SynthesisResult synthesize(SynthesisRequest request, AtomicBoolean cancelled) throws Exception;
    void unload();
}
