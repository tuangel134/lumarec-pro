package com.lumarec.pro;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.file.Files;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.util.Arrays;

/** Capturing uses only the public key; retrieving the video requires the unlocked vault key. */
public final class QuickSealTest {
    private static int checks;
    public static void main(String[] args) throws Exception {
        KeyPair pair=QuickSeal.newPair(), another=QuickSeal.newPair();
        byte[] publicBytes=pair.getPublic().getEncoded(), master=QuickSeal.randomKey(), wrongMaster=QuickSeal.randomKey();
        byte[] protectedPrivate=QuickSeal.protectPrivate(publicBytes,pair.getPrivate().getEncoded(),master);
        require(!Arrays.equals(protectedPrivate,pair.getPrivate().getEncoded()),"Private key is encrypted");
        expectFailure(()->QuickSeal.openPrivate(publicBytes,protectedPrivate,wrongMaster),"Wrong vault credential cannot open the capture key");
        expectFailure(()->QuickSeal.openPrivate(another.getPublic().getEncoded(),protectedPrivate,master),"Public-key substitution is rejected");
        byte[] changed=protectedPrivate.clone(); changed[changed.length-1]^=1;
        expectFailure(()->QuickSeal.openPrivate(publicBytes,changed,master),"Modified private-key envelope is rejected");
        PrivateKey unlocked=QuickSeal.openPrivate(publicBytes,protectedPrivate,master);
        for (int length:new int[]{129,524289,1_048_123}) {
            byte[] content=new byte[length]; new java.util.Random(length).nextBytes(content);
            byte[] captureKey=QuickSeal.randomKey();
            byte[] sealed=QuickSeal.seal(publicBytes,captureKey), repeated=QuickSeal.seal(publicBytes,captureKey);
            require(!Arrays.equals(sealed,repeated),"OAEP uses fresh randomness");
            byte[] mutated=sealed.clone(); mutated[7]^=1;
            expectFailure(()->QuickSeal.open(unlocked,mutated),"Modified video-key envelope is rejected");
            expectFailure(()->QuickSeal.open(another.getPrivate(),sealed),"Another vault cannot retrieve the capture");
            byte[] recovered=QuickSeal.open(unlocked,sealed);
            require(Arrays.equals(recovered,captureKey),"Only the unlocked vault retrieves the recording key");
            ByteArrayOutputStream bytes=new ByteArrayOutputStream(); VaultFile.encrypt(new ByteArrayInputStream(content),content.length,bytes,captureKey);
            File encrypted=Files.createTempFile("quick-capture-",".lrv").toFile();
            try {
                Files.write(encrypted.toPath(),bytes.toByteArray());
                expectFailure(()->{ try (VaultFile.Reader r=new VaultFile.Reader(encrypted,master)) { r.readAt(0,new byte[1],0,1); } },"The main key alone cannot play a quick capture");
                try (VaultFile.Reader reader=new VaultFile.Reader(encrypted,recovered)) {
                    byte[] result=new byte[length]; require(reader.readAt(0,result,0,result.length)==length && Arrays.equals(content,result),"Encrypted widget capture can be played after unlock");
                }
            } finally { encrypted.delete(); Arrays.fill(captureKey,(byte)0); Arrays.fill(recovered,(byte)0); }
        }
        System.out.println("PASS: "+checks+" checks for locked-vault capture, unlock, envelope tampering, different vaults and playback.");
    }
    private interface Operation { void run() throws Exception; }
    private static void expectFailure(Operation action,String label) throws Exception {
        try { action.run(); throw new AssertionError(label); } catch (java.security.GeneralSecurityException|java.io.IOException expected) { checks++; }
    }
    private static void require(boolean value,String label) { if (!value) throw new AssertionError(label); checks++; }
}
