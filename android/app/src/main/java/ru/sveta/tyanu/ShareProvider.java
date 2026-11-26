package ru.sveta.tyanu;

import android.content.*;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.webkit.MimeTypeMap;
import java.io.*;

/** Read-only URIs, explicitly granted by the foreground activity to the chosen viewer. */
public final class ShareProvider extends ContentProvider {
    private static final java.util.Map<String,File> shared=new java.util.concurrent.ConcurrentHashMap<>();
    public static Uri share(File file)throws IOException{
        String token=java.util.UUID.randomUUID().toString();shared.put(token,file.getCanonicalFile());
        return new Uri.Builder().scheme("content").authority("ru.sveta.tyanu.files").appendPath(token).appendPath(file.getName()).build();
    }
    private File file(Uri uri)throws FileNotFoundException{
        File file=uri.getPathSegments().isEmpty()?null:shared.get(uri.getPathSegments().get(0));
        if(file==null||!file.isFile())throw new FileNotFoundException("Файл недоступен");return file;
    }
    public boolean onCreate(){return true;}
    public String getType(Uri uri){String ext=MimeTypeMap.getFileExtensionFromUrl(uri.toString());String type=MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext.toLowerCase(java.util.Locale.ROOT));return type==null?"application/octet-stream":type;}
    public ParcelFileDescriptor openFile(Uri uri,String mode)throws FileNotFoundException{if(!"r".equals(mode))throw new FileNotFoundException("Только чтение");return ParcelFileDescriptor.open(file(uri),ParcelFileDescriptor.MODE_READ_ONLY);}
    public Cursor query(Uri uri,String[] projection,String selection,String[] args,String sort){try{File f=file(uri);String[] columns=projection==null?new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE}:projection;MatrixCursor c=new MatrixCursor(columns);Object[] row=new Object[columns.length];for(int i=0;i<columns.length;i++)row[i]=OpenableColumns.DISPLAY_NAME.equals(columns[i])?f.getName():OpenableColumns.SIZE.equals(columns[i])?f.length():null;c.addRow(row);return c;}catch(Exception e){return null;}}
    public Uri insert(Uri uri,ContentValues v){throw new UnsupportedOperationException();}
    public int update(Uri uri,ContentValues v,String s,String[] a){throw new UnsupportedOperationException();}
    public int delete(Uri uri,String s,String[] a){throw new UnsupportedOperationException();}
}
