package dev.inferdroid.engine;

/** A text turn; independent of the external HTTP schema. */
public final class ChatMessage {
    public final String role;
    public final String text;

    public ChatMessage(String role, String text) {
        this.role = role;
        this.text = text;
    }
}
