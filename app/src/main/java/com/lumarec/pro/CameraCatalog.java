package com.lumarec.pro;

import android.content.Context;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.CamcorderProfile;
import android.media.EncoderProfiles;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaRecorder;
import android.os.Build;
import android.util.Range;
import android.util.Size;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

final class CameraCatalog {
    static final class Camera {
        String id, label;
        int sensorOrientation;
        boolean front, stabilization;
        CameraCharacteristics characteristics;
        final List<Quality> qualities = new ArrayList<>();
        @Override public String toString() { return label; }
    }
    static final class Quality {
        int width, height, bitrate, profileFrameRate;
        Quality(int w, int h, int b) { width=w; height=h; bitrate=b; }
        @Override public String toString() {
            String title = height >= 2160 ? "4K UHD" : height >= 1080 ? "Full HD" : height >= 720 ? "HD" : "Estándar";
            return title + " · " + width + " × " + height;
        }
    }
    static List<Camera> scan(Context context) throws Exception {
        List<Camera> cameras = new ArrayList<>();
        CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        for (String id : manager.getCameraIdList()) {
            CameraCharacteristics c = manager.getCameraCharacteristics(id);
            StreamConfigurationMap streams = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (streams == null) continue;
            Size[] sizes = streams.getOutputSizes(MediaRecorder.class);
            if (sizes == null) continue;
            Camera camera = new Camera(); camera.id=id; camera.characteristics=c;
            Integer facing=c.get(CameraCharacteristics.LENS_FACING);
            camera.front=facing!=null && facing==CameraCharacteristics.LENS_FACING_FRONT;
            camera.label=camera.front ? "Frontal" : facing!=null && facing==CameraCharacteristics.LENS_FACING_BACK ? "Trasera" : "Externa";
            Integer sensor=c.get(CameraCharacteristics.SENSOR_ORIENTATION);
            camera.sensorOrientation=sensor==null ? 0 : sensor;
            int[] stab=c.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES);
            if (stab!=null) for (int mode:stab) if (mode==CameraCharacteristics.CONTROL_VIDEO_STABILIZATION_MODE_ON) camera.stabilization=true;
            for (Size size:sizes) {
                int w=size.getWidth(), h=size.getHeight();
                // Standard video output sizes, never photograph sensor dimensions.
                if (w < h || w>3840 || h>2160 || w<640 || h<480) continue;
                if (!encoderSupports(false,w,h,30) && !encoderSupports(false,w,h,24)
                        && !encoderSupports(false,w,h,15) && !encoderSupports(false,w,h,60)) continue;
                int rate=profileBitrate(id,w,h);
                int profileFps=profileFrameRate(id,w,h);
                if (rate==0) rate=(int)Math.min(80_000_000L, Math.max(4_000_000L,(long)w*h*6));
                Quality quality=new Quality(w,h,rate); quality.profileFrameRate=profileFps; camera.qualities.add(quality);
            }
            camera.qualities.sort(Comparator.comparingLong((Quality q)->(long)q.width*q.height).reversed());
            if (!camera.qualities.isEmpty()) cameras.add(camera);
        }
        cameras.sort(Comparator.comparing(camera->camera.front));
        // Label additional lenses unambiguously without assuming their physical focal length.
        for (Camera camera:cameras) {
            long count=cameras.stream().filter(other->other.label.equals(camera.label)).count();
            if (count>1) camera.label += " · cámara " + camera.id;
        }
        return cameras;
    }
    private static int profileFrameRate(String id,int width,int height) {
        int best=0;
        if (Build.VERSION.SDK_INT>=31) {
            int[] levels={CamcorderProfile.QUALITY_2160P,CamcorderProfile.QUALITY_1080P,CamcorderProfile.QUALITY_720P,CamcorderProfile.QUALITY_480P};
            for (int level:levels) try {
                EncoderProfiles profiles=CamcorderProfile.getAll(id,level);
                if (profiles!=null) for (EncoderProfiles.VideoProfile profile:profiles.getVideoProfiles())
                    if (profile.getWidth()==width && profile.getHeight()==height) best=Math.max(best,profile.getFrameRate());
            } catch (Exception ignored) { }
        }
        return best;
    }
    private static int profileBitrate(String id,int width,int height) {
        int[] levels={CamcorderProfile.QUALITY_2160P,CamcorderProfile.QUALITY_1080P,CamcorderProfile.QUALITY_720P,CamcorderProfile.QUALITY_480P};
        for (int level:levels) try {
            if (Build.VERSION.SDK_INT>=31) {
                EncoderProfiles profiles=CamcorderProfile.getAll(id,level);
                if (profiles!=null) for (EncoderProfiles.VideoProfile p:profiles.getVideoProfiles())
                    if (p.getWidth()==width && p.getHeight()==height && p.getCodec()==MediaRecorder.VideoEncoder.H264) return p.getBitrate();
            } else {
                int cameraId=Integer.parseInt(id);
                if (CamcorderProfile.hasProfile(cameraId,level)) {
                    CamcorderProfile p=CamcorderProfile.get(cameraId,level);
                    if (p.videoFrameWidth==width && p.videoFrameHeight==height) return p.videoBitRate;
                }
            }
        } catch (Exception ignored) { }
        return 0;
    }
    static List<Integer> frameRates(Camera camera, Quality quality, boolean hevc) {
        List<Integer> result=new ArrayList<>();
        Range<Integer>[] ranges=camera.characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
        StreamConfigurationMap streams=camera.characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        long min=0;
        try { min=streams.getOutputMinFrameDuration(MediaRecorder.class,new Size(quality.width,quality.height)); } catch (Exception ignored) { }
        for (int fps:new int[]{15,24,30,60}) {
            boolean supported=false;
            if (ranges!=null) for (Range<Integer> r:ranges) if (r.contains(fps)) supported=true;
            boolean sensorCanReachRate=min==0 || 1_000_000_000d/min+0.5>=fps;
            boolean vendorProfile=quality.profileFrameRate>=fps;
            // Some Huawei models keep the 4K60 Camera2 profile inside their stock app,
            // even though the public AE range omits it. Let their public 4K H.264
            // MediaRecorder path try the known device profile so it can be verified on device.
            boolean huawei4k60=fps==60 && !camera.front
                    && "HUAWEI".equalsIgnoreCase(Build.MANUFACTURER) && quality.height>=2160;
            boolean codecRate=encoderSupports(hevc,quality.width,quality.height,fps);
            if ((supported && sensorCanReachRate || vendorProfile || fps==60 && codecRate || huawei4k60)
                    && (codecRate || huawei4k60)) result.add(fps);
        }
        return result;
    }
    static Range<Integer> aeRange(Camera camera,int fps) {
        Range<Integer>[] ranges=camera.characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
        Range<Integer> best=null;
        if (ranges!=null) for (Range<Integer> range:ranges) {
            if (range.contains(fps) && (best==null || Math.abs(range.getUpper()-fps)<Math.abs(best.getUpper()-fps)
                    || (range.getUpper().equals(best.getUpper()) && range.getLower()>best.getLower()))) best=range;
        }
        return best;
    }
    static Range<Integer> highSpeedRange(Camera camera,Quality quality,int fps) {
        StreamConfigurationMap streams=camera.characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        if (streams==null) return null;
        try {
            Size requested=new Size(quality.width,quality.height);
            for (Size available:streams.getHighSpeedVideoSizes()) if (available.equals(requested)) {
                Range<Integer> best=null;
                for (Range<Integer> range:streams.getHighSpeedVideoFpsRangesFor(available)) {
                    if (range.getUpper()==fps && range.contains(fps)
                            && (best==null || range.getLower()>best.getLower())) best=range;
                }
                return best;
            }
        } catch (Exception ignored) { }
        return null;
    }
    static boolean encoderSupports(boolean hevc,int w,int h,int fps) { return findEncoder(hevc,w,h,fps)!=null; }
    static MediaCodecInfo.VideoCapabilities findEncoder(boolean hevc,int w,int h,int fps) {
        String mime=hevc ? "video/hevc" : "video/avc";
        for (MediaCodecInfo codec:new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
            if (!codec.isEncoder()) continue;
            try {
                MediaCodecInfo.VideoCapabilities video=codec.getCapabilitiesForType(mime).getVideoCapabilities();
                if (video!=null && video.areSizeAndRateSupported(w,h,fps)) return video;
            } catch (IllegalArgumentException ignored) { }
        }
        return null;
    }
    static int bitrate(Quality quality,int fps,boolean hevc,boolean high) {
        long rate=(long)(quality.bitrate*(fps/30d)*(high ? 1.2 : 0.85)*(hevc ? 0.7 : 1.0));
        MediaCodecInfo.VideoCapabilities codec=findEncoder(hevc,quality.width,quality.height,fps);
        if (codec!=null) return codec.getBitrateRange().clamp((int)Math.min(rate,Integer.MAX_VALUE));
        // Stock Huawei camera apps can use a private 4K60 profile. Keep the
        // public MediaRecorder attempt within a plausible encoder bit-rate.
        if (fps==60 && quality.height>=2160) rate=Math.min(rate,hevc ? 60_000_000L : 80_000_000L);
        return (int)Math.min(rate,Integer.MAX_VALUE);
    }
}
