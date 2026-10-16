package dev.inferdroid.server;

import dev.inferdroid.images.ImageController;
import dev.inferdroid.images.ImageModels;
import org.json.*;
import java.util.*;
import java.nio.file.Files;
import android.util.Base64;

final class ImageProtocol {
    static final class Request {
        final String prompt;final long seed;
        Request(String prompt,long seed) {this.prompt=prompt;this.seed=seed;}
    }
    static Request parse(String body) throws Exception {
        OpenAiProtocol.validateJson(body);
        JSONObject json=new JSONObject(body);
        Set<String> allowed=Set.of("model","prompt","n","size","response_format","seed","user");
        for(Iterator<String> names=json.keys();names.hasNext();) {String name=names.next();if(!allowed.contains(name)) throw invalid("Unsupported image parameter: "+name,name);}
        if(json.has("model")&&!ImageModels.MODEL_ID.equals(json.opt("model"))) throw new OpenAiProtocol.ApiError(404,"Unknown local image model.","invalid_request_error","model_not_found","model");
        Object prompt=json.opt("prompt");
        if(!(prompt instanceof String)||((String)prompt).isBlank()||((String)prompt).length()>8192) throw invalid("prompt must contain 1–8192 characters.","prompt");
        if(json.has("n")&&(!(json.opt("n") instanceof Integer)||json.getInt("n")!=1)) throw invalid("This model generates one image per request; use n=1.","n");
        if(json.has("size")&&!"512x512".equals(json.opt("size"))) throw invalid("This compiled model supports size=512x512.","size");
        if(json.has("response_format")&&!"b64_json".equals(json.opt("response_format"))) throw invalid("Use response_format=b64_json.","response_format");
        long seed=new java.security.SecureRandom().nextLong();
        if(json.has("seed")) {
            Object value=json.opt("seed");
            if(!(value instanceof Number)||!value.toString().matches("-?[0-9]+")) throw invalid("seed must be a signed 64-bit integer.","seed");
            try {seed=Long.parseLong(value.toString());} catch(NumberFormatException error) {throw invalid("seed must be a signed 64-bit integer.","seed");}
        }
        return new Request((String)prompt,seed);
    }
    static JSONObject models(JSONObject response,boolean available) throws Exception {
        if(available) response.getJSONArray("data").put(new JSONObject().put("id",ImageModels.MODEL_ID).put("object","model")
            .put("created",0).put("owned_by","inferdroid").put("architecture",new JSONObject()
                .put("input_modalities",new JSONArray().put("text")).put("output_modalities",new JSONArray().put("image"))));
        return response;
    }
    static JSONObject result(ImageController.Result result) throws Exception {
        if(result.error!=null||result.image==null) throw new OpenAiProtocol.ApiError(503,result.error==null?"No image was generated.":result.error,
            "server_error",result.cancelled?"image_cancelled":"image_generation_failed",null);
        byte[] png=Files.readAllBytes(result.image.toPath());
        return new JSONObject().put("created",System.currentTimeMillis()/1000).put("data",new JSONArray().put(new JSONObject().put("b64_json",Base64.encodeToString(png,Base64.NO_WRAP))));
    }
    private static OpenAiProtocol.ApiError invalid(String message,String param) {return new OpenAiProtocol.ApiError(400,message,"invalid_request_error","invalid_parameter",param);}
}
