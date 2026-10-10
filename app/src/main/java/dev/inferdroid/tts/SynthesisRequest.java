package dev.inferdroid.tts;

/** Protocol-neutral local synthesis options. */
public final class SynthesisRequest {
    public static final int MAX_TEXT = 4096;
    public final String text;
    public final String language;
    public final int speaker;
    public final float speed;
    public SynthesisRequest(String text, String language, int speaker, float speed) {
        if (text == null || text.trim().isEmpty() || text.length() > MAX_TEXT || text.indexOf('\0') >= 0)
            throw new IllegalArgumentException("Enter 1–4096 characters of text without null characters.");
        boolean spoken = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (++i >= text.length() || !Character.isLowSurrogate(text.charAt(i)))
                    throw new IllegalArgumentException("Text must contain valid Unicode.");
                spoken |= Character.isLetterOrDigit(Character.toCodePoint(c, text.charAt(i)));
            } else {
                if (Character.isLowSurrogate(c)) throw new IllegalArgumentException("Text must contain valid Unicode.");
                spoken |= Character.isLetterOrDigit(c);
            }
        }
        if (!spoken) throw new IllegalArgumentException("Text must include words or numbers to speak.");
        if (!TtsVoices.supportsLanguage(language)) throw new IllegalArgumentException("Use a supported TTS language code such as en or it.");
        if (speaker < 0 || speaker >= 10) throw new IllegalArgumentException("Choose a supported local voice.");
        if (!Float.isFinite(speed) || speed < 0.25f || speed > 2f) throw new IllegalArgumentException("Speech speed must be between 0.25 and 2.0.");
        this.text = text; this.language = language; this.speaker = speaker; this.speed = speed;
    }
}
