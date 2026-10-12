#!/usr/bin/env python3
"""Exercise client-side functions and reasoning with synthetic on-device requests.

Start InferDroid's server first. Use --default-thinking when its default-thinking
checkbox is enabled. The API key stays in memory; no network tools are executed.
"""

import argparse
from datetime import datetime
import http.client
import importlib.util
import json
from pathlib import Path
import re
import subprocess
import time

spec = importlib.util.spec_from_file_location("probe", Path(__file__).with_name("test-device-reasoning.py"))
probe = importlib.util.module_from_spec(spec)
spec.loader.exec_module(probe)
MODEL = "gemma-4-E2B-it_Google_Tensor_G5"
TOOLS = [{"type": "function", "function": {"name": "test_add", "description": "Add two integers. The client returns their sum.",
          "parameters": {"type": "object", "properties": {"a": {"type": "integer"}, "b": {"type": "integer"}}, "required": ["a", "b"]}}}]
MESSAGES = [{"role": "system", "content": "Use the provided test_add function for addition. After receiving its result, reply on one line: ANSWER: value."},
            {"role": "user", "content": "Use test_add to calculate 17 plus 23."}]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--default-thinking", action="store_true")
    args = parser.parse_args()
    if args.output.exists():
        parser.error("Choose a new output path to preserve previous evidence.")
    args.output.parent.mkdir(parents=True, exist_ok=True)
    server_port, key, require_key = probe.api_config()
    port = int(probe.adb("forward", "tcp:0", "tcp:" + str(server_port)).strip())
    report = {"started_at": datetime.now().astimezone().isoformat(), "device": probe.adb("shell", "getprop", "ro.product.model").strip(),
              "agora_wire_reference": "v2.1.0", "method": "Synthetic requests using Agora's Chat Completions wire format; only local test_add executed by this script.",
              "server_default_thinking": args.default_thinking, "results": []}
    headers = {"Content-Type": "application/json"}
    if require_key:
        headers["Authorization"] = "Bearer " + key

    def save():
        encoded = json.dumps(report, indent=2, ensure_ascii=False) + "\n"
        assert not key or key not in encoded, "Credential unexpectedly appeared in evidence."
        args.output.write_text(encoded)

    def chat(name, *, messages=None, stream=False, status=200, **extra):
        body = {"model": MODEL, "messages": messages or MESSAGES, "temperature": 0, "max_tokens": 512, "stream": stream, "tools": TOOLS}
        if stream:
            body["stream_options"] = {"include_usage": True}
        body.update(extra)
        connection = http.client.HTTPConnection("127.0.0.1", port, timeout=300)
        started = time.monotonic()
        result = {"name": name, "request": body, "text": "", "reasoning": "", "tool_calls": [], "passed": False, "frames": [],
                  "first_reasoning_s": None, "first_content_s": None, "first_tool_calls_s": None}
        report["results"].append(result)
        try:
            connection.request("POST", "/v1/chat/completions", json.dumps(body), headers)
            response = connection.getresponse()
            result["status"] = response.status
            if response.status != 200 or not stream:
                result["response"] = json.loads(response.read())
                if response.status == 200:
                    choice = result["response"]["choices"][0]
                    result["message"] = choice["message"]
                    result["text"] = choice["message"].get("content") or ""
                    result["reasoning"] = choice["message"].get("reasoning_content", "")
                    result["tool_calls"] = choice["message"].get("tool_calls", [])
                    result["finish_reason"] = choice["finish_reason"]
                    result["usage"] = result["response"].get("usage")
            else:
                for raw in response:
                    line = raw.decode().strip()
                    if not line.startswith("data: "):
                        continue
                    if line == "data: [DONE]":
                        result["done"] = True
                        break
                    frame = json.loads(line[6:])
                    result["frames"].append(frame)
                    if "error" in frame:
                        result["error"] = frame["error"]
                    if frame.get("usage"):
                        result["usage"] = frame["usage"]
                    for choice in frame.get("choices", []):
                        delta = choice.get("delta", {})
                        for field, target in [("content", "text"), ("reasoning_content", "reasoning")]:
                            if delta.get(field):
                                first = "first_content_s" if target == "text" else "first_reasoning_s"
                                if result[first] is None:
                                    result[first] = round(time.monotonic() - started, 3)
                                result[target] += delta[field]
                        if delta.get("tool_calls"):
                            result["first_tool_calls_s"] = round(time.monotonic() - started, 3)
                            result["tool_calls"].extend(delta["tool_calls"])
                        if choice.get("finish_reason"):
                            result["finish_reason"] = choice["finish_reason"]
            result["elapsed_s"] = round(time.monotonic() - started, 3)
            save()
            assert result["status"] == status, f"{name}: status {result['status']}"
            if status == 200:
                assert not result.get("error"), f"{name}: stream error {result.get('error')}"
                assert result.get("usage"), f"{name}: missing token counts"
                if stream:
                    assert result.get("done"), f"{name}: incomplete stream"
                for marker in ("<|channel>", "<channel|>", "<|tool_call>", "<tool_call|>"):
                    assert marker not in result["text"], f"{name}: control token leaked into answer"
                usage = result["usage"]
                assert usage["total_tokens"] == usage["prompt_tokens"] + usage["completion_tokens"]
            print(json.dumps({"name": name, "status": result["status"], "elapsed_s": result["elapsed_s"],
                              "finish": result.get("finish_reason"), "calls": len(result["tool_calls"]), "thinking_chars": len(result["reasoning"])}), flush=True)
            return result
        finally:
            connection.close()

    def passed(result):
        result["passed"] = True
        save()

    def addition_call(result):
        assert result["finish_reason"] == "tool_calls" and len(result["tool_calls"]) == 1, result["name"] + ": expected one function call"
        call = result["tool_calls"][0]
        assert re.fullmatch(r"call_[A-Za-z0-9_-]+", call["id"])
        assert call["type"] == "function" and call["function"]["name"] == "test_add"
        assert json.loads(call["function"]["arguments"]) == {"a": 17, "b": 23}
        return call

    def correct_answer(result, expected, *, allow_equation=False):
        matches = re.findall(r"ANSWER:\s*([^\n]+)", result["text"], re.IGNORECASE)
        correct = bool(matches and probe.answer_matches(matches[-1].strip().strip("* .`$"), expected))
        if allow_equation and not correct:
            equation = re.fullmatch(r"\s*(\d+)\s*\+\s*(\d+)\s*=\s*(\d+)\s*", result["text"])
            correct = bool(equation and int(equation[1]) + int(equation[2]) == int(equation[3])
                           and probe.answer_matches(equation[3], expected))
            if correct:
                result["format_observation"] = "Correct complete equation; the model omitted the requested ANSWER prefix."
        assert correct, result["name"] + ": incorrect or incomplete answer"
        assert result["finish_reason"] == "stop" and not result["tool_calls"]

    try:
        # Agora's custom adapter leaves thinking controls out of the request.
        buffered = chat("agora_custom_buffered", prompt_cache_key="synthetic-agora-conversation")
        call = addition_call(buffered)
        assert bool(buffered["reasoning"]) == args.default_thinking
        passed(buffered)
        streamed = chat("agora_custom_streamed", stream=True)
        streamed_call = addition_call(streamed)
        assert streamed_call["function"] == call["function"] and streamed["text"] == buffered["text"]
        assert streamed["reasoning"] == buffered["reasoning"]
        if args.default_thinking:
            assert streamed["first_reasoning_s"] < streamed["first_tool_calls_s"]
        passed(streamed)
        # The synthetic client executes addition locally, then returns its result.
        arguments = json.loads(call["function"]["arguments"])
        followup = MESSAGES + [buffered["message"], {"role": "tool", "tool_call_id": call["id"], "content": str(arguments["a"] + arguments["b"])}]
        completed = chat("agora_tool_result_round_trip", messages=followup, stream=True)
        correct_answer(completed, "40")
        passed(completed)
        # Already completed parallel histories retain result IDs and function names.
        calls = [{"id": "call_left", "type": "function", "function": {"name": "test_add", "arguments": '{"a":17,"b":23}'}},
                 {"id": "call_right", "type": "function", "function": {"name": "test_add", "arguments": '{"a":1,"b":2}'}}]
        history = [{"role": "system", "content": "After receiving both function results, sum their results and reply on one line: ANSWER: value."},
                   {"role": "user", "content": "Add 17 and 23 with test_add; also add 1 and 2 with test_add. Report the sum of both results."},
                   {"role": "assistant", "content": None, "tool_calls": calls, "reasoning_content": "Synthetic prior thought; do not prefill."},
                   {"role": "tool", "tool_call_id": "call_right", "content": "3"},
                   {"role": "tool", "tool_call_id": "call_left", "content": "40"}]
        parallel = chat("parallel_tool_history", messages=history, tool_choice="none", enable_thinking=False)
        correct_answer(parallel, "43", allow_equation=True)
        passed(parallel)
        lookup_tools = [{"type": "function", "function": {"name": "test_lookup", "description": "Look up the opaque color associated with a key.",
                        "parameters": {"type": "object", "properties": {"key": {"type": "string"}}, "required": ["key"]}}}]
        lookup_calls = [{"id": "call_alpha", "type": "function", "function": {"name": "test_lookup", "arguments": '{"key":"alpha"}'}},
                        {"id": "call_beta", "type": "function", "function": {"name": "test_lookup", "arguments": '{"key":"beta"}'}}]
        lookup_history = [{"role": "system", "content": "Use only returned tool values. Reply with the color for the alpha key alone."},
                          {"role": "user", "content": "Look up alpha and beta, then report the color associated with alpha."},
                          {"role": "assistant", "content": None, "tool_calls": lookup_calls},
                          {"role": "tool", "tool_call_id": "call_beta", "content": "BLUE"},
                          {"role": "tool", "tool_call_id": "call_alpha", "content": "RED"}]
        associated = chat("same_function_results_match_ids", messages=lookup_history, tools=lookup_tools, tool_choice="none", enable_thinking=False)
        assert associated["finish_reason"] == "stop" and associated["text"].strip().strip('* .`"').upper() == "RED"
        passed(associated)
        reserved_tools = [{"type": "function", "function": {"name": "test_note", "description": "Record a synthetic note with the given description and type.",
                          "parameters": {"type": "object", "properties": {"description": {"type": "string"}, "type": {"type": "string", "enum": ["note"]}},
                                         "required": ["description", "type"]}}}]
        reserved_messages = [{"role": "system", "content": "Use the provided function to record the note. Do not claim to execute it yourself."},
                             {"role": "user", "content": "Create a note with description local-only and type note."}]
        reserved = chat("schema_metadata_named_properties", messages=reserved_messages, tools=reserved_tools, enable_thinking=False)
        assert reserved["finish_reason"] == "tool_calls" and len(reserved["tool_calls"]) == 1
        assert reserved["tool_calls"][0]["function"]["name"] == "test_note"
        assert json.loads(reserved["tool_calls"][0]["function"]["arguments"]) == {"description": "local-only", "type": "note"}
        passed(reserved)
        normal = [{"role": "system", "content": "Reply on one line: ANSWER: value."}, {"role": "user", "content": "Compute 17 times 23."}]
        for name, control in [("explicit_thinking_off", {"enable_thinking": False}),
                              ("reasoning_effort_on", {"reasoning_effort": "medium"}),
                              ("reasoning_object_on", {"reasoning": {"effort": "medium"}})]:
            result = chat(name, messages=normal, tool_choice="none", **control)
            correct_answer(result, "391")
            assert bool(result["reasoning"]) == (name != "explicit_thinking_off")
            passed(result)
        truncated = chat("truncated_thought_not_executable", stream=True, enable_thinking=True, max_tokens=8)
        assert truncated["finish_reason"] == "length" and not truncated["tool_calls"]
        passed(truncated)
        tool_truncated = chat("truncated_function_not_executable", stream=True, enable_thinking=False, max_tokens=8)
        assert tool_truncated["finish_reason"] == "length" and not tool_truncated["tool_calls"]
        passed(tool_truncated)
        tool_truncated_buffered = chat("truncated_function_buffered", enable_thinking=False, max_tokens=8)
        assert tool_truncated_buffered["finish_reason"] == "length" and not tool_truncated_buffered["tool_calls"]
        assert tool_truncated_buffered["text"] == tool_truncated["text"]
        passed(tool_truncated_buffered)
        recovered = chat("recovery_after_truncated_tools", messages=normal, enable_thinking=False, tool_choice="none")
        correct_answer(recovered, "391")
        passed(recovered)
        for name, extra in [("required_choice_rejected", {"tool_choice": "required"}),
                            ("conflicting_thinking_rejected", {"enable_thinking": False, "reasoning_effort": "medium"}),
                            ("separate_reasoning_budget_rejected", {"reasoning": {"max_tokens": 128}}),
                            ("orphan_tool_result_rejected", {"messages": [{"role": "tool", "tool_call_id": "call_orphan", "content": "40"}]})]:
            rejected = chat(name, status=400, **extra)
            passed(rejected)
        report["completed_at"] = datetime.now().astimezone().isoformat()
        save()
    finally:
        subprocess.run(["adb", "forward", "--remove", "tcp:" + str(port)], check=True)


if __name__ == "__main__":
    main()
