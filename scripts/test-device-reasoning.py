#!/usr/bin/env python3
"""Synthetic reasoning probes against the installed app; never records API keys.

Uses enable_thinking for current builds. The marker mode reproduces the earlier
experiment on builds without the API switch. Never records API keys.
"""

import argparse
from datetime import datetime
from fractions import Fraction
import http.client
import json
import re
import subprocess
import time
import xml.etree.ElementTree as ET
from pathlib import Path


CASES = [
    ("multiplication", "Compute 17 times 23.", "391"),
    ("percentage", "A price starts at 100 euros, increases by 20%, then decreases by 20%. What is the final price in euros?", "96"),
    ("work_rate", "One pump fills a tank in 6 hours and another in 12 hours. Both run together at constant rates. How many hours to fill one empty tank?", "4"),
    ("probability", "A bag has 3 blue balls and 2 red balls. Draw two balls uniformly without replacement. What is the probability that both are blue? Give a reduced fraction.", "3/10"),
    ("code_trace", "Evaluate this Python code: a = 0; xs = [8, 3, 5, 2]. For each i, x in enumerate(xs), add x to a if i % 2 == 0; otherwise subtract x from a. What is the final a?", "8"),
    ("deduction", "Every employee with a gold badge can enter the lab. Lina can enter the lab. Does it follow with certainty that Lina has a gold badge? Answer YES or NO.", "NO"),
    ("arrangements", "Count the orders of four distinct people A, B, C, D in a line subject to all these rules: A is before C; D is immediately before B; C is not last. How many orders are valid?", "1"),
    ("italian", "Un treno percorre 90 km a 60 km/h, poi 90 km a 90 km/h. Qual e la velocita media dell'intero viaggio, in km/h?", "72"),
]

SYSTEM = "Answer concisely. Put your final answer on a line formatted ANSWER: value."


def adb(*args):
    return subprocess.check_output(["adb", *args], text=True, timeout=30)


def api_config(package="dev.inferdroid"):
    root = ET.fromstring(adb("shell", "run-as", package, "cat", "shared_prefs/server.xml"))
    entries = {e.attrib["name"]: e.attrib.get("value", e.text or "") for e in root}
    return int(entries.get("port", "8080")), entries.get("api_key", ""), entries.get("require_key", "true") == "true"


def answer_matches(answer, expected):
    if answer is None:
        return False
    if answer.upper() == expected.upper():
        return True
    # Accept equivalent numbers with the units requested in these problems.
    match = re.fullmatch(r"([+-]?\d+(?:\.\d+)?(?:/\d+)?)\s*(?:hours?|euros?|km/h)?", answer, re.IGNORECASE)
    if match:
        try:
            return Fraction(match.group(1)) == Fraction(expected)
        except (ValueError, ZeroDivisionError):
            return False
    return False


