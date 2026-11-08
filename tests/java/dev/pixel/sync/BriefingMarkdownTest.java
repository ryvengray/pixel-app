package dev.pixel.sync;

/** Exercises the same renderer used for old and new inbox messages. */
public final class BriefingMarkdownTest {
    public static void main(String[] args) {
        String html = BriefingMarkdown.render("## 今日重点\n\n- **新模型**\n  - *研究*\n\n"
                + "> 引用\n\n| 名称 | 状态 |\n| --- | --- |\n| A | \u0060beta\u0060 |\n\n"
                + "[官方来源](https://example.org/news)\nhttps://example.org/legacy\n\n"
                + "<script>alert(1)</script>\n\n[unsafe](javascript:alert(1))");
        for (String expected : new String[]{"<h2>", "<ul>", "<strong>", "<em>", "<blockquote>", "<table>",
                "<code>", "href=\"https://example.org/news\"", "href=\"https://example.org/legacy\"", "&lt;script&gt;"}) {
            if (!html.contains(expected)) throw new AssertionError("Missing " + expected + " in " + html);
        }
        if (html.contains("<script>") || html.contains("href=\"javascript:")) {
            throw new AssertionError("Unsafe HTML: " + html);
        }
        System.out.println("Briefing Markdown checks passed");
    }
}
