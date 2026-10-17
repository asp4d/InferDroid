package dev.inferdroid.images;

import android.content.Context;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/** Stream operations available on every supported Android version. */
final class ImageFiles {
    private ImageFiles() { }

    static void copy(InputStream input, OutputStream output) throws IOException {
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
    }

    static String asset(Context context, String name) throws IOException {
        try (InputStream input = context.getAssets().open(name);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            copy(input, output);
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
