package dev.pixel.sync;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.text.Html;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.LeadingMarginSpan;
import android.text.style.QuoteSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StrikethroughSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.text.style.URLSpan;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.commonmark.Extension;
import org.commonmark.ext.autolink.AutolinkExtension;
import org.commonmark.ext.gfm.tables.TableBlock;
import org.commonmark.ext.gfm.tables.TableBody;
import org.commonmark.ext.gfm.tables.TableCell;
import org.commonmark.ext.gfm.tables.TableHead;
import org.commonmark.ext.gfm.tables.TableRow;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.node.BlockQuote;
import org.commonmark.node.BulletList;
import org.commonmark.node.Code;
import org.commonmark.node.CustomBlock;
import org.commonmark.node.CustomNode;
import org.commonmark.node.Emphasis;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.HardLineBreak;
import org.commonmark.node.Heading;
import org.commonmark.node.IndentedCodeBlock;
import org.commonmark.node.Link;
import org.commonmark.node.ListItem;
import org.commonmark.node.Node;
import org.commonmark.node.OrderedList;
import org.commonmark.node.Paragraph;
import org.commonmark.node.SoftLineBreak;
import org.commonmark.node.StrongEmphasis;
import org.commonmark.node.Text;
import org.commonmark.node.ThematicBreak;
import org.commonmark.parser.Parser;

/**
 * Converts Markdown and inline HTML into styled Android Spanned text so raw
 * syntax (**bold**, ## headings, `code`, <br>, <b>, &amp;, etc.) never leaks.
 */
final class RichText {
    private static final List<Extension> EXTENSIONS = Arrays.asList(
            AutolinkExtension.create(), TablesExtension.create());
    private static final Parser PARSER = Parser.builder().extensions(EXTENSIONS).build();
    private static final Pattern FENCED_SEGMENT = Pattern.compile("(?s)(```.*?```)");
    private static final Pattern STRIKE_PATTERN = Pattern.compile("~~([^~\\n]+)~~");
    private static final Pattern HTML_ENTITY = Pattern.compile("&(?:#\\d+|#x[0-9a-fA-F]+|[a-zA-Z]{2,10});");

    private RichText() {}

    static String normalizeToMarkdown(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        String text = raw.replace("\r\n", "\n").replace('\r', '\n');
        String trimmed = text.trim();
        if (trimmed.startsWith("```") && trimmed.endsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            int lastFence = trimmed.lastIndexOf("```");
            if (firstNewline > 0 && lastFence > firstNewline) {
                String lang = trimmed.substring(3, firstNewline).trim().toLowerCase();
                String inner = trimmed.substring(firstNewline + 1, lastFence);
                if (!inner.contains("```") && (lang.isEmpty() || lang.equals("markdown")
                        || lang.equals("md") || lang.equals("html") || lang.equals("text"))) {
                    text = inner.trim();
                }
            }
        }
        Matcher matcher = FENCED_SEGMENT.matcher(text);
        StringBuilder out = new StringBuilder(text.length());
        int cursor = 0;
        while (matcher.find()) {
            out.append(convertHtmlChunk(text.substring(cursor, matcher.start())));
            out.append(matcher.group(1));
            cursor = matcher.end();
        }
        out.append(convertHtmlChunk(text.substring(cursor)));
        return out.toString();
    }

