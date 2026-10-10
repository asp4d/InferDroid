package dev.inferdroid.speech;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/** Imports only the tested model bundle, using SAF and an atomic directory switch. */
public final class SpeechModelStore {
    public static final String MODEL_ID = "sherpa-onnx-whisper-tiny";
    private static final Map<String, String> HASHES = new LinkedHashMap<>();
    static {
        HASHES.put("tiny-encoder.int8.onnx", "d24fb083ae3b1041fc24e97971d60e280c9342201fbb67b0ab428a8b4a51a434");
        HASHES.put("tiny-decoder.int8.onnx", "d2fece8dd42771f1df975c6c0445770d0c292bf7547c2cae04a6c0cc57540925");
        HASHES.put("tiny-tokens.txt", "b34b360dbb493e781e479794586d661700670d65564001f23024971d1f2fa126");
    }
    public static final class ModelException extends IOException {
        public ModelException(String message) { super(message); }
    }
    private final Context context;
    private final File root;
    public SpeechModelStore(Context context) {
        this.context = context.getApplicationContext();
        root = new File(context.getFilesDir(), "speech");
        File previous = new File(root, "previous");
        if (!directory().exists() && previous.isDirectory()) {
            // A process kill between the two renames must preserve the old bundle.
            if (!previous.renameTo(directory())) android.util.Log.e("InferDroid", "Speech model recovery failed");
        }
    }
    public File directory() { return new File(root, "whisper-tiny"); }
    public boolean isAvailable() {
        for (String name : HASHES.keySet()) if (!new File(directory(), name).isFile()) return false;
        return true;
    }
    public void requireAvailable() throws ModelException {
        if (!isAvailable()) throw new ModelException("Import the Whisper tiny speech model folder in InferDroid first.");
    }
    public void importTree(Uri tree, AtomicBoolean cancelled) throws Exception {
        if (!root.isDirectory() && !root.mkdirs()) throw new ModelException("Cannot create private speech model storage.");
        File candidate = new File(root, "importing");
        File previous = new File(root, "previous");
        // Recover an interrupted previous rename before doing new work.
        if (!directory().exists() && previous.exists() && !previous.renameTo(directory())) {
            throw new ModelException("Cannot restore the previous speech model.");
        }
        remove(candidate);
        if (!candidate.mkdir()) throw new ModelException("Cannot prepare speech model import.");
        try {
            Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree,
                    DocumentsContract.getTreeDocumentId(tree));
            Map<String, Uri> files = new LinkedHashMap<>();
            try (Cursor cursor = context.getContentResolver().query(children,
                    new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME},
                    null, null, null)) {
                if (cursor == null) throw new ModelException("Cannot read the selected speech model folder.");
                while (cursor.moveToNext()) {
                    String name = cursor.getString(1);
                    if (HASHES.containsKey(name)) {
                        if (files.put(name, DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(0))) != null) {
                            throw new ModelException("The folder contains duplicate speech model filenames.");
                        }
                    }
                }
            }
            if (files.size() != HASHES.size()) throw new ModelException(
                    "Select the folder containing tiny-encoder.int8.onnx, tiny-decoder.int8.onnx, and tiny-tokens.txt.");
            byte[] buffer = new byte[128 * 1024];
            long total = 0;
            for (Map.Entry<String, String> entry : HASHES.entrySet()) {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                try (InputStream input = context.getContentResolver().openInputStream(files.get(entry.getKey()));
                     FileOutputStream output = new FileOutputStream(new File(candidate, entry.getKey()))) {
                    if (input == null) throw new ModelException("The provider returned no speech model data.");
                    int count;
                    while ((count = input.read(buffer)) >= 0) {
                        if (cancelled.get()) throw new ModelException("Speech model import cancelled.");
                        total += count;
                        if (total > 160L * 1024 * 1024) throw new ModelException("Speech model files exceed the import size limit.");
                        output.write(buffer, 0, count);
                        digest.update(buffer, 0, count);
                    }
                    output.getFD().sync();
                }
                StringBuilder hex = new StringBuilder();
                for (byte value : digest.digest()) hex.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
                if (!entry.getValue().equals(hex.toString())) throw new ModelException(
                        "Speech model checksum mismatch. Use the verified Whisper tiny int8 bundle from the README.");
            }
            if (cancelled.get()) throw new ModelException("Speech model import cancelled.");
            remove(previous);
            if (directory().exists() && !directory().renameTo(previous)) throw new ModelException("Cannot replace the speech model.");
            if (!candidate.renameTo(directory())) {
                if (previous.exists() && !previous.renameTo(directory())) throw new ModelException("Cannot restore the previous speech model.");
                throw new ModelException("Cannot finish speech model import.");
            }
            remove(previous);
        } finally { remove(candidate); }
    }
    private static void remove(File file) throws IOException {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children == null) throw new IOException("Cannot inspect temporary speech model storage.");
            for (File child : children) remove(child);
        }
        if (file.exists() && !file.delete()) throw new IOException("Cannot remove temporary speech model storage.");
    }
}
