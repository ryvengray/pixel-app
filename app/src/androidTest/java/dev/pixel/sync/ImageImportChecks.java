package dev.pixel.sync;

import android.app.*;
import android.content.*;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.*;
import android.provider.DocumentsContract;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.util.*;

/** Isolated emulator checks: no real printer, model request, or user vault. */
final class ImageImportChecks {
    private final Instrumentation test;
    private final Context context;
    private final List<Activity> activities=new ArrayList<>();
    private final List<LifeImages.Image> images=new ArrayList<>();
    private final List<String> recordIds=new ArrayList<>();
    private int passed;
    private final Uri root=DocumentsContract.buildDocumentUriUsingTree(Uri.parse("content://dev.pixel.sync.records.testprovider/tree/root"),"root");
    ImageImportChecks(Instrumentation test){this.test=test;context=test.getTargetContext();}
    private interface Work{void run()throws Exception;}
    private interface Condition{boolean get();}
    private void ui(Runnable work){test.runOnMainSync(work);}
    private static void expect(boolean ok,String text){if(!ok)throw new AssertionError(text);}
    private void await(Condition condition,String message){long until=SystemClock.uptimeMillis()+10000;boolean[] yes={false};do{ui(()->yes[0]=condition.get());if(yes[0])return;SystemClock.sleep(80);}while(SystemClock.uptimeMillis()<until);throw new AssertionError(message);}
    private void check(String name,Work work)throws Exception{work.run();passed++;Bundle status=new Bundle();status.putString("test",name);status.putInt("current",passed);test.sendStatus(0,status);}
    private Uri fixture()throws Exception{Uri uri=DocumentsContract.createDocument(context.getContentResolver(),root,"image/png","fixture-"+UUID.randomUUID()+".png");Bitmap bitmap=Bitmap.createBitmap(120,80,Bitmap.Config.ARGB_8888);bitmap.eraseColor(Color.rgb(91,160,115));try(OutputStream out=context.getContentResolver().openOutputStream(uri,"wt")){bitmap.compress(Bitmap.CompressFormat.PNG,100,out);}bitmap.recycle();return uri;}
    private JSONObject payload(String content)throws Exception{return new JSONObject().put("content",content).put("items",new JSONArray());}
    private void finish(Activity activity){if(activity!=null)ui(()->{if(!activity.isFinishing())activity.finish();});test.waitForIdleSync();}
    private Activity openPhoto(Uri uri,String action,String note)throws Exception{
        Instrumentation.ActivityMonitor mainMonitor=test.addMonitor(MainActivity.class.getName(),null,false),photoMonitor=test.addMonitor(ImageRecordActivity.class.getName(),null,false);
        Intent intent=new Intent(context,MainActivity.class).setAction(action).setType("image/png").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_GRANT_READ_URI_PERMISSION);intent.setClipData(ClipData.newRawUri("fixture",uri));
        if(action.equals(Intent.ACTION_VIEW))intent.setDataAndType(uri,"image/png");else intent.putExtra(Intent.EXTRA_STREAM,uri).putExtra(Intent.EXTRA_TEXT,note);
        context.startActivity(intent);Activity activity=photoMonitor.waitForActivityWithTimeout(10000),main=mainMonitor.getLastActivity();test.removeMonitor(mainMonitor);test.removeMonitor(photoMonitor);if(main!=null)activities.add(main);
        expect(activity instanceof ImageRecordActivity,"image did not open as a life record");activities.add(activity);await(()->activity.hasWindowFocus(),"photo editor has no focus");await(()->activity.findViewById(android.R.id.content).findViewWithTag("image_record_save").isEnabled(),"photo import did not finish");return activity;
    }
    private int count(){try(LifeRecords records=new LifeRecords(context)){return records.all(true).size();}}
    private void capture(String name)throws Exception{test.waitForIdleSync();SystemClock.sleep(250);Bitmap bitmap=test.getUiAutomation().takeScreenshot();try(OutputStream out=new FileOutputStream(new File(context.getCacheDir(),name))){bitmap.compress(Bitmap.CompressFormat.PNG,100,out);}bitmap.recycle();}
    void run(){
        Bundle result=new Bundle();int code=Activity.RESULT_OK;
        SharedPreferences settings=context.getSharedPreferences("gray_settings",0),prefs=context.getSharedPreferences("home_conversation",0),sync=context.getSharedPreferences("life_sync",0);String oldServer=settings.getString("server_url",null),oldTree=sync.getString("tree",null);boolean proactive=prefs.getBoolean("proactive",true);
        try{
            expect(Build.HARDWARE.equals("ranchu")||Build.HARDWARE.equals("goldfish"),"Use an independent emulator");context.deleteDatabase("image-record-tests.db");settings.edit().putString("server_url","").commit();prefs.edit().putBoolean("proactive",false).commit();sync.edit().putString("tree","").commit();
            check("versionFourMigrationPreservesRecords",()->{
                context.deleteDatabase("image-migration-tests.db");try(LifeRecords records=new LifeRecords(context,"image-migration-tests.db")){records.save("old",payload("原有日记"),"原有日记");}
                try(AssistantStore old=new AssistantStore(context,"image-migration-tests.db")){SQLiteDatabase db=old.getWritableDatabase();db.execSQL("DROP TABLE life_record_image");db.setVersion(4);}
                try(LifeRecords records=new LifeRecords(context,"image-migration-tests.db")){expect(records.find("old").payload.optString("content").equals("原有日记"),"migration lost record");expect(records.find("old").images.isEmpty(),"migration created unexpected image");}finally{context.deleteDatabase("image-migration-tests.db");}
            });
            check("retainedImageSurvivesSourceAndCacheLossAndRetry",()->{
                Uri source=fixture();LifeImages.Image photo=LifeImages.importImage(context,source,"相册照片.png");images.add(photo);String id=UUID.randomUUID().toString();
                try(LifeRecords records=new LifeRecords(context,"image-record-tests.db")){records.saveWithImage(id,payload("图片日记"),"图片日记",photo);}
                LifeImages.file(context,true,photo.fileName).delete();try(OutputStream out=context.getContentResolver().openOutputStream(source,"wt")){out.write(new byte[]{1,2,3});}
                try(LifeRecords reopened=new LifeRecords(context,"image-record-tests.db")){
                    LifeRecords.Record record=reopened.find(id);expect(record.images.size()==1,"image reference lost after reopening");expect(LifeImages.hash(LifeImages.file(context,false,photo.fileName)).equals(photo.hash),"saved bytes depend on source permission");
                    reopened.saveWithImage(id,payload("重试不应覆盖"),"retry",photo);expect(reopened.find(id).payload.optString("content").equals("图片日记"),"retry rewrote record");
                    reopened.update(id,payload("补充文字"),false);expect(reopened.find(id).images.size()==1,"editing lost image");expect(LifeRecords.markdown(reopened.find(id)).contains("![](图片/"+photo.fileName+")"),"markdown image link missing");
                    reopened.update(id,payload("补充文字"),true);expect(!LifeRecords.markdown(reopened.find(id)).contains("![]("),"deleted image still exported");reopened.update(id,payload("撤销删除"),false);expect(reopened.find(id).images.size()==1,"undo lost image");
                    VaultExport.exportToRoot(context,reopened,root);Uri folder=DocumentsContract.buildDocumentUriUsingTree(root,"Greta 生活记录/图片/"+photo.fileName);
                    try(InputStream in=context.getContentResolver().openInputStream(folder)){expect(LifeImages.hash(in).equals(photo.hash),"vault image bytes differ");}
                    reopened.update(id,payload("再次修改文字"),false);VaultExport.exportToRoot(context,reopened,root);
                    try(OutputStream out=context.getContentResolver().openOutputStream(folder,"wt")){out.write(new byte[]{7,8,9});}reopened.update(id,payload("不覆盖外部图片"),false);boolean conflict=false;try{VaultExport.exportToRoot(context,reopened,root);}catch(IOException expected){conflict=true;}expect(conflict,"external image edit was overwritten");
                }
            });
            check("privateProviderRejectsWritesAndTraversal",()->{LifeImages.Image photo=images.get(0);boolean blocked=false;try(OutputStream out=context.getContentResolver().openOutputStream(LifeImages.uri(context,false,photo.fileName),"wt")){}catch(Exception expected){blocked=true;}expect(blocked,"private image provider accepted write");blocked=false;try{LifeImages.file(context,false,"../../greta.db");}catch(IOException expected){blocked=true;}expect(blocked,"path traversal was accepted");});
            check("webpPrintUsesCompatibleCopyAndKeepsOriginal",()->{
                Uri source=DocumentsContract.createDocument(context.getContentResolver(),root,"image/webp","fixture-"+UUID.randomUUID()+".webp");Bitmap bitmap=Bitmap.createBitmap(80,60,Bitmap.Config.ARGB_8888);bitmap.eraseColor(Color.argb(120,20,100,200));try(OutputStream out=context.getContentResolver().openOutputStream(source,"wt")){bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSLESS,100,out);}bitmap.recycle();
                LifeImages.Image photo=LifeImages.importImage(context,source,"透明图片.webp");images.add(photo);Uri print=LifeImages.preparePrint(context,true,photo);expect("image/jpeg".equals(context.getContentResolver().getType(print)),"print copy is not JPEG");expect(LifeImages.hash(LifeImages.file(context,true,photo.fileName)).equals(photo.hash),"printing modified the original image");Bitmap decoded=LifeImages.preview(LifeImages.file(context,true,print.getLastPathSegment()),100);try{expect(decoded.getWidth()>0,"print copy cannot decode");}finally{decoded.recycle();}
            });
            check("printHonorsCameraOrientationAndWhiteTransparency",()->{
                Uri source=DocumentsContract.createDocument(context.getContentResolver(),root,"image/jpeg","camera-"+UUID.randomUUID()+".jpg");Bitmap original=Bitmap.createBitmap(120,80,Bitmap.Config.ARGB_8888);original.eraseColor(Color.BLUE);try(OutputStream out=context.getContentResolver().openOutputStream(source,"wt")){original.compress(Bitmap.CompressFormat.JPEG,95,out);}original.recycle();
                try(ParcelFileDescriptor descriptor=context.getContentResolver().openFileDescriptor(source,"rw")){android.media.ExifInterface exif=new android.media.ExifInterface(descriptor.getFileDescriptor());exif.setAttribute(android.media.ExifInterface.TAG_ORIENTATION,String.valueOf(android.media.ExifInterface.ORIENTATION_ROTATE_90));exif.saveAttributes();}
                LifeImages.Image photo=LifeImages.importImage(context,source,"相机照片.jpg");images.add(photo);Uri print=LifeImages.preparePrint(context,true,photo);Bitmap decoded=LifeImages.preview(LifeImages.file(context,true,print.getLastPathSegment()),300);try{expect(decoded.getWidth()==80&&decoded.getHeight()==120,"camera orientation was ignored");}finally{decoded.recycle();}
                Uri transparent=DocumentsContract.createDocument(context.getContentResolver(),root,"image/png","transparent-"+UUID.randomUUID()+".png");Bitmap blank=Bitmap.createBitmap(20,20,Bitmap.Config.ARGB_8888);try(OutputStream out=context.getContentResolver().openOutputStream(transparent,"wt")){blank.compress(Bitmap.CompressFormat.PNG,100,out);}blank.recycle();LifeImages.Image png=LifeImages.importImage(context,transparent,"透明图片.png");images.add(png);Uri copy=LifeImages.preparePrint(context,true,png);decoded=LifeImages.preview(LifeImages.file(context,true,copy.getLastPathSegment()),100);try{expect(Color.red(decoded.getPixel(10,10))>245,"transparent PNG printed on a dark background");}finally{decoded.recycle();}
            });
            check("invalidImageIsNotSaved",()->{Uri bad=DocumentsContract.createDocument(context.getContentResolver(),root,"image/png","invalid-"+UUID.randomUUID()+".png");try(OutputStream out=context.getContentResolver().openOutputStream(bad,"wt")){out.write("not an image".getBytes());}boolean failed=false;try{LifeImages.importImage(context,bad,"bad.png");}catch(IOException expected){failed=true;}expect(failed,"invalid image was imported");});
            check("shareDefaultsToRecordAndPrintKeepsUnsavedNote",()->{
                int before=count();String caption="image-ui-"+UUID.randomUUID();Activity editor=openPhoto(fixture(),Intent.ACTION_SEND,caption);EditText note=editor.findViewById(android.R.id.content).findViewWithTag("image_record_note");ui(()->expect(note.getText().toString().equals(caption),"shared caption missing"));expect(count()==before,"opening photo created a record");capture("image-record-editor.png");
                Instrumentation.ActivityMonitor printer=test.addMonitor(PrintActivity.class.getName(),null,false);ui(()->editor.findViewById(android.R.id.content).findViewWithTag("image_record_print").performClick());Activity print=printer.waitForActivityWithTimeout(10000);test.removeMonitor(printer);expect(print!=null,"print mode did not open");activities.add(print);expect(print.getIntent().getData().getAuthority().equals(context.getPackageName()+".images"),"print still relies on external URI");try(InputStream in=context.getContentResolver().openInputStream(print.getIntent().getData())){expect(in.read()!=-1,"print cannot read image");}expect(count()==before,"printing saved an unwanted record");finish(print);await(()->editor.hasWindowFocus(),"record editor did not return");ui(()->expect(note.getText().toString().equals(caption),"printing discarded note"));
                Instrumentation.ActivityMonitor saved=test.addMonitor(RecordsActivity.class.getName(),null,false);ui(()->editor.findViewById(android.R.id.content).findViewWithTag("image_record_save").performClick());Activity book=saved.waitForActivityWithTimeout(10000);test.removeMonitor(saved);expect(book!=null,"save did not open the life record book");activities.add(book);await(()->book.hasWindowFocus(),"saved record book has no focus");
                try(LifeRecords records=new LifeRecords(context)){LifeRecords.Record found=null;for(LifeRecords.Record r:records.all(false))if(r.payload.optString("content").equals(caption))found=r;expect(found!=null&&found.images.size()==1,"photo and note were not saved together");recordIds.add(found.id);images.addAll(found.images);}capture("image-record-saved.png");finish(book);for(Activity a:new ArrayList<>(activities))finish(a);activities.clear();
            });
            check("openWithActionViewAlsoDefaultsToRecord",()->{int before=count();Activity editor=openPhoto(fixture(),Intent.ACTION_VIEW,"");expect(count()==before,"ACTION_VIEW automatically saved a record");finish(editor);});
            result.putString("stream","Image import tests passed: "+passed+"\n");
        }catch(Throwable e){code=Activity.RESULT_CANCELED;result.putString("stream",android.util.Log.getStackTraceString(e));}
        finally{
            for(Activity a:activities)finish(a);context.deleteDatabase("image-record-tests.db");
            try(AssistantStore records=new AssistantStore(context)){for(String id:recordIds){records.getWritableDatabase().delete("life_record_image","record_id=?",new String[]{id});records.getWritableDatabase().delete("life_record","id=?",new String[]{id});}}
            for(LifeImages.Image image:images)try{LifeImages.file(context,false,image.fileName).delete();LifeImages.file(context,true,image.fileName).delete();}catch(Exception ignored){}
            SharedPreferences.Editor edit=settings.edit();if(oldServer==null)edit.remove("server_url");else edit.putString("server_url",oldServer);edit.commit();edit=sync.edit();if(oldTree==null)edit.remove("tree");else edit.putString("tree",oldTree);edit.commit();prefs.edit().putBoolean("proactive",proactive).commit();
        }
        test.finish(code,result);
    }
}
