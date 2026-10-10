package dev.inferdroid.tts;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/** Mono PCM16 at the API's 24 kHz output rate; bounded before allocating a WAV. */
public final class PcmAudio {
    public static final int SAMPLE_RATE = 24000;
    public static final int MAX_SECONDS = 120;
    public static final int MAX_BYTES = SAMPLE_RATE * MAX_SECONDS * 2;
    public final byte[] pcm;
    public PcmAudio(byte[] pcm) {
        if (pcm == null || pcm.length == 0 || pcm.length % 2 != 0 || pcm.length > MAX_BYTES)
            throw new IllegalArgumentException("Invalid or excessive synthesized PCM audio.");
        this.pcm = pcm;
    }
    public double durationSeconds() { return pcm.length / (SAMPLE_RATE * 2.0); }
    public byte[] wav() {
        ByteBuffer out = ByteBuffer.allocate(44 + pcm.length).order(ByteOrder.LITTLE_ENDIAN);
        out.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(36 + pcm.length);
        out.put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16).putShort((short) 1).putShort((short) 1);
        out.putInt(SAMPLE_RATE).putInt(SAMPLE_RATE * 2).putShort((short) 2).putShort((short) 16);
        out.put("data".getBytes(StandardCharsets.US_ASCII)).putInt(pcm.length).put(pcm);
        return out.array();
    }
}
