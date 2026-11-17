package com.lumarec.pro;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Random;

/** Behavioral checks for encrypted playback, seeking and tamper detection. */
public final class VaultFileTest {
    private static final Random random=new Random(773123);
    private static int checks;
    public static void main(String[] args) throws Exception {
        byte[] key=new byte[32]; random.nextBytes(key);
        for (int size:new int[]{1,4096,524287,524288,524289,1572991,7_123_456}) {
            byte[] original=new byte[size]; random.nextBytes(original);
            byte[] encoded=encode(original,key);
            File file=Files.createTempFile("vault-roundtrip-",".lrv").toFile();
            try {
                Files.write(file.toPath(),encoded);
                require(VaultFile.isChunked(file),"Chunked recording recognized");
                try (VaultFile.Reader reader=new VaultFile.Reader(file,key)) {
                    require(reader.size==size,"Original size preserved");
                    byte[] output=new byte[size]; int total=reader.readAt(0,output,0,size);
                    require(total==size && Arrays.equals(output,original),"Entire recording recovered");
                    require(reader.readAt(size,new byte[1],0,1)==-1,"EOF");
                    require(reader.readAt(size,new byte[0],0,0)==0,"Empty read");
                    for (int attempt=0;attempt<50;attempt++) {
                        int position=random.nextInt(size), length=Math.min(800_000,size-position);
                        byte[] segment=new byte[length+12]; Arrays.fill(segment,(byte)99);
                        int n=reader.readAt(position,segment,5,length);
                        require(n==length && Arrays.equals(Arrays.copyOfRange(segment,5,5+n),Arrays.copyOfRange(original,position,position+n)),"Random seeking recovers exact bytes");
                        require(segment[0]==99 && segment[segment.length-1]==99,"Read bounds respected");
                    }
                }
                VaultFile.Reader closed=new VaultFile.Reader(file,key); closed.close();
                expectFailure(()->closed.readAt(0,new byte[1],0,1),"Closed player refuses reads");
                byte[] wrong=key.clone(); wrong[0]^=1;
                expectFailure(()->{ try (VaultFile.Reader r=new VaultFile.Reader(file,wrong)) { r.readAt(0,new byte[1],0,1); } },"Wrong key refused");
                for (int at:new int[]{4,12,encoded.length-1}) {
                    byte[] corrupt=encoded.clone(); corrupt[at]^=1; Files.write(file.toPath(),corrupt);
                    expectFailure(()->{ try (VaultFile.Reader r=new VaultFile.Reader(file,key)) { byte[] all=new byte[size]; r.readAt(0,all,0,all.length); } },"Modified file refused");
                }
                Files.write(file.toPath(),Arrays.copyOf(encoded,encoded.length-1));
                expectFailure(()->new VaultFile.Reader(file,key),"Truncation refused");
                byte[] extended=Arrays.copyOf(encoded,encoded.length+1); Files.write(file.toPath(),extended);
                expectFailure(()->new VaultFile.Reader(file,key),"Appended bytes refused");
            } finally { file.delete(); Arrays.fill(original,(byte)0); }
        }
        byte[] sample=new byte[1_048_576]; random.nextBytes(sample);
        require(!Arrays.equals(encode(sample,key),encode(sample,key)),"Different nonces for repeated recordings");
        expectFailure(()->VaultFile.encrypt(new ByteArrayInputStream(new byte[7]),8,new ByteArrayOutputStream(),key),"Short input refused");
        expectFailure(()->VaultFile.encrypt(new ByteArrayInputStream(new byte[9]),8,new ByteArrayOutputStream(),key),"Long input refused");
        expectFailure(()->VaultFile.encrypt(new ByteArrayInputStream(new byte[0]),0,new ByteArrayOutputStream(),key),"Empty recording refused");
        System.out.println("PASS: "+checks+" checks covering round trips, random seeking, boundaries, key rejection and corruption.");
    }
    private static byte[] encode(byte[] original,byte[] key) throws Exception {
        ByteArrayOutputStream output=new ByteArrayOutputStream(); VaultFile.encrypt(new ByteArrayInputStream(original),original.length,output,key); return output.toByteArray();
    }
    private interface Operation { void run() throws Exception; }
    private static void expectFailure(Operation op,String label) throws Exception {
        try { op.run(); throw new AssertionError(label); } catch (IOException expected) { checks++; }
    }
    private static void require(boolean value,String label) { if (!value) throw new AssertionError(label); checks++; }
}
