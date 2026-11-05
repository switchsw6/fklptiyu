package com.sim.campusrun;

import android.app.AppOpsManager;
import android.content.ContentResolver;
import android.location.Location;

import java.io.File;
import java.io.IOException;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/** 痕迹面：mock 标记 / AppOps / Settings / 常见 root 探针 */
final class HookHide {
    private static final String[] SU_PATHS = {
            "/system/bin/su", "/system/xbin/su", "/sbin/su", "/system/sd/xbin/su",
            "/system/bin/failsafe/su", "/data/local/su", "/data/local/xbin/su",
            "/data/local/bin/su", "/su/bin/su", "/magisk/.core/bin/su"
    };

    static void install() {
        mock();
        settings();
        appops();
        root();
    }

    private static void mock() {
        String[] names = {"isFromMockProvider", "isMock"};
        for (String n : names) {
            try {
                XposedHelpers.findAndHookMethod(Location.class, n, new XC_MethodHook() {
                    protected void afterHookedMethod(MethodHookParam p) { p.setResult(false); }
                });
            } catch (Throwable ignored) { }
        }
        try {
            XposedHelpers.findAndHookMethod(Location.class, "setMock", boolean.class, new XC_MethodHook() {
                protected void beforeHookedMethod(MethodHookParam p) { p.setResult(null); }
            });
        } catch (Throwable ignored) { }
    }

    private static void settings() {
        try {
            XposedHelpers.findAndHookMethod("android.provider.Settings$Secure", null, "getString",
                    ContentResolver.class, String.class, new XC_MethodHook() {
                        protected void afterHookedMethod(MethodHookParam p) {
                            String k = String.valueOf(p.args[1]);
                            if (k.contains("mock_location")) p.setResult("0");
                        }
                    });
        } catch (Throwable ignored) { }
        try {
            XposedHelpers.findAndHookMethod("android.provider.Settings$Secure", null, "getInt",
                    ContentResolver.class, String.class, int.class, new XC_MethodHook() {
                        protected void afterHookedMethod(MethodHookParam p) {
                            String k = String.valueOf(p.args[1]);
                            if (k.contains("mock_location")) p.setResult(0);
                        }
                    });
        } catch (Throwable ignored) { }
    }

    private static void appops() {
        XC_MethodHook cb = new XC_MethodHook() {
            protected void afterHookedMethod(MethodHookParam p) {
                String op = String.valueOf(p.args[0]);
                if (op != null && op.contains("MOCK_LOCATION")) p.setResult(AppOpsManager.MODE_ALLOWED);
            }
        };
        for (String m : new String[]{"checkOpNoThrow", "unsafeCheckOpNoThrow", "noteOpNoThrow"}) {
            try {
                XposedHelpers.findAndHookMethod(AppOpsManager.class, m, String.class, int.class, String.class, cb);
            } catch (Throwable ignored) { }
        }
    }

    private static void root() {
        try {
            XposedHelpers.findAndHookMethod(File.class, "exists", new XC_MethodHook() {
                protected void afterHookedMethod(MethodHookParam p) {
                    if (!(p.getResult() instanceof Boolean) || !((Boolean) p.getResult())) return;
                    String path = ((File) p.thisObject).getAbsolutePath();
                    for (String s : SU_PATHS) if (path.equals(s)) { p.setResult(false); return; }
                }
            });
        } catch (Throwable ignored) { }
        try {
            XposedHelpers.findAndHookMethod(Runtime.class, "exec", String.class, new XC_MethodHook() {
                protected void beforeHookedMethod(MethodHookParam p) throws IOException {
                    String cmd = String.valueOf(p.args[0]).trim();
                    if (cmd.equals("su") || cmd.startsWith("su ") || cmd.equals("/system/bin/su")) {
                        throw new IOException("Permission denied");
                    }
                }
            });
        } catch (Throwable ignored) { }
    }

    private HookHide() { }
}
