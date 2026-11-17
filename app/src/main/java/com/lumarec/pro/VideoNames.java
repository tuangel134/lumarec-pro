package com.lumarec.pro;

import java.util.Locale;

/** File names stay within the chosen document directory and always retain the MP4 extension. */
final class VideoNames {
    static String filename(String value) {
        String name=(value==null ? "" : value).replaceAll("[\\p{Cntrl}\\\\/:*?\"<>|]","_").trim();
        if (name.toLowerCase(Locale.ROOT).endsWith(".mp4")) name=name.substring(0,name.length()-4);
        while (name.startsWith(".")) name=name.substring(1);
        if (name.isEmpty()) name="LumaRec";
        if (name.codePointCount(0,name.length())>96) name=name.substring(0,name.offsetByCodePoints(0,96));
        while (name.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>240)
            name=name.substring(0,name.offsetByCodePoints(0,name.codePointCount(0,name.length())-1));
        return name+".mp4";
    }
}
