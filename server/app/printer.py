from __future__ import annotations

import asyncio
import secrets
import socket
import struct
import subprocess
import tempfile
from pathlib import Path
from typing import Any

import httpx
from zeroconf import ServiceBrowser, ServiceStateChange, Zeroconf

from .main import connect, utc_now

# ---------------------------------------------------------------------------
# IPP protocol helpers
# ---------------------------------------------------------------------------

def _attr(tag: int, name: str, value: bytes) -> bytes:
    n = name.encode("utf-8")
    return struct.pack(">B", tag) + struct.pack(">H", len(n)) + n + struct.pack(">H", len(value)) + value


def _str_attr(tag: int, name: str, value: str) -> bytes:
    return _attr(tag, name, value.encode("utf-8"))


def _int_attr(tag: int, name: str, value: int) -> bytes:
    return _attr(tag, name, struct.pack(">i", value))


def _build_request(operation: int, printer_uri: str, extra_ops: bytes = b"", job_attrs: bytes = b"", data: bytes = b"") -> bytes:
    req = struct.pack(">BBHI", 2, 0, operation, 1)
    req += b"\x01"  # operation-attributes-tag
    req += _str_attr(0x47, "attributes-charset", "utf-8")
    req += _str_attr(0x48, "attributes-natural-language", "zh-cn")
    req += _str_attr(0x45, "printer-uri", printer_uri)
    req += extra_ops
    if job_attrs:
        req += b"\x02" + job_attrs  # job-attributes-tag
    req += b"\x03"  # end-of-attributes-tag
    return req + data


def _printer_uri(host: str, port: int, scheme: str = "ipp") -> str:
    return f"{scheme}://{host}:{port}/ipp/print"


# ---------------------------------------------------------------------------
# Printer status check (IPP Get-Printer-Attributes)
# ---------------------------------------------------------------------------

async def check_online(host: str, port: int = 631) -> tuple[bool, str]:
    """Returns (online, printer_state_description)."""
    ipp_paths = ["/ipp/print", "/ipp", "/printers/ipp", ""]
    for path in ipp_paths:
        uri = f"ipp://{host}:{port}{path}"
        body = _build_request(0x000B, uri)
        try:
            async with httpx.AsyncClient(timeout=3, trust_env=False) as client:
                resp = await client.post(
                    f"http://{host}:{port}{path}",
                    content=body,
                    headers={"Content-Type": "application/ipp"},
                )
                if resp.status_code == 200:
                    raw = resp.content
                    state = "idle"
                    idx = 9
                    while idx < len(raw) - 4:
                        tag = raw[idx]
                        if tag == 0x03:
                            break
                        if tag in (0x01, 0x02, 0x04, 0x05):
                            idx += 1
                            continue
                        name_len = struct.unpack(">H", raw[idx + 1: idx + 3])[0]
                        name = raw[idx + 3: idx + 3 + name_len].decode("utf-8", errors="replace")
                        val_len = struct.unpack(">H", raw[idx + 3 + name_len: idx + 5 + name_len])[0]
                        val_start = idx + 5 + name_len
                        if name == "printer-state" and val_len == 4:
                            state_num = struct.unpack(">i", raw[val_start: val_start + 4])[0]
                            state = {3: "idle", 4: "processing", 5: "stopped"}.get(state_num, "unknown")
                        idx = val_start + val_len
                    return True, state
        except Exception:
            continue
    try:
        reader, writer = await asyncio.wait_for(asyncio.open_connection(host, port), timeout=2)
        writer.close()
        await writer.wait_closed()
        return True, "idle"
    except Exception:
        pass
    return False, "offline"


# ---------------------------------------------------------------------------
# Print job submission (IPP Print-Job)
# ---------------------------------------------------------------------------

DOC_FORMATS = {
    ".pdf": "application/pdf",
    ".jpg": "image/jpeg",
    ".jpeg": "image/jpeg",
    ".png": "image/png",
    ".txt": "text/plain",
}


