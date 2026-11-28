import html
import re
from markdown_it import MarkdownIt


_MARKDOWN = MarkdownIt("commonmark", {"html": False}).enable("strikethrough")
_SOURCE_HEADINGS = {"来源与发布时间", "通知来源（节选）", "原始帖来源"}
_BOILERPLATE_PREFIXES = ("仅依据手机收到的 X 通知片段",)
_MAX_BODY_LENGTH = 180


def _clean_inline_html(raw: str) -> str:
    if not raw:
        return ""
    text = re.sub(r"(?is)<(script|style)\b.*?>.*?</\1>", "", raw)
    text = re.sub(r"(?i)<br\s*/?>", "\n", text)
    text = re.sub(r"(?i)</(p|div|li|h[1-6]|blockquote|tr)>", "\n", text)
    text = re.sub(r"(?s)<[a-zA-Z!/][^>]*>", "", text)
    return html.unescape(text)


def strip_formatting(body: str, max_length: int = _MAX_BODY_LENGTH) -> str:
    cleaned = _clean_inline_html(body)
    lines = []
    heading = False
    bullet = False
    for token in _MARKDOWN.parse(cleaned):
        if token.type == "heading_open":
            heading = True
        elif token.type == "heading_close":
            heading = False
        elif token.type == "list_item_open":
            bullet = True
        elif token.type == "list_item_close":
            bullet = False
        elif token.type == "inline":
            parts = []
            for child in token.children or []:
                if child.type in {"text", "code_inline"}:
                    parts.append(child.content)
                elif child.type in {"softbreak", "hardbreak"}:
                    parts.append(" ")
            text = " ".join("".join(parts).split())
            if heading and text in _SOURCE_HEADINGS:
                break
            if not text or heading:
                continue
            if any(text.startswith(prefix) for prefix in _BOILERPLATE_PREFIXES):
                bullet = False
                continue
            lines.append(f"• {text}" if bullet else text)
            bullet = False

    preview = "\n".join(lines).strip()
    if not preview:
        preview = " ".join(cleaned.split())
    if max_length and len(preview) > max_length:
        preview = preview[:max_length - 1].rstrip() + "…"
    return preview


def format_push(message_id: str, title: str, body: str) -> tuple[str, str]:
    clean_title = " ".join(_clean_inline_html(title).split()) or "Greta"
    if message_id.startswith("morning:"):
        clean_title = f"☀️ {clean_title}"
    elif message_id.startswith("idea:"):
        clean_title = f"💡 {clean_title}"
    elif message_id.startswith("news-breaking:"):
        clean_title = f"⚡ {clean_title}"
    elif message_id.startswith("news:"):
        clean_title = f"📰 {clean_title}"
    elif message_id.startswith("x-notifications:"):
        clean_title = f"𝕏 {clean_title}"
    else:
        plain = strip_formatting(body, _MAX_BODY_LENGTH)
        return clean_title, plain or body[:_MAX_BODY_LENGTH]

    preview = strip_formatting(body, _MAX_BODY_LENGTH) or "点按查看完整简报"
    return clean_title, preview
