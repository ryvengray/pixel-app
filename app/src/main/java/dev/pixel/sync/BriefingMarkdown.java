package dev.pixel.sync;

import java.util.Arrays;
import java.util.List;
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

    static String render(String markdown) {
        return RENDERER.render(PARSER.parse(markdown));
    }

    private BriefingMarkdown() {}
}
