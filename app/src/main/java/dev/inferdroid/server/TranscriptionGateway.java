package dev.inferdroid.server;

import dev.inferdroid.speech.TranscriptionListener;
import dev.inferdroid.speech.TranscriptionRequest;

public interface TranscriptionGateway {
    boolean isAvailable();
    boolean transcribe(TranscriptionRequest request, TranscriptionListener listener) throws Exception;
    void cancel(TranscriptionListener listener);
}
