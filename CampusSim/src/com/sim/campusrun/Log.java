package com.sim.campusrun;

/**
 * 日志：logcat + Xposed 日志。
 * 注意：模块 App 单独运行时（没有 Xposed 框架）也会调用这里，
 * 所以对 XposedBridge 必须做可用性判断，不能让它把进程带崩。
 */
public final class Log {
    private static final String TAG = "CampusSim";
    private static volatile boolean xposedOk = true;

    private static void xlog(String m) {
        if (!xposedOk) return;
        try {
            de.robv.android.xposed.XposedBridge.log(m);
        } catch (Throwable t) {
            xposedOk = false;   // 没有框架就彻底关掉这条通道
        }
    }

    public static void i(String m) {
        try { android.util.Log.i(TAG, m); } catch (Throwable ignored) { }
        xlog(TAG + " " + m);
    }

    public static void w(String m) {
        try { android.util.Log.w(TAG, m); } catch (Throwable ignored) { }
        xlog("! " + TAG + " " + m);
    }

    private Log() { }
}
