package com.lumarec.pro;

/** Exported titles cannot turn into path traversal, hidden files or malformed file names. */
public final class VideoNamesTest {
    private static int checks;
    public static void main(String[] args) {
        for (String name:new String[]{null,"../../video","..\\..\\video","/storage/emulated/0/secret","C:\\videos\\clip",
                ".hidden",".mp4","","   ","a\nb\u0000c","Vídeo de Chihuahua","clip.MP4","\"<>|:*?","...","🎥".repeat(200)}) {
            String file=VideoNames.filename(name);
            require(file.endsWith(".mp4"),"MP4 extension is retained");
            require(!file.startsWith(".") && !file.isBlank(),"Output is visible and nonempty");
            require(!file.contains("/") && !file.contains("\\"),"Output cannot select another directory");
            require(file.chars().noneMatch(Character::isISOControl),"Control characters are removed");
            require(file.codePointCount(0,file.length())<=100,"Output length is bounded");
            require(file.getBytes(java.nio.charset.StandardCharsets.UTF_8).length<=244,"Output fits document and filesystem byte limits");
            for (int i=0;i<file.length();i++) if (Character.isSurrogate(file.charAt(i))) {
                require(Character.isHighSurrogate(file.charAt(i)) && i+1<file.length() && Character.isLowSurrogate(file.charAt(i+1)),"Unicode is not split");
                i++;
            }
        }
        require("clip.mp4".equals(VideoNames.filename("clip.MP4")),"No duplicate MP4 extension");
        require("LumaRec.mp4".equals(VideoNames.filename(".mp4")),"An extension-only title receives a name");
        require("Vídeo de Chihuahua.mp4".equals(VideoNames.filename("Vídeo de Chihuahua")),"Readable local titles are preserved");
        System.out.println("PASS: "+checks+" export filename checks");
    }
    private static void require(boolean value,String message) { if (!value) throw new AssertionError(message); checks++; }
}
