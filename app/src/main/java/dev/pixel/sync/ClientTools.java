package dev.pixel.sync;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Iterator;

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
        if(args==null||!(name.equals("open_sync")||name.equals("open_print")||name.equals("open_topics")))return false;
        for(Iterator<String> keys=args.keys();keys.hasNext();){
            String key=keys.next();if(!name.equals("open_print")||!key.equals("text"))return false;
            Object value=args.opt(key);
            if(!(value instanceof String)||((String)value).isEmpty()||((String)value).length()>4000)return false;
        }
        return true;
    }
    public static boolean explicitCommand(String text){
        return text.matches("(?s).*(提醒我|提醒一下我).*|^(请|麻烦|帮我|给我|我要|我想|我需要)*(打开|查看|看看|看一下|同步|打印(?!机)|设置提醒).*")
            && !text.matches("(?s).*(为什么|怎么|如何|什么|啥|是否|能否|能不能|可不可以|可以|支持|会不会|有没有|是不是|怎样|为何|哪里|介绍|解释|请问|假如|如果|假设|不想|不需要|不要|不用|别|取消|停止|然后|并且|同时|以及|再帮|顺便|先.+再|[?？吗么呢‘’“”\"`\\n]).*");
    }
    private ClientTools(){}
}
