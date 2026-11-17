package com.lumarec.pro;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.File;

/** Exposes only short-lived copies explicitly authorized through the Share confirmation. */
public final class LocalMediaProvider extends ContentProvider {
    static final String AUTHORITY="com.lumarec.pro.media";
    @Override public boolean onCreate() { return true; }
    private File fileFor(Uri uri) throws java.io.IOException {
        if (getContext()==null || !AUTHORITY.equals(uri.getAuthority()) || uri.getPathSegments().size()!=2 || !"outgoing".equals(uri.getPathSegments().get(0))) throw new java.io.FileNotFoundException();
        String name=uri.getLastPathSegment();
        if (name==null || !name.matches("[0-9a-fA-F-]{36}\\.mp4")) throw new java.io.FileNotFoundException();
        File root=new File(getContext().getCacheDir(),"outgoing-videos").getCanonicalFile();
        File file=new File(root,name).getCanonicalFile();
        if (!file.getParentFile().equals(root) || !file.isFile() || VideoLibrary.expired(file)) throw new java.io.FileNotFoundException();
        return file;
    }
    @Override public String getType(Uri uri) { return "video/mp4"; }
    @Override public Cursor query(Uri uri,String[] projection,String selection,String[] args,String sort) {
        try {
            File file=fileFor(uri);
            String[] columns=projection==null ? new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE} : projection;
            MatrixCursor cursor=new MatrixCursor(columns,1); Object[] row=new Object[columns.length];
            for (int i=0;i<columns.length;i++) if (OpenableColumns.DISPLAY_NAME.equals(columns[i])) row[i]="LumaRec_"+file.getName(); else if (OpenableColumns.SIZE.equals(columns[i])) row[i]=file.length();
            cursor.addRow(row); return cursor;
        } catch (Exception ignored) { return new MatrixCursor(new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE}); }
    }
    @Override public ParcelFileDescriptor openFile(Uri uri,String mode) throws java.io.FileNotFoundException {
        if (!"r".equals(mode)) throw new java.io.FileNotFoundException("Sólo lectura");
        try { return ParcelFileDescriptor.open(fileFor(uri),ParcelFileDescriptor.MODE_READ_ONLY); }
        catch (Exception error) { throw new java.io.FileNotFoundException("Archivo de vídeo temporal no disponible."); }
    }
    @Override public Uri insert(Uri uri,ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri,String selection,String[] args) { return 0; }
    @Override public int update(Uri uri,ContentValues values,String selection,String[] args) { return 0; }
}
