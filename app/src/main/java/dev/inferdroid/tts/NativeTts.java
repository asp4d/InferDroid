package dev.inferdroid.tts;

final class NativeTts {
    static { System.loadLibrary("inferdroid_speech_jni"); }
    private NativeTts() { }
    static native long load(String directory);
    static native byte[] generate(long handle, byte[] text, String language, int speaker, float speed);
    static native void unload(long handle);
}
