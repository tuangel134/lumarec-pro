package com.lumarec.pro;

import android.app.Activity;
import android.app.Dialog;
import android.os.Build;
import android.view.Window;
import android.view.WindowManager;

final class PrivacyUi {
    static void protect(Activity activity,boolean enabled) { protect(activity.getWindow(),enabled); }
    static void protect(Dialog dialog) { if (dialog.getWindow()!=null) protect(dialog.getWindow(),true); }
    private static void protect(Window window,boolean enabled) {
        if (enabled) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
        if (Build.VERSION.SDK_INT>=31) window.setHideOverlayWindows(enabled);
    }
}
