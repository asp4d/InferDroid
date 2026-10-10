package dev.inferdroid.speech;

import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaDataSource;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

/** Bounded file decoding to mono 16 kHz. No microphone or filesystem permission. */
public final class AudioDecoder {
    public static final int MAX_FILE_BYTES = 25 * 1024 * 1024;
    public static final int MAX_SECONDS = 120;
    public static final int SAMPLE_RATE = 16000;
    public static final class AudioException extends IOException {
        public final boolean tooLarge;
        public AudioException(String message, boolean tooLarge) { super(message); this.tooLarge = tooLarge; }
    }
    public static final class Audio {
        public final float[] samples;
        Audio(float[] samples) { this.samples = samples; }
        public double durationSeconds() { return samples.length / (double) SAMPLE_RATE; }
    }
    private AudioDecoder() { }
    public static Audio decode(InputStream input, AtomicBoolean cancelled) throws IOException {
        if (input == null) throw invalid("No audio data was supplied.");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[64 * 1024];
        int count;
        while ((count = input.read(buffer)) >= 0) {
            checkCancelled(cancelled);
            if (bytes.size() + count > MAX_FILE_BYTES) throw tooLarge("Audio file exceeds 25 MiB.");
            bytes.write(buffer, 0, count);
        }
        byte[] encoded = bytes.toByteArray();
        if (encoded.length == 0) throw invalid("The audio file is empty.");
        if (tag(encoded, 0, "RIFF")) return wav(encoded, cancelled);
        return compressed(encoded, cancelled);
    }
    private static Audio wav(byte[] bytes, AtomicBoolean cancelled) throws IOException {
        if (bytes.length < 12 || !tag(bytes, 8, "WAVE") || uint(bytes, 4) + 8 != bytes.length) throw invalid("Malformed RIFF/WAVE file.");
        int rate = 0, channels = 0, bits = 0, format = 0, align = 0;
        int dataStart = -1, dataLength = 0;
        for (int pos = 12; pos < bytes.length;) {
            if (pos > bytes.length - 8) throw invalid("Truncated WAV chunk.");
            long size = uint(bytes, pos + 4);
            long end = pos + 8L + size;
            if (end > bytes.length) throw invalid("Truncated WAV chunk data.");
            if (tag(bytes, pos, "fmt ")) {
                if (format != 0 || size < 16) throw invalid("Invalid or duplicate WAV format chunk.");
                format = ushort(bytes, pos + 8); channels = ushort(bytes, pos + 10);
                long sampleRate = uint(bytes, pos + 12);
                if (sampleRate > 192000) throw invalid("Unsupported WAV sample rate.");
                rate = (int) sampleRate; align = ushort(bytes, pos + 20); bits = ushort(bytes, pos + 22);
                if (format == 0xfffe) {
                    byte[] guidTail = {0, 0, 16, 0, (byte) 128, 0, 0, (byte) 170, 0, 56, (byte) 155, 113};
                    if (size < 40 || ushort(bytes, pos + 24) < 22 || ushort(bytes, pos + 26) != bits
                            || !at(bytes, pos + 36, guidTail) || uint(bytes, pos + 32) > 3) {
                        throw invalid("Unsupported extensible WAV format or packed sample width.");
                    }
                    format = (int) uint(bytes, pos + 32);
                }
                if (format != 1 && format != 3 || format == 3 && bits != 32
                        || format == 1 && bits != 8 && bits != 16 && bits != 24 && bits != 32
                        || channels < 1 || channels > 2 || rate < 8000
                        || align != channels * (bits / 8) || uint(bytes, pos + 16) != (long) rate * align) {
                    throw invalid("Use PCM 8/16/24/32-bit or float32 WAV, mono/stereo, 8–192 kHz.");
                }
            } else if (tag(bytes, pos, "data")) {
                if (dataStart >= 0) throw invalid("Multiple WAV data chunks are unsupported.");
                dataStart = pos + 8; dataLength = (int) size;
            }
            long next = end + (size & 1);
            if (next > bytes.length) throw invalid("Missing WAV padding byte.");
            pos = (int) next;
        }
        if (format == 0 || dataStart < 0 || dataLength == 0 || dataLength % align != 0) throw invalid("WAV format or audio samples are missing or malformed.");
        if ((long) dataLength / align > (long) rate * MAX_SECONDS) throw tooLarge("Audio duration exceeds 120 seconds.");
        Collector collector = new Collector(rate);
        ByteBuffer pcm = ByteBuffer.wrap(bytes, dataStart, dataLength).order(ByteOrder.LITTLE_ENDIAN);
        appendPcm(collector, pcm, channels, bits, format == 3, cancelled);
        return collector.finish();
    }
    private static Audio compressed(byte[] bytes, AtomicBoolean cancelled) throws IOException {
        MediaExtractor extractor = new MediaExtractor();
        MediaCodec codec = null;
        try {
            extractor.setDataSource(new MediaDataSource() {
                @Override public int readAt(long position, byte[] target, int offset, int size) {
                    if (position < 0 || position >= bytes.length) return -1;
                    int n = (int) Math.min(size, bytes.length - position);
                    System.arraycopy(bytes, (int) position, target, offset, n);
                    return n;
                }
                @Override public long getSize() { return bytes.length; }
                @Override public void close() { }
            });
            MediaFormat selected = null;
            int track = -1;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat format = extractor.getTrackFormat(i);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    if (track >= 0) throw invalid("Supply a file with one audio track.");
                    selected = format; track = i;
                }
            }
            if (selected == null) throw invalid("No supported audio track was found. Try a PCM WAV file.");
            if (selected.containsKey(MediaFormat.KEY_DURATION)
                    && selected.getLong(MediaFormat.KEY_DURATION) > MAX_SECONDS * 1_000_000L) throw tooLarge("Audio duration exceeds 120 seconds.");
            extractor.selectTrack(track);
            codec = MediaCodec.createDecoderByType(selected.getString(MediaFormat.KEY_MIME));
            codec.configure(selected, null, null, 0);
            codec.start();
            Collector collector = null;
            int channels = 0, encoding = AudioFormat.ENCODING_PCM_16BIT;
            boolean inputDone = false, outputDone = false;
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            long deadline = System.nanoTime() + 30_000_000_000L;
            while (!outputDone) {
                checkCancelled(cancelled);
                if (System.nanoTime() > deadline) throw invalid("Audio decoding timed out. Try a PCM WAV file.");
                if (!inputDone) {
                    int index = codec.dequeueInputBuffer(10_000);
                    if (index >= 0) {
                        ByteBuffer target = codec.getInputBuffer(index);
                        if (target == null) throw invalid("The audio decoder returned no input buffer.");
                        int size = extractor.readSampleData(target, 0);
                        if (size < 0) {
                            codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            codec.queueInputBuffer(index, 0, size, extractor.getSampleTime(), 0);
                            extractor.advance();
                        }
                    }
                }
                int index = codec.dequeueOutputBuffer(info, 10_000);
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat format = codec.getOutputFormat();
                    int rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                    encoding = format.containsKey(MediaFormat.KEY_PCM_ENCODING)
                            ? format.getInteger(MediaFormat.KEY_PCM_ENCODING) : AudioFormat.ENCODING_PCM_16BIT;
                    if (channels < 1 || channels > 2 || rate < 8000 || rate > 192000
                            || encoding != AudioFormat.ENCODING_PCM_16BIT && encoding != AudioFormat.ENCODING_PCM_FLOAT) {
                        throw invalid("Unsupported decoded audio format. Try mono/stereo PCM WAV.");
                    }
                    if (collector != null) throw invalid("Changing audio formats mid-file is unsupported.");
                    collector = new Collector(rate);
                } else if (index >= 0) {
                    try {
                        if (info.size > 0) {
                            if (collector == null) throw invalid("The decoder returned audio before its format.");
                            ByteBuffer pcm = codec.getOutputBuffer(index);
                            if (pcm == null) throw invalid("The decoder returned no audio buffer.");
                            pcm.position(info.offset); pcm.limit(info.offset + info.size);
                            appendPcm(collector, pcm.order(ByteOrder.LITTLE_ENDIAN), channels,
                                    encoding == AudioFormat.ENCODING_PCM_FLOAT ? 32 : 16,
                                    encoding == AudioFormat.ENCODING_PCM_FLOAT, cancelled);
                        }
                        outputDone = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    } finally { codec.releaseOutputBuffer(index, false); }
                }
            }
            if (collector == null) throw invalid("No decoded audio samples were found.");
            return collector.finish();
        } catch (AudioException error) { throw error; }
        catch (RuntimeException | IOException error) {
            throw invalid("The audio file is corrupt or its codec is unsupported on this device. Try PCM WAV.");
        } finally {
            if (codec != null) { try { codec.stop(); } catch (RuntimeException ignored) { } codec.release(); }
            extractor.release();
        }
    }
    private static void appendPcm(Collector collector, ByteBuffer pcm, int channels, int bits,
                                  boolean floating, AtomicBoolean cancelled) throws IOException {
        if (pcm.remaining() % (channels * bits / 8) != 0) throw invalid("Truncated PCM audio frame.");
        int frames = 0;
        while (pcm.hasRemaining()) {
            if ((frames++ & 4095) == 0) checkCancelled(cancelled);
            float mono = 0;
            for (int channel = 0; channel < channels; channel++) {
                float value;
                if (floating) value = pcm.getFloat();
                else if (bits == 8) value = ((pcm.get() & 255) - 128) / 128f;
                else if (bits == 16) value = pcm.getShort() / 32768f;
                else if (bits == 24) {
                    int raw = (pcm.get() & 255) | (pcm.get() & 255) << 8 | pcm.get() << 16;
                    value = raw / 8388608f;
                } else value = pcm.getInt() / 2147483648f;
                if (!Float.isFinite(value)) throw invalid("Audio contains invalid floating-point samples.");
                mono += Math.max(-1, Math.min(1, value)) / channels;
            }
            collector.add(mono);
        }
    }
    private static final class Collector {
        private final int rate;
        private final float[] samples = new float[MAX_SECONDS * SAMPLE_RATE];
        private long frames;
        private int count;
        private float previous;
        Collector(int rate) { this.rate = rate; }
        void add(float current) throws AudioException {
            if (frames >= (long) MAX_SECONDS * rate) throw tooLarge("Audio duration exceeds 120 seconds.");
            while ((long) count * rate <= frames * SAMPLE_RATE) {
                if (count >= samples.length) break;
                double position = count * (double) rate / SAMPLE_RATE;
                samples[count++] = frames == 0 ? current : (float) (previous + (current - previous) * (position - frames + 1));
            }
            previous = current; frames++;
        }
        Audio finish() throws AudioException {
            if (count == 0) throw invalid("The audio file has no samples.");
            int expected = (int) ((frames * SAMPLE_RATE + rate - 1) / rate);
            while (count < expected) samples[count++] = previous;
            return new Audio(Arrays.copyOf(samples, count));
        }
    }
    private static boolean tag(byte[] bytes, int pos, String value) {
        if (pos + value.length() > bytes.length) return false;
        for (int i = 0; i < value.length(); i++) if (bytes[pos + i] != value.charAt(i)) return false;
        return true;
    }
    private static boolean at(byte[] bytes, int pos, byte[] values) {
        if (pos > bytes.length - values.length) return false;
        for (int i = 0; i < values.length; i++) if (bytes[pos + i] != values[i]) return false;
        return true;
    }
    private static long uint(byte[] bytes, int pos) {
        return (bytes[pos] & 255L) | (bytes[pos + 1] & 255L) << 8
                | (bytes[pos + 2] & 255L) << 16 | (bytes[pos + 3] & 255L) << 24;
    }
    private static int ushort(byte[] bytes, int pos) { return (bytes[pos] & 255) | (bytes[pos + 1] & 255) << 8; }
    private static void checkCancelled(AtomicBoolean cancelled) throws AudioException {
        if (cancelled.get()) throw invalid("Audio decoding cancelled.");
    }
    private static AudioException invalid(String message) { return new AudioException(message, false); }
    private static AudioException tooLarge(String message) { return new AudioException(message, true); }
}
