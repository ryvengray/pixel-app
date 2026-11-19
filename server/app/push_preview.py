from markdown_it import MarkdownIt


_MARKDOWN = MarkdownIt("commonmark", {"html": False}).enable("strikethrough")
_SOURCE_HEADINGS = {"来源与发布时间", "通知来源（节选）"}
_MAX_BODY_LENGTH = 180


def format_push(message_id: str, title: str, body: str) -> tuple[str, str]:
    if message_id.startswith("news:"):
        title = f"📰 {title}"
    elif message_id.startswith("x-notifications:"):
        title = f"𝕏 {title}"
    else:
        return title, body[:_MAX_BODY_LENGTH]

    lines = []
    heading = False
    bullet = False
    for token in _MARKDOWN.parse(body):
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
            if text and not heading:
                lines.append(f"• {text}" if bullet else text)
                bullet = False

    preview = "\n".join(lines) or "点按查看完整简报"
    if len(preview) > _MAX_BODY_LENGTH:
        preview = preview[:_MAX_BODY_LENGTH - 1].rstrip() + "…"
    return title, preview
