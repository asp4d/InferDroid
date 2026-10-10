package dev.inferdroid.tts;

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
public final class TtsModelStore {
    public static final String MODEL_ID = "sherpa-onnx-supertonic-3-int8";
    private static final Map<String, String> HASHES = new LinkedHashMap<>();
    static {
        HASHES.put("duration_predictor.int8.onnx", "c3eb91414d5ff8a7a239b7fe9e34e7e2bf8a8140d8375ffb14718b1c639325db");
        HASHES.put("text_encoder.int8.onnx", "c7befd5ea8c3119769e8a6c1486c4edc6a3bc8365c67621c881bbb774b9902ff");
        HASHES.put("vector_estimator.int8.onnx", "20cd86fa5c6effedfda0e7cffe5b0569ca401c440a0c3a1d72bf39286c0db3fd");
        HASHES.put("vocoder.int8.onnx", "e923d60f53f95eb1ce235f1dc33ec56d9c057823c96fa6f8acf98f32b0da6152");
        HASHES.put("tts.json", "42078d3aef1cd43ab43021f3c54f47d2d75ceb4e75f627f118890128b06a0d09");
        HASHES.put("unicode_indexer.bin", "8402ca48e5189a8950138580b0fff64db6f072f24ac07cd54ba8b2fbb9883b30");
        HASHES.put("voice.bin", "67d5209b0ee8ce6c74105ffbe12fe6a7628aea3b4ba2fcb308a4a67938a93ce8");
    }
    public static final class ModelException extends IOException {
        public ModelException(String message) { super(message); }
    }
    private final Context context;
    private final File root;
    public TtsModelStore(Context context) {
        this.context = context.getApplicationContext();
        root = new File(context.getFilesDir(), "tts");
        File previous = new File(root, "previous");
        if (!directory().exists() && previous.isDirectory()) {
            // A process kill between the two renames must preserve the old bundle.
            if (!previous.renameTo(directory())) android.util.Log.e("InferDroid", "TTS model recovery failed");
        }
    }
    public File directory() { return new File(root, "supertonic-3"); }
    public boolean isAvailable() {
        for (String name : HASHES.keySet()) if (!new File(directory(), name).isFile()) return false;
        return true;
    }
    public void requireAvailable() throws ModelException {
        if (!isAvailable()) throw new ModelException("Import the Supertonic 3 TTS model folder in InferDroid first.");
    }
    public void importTree(Uri tree, AtomicBoolean cancelled) throws Exception {
        if (!root.isDirectory() && !root.mkdirs()) throw new ModelException("Cannot create private TTS model storage.");
        File candidate = new File(root, "importing");
        File previous = new File(root, "previous");
        // Recover an interrupted previous rename before doing new work.
        if (!directory().exists() && previous.exists() && !previous.renameTo(directory())) {
            throw new ModelException("Cannot restore the previous TTS model.");
        }
        remove(candidate);
        if (!candidate.mkdir()) throw new ModelException("Cannot prepare TTS model import.");
        try {
            Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree,
                    DocumentsContract.getTreeDocumentId(tree));
            Map<String, Uri> files = new LinkedHashMap<>();
            try (Cursor cursor = context.getContentResolver().query(children,
                    new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME},
                    null, null, null)) {
                if (cursor == null) throw new ModelException("Cannot read the selected TTS model folder.");
                while (cursor.moveToNext()) {
                    String name = cursor.getString(1);
                    if (HASHES.containsKey(name)) {
                        if (files.put(name, DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(0))) != null) {
                            throw new ModelException("The folder contains duplicate TTS model filenames.");
                        }
                    }
                }
            }
            if (files.size() != HASHES.size()) throw new ModelException(
                    "Select the Supertonic 3 int8 folder containing all seven model/data files.");
            byte[] buffer = new byte[128 * 1024];
            long total = 0;
            for (Map.Entry<String, String> entry : HASHES.entrySet()) {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                try (InputStream input = context.getContentResolver().openInputStream(files.get(entry.getKey()));
                     FileOutputStream output = new FileOutputStream(new File(candidate, entry.getKey()))) {
                    if (input == null) throw new ModelException("The provider returned no TTS model data.");
                    int count;
                    while ((count = input.read(buffer)) >= 0) {
                        if (cancelled.get()) throw new ModelException("TTS model import cancelled.");
                        total += count;
                        if (total > 180L * 1024 * 1024) throw new ModelException("TTS model files exceed the import size limit.");
                        output.write(buffer, 0, count);
                        digest.update(buffer, 0, count);
                    }
                    output.getFD().sync();
                }
                StringBuilder hex = new StringBuilder();
                for (byte value : digest.digest()) hex.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
                if (!entry.getValue().equals(hex.toString())) throw new ModelException(
                        "TTS model checksum mismatch. Use the verified Supertonic 3 int8 bundle from the README.");
            }
            if (cancelled.get()) throw new ModelException("TTS model import cancelled.");
            remove(previous);
            if (directory().exists() && !directory().renameTo(previous)) throw new ModelException("Cannot replace the TTS model.");
            if (!candidate.renameTo(directory())) {
                if (previous.exists() && !previous.renameTo(directory())) throw new ModelException("Cannot restore the previous TTS model.");
                throw new ModelException("Cannot finish TTS model import.");
            }
            remove(previous);
        } finally { remove(candidate); }
    }
    private static void remove(File file) throws IOException {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children == null) throw new IOException("Cannot inspect temporary TTS model storage.");
            for (File child : children) remove(child);
        }
        if (file.exists() && !file.delete()) throw new IOException("Cannot remove temporary TTS model storage.");
    }
}
