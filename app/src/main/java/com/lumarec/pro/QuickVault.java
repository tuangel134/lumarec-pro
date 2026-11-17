package com.lumarec.pro;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;
import java.io.File;
import java.nio.file.Files;
import java.security.KeyPair;
import java.util.Arrays;

final class QuickVault {
    private static SharedPreferences prefs(Context c) { return c.getSharedPreferences("quick_capture_keys",Context.MODE_PRIVATE); }
    static boolean ready(Context c) { SharedPreferences p=prefs(c); return SecureVault.configured(c) && p.contains("public") && p.contains("private"); }
    static synchronized void ensure(Context c,byte[] master) throws Exception {
        if (!SecureVault.configured(c) || !SecureVault.unlocked() || master==null) throw new SecurityException("Desbloquea la bóveda para preparar el widget.");
        SharedPreferences p=prefs(c);
        if (ready(c)) return;
        if (p.contains("public") || p.contains("private")) throw new SecurityException("No se pudo verificar la clave del widget.");
        KeyPair pair=QuickSeal.newPair(); byte[] privateBytes=pair.getPrivate().getEncoded();
        try {
            byte[] publicBytes=pair.getPublic().getEncoded(), protectedPrivate=QuickSeal.protectPrivate(publicBytes,privateBytes,master);
            if (!p.edit().putString("public",encode(publicBytes)).putString("private",encode(protectedPrivate)).commit())
                throw new java.io.IOException("No se pudo preparar el guardado privado del widget.");
        } finally { Arrays.fill(privateBytes,(byte)0); }
    }
    static byte[] seal(Context c,byte[] key) throws Exception {
        if (!ready(c)) throw new SecurityException("Abre Ajustes y prepara el widget para la bóveda.");
        return QuickSeal.seal(decode(prefs(c).getString("public",null)),key);
    }
    static byte[] videoKey(Context c,File encrypted,byte[] master) throws Exception {
        if (master==null) throw new SecurityException("La bóveda está bloqueada.");
        File envelope=new File(encrypted.getParentFile(),encrypted.getName().replace(".lrv",".key"));
        if (!envelope.exists()) return master.clone();
        if (!envelope.isFile() || envelope.length()!=384 || !ready(c)) throw new SecurityException("Clave de vídeo incompleta.");
        SharedPreferences p=prefs(c);
        return QuickSeal.open(QuickSeal.openPrivate(decode(p.getString("public",null)),decode(p.getString("private",null)),master),Files.readAllBytes(envelope.toPath()));
    }
    static void clear(Context c) { prefs(c).edit().clear().commit(); }
    private static String encode(byte[] value) { return Base64.encodeToString(value,Base64.NO_WRAP); }
    private static byte[] decode(String value) { if (value==null) throw new SecurityException("Clave de captura no disponible."); return Base64.decode(value,Base64.NO_WRAP); }
}
