package dev.inferdroid.engine;

/** Ordered text or image content, independent of HTTP and native JSON schemas. */
public final class ChatPart {
    public final String text;
    public final ImageInput image;
    final ImageInput.Source source;

    private ChatPart(String text, ImageInput image, ImageInput.Source source) {
        this.text = text; this.image = image; this.source = source;
    }
    public static ChatPart text(String text) { return new ChatPart(text, null, null); }
    public static ChatPart image(ImageInput image) { return new ChatPart(null, image, null); }
    public static ChatPart image(ImageInput.Source source) { return new ChatPart(null, null, source); }
    public boolean isImage() { return text == null; }
}
