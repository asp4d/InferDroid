package dev.inferdroid.speech;

final class NativeSpeech {
    static { System.loadLibrary("inferdroid_speech_jni"); }
    private NativeSpeech() { }
    static native long load(String modelDirectory);
    static native TranscriptionResult transcribe(long handle, float[] samples, String language);
    static native void unload(long handle);
}
