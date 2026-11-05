package com.sim.campusrun;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * 逆向辅助：
 *  1) dexDump：拦 InMemoryDexClassLoader / DexFile，把运行时解出来的 dex 落盘（脱壳）
 *  2) logTrace：目标 App 一旦用 "UniverseSec*" 打日志，就把 Java 调用栈打出来（定位检测点）
 *
 * 两个开关都在 Cfg 里，默认关闭；只对目标进程生效。
 */
final class HookDump {

    private static final String DIR = "/data/local/tmp/campusim_dump";

    static void install() {
        if (!Cfg.dexDump && !Cfg.logTrace) return;
        if (Cfg.dexDump) {
            try {
                File d = new File(DIR);
                if (!d.exists()) d.mkdirs();
            } catch (Throwable ignored) { }
            hookInMemory();
            hookDexFile();
        }
        if (Cfg.logTrace) hookLog();
    }

    // ---------------------------------------------------------------- dex 落盘
    private static void hookInMemory() {
        try {
            final Class<?> c = XposedHelpers.findClassIfExists("dalvik.system.InMemoryDexClassLoader", null);
            if (c == null) return;
            XC_MethodHook h = new XC_MethodHook() {
                protected void afterHookedMethod(MethodHookParam p) {
                    try {
                        for (Object a : p.args) {
                            if (a instanceof ByteBuffer) {
                                dumpBuffer((ByteBuffer) a, "inmem");
                            } else if (a instanceof ByteBuffer[]) {
                                int i = 0;
                                for (ByteBuffer b : (ByteBuffer[]) a) dumpBuffer(b, "inmem" + (i++));
                            }
                        }
                    } catch (Throwable t) { Log.w("dexdump: " + t); }
                }
            };
            XposedHelpers.findAndHookConstructor(c, ByteBuffer.class, ClassLoader.class, h);
            XposedHelpers.findAndHookConstructor(c, ByteBuffer[].class, ClassLoader.class, h);
            Log.i("dexdump: InMemoryDexClassLoader hooked");
        } catch (Throwable t) {
            Log.w("dexdump.inmem: " + t);
        }
    }

    private static void hookDexFile() {
        try {
            Class<?> dexFile = XposedHelpers.findClassIfExists("dalvik.system.DexFile", null);
            if (dexFile == null) return;
            XposedHelpers.findAndHookConstructor(dexFile, String.class, ClassLoader.class,
                    new XC_MethodHook() {
                        protected void afterHookedMethod(MethodHookParam p) {
                            Log.i("dexdump: DexFile loaded from " + p.args[0]);
                        }
                    });
        } catch (Throwable t) {
            Log.w("dexdump.dexfile: " + t);
        }
    }

    private static void dumpBuffer(ByteBuffer buf, String tag) {
        try {
            ByteBuffer b = buf.duplicate();
            b.position(0);
            byte[] data = new byte[b.remaining()];
            b.get(data);
            if (data.length < 100 || data[0] != 'd' || data[1] != 'e' || data[2] != 'x') {
                // 不是标准 dex 头也存下来（可能是 dex035/038 变体或加密块）
                if (data.length < 16) return;
            }
            String name = DIR + "/" + tag + "_" + System.currentTimeMillis() + ".dex";
            FileOutputStream os = new FileOutputStream(name);
            os.write(data);
            os.close();
            Log.i("dexdump: wrote " + name + " (" + data.length + " bytes)");
        } catch (Throwable t) {
            Log.w("dexdump.write: " + t);
        }
    }

    // ---------------------------------------------------------------- 日志栈
    private static void hookLog() {
        String[] names = {"e", "w", "i", "d", "v", "wtf"};
        for (final String n : names) {
            try {
                XposedHelpers.findAndHookMethod("android.util.Log", null, n,
                        String.class, String.class, new XC_MethodHook() {
                            protected void afterHookedMethod(MethodHookParam p) {
                                String tag = p.args[0] == null ? "" : p.args[0].toString();
                                if (tag.startsWith("UniverseSec") || tag.startsWith("Universe")) {
                                    Log.i("[TRACE] " + n + "/" + tag + " : " + p.args[1] + "\n"
                                            + android.util.Log.getStackTraceString(new Throwable()));
                                }
                            }
                        });
            } catch (Throwable ignored) { }
        }
        Log.i("logtrace: Log hooked");
    }

    private HookDump() { }
}
