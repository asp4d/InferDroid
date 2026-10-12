#!/usr/bin/env python3
"""Verify native thinking through the installed debug app and a connected Pixel.

Start InferDroid's chat server before running. Synthetic inputs and outputs are
saved; the app's saved API key is used in memory only. No model downloads.
"""

import argparse
from datetime import datetime
import http.client
import importlib.util
import json
from pathlib import Path
import re
import time


spec = importlib.util.spec_from_file_location("reasoning_probe", Path(__file__).with_name("test-device-reasoning.py"))
probe = importlib.util.module_from_spec(spec)
spec.loader.exec_module(probe)
MODEL = "gemma-4-E2B-it_Google_Tensor_G5"
SYSTEM = "Answer concisely. End with ANSWER: value."


class Client:
    def __init__(self, port, key, require_key, report, output):
        self.port, self.key, self.require_key = port, key, require_key
        self.report, self.output = report, output

    def save(self, result):
        self.report["results"].append(result)
        self.output.write_text(json.dumps(self.report, indent=2, ensure_ascii=False) + "\n")

    def chat(self, name, *, thinking=None, stream=False, cap=None, messages=None, expected_status=200):
        if messages is None:
            messages = [{"role": "system", "content": SYSTEM},
                        {"role": "user", "content": "Compute 17 times 23."}]
        payload = {"model": MODEL, "messages": messages, "temperature": 0, "seed": 42, "stream": stream}
        if thinking is not None:
            payload["enable_thinking"] = thinking
        if cap is not None:
            payload["max_completion_tokens"] = cap
        if stream:
            payload["stream_options"] = {"include_usage": True}
        headers = {"Content-Type": "application/json"}
        if self.require_key:
            headers["Authorization"] = "Bearer " + self.key
        connection = http.client.HTTPConnection("127.0.0.1", self.port, timeout=300)
        start = time.monotonic()
        result = {"name": name, "request": payload, "text": "", "reasoning": "",
                  "first_content_s": None, "first_reasoning_s": None}
        try:
            connection.request("POST", "/v1/chat/completions", json.dumps(payload), headers)
            response = connection.getresponse()
            result["status"] = response.status
            if response.status != 200 or not stream:
                result["response"] = json.loads(response.read())
                if response.status == 200:
                    choice = result["response"]["choices"][0]
                    result["message"] = choice["message"]
                    result["text"] = choice["message"]["content"]
                    result["reasoning"] = choice["message"].get("reasoning_content", "")
                    result["finish_reason"] = choice["finish_reason"]
                    result["usage"] = result["response"].get("usage")
            else:
                for raw in response:
                    line = raw.decode().strip()
                    if not line.startswith("data: "):
                        continue
                    data = line[6:]
                    if data == "[DONE]":
                        result["done"] = True
                        break
                    frame = json.loads(data)
                    if "error" in frame:
                        result["error"] = frame["error"]
                    if frame.get("usage"):
                        result["usage"] = frame["usage"]
                    for choice in frame.get("choices", []):
                        delta = choice.get("delta", {})
                        for field, target, first in [("content", "text", "first_content_s"),
                                                     ("reasoning_content", "reasoning", "first_reasoning_s")]:
                            value = delta.get(field, "")
                            if value:
                                if result[first] is None:
                                    result[first] = round(time.monotonic() - start, 3)
                                result[target] += value
                        if choice.get("finish_reason"):
                            result["finish_reason"] = choice["finish_reason"]
            result["elapsed_s"] = round(time.monotonic() - start, 3)
            self.save(result)
        finally:
            connection.close()
        allowed = (expected_status,) if isinstance(expected_status, int) else expected_status
        assert result["status"] in allowed, f"{name}: unexpected status {result['status']}"
        if result["status"] == 200:
            assert not result.get("error"), f"{name}: {result.get('error')}"
            assert result.get("usage"), f"{name}: missing runtime token counts"
            if stream:
                assert result.get("done"), f"{name}: incomplete SSE transport"
            for marker in ["<|channel>", "<channel|>", "<|think|>"]:
                assert marker not in result["text"] + result["reasoning"], f"{name}: raw delimiter leaked"
        print(json.dumps({"name": name, "status": result["status"], "elapsed_s": result["elapsed_s"],
                          "reasoning_chars": len(result["reasoning"]), "finish": result.get("finish_reason"),
                          "usage": result.get("usage")}), flush=True)
        return result

    def disconnect_during_reasoning(self):
        payload = {"model": MODEL, "messages": [{"role": "user", "content": "Explain every step when counting the integers from 1 to 200."}],
                   "enable_thinking": True, "stream": True, "max_completion_tokens": 4096, "temperature": 0}
        headers = {"Content-Type": "application/json"}
        if self.require_key:
            headers["Authorization"] = "Bearer " + self.key
        connection = http.client.HTTPConnection("127.0.0.1", self.port, timeout=60)
        start = time.monotonic()
        response = None
        try:
            connection.request("POST", "/v1/chat/completions", json.dumps(payload), headers)
            response = connection.getresponse()
            assert response.status == 200, "cancellation request not admitted"
            for raw in response:
                line = raw.decode().strip()
                if not line.startswith("data: ") or line == "data: [DONE]":
                    continue
                frame = json.loads(line[6:])
                assert not frame.get("error"), "reasoning request failed before disconnect"
                if any(c.get("delta", {}).get("reasoning_content") for c in frame.get("choices", [])):
                    self.save({"name": "disconnect_during_native_reasoning", "request": payload,
                               "status": 200, "elapsed_s": round(time.monotonic() - start, 3),
                               "disconnected_after_reasoning": True})
                    return
            raise AssertionError("no live reasoning before disconnect")
        finally:
            if response is not None:
                response.close()
            connection.close()


