package dev.inferdroid.server;

import dev.inferdroid.engine.ToolCall;
import dev.inferdroid.engine.ToolDefinition;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import static dev.inferdroid.server.OpenAiProtocol.invalid;
import static dev.inferdroid.server.OpenAiProtocol.keys;
import static dev.inferdroid.server.OpenAiProtocol.optional;
import static dev.inferdroid.server.OpenAiProtocol.string;

/** Wire validation for client-executed functions, kept out of the inference lifecycle. */
final class OpenAiTools {
    private static final Set<String> TYPES = Set.of("string", "number", "integer", "boolean", "object", "array", "null");

    private OpenAiTools() {}

    static List<ToolDefinition> definitions(JSONObject request) throws OpenAiProtocol.ApiError, JSONException {
        if (!optional(request, "tools")) return List.of();
        JSONArray tools = request.optJSONArray("tools");
        if (tools == null || tools.length() > 128) throw invalid("tools must be an array of at most 128 functions.", "tools");
        if (tools.toString().getBytes(StandardCharsets.UTF_8).length > 256 * 1024) {
            throw invalid("Function definitions exceed 256 KiB.", "tools");
        }
        List<ToolDefinition> result = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (int i = 0; i < tools.length(); i++) {
            String param = "tools[" + i + "]";
            JSONObject tool = tools.optJSONObject(i);
            if (tool == null) throw invalid("Each tool must be a function object.", param);
            keys(tool, Set.of("type", "function"), param + ".");
            if (!"function".equals(tool.opt("type"))) throw invalid("Only client-executed function tools are supported.", param + ".type");
            JSONObject function = tool.optJSONObject("function");
            if (function == null) throw invalid("function must be an object.", param + ".function");
            keys(function, Set.of("name", "description", "parameters", "strict"), param + ".function.");
            String name = name(string(function, "name"), param + ".function.name");
            if (!names.add(name)) throw invalid("Function names must be unique.", param + ".function.name");
            String description = optional(function, "description") ? string(function, "description") : "";
            if (description.indexOf('\0') >= 0) throw invalid("Function descriptions cannot contain null characters.", param + ".function.description");
            if (optional(function, "strict") && !Boolean.FALSE.equals(function.opt("strict"))) {
                throw invalid("strict=true is unsupported; function arguments are generated without schema-constrained decoding.", param + ".function.strict");
            }
            JSONObject parameters = optional(function, "parameters") ? function.optJSONObject("parameters")
                    : new JSONObject().put("type", "object");
            if (parameters == null) throw invalid("parameters must be an object schema.", param + ".function.parameters");
            schema(parameters, param + ".function.parameters", 0);
            if (!"object".equals(parameters.opt("type"))) throw invalid("Function parameters must have type=object.", param + ".function.parameters.type");
            result.add(new ToolDefinition(name, description, parameters.toString()));
        }
        return List.copyOf(result);
    }

