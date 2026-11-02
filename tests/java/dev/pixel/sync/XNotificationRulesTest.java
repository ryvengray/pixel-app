package dev.pixel.sync;
import java.util.*;

/** Run with javac/java; no phone notifications, network, or Android permission involved. */
public final class XNotificationRulesTest {
    static final Map<String,String> authors=new LinkedHashMap<>();
    static int checks;
    static void check(boolean value){checks++;if(!value)throw new AssertionError("check "+checks);}
    static String accept(String pkg,String cat,boolean summary,boolean messages,boolean reply,String channel,String title,String text){return XNotificationRules.accept(pkg,cat,summary,messages,reply,channel,title,text,authors);}
    public static void main(String[] args){
        authors.put("elonmusk","Elon Musk");authors.put("karpathy","Andrej Karpathy");
        check("elonmusk".equals(accept("com.twitter.android","social",false,false,false,"tweets","Elon Musk","New launch")));
        check("karpathy".equals(accept("com.twitter.android","social",false,false,false,"tweets","Andrej Karpathy (@karpathy)","New paper")));
        check(accept("com.other.app","social",false,false,false,"tweets","Elon Musk","text")==null);
        check(accept("com.twitter.android","msg",false,false,false,"tweets","Elon Musk","text")==null);
        check(accept("com.twitter.android",null,false,false,false,"tweets","Elon Musk","text")==null);
        check(accept("com.twitter.android","social",true,false,false,"tweets","Elon Musk","text")==null);
        check(accept("com.twitter.android","social",false,true,false,"tweets","Elon Musk","text")==null);
        check(accept("com.twitter.android","social",false,false,true,"tweets","Elon Musk","text")==null);
        check(accept("com.twitter.android","social",false,false,false,"direct_messages","Elon Musk","text")==null);
        check(accept("com.twitter.android","social",false,false,false,"tweets","Elon Musk","sent you a message")==null);
        check(accept("com.twitter.android","social",false,false,false,"tweets","Elon Musk","给你发来消息")==null);
        check(accept("com.twitter.android","social",false,false,false,"tweets","Unknown Author","text")==null);
        check(accept("com.twitter.android","social",false,false,false,"tweets","News about Elon Musk","text")==null);
        check(accept("com.twitter.android","social",false,false,false,"tweets","Elon Musk","  ")==null);
        check(accept("com.twitter.android","social",false,false,false,"tweets",null,"text")==null);
        check("karpathy".equals(accept("com.twitter.android","social",false,false,false,"tweets","@Karpathy","text")));
        System.out.println(checks+" notification filter checks passed");
    }
}