def correct(result):
    answers = re.findall(r"^\s*ANSWER:\s*([^\n]+)", result["text"], flags=re.IGNORECASE | re.MULTILINE)
    answer = (answers[-1] if answers else result["text"]).strip().strip("* .`$")
    assert result["finish_reason"] == "stop" and probe.answer_matches(answer, "391"), result["name"] + ": incorrect or incomplete final answer"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--host-port", type=int, default=18082)
    parser.add_argument("--package", default="dev.inferdroid", help="Debug application ID to test")
    args = parser.parse_args()
    if args.output.exists():
        parser.error("Choose a new output path to preserve previous evidence.")
    args.output.parent.mkdir(parents=True, exist_ok=True)
    device_port, key, require_key = probe.api_config(args.package)
    installed = probe.adb("shell", "dumpsys", "package", args.package)
    version = re.search(r"versionName=(\S+)", installed)
    report = {"started_at": datetime.now().astimezone().isoformat(),
              "device": probe.adb("shell", "getprop", "ro.product.model").strip(),
              "application_id": args.package,
              "installed_version": version.group(1) if version else None, "results": []}
    client = Client(args.host_port, key, require_key, report, args.output)
    probe.adb("forward", "--no-rebind", f"tcp:{args.host_port}", f"tcp:{device_port}")
    try:
        direct = client.chat("default_direct")
        correct(direct)
        assert not direct["reasoning"] and "reasoning_content" not in direct["message"]
        buffered = client.chat("thinking_buffered_default_budget", thinking=True)
        correct(buffered)
        assert buffered["reasoning"]
        streamed = client.chat("thinking_live_sse", thinking=True, stream=True)
        correct(streamed)
        assert streamed["reasoning"] and streamed["first_reasoning_s"] < streamed["first_content_s"]
        assert streamed["first_reasoning_s"] < streamed["elapsed_s"] / 2, "reasoning buffered until completion"
        assert streamed["text"] == buffered["text"] and streamed["reasoning"] == buffered["reasoning"]
        for stream in [False, True]:
            truncated = client.chat("truncated_thoughts_sse" if stream else "truncated_thoughts_buffered",
                                    thinking=True, cap=16, stream=stream)
            assert truncated["finish_reason"] == "length" and truncated["reasoning"] and not truncated["text"]
            assert truncated["usage"]["completion_tokens"] == 16
        disabled = client.chat("explicit_disable_after_truncation", thinking=False)
        correct(disabled)
        assert not disabled["reasoning"]
        history = [dict(x) for x in buffered["request"]["messages"]]
        history.extend([dict(buffered["message"]), {"role": "user", "content": "What was the product? Reply with only the number."}])
        echoed = client.chat("assistant_history_echo", messages=history)
        correct(echoed)
        assert not echoed["reasoning"]
        history[-2].pop("reasoning_content")
        stripped = client.chat("assistant_history_without_thoughts", messages=history)
        correct(stripped)
        assert echoed["usage"]["prompt_tokens"] == stripped["usage"]["prompt_tokens"]
        assert echoed["text"] == stripped["text"], "historical reasoning influenced prefill"
        client.chat("invalid_thinking_type", thinking="true", expected_status=400)
        client.chat("invalid_output_cap", cap=4097, expected_status=400)
        capped = client.chat("expanded_4096_cap", thinking=True, cap=4096)
        correct(capped)
        assert capped["reasoning"]
        client.disconnect_during_reasoning()
        deadline = time.monotonic() + 30
        while True:
            recovered = client.chat("recovery_after_native_disconnect", expected_status=(200, 429))
            if recovered["status"] == 200:
                correct(recovered)
                assert not recovered["reasoning"]
                break
            assert time.monotonic() < deadline, "native cancellation did not drain"
            time.sleep(0.2)
        report["passed"] = True
        args.output.write_text(json.dumps(report, indent=2, ensure_ascii=False) + "\n")
        print("Native thinking API checks passed.", flush=True)
    finally:
        probe.adb("forward", "--remove", f"tcp:{args.host_port}")


if __name__ == "__main__":
    main()
