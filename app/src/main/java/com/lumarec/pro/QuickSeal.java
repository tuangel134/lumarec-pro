package com.lumarec.pro;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import javax.crypto.spec.SecretKeySpec;

/** Public-key capture can seal a new video while the vault's reading key remains locked. */
final class QuickSeal {
    private static final SecureRandom RANDOM=new SecureRandom();
    private static final OAEPParameterSpec OAEP=new OAEPParameterSpec("SHA-256","MGF1",MGF1ParameterSpec.SHA256,PSource.PSpecified.DEFAULT);
    private static final byte[] DOMAIN="LumaRec quick capture key v1".getBytes(StandardCharsets.UTF_8);
    static KeyPair newPair() throws Exception {
        KeyPairGenerator generator=KeyPairGenerator.getInstance("RSA"); generator.initialize(3072,RANDOM); return generator.generateKeyPair();
    }
    static byte[] protectPrivate(byte[] publicBytes,byte[] privateBytes,byte[] master) throws Exception {
        byte[] iv=new byte[12]; RANDOM.nextBytes(iv);
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(master,"AES"),new GCMParameterSpec(128,iv));
        cipher.updateAAD(DOMAIN); cipher.updateAAD(publicBytes);
        byte[] encrypted=cipher.doFinal(privateBytes), result=new byte[iv.length+encrypted.length];
        System.arraycopy(iv,0,result,0,iv.length); System.arraycopy(encrypted,0,result,iv.length,encrypted.length); return result;
    }
    static PrivateKey openPrivate(byte[] publicBytes,byte[] protectedPrivate,byte[] master) throws Exception {
        if (protectedPrivate.length<28) throw new SecurityException("Clave de captura no válida.");
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE,new SecretKeySpec(master,"AES"),new GCMParameterSpec(128,Arrays.copyOf(protectedPrivate,12)));
        cipher.updateAAD(DOMAIN); cipher.updateAAD(publicBytes);
        byte[] clear=cipher.doFinal(protectedPrivate,12,protectedPrivate.length-12);
        try { return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(clear)); }
        finally { Arrays.fill(clear,(byte)0); }
    }
    static byte[] seal(byte[] publicBytes,byte[] recordingKey) throws Exception {
        if (recordingKey.length!=32) throw new SecurityException("Clave de vídeo no válida.");
        PublicKey publicKey=KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(publicBytes));
        Cipher cipher=Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding"); cipher.init(Cipher.ENCRYPT_MODE,publicKey,OAEP);
        return cipher.doFinal(recordingKey);
    }
    static byte[] open(PrivateKey privateKey,byte[] sealed) throws Exception {
        Cipher cipher=Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding"); cipher.init(Cipher.DECRYPT_MODE,privateKey,OAEP);
        byte[] key=cipher.doFinal(sealed);
        if (key.length!=32) { Arrays.fill(key,(byte)0); throw new SecurityException("Clave de vídeo no válida."); }
        return key;
    }
    static byte[] randomKey() { byte[] key=new byte[32]; RANDOM.nextBytes(key); return key; }
}
