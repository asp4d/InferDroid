package dev.inferdroid.engine;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ImageDecoder;
import android.util.Base64;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.Set;

/** Bounded, still-image attachment. Only normalized PNG bytes reach LiteRT-LM. */
public final class ImageInput {
    public static final int MAX_BYTES = 2 * 1024 * 1024;
    public static final int MAX_IMAGES = 4;
    public static final int MAX_TOTAL_BYTES = 4 * 1024 * 1024;
    public static final int MAX_EDGE = 1024;
    private static final Set<String> TYPES = Set.of("image/png", "image/jpeg", "image/webp");
    private final byte[] png;
    public final int width;
    public final int height;
    public final int sourceBytes;

    @FunctionalInterface public interface Source { InputStream open() throws IOException; }

    public static final class InvalidImage extends IOException {
        public final boolean tooLarge;
        InvalidImage(String message, boolean tooLarge) { super(message); this.tooLarge = tooLarge; }
    }

    private ImageInput(byte[] png, int width, int height, int sourceBytes) {
        this.png = png;
        this.width = width;
        this.height = height;
        this.sourceBytes = sourceBytes;
    }

    public static ImageInput fromDataUrl(String url) throws IOException {
        int comma = url.indexOf(',');
        if (comma < 0 || !url.startsWith("data:") || !url.substring(0, comma).endsWith(";base64")) {
            throw new InvalidImage("Use a base64 data URL for a local PNG, JPEG, or WebP image. Remote URLs and file paths are unsupported.", false);
        }
        String mime = url.substring(5, comma - 7);
        if (!TYPES.contains(mime)) throw new InvalidImage("Supported image MIME types: image/png, image/jpeg, image/webp.", false);
        String encoded = url.substring(comma + 1);
        if (encoded.length() > ((MAX_BYTES + 2) / 3) * 4) {
            throw new InvalidImage("Each encoded image must be at most 2 MiB before base64 encoding.", true);
        }
        // Basic, padded RFC 4648 base64 only. Android's decoder otherwise accepts whitespace.
        if (!validBase64(encoded)) {
            throw new InvalidImage("Image data must be valid padded base64 without whitespace.", false);
        }
        byte[] bytes;
        try { bytes = Base64.decode(encoded, Base64.NO_WRAP); }
        catch (IllegalArgumentException error) { throw new InvalidImage("Invalid image base64.", false); }
        return decode(bytes, mime);
    }

    private static boolean validBase64(String text) {
        if (text.isEmpty() || text.length() % 4 != 0) return false;
        int end = text.length();
        if (text.charAt(end - 1) == '=') end--;
        if (text.charAt(end - 1) == '=') end--;
        for (int i = 0; i < end; i++) {
            char value = text.charAt(i);
            if (!(value >= 'A' && value <= 'Z' || value >= 'a' && value <= 'z'
                    || value >= '0' && value <= '9' || value == '+' || value == '/')) return false;
        }
        return true;
    }

    public static ImageInput read(Source source) throws IOException {
        try (InputStream input = source.open(); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            if (input == null) throw new InvalidImage("The image provider returned no data.", false);
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (bytes.size() + count > MAX_BYTES) throw new InvalidImage("Each image must be at most 2 MiB.", true);
                bytes.write(buffer, 0, count);
            }
            return decode(bytes.toByteArray(), null);
        }
    }

    public static ImageInput decode(byte[] bytes, String expectedMime) throws IOException {
        if (bytes.length == 0) throw new InvalidImage("Image data is empty.", false);
        if (bytes.length > MAX_BYTES) throw new InvalidImage("Each image must be at most 2 MiB.", true);
        Bitmap decoded = null;
        Bitmap normalized = null;
        try {
            decoded = ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes)), (decoder, info, source) -> {
                String mime = info.getMimeType();
                if (!TYPES.contains(mime) || expectedMime != null && !expectedMime.equals(mime)) {
                    throw new IllegalArgumentException("The image format is unsupported or does not match its MIME type.");
                }
                if (info.isAnimated()) throw new IllegalArgumentException("Animated images are unsupported; send a still image.");
                int width = info.getSize().getWidth();
                int height = info.getSize().getHeight();
                if (width < 1 || height < 1 || width > 8192 || height > 8192 || (long) width * height > 16_000_000) {
                    throw new ImageBoundsException();
                }
                double scale = Math.min(1.0, (double) MAX_EDGE / Math.max(width, height));
                decoder.setTargetSize(Math.max(1, (int) Math.round(width * scale)), Math.max(1, (int) Math.round(height * scale)));
                decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                decoder.setOnPartialImageListener(error -> false);
            });
            // ImageDecoder applies EXIF orientation. Flatten transparency on white,
            // then re-encode without original metadata or unsupported native codecs.
            normalized = Bitmap.createBitmap(decoded.getWidth(), decoded.getHeight(), Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(normalized);
            canvas.drawColor(Color.WHITE);
            canvas.drawBitmap(decoded, 0, 0, null);
            try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                if (!normalized.compress(Bitmap.CompressFormat.PNG, 100, output)) throw new IOException("Cannot normalize the image.");
                return new ImageInput(output.toByteArray(), normalized.getWidth(), normalized.getHeight(), bytes.length);
            }
        } catch (ImageBoundsException error) {
            throw new InvalidImage("Images must be at most 16 megapixels and 8192 pixels on either side.", true);
        } catch (IllegalArgumentException | ImageDecoder.DecodeException error) {
            throw new InvalidImage(error instanceof IllegalArgumentException ? error.getMessage() : "Cannot decode this image; send a complete PNG, JPEG, or WebP still image.", false);
        } finally {
            if (decoded != null) decoded.recycle();
            if (normalized != null) normalized.recycle();
        }
    }

    private static final class ImageBoundsException extends IllegalArgumentException { }

    String base64Png() { return Base64.encodeToString(png, Base64.NO_WRAP); }
}
