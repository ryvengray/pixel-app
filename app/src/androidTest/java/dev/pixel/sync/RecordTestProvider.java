package dev.pixel.sync;

import android.database.Cursor;
import android.database.MatrixCursor;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsProvider;
import android.provider.DocumentsContract.Document;
import java.io.*;

/** Test APK only. Emulates SAF with files inside the test app cache, never a real vault. */
public final class RecordTestProvider extends android.content.ContentProvider {
    private File root;
    @Override public boolean onCreate(){root=new File(getContext().getCacheDir(),"record-test-vault");root.mkdirs();return true;}
    private File file(String id)throws FileNotFoundException{try{File result=id.equals("root")?root:new File(root,id);if(!result.getCanonicalPath().startsWith(root.getCanonicalPath()+"/")&&!result.equals(root))throw new IOException();return result;}catch(IOException e){throw new FileNotFoundException();}}
    private String id(File f){return f.equals(root)?"root":f.getAbsolutePath().substring(root.getAbsolutePath().length()+1);}
    private String[] columns(String[] projection){return projection==null?new String[]{Document.COLUMN_DOCUMENT_ID,Document.COLUMN_DISPLAY_NAME,Document.COLUMN_MIME_TYPE,Document.COLUMN_FLAGS,Document.COLUMN_SIZE}:projection;}
    private void row(MatrixCursor cursor,File f){MatrixCursor.RowBuilder row=cursor.newRow();for(String key:cursor.getColumnNames())switch(key){case Document.COLUMN_DOCUMENT_ID:row.add(id(f));break;case Document.COLUMN_DISPLAY_NAME:row.add(f.getName());break;case Document.COLUMN_MIME_TYPE:row.add(f.isDirectory()?Document.MIME_TYPE_DIR:"text/markdown");break;case Document.COLUMN_SIZE:row.add(f.length());break;case Document.COLUMN_FLAGS:row.add(Document.FLAG_SUPPORTS_WRITE|Document.FLAG_DIR_SUPPORTS_CREATE);break;default:row.add(null);}}
    @Override public Cursor query(android.net.Uri uri,String[] projection,String selection,String[] args,String order){try{return uri.getLastPathSegment().equals("children")?queryChildDocuments(android.provider.DocumentsContract.getDocumentId(uri),projection,order):queryDocument(android.provider.DocumentsContract.getDocumentId(uri),projection);}catch(Exception e){throw new IllegalStateException(e);}}
    @Override public android.os.Bundle call(String method,String arg,android.os.Bundle extras){try{
        if(!method.equals("android:createDocument"))throw new UnsupportedOperationException(method);
        android.net.Uri parent=extras.getParcelable("uri");String id=createDocument(android.provider.DocumentsContract.getDocumentId(parent),extras.getString(Document.COLUMN_MIME_TYPE),extras.getString(Document.COLUMN_DISPLAY_NAME));
        android.os.Bundle result=new android.os.Bundle();result.putParcelable("uri",android.provider.DocumentsContract.buildDocumentUriUsingTree(parent,id));return result;
    }catch(Exception e){throw new IllegalStateException(e);}}
    @Override public android.net.Uri insert(android.net.Uri uri,android.content.ContentValues values){throw new UnsupportedOperationException();}
    @Override public int update(android.net.Uri uri,android.content.ContentValues values,String where,String[] args){throw new UnsupportedOperationException();}
    @Override public int delete(android.net.Uri uri,String where,String[] args){throw new UnsupportedOperationException();}
    @Override public String getType(android.net.Uri uri){return "text/markdown";}
    @Override public ParcelFileDescriptor openFile(android.net.Uri uri,String mode)throws FileNotFoundException{return openDocument(android.provider.DocumentsContract.getDocumentId(uri),mode,null);}
    public boolean isChildDocument(String parent,String child){try{return file(child).getCanonicalPath().startsWith(file(parent).getCanonicalPath()+"/");}catch(Exception e){return false;}}
    public Cursor queryRoots(String[] projection){return new MatrixCursor(new String[]{"root_id","document_id","title"});}
    public Cursor queryDocument(String id,String[] projection)throws FileNotFoundException{MatrixCursor c=new MatrixCursor(columns(projection));row(c,file(id));return c;}
    public Cursor queryChildDocuments(String id,String[] projection,String order)throws FileNotFoundException{MatrixCursor c=new MatrixCursor(columns(projection));File[] files=file(id).listFiles();if(files!=null)for(File f:files)row(c,f);return c;}
    public String createDocument(String parent,String mime,String name)throws FileNotFoundException{try{File f=new File(file(parent),name);if(mime.equals(Document.MIME_TYPE_DIR))f.mkdirs();else f.createNewFile();return id(f);}catch(IOException e){throw new FileNotFoundException();}}
    public ParcelFileDescriptor openDocument(String id,String mode,android.os.CancellationSignal signal)throws FileNotFoundException{return ParcelFileDescriptor.open(file(id),ParcelFileDescriptor.parseMode(mode));}
}
