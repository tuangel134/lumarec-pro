package com.lumarec.pro;

import android.content.Context;
import android.content.SharedPreferences;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

final class VideoNotes {
    private static SharedPreferences store(Context c) { return c.getSharedPreferences("video_notes",Context.MODE_PRIVATE); }
    private static String id(VideoLibrary.Video video) { return video.hidden() ? video.vaultId : video.uri.toString(); }
    static boolean favorite(Context c,VideoLibrary.Video video) { return store(c).getBoolean("favorite:"+id(video),false); }
    static void favorite(Context c,VideoLibrary.Video video,boolean value) {
        if (video.hidden() && !SecureVault.unlocked()) throw new SecurityException("Desbloquea la bóveda.");
        store(c).edit().putBoolean("favorite:"+id(video),value).apply();
    }
    static String name(Context c,VideoLibrary.Video video) { return store(c).getString("name:"+id(video),video.name); }
    static void rename(Context c,VideoLibrary.Video video,String name) {
        if (video.hidden() && !SecureVault.unlocked()) throw new SecurityException("Desbloquea la bóveda.");
        String title=name.trim(); if (title.isEmpty()) throw new IllegalArgumentException("Escribe un nombre.");
        store(c).edit().putString("name:"+id(video),title).apply();
    }
    static void forget(Context c,VideoLibrary.Video video) { store(c).edit().remove("name:"+id(video)).remove("favorite:"+id(video)).apply(); }
    /** Used only by an already authorized gallery/vault migration. */
    static void copy(Context c,VideoLibrary.Video source,VideoLibrary.Video target) {
        SharedPreferences notes=store(c); SharedPreferences.Editor edit=notes.edit();
        if (notes.contains("name:"+id(source))) edit.putString("name:"+id(target),notes.getString("name:"+id(source),""));
        if (notes.contains("favorite:"+id(source))) edit.putBoolean("favorite:"+id(target),notes.getBoolean("favorite:"+id(source),false));
        edit.apply();
    }
    static String filename(String value) {
        return VideoNames.filename(value);
    }
    static String newRecordingName(Context c) {
        String prefix=c.getSharedPreferences("recorder",Context.MODE_PRIVATE).getString("file_prefix","LumaRec");
        return filename(prefix+"_"+new SimpleDateFormat("yyyyMMdd_HHmmss_SSS",Locale.US).format(new Date()));
    }
}
