package dev.inferdroid.images;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;
import org.json.*;

public final class ImageModels {
    public static final String MODEL_ID="sdxs-512-dreamshaper-tensor-g5";
    private final Context context;
    final JSONObject profile;
    public ImageModels(Context context) throws Exception {
        this.context=context;
        try(InputStream stream=context.getAssets().open("image-research/profile.json")) {
            profile=new JSONObject(new String(stream.readAllBytes(),StandardCharsets.UTF_8));
        }
    }
    public File directory() { return new File(context.getFilesDir(),"image-models/sdxs-512-g5"); }
    public boolean available() {
        try {
            JSONObject files=profile.getJSONObject("files");
            for(Iterator<String> names=files.keys();names.hasNext();) {
                String name=names.next(); File path=new File(directory(),name);
                if(!path.isFile()||path.length()!=files.getJSONObject(name).getLong("bytes")) return false;
            }
            return true;
        } catch(Exception error) {return false;}
    }
    void verify(File folder) throws Exception {
        JSONObject files=profile.getJSONObject("files");
        for(Iterator<String> names=files.keys();names.hasNext();) {
            String name=names.next(); File path=new File(folder,name);
            if(!path.isFile()||path.length()!=files.getJSONObject(name).getLong("bytes")) throw new IOException("Import the complete local SDXS model folder first.");
            MessageDigest digest=MessageDigest.getInstance("SHA-256");
            try(InputStream input=new FileInputStream(path)) {
                byte[] buffer=new byte[1024*1024];int count;
                while((count=input.read(buffer))!=-1) digest.update(buffer,0,count);
            }
            StringBuilder hex=new StringBuilder(); for(byte value:digest.digest()) hex.append(String.format(Locale.ROOT,"%02x",value&255));
            if(!hex.toString().equals(files.getJSONObject(name).getString("sha256"))) throw new IOException("The local SDXS model checksum does not match this research build.");
        }
    }
    public void importTree(Uri tree) throws Exception {
        Map<String,Uri> sources=new HashMap<>();
        Uri children=DocumentsContract.buildChildDocumentsUriUsingTree(tree,DocumentsContract.getTreeDocumentId(tree));
        try(Cursor cursor=context.getContentResolver().query(children,new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME},null,null,null)) {
            if(cursor==null) throw new IOException("Cannot read the selected model folder.");
            while(cursor.moveToNext()) sources.put(cursor.getString(1),DocumentsContract.buildDocumentUriUsingTree(tree,cursor.getString(0)));
        }
        File parent=directory().getParentFile(); if(!parent.isDirectory()&&!parent.mkdirs()) throw new IOException("Cannot create image model storage.");
        File temporary=new File(parent,"import-"+UUID.randomUUID()); if(!temporary.mkdir()) throw new IOException("Cannot create image import staging.");
        try {
            for(Iterator<String> names=profile.getJSONObject("files").keys();names.hasNext();) {
                String name=names.next(); Uri uri=sources.get(name);
                if(uri==null) throw new IOException("Selected folder is missing "+name+".");
                try(InputStream input=context.getContentResolver().openInputStream(uri);OutputStream output=new FileOutputStream(new File(temporary,name))) {
                    if(input==null) throw new IOException("Cannot open a selected image model.");
                    input.transferTo(output);
                }
            }
            verify(temporary);
            File backup=new File(parent,"previous-"+UUID.randomUUID());
            boolean old=directory().exists();
            if(old) Files.move(directory().toPath(),backup.toPath());
            try { Files.move(temporary.toPath(),directory().toPath()); }
            catch(Exception error) {if(old) Files.move(backup.toPath(),directory().toPath());throw error;}
            remove(backup);
        } finally {remove(temporary);}
    }
    private static void remove(File path) throws IOException {
        if(path.isDirectory()) {File[] children=path.listFiles();if(children!=null) for(File child:children) remove(child);}
        if(path.exists()&&!path.delete()) throw new IOException("Cannot remove temporary image import files.");
    }
}
