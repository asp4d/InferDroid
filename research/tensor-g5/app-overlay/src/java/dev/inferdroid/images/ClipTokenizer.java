package dev.inferdroid.images;

import android.content.Context;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.*;
import java.util.regex.*;
import org.json.JSONObject;

/** Byte BPE matching the pinned Transformers 5 CLIP tokenizer configuration. */
public final class ClipTokenizer {
    private final JSONObject vocabulary;
    private final Map<String,Integer> ranks=new HashMap<>();
    private final Map<String,List<String>> cache=new HashMap<>();
    private final String[] bytes=new String[256];
    private static final Pattern TOKENS=Pattern.compile("<\\|startoftext\\|>|<\\|endoftext\\|>|'s|'t|'re|'ve|'m|'ll|'d|[\\p{L}]+|[\\p{N}]|[^\\s\\p{L}\\p{N}]+");
    private static final Pattern SPACE=Pattern.compile("\\s+");
    public ClipTokenizer(Context context) throws Exception {
        vocabulary=new JSONObject(new String(context.getAssets().open("image-research/vocab.json").readAllBytes(),StandardCharsets.UTF_8));
        List<Integer> visible=new ArrayList<>();
        for(int i=33;i<=126;i++) visible.add(i);
        for(int i=161;i<=172;i++) visible.add(i);
        for(int i=174;i<=255;i++) visible.add(i);
        int extra=256;
        for(int i=0;i<256;i++) bytes[i]=new String(Character.toChars(visible.contains(i)?i:extra++));
        String merges=new String(context.getAssets().open("image-research/merges.txt").readAllBytes(),StandardCharsets.UTF_8);
        int rank=0;
        for(String line:merges.split("\\R")) if(!line.isBlank()&&!line.startsWith("#")) {
            String[] pair=line.trim().split(" ");
            if(pair.length!=2) throw new IllegalArgumentException("Invalid local tokenizer merges.");
            ranks.put(pair[0]+"\u0000"+pair[1],rank++);
        }
    }
    public int[] encode(String input) throws Exception {
        if(input==null||input.length()>8192||input.isBlank()) throw new IllegalArgumentException("Enter a prompt of 1–8192 characters.");
        String normalized=SPACE.matcher(Normalizer.normalize(input,Normalizer.Form.NFC)).replaceAll(" ");
        StringBuilder lower=new StringBuilder();
        for(int value:normalized.codePoints().toArray()) lower.append(new String(Character.toChars(value)).toLowerCase(Locale.ROOT));
        String text=lower.toString();
        List<Integer> ids=new ArrayList<>(); ids.add(vocabulary.getInt("<|startoftext|>"));
        Matcher matcher=TOKENS.matcher(text);
        while(matcher.find()) {
            String token=matcher.group();
            if(token.equals("<|startoftext|>")||token.equals("<|endoftext|>")) { ids.add(vocabulary.getInt(token)); continue; }
            StringBuilder encoded=new StringBuilder();
            for(byte value:token.getBytes(StandardCharsets.UTF_8)) encoded.append(bytes[value&255]);
            for(String piece:bpe(encoded.toString())) ids.add(vocabulary.optInt(piece,49407));
        }
        int[] result=new int[77]; Arrays.fill(result,vocabulary.getInt("<|endoftext|>"));
        for(int i=0;i<Math.min(ids.size(),76);i++) result[i]=ids.get(i);
        return result;
    }
    private List<String> bpe(String text) {
        if(cache.containsKey(text)) return cache.get(text);
        List<String> word=new ArrayList<>();
        for(int value:text.codePoints().toArray()) word.add(new String(Character.toChars(value)));
        word.set(word.size()-1,word.get(word.size()-1)+"</w>");
        while(word.size()>1) {
            int best=Integer.MAX_VALUE; String first=null,second=null;
            for(int i=0;i<word.size()-1;i++) {
                int rank=ranks.getOrDefault(word.get(i)+"\u0000"+word.get(i+1),Integer.MAX_VALUE);
                if(rank<best) {best=rank;first=word.get(i);second=word.get(i+1);}
            }
            if(first==null) break;
            List<String> joined=new ArrayList<>();
            for(int i=0;i<word.size();i++) {
                if(i+1<word.size()&&word.get(i).equals(first)&&word.get(i+1).equals(second)) joined.add(word.get(i++)+second);
                else joined.add(word.get(i));
            }
            word=joined;
        }
        if(cache.size()>4096) cache.clear();
        cache.put(text,word); return word;
    }
}
