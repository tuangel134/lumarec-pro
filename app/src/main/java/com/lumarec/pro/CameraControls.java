package com.lumarec.pro;

import android.content.SharedPreferences;
import android.graphics.Rect;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CaptureRequest;
import android.util.Range;
import java.util.ArrayList;
import java.util.List;

/** Applies only controls exposed by the selected camera; the recording profile stays intact. */
final class CameraControls {
    static final String[] LIVE_KEYS={"zoom","exposure","white_balance","focus","torch","ae_lock","awb_lock"};
    static java.util.Map<String,Object> snapshot(SharedPreferences p) {
        java.util.Map<String,Object> values=new java.util.HashMap<>();
        for (String key:LIVE_KEYS) values.put(key,p.getAll().get(key));
        return values;
    }
    static void restore(SharedPreferences p,java.util.Map<String,Object> values) {
        SharedPreferences.Editor editor=p.edit();
        for (String key:LIVE_KEYS) {
            editor.remove(key); Object value=values.get(key);
            if (value instanceof Boolean) editor.putBoolean(key,(Boolean)value);
            else if (value instanceof Float) editor.putFloat(key,(Float)value);
            else if (value instanceof Integer) editor.putInt(key,(Integer)value);
        }
        editor.apply();
    }
    static boolean has(int[] values,int value) { if (values!=null) for (int v:values) if (v==value) return true; return false; }
    static boolean supports(CameraCharacteristics c,CaptureRequest.Key<?> key) {
        List<CaptureRequest.Key<?>> keys=c.getAvailableCaptureRequestKeys(); return keys!=null && keys.contains(key);
    }
    static Range<Float> zoomRange(CameraCharacteristics c) {
        Range<Float> range=c.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE);
        if (range!=null && supports(c,CaptureRequest.CONTROL_ZOOM_RATIO)) return range;
        Float max=supports(c,CaptureRequest.SCALER_CROP_REGION) && c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)!=null ? c.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) : null;
        return new Range<>(1f,max==null ? 1f : Math.max(1f,max));
    }
    static Range<Integer> exposureRange(CameraCharacteristics c) {
        Range<Integer> range=c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE);
        return range==null || !supports(c,CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION) ? new Range<>(0,0) : range;
    }
    static boolean flash(CameraCharacteristics c) { return Boolean.TRUE.equals(c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE)) && supports(c,CaptureRequest.FLASH_MODE); }
    static boolean infinity(CameraCharacteristics c) {
        Float distance=c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE);
        int[] modes=c.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);
        return distance!=null && distance>0 && has(modes,CaptureRequest.CONTROL_AF_MODE_OFF)
                && (has(modes,CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO) || has(modes,CaptureRequest.CONTROL_AF_MODE_AUTO))
                && supports(c,CaptureRequest.LENS_FOCUS_DISTANCE) && supports(c,CaptureRequest.CONTROL_AF_MODE);
    }
    static List<Integer> whiteBalanceModes(CameraCharacteristics c) {
        List<Integer> result=new ArrayList<>();
        if (!supports(c,CaptureRequest.CONTROL_AWB_MODE)) { result.add(CaptureRequest.CONTROL_AWB_MODE_AUTO); return result; }
        int[] available=c.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES);
        for (int mode:new int[]{CaptureRequest.CONTROL_AWB_MODE_AUTO,CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT,
                CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT,CaptureRequest.CONTROL_AWB_MODE_SHADE,
                CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT,CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT})
            if (has(available,mode)) result.add(mode);
        if (result.isEmpty()) result.add(CaptureRequest.CONTROL_AWB_MODE_AUTO);
        return result;
    }
    static String whiteBalanceName(int mode) {
        if (mode==CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT) return "Luz de día";
        if (mode==CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT) return "Nublado";
        if (mode==CaptureRequest.CONTROL_AWB_MODE_SHADE) return "Sombra";
        if (mode==CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT) return "Incandescente";
        if (mode==CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT) return "Fluorescente";
        return "Automático";
    }
    static int customBitrate(SharedPreferences p,int fallback,boolean hevc,int width,int height,int fps) {
        int mbps=p.getInt("custom_mbps",0);
        if (mbps<=0) return fallback;
        int requested=Math.min(150,mbps)*1_000_000;
        android.media.MediaCodecInfo.VideoCapabilities codec=CameraCatalog.findEncoder(hevc,width,height,fps);
        if (codec!=null) return codec.getBitrateRange().clamp(requested);
        return Math.min(requested,hevc ? 60_000_000 : 80_000_000);
    }
    static void apply(CaptureRequest.Builder request,CameraCharacteristics c,SharedPreferences p) {
        Range<Float> ratios=zoomRange(c);
        float zoom=ratios.clamp(p.getFloat("zoom",1f));
        if (supports(c,CaptureRequest.CONTROL_ZOOM_RATIO) && c.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)!=null) {
            if (p.contains("zoom")) request.set(CaptureRequest.CONTROL_ZOOM_RATIO,zoom);
        } else if (p.contains("zoom") && supports(c,CaptureRequest.SCALER_CROP_REGION)) {
            Rect sensor=c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
            if (sensor!=null) {
                int width=Math.max(1,Math.round(sensor.width()/zoom)),height=Math.max(1,Math.round(sensor.height()/zoom));
                request.set(CaptureRequest.SCALER_CROP_REGION,new Rect(sensor.centerX()-width/2,sensor.centerY()-height/2,
                        sensor.centerX()+(width+1)/2,sensor.centerY()+(height+1)/2));
            }
        }
        if (p.contains("exposure") && supports(c,CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION))
            request.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION,exposureRange(c).clamp(p.getInt("exposure",0)));
        if (p.contains("white_balance") && supports(c,CaptureRequest.CONTROL_AWB_MODE)) {
            int mode=p.getInt("white_balance",CaptureRequest.CONTROL_AWB_MODE_AUTO);
            if (whiteBalanceModes(c).contains(mode)) request.set(CaptureRequest.CONTROL_AWB_MODE,mode);
        }
        if (p.contains("focus") && infinity(c)) {
            boolean far=p.getInt("focus",0)==1;
            if (far) { request.set(CaptureRequest.CONTROL_AF_MODE,CaptureRequest.CONTROL_AF_MODE_OFF); request.set(CaptureRequest.LENS_FOCUS_DISTANCE,0f); }
            else {
                int[] modes=c.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);
                request.set(CaptureRequest.CONTROL_AF_MODE,has(modes,CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                        ? CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO : CaptureRequest.CONTROL_AF_MODE_AUTO);
            }
        }
        if (p.contains("torch") && flash(c)) request.set(CaptureRequest.FLASH_MODE,p.getBoolean("torch",false) ? CaptureRequest.FLASH_MODE_TORCH : CaptureRequest.FLASH_MODE_OFF);
        if (p.contains("ae_lock") && Boolean.TRUE.equals(c.get(CameraCharacteristics.CONTROL_AE_LOCK_AVAILABLE)) && supports(c,CaptureRequest.CONTROL_AE_LOCK))
            request.set(CaptureRequest.CONTROL_AE_LOCK,p.getBoolean("ae_lock",false));
        if (p.contains("awb_lock") && Boolean.TRUE.equals(c.get(CameraCharacteristics.CONTROL_AWB_LOCK_AVAILABLE)) && supports(c,CaptureRequest.CONTROL_AWB_LOCK))
            request.set(CaptureRequest.CONTROL_AWB_LOCK,p.getBoolean("awb_lock",false));
    }
}
