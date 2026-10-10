package dev.inferdroid.speech;

import java.util.concurrent.atomic.AtomicBoolean;

/** An ASR engine has no dependency on Gemma, OpenAI JSON, or the HTTP transport. */
public interface SpeechEngine {
    boolean isLoaded();
    void load() throws Exception;
    TranscriptionResult transcribe(TranscriptionRequest request, AtomicBoolean cancelled) throws Exception;
    void unload();
}
