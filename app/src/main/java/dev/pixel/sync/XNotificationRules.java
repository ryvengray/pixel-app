package dev.pixel.sync;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** Conservative author and public-post metadata gate, independent of Android runtime. */
final class XNotificationRules {
    private static final Pattern POST_TITLE = Pattern.compile(
            "(?: posted(?: a new post)?| tweeted|\\s*(?:发布了(?:一条)?(?:新)?(?:帖子|推文)|发帖了))$");
    private static final Pattern POST_CHANNEL = Pattern.compile(
            "tweets?|(?:new|following|account)_(?:tweets?|posts?)|(?:tweet|post)_notifications?");
    private static final Pattern PRIVATE_CHANNEL = Pattern.compile(".*(direct|message|inbox|chat|\\bdm\\b).*");
    private static final Pattern PRIVATE_TEXT = Pattern.compile(
            "(?s).*(sent (?:you )?a message|messaged you|direct message|私信|发来消息|发送了消息).*");

    private static String normalize(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC).trim().toLowerCase(Locale.ROOT);
    }

    static String author(String title, Map<String,String> allowed) {
        if (title == null) return null;
        String value = POST_TITLE.matcher(normalize(title)).replaceFirst("").trim();
        for (Map.Entry<String,String> entry : allowed.entrySet()) {
            String handle = entry.getKey(), name = normalize(entry.getValue());
            if (value.equals(handle) || value.equals("@" + handle)
                    || (!name.isEmpty() && (value.equals(name)
                    || value.equals(name + " (@" + handle + ")") || value.equals(name + " @" + handle)))) {
                return handle;
            }
        }
        return null;
    }

    static boolean privateText(CharSequence text) {
        return text != null && PRIVATE_TEXT.matcher(normalize(text.toString())).matches();
    }

    static String accept(String pkg, String category, boolean groupSummary, boolean messages, boolean replyInput,
                         String channel, String title, String text, Map<String,String> allowed) {
        if (!"com.twitter.android".equals(pkg) || groupSummary || messages || replyInput
                || text == null || text.trim().isEmpty()) return null;
        String channelId = channel == null ? "" : normalize(channel);
        if (PRIVATE_CHANNEL.matcher(channelId).matches() || privateText(title) || privateText(text)) return null;
        boolean explicitPost = POST_CHANNEL.matcher(channelId).matches()
                || (title != null && POST_TITLE.matcher(normalize(title)).find());
        if (!"social".equals(category) && !(category == null && explicitPost)) return null;
        return author(title, allowed);
    }
}
