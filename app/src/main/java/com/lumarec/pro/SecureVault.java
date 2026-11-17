package com.lumarec.pro;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.provider.Settings;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.KeyGenerator;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/** Private storage, device-bound credential wrapping and authenticated video encryption. */
final class SecureVault {
    private static final String PREFS="vault_security", SALT="salt", IV="iv", WRAPPED="wrapped", CHECK="check";
    private static final String PEPPER="lumarec_vault_device_v1";
    private static volatile byte[] masterKey;
    private static volatile boolean uiAuthorized;
    private static final byte[] LEGACY_MAGIC={'L','R','V','1'};
    private static final SecureRandom RANDOM=new SecureRandom();
    private SecureVault() { }
    private static SharedPreferences preferences(Context c) { return c.getSharedPreferences(PREFS,Context.MODE_PRIVATE); }
    static boolean configured(Context c) { return preferences(c).contains(WRAPPED); }
    static boolean knockMode(Context c) { return "knock".equals(preferences(c).getString("mode","password")); }
    static synchronized boolean unlocked() { return uiAuthorized && masterKey!=null; }
    static synchronized byte[] key() { return masterKey==null ? null : masterKey.clone(); }
    static synchronized boolean hasRecordingKey() { return masterKey!=null; }
    static synchronized void lock() {
        uiAuthorized=false; byte[] old=masterKey; masterKey=null; if (old!=null) Arrays.fill(old,(byte)0);
    }
    static synchronized void lockUi(boolean keepRecordingKey) { uiAuthorized=false; if (!keepRecordingKey) lock(); }
    private static synchronized void authorize(byte[] master) {
        if (!LumaRecApp.isForeground()) { Arrays.fill(master,(byte)0); return; }
        byte[] old=masterKey; masterKey=master; uiAuthorized=true; if (old!=null) Arrays.fill(old,(byte)0);
    }
    static void setup(Context c,char[] credential,boolean knock) throws Exception {
        if (configured(c)) throw new SecurityException("La bóveda ya está creada.");
        byte[] master=new byte[32]; RANDOM.nextBytes(master);
        try { storeCredential(c,credential,master,knock); authorize(master.clone()); }
        finally { Arrays.fill(master,(byte)0); }
    }
    static boolean unlock(Context c,char[] credential) {
        if (remainingMillis(c)>0 || !LumaRecApp.isForeground()) return false;
        byte[] wrapping=null, master=null;
        try {
            SharedPreferences p=preferences(c);
            byte[] salt=decode(p.getString(SALT,null)), iv=decode(p.getString(IV,null));
            byte[] wrapped=decode(p.getString(WRAPPED,null)), expected=decode(p.getString(CHECK,null));
            if (salt==null || iv==null || wrapped==null || expected==null) return false;
            wrapping=derive(c,credential,salt,p.getBoolean("device_bound",false),false);
            byte[] check=hmac(wrapping,"LumaRec key check v1".getBytes(StandardCharsets.UTF_8));
            if (!MessageDigest.isEqual(check,expected)) { failed(c); return false; }
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE,new SecretKeySpec(wrapping,"AES"),new GCMParameterSpec(128,iv));
            master=cipher.doFinal(wrapped);
            if (master.length!=32) throw new SecurityException();
            if (!p.getBoolean("device_bound",false)) {
                // Old vaults keep the same file key while upgrading the credential protection.
                try { storeCredential(c,credential,master,false); } catch (Exception ignored) { }
            }
            p.edit().remove("attempts").remove("lock_elapsed").remove("lock_wall").remove("lock_duration").apply();
            authorize(master.clone()); return unlocked();
        } catch (Exception ignored) { failed(c); return false; }
        finally { if (wrapping!=null) Arrays.fill(wrapping,(byte)0); if (master!=null) Arrays.fill(master,(byte)0); }
    }
    static void setCredential(Context c,char[] credential,boolean knock) throws Exception {
        if (!unlocked()) throw new SecurityException("Desbloquea la bóveda.");
        byte[] master=key();
        try { storeCredential(c,credential,master,knock); }
        finally { if (master!=null) Arrays.fill(master,(byte)0); }
    }
    private static void storeCredential(Context c,char[] credential,byte[] master,boolean knock) throws Exception {
        if (knock) {
            if (credential.length!=4) throw new IllegalArgumentException("El código tiene cuatro toques.");
            for (char digit:credential) if (digit<'0' || digit>'3') throw new IllegalArgumentException();
        } else if (credential.length<6) throw new IllegalArgumentException("Usa al menos seis caracteres.");
        byte[] salt=new byte[16], iv=new byte[12]; RANDOM.nextBytes(salt); RANDOM.nextBytes(iv);
        byte[] wrapping=derive(c,credential,salt,true,true);
        try {
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(wrapping,"AES"),new GCMParameterSpec(128,iv));
            byte[] wrapped=cipher.doFinal(master);
            boolean ok=preferences(c).edit().putString(SALT,encode(salt)).putString(IV,encode(iv)).putString(WRAPPED,encode(wrapped))
                    .putString(CHECK,encode(hmac(wrapping,"LumaRec key check v1".getBytes(StandardCharsets.UTF_8))))
                    .putBoolean("device_bound",true).putString("mode",knock ? "knock" : "password")
                    .remove("attempts").remove("lock_elapsed").remove("lock_wall").remove("lock_duration").commit();
            if (!ok) throw new IllegalStateException("No se pudo guardar el acceso a la bóveda.");
        } finally { Arrays.fill(wrapping,(byte)0); }
    }
    static synchronized long remainingMillis(Context c) {
        SharedPreferences p=preferences(c); long until=p.getLong("lock_elapsed",0);
        if (until==0) return 0;
        int boot=Settings.Global.getInt(c.getContentResolver(),Settings.Global.BOOT_COUNT,-1);
        if (boot!=p.getInt("lock_boot",-1)) {
            long duration=p.getLong("lock_duration",30_000);
            until=SystemClock.elapsedRealtime()+duration;
            p.edit().putInt("lock_boot",boot).putLong("lock_elapsed",until).commit();
        }
        return Math.max(0,until-SystemClock.elapsedRealtime());
    }
    private static synchronized void failed(Context c) {
        SharedPreferences p=preferences(c); int attempts=Math.min(100,p.getInt("attempts",0)+1);
        SharedPreferences.Editor edit=p.edit().putInt("attempts",attempts);
        if (attempts>=5) {
            long delay=Math.min(30L*60*1000,30_000L<<Math.min(6,attempts-5));
            edit.putLong("lock_elapsed",SystemClock.elapsedRealtime()+delay).putLong("lock_duration",delay)
                    .putInt("lock_boot",Settings.Global.getInt(c.getContentResolver(),Settings.Global.BOOT_COUNT,-1));
        }
        edit.commit();
    }
    static void disable(Context c) throws Exception {
        File[] roots={directory(c),legacyDirectory(c)};
        for (File root:roots) if (root!=null && root.exists()) {
            File[] remaining=root.listFiles((dir,name)->name.endsWith(".lrv"));
            if (remaining==null || remaining.length>0) throw new IllegalStateException("Todavía quedan vídeos cifrados.");
        }
        if (!unlocked() || !preferences(c).edit().clear().commit()) throw new SecurityException("La bóveda está bloqueada.");
        QuickVault.clear(c); lock(); KeyStore store=KeyStore.getInstance("AndroidKeyStore"); store.load(null); store.deleteEntry(PEPPER);
    }
    private static byte[] derive(Context context,char[] credential,byte[] salt,boolean bound,boolean create) throws Exception {
        char[] password=credential;
        byte[] bytes=null, peppered=null;
        try {
            if (bound) {
                KeyStore store=KeyStore.getInstance("AndroidKeyStore"); store.load(null);
                if (!store.containsAlias(PEPPER)) {
                    if (!create) throw new SecurityException("No se encuentra la clave de este dispositivo.");
                    KeyGenerator generator=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256,"AndroidKeyStore");
                    generator.init(new KeyGenParameterSpec.Builder(PEPPER,KeyProperties.PURPOSE_SIGN)
                            .setKeySize(256).setDigests(KeyProperties.DIGEST_SHA256).setUnlockedDeviceRequired(true).build()); generator.generateKey();
                }
                // A non-exportable device key prevents offline enumeration of the 256 tap codes.
                Mac mac=Mac.getInstance("HmacSHA256"); mac.init((SecretKey)store.getKey(PEPPER,null));
                bytes=new byte[credential.length*2];
                for (int i=0;i<credential.length;i++) { bytes[2*i]=(byte)(credential[i]>>>8); bytes[2*i+1]=(byte)credential[i]; }
                mac.update("LumaRec credential v2".getBytes(StandardCharsets.UTF_8)); peppered=mac.doFinal(bytes);
                password=new char[peppered.length*2]; char[] digits="0123456789abcdef".toCharArray();
                for (int i=0;i<peppered.length;i++) { password[2*i]=digits[(peppered[i]&255)>>>4]; password[2*i+1]=digits[peppered[i]&15]; }
            }
            PBEKeySpec spec=new PBEKeySpec(password,salt,310_000,256);
            try { return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded(); }
            finally { spec.clearPassword(); }
        } finally { if (password!=credential) Arrays.fill(password,'\0'); if (bytes!=null) Arrays.fill(bytes,(byte)0); if (peppered!=null) Arrays.fill(peppered,(byte)0); }
    }
    private static byte[] hmac(byte[] key,byte[] message) throws Exception {
        Mac mac=Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(key,"HmacSHA256")); return mac.doFinal(message);
    }
    static File directory(Context c) throws java.io.IOException {
        File dir=new File(c.getFilesDir(),"vault");
        if (!dir.exists() && !dir.mkdirs()) throw new java.io.IOException("No se pudo crear la bóveda.");
        return dir;
    }
    static File legacyDirectory(Context c) { return c.getExternalFilesDir("LumaRecVault"); }
    static File encryptedFile(Context c,String id) throws Exception {
        if (id==null || !id.matches("[0-9a-fA-F-]{36}")) throw new SecurityException("Vídeo no válido.");
        File file=new File(directory(c),id+".lrv");
        if (file.isFile()) return file;
        File legacy=legacyDirectory(c);
        if (legacy!=null && new File(legacy,id+".lrv").isFile()) return new File(legacy,id+".lrv");
        throw new java.io.FileNotFoundException("No se encuentra el vídeo.");
    }
    static void migrate(Context c) throws Exception {
        File legacy=legacyDirectory(c); if (legacy==null || !legacy.isDirectory()) return;
        File[] videos=legacy.listFiles((dir,name)->name.matches("[0-9a-fA-F-]{36}\\.lrv")); if (videos==null) return;
        File target=directory(c);
        for (File old:videos) {
            String id=old.getName().substring(0,36); File meta=new File(legacy,id+".meta");
            File moved=new File(target,old.getName()), movedMeta=new File(target,meta.getName());
            copyVerified(old,moved); if (meta.isFile()) copyVerified(meta,movedMeta);
            if (moved.isFile() && (!meta.isFile() || movedMeta.isFile())) { old.delete(); meta.delete(); }
        }
    }
    private static void copyVerified(File source,File target) throws Exception {
        if (target.isFile() && sameFile(source,target)) return;
        File temp=new File(target.getParentFile(),target.getName()+".migrate");
        try (FileInputStream input=new FileInputStream(source); FileOutputStream output=new FileOutputStream(temp)) {
            transfer(input,output); output.getFD().sync();
        } catch (Exception error) { temp.delete(); throw error; }
        if (!sameFile(source,temp)) { temp.delete(); throw new java.io.IOException("No se pudo verificar la migración."); }
        Files.move(temp.toPath(),target.toPath(),StandardCopyOption.REPLACE_EXISTING);
    }
    private static boolean sameFile(File a,File b) throws Exception {
        if (a.length()!=b.length()) return false;
        return MessageDigest.isEqual(digest(a),digest(b));
    }
    private static byte[] digest(File file) throws Exception {
        MessageDigest hash=MessageDigest.getInstance("SHA-256");
        try (InputStream input=new FileInputStream(file)) { byte[] buffer=new byte[256*1024]; int n; while ((n=input.read(buffer))!=-1) hash.update(buffer,0,n); }
        return hash.digest();
    }
    static String save(Context context,File clear,long duration,byte[] key) throws Exception {
        try (InputStream input=new FileInputStream(clear)) { return save(context,input,clear.length(),duration,key); }
    }
    static String saveQuickCapture(Context context,File clear,long duration,byte[] key,byte[] sealedKey) throws Exception {
        if (sealedKey==null || sealedKey.length!=384) throw new SecurityException("Clave de captura no válida.");
        try (InputStream input=new FileInputStream(clear)) { return save(context,input,clear.length(),duration,key,sealedKey); }
    }
    static String save(Context context,InputStream clear,long size,long duration,byte[] key) throws Exception {
        return save(context,clear,size,duration,key,null);
    }
    private static String save(Context context,InputStream clear,long size,long duration,byte[] key,byte[] sealedKey) throws Exception {
        if (key==null) throw new SecurityException("Desbloquea la bóveda para guardar el vídeo.");
        String id=java.util.UUID.randomUUID().toString(); File dir=directory(context);
        File target=new File(dir,id+".lrv"),tmp=new File(dir,id+".tmp");
        File envelope=new File(dir,id+".key"),metadata=new File(dir,id+".meta");
        try {
            try (FileOutputStream raw=new FileOutputStream(tmp)) { VaultFile.encrypt(clear,size,raw,key); raw.getFD().sync(); }
            if (sealedKey!=null) try (FileOutputStream wrapped=new FileOutputStream(envelope)) { wrapped.write(sealedKey); wrapped.getFD().sync(); }
            try (FileOutputStream raw=new FileOutputStream(metadata); java.io.DataOutputStream meta=new java.io.DataOutputStream(raw)) {
                meta.writeLong(duration); meta.writeLong(System.currentTimeMillis()); meta.writeLong(size); meta.flush(); raw.getFD().sync();
            }
            Files.move(tmp.toPath(),target.toPath(),StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception error) { tmp.delete(); target.delete(); envelope.delete(); metadata.delete(); throw error; }
        return id;
    }
    static void decryptTo(Context context,String id,OutputStream output,byte[] key) throws Exception {
        if (key==null) throw new SecurityException("La bóveda está bloqueada.");
        File encrypted=encryptedFile(context,id);
        byte[] videoKey=QuickVault.videoKey(context,encrypted,key);
        try { decryptFileTo(encrypted,output,videoKey); } finally { Arrays.fill(videoKey,(byte)0); }
    }
    private static void decryptFileTo(File encrypted,OutputStream output,byte[] key) throws Exception {
        if (VaultFile.isChunked(encrypted)) {
            try (VaultFile.Reader reader=new VaultFile.Reader(encrypted,key)) {
                byte[] buffer=new byte[256*1024]; long at=0; int n;
                try { while ((n=reader.readAt(at,buffer,0,buffer.length))!=-1) { if (Thread.currentThread().isInterrupted()) throw new java.io.IOException("Operación cancelada."); output.write(buffer,0,n); at+=n; } output.flush(); }
                finally { Arrays.fill(buffer,(byte)0); }
            }
        } else {
            try (FileInputStream input=new FileInputStream(encrypted)) {
                byte[] magic=readExact(input,4),iv=readExact(input,12);
                if (!Arrays.equals(magic,LEGACY_MAGIC)) throw new java.io.IOException("Archivo de bóveda no válido.");
                Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.DECRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,iv));
                try (CipherInputStream decrypted=new CipherInputStream(input,cipher)) { transfer(decrypted,output); }
            }
        }
    }
    // Older recordings use whole-file GCM. Their playback descriptor has no filesystem path.
    static ParcelFileDescriptor legacyPlayback(Context context,String id,byte[] key) throws Exception {
        File temp=File.createTempFile("vault-playback-",".tmp",context.getCacheDir());
        ParcelFileDescriptor fd=ParcelFileDescriptor.open(temp,ParcelFileDescriptor.MODE_READ_WRITE);
        if (!temp.delete()) { fd.close(); throw new SecurityException("No se pudo proteger la reproducción."); }
        try (OutputStream output=new ParcelFileDescriptor.AutoCloseOutputStream(ParcelFileDescriptor.dup(fd.getFileDescriptor()))) {
            decryptTo(context,id,output,key);
        } catch (Exception error) { fd.close(); throw error; }
        return fd;
    }
    private static byte[] readExact(InputStream input,int length) throws java.io.IOException {
        byte[] bytes=new byte[length]; int at=0;
        while (at<length) { int count=input.read(bytes,at,length-at); if (count<0) throw new java.io.IOException("Archivo de bóveda incompleto."); at+=count; }
        return bytes;
    }
    static void delete(Context c,String id) throws Exception {
        File file=encryptedFile(c,id);
        if (!file.delete()) throw new java.io.IOException("No se pudo borrar el vídeo.");
        File legacy=legacyDirectory(c); new File(directory(c),id+".meta").delete(); new File(directory(c),id+".key").delete();
        if (legacy!=null) { new File(legacy,id+".lrv").delete(); new File(legacy,id+".meta").delete(); new File(legacy,id+".key").delete(); }
    }
    private static void transfer(InputStream input,OutputStream output) throws java.io.IOException {
        byte[] buffer=new byte[256*1024];
        try { int n; while ((n=input.read(buffer))!=-1) { if (Thread.currentThread().isInterrupted()) throw new java.io.IOException("Operación cancelada."); output.write(buffer,0,n); } output.flush(); }
        finally { Arrays.fill(buffer,(byte)0); }
    }
    private static String encode(byte[] value) { return value==null ? null : Base64.encodeToString(value,Base64.NO_WRAP); }
    private static byte[] decode(String value) { return value==null ? null : Base64.decode(value,Base64.NO_WRAP); }
}
