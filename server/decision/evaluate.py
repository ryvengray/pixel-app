"""Run against a real loaded BGE sidecar: python server/decision/evaluate.py."""
import json
import sys
from pathlib import Path
from urllib.request import Request, urlopen

url = sys.argv[1] if len(sys.argv) > 1 else "http://127.0.0.1:18081"
cases = json.loads(Path(__file__).with_name("cases.json").read_text(encoding="utf-8"))
failed = 0
for case in cases:
    payload = {"text": case["text"], "available_tools": ["open_sync", "open_print", "open_topics", "list_reminders", "create_reminder"]}
    with urlopen(Request(url.rstrip("/") + "/decide", data=json.dumps(payload).encode(), headers={"Content-Type": "application/json"}), timeout=10) as response:
        result = json.load(response)
    ok = result["type"] == case["type"] and result.get("tool") == case.get("tool")
    failed += not ok
    print(json.dumps({"pass": ok, "text": case["text"], "expected": case, "actual": result}, ensure_ascii=False))
print(f"{len(cases)-failed}/{len(cases)} passed")
sys.exit(bool(failed))