    private static String convertHtmlChunk(String chunk) {
        if (chunk.isEmpty() || (chunk.indexOf('<') < 0 && chunk.indexOf('&') < 0)) return chunk;
        String s = chunk;
        s = s.replaceAll("(?is)<(script|style)\\b[^>]*>.*?</\\1>", "");
        s = s.replaceAll("(?is)<pre\\b[^>]*>\\s*<code\\b[^>]*>(.*?)</code>\\s*</pre>", "\n```\n$1\n```\n");
        s = s.replaceAll("(?is)<pre\\b[^>]*>(.*?)</pre>", "\n```\n$1\n```\n");
        for (int level = 1; level <= 6; level++) {
            String hashes = new String(new char[level]).replace("\0", "#");
            s = s.replaceAll("(?is)<h" + level + "\\b[^>]*>(.*?)</h" + level + ">", "\n\n" + hashes + " $1\n\n");
        }
        s = s.replaceAll("(?is)<blockquote\\b[^>]*>(.*?)</blockquote>", "\n> $1\n");
        s = s.replaceAll("(?is)<li\\b[^>]*>(.*?)</li>", "\n- $1");
        s = s.replaceAll("(?i)<hr\\s*/?>", "\n\n---\n\n");
        s = s.replaceAll("(?i)<br\\s*/?>", "\n");
        s = s.replaceAll("(?i)</(p|div|section|article|ul|ol|table|tr)>", "\n\n");
        s = s.replaceAll("(?i)<(p|div|section|article|ul|ol|table|thead|tbody|tr)\\b[^>]*>", "");
        s = s.replaceAll("(?i)</?(td|th)\\b[^>]*>", " ");
        s = s.replaceAll("(?is)<a\\b[^>]*href\\s*=\\s*[\"'](https?://[^\"'\\s>]+)[\"'][^>]*>(.*?)</a>", "[$2]($1)");
        s = s.replaceAll("(?is)<(b|strong)\\b[^>]*>(.*?)</\\1>", "**$2**");
        s = s.replaceAll("(?is)<(i|em)\\b[^>]*>(.*?)</\\1>", "*$2*");
        s = s.replaceAll("(?is)<(del|s|strike)\\b[^>]*>(.*?)</\\1>", "~~$2~~");
        s = s.replaceAll("(?is)<code\\b[^>]*>(.*?)</code>", "`$1`");
        s = s.replaceAll("(?s)</?[a-zA-Z][a-zA-Z0-9:-]*(?:\\s+[^<>]*)?>", "");
        if (HTML_ENTITY.matcher(s).find()) {
            Matcher em = HTML_ENTITY.matcher(s);
            StringBuffer decoded = new StringBuffer();
            while (em.find()) {
                String entity = em.group();
                String value = Html.fromHtml(entity, Html.FROM_HTML_MODE_LEGACY).toString();
                em.appendReplacement(decoded, Matcher.quoteReplacement(value));
            }
            em.appendTail(decoded);
            s = decoded.toString();
        }
        return s;
    }

    static CharSequence format(Context context, String raw, boolean dark) {
        if (raw == null || raw.isEmpty()) return "";
        String normalized = normalizeToMarkdown(raw);
        if (!containsFormattingHint(normalized)) return normalized;
        float density = context.getResources().getDisplayMetrics().density;
        int accent = Color.parseColor(dark ? "#A8DCB5" : "#286849");
        int codeBg = Color.parseColor(dark ? "#2A4336" : "#E2ECE0");
        int muted = Color.parseColor(dark ? "#AEC4B4" : "#526B59");
        Node document = PARSER.parse(normalized);
        SpannableStringBuilder out = new SpannableStringBuilder();
        renderChildren(document, out, density, accent, codeBg, muted, 0);
        trimTrailingBreaks(out);
        applyStrikethrough(out);
        return out.length() == 0 ? normalized.trim() : out;
    }

    static String plainPreview(String raw, int maxChars) {
        if (raw == null || raw.isEmpty()) return "";
        String normalized = normalizeToMarkdown(raw);
        Node document = PARSER.parse(normalized);
        StringBuilder out = new StringBuilder();
        collectPlain(document, out);
        String compact = out.toString().replace("~~", "").replaceAll("\\s+", " ").trim();
        if (maxChars > 0 && compact.length() > maxChars) {
            return compact.substring(0, maxChars - 1).trim() + "…";
        }
        return compact;
    }

