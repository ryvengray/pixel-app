package dev.pixel.sync;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.commonmark.Extension;
import org.commonmark.ext.autolink.AutolinkExtension;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;

final class BriefingMarkdown {
    private static final List<Extension> EXTENSIONS = Arrays.asList(
            AutolinkExtension.create(), TablesExtension.create());
    private static final Parser PARSER = Parser.builder().extensions(EXTENSIONS).build();
    private static final HtmlRenderer RENDERER = HtmlRenderer.builder().extensions(EXTENSIONS)
            .softbreak("<br>\n").escapeHtml(true).sanitizeUrls(true).build();
    private static final Pattern CODE_BLOCK_OR_INLINE = Pattern.compile("(?s)(<pre>.*?</pre>|<code>.*?</code>)");
    private static final Pattern STRIKE = Pattern.compile("~~([^~\\n]+)~~");

    static String render(String markdown) {
        String normalized = RichText.normalizeToMarkdown(markdown);
        String html = RENDERER.render(PARSER.parse(normalized));
        return applyStrikethroughOutsideCode(html);
    }

    private static String applyStrikethroughOutsideCode(String html) {
        if (!html.contains("~~")) return html;
        Matcher codeMatcher = CODE_BLOCK_OR_INLINE.matcher(html);
        StringBuilder out = new StringBuilder(html.length());
        int cursor = 0;
        while (codeMatcher.find()) {
            out.append(STRIKE.matcher(html.substring(cursor, codeMatcher.start())).replaceAll("<del>$1</del>"));
            out.append(codeMatcher.group(1));
            cursor = codeMatcher.end();
        }
        out.append(STRIKE.matcher(html.substring(cursor)).replaceAll("<del>$1</del>"));
        return out.toString();
    }

    private BriefingMarkdown() {}
}
