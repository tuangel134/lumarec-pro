package com.lumarec.pro;

import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.MediaStore;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

final class VideoLibrary {
    static final String DIRECTORY="Movies/LumaRec/";
    static final long SHARE_TTL=15L*60*1000;
    static final Uri COLLECTION=MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
    static final class Video {
        Uri uri; File encrypted; String vaultId,name; long bytes,duration,date;
        boolean hidden() { return vaultId!=null; }
    }
    static Uri create(Context context,String name) {
        ContentValues values=new ContentValues();
        values.put(MediaStore.Video.Media.DISPLAY_NAME,name);
        values.put(MediaStore.Video.Media.MIME_TYPE,"video/mp4");
        values.put(MediaStore.Video.Media.RELATIVE_PATH,DIRECTORY);
        values.put(MediaStore.Video.Media.IS_PENDING,1);
        return context.getContentResolver().insert(COLLECTION,values);
    }
    static Uri publishFile(Context context,File file,long duration) throws Exception {
        Uri uri=create(context,VideoNotes.newRecordingName(context));
        if (uri==null) throw new java.io.IOException("No se pudo crear el vídeo en la galería.");
        boolean ok=false;
        try {
            try (InputStream source=new FileInputStream(file); OutputStream output=context.getContentResolver().openOutputStream(uri,"w")) {
                if (output==null) throw new java.io.IOException("No se pudo guardar en la galería.");
                byte[] buffer=new byte[256*1024]; int count; while ((count=source.read(buffer))!=-1) output.write(buffer,0,count);
                output.flush();
            }
            publish(context,uri,duration); ok=true; return uri;
        } finally { if (!ok) context.getContentResolver().delete(uri,null,null); }
    }
    static void publish(Context context,Uri uri,long duration) {
        ContentValues values=new ContentValues(); values.put(MediaStore.Video.Media.IS_PENDING,0); values.put(MediaStore.Video.Media.DURATION,duration);
        if (context.getContentResolver().update(uri,values,null,null)!=1) throw new IllegalStateException("No se pudo publicar el vídeo.");
    }
    static List<Video> list(Context context) {
        List<Video> videos=new ArrayList<>();
        if (SecureVault.unlocked()) videos.addAll(privateVideos(context));
        videos.addAll(publicVideos(context)); return videos;
    }
    private static List<Video> privateVideos(Context context) {
        List<Video> videos=new ArrayList<>(); Set<String> seen=new HashSet<>();
        try {
            File[] directories={SecureVault.directory(context),SecureVault.legacyDirectory(context)};
            for (File directory:directories) {
                if (directory==null) continue;
                File[] files=directory.listFiles((dir,name)->name.matches("[0-9a-fA-F-]{36}\\.lrv"));
                if (files==null) continue;
                for (File file:files) {
                    String id=file.getName().substring(0,36); if (!seen.add(id)) continue;
                    Video video=new Video(); video.encrypted=file; video.vaultId=id;
                    video.date=file.lastModified(); video.bytes=file.length();
                    try (java.io.DataInputStream meta=new java.io.DataInputStream(new FileInputStream(new File(directory,id+".meta")))) {
                        video.duration=meta.readLong(); video.date=meta.readLong(); video.bytes=meta.readLong();
                    } catch (Exception ignored) { }
                    video.name="Vídeo protegido · "+new SimpleDateFormat("d MMM · HH:mm",Locale.getDefault()).format(new Date(video.date)); videos.add(video);
                }
            }
            videos.sort((a,b)->Long.compare(b.date,a.date));
        } catch (Exception ignored) { }
        return videos;
    }
    static List<Video> publicVideos(Context context) {
        List<Video> videos=new ArrayList<>();
        String[] columns={MediaStore.Video.Media._ID,MediaStore.Video.Media.DISPLAY_NAME,MediaStore.Video.Media.SIZE,MediaStore.Video.Media.DURATION,MediaStore.Video.Media.DATE_ADDED};
        try (Cursor cursor=context.getContentResolver().query(COLLECTION,columns,
                MediaStore.Video.Media.RELATIVE_PATH+"=? AND "+MediaStore.Video.Media.IS_PENDING+"=0",new String[]{DIRECTORY},MediaStore.Video.Media.DATE_ADDED+" DESC")) {
            if (cursor!=null) while (cursor.moveToNext()) {
                Video video=new Video(); video.uri=ContentUris.withAppendedId(COLLECTION,cursor.getLong(0));
                video.name=cursor.getString(1); video.bytes=cursor.getLong(2); video.duration=cursor.getLong(3); video.date=cursor.getLong(4)*1000; videos.add(video);
            }
        }
        return videos;
    }
    /** Only called after the user confirms that another app may receive a clear copy. */
    static Uri exportForShare(Context context,Video video,byte[] key) throws Exception {
        if (!video.hidden() || key==null) throw new SecurityException("La bóveda está bloqueada.");
        File dir=new File(context.getCacheDir(),"outgoing-videos");
        if (!dir.exists() && !dir.mkdirs()) throw new java.io.IOException("No se pudo preparar el vídeo.");
        String token=java.util.UUID.randomUUID().toString(); File file=new File(dir,token+".mp4"), temp=new File(dir,token+".tmp");
        try (FileOutputStream output=new FileOutputStream(temp)) { SecureVault.decryptTo(context,video.vaultId,output,key); output.getFD().sync(); }
        catch (Exception error) { temp.delete(); throw error; }
        if (!temp.renameTo(file)) { temp.delete(); throw new java.io.IOException("No se pudo compartir el vídeo."); }
        file.setLastModified(System.currentTimeMillis()); return sharedUri(file);
    }
    static Uri sharedUri(File file) { return Uri.parse("content://com.lumarec.pro.media/outgoing/"+file.getName()); }
    static void discardExport(Context c,Uri uri) {
        c.revokeUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION);
        String name=uri.getLastPathSegment();
        if (name!=null && name.matches("[0-9a-fA-F-]{36}\\.mp4")) new File(new File(c.getCacheDir(),"outgoing-videos"),name).delete();
    }
    static boolean delete(Context context,Video video) {
        if (video.hidden()) try { if (!SecureVault.unlocked()) return false; SecureVault.delete(context,video.vaultId); return true; }
        catch (Exception ignored) { return false; }
        return context.getContentResolver().delete(video.uri,null,null)>0;
    }
    static int hideExisting(Context context,byte[] key) throws Exception {
        if (key==null) throw new SecurityException("La bóveda está bloqueada.");
        int moved=0;
        for (Video video:publicVideos(context)) {
            try (InputStream source=context.getContentResolver().openInputStream(video.uri)) {
                if (source==null) throw new java.io.IOException("No se pudo abrir el vídeo.");
                String id=SecureVault.save(context,source,video.bytes,video.duration,key);
                Video target=new Video(); target.vaultId=id; VideoNotes.copy(context,video,target);
                if (context.getContentResolver().delete(video.uri,null,null)!=1) throw new java.io.IOException("No se pudo retirar el original de la galería.");
                VideoNotes.forget(context,video);
                moved++;
            }
        }
        return moved;
    }
    static int showVault(Context context,byte[] key) throws Exception {
        if (key==null) throw new SecurityException("La bóveda está bloqueada.");
        int moved=0;
        for (Video video:privateVideos(context)) {
            Uri uri=create(context,VideoNotes.newRecordingName(context)); if (uri==null) throw new java.io.IOException("No se pudo exportar el vídeo.");
            boolean ok=false;
            try {
                try (OutputStream output=context.getContentResolver().openOutputStream(uri,"w")) {
                    if (output==null) throw new java.io.IOException("No se pudo exportar el vídeo.");
                    SecureVault.decryptTo(context,video.vaultId,output,key);
                }
                publish(context,uri,video.duration);
                Video target=new Video(); target.uri=uri; VideoNotes.copy(context,video,target); ok=true;
            } finally { if (!ok) context.getContentResolver().delete(uri,null,null); }
            SecureVault.delete(context,video.vaultId); VideoNotes.forget(context,video); moved++;
        }
        return moved;
    }
    static void clearLegacyExports(Context context) {
        File[] old=new File(context.getCacheDir(),"shared-videos").listFiles();
        if (old!=null) for (File file:old) {
            try { context.revokeUriPermission(Uri.parse("content://com.lumarec.pro.media/shared/"+file.getName()),Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch (Exception ignored) { }
            file.delete();
        }
        File[] leftovers=context.getCacheDir().listFiles((dir,name)->name.endsWith(".restore.mp4") || name.startsWith("vault-playback-"));
        if (leftovers!=null) for (File file:leftovers) file.delete();
        File[] interrupted=new File(context.getCacheDir(),"outgoing-videos").listFiles((dir,name)->name.endsWith(".tmp"));
        if (interrupted!=null) for (File file:interrupted) file.delete();
    }
    static boolean expired(File file) {
        long age=System.currentTimeMillis()-file.lastModified(); return age<0 || age>=SHARE_TTL;
    }
    static void clearExpiredExports(Context context) {
        File[] files=new File(context.getCacheDir(),"outgoing-videos").listFiles(); if (files==null) return;
        for (File file:files) if (expired(file)) { try { discardExport(context,sharedUri(file)); } catch (Exception ignored) { } file.delete(); }
    }
    static String time(long millis) {
        long total=Math.max(0,millis/1000); return String.format(Locale.US,"%02d:%02d:%02d",total/3600,(total/60)%60,total%60);
    }
    static String size(long bytes) {
        return bytes>=1_000_000_000L ? String.format(Locale.getDefault(),"%.2f GB",bytes/1e9) : String.format(Locale.getDefault(),"%.1f MB",bytes/1e6);
    }
}