    private static boolean containsFormattingHint(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '*' || c == '#' || c == '`' || c == '[' || c == '_' || c == '~'
                    || c == '>' || c == '|' || c == '-') {
                return true;
            }
        }
        return false;
    }

    private static int dp(float density, float value) {
        return Math.round(density * value);
    }

    private static void ensureBlockSpacing(SpannableStringBuilder out) {
        int len = out.length();
        if (len == 0) return;
        if (out.charAt(len - 1) != '\n') out.append("\n\n");
        else if (len >= 2 && out.charAt(len - 2) != '\n') out.append('\n');
    }

    private static void ensureLineBreak(SpannableStringBuilder out) {
        int len = out.length();
        if (len > 0 && out.charAt(len - 1) != '\n') out.append('\n');
    }

    private static void trimTrailingBreaks(SpannableStringBuilder out) {
        while (out.length() > 0 && Character.isWhitespace(out.charAt(out.length() - 1))) {
            out.delete(out.length() - 1, out.length());
        }
    }

    private static void renderChildren(
            Node parent, SpannableStringBuilder out, float density,
            int accent, int codeBg, int muted, int listDepth) {
        int order = parent instanceof OrderedList ? ((OrderedList) parent).getMarkerStartNumber() : 1;
        for (Node node = parent.getFirstChild(); node != null; node = node.getNext()) {
            if (node instanceof Paragraph) {
                boolean inList = parent instanceof ListItem;
                if (!inList) ensureBlockSpacing(out);
                else if (node.getPrevious() != null) ensureLineBreak(out);
                renderChildren(node, out, density, accent, codeBg, muted, listDepth);
                if (!inList) out.append('\n');
            } else if (node instanceof Heading) {
                ensureBlockSpacing(out);
                int level = ((Heading) node).getLevel();
                float scale = level == 1 ? 1.18f : level == 2 ? 1.11f : 1.05f;
                int start = out.length();
                renderChildren(node, out, density, accent, codeBg, muted, listDepth);
                int end = out.length();
                if (end > start) {
                    out.setSpan(new StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    out.setSpan(new RelativeSizeSpan(scale), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
                out.append('\n');
            } else if (node instanceof Text) {
                out.append(((Text) node).getLiteral());
            } else if (node instanceof SoftLineBreak || node instanceof HardLineBreak) {
                out.append('\n');
            } else if (node instanceof StrongEmphasis) {
                int start = out.length();
                renderChildren(node, out, density, accent, codeBg, muted, listDepth);
                if (out.length() > start) {
                    out.setSpan(new StyleSpan(Typeface.BOLD), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
            } else if (node instanceof Emphasis) {
                int start = out.length();
                renderChildren(node, out, density, accent, codeBg, muted, listDepth);
                if (out.length() > start) {
                    out.setSpan(new StyleSpan(Typeface.ITALIC), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
            } else if (node instanceof Code) {
                String literal = ((Code) node).getLiteral();
                int start = out.length();
                out.append('\u00A0').append(literal).append('\u00A0');
                int end = out.length();
                out.setSpan(new TypefaceSpan("monospace"), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                out.setSpan(new RelativeSizeSpan(0.92f), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                out.setSpan(new BackgroundColorSpan(codeBg), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else if (node instanceof FencedCodeBlock || node instanceof IndentedCodeBlock) {
                ensureBlockSpacing(out);
                String literal = node instanceof FencedCodeBlock
                        ? ((FencedCodeBlock) node).getLiteral()
                        : ((IndentedCodeBlock) node).getLiteral();
                if (literal.endsWith("\n")) literal = literal.substring(0, literal.length() - 1);
                int start = out.length();
                out.append(literal);
                int end = out.length();
                if (end > start) {
                    out.setSpan(new TypefaceSpan("monospace"), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    out.setSpan(new RelativeSizeSpan(0.9f), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    out.setSpan(new BackgroundColorSpan(codeBg), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    out.setSpan(new LeadingMarginSpan.Standard(dp(density, 10)), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
                out.append('\n');
            } else if (node instanceof BulletList || node instanceof OrderedList) {
                if (listDepth == 0) ensureBlockSpacing(out);
                else ensureLineBreak(out);
                renderChildren(node, out, density, accent, codeBg, muted, listDepth + 1);
            } else if (node instanceof ListItem) {
                ensureLineBreak(out);
                int start = out.length();
                String marker = parent instanceof OrderedList ? (order++ + ". ") : "• ";
                out.append(marker);
                renderChildren(node, out, density, accent, codeBg, muted, listDepth);
                int end = out.length();
                if (end > start) {
                    int baseIndent = dp(density, Math.max(0, (listDepth - 1) * 14));
                    out.setSpan(new LeadingMarginSpan.Standard(baseIndent, baseIndent + dp(density, 14)),
                            start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
                ensureLineBreak(out);
            } else if (node instanceof BlockQuote) {
                ensureBlockSpacing(out);
                int start = out.length();
                renderChildren(node, out, density, accent, codeBg, muted, listDepth);
                trimTrailingBreaks(out);
                int end = out.length();
                if (end > start) {
                    out.setSpan(new QuoteSpan(accent), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    out.setSpan(new LeadingMarginSpan.Standard(dp(density, 10)), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    out.setSpan(new ForegroundColorSpan(muted), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
                out.append('\n');
            } else if (node instanceof Link) {
                Link link = (Link) node;
                int start = out.length();
                renderChildren(node, out, density, accent, codeBg, muted, listDepth);
                if (out.length() == start) out.append(link.getDestination());
                int end = out.length();
                if (end > start) {
                    out.setSpan(new URLSpan(link.getDestination()), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    out.setSpan(new ForegroundColorSpan(accent), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
            } else if (node instanceof ThematicBreak) {
                ensureBlockSpacing(out);
                int start = out.length();
                out.append("────────");
                out.setSpan(new ForegroundColorSpan(muted), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                out.append('\n');
            } else if (node instanceof TableBlock) {
                ensureBlockSpacing(out);
                renderTable((TableBlock) node, out, density, accent, codeBg, muted);
            } else if (node instanceof CustomBlock || node instanceof CustomNode) {
                renderChildren(node, out, density, accent, codeBg, muted, listDepth);
            }
        }
    }

    private static void renderTable(
            TableBlock table, SpannableStringBuilder out, float density,
            int accent, int codeBg, int muted) {
        for (Node section = table.getFirstChild(); section != null; section = section.getNext()) {
            boolean header = section instanceof TableHead;
            if (!(section instanceof TableHead || section instanceof TableBody)) continue;
            for (Node row = section.getFirstChild(); row != null; row = row.getNext()) {
                if (!(row instanceof TableRow)) continue;
                ensureLineBreak(out);
                int rowStart = out.length();
                boolean firstCell = true;
                for (Node cell = row.getFirstChild(); cell != null; cell = cell.getNext()) {
                    if (!(cell instanceof TableCell)) continue;
                    if (!firstCell) out.append("  ·  ");
                    firstCell = false;
                    renderChildren(cell, out, density, accent, codeBg, muted, 0);
                }
                if (header && out.length() > rowStart) {
                    out.setSpan(new StyleSpan(Typeface.BOLD), rowStart, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
                out.append('\n');
            }
        }
    }

    private static void applyStrikethrough(SpannableStringBuilder out) {
        Matcher matcher = STRIKE_PATTERN.matcher(out);
        List<int[]> matches = new ArrayList<>();
        while (matcher.find()) {
            TypefaceSpan[] codeSpans = out.getSpans(matcher.start(), matcher.end(), TypefaceSpan.class);
            if (codeSpans == null || codeSpans.length == 0) {
                matches.add(new int[]{matcher.start(), matcher.end()});
            }
        }
        for (int i = matches.size() - 1; i >= 0; i--) {
            int start = matches.get(i)[0];
            int end = matches.get(i)[1];
            out.delete(end - 2, end);
            out.delete(start, start + 2);
            int spanEnd = end - 4;
            if (spanEnd > start) {
                out.setSpan(new StrikethroughSpan(), start, spanEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
    }

    private static void collectPlain(Node parent, StringBuilder out) {
        for (Node node = parent.getFirstChild(); node != null; node = node.getNext()) {
            if (node instanceof Heading) {
                StringBuilder headingText = new StringBuilder();
                collectPlain(node, headingText);
                String h = headingText.toString().trim();
                if ("来源与发布时间".equals(h) || "通知来源（节选）".equals(h) || "原始帖来源".equals(h)) return;
                continue;
            } else if (node instanceof Text) {
                out.append(((Text) node).getLiteral());
            } else if (node instanceof Code) {
                out.append(((Code) node).getLiteral());
            } else if (node instanceof SoftLineBreak || node instanceof HardLineBreak
                    || node instanceof Paragraph || node instanceof ListItem) {
                collectPlain(node, out);
                out.append(' ');
            } else {
                collectPlain(node, out);
            }
        }
    }
}
