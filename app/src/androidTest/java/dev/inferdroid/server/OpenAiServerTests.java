package dev.inferdroid.server;

import android.app.Instrumentation;
import android.os.Looper;
import dev.inferdroid.engine.EngineManager;
import dev.inferdroid.engine.GenerationListener;
import dev.inferdroid.engine.GenerationRequest;
import dev.inferdroid.engine.GenerationResult;
import dev.inferdroid.engine.InferenceEngine;
import dev.inferdroid.engine.ImageInput;
import dev.inferdroid.engine.ToolCall;
import java.util.List;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.util.Base64;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;

/** Actual loopback sockets and EngineManager admission; no JNI or network dependencies. */
public final class OpenAiServerTests {
    private static final String KEY = "instrumentation-local-key";
    private static final String CHAT = "{\"model\":\"" + OpenAiServer.MODEL_ID
            + "\",\"messages\":[{\"role\":\"user\",\"content\":\"Hello\"}]}";
    private final Instrumentation instrumentation;

    public OpenAiServerTests(Instrumentation instrumentation) { this.instrumentation = instrumentation; }

    public void run(String name) throws Exception {
        switch (name) {
            case "apiAuthenticationAndValidation" -> authenticationAndValidation();
            case "apiChatHistoryAndErrors" -> chatHistoryAndErrors();
            case "apiLiveStreaming" -> liveStreaming();
            case "apiBusyDisconnectAndRecovery" -> busyDisconnectAndRecovery();
            case "apiCorsAndPortConflict" -> corsAndPortConflict();
            case "apiStopAndRestart" -> stopAndRestart();
            case "imageDecodeBoundsAndFormats" -> imageDecode();
            case "apiImagePartsAndHistory" -> imageParts();
            case "apiImageValidationAndLimits" -> imageValidation();
            case "apiImageStreaming" -> liveStreaming(imageChat(imageUrl(Bitmap.CompressFormat.PNG)));
            case "apiImageDisconnectAndRecovery" -> busyDisconnectAndRecovery(imageChat(imageUrl(Bitmap.CompressFormat.PNG)));
            case "apiThinkingParametersAndHistory" -> thinkingParametersAndHistory();
            case "apiThinkingStreaming" -> thinkingStreaming();
            case "apiThinkingTruncationAndRecovery" -> thinkingTruncationAndRecovery();
            case "apiThinkingDisconnectAndRecovery" -> busyDisconnectAndRecovery(thinkingChat());
            case "apiClientToolsBufferedAndHistory" -> clientToolsBufferedAndHistory();
            case "apiClientToolsStreamingAndTruncation" -> clientToolsStreamingAndTruncation();
            case "apiClientToolsValidation" -> clientToolsValidation();
            case "apiReasoningCompatibilityControls" -> reasoningCompatibilityControls();
            case "apiJsonObjectBufferedAndHistory" -> jsonObjectBufferedAndHistory();
            case "apiJsonObjectValidationAndRecovery" -> jsonObjectValidationAndRecovery();
            case "apiJsonObjectStreaming" -> jsonObjectStreaming();
            case "apiJsonObjectRepair" -> jsonObjectRepair();
            case "apiModelCapabilities" -> modelCapabilities();
            case "apiRepeatedVersionPrefix" -> repeatedVersionPrefix();
            default -> throw new AssertionError("Unknown test: " + name);
        }
    }

    private void jsonObjectBufferedAndHistory() throws Exception {
        try (Fixture fixture = new Fixture(false, false, true)) {
            String reply = "{\"status\":\"SUCCESS\",\"dish_name\":\"Toast 🍞\",\"calories\":120}";
            fixture.engine.replyText = reply;
            JSONObject request = jsonChat().put("enable_thinking", false).put("temperature", 0.2)
                    .put("messages", new JSONArray()
                            .put(new JSONObject().put("role", "developer").put("content", "Keep the supplied meal schema."))
                            .put(new JSONObject().put("role", "user").put("content", new JSONArray()
                                    .put(new JSONObject().put("type", "text").put("text", "One slice of toast.")))));
            Response response = fixture.request("POST", "/v1/chat/completions", request.toString(), auth());
            check(response.status == 200, "FitBuddy text request failed: " + response.body);
            String content = response.json().getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content");
            check(content.equals(reply) && new JSONObject(content).getInt("calories") == 120, "JSON content or Unicode changed");
            GenerationRequest accepted = fixture.engine.lastRequest;
            check(accepted.maxOutputTokens == 2048 && !accepted.enableThinking && accepted.temperature == 0.2,
                    "JSON defaults or explicit sampling/thinking controls lost");
            check(accepted.messages.size() == 2 && accepted.messages.get(0).role.equals("system")
                    && accepted.messages.get(0).text.startsWith("Keep the supplied meal schema.")
                    && accepted.messages.get(1).text.equals("One slice of toast."), "JSON mode lost instructions or input");
            request = new JSONObject(imageChat(imageUrl(Bitmap.CompressFormat.JPEG)))
                    .put("response_format", new JSONObject().put("type", "json_object"))
                    .put("max_completion_tokens", 1024);
            check(fixture.request("POST", "/v1/chat/completions", request.toString(), auth()).status == 200,
                    "FitBuddy photo request failed");
            accepted = fixture.engine.lastRequest;
            check(accepted.maxOutputTokens == 1024 && accepted.enableThinking
                    && accepted.messages.get(1).parts.stream().anyMatch(part -> part.isImage()),
                    "JSON mode lost explicit cap, saved thinking default, or image");
            fixture.engine.replyText = null;
            request = new JSONObject(CHAT).put("enable_thinking", false).put("response_format", new JSONObject().put("type", "text"));
            check(fixture.request("POST", "/v1/chat/completions", request.toString(), auth()).status == 200,
                    "normal text request failed after JSON");
            accepted = fixture.engine.lastRequest;
            check(accepted.messages.size() == 1 && accepted.messages.get(0).text.equals("Hello")
                    && accepted.maxOutputTokens == 256 && !accepted.enableThinking && fixture.engine.loads.get() == 1,
                    "JSON instructions/defaults leaked to ordinary chat or reloaded the engine");
        }
    }

    private void jsonObjectValidationAndRecovery() throws Exception {
        try (Fixture fixture = new Fixture(false, false)) {
            for (Object format : new Object[]{"json_object", new JSONObject(),
                    new JSONObject().put("type", "json_schema"), new JSONObject().put("type", 1),
                    new JSONObject().put("type", "json_object").put("schema", new JSONObject())}) {
                Response response = fixture.request("POST", "/v1/chat/completions",
                        new JSONObject(CHAT).put("response_format", format).toString(), auth());
                check(response.status == 400 && response.json().getJSONObject("error").getString("param").equals("response_format"),
                        "unsupported response format accepted");
            }
            check(fixture.engine.generations.get() == 0 && fixture.engine.loads.get() == 0, "invalid format touched inference");
            for (String text : new String[]{"", "[]", "null", "{\"status\":", "{} commentary", "{}{}",
                    "```json\n{}\n```", "{'status':'SUCCESS'}", "{\"a\":1,}", "{\"a\":1,\"a\":2}"}) {
                fixture.engine.replyText = text;
                Response response = fixture.request("POST", "/v1/chat/completions", jsonChat().toString(), auth());
                check(response.status == 500 && response.json().getJSONObject("error").getString("code").equals("invalid_json_output"),
                        "invalid model output published as JSON: " + text);
            }
            fixture.engine.replyText = "{\"status\":";
            Response truncated = fixture.request("POST", "/v1/chat/completions", jsonChat().put("max_tokens", 4).toString(), auth());
            check(truncated.status == 500 && truncated.body.contains("output token limit"), "truncated JSON returned as a completion");
            fixture.engine.replyText = " {\"nested\":{\"ok\":true},\"values\":[null,1,\"é\"]} \n";
            check(fixture.request("POST", "/v1/chat/completions", jsonChat().toString(), auth()).status == 200
                    && fixture.engine.loads.get() == 1, "invalid JSON broke engine recovery");
        }
    }

