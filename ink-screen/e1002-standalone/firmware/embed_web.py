"""Embed the actual offline phone UI; no uploadfs step or external CDN."""
from pathlib import Path
Import("env")
root = Path(env.subst("$PROJECT_DIR"))
out = Path(env.subst("$BUILD_DIR")) / "generated"
out.mkdir(parents=True, exist_ok=True)
parts = ["#pragma once\n"]
for name in ("setup", "upload"):
    body = (root / "web" / (name + ".html")).read_text()
    parts.append(f'const char {name.upper()}_HTML[] PROGMEM = R"E1002WEB({body})E1002WEB";\n')
(out / "web_assets.h").write_text("".join(parts))
env.Append(CPPPATH=[str(out)])
