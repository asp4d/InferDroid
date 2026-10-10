package dev.inferdroid.tts;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Handler;
import android.os.Looper;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** In-memory preview, separate from inference. Leaving the Activity stops it. */
public final class TtsPlayback implements AutoCloseable {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private AtomicBoolean cancelled = new AtomicBoolean();
    private boolean closed;
    public void play(PcmAudio audio, Runnable failed) {
        stop();
        if (closed) return;
        AtomicBoolean token = new AtomicBoolean();
        cancelled = token;
        worker.execute(() -> {
            AudioTrack track = null;
            try {
                if (token.get()) return;
                track = new AudioTrack.Builder().setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                        .setAudioFormat(new AudioFormat.Builder().setSampleRate(PcmAudio.SAMPLE_RATE)
                                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                        .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(audio.pcm.length).build();
                // A static track starts in STATE_NO_STATIC_DATA and becomes
                // initialized only after its first successful write.
                if (track.write(audio.pcm, 0, audio.pcm.length) != audio.pcm.length
                        || track.getState() != AudioTrack.STATE_INITIALIZED)
                    throw new IllegalStateException("Cannot play generated speech.");
                if (token.get()) return;
                track.play();
                long deadline = System.nanoTime() + (long) ((audio.durationSeconds() + 5) * 1_000_000_000L);
                while (!token.get() && track.getPlaybackHeadPosition() < audio.pcm.length / 2
                        && System.nanoTime() < deadline) Thread.sleep(50);
            } catch (Exception error) {
                if (!token.get()) main.post(() -> { if (!token.get()) failed.run(); });
            } finally {
                if (track != null) { try { track.stop(); } catch (RuntimeException ignored) { } track.release(); }
            }
        });
    }
    public void stop() { cancelled.set(true); }
    @Override public void close() { closed = true; stop(); worker.shutdown(); }
}
