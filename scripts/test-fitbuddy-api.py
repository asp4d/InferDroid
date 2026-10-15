#!/usr/bin/env python3
"""Synthetic FitBuddy-format requests against the installed InferDroid server.

Start its server first. Uses no personal health data and records no API key.
Checks transport and JSON structure, not nutritional accuracy.
"""

import argparse
import base64
from datetime import datetime
import http.client
import importlib.util
import json
from pathlib import Path
import struct
import time
import zlib


spec = importlib.util.spec_from_file_location("probe", Path(__file__).with_name("test-device-reasoning.py"))
probe = importlib.util.module_from_spec(spec)
spec.loader.exec_module(probe)
MODEL = "gemma-4-E2B-it_Google_Tensor_G5"
MEAL_PROMPT = """Analyze the meal described below. Reply with one JSON object, without Markdown.
Use this structure, with integer macro estimates and an entry for each ingredient:
{"status":"SUCCESS","clarification_message":null,"food_analysis":{
"dish_name":"string","icon_topic":"meal",
"macros":{"calories":0,"protein_g":0,"carbs_g":0,"fats_g":0},
"ingredients":[{"name":"string","quantity":1,"weight_g":1,"calories":0,
"protein_g":0,"carbs_g":0,"fats_g":0}]},"exercise_analysis":null}
The meal: two boiled eggs, one slice of wholemeal toast, one banana, and 150 g plain yogurt.
"""


