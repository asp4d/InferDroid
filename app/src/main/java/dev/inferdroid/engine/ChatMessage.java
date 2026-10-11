package dev.inferdroid.engine;

import java.util.List;

/** A chat turn; independent of the external HTTP schema. */
public final class ChatMessage {
    public final String role;
    public final String text;
    public final List<ChatPart> parts;

    public ChatMessage(String role, String text) {
        this(role, List.of(ChatPart.text(text)));
    }

    public ChatMessage(String role, List<ChatPart> parts) {
        this.role = role;
        this.parts = List.copyOf(parts);
        StringBuilder joined = new StringBuilder();
        for (ChatPart part : parts) if (!part.isImage()) joined.append(part.text);
        this.text = joined.toString();
    }
}
