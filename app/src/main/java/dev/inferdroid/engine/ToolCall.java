package dev.inferdroid.engine;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** One complete function call. Arguments are a JSON object encoded as ordinary UTF-8. */
public final class ToolCall {
    public final String id;
    public final String name;
    public final String argumentsJson;

    public ToolCall(String id, String name, String argumentsJson) {
        this.id = id;
        this.name = name;
        this.argumentsJson = argumentsJson;
    }

    static List<ToolCall> fromNative(byte[] bytes) {
        try {
            JSONArray calls = new JSONArray(new String(bytes, StandardCharsets.UTF_8));
            List<ToolCall> result = new ArrayList<>();
            for (int i = 0; i < calls.length(); i++) {
                JSONObject function = calls.getJSONObject(i).getJSONObject("function");
                result.add(new ToolCall("call_" + UUID.randomUUID().toString().replace("-", ""),
                        function.getString("name"), function.getJSONObject("arguments").toString()));
            }
            return List.copyOf(result);
        } catch (JSONException error) {
            throw new IllegalArgumentException("Invalid native function-call result.", error);
        }
    }
}
