package dev.pixel.sync;

import java.text.Normalizer;
import java.util.*;

/** Conservative author and public-social metadata gate, independent of Android runtime. */
final class XNotificationRules {
    private static String normalize(String s){return Normalizer.normalize(s,Normalizer.Form.NFKC).trim().toLowerCase(Locale.ROOT);}
    static String author(String title,Map<String,String> allowed){if(title==null)return null;String value=normalize(title);
        for(Map.Entry<String,String> entry:allowed.entrySet()){String handle=entry.getKey(),name=normalize(entry.getValue());if(value.equals(handle)||value.equals("@"+handle)||value.equals(name)||value.equals(name+" (@"+handle+")")||value.equals(name+" @"+handle))return handle;}
        return null;
    }
    static String accept(String pkg,String category,boolean groupSummary,boolean messages,boolean replyInput,String channel,String title,String text,Map<String,String> allowed){
        if(!"com.twitter.android".equals(pkg)||!"social".equals(category)||groupSummary||messages||replyInput||text==null||text.trim().isEmpty())return null;
        if(channel!=null&&channel.toLowerCase(Locale.ROOT).matches(".*(direct|message|inbox|chat|\\bdm\\b).*"))return null;
        if(text.toLowerCase(Locale.ROOT).matches("(?s).*(sent you a message|direct message|私信|发来消息|发送了消息).*"))return null;
        return author(title,allowed);
    }
}
