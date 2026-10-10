package dev.inferdroid.speech;

import java.io.IOException;
import java.io.InputStream;

/** Protocol-neutral audio input. Opened only by the speech worker. */
public final class TranscriptionRequest {
    public interface AudioSource { InputStream open() throws IOException; }
    public final AudioSource audio;
    public final String language;
    public TranscriptionRequest(AudioSource audio, String language) {
        this.audio = audio;
        this.language = language == null ? "" : language;
    }
}