def test_image():
    """A small synthetic blue square, with no food or personal content."""
    def chunk(kind, data):
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data))
    png = b"\x89PNG\r\n\x1a\n"
    png += chunk(b"IHDR", struct.pack(">IIBBBBB", 64, 64, 8, 2, 0, 0, 0))
    png += chunk(b"IDAT", zlib.compress((b"\x00" + b"\x00\x00\xff" * 64) * 64))
    png += chunk(b"IEND", b"")
    return "data:image/png;base64," + base64.b64encode(png).decode()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    if args.output.exists():
        parser.error("Choose a new output path to preserve previous evidence.")
    args.output.parent.mkdir(parents=True, exist_ok=True)
    server_port, key, require_key = probe.api_config()
    forwarded = probe.adb("forward", "tcp:0", "tcp:" + str(server_port)).strip()
    report = {"started_at": datetime.now().astimezone().isoformat(), "results": [],
              "method": "Synthetic FitBuddy wire formats; validates JSON structure, not nutrition."}
    headers = {"Content-Type": "application/json"}
    if require_key:
        headers["Authorization"] = "Bearer " + key

    def save():
        encoded = json.dumps(report, indent=2, ensure_ascii=False) + "\n"
        assert not key or key not in encoded, "Credential unexpectedly appeared in evidence."
        args.output.write_text(encoded)

    def request(name, payload=None, expected=200, path=None):
        record = {"name": name, "passed": False}
        report["results"].append(record)
        connection = http.client.HTTPConnection("127.0.0.1", int(forwarded), timeout=300)
        started = time.monotonic()
        try:
            connection.request("GET" if payload is None else "POST", path or ("/v1/models" if payload is None else "/v1/chat/completions"),
                               None if payload is None else json.dumps(payload), headers)
            response = connection.getresponse()
            record["status"] = response.status
            if payload and payload.get("stream") and response.status == 200:
                frames, content = [], ""
                for raw in response:
                    line = raw.decode().strip()
                    if line == "data: [DONE]":
                        record["done"] = True
                        break
                    if not line.startswith("data: "):
                        continue
                    frame = json.loads(line[6:])
                    frames.append(frame)
                    if "error" in frame:
                        record["error"] = frame["error"]
                    for choice in frame.get("choices", []):
                        content += choice.get("delta", {}).get("content", "")
                        if choice.get("finish_reason"):
                            record["finish_reason"] = choice["finish_reason"]
                record.update(frames=frames, content=content)
            else:
                record["response"] = json.loads(response.read())
                if response.status == 200 and payload:
                    choice = record["response"]["choices"][0]
                    record["content"] = choice["message"]["content"]
                    record["finish_reason"] = choice["finish_reason"]
            assert response.status == expected, f"{name}: status {response.status}"
            return record
        finally:
            record["elapsed_s"] = round(time.monotonic() - started, 3)
            save()
            connection.close()

    def chat(prompt, **extra):
        return {"model": MODEL, "messages": [{"role": "user", "content": [{"type": "text", "text": prompt}]}],
                "temperature": 0.2, "enable_thinking": False, "response_format": {"type": "json_object"}, **extra}

    def passed(record):
        record["passed"] = True
        save()
        print(json.dumps({k: record.get(k) for k in ("name", "status", "elapsed_s", "finish_reason", "passed")}), flush=True)

    try:
        models = request("model_discovery")
        listing = models["response"]
        standard = next(m for m in listing["data"] if m["id"] == MODEL)
        assert "image" in standard["architecture"]["input_modalities"]
        assert any(m.get("model") == MODEL and "vision" in m.get("capabilities", []) for m in listing["models"])
        passed(models)

        probe_request = {"model": MODEL, "messages": [{"role": "user", "content": "ping"}], "temperature": 0, "max_tokens": 1}
        reachable = request("fitbuddy_refresh_with_versioned_base", probe_request, path="/v1/v1/chat/completions")
        assert reachable["finish_reason"] == "length" and reachable["response"]["usage"]["completion_tokens"] == 1
        passed(reachable)

        meal = request("fitbuddy_text_meal", chat(MEAL_PROMPT))
        food = json.loads(meal["content"])
        assert meal["finish_reason"] == "stop" and food["status"] == "SUCCESS"
        # FitBuddy's Moshi DTO permits an absent nullable exercise_analysis field.
        assert food.get("exercise_analysis") is None and isinstance(food["food_analysis"]["dish_name"], str)
        assert len(food["food_analysis"]["ingredients"]) >= 4
        macro_fields = ("calories", "protein_g", "carbs_g", "fats_g")
        assert all(type(food["food_analysis"]["macros"][field]) is int for field in macro_fields)
        for ingredient in food["food_analysis"]["ingredients"]:
            assert isinstance(ingredient["name"], str)
            assert all(type(ingredient[field]) is int for field in ("weight_g", *macro_fields))
        passed(meal)

        photo = chat('Analyze the attached image for food. If it contains no food, return '
                     '{"status":"NOT_IDENTIFIED","clarification_message":"a short reason",'
                     '"food_analysis":null,"exercise_analysis":null}. Do not invent a meal.')
        photo["messages"][0]["content"].append({"type": "image_url", "image_url": {"url": test_image()}})
        image = request("fitbuddy_photo_nonfood", photo)
        data = json.loads(image["content"])
        assert image["finish_reason"] == "stop" and data["status"] == "NOT_IDENTIFIED" and data["food_analysis"] is None
        passed(image)

        stream = request("json_sse", chat('Return a JSON object with "status" equal to "OK" and "value" equal to 391.',
                                           stream=True, stream_options={"include_usage": True}))
        assert stream.get("done") and not stream.get("error") and stream["finish_reason"] == "stop"
        assert json.loads(stream["content"]) == {"status": "OK", "value": 391}
        assert any(frame.get("usage") for frame in stream["frames"])
        passed(stream)

        capped = request("incomplete_json_error", chat(MEAL_PROMPT, max_tokens=4), expected=500)
        assert capped["response"]["error"]["code"] == "invalid_json_output"
        passed(capped)

        invalid = request("unsupported_schema_still_rejected", chat("Hello", response_format={"type": "json_schema"}), expected=400)
        assert invalid["response"]["error"]["param"] == "response_format"
        passed(invalid)

        ordinary = chat("Compute 17 times 23. Reply only with the number.", response_format={"type": "text"},
                        stream=True, stream_options={"include_usage": True}, temperature=0)
        normal = request("ordinary_sse_after_json", ordinary)
        assert normal.get("done") and normal["finish_reason"] == "stop" and normal["content"].strip() == "391"
        passed(normal)
    finally:
        probe.adb("forward", "--remove", "tcp:" + forwarded)


if __name__ == "__main__":
    main()