async def send_print_job(host: str, port: int, data: bytes, job_name: str, doc_format: str, copies: int = 1, paper_size: str = "a4", color_mode: str = "monochrome") -> dict[str, Any]:
    import logging
    logger = logging.getLogger("uvicorn.error")

    uri = _printer_uri(host, port)
    url = uri.replace("ipp://", "http://").replace("ipps://", "https://")
    media = {"a4": "iso_a4_210x297mm", "a5": "iso_a5_148x210mm", "letter": "na_letter_8.5x11in"}[paper_size]

    job_attrs = _str_attr(0x44, "media", media) + _str_attr(0x44, "print-color-mode", "auto")
    if copies > 1:
        job_attrs = _int_attr(0x21, "copies", copies) + job_attrs

    extra = _str_attr(0x42, "job-name", job_name) + _str_attr(0x49, "document-format", doc_format)
    body = _build_request(0x0002, uri, extra_ops=extra, job_attrs=job_attrs, data=data)
    logger.info("Sending print job to %s (%d bytes)", url, len(body))
    try:
        timeout = httpx.Timeout(connect=10, read=60, write=300, pool=10)
        async with httpx.AsyncClient(timeout=timeout, trust_env=False) as client:
            resp = await client.post(url, content=body, headers={"Content-Type": "application/ipp"})
            if resp.status_code == 200:
                status_code = struct.unpack(">H", resp.content[2:4])[0] if len(resp.content) >= 4 else -1
                if status_code == 0x0000:
                    return {"ok": True}
                error = f"IPP status {status_code:#06x}"
                logger.error("Print job failed: %s", error)
                return {"ok": False, "error": error}
            error = f"HTTP {resp.status_code}"
            logger.error("Print job failed: %s", error)
            return {"ok": False, "error": error}
    except Exception as exc:
        import traceback
        logger.error("Print job exception: %s", exc, exc_info=True)
        return {"ok": False, "error": str(exc) or repr(exc)}


# ---------------------------------------------------------------------------
# mDNS printer discovery
# ---------------------------------------------------------------------------

SERVICE_TYPES = ["_ipp._tcp.local.", "_ipps._tcp.local."]


def discover_printers_sync(timeout: float = 4.0) -> list[dict[str, Any]]:
    found: dict[str, dict[str, Any]] = {}

    class Listener:
        def add_service(self, zc: Zeroconf, type_: str, name: str) -> None:
            info = zc.get_service_info(type_, name, timeout=1000)
            if not info or not info.addresses:
                return
            host = socket.inet_ntoa(info.addresses[0])
            props = {k.decode(): v.decode() if isinstance(v, bytes) else v for k, v in info.properties.items()}
            friendly = props.get("note") or props.get("ty") or name.split(".")[0]
            scheme = "ipps" if type_ == "_ipps._tcp.local." else "ipp"
            found[host] = {
                "name": friendly,
                "host": host,
                "port": info.port or 631,
                "protocol": scheme,
                "model": props.get("ty", ""),
                "location": props.get("note", ""),
            }

        def remove_service(self, zc: Zeroconf, type_: str, name: str) -> None:
            pass

        def update_service(self, zc: Zeroconf, type_: str, name: str) -> None:
            pass

    zc = Zeroconf()
    browsers = [ServiceBrowser(zc, stype, Listener()) for stype in SERVICE_TYPES]
    import time
    time.sleep(timeout)
    for b in browsers:
        b.cancel()
    zc.close()
    return list(found.values())


async def discover_printers() -> list[dict[str, Any]]:
    loop = asyncio.get_event_loop()
    return await loop.run_in_executor(None, discover_printers_sync)


# ---------------------------------------------------------------------------
# Document conversion (LibreOffice / Pillow)
# ---------------------------------------------------------------------------

def convert_to_pdf(source: Path, output_dir: Path) -> Path:
    """Convert Word/Excel/text to PDF using LibreOffice. Returns the PDF path."""
    result = subprocess.run(
        ["libreoffice", "--headless", "--convert-to", "pdf", "--outdir", str(output_dir), str(source)],
        capture_output=True, timeout=120,
    )
    pdf_path = output_dir / (source.stem + ".pdf")
    if result.returncode != 0 or not pdf_path.exists():
        raise RuntimeError(f"LibreOffice conversion failed: {result.stderr.decode(errors='replace')}")
    return pdf_path


def image_to_pdf(source: Path, output_dir: Path) -> Path:
    """Convert an image file to a single-page PDF using Pillow."""
    from PIL import Image
    pdf_path = output_dir / (source.stem + ".pdf")
    with Image.open(source) as img:
        rgb = img.convert("RGB")
        rgb.save(str(pdf_path), "PDF", resolution=150)
    return pdf_path


def text_to_pdf(text: str, output_dir: Path, job_name: str = "text") -> Path:
    """Write text to a .txt file then convert via LibreOffice for CJK support."""
    txt_path = output_dir / f"{job_name}.txt"
    txt_path.write_text(text, encoding="utf-8")
    return convert_to_pdf(txt_path, output_dir)


# ---------------------------------------------------------------------------
# Database helpers
# ---------------------------------------------------------------------------

def save_printer(printer: dict[str, Any]) -> str:
    printer_id = secrets.token_urlsafe(12)
    now = utc_now()
    with connect() as db:
        existing = db.execute("SELECT id FROM printer WHERE host = ?", (printer["host"],)).fetchone()
        if existing:
            db.execute(
                "UPDATE printer SET name=?, port=?, protocol=?, model=?, location=?, last_seen=? WHERE id=?",
                (printer["name"], printer["port"], printer["protocol"], printer.get("model", ""),
                 printer.get("location", ""), now, existing["id"]),
            )
            return existing["id"]
        db.execute(
            "INSERT INTO printer(id,name,host,port,protocol,model,location,last_seen,added_at) VALUES(?,?,?,?,?,?,?,?,?)",
            (printer_id, printer["name"], printer["host"], printer["port"], printer["protocol"],
             printer.get("model", ""), printer.get("location", ""), now, now),
        )
    return printer_id