def probe(host_port, key, require_key, case, mode, cap, system=SYSTEM):
    name, prompt, expected = case
    messages = [{"role": "system", "content": ("<|think|>\n" if mode == "marker" else "") + system}]
    messages.append({"role": "user", "content": prompt})
    payload = {"model": "gemma-4-E2B-it_Google_Tensor_G5", "messages": messages,
               "temperature": 0, "seed": 42, "max_tokens": cap,
               "stream": True, "stream_options": {"include_usage": True}}
    headers = {"Content-Type": "application/json"}
    if require_key:
        headers["Authorization"] = "Bearer " + key
    connection = http.client.HTTPConnection("127.0.0.1", host_port, timeout=180)
    start = time.monotonic()
    result = {"case": name, "mode": mode, "cap": cap, "prompt": prompt, "expected": expected,
              "first_text_s": None, "first_reasoning_s": None, "text": "", "reasoning": "",
              "frames": [], "frame_elapsed_s": [], "finish_reason": None}
    if mode == "thinking":
        payload["enable_thinking"] = True
    try:
        connection.request("POST", "/v1/chat/completions", json.dumps(payload), headers)
        response = connection.getresponse()
        result["status"] = response.status
        if response.status != 200:
            result["error"] = response.read().decode()
        else:
            for line in response:
                line = line.decode().strip()
                if not line.startswith("data: "):
                    continue
                data = line[6:]
                if data == "[DONE]":
                    result["done"] = True
                    break
                frame = json.loads(data)
                result["frames"].append(frame)
                result["frame_elapsed_s"].append(round(time.monotonic() - start, 3))
                if frame.get("usage"):
                    result["usage"] = frame["usage"]
                if "error" in frame:
                    result["error"] = frame["error"]
                for choice in frame.get("choices", []):
                    thought = choice.get("delta", {}).get("reasoning_content", "")
                    if thought:
                        if result["first_reasoning_s"] is None:
                            result["first_reasoning_s"] = round(time.monotonic() - start, 3)
                        result["reasoning"] += thought
                    delta = choice.get("delta", {}).get("content", "")
                    if delta:
                        if result["first_text_s"] is None:
                            result["first_text_s"] = round(time.monotonic() - start, 3)
                        result["text"] += delta
                    if choice.get("finish_reason"):
                        result["finish_reason"] = choice["finish_reason"]
        result["elapsed_s"] = round(time.monotonic() - start, 3)
        result["has_thought_marker"] = "<|channel>thought" in result["text"]
        result["has_thought_end"] = "<channel|>" in result["text"]
        final_text = result["text"].rsplit("<channel|>", 1)[-1]
        if result["has_thought_marker"] and not result["has_thought_end"]:
            final_text = ""
        answers = re.findall(r"ANSWER:\s*([^\n]+)", final_text, flags=re.IGNORECASE)
        result["answer"] = answers[-1].strip().strip("* .`$") if answers else None
        result["exact_answer_match"] = result["answer"] == expected
        result["answer_correct"] = answer_matches(result["answer"], expected)
        result["correct"] = result["answer_correct"] and result["finish_reason"] == "stop" and result.get("done", False) and not result.get("error")
    finally:
        connection.close()
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--caps", nargs="+", type=int, default=[1024])
    parser.add_argument("--modes", nargs="+", choices=["plain", "thinking", "marker"], default=["plain", "thinking"])
    parser.add_argument("--cases", nargs="+", default=[c[0] for c in CASES])
    parser.add_argument("--host-port", type=int, default=18081)
    parser.add_argument("--package", default="dev.inferdroid", help="Debug application ID to test")
    parser.add_argument("--system", default=SYSTEM)
    args = parser.parse_args()
    if set(args.cases) - {case[0] for case in CASES}:
        parser.error("Unknown case name; see CASES in this script.")
    if args.output.exists():
        parser.error("Output already exists; choose a new path to preserve previous evidence.")
    port, key, require_key = api_config(args.package)
    installed = adb("shell", "dumpsys", "package", args.package)
    version = re.search(r"versionName=(\S+)", installed)
    report = {"started_at": datetime.now().astimezone().isoformat(), "device": adb("shell", "getprop", "ro.product.model").strip(),
              "serial": adb("get-serialno").strip(),
              "application_id": args.package,
              "installed_version": version.group(1) if version else None,
              "system_prompt": args.system,
              "method": "plain: default configuration; thinking: enable_thinking=true; marker: legacy system token",
              "results": []}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    adb("forward", "--no-rebind", f"tcp:{args.host_port}", f"tcp:{port}")
    try:
        for cap in args.caps:
            for case in CASES:
                if case[0] not in args.cases:
                    continue
                for mode in args.modes:
                    result = probe(args.host_port, key, require_key, case, mode, cap, args.system)
                    report["results"].append(result)
                    args.output.write_text(json.dumps(report, indent=2) + "\n")
                    print(json.dumps({k: result.get(k) for k in ["case", "mode", "cap", "status", "elapsed_s", "first_text_s", "usage", "finish_reason", "answer", "correct", "error"]}), flush=True)
    finally:
        adb("forward", "--remove", f"tcp:{args.host_port}")


if __name__ == "__main__":
    main()
