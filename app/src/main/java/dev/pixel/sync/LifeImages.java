package dev.pixel.sync;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageDecoder;
import android.net.Uri;
import android.webkit.MimeTypeMap;
import java.io.*;
import java.security.MessageDigest;
import java.util.UUID;

/** Imported image bytes belong to the phone; external URI grants are never record storage. */
final class LifeImages {
    static final long MAX_BYTES=30L*1024*1024;
    static final class Image {
        final String fileName,name,mime,hash;
        Image(String fileName,String name,String mime,String hash){this.fileName=fileName;this.name=name;this.mime=mime;this.hash=hash;}
    }
    static File directory(Context c,boolean draft)throws IOException{
        File dir=new File(draft?c.getCacheDir():c.getFilesDir(),draft?"incoming-images":"life-images");
        if(!dir.isDirectory()&&!dir.mkdirs())throw new IOException("无法保存图片");return dir;
    }
    static File file(Context c,boolean draft,String name)throws IOException{
        if(name==null||!name.matches("[a-f0-9]{32}\\.[a-z0-9]{1,8}"))throw new IOException("图片标识无效");return new File(directory(c,draft),name);
    }
    static Uri uri(Context c,boolean draft,String name){return new Uri.Builder().scheme("content").authority(c.getPackageName()+".images").appendPath(draft?"draft":"image").appendPath(name).build();}
    static Image importImage(Context c,Uri source,String name)throws Exception{
        if(source==null||!("content".equals(source.getScheme())||"file".equals(source.getScheme())))throw new IOException("无法读取这张图片，请重新分享");
        File dir=directory(c,true);File[] old=dir.listFiles();if(old!=null)for(File f:old)if(f.lastModified()<System.currentTimeMillis()-7L*24*60*60*1000)f.delete();
        File temporary=File.createTempFile("import-",".tmp",dir);
        try{
            try(InputStream in=c.getContentResolver().openInputStream(source);OutputStream out=new FileOutputStream(temporary)){if(in==null)throw new IOException("图片读取权限已失效，请重新分享");copy(in,out);}
            BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;BitmapFactory.decodeFile(temporary.getPath(),bounds);
            if(bounds.outWidth<=0||bounds.outHeight<=0||bounds.outMimeType==null)throw new IOException("暂不支持这张图片，请使用 JPG、PNG 或 WebP");
            String extension=MimeTypeMap.getSingleton().getExtensionFromMimeType(bounds.outMimeType);if(extension==null||!extension.matches("[a-z0-9]{1,8}"))extension="img";
            String filename=UUID.randomUUID().toString().replace("-","")+"."+extension;File target=file(c,true,filename);
            String digest=hash(temporary);if(!temporary.renameTo(target))throw new IOException("图片导入失败，请重试");
            return new Image(filename,name==null||name.isEmpty()?"图片":name,bounds.outMimeType,digest);
        }finally{temporary.delete();}
    }
    static Bitmap preview(File file,int maxSide)throws IOException{
        return ImageDecoder.decodeBitmap(ImageDecoder.createSource(file),(decoder,info,source)->{
            int w=info.getSize().getWidth(),h=info.getSize().getHeight();float scale=Math.min(1f,maxSide/(float)Math.max(w,h));
            decoder.setTargetSize(Math.max(1,Math.round(w*scale)),Math.max(1,Math.round(h*scale)));decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
        });
    }
    static Uri preparePrint(Context c,boolean draft,Image image)throws Exception{
        // Decode EXIF orientation and flatten transparency before the existing server conversion.
        String name=VaultExport.hash("print:"+image.fileName).substring(0,32)+".jpg";File target=file(c,true,name);if(target.isFile())return uri(c,true,name);
        Bitmap bitmap=ImageDecoder.decodeBitmap(ImageDecoder.createSource(file(c,draft,image.fileName)),(decoder,info,source)->{
            float scale=Math.min(1f,3072f/Math.max(info.getSize().getWidth(),info.getSize().getHeight()));decoder.setTargetSize(Math.max(1,Math.round(info.getSize().getWidth()*scale)),Math.max(1,Math.round(info.getSize().getHeight()*scale)));decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);decoder.setMutableRequired(true);
        });
        File temporary=File.createTempFile("print-",".tmp",directory(c,true));
        try{new android.graphics.Canvas(bitmap).drawColor(android.graphics.Color.WHITE,android.graphics.PorterDuff.Mode.DST_OVER);try(OutputStream out=new FileOutputStream(temporary)){if(!bitmap.compress(Bitmap.CompressFormat.JPEG,96,out))throw new IOException("无法准备打印图片");}if(!temporary.renameTo(target))throw new IOException("无法准备打印图片");}finally{bitmap.recycle();temporary.delete();}return uri(c,true,name);
    }
    static void retain(Context c,Image image)throws Exception{
        File source=file(c,true,image.fileName),target=file(c,false,image.fileName);
        if(target.exists()){if(!hash(target).equals(image.hash))throw new IOException("已保存的图片校验失败");return;}
        File temporary=File.createTempFile("save-",".tmp",target.getParentFile());
        try{
            try(InputStream in=new FileInputStream(source);OutputStream out=new FileOutputStream(temporary)){copy(in,out);}
            if(!hash(temporary).equals(image.hash))throw new IOException("图片发生变化，请重新分享");
            if(!temporary.renameTo(target))throw new IOException("无法保存图片");
        }finally{temporary.delete();}
    }
    static long copy(InputStream in,OutputStream out)throws IOException{byte[] buffer=new byte[16384];long size=0;int n;while((n=in.read(buffer))!=-1){size+=n;if(size>MAX_BYTES)throw new IOException("图片超过 30 MB，请缩小后重试");out.write(buffer,0,n);}return size;}
    static String hash(File file)throws Exception{try(InputStream in=new FileInputStream(file)){return hash(in);}}
    static String hash(InputStream in)throws Exception{
        MessageDigest digest=MessageDigest.getInstance("SHA-256");byte[] bytes=new byte[16384];int n;long size=0;while((n=in.read(bytes))!=-1){size+=n;if(size>MAX_BYTES)throw new IOException("图片过大");digest.update(bytes,0,n);}StringBuilder result=new StringBuilder();for(byte b:digest.digest())result.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return result.toString();
    }
    private LifeImages(){}
}
