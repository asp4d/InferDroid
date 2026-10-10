package dev.inferdroid.server;

import dev.inferdroid.tts.SynthesisListener;
import dev.inferdroid.tts.SynthesisRequest;

public interface SynthesisGateway {
    boolean isAvailable();
    boolean synthesize(SynthesisRequest request, SynthesisListener listener) throws Exception;
    void cancel(SynthesisListener owner);
}
