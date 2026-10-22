package dev.pixel.sync;

import android.content.*;
import android.database.*;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.*;
import java.util.List;

/** Read-only private images, shared with internal printing through content URIs. */
public final class LifeImageProvider extends ContentProvider {
    @Override public boolean onCreate(){return true;}
    private File resolve(Uri uri)throws FileNotFoundException{
        try{List<String> path=uri.getPathSegments();if(!uri.getAuthority().equals(getContext().getPackageName()+".images")||path.size()!=2||!(path.get(0).equals("draft")||path.get(0).equals("image")))throw new IOException();File file=LifeImages.file(getContext(),path.get(0).equals("draft"),path.get(1));if(!file.isFile())throw new IOException();return file;}catch(Exception e){throw new FileNotFoundException("图片不存在");}
    }
    @Override public ParcelFileDescriptor openFile(Uri uri,String mode)throws FileNotFoundException{if(!"r".equals(mode))throw new FileNotFoundException("图片只读");return ParcelFileDescriptor.open(resolve(uri),ParcelFileDescriptor.MODE_READ_ONLY);}
    @Override public String getType(Uri uri){try{BitmapFactory.Options options=new BitmapFactory.Options();options.inJustDecodeBounds=true;BitmapFactory.decodeFile(resolve(uri).getPath(),options);return options.outMimeType;}catch(Exception e){return null;}}
    @Override public Cursor query(Uri uri,String[] projection,String selection,String[] args,String order){try{File file=resolve(uri);String[] columns=projection==null?new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE}:projection;MatrixCursor result=new MatrixCursor(columns);MatrixCursor.RowBuilder row=result.newRow();for(String column:columns)row.add(column.equals(OpenableColumns.DISPLAY_NAME)?file.getName():column.equals(OpenableColumns.SIZE)?file.length():null);return result;}catch(Exception e){return null;}}
    @Override public Uri insert(Uri uri,ContentValues values){throw new UnsupportedOperationException("只读图片");}
    @Override public int update(Uri uri,ContentValues values,String where,String[] args){throw new UnsupportedOperationException("只读图片");}
    @Override public int delete(Uri uri,String where,String[] args){throw new UnsupportedOperationException("只读图片");}
}
