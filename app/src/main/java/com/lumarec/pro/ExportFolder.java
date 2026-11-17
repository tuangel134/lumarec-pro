package com.lumarec.pro;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import java.io.InputStream;
import java.io.OutputStream;

/** The folder receives a clear copy only after the user explicitly confirms an export. */
final class ExportFolder {
    static final class IncompleteExportException extends java.io.IOException {
        IncompleteExportException(Exception cause) { super("La copia incompleta no se pudo eliminar de la carpeta.",cause); }
    }
    private static SharedPreferences prefs(Context c) { return c.getSharedPreferences("export_folder",Context.MODE_PRIVATE); }
    static Uri tree(Context c) { String value=prefs(c).getString("uri",""); return value.isEmpty() ? null : Uri.parse(value); }
    static String name(Context c) { return prefs(c).getString("name","Sin carpeta elegida"); }
    static void remember(Context c,Intent result) throws Exception {
        Uri tree=result.getData();
        if (tree==null || !DocumentsContract.isTreeUri(tree)) throw new IllegalArgumentException("Elige una carpeta.");
        int flags=result.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        if ((flags&Intent.FLAG_GRANT_WRITE_URI_PERMISSION)==0) throw new SecurityException("La carpeta no permite escritura.");
        Uri previous=tree(c); c.getContentResolver().takePersistableUriPermission(tree,flags);
        boolean accepted=false;
        try {
            Uri document=DocumentsContract.buildDocumentUriUsingTree(tree,DocumentsContract.getTreeDocumentId(tree));
            String name="Carpeta de exportación";
            try (Cursor cursor=c.getContentResolver().query(document,new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME,DocumentsContract.Document.COLUMN_FLAGS},null,null,null)) {
                if (cursor==null || !cursor.moveToFirst()) throw new java.io.IOException("No se pudo abrir la carpeta.");
                if ((cursor.getInt(1)&DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE)==0) throw new SecurityException("No se pueden crear vídeos en esta carpeta.");
                name=cursor.getString(0);
            }
            prefs(c).edit().putString("uri",tree.toString()).putString("name",name).apply(); accepted=true;
            if (previous!=null && !previous.equals(tree)) release(c,previous);
        } finally { if (!accepted && !tree.equals(previous)) release(c,tree); }
    }
    private static void release(Context c,Uri tree) {
        for (android.content.UriPermission permission:c.getContentResolver().getPersistedUriPermissions()) if (tree.equals(permission.getUri())) {
            int flags=(permission.isReadPermission() ? Intent.FLAG_GRANT_READ_URI_PERMISSION : 0)|(permission.isWritePermission() ? Intent.FLAG_GRANT_WRITE_URI_PERMISSION : 0);
            try { c.getContentResolver().releasePersistableUriPermission(tree,flags); } catch (Exception ignored) { }
        }
    }
    static void clear(Context c) {
        Uri tree=tree(c); prefs(c).edit().clear().apply();
        if (tree!=null) release(c,tree);
    }
    static Uri save(Context c,VideoLibrary.Video video,byte[] key) throws Exception {
        if (video.hidden() && (!SecureVault.unlocked() || key==null)) throw new SecurityException("Desbloquea la bóveda antes de exportar.");
        Uri tree=tree(c); if (tree==null) throw new IllegalStateException("Elige primero una carpeta de exportación.");
        Uri parent=DocumentsContract.buildDocumentUriUsingTree(tree,DocumentsContract.getTreeDocumentId(tree));
        Uri target=DocumentsContract.createDocument(c.getContentResolver(),parent,"video/mp4",VideoNotes.filename(VideoNotes.name(c,video)));
        if (target==null) throw new java.io.IOException("No se pudo crear la copia.");
        boolean complete=false;
        try {
            try (OutputStream output=c.getContentResolver().openOutputStream(target,"w")) {
                if (output==null) throw new java.io.IOException("La carpeta no permite guardar el vídeo.");
                if (video.hidden()) SecureVault.decryptTo(c,video.vaultId,output,key);
                else try (InputStream source=c.getContentResolver().openInputStream(video.uri)) {
                    if (source==null) throw new java.io.IOException("No se pudo leer el vídeo.");
                    byte[] buffer=new byte[256*1024];
                    try { int n; while ((n=source.read(buffer))!=-1) { if (Thread.currentThread().isInterrupted()) throw new java.io.IOException("Exportación cancelada."); output.write(buffer,0,n); } }
                    finally { java.util.Arrays.fill(buffer,(byte)0); }
                }
                output.flush();
            }
            complete=true; return target;
        } finally {
            if (!complete) try {
                if (!DocumentsContract.deleteDocument(c.getContentResolver(),target)) throw new java.io.IOException("El proveedor no eliminó la copia.");
            } catch (Exception error) { throw new IncompleteExportException(error); }
        }
    }
}
