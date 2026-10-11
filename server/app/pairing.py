"""Generate a one-time terminal QR code to pair an Android device."""
from __future__ import annotations

import json

import qrcode

from .main import create_pairing, setup_db


def main() -> None:
    setup_db()
    pairing = create_pairing()
    payload = json.dumps({"version": 1, "endpoint": pairing["endpoint"], "secret": pairing["secret"]}, separators=(",", ":"))
    qr = qrcode.QRCode(border=2)
    qr.add_data(payload)
    qr.make(fit=True)
    print("Open Gray on the Pixel and tap ‘扫码配对’, then scan this one-time code:")
    qr.print_ascii(invert=True)
    print(f"Code expires at {pairing['expires_at']}")


if __name__ == "__main__":
    main()
