package com.lumarec.pro;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Authenticated chunks allow playback and seeking without exporting a clear video. */
final class VaultFile {
    private static final int CHUNK=512*1024, HEADER=24, TAG=16;
    private static final byte[] MAGIC={'L','R','V','2'};
    private static final SecureRandom RANDOM=new SecureRandom();

    static void encrypt(InputStream source,long size,OutputStream output,byte[] key) throws Exception {
        if (size<=0 || size>(1L<<40)) throw new IOException("Tamaño de vídeo no válido.");
        byte[] nonce=new byte[8]; RANDOM.nextBytes(nonce);
        byte[] header=ByteBuffer.allocate(HEADER).put(MAGIC).put(nonce).putLong(size).putInt(CHUNK).array();
        output.write(header);
        byte[] plain=new byte[CHUNK]; long left=size; int index=0;
        try {
            while (left>0) {
                int length=(int)Math.min(CHUNK,left), offset=0;
                while (offset<length) {
                    int n=source.read(plain,offset,length-offset);
                    if (n<0) throw new IOException("El vídeo está incompleto.");
                    offset+=n;
                }
                Cipher cipher=cipher(Cipher.ENCRYPT_MODE,key,nonce,index,header);
                output.write(cipher.doFinal(plain,0,length));
                Arrays.fill(plain,(byte)0); left-=length; index++;
            }
            if (source.read()!=-1) throw new IOException("El tamaño del vídeo cambió.");
            output.flush();
        } finally { Arrays.fill(plain,(byte)0); }
    }

    private static Cipher cipher(int mode,byte[] key,byte[] nonce,int index,byte[] header) throws Exception {
        byte[] iv=ByteBuffer.allocate(12).put(nonce).putInt(index).array();
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,iv));
        cipher.updateAAD(header); cipher.updateAAD(ByteBuffer.allocate(4).putInt(index).array());
        return cipher;
    }

    static boolean isChunked(File file) throws IOException {
        try (RandomAccessFile input=new RandomAccessFile(file,"r")) {
            byte[] magic=new byte[4]; input.readFully(magic); return Arrays.equals(magic,MAGIC);
        }
    }

    static final class Reader implements Closeable {
        private final RandomAccessFile input;
        private final byte[] key,nonce,header;
        final long size;
        private byte[] cached;
        private long cachedIndex=-1;
        private boolean closed;

        Reader(File file,byte[] suppliedKey) throws IOException {
            if (suppliedKey==null || suppliedKey.length!=32) throw new IOException("La bóveda está bloqueada.");
            input=new RandomAccessFile(file,"r"); key=suppliedKey.clone(); header=new byte[HEADER];
            byte[] parsedNonce;
            long parsedSize;
            try {
                input.readFully(header); ByteBuffer bytes=ByteBuffer.wrap(header);
                byte[] magic=new byte[4]; bytes.get(magic); parsedNonce=new byte[8]; bytes.get(parsedNonce);
                parsedSize=bytes.getLong(); int chunk=bytes.getInt();
                if (!Arrays.equals(magic,MAGIC) || chunk!=CHUNK || parsedSize<=0 || parsedSize>(1L<<40))
                    throw new IOException("Formato de bóveda no válido.");
                long count=(parsedSize+CHUNK-1)/CHUNK;
                if (input.length()!=HEADER+parsedSize+count*TAG) throw new IOException("El vídeo cifrado está incompleto.");
            } catch (Exception error) {
                input.close(); Arrays.fill(key,(byte)0);
                throw error instanceof IOException ? (IOException)error : new IOException(error);
            }
            nonce=parsedNonce; size=parsedSize;
        }

        synchronized int readAt(long position,byte[] buffer,int offset,int length) throws IOException {
            if (closed) throw new IOException("Reproductor cerrado.");
            if (position<0 || offset<0 || length<0 || offset>buffer.length-length) throw new IOException("Lectura no válida.");
            if (length==0) return 0;
            if (position>=size) return -1;
            int count=(int)Math.min(length,size-position), copied=0;
            while (copied<count) {
                long index=position/CHUNK;
                if (cachedIndex!=index) load(index);
                int inside=(int)(position%CHUNK), n=Math.min(count-copied,cached.length-inside);
                System.arraycopy(cached,inside,buffer,offset+copied,n); copied+=n; position+=n;
            }
            return copied;
        }

        private void load(long index) throws IOException {
            if (cached!=null) { Arrays.fill(cached,(byte)0); cached=null; } cachedIndex=-1;
            int length=(int)Math.min(CHUNK,size-index*CHUNK);
            byte[] encrypted=new byte[length+TAG]; input.seek(HEADER+index*(CHUNK+TAG)); input.readFully(encrypted);
            try { cached=cipher(Cipher.DECRYPT_MODE,key,nonce,(int)index,header).doFinal(encrypted); cachedIndex=index; }
            catch (Exception error) { throw new IOException("El vídeo no superó la comprobación de integridad.",error); }
        }

        @Override public synchronized void close() throws IOException {
            if (closed) return; closed=true; Arrays.fill(key,(byte)0);
            if (cached!=null) Arrays.fill(cached,(byte)0); cached=null; input.close();
        }
    }
}