def list_printers() -> list[dict[str, Any]]:
    with connect() as db:
        return [dict(row) for row in db.execute("SELECT * FROM printer ORDER BY name").fetchall()]


def get_printer(printer_id: str) -> dict[str, Any] | None:
    with connect() as db:
        row = db.execute("SELECT * FROM printer WHERE id = ?", (printer_id,)).fetchone()
    return dict(row) if row else None


def delete_printer(printer_id: str) -> bool:
    with connect() as db:
        return db.execute("DELETE FROM printer WHERE id = ?", (printer_id,)).rowcount > 0


def touch_printer(printer_id: str) -> None:
    with connect() as db:
        db.execute("UPDATE printer SET last_seen = ? WHERE id = ?", (utc_now(), printer_id))


# ---------------------------------------------------------------------------
# Print job DB helpers + background processing
# ---------------------------------------------------------------------------

IMAGE_SUFFIXES = {".jpg", ".jpeg", ".png"}
ALLOWED_SUFFIXES = {".pdf", ".jpg", ".jpeg", ".png", ".docx", ".doc", ".xlsx", ".xls"}

_print_lock = asyncio.Lock()


def create_print_job(job_id: str, printer_id: str, file_name: str, file_type: str, copies: int) -> None:
    with connect() as db:
        db.execute(
            "INSERT INTO print_job(id,printer_id,file_name,file_type,copies,status,created_at) VALUES(?,?,?,?,?,?,?)",
            (job_id, printer_id, file_name, file_type, copies, "pending", utc_now()),
        )


def update_print_job(job_id: str, *, status: str, error: str | None = None, completed: bool = False) -> None:
    with connect() as db:
        if completed:
            db.execute(
                "UPDATE print_job SET status=?, error=?, completed_at=? WHERE id=?",
                (status, error, utc_now(), job_id),
            )
        else:
            db.execute("UPDATE print_job SET status=? WHERE id=?", (status, job_id))


def get_print_job(job_id: str) -> dict[str, Any] | None:
    with connect() as db:
        row = db.execute("SELECT * FROM print_job WHERE id = ?", (job_id,)).fetchone()
    return dict(row) if row else None


def list_print_jobs(limit: int = 20) -> list[dict[str, Any]]:
    with connect() as db:
        return [dict(row) for row in db.execute(
            "SELECT * FROM print_job ORDER BY created_at DESC LIMIT ?", (limit,)
        ).fetchall()]


async def process_print_job(
    job_id: str,
    printer: dict[str, Any],
    *,
    text: str | None = None,
    file_bytes: bytes | None = None,
    suffix: str | None = None,
    job_name: str,
    copies: int,
    paper_size: str,
    color_mode: str,
) -> None:
    import logging
    logger = logging.getLogger("uvicorn.error")

    update_print_job(job_id, status="printing")
    try:
        async with _print_lock:
            loop = asyncio.get_running_loop()
            if text is not None:
                with tempfile.TemporaryDirectory() as tmp:
                    pdf_path = await loop.run_in_executor(
                        None, text_to_pdf, text, Path(tmp), f"job_{job_id}"
                    )
                    pdf_bytes = pdf_path.read_bytes()
            elif suffix == ".pdf":
                pdf_bytes = file_bytes
            elif suffix in IMAGE_SUFFIXES:
                with tempfile.TemporaryDirectory() as tmp:
                    tmp_dir = Path(tmp)
                    src = tmp_dir / f"upload_{job_id}{suffix}"
                    src.write_bytes(file_bytes)
                    pdf_path = await loop.run_in_executor(None, image_to_pdf, src, tmp_dir)
                    pdf_bytes = pdf_path.read_bytes()
            else:
                with tempfile.TemporaryDirectory() as tmp:
                    tmp_dir = Path(tmp)
                    src = tmp_dir / f"upload_{job_id}{suffix}"
                    src.write_bytes(file_bytes)
                    pdf_path = await loop.run_in_executor(None, convert_to_pdf, src, tmp_dir)
                    pdf_bytes = pdf_path.read_bytes()

        result = await send_print_job(
            printer["host"], printer["port"], pdf_bytes,
            job_name, "application/pdf", copies, paper_size, color_mode,
        )
        if result.get("ok"):
            update_print_job(job_id, status="completed", completed=True)
        else:
            update_print_job(job_id, status="failed", error=result.get("error") or "Print job failed", completed=True)
    except Exception as exc:
        logger.error("Background print job %s failed: %s", job_id, exc, exc_info=True)
        update_print_job(job_id, status="failed", error=str(exc) or repr(exc), completed=True)
