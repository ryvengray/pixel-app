package dev.pixel.sync;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** SAF access only to the vault used by the existing fixed Termux script. */
public final class VaultExport {
    public static boolean correctTree(Uri uri){
        try{return "com.android.externalstorage.documents".equals(uri.getAuthority())&&"primary:Documents/obsidian".equals(DocumentsContract.getTreeDocumentId(uri));}catch(Exception e){return false;}
    }
    public static String hash(String text)throws Exception{
        byte[] digest=MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));StringBuilder out=new StringBuilder();for(byte b:digest)out.append(String.format("%02x",b&255));return out.toString();
    }
    private static Uri child(Context c,Uri parent,String name,String type)throws Exception{
        Uri children=DocumentsContract.buildChildDocumentsUriUsingTree(parent,DocumentsContract.getDocumentId(parent));
        try(Cursor cursor=c.getContentResolver().query(children,new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME,DocumentsContract.Document.COLUMN_MIME_TYPE},null,null,null)){
            if(cursor==null)throw new IOException("无法读取 Obsidian 目录");
            while(cursor.moveToNext())if(name.equals(cursor.getString(1))){if(!type.equals(cursor.getString(2))&&!type.equals("text/markdown"))throw new IOException("记录路径被其他文件占用");return DocumentsContract.buildDocumentUriUsingTree(parent,cursor.getString(0));}
        }
        Uri uri=DocumentsContract.createDocument(c.getContentResolver(),parent,type,name);if(uri==null)throw new IOException("无法创建记录文件");return uri;
    }
    static String read(Context c,Uri uri)throws Exception{
        try(InputStream in=c.getContentResolver().openInputStream(uri);ByteArrayOutputStream out=new ByteArrayOutputStream()){
            if(in==null)throw new IOException("无法读取记录文件");byte[] bytes=new byte[4096];int n;while((n=in.read(bytes))!=-1){out.write(bytes,0,n);if(out.size()>1000000)throw new IOException("记录文件过大");}return out.toString("UTF-8");
        }
    }
    public static void export(Context c,LifeRecords records)throws Exception{
        String value=c.getSharedPreferences("life_sync",0).getString("tree","");if(value.isEmpty())return;
        Uri tree=Uri.parse(value);if(!correctTree(tree))throw new IOException("请选择 Documents/obsidian 仓库");
        Uri root=DocumentsContract.buildDocumentUriUsingTree(tree,DocumentsContract.getTreeDocumentId(tree));
        exportToRoot(c,records,root);
    }
    static void exportToRoot(Context c,LifeRecords records,Uri root)throws Exception{
        Uri folder=child(c,root,"Greta 生活记录",DocumentsContract.Document.MIME_TYPE_DIR);
        for(LifeRecords.Record record:records.all(true)){
            if(record.exported>=record.revision)continue;
            if(!record.deleted&&!record.images.isEmpty()){
                Uri images=child(c,folder,"图片",DocumentsContract.Document.MIME_TYPE_DIR);
                for(LifeImages.Image image:record.images){
                    File source=LifeImages.file(c,false,image.fileName);if(!LifeImages.hash(source).equals(image.hash))throw new IOException("手机中的图片校验失败");
                    Uri target=child(c,images,image.fileName,image.mime);String existing="";boolean empty;
                    InputStream stream=c.getContentResolver().openInputStream(target);if(stream==null)throw new IOException("无法读取 Obsidian 图片");
                    try(PushbackInputStream in=new PushbackInputStream(stream,1)){int first=in.read();empty=first==-1;if(!empty){in.unread(first);existing=LifeImages.hash(in);}}
                    if(!empty&&!existing.equals(image.hash))throw new IOException("Obsidian 中的图片已被修改，请先保留改动："+image.fileName);
                    if(empty)try(InputStream in=new FileInputStream(source);OutputStream out=c.getContentResolver().openOutputStream(target,"wt")){if(out==null)throw new IOException("无法导出图片");LifeImages.copy(in,out);}
                    try(InputStream in=c.getContentResolver().openInputStream(target)){if(in==null||!LifeImages.hash(in).equals(image.hash))throw new IOException("图片写入校验失败");}
                }
            }
            // Stable path survives date edits; one app-owned file per record avoids daily-note races.
            String filename=hash(record.id).substring(0,32)+".md";
            Uri file=child(c,folder,filename,"text/markdown");String desired=LifeRecords.markdown(record),old=read(c,file),newHash=hash(desired);
            if(!old.isEmpty()&&!hash(old).equals(newHash)&&!hash(old).equals(record.hash))throw new IOException("Obsidian 中的记录已被修改，请先保留改动再重试："+filename);
            if(!old.equals(desired))try(OutputStream out=c.getContentResolver().openOutputStream(file,"wt")){if(out==null)throw new IOException("无法写入记录");out.write(desired.getBytes(StandardCharsets.UTF_8));}
            if(!hash(read(c,file)).equals(newHash))throw new IOException("记录写入校验失败");
            records.exported(record,newHash);
        }
    }
    private VaultExport(){}
}
