package dev.inferdroid.tts;

import java.util.List;
import java.util.Set;

/** IDs follow the pinned exporter's sorted voice names: F1..F5, then M1..M5. */
public final class TtsVoices {
    public static final List<String> NAMES = List.of("F1", "F2", "F3", "F4", "F5", "M1", "M2", "M3", "M4", "M5");
    public static final Set<String> LANGUAGES = Set.of("en", "ko", "ja", "ar", "bg", "cs", "da", "de", "el", "es", "et", "fi", "fr", "hi", "hr", "hu", "id", "it", "lt", "lv", "nl", "pl", "pt", "ro", "ru", "sk", "sl", "sv", "tr", "uk", "vi");
    private TtsVoices() { }
    public static boolean supportsLanguage(String value) { return value != null && LANGUAGES.contains(value); }
    public static int speaker(String voice) {
        int id = NAMES.indexOf(voice);
        if (id >= 0) return id;
        return switch (voice) {
            case "alloy" -> 0;
            case "echo" -> 5;
            case "fable" -> 6;
            case "onyx" -> 7;
            case "nova" -> 1;
            case "shimmer" -> 2;
            default -> -1;
        };
    }
}
