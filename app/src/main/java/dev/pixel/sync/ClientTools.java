package dev.pixel.sync;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Iterator;
import java.util.List;
import java.util.Arrays;

/** The phone and gateway share schemas; only these fixed UI actions are executable. */
public final class ClientTools {
    public static JSONArray definitions(Context context) throws Exception {
        try(InputStream input=context.getAssets().open("tools.json")){
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();
            byte[] buffer=new byte[4096];int n;
            while((n=input.read(buffer))!=-1)bytes.write(buffer,0,n);
            return new JSONArray(bytes.toString("UTF-8"));
        }
    }
    public static boolean valid(String name,JSONObject args){
        if(CalendarTools.isTool(name))return CalendarTools.valid(name,args);
        if(name.equals("create_life_record"))return LifeRecords.valid(args);
        if(args==null||!(name.equals("open_sync")||name.equals("open_print")||name.equals("open_topics")||name.equals("open_records")))return false;
        for(Iterator<String> keys=args.keys();keys.hasNext();){
            String key=keys.next();if(!name.equals("open_print")||!key.equals("text"))return false;
            Object value=args.opt(key);
            if(!(value instanceof String)||((String)value).isEmpty()||((String)value).length()>4000)return false;
        }
        return true;
    }
    public static boolean explicitCommand(String text){
        return text.matches("(?s).*(提醒我|提醒一下我).*|^(请|麻烦|帮我|给我|我要|我想|我需要)*(打开|查看|看看|看一下|同步|打印(?!机)|设置提醒|记一下|记录一下|记一笔|记录一笔|记日记|写日记).*")
            && !text.matches("(?s).*(为什么|怎么|如何|什么|啥|是否|能否|能不能|可不可以|可以|支持|会不会|有没有|是不是|怎样|为何|哪里|介绍|解释|请问|假如|如果|假设|不想|不需要|不要|不用|别|取消|停止|然后|并且|同时|以及|再帮|顺便|先.+再|[?？吗么呢‘’“”\"`\\n]).*");
    }
    public static boolean recordCommand(String text){return explicitCommand(text)&&text.trim().matches("^(请|麻烦|帮我|给我|我要|我想|我需要)*(记一下|记录一下|记一笔|记录一笔|记日记|写日记).*");}
    static boolean recordConfirmation(String offer,String answer){
        String normalized=answer.replaceAll("[\\s，,。.!！~～]+","").toLowerCase(java.util.Locale.ROOT);
        if(!Arrays.asList("好","好的","好啊","好呀","行","行的","可以","可以的","嗯","嗯嗯","是","是的","对","确定","确认","同意","记吧","记下来","保存吧","帮我记下","好记吧","好记一下","ok","yes").contains(normalized))return false;
        String prompt=offer.split("\\n\\s*\\n",2)[0];
        if(java.util.regex.Pattern.compile("还是|或者|或是|同时|顺便|提醒|打印|删除|同步|待办|重要日期|不要|不需要|已经|已保存|已记录|没能|无法|不能").matcher(prompt).find())return false;
        return java.util.regex.Pattern.compile("(?:要我|要不要|需要我|是否|我帮你|我来|要)[^。！？?!\\n]{0,100}(?:(?:记|记录|保存|存入|存进|存到|写入|写进)[^。！？?!\\n]{0,30}(?:生活记录|日记|账本)|记账)[^。！？?!\\n]{0,30}(?:吗|么|[？?])").matcher(prompt).find();
    }
    /** User evidence for a direct request or its immediate save confirmation; null means no intent. */
    static String recordEvidence(List<AssistantStore.Message> messages){
        int end=messages.size()-1;if(end<0||!messages.get(end).role.equals("user"))return null;
        int start=end;while(start>0&&messages.get(start-1).role.equals("user"))start--;
        boolean direct=recordCommand(messages.get(end).content);
        if(!direct){
            if(start!=end||start<2||!messages.get(start-1).role.equals("assistant")||!messages.get(start-2).role.equals("user")||messages.get(start-2).content.trim().isEmpty()||!recordConfirmation(messages.get(start-1).content,messages.get(start).content))return null;
            start-=2;while(start>0&&messages.get(start-1).role.equals("user"))start--;
        }
        StringBuilder evidence=new StringBuilder();for(int i=start;i<=end;i++)if(messages.get(i).role.equals("user")){if(evidence.length()>0)evidence.append("\n");evidence.append(messages.get(i).content);}return evidence.toString();
    }
    private ClientTools(){}
}