    // Gemma's embedded function template needs explicit types, object properties,
    // and homogeneous array items. Normalize missing object fields and nullable types.
    private static void schema(JSONObject value, String param, int depth) throws OpenAiProtocol.ApiError, JSONException {
        if (depth > 16) throw invalid("Function schema nesting exceeds 16 levels.", param);
        for (String key : List.of("$ref", "anyOf", "oneOf", "allOf")) {
            if (value.has(key)) throw invalid("Use explicit function parameter types instead of " + key + ".", param + "." + key);
        }
        Object type = value.opt("type");
        if (type instanceof JSONArray variants && variants.length() == 2) {
            String nonNull = null;
            for (int i = 0; i < variants.length(); i++) {
                Object variant = variants.get(i);
                if (variant instanceof String && !variant.equals("null")) nonNull = (String) variant;
            }
            if (nonNull != null && (variants.opt(0).equals("null") || variants.opt(1).equals("null"))) {
                value.put("type", nonNull).put("nullable", true);
                type = nonNull;
            }
        }
        if (!(type instanceof String) || !TYPES.contains(type)) throw invalid("Supply an explicit supported parameter type.", param + ".type");
        if (optional(value, "description") && !(value.opt("description") instanceof String)) throw invalid("description must be a string.", param + ".description");
        if (optional(value, "nullable") && !(value.opt("nullable") instanceof Boolean)) throw invalid("nullable must be a boolean.", param + ".nullable");
        if (optional(value, "enum") && !(value.opt("enum") instanceof JSONArray)) throw invalid("enum must be an array.", param + ".enum");
        if (type.equals("object")) {
            if (!value.has("properties")) value.put("properties", new JSONObject());
            JSONObject properties = value.optJSONObject("properties");
            if (properties == null || properties.length() > 256) throw invalid("properties must be an object with at most 256 entries.", param + ".properties");
            java.util.Iterator<String> propertyNames = properties.keys();
            while (propertyNames.hasNext()) {
                String property = propertyNames.next();
                JSONObject child = properties.optJSONObject(property);
                if (child == null || property.isEmpty() || property.indexOf('\0') >= 0) throw invalid("Each named property must be a schema object.", param + ".properties");
                schema(child, param + ".properties." + property, depth + 1);
            }
            if (!value.has("required")) value.put("required", new JSONArray());
            JSONArray required = value.optJSONArray("required");
            if (required == null) throw invalid("required must be an array of property names.", param + ".required");
            Set<String> seen = new HashSet<>();
            for (int i = 0; i < required.length(); i++) {
                Object name = required.get(i);
                if (!(name instanceof String) || !properties.has((String) name) || !seen.add((String) name)) throw invalid("required must name unique declared properties.", param + ".required");
            }
        } else if (type.equals("array")) {
            JSONObject items = value.optJSONObject("items");
            if (items == null) throw invalid("Array parameters require an items schema object.", param + ".items");
            schema(items, param + ".items", depth + 1);
        }
    }

    static List<ToolCall> historyCalls(JSONObject message, String param) throws OpenAiProtocol.ApiError, JSONException {
        if (!optional(message, "tool_calls")) return List.of();
        JSONArray calls = message.optJSONArray("tool_calls");
        if (calls == null || calls.length() < 1 || calls.length() > 128) throw invalid("tool_calls must contain 1–128 function calls.", param);
        List<ToolCall> result = new ArrayList<>();
        for (int i = 0; i < calls.length(); i++) {
            String location = param + "[" + i + "]";
            JSONObject call = calls.optJSONObject(i);
            if (call == null) throw invalid("Each tool call must be an object.", location);
            keys(call, Set.of("id", "type", "function"), location + ".");
            String id = id(string(call, "id"), location + ".id");
            if (!"function".equals(call.opt("type"))) throw invalid("Only function calls are supported.", location + ".type");
            JSONObject function = call.optJSONObject("function");
            if (function == null) throw invalid("function must be an object.", location + ".function");
            keys(function, Set.of("name", "arguments"), location + ".function.");
            String name = name(string(function, "name"), location + ".function.name");
            String arguments = string(function, "arguments");
            OpenAiProtocol.validateJson(arguments);
            if (!(new org.json.JSONTokener(arguments).nextValue() instanceof JSONObject)) throw invalid("Function arguments must encode one JSON object.", location + ".function.arguments");
            result.add(new ToolCall(id, name, arguments));
        }
        return List.copyOf(result);
    }

    static String name(String value, String param) throws OpenAiProtocol.ApiError {
        if (!value.matches("[A-Za-z0-9_-]{1,64}")) throw invalid("Function names must contain 1–64 letters, digits, underscores or hyphens.", param);
        return value;
    }

    static String id(String value, String param) throws OpenAiProtocol.ApiError {
        if (!value.matches("[A-Za-z0-9_-]{1,128}")) throw invalid("Tool call IDs must contain 1–128 letters, digits, underscores or hyphens.", param);
        return value;
    }

    static JSONArray wireCalls(List<ToolCall> calls, boolean streaming) throws JSONException {
        JSONArray result = new JSONArray();
        for (int i = 0; i < calls.size(); i++) {
            ToolCall call = calls.get(i);
            JSONObject value = new JSONObject().put("id", call.id).put("type", "function")
                    .put("function", new JSONObject().put("name", call.name).put("arguments", call.argumentsJson));
            if (streaming) value.put("index", i);
            result.put(value);
        }
        return result;
    }
}
