package com.sim.campusrun;

import android.os.Process;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * 反自杀兜底：目标 App 的加固壳在检测到环境异常时会 Process.killProcess(自己) / System.exit。
 * 打开 Cfg.antiKill 后把这些自杀调用拦下来，让进程活下来（模拟器调试用）。
 *
 * 只在 enabled 且 antiKill=true 时生效，默认关闭。
 */
final class HookAntiKill {

    private static volatile boolean logged;

    static void install() {
        try {
            XposedHelpers.findAndHookMethod(Process.class, "killProcess", int.class, new XC_MethodHook() {
                protected void beforeHookedMethod(MethodHookParam p) {
                    if (!shouldBlock()) return;
                    int pid = (Integer) p.args[0];
                    if (pid == Process.myPid()) {
                        p.setResult(null);
                        once("killProcess(" + pid + ") blocked");
                    }
                }
            });
        } catch (Throwable t) { Log.w("antiKill.killProcess: " + t); }

        try {
            XposedHelpers.findAndHookMethod(Process.class, "sendSignal", int.class, int.class, new XC_MethodHook() {
                protected void beforeHookedMethod(MethodHookParam p) {
                    if (!shouldBlock()) return;
                    int pid = (Integer) p.args[0];
                    if (pid == Process.myPid()) {
                        p.setResult(null);
                        once("sendSignal(" + pid + ") blocked");
                    }
                }
            });
        } catch (Throwable t) { Log.w("antiKill.sendSignal: " + t); }

        try {
            XposedHelpers.findAndHookMethod(System.class, "exit", int.class, new XC_MethodHook() {
                protected void beforeHookedMethod(MethodHookParam p) {
                    if (!shouldBlock()) return;
                    p.setResult(null);
                    once("System.exit(" + p.args[0] + ") blocked");
                }
            });
        } catch (Throwable t) { Log.w("antiKill.System.exit: " + t); }

        try {
            XposedHelpers.findAndHookMethod(Runtime.class, "exit", int.class, new XC_MethodHook() {
                protected void beforeHookedMethod(MethodHookParam p) {
                    if (!shouldBlock()) return;
                    p.setResult(null);
                    once("Runtime.exit(" + p.args[0] + ") blocked");
                }
            });
        } catch (Throwable t) { Log.w("antiKill.Runtime.exit: " + t); }

        try {
            XposedHelpers.findAndHookMethod(Runtime.class, "halt", int.class, new XC_MethodHook() {
                protected void beforeHookedMethod(MethodHookParam p) {
                    if (!shouldBlock()) return;
                    p.setResult(null);
                    once("Runtime.halt(" + p.args[0] + ") blocked");
                }
            });
        } catch (Throwable t) { Log.w("antiKill.Runtime.halt: " + t); }

        try {
            XposedHelpers.findAndHookMethod(Process.class, "killProcessQuiet", int.class, new XC_MethodHook() {
                protected void beforeHookedMethod(MethodHookParam p) {
                    if (!shouldBlock()) return;
                    int pid = (Integer) p.args[0];
                    if (pid == Process.myPid()) {
                        p.setResult(null);
                        once("killProcessQuiet blocked");
                    }
                }
            });
        } catch (Throwable t) { Log.w("antiKill.killProcessQuiet: " + t); }
    }

    private static boolean shouldBlock() {
        return Cfg.enabled && Cfg.antiKill;
    }

    private static void once(String what) {
        if (logged) return;
        logged = true;
        Log.i("antiKill: " + what + " -> 进程保活");
    }

    private HookAntiKill() { }
}