    private void jsonObjectStreaming() throws Exception {
        try (Fixture fixture = new Fixture(true, false); Socket client = fixture.connect()) {
            String reply = "{\"status\":\"SUCCESS\",\"dish_name\":\"Toast 🍞\"}";
            fixture.engine.replyText = reply;
            JSONObject request = jsonChat().put("stream", true).put("enable_thinking", true)
                    .put("stream_options", new JSONObject().put("include_usage", true));
            fixture.send(client, "POST", "/v1/chat/completions", request.toString(), auth());
            BufferedReader reader = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
            check(reader.readLine().contains("200"), "JSON stream status");
            while (!reader.readLine().isEmpty()) { }
            String line;
            do { line = reader.readLine(); } while (line != null && !line.startsWith("data: "));
            check(line != null && line.contains("assistant"), "JSON stream missing initial assistant role");
            check(reader.readLine().isEmpty(), "invalid SSE delimiter");
            await(fixture.engine.entered);
            client.setSoTimeout(150);
            try {
                String early = reader.readLine();
                check(early == null || !early.startsWith("data: "), "JSON content escaped before validation");
            } catch (java.net.SocketTimeoutException expected) { }
            client.setSoTimeout(5000);
            fixture.engine.allowFinish.countDown();
            StringBuilder content = new StringBuilder(), reasoning = new StringBuilder();
            boolean done = false, finish = false, usage = false;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("data: ")) continue;
                if (line.equals("data: [DONE]")) { done = true; break; }
                JSONObject frame = new JSONObject(line.substring(6));
                check(!frame.has("error"), "valid JSON stream failed: " + line);
                if (frame.optJSONObject("usage") != null) usage = true;
                JSONArray choices = frame.getJSONArray("choices");
                if (choices.length() == 0) continue;
                JSONObject choice = choices.getJSONObject(0), delta = choice.getJSONObject("delta");
                content.append(delta.optString("content", ""));
                reasoning.append(delta.optString("reasoning_content", ""));
                if ("stop".equals(choice.optString("finish_reason"))) finish = true;
            }
            check(done && finish && usage && content.toString().equals(reply) && reasoning.toString().equals("Plan 🧠."),
                    "JSON SSE lost content, reasoning, usage, or termination");
        }
        try (Fixture fixture = new Fixture(false, false)) {
            fixture.engine.replyText = "{\"status\":";
            Response response = fixture.request("POST", "/v1/chat/completions", jsonChat().put("stream", true).toString(), auth());
            check(response.status == 200 && response.body.contains("invalid_json_output") && response.body.contains("[DONE]")
                    && !response.body.contains("\\\"status\\\"") && !response.body.contains("\"finish_reason\":\"stop\""),
                    "invalid JSON leaked or stream claimed success");
            fixture.engine.replyText = null;
            check(fixture.request("POST", "/v1/chat/completions", CHAT, auth()).status == 200, "JSON stream error broke ordinary chat");
        }
    }

    private void modelCapabilities() throws Exception {
        try (Fixture fixture = new Fixture(false, false)) {
            JSONObject models = fixture.request("GET", "/v1/models", null, auth()).json();
            JSONObject standard = models.getJSONArray("data").getJSONObject(0);
            check(models.getString("object").equals("list") && standard.getString("id").equals(OpenAiServer.MODEL_ID)
                    && standard.getString("object").equals("model"), "standard model discovery changed");
            check(standard.getJSONObject("architecture").getJSONArray("input_modalities").toString().contains("image"),
                    "image modality missing");
            JSONObject nativeModel = models.getJSONArray("models").getJSONObject(0);
            check(nativeModel.getString("model").equals(standard.getString("id"))
                    && nativeModel.getJSONArray("capabilities").toString().contains("vision"), "FitBuddy photo picker cannot discover vision");
            JSONObject audioOnly = OpenAiProtocol.models(false, true, true);
            check(audioOnly.getJSONArray("data").length() == 2 && audioOnly.getJSONArray("models").length() == 0,
                    "unconfigured chat advertised as vision");
        }
    }

    private void jsonObjectRepair() throws Exception {
        String corrected = "{\"status\":\"SUCCESS\",\"calories\":120}";
        for (boolean stream : new boolean[]{false, true}) {
            try (Fixture fixture = new Fixture(false, false)) {
                fixture.engine.replyText = "{\"status\":\"SUCCESS\",\"calories\":120]}";
                fixture.engine.repairedText = corrected;
                Response response = fixture.request("POST", "/v1/chat/completions",
                        jsonChat().put("max_tokens", 64).put("stream", stream).toString(), auth());
                check(response.status == 200 && fixture.engine.generations.get() == 2,
                        "JSON syntax repair failed or was not bounded");
                if (stream) {
                    check(response.body.contains("[DONE]") && response.body.contains("\"finish_reason\":\"stop\"")
                            && !response.body.contains("120]}"), "repair stream leaked the invalid response");
                } else {
                    check(response.json().getJSONArray("choices").getJSONObject(0).getJSONObject("message")
                            .getString("content").equals(corrected), "repaired JSON was not published");
                    check(response.json().getJSONObject("usage").getInt("completion_tokens") == 32
                            && response.json().getJSONObject("usage").getInt("prompt_tokens") == 24,
                            "repair usage omitted the first attempt");
                }
                GenerationRequest repair = fixture.engine.lastRequest;
                check(repair.maxOutputTokens == 48 && repair.messages.size() == 4
                        && repair.messages.get(2).text.equals(fixture.engine.replyText)
                        && repair.messages.get(3).role.equals("user"), "repair lost content or exceeded the remaining budget");
                fixture.engine.replyText = null;
                fixture.engine.repairedText = null;
                check(fixture.request("POST", "/v1/chat/completions", CHAT, auth()).status == 200
                        && fixture.engine.lastRequest.messages.size() == 1 && fixture.engine.loads.get() == 1,
                        "repair contaminated ordinary chat or reloaded the engine");
            }
        }
        try (Fixture fixture = new Fixture(false, false)) {
            fixture.engine.replyText = "{\"status\":";
            check(fixture.request("POST", "/v1/chat/completions", jsonChat().put("max_tokens", 4).toString(), auth()).status == 500
                    && fixture.engine.generations.get() == 1, "truncated JSON retried past its budget");
        }
        try (Fixture fixture = new Fixture(false, false)) {
            fixture.engine.fail = true;
            check(fixture.request("POST", "/v1/chat/completions", jsonChat().toString(), auth()).status == 500
                    && fixture.engine.generations.get() == 1, "native failure triggered a JSON repair");
        }
    }

    private void repeatedVersionPrefix() throws Exception {
        try (Fixture fixture = new Fixture(false, false)) {
            check(fixture.request("GET", "/v1/v1/models", null, "").status == 401, "version alias bypassed authentication");
            Response models = fixture.request("GET", "/v1/v1/models", null, auth());
            check(models.status == 200 && models.json().getJSONArray("data").getJSONObject(0).getString("id").equals(OpenAiServer.MODEL_ID),
                    "versioned-base discovery alias failed");
            JSONObject probe = new JSONObject(CHAT).put("max_tokens", 1).put("temperature", 0);
            check(fixture.request("POST", "/v1/v1/chat/completions", probe.toString(), auth()).status == 200
                    && fixture.engine.lastRequest.maxOutputTokens == 1, "FitBuddy reachability URL rejected");
            Response stream = fixture.request("POST", "/v1/v1/chat/completions?probe=true",
                    new JSONObject(CHAT).put("stream", true).toString(), auth());
            check(stream.status == 200 && stream.body.contains("[DONE]"), "version alias broke SSE or query handling");
            for (String path : new String[]{"/v1/v1/responses", "/v1/v1/v1/models", "/models"}) {
                check(fixture.request("GET", path, null, auth()).status == 404, "alias accepted an unrelated route");
            }
            check(fixture.request("POST", "/v1/chat/completions", CHAT, auth()).status == 200
                    && fixture.engine.loads.get() == 1, "alias affected standard requests or engine reuse");
        }
    }

    private static JSONObject jsonChat() throws Exception {
        return new JSONObject(CHAT).put("response_format", new JSONObject().put("type", "json_object"));
    }

    private void authenticationAndValidation() throws Exception {
        try (Fixture fixture = new Fixture(false, false)) {
            Response missing = fixture.request("GET", "/v1/models", null, "");
            check(missing.status == 401 && missing.json().getJSONObject("error").getString("type").equals("authentication_error"), "missing authentication");
            check(fixture.request("GET", "/v1/models", null, "Authorization: Bearer wrong\r\n").status == 401, "wrong key");
            Response models = fixture.request("GET", "/v1/models", null, auth());
            check(models.status == 200 && models.json().getJSONArray("data").getJSONObject(0).getString("id").equals(OpenAiServer.MODEL_ID), "model discovery");
            check(fixture.request("GET", "/v1/models", null, auth() + "Origin: https://client.example\r\n").status == 403, "CORS disabled");
            check(fixture.raw("GET /v1/models HTTP/1.1\r\nHost: rebound.example\r\n" + auth() + "\r\n").status == 403, "non-local Host accepted");
            check(fixture.raw("GET /v1/models HTTP/1.1\r\nHost: localhost\r\nHost: localhost\r\n" + auth() + "\r\n").status == 400, "duplicate Host accepted");
            check(fixture.request("POST", "/v1/chat/completions", "{broken", auth()).status == 400, "invalid JSON accepted");
            check(fixture.request("POST", "/v1/chat/completions", CHAT + "{}", auth()).status == 400, "trailing JSON accepted");
            check(fixture.request("POST", "/v1/chat/completions", "{'model':'x'}", auth()).status == 400, "non-JSON syntax accepted");
            check(fixture.request("POST", "/v1/chat/completions", "{\"model\":\"x\",\"model\":\"y\"}", auth()).status == 400, "duplicate JSON field accepted");
            check(fixture.request("POST", "/v1/chat/completions", "[".repeat(40) + "0" + "]".repeat(40), auth()).status == 400, "deep JSON accepted");
            check(fixture.raw("POST /v1/chat/completions HTTP/1.1\r\nHost: localhost\r\n" + auth()
                    + "Content-Type: application/json\r\nContent-Length: 7000000\r\n\r\n").status == 413, "body limit missing");
            check(fixture.raw("POST /v1/chat/completions HTTP/1.1\r\nHost: localhost\r\n" + auth()
                    + "Content-Type: application/json\r\n\r\n").status == 411, "missing length accepted");
            check(fixture.raw("POST /v1/chat/completions HTTP/1.1\r\nHost: localhost\r\n" + auth()
                    + "Content-Type: text/plain\r\nContent-Length: 0\r\n\r\n").status == 415, "wrong content type accepted");
            check(fixture.engine.generations.get() == 0 && fixture.engine.loads.get() == 0, "invalid request touched engine");
        }
    }

    private void chatHistoryAndErrors() throws Exception {
        try (Fixture fixture = new Fixture(false, false)) {
            JSONObject request = new JSONObject(CHAT);
            request.put("messages", new JSONArray().put(new JSONObject().put("role", "developer").put("content", "Be brief"))
                    .put(new JSONObject().put("role", "user").put("content", "My name is Ada"))
                    .put(new JSONObject().put("role", "assistant").put("content", "Hello Ada"))
                    .put(new JSONObject().put("role", "user").put("content", new JSONArray().put(new JSONObject().put("type", "text").put("text", "Who am I?")))));
            request.put("max_completion_tokens", 4).put("temperature", 0).put("top_p", 0.5).put("seed", 42);
            Response response = fixture.request("POST", "/v1/chat/completions", request.toString(), auth());
            check(response.status == 200, "non-streaming failed: " + response.body);
            JSONObject json = response.json();
            check(json.getString("object").equals("chat.completion"), "wrong completion object");
            check(json.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content").equals("Hello 🌍!"), "UTF-8 response corrupted");
            check(json.getJSONArray("choices").getJSONObject(0).getString("finish_reason").equals("length"), "token limit result discarded");
            check(json.getJSONObject("usage").getInt("total_tokens") == 16, "upstream counts discarded");
            GenerationRequest accepted = fixture.engine.lastRequest;
            check(accepted.messages.size() == 4 && accepted.messages.get(0).role.equals("system") && accepted.messages.get(3).text.equals("Who am I?"), "history was flattened/lost");
            check(accepted.maxOutputTokens == 4 && accepted.temperature == 0 && accepted.topP == 0.5 && accepted.seed == 42, "sampling not translated");
            check(fixture.request("POST", "/v1/chat/completions", CHAT, auth()).status == 200, "repeat failed");
            check(fixture.engine.loads.get() == 1 && fixture.engine.lastRequest.messages.size() == 1, "request history leaked or engine reloaded");
            for (String option : new String[]{"\"max_tokens\":0", "\"max_tokens\":1.5", "\"stream\":\"true\"", "\"temperature\":3", "\"top_p\":0", "\"seed\":2147483648", "\"stop\":\"x\"", "\"n\":2", "\"tools\":[{}]"}) {
                check(fixture.request("POST", "/v1/chat/completions", CHAT.substring(0, CHAT.length() - 1) + "," + option + "}", auth()).status == 400, "unsupported parameter accepted: " + option);
            }
            check(fixture.request("POST", "/v1/chat/completions", CHAT.replace(OpenAiServer.MODEL_ID, "unknown"), auth()).status == 404, "unknown model accepted");
            check(fixture.request("POST", "/v1/chat/completions", CHAT.replace("\"content\":\"Hello\"", "\"content\":[{\"type\":\"image_url\",\"image_url\":{\"url\":\"x\"}}]"), auth()).status == 400, "invalid image URL accepted");
            fixture.engine.fail = true;
            Response failed = fixture.request("POST", "/v1/chat/completions", CHAT, auth());
            check(failed.status == 500 && failed.json().getJSONObject("error").getString("code").equals("inference_failed"), "backend error shape");
        }
    }

    private void liveStreaming() throws Exception {
        liveStreaming(CHAT);
    }

    private static String thinkingChat() throws Exception {
        return new JSONObject(CHAT).put("enable_thinking", true).toString();
    }

    private static JSONObject toolsChat() throws Exception {
        JSONArray tools = new JSONArray("[{\"type\":\"function\",\"function\":{\"name\":\"test_add\",\"description\":\"Add integers.\",\"parameters\":{\"type\":\"object\",\"properties\":{\"a\":{\"type\":\"integer\"},\"b\":{\"type\":\"integer\"}},\"required\":[\"a\",\"b\"]}}},{\"type\":\"function\",\"function\":{\"name\":\"test_echo\",\"parameters\":{\"type\":\"object\",\"properties\":{\"text\":{\"type\":\"string\"}}}}}]");
        return new JSONObject(CHAT).put("tools", tools);
    }

    private static List<ToolCall> exampleCalls() {
        return List.of(new ToolCall("call_one", "test_add", "{\"a\":17,\"b\":23}"),
                new ToolCall("call_two", "test_echo", "{\"text\":\"Quotes: \\\"ciao\\\" 🌍\"}"));
    }

    private void clientToolsBufferedAndHistory() throws Exception {
        try (Fixture fixture = new Fixture(false, false)) {
            fixture.engine.replyCalls = exampleCalls();
            JSONObject request = toolsChat().put("enable_thinking", true);
            Response response = fixture.request("POST", "/v1/chat/completions", request.toString(), auth());
            check(response.status == 200, "buffered function request failed: " + response.body);
            JSONObject choice = response.json().getJSONArray("choices").getJSONObject(0);
            JSONObject assistant = choice.getJSONObject("message");
            JSONArray calls = assistant.getJSONArray("tool_calls");
            check(choice.getString("finish_reason").equals("tool_calls") && assistant.isNull("content")
                    && assistant.getString("reasoning_content").equals("Plan 🧠.") && calls.length() == 2,
                    "function response did not preserve thought, tool-only content, and finish reason");
            check(!calls.getJSONObject(0).has("index") && calls.getJSONObject(1).getJSONObject("function")
                    .getString("arguments").equals(exampleCalls().get(1).argumentsJson), "buffered arguments corrupted");
            check(fixture.engine.lastRequest.allowToolCalls && fixture.engine.lastRequest.tools.size() == 2,
                    "function definitions were dropped");
            // Out-of-order results must match IDs, not positions. Echo the assistant reply unchanged.
            request.put("messages", new JSONArray().put(new JSONObject().put("role", "user").put("content", "Use both functions."))
                    .put(assistant).put(new JSONObject().put("role", "tool").put("tool_call_id", "call_two").put("content", "ciao 🌍"))
                    .put(new JSONObject().put("role", "tool").put("tool_call_id", "call_one").put("content", "40")));
            fixture.engine.replyCalls = List.of();
            Response followup = fixture.request("POST", "/v1/chat/completions", request.toString(), auth());
            check(followup.status == 200, "function result round trip failed: " + followup.body);
            GenerationRequest accepted = fixture.engine.lastRequest;
            check(accepted.messages.get(1).toolCalls.size() == 2 && accepted.messages.get(1).text.isEmpty()
                    && accepted.messages.get(2).toolName.equals("test_echo") && accepted.messages.get(3).toolName.equals("test_add")
                    && accepted.messages.get(2).text.equals("ciao 🌍") && fixture.engine.loads.get() == 1,
                    "function history flattened, misidentified, or reloaded the engine");
            check(fixture.request("POST", "/v1/chat/completions", toolsChat().put("tool_choice", "none").toString(), auth()).status == 200
                    && !fixture.engine.lastRequest.allowToolCalls, "tool_choice=none rejected or enabled execution");
        }
    }

    private void clientToolsStreamingAndTruncation() throws Exception {
        try (Fixture fixture = new Fixture(true, false); Socket client = fixture.connect()) {
            fixture.engine.replyCalls = exampleCalls();
            JSONObject request = toolsChat().put("enable_thinking", true).put("stream", true)
                    .put("stream_options", new JSONObject().put("include_usage", true));
            fixture.send(client, "POST", "/v1/chat/completions", request.toString(), auth());
            BufferedReader reader = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
            check(reader.readLine().contains("200"), "function stream status");
            while (!reader.readLine().isEmpty()) { }
            boolean sawThought = false, sawCalls = false, sawFinish = false, sawUsage = false, done = false;
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("data: ")) continue;
                if (line.equals("data: [DONE]")) { done = true; break; }
                JSONObject frame = new JSONObject(line.substring(6));
                check(!frame.has("error"), "function stream error: " + frame);
                JSONArray choices = frame.getJSONArray("choices");
                if (choices.length() == 0) { sawUsage = frame.getJSONObject("usage").getInt("completion_tokens") == 16; continue; }
                JSONObject choice = choices.getJSONObject(0), delta = choice.getJSONObject("delta");
                if (delta.has("reasoning_content")) {
                    check(!sawCalls && fixture.engine.finished.getCount() == 1, "thinking was buffered behind the tool call");
                    sawThought = true; fixture.engine.allowFinish.countDown();
                }
                if (delta.has("tool_calls")) {
                    check(sawThought && !sawCalls && fixture.engine.finished.getCount() == 0, "executable arguments released before completion or duplicated");
                    JSONArray calls = delta.getJSONArray("tool_calls");
                    check(calls.length() == 2 && calls.getJSONObject(1).getInt("index") == 1
                            && calls.getJSONObject(1).getJSONObject("function").getString("arguments").equals(exampleCalls().get(1).argumentsJson), "streamed function arguments corrupted");
                    sawCalls = true;
                }
                if ("tool_calls".equals(choice.optString("finish_reason"))) { check(sawCalls, "finish preceded function arguments"); sawFinish = true; }
            }
            check(done && sawThought && sawCalls && sawFinish && sawUsage, "incomplete function stream");
            request.remove("enable_thinking"); request.put("max_tokens", 4);
            Response truncated = fixture.request("POST", "/v1/chat/completions", request.toString(), auth());
            check(truncated.status == 200 && truncated.body.contains("\"finish_reason\":\"length\"")
                    && !truncated.body.contains("\"tool_calls\"") && truncated.body.contains("[DONE]"), "length-limited function call was executable");
            fixture.engine.replyCalls = List.of();
            check(fixture.request("POST", "/v1/chat/completions", CHAT, auth()).status == 200 && fixture.engine.loads.get() == 1,
                    "function truncation broke engine recovery");
        }
    }

    private void clientToolsValidation() throws Exception {
        try (Fixture fixture = new Fixture(false, false)) {
            JSONObject request = toolsChat();
            JSONObject definition = request.getJSONArray("tools").getJSONObject(0).getJSONObject("function");
            definition.put("strict", true);
            check(fixture.request("POST", "/v1/chat/completions", request.toString(), auth()).status == 400, "unsupported strict schema accepted");
            definition.remove("strict"); definition.put("name", "bad name");
            check(fixture.request("POST", "/v1/chat/completions", request.toString(), auth()).status == 400, "unsafe function name accepted");
            request = toolsChat(); request.getJSONArray("tools").put(request.getJSONArray("tools").getJSONObject(0));
            check(fixture.request("POST", "/v1/chat/completions", request.toString(), auth()).status == 400, "duplicate function names accepted");
            request = toolsChat().put("tool_choice", "required");
            check(fixture.request("POST", "/v1/chat/completions", request.toString(), auth()).status == 400, "forced calls claimed supported");
            JSONObject assistant = new JSONObject().put("role", "assistant").put("content", JSONObject.NULL)
                    .put("tool_calls", OpenAiTools.wireCalls(exampleCalls(), false));
            JSONArray history = new JSONArray().put(new JSONObject().put("role", "user").put("content", "Use functions.")).put(assistant);
            request = toolsChat().put("messages", history);
            check(fixture.request("POST", "/v1/chat/completions", request.toString(), auth()).status == 400, "pending tool results accepted");
            history.put(new JSONObject().put("role", "tool").put("tool_call_id", "wrong_id").put("content", "40"));
            check(fixture.request("POST", "/v1/chat/completions", request.toString(), auth()).status == 400, "orphan tool result accepted");
            history.getJSONObject(2).put("tool_call_id", "call_one"); history.put(history.getJSONObject(2));
            check(fixture.request("POST", "/v1/chat/completions", request.toString(), auth()).status == 400, "duplicate tool result accepted");
            history.getJSONObject(1).getJSONArray("tool_calls").getJSONObject(0).getJSONObject("function").put("arguments", "[]");
            check(fixture.request("POST", "/v1/chat/completions", request.toString(), auth()).status == 400, "non-object function arguments accepted");
            check(fixture.engine.generations.get() == 0 && fixture.engine.loads.get() == 0, "invalid functions touched inference");
        }
    }

    private void reasoningCompatibilityControls() throws Exception {
        try (Fixture fixture = new Fixture(false, false, true)) {
            check(fixture.request("POST", "/v1/chat/completions", CHAT, auth()).status == 200
                    && fixture.engine.lastRequest.enableThinking && fixture.engine.lastRequest.maxOutputTokens == 2048, "configured thinking default ignored");
            JSONObject request = new JSONObject(CHAT).put("enable_thinking", false);
            check(fixture.request("POST", "/v1/chat/completions", request.toString(), auth()).status == 200
                    && !fixture.engine.lastRequest.enableThinking && fixture.engine.lastRequest.maxOutputTokens == 256, "explicit disable lost to default");
            request = new JSONObject(CHAT).put("reasoning_effort", "none");
            check(fixture.request("POST", "/v1/chat/completions", request.toString(), auth()).status == 200 && !fixture.engine.lastRequest.enableThinking, "effort=none ignored");
            request.put("reasoning_effort", "high");
            check(fixture.request("POST", "/v1/chat/completions", request.toString(), auth()).status == 200 && fixture.engine.lastRequest.enableThinking, "positive effort ignored");
            request = new JSONObject(CHAT).put("reasoning", new JSONObject().put("effort", "medium")).put("prompt_cache_key", "synthetic-conversation");
            check(fixture.request("POST", "/v1/chat/completions", request.toString(), auth()).status == 200 && fixture.engine.lastRequest.enableThinking, "OpenRouter reasoning/metadata rejected");
            request.getJSONObject("reasoning").put("enabled", false).remove("effort");
            check(fixture.request("POST", "/v1/chat/completions", request.toString(), auth()).status == 200 && !fixture.engine.lastRequest.enableThinking, "reasoning.enabled=false ignored");
            int before = fixture.engine.generations.get();
            request.put("enable_thinking", true);
            check(fixture.request("POST", "/v1/chat/completions", request.toString(), auth()).status == 400, "conflicting reasoning controls accepted");
            request = new JSONObject(CHAT).put("reasoning", new JSONObject().put("max_tokens", 128));
            check(fixture.request("POST", "/v1/chat/completions", request.toString(), auth()).status == 400, "separate reasoning budget falsely supported");
            check(fixture.request("POST", "/v1/chat/completions", new JSONObject(CHAT).put("reasoning_effort", true).toString(), auth()).status == 400, "non-string effort accepted");
            check(fixture.engine.generations.get() == before, "invalid reasoning touched inference");
        }
    }

    private void thinkingParametersAndHistory() throws Exception {
        try (Fixture fixture = new Fixture(false, false)) {
            Response normal = fixture.request("POST", "/v1/chat/completions", CHAT, auth());
            check(normal.status == 200 && !fixture.engine.lastRequest.enableThinking
                    && fixture.engine.lastRequest.maxOutputTokens == 256, "normal defaults changed");
            check(!normal.json().getJSONArray("choices").getJSONObject(0).getJSONObject("message")
                    .has("reasoning_content"), "normal reply gained reasoning");
            Response thinking = fixture.request("POST", "/v1/chat/completions", thinkingChat(), auth());
            check(thinking.status == 200 && fixture.engine.lastRequest.enableThinking
                    && fixture.engine.lastRequest.maxOutputTokens == 2048, "thinking defaults not routed");
            JSONObject message = thinking.json().getJSONArray("choices").getJSONObject(0).getJSONObject("message");
            check(message.getString("content").equals("Hello 🌍!")
                    && message.getString("reasoning_content").equals("Plan 🧠."), "buffered reasoning lost or mixed");
            check(thinking.json().getJSONObject("usage").getInt("completion_tokens") == 8, "reasoning usage lost");
            JSONObject followup = new JSONObject(CHAT).put("messages", new JSONArray()
                    .put(new JSONObject().put("role", "user").put("content", "Hello"))
                    .put(message).put(new JSONObject().put("role", "user").put("content", "Continue")));
            check(fixture.request("POST", "/v1/chat/completions", followup.toString(), auth()).status == 200
                    && !fixture.engine.lastRequest.enableThinking, "thinking leaked into next request");
            check(fixture.engine.lastRequest.messages.get(1).text.equals("Hello 🌍!")
                    && fixture.engine.lastRequest.messages.get(1).parts.size() == 1, "historical thoughts reached prefill");
            for (String option : new String[]{"\"enable_thinking\":\"true\"", "\"enable_thinking\":1", "\"max_tokens\":4097"}) {
                check(fixture.request("POST", "/v1/chat/completions", CHAT.substring(0, CHAT.length() - 1)
                        + "," + option + "}", auth()).status == 400, "bad thinking option accepted");
            }
            JSONObject explicit = new JSONObject(thinkingChat()).put("max_completion_tokens", 4096);
            check(fixture.request("POST", "/v1/chat/completions", explicit.toString(), auth()).status == 200
                    && fixture.engine.lastRequest.maxOutputTokens == 4096, "expanded output cap rejected");
            explicit.put("enable_thinking", false).remove("max_completion_tokens");
            check(fixture.request("POST", "/v1/chat/completions", explicit.toString(), auth()).status == 200
                    && !fixture.engine.lastRequest.enableThinking && fixture.engine.lastRequest.maxOutputTokens == 256,
                    "explicit thinking disable ignored");
            followup.getJSONArray("messages").getJSONObject(1).put("reasoning_content", 3);
            check(fixture.request("POST", "/v1/chat/completions", followup.toString(), auth()).status == 400,
                    "non-string historical reasoning accepted");
            followup.getJSONArray("messages").getJSONObject(1).put("reasoning_content", JSONObject.NULL);
            check(fixture.request("POST", "/v1/chat/completions", followup.toString(), auth()).status == 200,
                    "null historical reasoning rejected");
            followup.getJSONArray("messages").getJSONObject(2).put("reasoning_content", "Do not prefill");
            check(fixture.request("POST", "/v1/chat/completions", followup.toString(), auth()).status == 400,
                    "user reasoning_content accepted");
        }
    }

    private void thinkingStreaming() throws Exception {
        try (Fixture fixture = new Fixture(true, false); Socket client = fixture.connect()) {
            JSONObject request = new JSONObject(thinkingChat()).put("stream", true)
                    .put("stream_options", new JSONObject().put("include_usage", true));
            fixture.send(client, "POST", "/v1/chat/completions", request.toString(), auth());
            BufferedReader reader = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
            check(reader.readLine().contains("200"), "thinking stream status");
            while (!reader.readLine().isEmpty()) { }
            StringBuilder reasoning = new StringBuilder(), content = new StringBuilder();
            boolean done = false, usage = false, earlyReasoning = false;
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("data: ")) continue;
                if (line.equals("data: [DONE]")) { done = true; break; }
                JSONObject chunk = new JSONObject(line.substring(6));
                check(!chunk.has("error"), "thinking stream mismatch or error: " + chunk);
                JSONArray choices = chunk.getJSONArray("choices");
                if (choices.length() == 0) {
                    usage = chunk.getJSONObject("usage").getInt("completion_tokens") == 8;
                    continue;
                }
                JSONObject delta = choices.getJSONObject(0).getJSONObject("delta");
                String thought = delta.optString("reasoning_content", "");
                if (!thought.isEmpty() && !earlyReasoning) {
                    check(fixture.engine.finished.getCount() == 1, "reasoning buffered until completion");
                    earlyReasoning = true;
                    fixture.engine.allowFinish.countDown();
                }
                reasoning.append(thought);
                content.append(delta.optString("content", ""));
            }
            check(done && usage && earlyReasoning && reasoning.toString().equals("Plan 🧠.")
                    && content.toString().equals("Hello 🌍!"), "reasoning and answer not independently streamed");
        }
    }

    private void thinkingTruncationAndRecovery() throws Exception {
        try (Fixture fixture = new Fixture(false, false)) {
            JSONObject request = new JSONObject(thinkingChat()).put("max_tokens", 4);
            Response response = fixture.request("POST", "/v1/chat/completions", request.toString(), auth());
            JSONObject choice = response.json().getJSONArray("choices").getJSONObject(0);
            check(response.status == 200 && choice.getString("finish_reason").equals("length")
                    && choice.getJSONObject("message").getString("content").isEmpty()
                    && choice.getJSONObject("message").getString("reasoning_content").equals("Plan 🧠."),
                    "truncated thoughts were treated as a final answer");
            Response streamed = fixture.request("POST", "/v1/chat/completions", request.put("stream", true).toString(), auth());
            check(streamed.status == 200 && streamed.body.contains("\"finish_reason\":\"length\"")
                    && streamed.body.contains("\"reasoning_content\"") && !streamed.body.contains("\"error\"")
                    && streamed.body.contains("[DONE]"), "truncated reasoning stream failed");
            check(fixture.request("POST", "/v1/chat/completions", CHAT, auth()).status == 200
                    && fixture.engine.loads.get() == 1, "truncation broke engine reuse");
        }
    }

    private void liveStreaming(String request) throws Exception {
        try (Fixture fixture = new Fixture(true, false); Socket client = fixture.connect()) {
            fixture.send(client, "POST", "/v1/chat/completions", request.substring(0, request.length() - 1) + ",\"stream\":true,\"stream_options\":{\"include_usage\":true}}", auth());
            BufferedReader reader = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
            check(reader.readLine().contains("200"), "stream status");
            String line;
            while (!(line = reader.readLine()).isEmpty()) { }
            StringBuilder text = new StringBuilder();
            String id = null;
            int contentChunks = 0;
            boolean gotUsage = false;
            boolean finished = false;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("data: ")) continue;
                String data = line.substring(6);
                if (data.equals("[DONE]")) { finished = true; break; }
                JSONObject chunk = new JSONObject(data);
                check(!chunk.has("error"), "stream error: " + chunk);
                if (id == null) id = chunk.getString("id");
                check(chunk.getString("id").equals(id), "stream ID changed");
                JSONArray choices = chunk.getJSONArray("choices");
                if (choices.length() == 0) {
                    gotUsage = chunk.getJSONObject("usage").getInt("completion_tokens") == 4;
                    continue;
                }
                JSONObject delta = choices.getJSONObject(0).getJSONObject("delta");
                String part = delta.optString("content", "");
                if (!part.isEmpty()) {
                    text.append(part);
                    contentChunks++;
                    if (contentChunks == 1) {
                        check(fixture.engine.finished.getCount() == 1, "first content buffered until completion");
                        fixture.engine.allowFinish.countDown();
                    }
                }
            }
            check(finished && gotUsage && contentChunks == 2 && text.toString().equals("Hello 🌍!"), "incomplete live SSE stream");
        }
    }

    private void busyDisconnectAndRecovery() throws Exception {
        busyDisconnectAndRecovery(CHAT);
    }

    private void busyDisconnectAndRecovery(String request) throws Exception {
        try (Fixture fixture = new Fixture(true, false)) {
            Socket abandoned = fixture.connect();
            fixture.send(abandoned, "POST", "/v1/chat/completions", request, auth());
            await(fixture.engine.entered);
            Response busy = fixture.request("POST", "/v1/chat/completions", CHAT, auth());
            check(busy.status == 429 && busy.json().getJSONObject("error").getString("code").equals("engine_busy"), "overlap not rejected");
            check(!onMain(() -> fixture.manager.generate(new GenerationRequest("test-model", "UI", false))), "UI bypassed API admission");
            abandoned.close();
            await(fixture.engine.cancelled);
            waitReady(fixture);
            check(fixture.request("POST", "/v1/chat/completions", CHAT, auth()).status == 200, "disconnect recovery failed");
            check(fixture.engine.loads.get() == 1, "disconnect unloaded retained model");
            GenerationListener oldOwner = fixture.lastOwner;
            fixture.engine.allowFinish = new CountDownLatch(1);
            fixture.engine.entered = new CountDownLatch(1);
            try (Socket next = fixture.connect()) {
                fixture.send(next, "POST", "/v1/chat/completions", CHAT, auth());
                await(fixture.engine.entered);
                onMain(() -> { fixture.manager.cancel(oldOwner); return null; });
                check(onMain(() -> fixture.manager.getState().canCancel), "stale owner cancelled newer request");
                fixture.engine.allowFinish.countDown();
                check(read(next).status == 200, "new request failed after stale cancel");
            }
        }
    }

    private void corsAndPortConflict() throws Exception {
        try (Fixture fixture = new Fixture(false, true)) {
            Response options = fixture.request("OPTIONS", "/v1/chat/completions", null,
                    "Origin: https://client.example\r\nAccess-Control-Request-Method: POST\r\nAccess-Control-Request-Headers: authorization,content-type\r\n");
            check(options.status == 204 && options.headers.contains("Access-Control-Allow-Origin: *"), "authenticated preflight required or missing CORS");
            Response authError = fixture.request("GET", "/v1/models", null, "Origin: https://client.example\r\n");
            check(authError.status == 401 && authError.headers.contains("Access-Control-Allow-Origin"), "CORS hides authentication error");
            OpenAiServer collision = new OpenAiServer(new ServerConfig(fixture.server.getPort(), KEY, true, false), "test-model", false, fixture.gateway, null);
            boolean failed = false;
            try { collision.start(); } catch (java.io.IOException expected) { failed = true; }
            finally { collision.close(); }
            check(failed && fixture.server.isRunning(), "port conflict replaced original listener");
            check(fixture.request("GET", "/v1/models", null, auth()).status == 200, "original server lost after conflict");
        }
    }

    private void stopAndRestart() throws Exception {
        try (Fixture fixture = new Fixture(true, false)) {
            Socket client = fixture.connect();
            fixture.send(client, "POST", "/v1/chat/completions", CHAT, auth());
            await(fixture.engine.entered);
            int port = fixture.server.getPort();
            fixture.server.close();
            await(fixture.engine.cancelled);
            waitReady(fixture);
            client.close();
            check(fixture.engine.loaded, "stopping HTTP server unloaded the engine");
            fixture.server = new OpenAiServer(new ServerConfig(port, "rotated-key", true, false), "test-model", false, fixture.gateway, null);
            fixture.server.start();
            check(fixture.request("GET", "/v1/models", null, auth()).status == 401, "old key survived rotation");
            check(fixture.request("GET", "/v1/models", null, "Authorization: Bearer rotated-key\r\n").status == 200, "restart on same port failed");
            fixture.server.close();
            fixture.server = new OpenAiServer(new ServerConfig(port, "", false, false), "test-model", false, fixture.gateway, null);
            fixture.server.start();
            check(fixture.request("GET", "/v1/models", null, "").status == 200, "explicit key opt-out failed");
        }
    }

    private static byte[] imageBytes(Bitmap.CompressFormat format) {
        Bitmap bitmap = Bitmap.createBitmap(64, 32, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(Color.RED);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        check(bitmap.compress(format, 90, bytes), "test image encoding failed");
        bitmap.recycle();
        return bytes.toByteArray();
    }

    private static String imageUrl(Bitmap.CompressFormat format) {
        String mime = format == Bitmap.CompressFormat.PNG ? "image/png"
                : format == Bitmap.CompressFormat.JPEG ? "image/jpeg" : "image/webp";
        return "data:" + mime + ";base64," + Base64.encodeToString(imageBytes(format), Base64.NO_WRAP);
    }

    private static JSONObject imagePart(String url) throws Exception {
        return new JSONObject().put("type", "image_url").put("image_url", new JSONObject().put("url", url));
    }

    private static String imageChat(String url) throws Exception {
        return new JSONObject(CHAT).put("messages", new JSONArray().put(new JSONObject().put("role", "user")
                .put("content", new JSONArray().put(new JSONObject().put("type", "text").put("text", "Describe this."))
                        .put(imagePart(url))))).toString();
    }

    private void imageDecode() throws Exception {
        for (Bitmap.CompressFormat format : new Bitmap.CompressFormat[]{Bitmap.CompressFormat.PNG,
                Bitmap.CompressFormat.JPEG, Bitmap.CompressFormat.WEBP_LOSSLESS}) {
            ImageInput image = ImageInput.fromDataUrl(imageUrl(format));
            check(image.width == 64 && image.height == 32, "image dimensions changed");
        }
        Bitmap wide = Bitmap.createBitmap(2048, 512, Bitmap.Config.ARGB_8888);
        wide.eraseColor(Color.BLUE);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        wide.compress(Bitmap.CompressFormat.PNG, 100, bytes);
        wide.recycle();
        ImageInput resized = ImageInput.decode(bytes.toByteArray(), "image/png");
        check(resized.width == 1024 && resized.height == 256, "image downscale lost aspect ratio");
        byte[] jpeg = imageBytes(Bitmap.CompressFormat.JPEG);
        // EXIF Orientation=6 (90 degrees clockwise), a little-endian TIFF IFD.
        byte[] exif = new byte[]{(byte) 0xff, (byte) 0xe1, 0, 34, 'E', 'x', 'i', 'f', 0, 0,
                'I', 'I', 42, 0, 8, 0, 0, 0, 1, 0, 18, 1, 3, 0, 1, 0, 0, 0, 6, 0, 0, 0, 0, 0, 0, 0};
        ByteArrayOutputStream rotated = new ByteArrayOutputStream();
        rotated.write(jpeg, 0, 2); rotated.write(exif); rotated.write(jpeg, 2, jpeg.length - 2);
        ImageInput oriented = ImageInput.decode(rotated.toByteArray(), "image/jpeg");
        check(oriented.width == 32 && oriented.height == 64, "EXIF orientation was ignored");
        AtomicBoolean closed = new AtomicBoolean();
        ImageInput.read(() -> new java.io.ByteArrayInputStream(imageBytes(Bitmap.CompressFormat.PNG)) {
            @Override public void close() { closed.set(true); }
        });
        check(closed.get(), "image document stream leaked");
        closed.set(false);
        try {
            ImageInput.read(() -> new java.io.ByteArrayInputStream(new byte[ImageInput.MAX_BYTES + 1]) {
                @Override public void close() { closed.set(true); }
            });
            throw new AssertionError("oversized document read accepted");
        } catch (ImageInput.InvalidImage expected) { check(expected.tooLarge && closed.get(), "failed image read leaked its stream"); }
    }

    private void imageParts() throws Exception {
        try (Fixture fixture = new Fixture(false, false)) {
            JSONObject request = new JSONObject(imageChat(imageUrl(Bitmap.CompressFormat.PNG)));
            JSONArray turns = request.getJSONArray("messages");
            turns.getJSONObject(0).getJSONArray("content").put(new JSONObject().put("type", "text").put("text", "Then compare."))
                    .put(imagePart(imageUrl(Bitmap.CompressFormat.JPEG)));
            turns.put(new JSONObject().put("role", "assistant").put("content", "Two images."));
            turns.put(new JSONObject().put("role", "user").put("content", "What changed?"));
            check(fixture.request("POST", "/v1/chat/completions", request.toString(), auth()).status == 200, "image history rejected");
            GenerationRequest accepted = fixture.engine.lastRequest;
            check(accepted.messages.size() == 3 && accepted.messages.get(0).parts.size() == 4, "multimodal history was flattened");
            check(accepted.messages.get(0).parts.get(1).isImage() && accepted.messages.get(0).parts.get(2).text.equals("Then compare.")
                    && accepted.messages.get(0).parts.get(3).image.width == 64, "content ordering lost");
            request.put("messages", new JSONArray().put(new JSONObject().put("role", "user")
                    .put("content", new JSONArray().put(imagePart(imageUrl(Bitmap.CompressFormat.WEBP_LOSSLESS))))));
            check(fixture.request("POST", "/v1/chat/completions", request.toString(), auth()).status == 200, "image-only request rejected");
            check(fixture.request("POST", "/v1/chat/completions", CHAT, auth()).status == 200
                    && !fixture.engine.lastRequest.messages.get(0).parts.get(0).isImage()
                    && fixture.engine.loads.get() == 1, "image history leaked into a later request or forced reload");
        }
    }

    private void imageValidation() throws Exception {
        try (Fixture fixture = new Fixture(false, false)) {
            String valid = imageUrl(Bitmap.CompressFormat.PNG);
            for (String url : new String[]{"http://127.0.0.1:9/private", "https://example.com/image.png", "file:///data/private.png",
                    "content://provider/image", "data:image/gif;base64,R0lGODlh", "data:image/png;base64,%%%", "data:image/png;base64,AAAA",
                    valid.replace("image/png", "image/jpeg"), valid + "\n", valid.replace(";base64", "")}) {
                Response reply = fixture.request("POST", "/v1/chat/completions", imageChat(url), auth());
                check(reply.status == 400 && reply.json().getJSONObject("error").getString("code").equals("invalid_image"), "invalid image admitted");
            }
            String animated = "data:image/webp;base64,UklGRsQAAABXRUJQVlA4WAoAAAACAAAAAwAAAwAAQU5JTQYAAAAAAAAAAABBTk1GSgAAAAAAAAAAAAMAAAMAAGQAAAJWUDggMgAAADABAJ0BKgQABAABQCYloAADcAD+8ut///mwP/bz/wR6Af//0uD//pcH//S4P/SkAAAAQU5NRkYAAAAAAAAAAAADAAADAABkAAAAVlA4IC4AAAA0AQCdASoEAAQAAAAmJaAAA3AA/vtV4///S4P/+lwf/9Lg/9Lg//rV5Vesq6AA";
            check(fixture.request("POST", "/v1/chat/completions", imageChat(animated), auth()).status == 400, "animated image accepted");
            JSONObject bad = new JSONObject(imageChat(valid));
            bad.getJSONArray("messages").getJSONObject(0).getJSONArray("content").getJSONObject(1)
                    .getJSONObject("image_url").put("detail", "high");
            check(fixture.request("POST", "/v1/chat/completions", bad.toString(), auth()).status == 400, "unsupported detail accepted");
            check(fixture.request("POST", "/v1/chat/completions", imageChat(valid).replace("\"user\"", "\"system\""), auth()).status == 400, "system image accepted");
            byte[] forged = imageBytes(Bitmap.CompressFormat.PNG);
            java.nio.ByteBuffer.wrap(forged, 16, 4).putInt(9000);
            java.util.zip.CRC32 crc = new java.util.zip.CRC32();
            crc.update(forged, 12, 17);
            java.nio.ByteBuffer.wrap(forged, 29, 4).putInt((int) crc.getValue());
            check(fixture.request("POST", "/v1/chat/completions", imageChat("data:image/png;base64,"
                    + Base64.encodeToString(forged, Base64.NO_WRAP)), auth()).status == 413, "oversized image dimensions accepted");
            String huge = "data:image/png;base64," + Base64.encodeToString(new byte[ImageInput.MAX_BYTES + 1], Base64.NO_WRAP);
            check(fixture.request("POST", "/v1/chat/completions", imageChat(huge), auth()).status == 413, "encoded image byte limit missing");
            JSONObject many = new JSONObject(imageChat(valid));
            JSONArray parts = many.getJSONArray("messages").getJSONObject(0).getJSONArray("content");
            for (int i = 0; i < 4; i++) parts.put(imagePart(valid));
            check(fixture.request("POST", "/v1/chat/completions", many.toString(), auth()).status == 413, "image count limit missing");
            // Valid small PNG followed by padding: the source byte budget still
            // counts bytes discarded when the decoder normalizes the image.
            String padded = "data:image/png;base64," + Base64.encodeToString(java.util.Arrays.copyOf(
                    imageBytes(Bitmap.CompressFormat.PNG), 1400 * 1024), Base64.NO_WRAP);
            JSONObject total = new JSONObject(imageChat(padded));
            total.getJSONArray("messages").getJSONObject(0).getJSONArray("content").put(imagePart(padded)).put(imagePart(padded));
            Response aggregate = fixture.request("POST", "/v1/chat/completions", total.toString(), auth());
            check(aggregate.status == 413 && aggregate.json().getJSONObject("error").getString("code").equals("image_too_large"), "aggregate image byte limit missing");
            check(fixture.request("POST", "/v1/chat/completions", CHAT.replace("Hello", "x".repeat(262145)), auth()).status == 413, "text bound relaxed by larger chat body limit");
            check(fixture.engine.loads.get() == 0 && fixture.engine.generations.get() == 0, "invalid images reached the engine");
        }
    }

    private final class Fixture implements AutoCloseable {
        final FakeEngine engine = new FakeEngine();
        final EngineManager manager;
        final ChatGateway gateway;
        volatile GenerationListener lastOwner;
        OpenAiServer server;

        Fixture(boolean hold, boolean cors) throws Exception {
            this(hold, cors, false);
        }
        Fixture(boolean hold, boolean cors, boolean defaultThinking) throws Exception {
            if (hold) engine.allowFinish = new CountDownLatch(1);
            manager = onMain(() -> new EngineManager(engine, new EngineManager.WorkGuard() {
                @Override public void begin() { check(Looper.myLooper() != Looper.getMainLooper(), "native work on main"); }
                @Override public void end() { }
            }));
            gateway = new ChatGateway() {
                @Override public boolean generate(GenerationRequest request, GenerationListener listener) throws Exception {
                    boolean accepted = onMain(() -> manager.generate(request, listener));
                    if (accepted) lastOwner = listener;
                    return accepted;
                }
                @Override public void cancel(GenerationListener listener) {
                    new android.os.Handler(Looper.getMainLooper()).post(() -> manager.cancel(listener));
                }
            };
            server = new OpenAiServer(new ServerConfig(0, KEY, true, cors, defaultThinking), "test-model", false, gateway, null);
            server.start();
        }

        Socket connect() throws Exception {
            Socket socket = new Socket("127.0.0.1", server.getPort());
            socket.setSoTimeout(5000);
            return socket;
        }
        void send(Socket socket, String method, String path, String body, String extra) throws Exception {
            byte[] bytes = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
            String request = method + " " + path + " HTTP/1.1\r\nHost: localhost:" + server.getPort() + "\r\n" + extra;
            if (body != null) request += "Content-Type: application/json\r\nContent-Length: " + bytes.length + "\r\n";
            socket.getOutputStream().write((request + "\r\n").getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().write(bytes);
            socket.getOutputStream().flush();
        }
        Response request(String method, String path, String body, String extra) throws Exception {
            try (Socket socket = connect()) { send(socket, method, path, body, extra); return read(socket); }
        }
        Response raw(String request) throws Exception {
            try (Socket socket = connect()) {
                socket.getOutputStream().write(request.getBytes(StandardCharsets.UTF_8));
                socket.getOutputStream().flush();
                return read(socket);
            }
        }
        @Override public void close() throws Exception {
            server.close();
            engine.allowFinish.countDown();
            onMain(() -> { manager.close(); return null; });
        }
    }

    private static final class FakeEngine implements InferenceEngine {
        final AtomicInteger loads = new AtomicInteger();
        final AtomicInteger generations = new AtomicInteger();
        volatile CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch finished = new CountDownLatch(1);
        final CountDownLatch cancelled = new CountDownLatch(1);
        volatile CountDownLatch allowFinish = new CountDownLatch(0);
        volatile GenerationRequest lastRequest;
        volatile boolean loaded;
        volatile boolean fail;
        volatile List<ToolCall> replyCalls = List.of();
        volatile String replyText;
        volatile String repairedText;

        @Override public GenerationResult load(String model, boolean verbose) {
            if (!loaded) { loaded = true; loads.incrementAndGet(); }
            return new GenerationResult(true, "", "test load");
        }
        @Override public boolean isLoaded() { return loaded; }
        @Override public String getModelSource() { return loaded ? "test-model" : ""; }
        @Override public String getLoadDiagnostics() { return "test load"; }
        @Override public GenerationResult generate(GenerationRequest request, AtomicBoolean cancel) throws Exception { return generate(request, cancel, null); }
        @Override public GenerationResult generate(GenerationRequest request, AtomicBoolean cancel, GenerationListener listener) throws Exception {
            lastRequest = request;
            generations.incrementAndGet();
            if (replyText != null) {
                String text = generations.get() > 1 && repairedText != null ? repairedText : replyText;
                int split = text.length() / 2;
                if (listener != null) {
                    listener.onText(text.substring(0, split));
                    if (request.enableThinking) listener.onReasoning("Plan 🧠.");
                }
                entered.countDown(); await(allowFinish);
                if (cancel.get()) return new GenerationResult(false, true, "", "cancelled");
                if (listener != null) listener.onText(text.substring(split));
                return new GenerationResult(true, false, text, request.enableThinking ? "Plan 🧠." : "",
                        "test JSON output", 12, 16, request.maxOutputTokens == 4);
            }
            if (!replyCalls.isEmpty()) {
                if (listener != null && request.enableThinking) listener.onReasoning("Plan 🧠.");
                entered.countDown(); await(allowFinish);
                if (cancel.get()) return new GenerationResult(false, true, "", "cancelled");
                finished.countDown();
                return new GenerationResult(true, false, "", request.enableThinking ? "Plan 🧠." : "", replyCalls,
                        "test functions", 12, request.maxOutputTokens == 4 ? 4 : 16, request.maxOutputTokens == 4);
            }
            if (request.enableThinking && request.maxOutputTokens == 4) {
                if (listener != null) listener.onReasoning("Plan 🧠.");
                return new GenerationResult(true, false, "", "Plan 🧠.", "test result", 12, 4, true);
            }
            if (listener != null && request.enableThinking) listener.onReasoning("Plan ");
            if (listener != null) listener.onText("Hello ");
            entered.countDown();
            await(allowFinish);
            if (cancel.get()) return new GenerationResult(false, true, "", "cancelled");
            if (fail) return new GenerationResult(false, "", "test failure");
            if (listener != null && request.enableThinking) listener.onReasoning("🧠.");
            if (listener != null) listener.onText("🌍!");
            finished.countDown();
            return new GenerationResult(true, false, "Hello 🌍!", request.enableThinking ? "Plan 🧠." : "",
                    "test result", 12, request.enableThinking ? 8 : 4, request.maxOutputTokens == 4);
        }
        @Override public void cancel() { cancelled.countDown(); allowFinish.countDown(); }
        @Override public void unload() { loaded = false; }
    }

    private static final class Response {
        final int status;
        final String headers;
        final String body;
        Response(String response) {
            int end = response.indexOf("\r\n\r\n");
            check(end >= 0, "response missing headers");
            headers = response.substring(0, end);
            status = Integer.parseInt(headers.split(" ", 3)[1]);
            body = response.substring(end + 4);
        }
        JSONObject json() throws Exception { return new JSONObject(body); }
    }

    private static Response read(Socket socket) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int count;
        while ((count = socket.getInputStream().read(buffer)) >= 0) output.write(buffer, 0, count);
        return new Response(output.toString(StandardCharsets.UTF_8.name()));
    }
    private static String auth() { return "Authorization: Bearer " + KEY + "\r\n"; }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private static void await(CountDownLatch latch) throws InterruptedException { check(latch.await(5, TimeUnit.SECONDS), "timed out waiting for test event"); }
    private <T> T onMain(Callable<T> action) throws Exception {
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> {
            try { result.set(action.call()); } catch (Throwable problem) { error.set(problem); }
        });
        if (error.get() != null) throw new AssertionError(error.get());
        return result.get();
    }
    private void waitReady(Fixture fixture) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (onMain(() -> fixture.manager.getState().busy) && System.nanoTime() < deadline) Thread.sleep(10);
        check(!onMain(() -> fixture.manager.getState().busy), "cancelled request never drained");
    }
}
