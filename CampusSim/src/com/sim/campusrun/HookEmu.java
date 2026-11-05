package com.sim.campusrun;

import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 绕过目标 App 的环境检测：
 *  1) com.lptiyu.tanke.utils.r0 —— 模拟器/环境检测（混淆类）
 *  2) com.scottyab.rootbeer.RootBeer —— root 检测（第三方库）
 *
 * 这类类由加固壳稍后才加载，所以这里同时用三种方式捕获：
 *   a) 立刻 findClass（壳已解开的进程可命中）
 *   b) hook ClassLoader.loadClass，一加载就挂
 *   c) 后台线程定时重试（最多 20 秒）
 */
final class HookEmu {

    private static final String R0 = "com.lptiyu.tanke.utils.r0";
    private static final String ROOTBEER = "com.scottyab.rootbeer.RootBeer";
    private static final String ROOTBEER_NATIVE = "com.scottyab.rootbeer.RootBeerNative";

    private static volatile boolean r0Done, rbDone, rbnDone;

    static void install(final ClassLoader cl) {
        if (!Cfg.probeR0 && !Cfg.bypassEmu) return;

        fixBuild();
        hookProps();
        hookClassLoader(cl);
        tryNow(cl);
        retryLoop(cl);
    }

    /** 一有人加载目标类就立刻挂 hook */
    private static void hookClassLoader(final ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod(ClassLoader.class, "loadClass", String.class, boolean.class,
                    new XC_MethodHook() {
                        protected void afterHookedMethod(MethodHookParam p) {
                            Object r = p.getResult();
                            if (!(r instanceof Class)) return;
                            String n = (String) p.args[0];
                            if (R0.equals(n)) hookR0Class((Class<?>) r);
                            else if (ROOTBEER.equals(n)) hookRootBeerClass((Class<?>) r);
                            else if (ROOTBEER_NATIVE.equals(n)) hookRootBeerNative((Class<?>) r);
                        }
                    });
        } catch (Throwable t) {
            Log.w("HookEmu.loadClass: " + t);
        }
    }

    private static void tryNow(ClassLoader cl) {
        Class<?> c = XposedHelpers.findClassIfExists(R0, cl);
        if (c != null) hookR0Class(c);
        Class<?> rb = XposedHelpers.findClassIfExists(ROOTBEER, cl);
        if (rb != null) hookRootBeerClass(rb);
        Class<?> rbn = XposedHelpers.findClassIfExists(ROOTBEER_NATIVE, cl);
        if (rbn != null) hookRootBeerNative(rbn);
    }

    private static void retryLoop(final ClassLoader cl) {
        Thread t = new Thread(new Runnable() {
            public void run() {
                for (int i = 0; i < 40; i++) {
                    if (r0Done && rbDone && rbnDone) return;
                    try { Thread.sleep(500); } catch (Throwable ignored) { }
                    tryNow(cl);
                }
                if (!r0Done) Log.w("HookEmu: " + R0 + " 一直没加载（可能不在这个类加载器里）");
            }
        }, "campus-emu");
        t.setDaemon(true);
        t.start();
    }

    // ---------------------------------------------------------------- 属性伪造
    private static final String[][] SAFE_PROPS = {
            {"ro.debuggable", "0"},
            {"ro.secure", "1"},
            {"ro.build.tags", "release-keys"},
            {"ro.build.characteristics", "default"},
            {"ro.enable.native.bridge.exec", "0"},
            {"ro.enable.native.bridge.exec64", "0"},
            {"ro.product.cpu.abilist", "arm64-v8a,armeabi-v7a,armeabi"},
            {"ro.product.cpu.abilist64", "arm64-v8a"},
            {"ro.product.cpu.abilist32", "armeabi-v7a,armeabi"},
            {"ro.system.product.cpu.abilist", "arm64-v8a,armeabi-v7a,armeabi"},
            {"ro.system.product.cpu.abilist64", "arm64-v8a"},
            {"ro.vendor.product.cpu.abilist", "arm64-v8a,armeabi-v7a,armeabi"},
            {"ro.odm.product.cpu.abilist", "arm64-v8a,armeabi-v7a,armeabi"},
            {"ro.build.fingerprint", "OPPO/PDKM00/OP4ABB:11/RP1A.200720.011/1638469161792:user/release-keys"},
            {"ro.kernel.qemu", "0"},
            {"ro.boot.qemu", "0"},
            {"qemu.hw.mainkeys", "0"},
            {"ro.build.hv.platform", "qcom"},
    };

    private static String safe(String key) {
        if (key == null) return null;
        for (String[] kv : SAFE_PROPS) if (kv[0].equals(key)) return kv[1];
        return null;
    }


    // ---------------------------------------------------------------- Build 字段
    /** 检查读的是 Build 的静态字段（进程启动即缓存），必须直接改字段值 */
    private static void fixBuild() {
        if (!Cfg.bypassEmu) return;
        try {
            Class<?> b = XposedHelpers.findClassIfExists("android.os.Build", null);
            if (b == null) return;
            setStr(b, "TAGS", "release-keys");
            setStr(b, "TYPE", "user");
            setStr(b, "HARDWARE", "qcom");
            setStr(b, "BOARD", "kona");
            setStr(b, "CHARACTERISTICS", "default");
            setStr(b, "FINGERPRINT",
                    "OPPO/PDKM00/OP4ABB:11/RP1A.200720.011/1638469161792:user/release-keys");
            setStr(b, "MANUFACTURER", "OPPO");
            setStr(b, "BRAND", "OPPO");
            setStr(b, "MODEL", "PDKM00");
            setStr(b, "DEVICE", "OP4ABB");
            setStr(b, "PRODUCT", "PDKM00");
            setStr(b, "FLAVOR", "full");
            setArr(b, "SUPPORTED_ABIS", new String[]{"arm64-v8a", "armeabi-v7a", "armeabi"});
            setArr(b, "SUPPORTED_64_BIT_ABIS", new String[]{"arm64-v8a"});
            setArr(b, "SUPPORTED_32_BIT_ABIS", new String[]{"armeabi-v7a", "armeabi"});
            setStr(b, "CPU_ABI", "arm64-v8a");
            setStr(b, "CPU_ABI2", "armeabi-v7a");
            Log.i("Build fields patched");
        } catch (Throwable t) {
            Log.w("fixBuild: " + t);
        }
    }

    private static void setStr(Class<?> c, String name, String v) {
        try {
            XposedHelpers.setStaticObjectField(c, name, v);
        } catch (Throwable ignored) {
            try {
                java.lang.reflect.Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                f.set(null, v);
            } catch (Throwable ignored2) { }
        }
    }

    private static void setArr(Class<?> c, String name, String[] v) {
        try {
            XposedHelpers.setStaticObjectField(c, name, v);
        } catch (Throwable ignored) { }
    }

    private static void hookProps() {
        if (!Cfg.bypassEmu) return;
        try {
            Class<?> sp = XposedHelpers.findClassIfExists("android.os.SystemProperties", null);
            if (sp == null) return;
            XposedHelpers.findAndHookMethod(sp, "get", String.class, new XC_MethodHook() {
                protected void beforeHookedMethod(MethodHookParam p) {
                    String v = safe((String) p.args[0]);
                    if (v != null) {
                        p.setResult(v);
                        if (Cfg.probeR0) Log.i("prop(" + p.args[0] + ") -> " + v);
                    }
                }
            });
            XposedHelpers.findAndHookMethod(sp, "get", String.class, String.class, new XC_MethodHook() {
                protected void beforeHookedMethod(MethodHookParam p) {
                    String v = safe((String) p.args[0]);
                    if (v != null) p.setResult(v);
                }
            });
            XposedHelpers.findAndHookMethod(sp, "getInt", String.class, int.class, new XC_MethodHook() {
                protected void beforeHookedMethod(MethodHookParam p) {
                    String v = safe((String) p.args[0]);
                    if (v != null) p.setResult(Integer.parseInt(v));
                }
            });
            XposedHelpers.findAndHookMethod(sp, "getBoolean", String.class, boolean.class, new XC_MethodHook() {
                protected void beforeHookedMethod(MethodHookParam p) {
                    String v = safe((String) p.args[0]);
                    if (v != null) p.setResult("1".equals(v));
                }
            });
            Log.i("props hooked (" + SAFE_PROPS.length + " keys)");
        } catch (Throwable t) {
            Log.w("hookProps: " + t);
        }
    }
    private static void hookR0Class(Class<?> r0) {
        if (r0Done) return;
        r0Done = true;
        Method[] ms = r0.getDeclaredMethods();
        int n = 0;
        for (final Method m : ms) {
            try {
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    protected void beforeHookedMethod(MethodHookParam p) {
                        String ret = "";
                        if (Cfg.bypassEmu) {
                            Object v = safeValue(m.getReturnType());
                            p.setResult(v);
                            ret = " -> " + (v == null ? "null" : v);
                        }
                        if (Cfg.probeR0) Log.i("r0." + m.getName() + "()" + ret);
                    }
                });
                n++;
            } catch (Throwable ignored) { }
        }
        StringBuilder sig = new StringBuilder();
        for (Method m : ms) {
            sig.append("  r0sig: ").append(m.getName()).append("(");
            Class<?>[] ps = m.getParameterTypes();
            for (int i = 0; i < ps.length; i++) sig.append(i > 0 ? "," : "").append(ps[i].getSimpleName());
            sig.append("):").append(m.getReturnType().getSimpleName()).append("\n");
        }
        Log.i("r0 hooked: " + n + "/" + ms.length + " (bypass=" + Cfg.bypassEmu + ")\n" + sig);
    }

    private static void hookRootBeerClass(Class<?> rb) {
        if (rbDone) return;
        rbDone = true;
        String[] names = {"isRooted", "isRootedWithoutBusyBoxCheck", "isRootedWithBusyBoxCheck",
                "detectRootManagementApps", "detectPotentiallyDangerousApps",
                "detectRootCloakingApps", "checkForBinary", "checkForSuBinary",
                "checkSuExists", "checkForRWPaths", "checkForDangerousProps",
                "checkForMagiskBinary", "checkForDrozerCicada"};
        int n = 0;
        for (final String name : names) {
            try {
                XposedHelpers.findAndHookMethod(rb, name, new XC_MethodHook() {
                    protected void beforeHookedMethod(MethodHookParam p) {
                        if (Cfg.probeR0) Log.i("RootBeer." + name + "() -> false");
                        if (Cfg.bypassEmu) p.setResult(false);
                    }
                });
                n++;
            } catch (Throwable ignored) { }
        }
        Log.i("RootBeer hooked: " + n + " methods");
    }

    private static void hookRootBeerNative(Class<?> rbn) {
        if (rbnDone) return;
        rbnDone = true;
        try {
            XposedHelpers.findAndHookMethod(rbn, "checkForRoot", String[].class, new XC_MethodHook() {
                protected void beforeHookedMethod(MethodHookParam p) {
                    if (Cfg.probeR0) Log.i("RootBeerNative.checkForRoot() -> 0");
                    if (Cfg.bypassEmu) p.setResult(0);
                }
            });
        } catch (Throwable ignored) { }
        Log.i("RootBeerNative hooked");
    }

    private static Object safeValue(Class<?> rt) {
        if (rt == void.class) return null;
        if (rt == boolean.class) return false;
        if (rt == int.class) return 0;
        if (rt == long.class) return 0L;
        if (rt == float.class) return 0f;
        if (rt == double.class) return 0d;
        if (rt == String.class) return "";
        return null;
    }

    private HookEmu() { }
}
