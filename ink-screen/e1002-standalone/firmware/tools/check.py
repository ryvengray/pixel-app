#!/usr/bin/env python3
"""Run portable logic, filesystem fault injection, and actual phone JavaScript tests."""
from pathlib import Path
import os
import subprocess
import tempfile

root = Path(__file__).resolve().parents[1]
with tempfile.TemporaryDirectory(prefix="e1002-tests-") as directory:
    for name in ("core", "album", "network", "settings"):
        output = str(Path(directory) / name)
        subprocess.run([
            os.environ.get("CXX", "c++"), "-std=c++17", "-Wall", "-Wextra", "-Werror",
            "-I", str(root / "tests/fakes"), "-I", str(root / "include"),
            "-I", str(root / ".pio/libdeps/e1002-standalone/ArduinoJson/src"),
            str(root / "tests" / f"{name}_test.cpp"), "-o", output,
        ], check=True)
        subprocess.run([output], check=True)
subprocess.run(["node", str(root / "tests/web_test.mjs")], check=True)
