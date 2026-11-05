package com.sim.campusrun;

import android.location.GnssStatus;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.CancellationSignal;
import android.os.Looper;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** 定位面 hook：framework + AMap SDK + GMS Fused */
final class HookLoc {
    private static ClassLoader cl;
    private static volatile boolean amapDone;
    private static volatile boolean gmsDone;
    private static volatile boolean gcjLogged;
    private static final List<Object> amapReal = new CopyOnWriteArrayList<Object>();

    static void install() {
        cl = MainHook.appCl != null ? MainHook.appCl : HookLoc.class.getClassLoader();
        try { framework(); } catch (Throwable t) { Log.w("loc-fw: " + t); }
        try { gnss(); } catch (Throwable t) { Log.w("loc-gnss: " + t); }
        installSdkHooks();
    }

    /** AMap / GMS 类可能还没被 App 加载，可反复调用 */
    static synchronized boolean installSdkHooks() {
        if (cl == null) cl = MainHook.appCl != null ? MainHook.appCl : HookLoc.class.getClassLoader();
        if (!amapDone) {
            try { amap(); } catch (Throwable t) { Log.w("loc-amap: " + t); }
        }
        if (!gmsDone) {
            try { gms(); } catch (Throwable t) { Log.w("loc-gms: " + t); }
        }
        return amapDone || gmsDone;
    }

    // ---------------------------------------------------------------- framework
    private static void framework() {
        hookNoArg(LocationManager.class, "getLastKnownLocation", new XC_MethodHook() {
            protected void beforeHookedMethod(MethodHookParam p) {
                Location f = State.fake;
                if (f != null) p.setResult(State.withProvider(f, (String) p.args[0]));
            }
        });
        hookNoArg(LocationManager.class, "getLastLocation", new XC_MethodHook() {
            protected void beforeHookedMethod(MethodHookParam p) {
                Location f = State.fake;
                if (f != null) p.setResult(State.withProvider(f, LocationManagerConst.GPS));
            }
        });

        // requestLocationUpdates 的四种重载：把真实 listener 换成"吞真事件"的代理
        XposedHelpers.findAndHookMethod(LocationManager.class, "requestLocationUpdates",
                String.class, long.class, float.class, LocationListener.class,
                new XC_MethodHook() { protected void beforeHookedMethod(MethodHookParam p) { swap(p, 3, 0); } });
        XposedHelpers.findAndHookMethod(LocationManager.class, "requestLocationUpdates",
                String.class, long.class, float.class, LocationListener.class, Looper.class,
                new XC_MethodHook() { protected void beforeHookedMethod(MethodHookParam p) { swap(p, 3, 0); } });

        XposedHelpers.findAndHookMethod(LocationManager.class, "getCurrentLocation",
                String.class, CancellationSignal.class, Executor.class, Consumer.class,
                new XC_MethodHook() {
                    @SuppressWarnings("unchecked")
                    protected void beforeHookedMethod(MethodHookParam p) {
                        Location f = State.fake;
                        if (f == null) return;
                        final Consumer<Location> c = (Consumer<Location>) p.args[3];
                        if (c != null) {
                            try { c.accept(State.withProvider(f, (String) p.args[0])); }
                            catch (Throwable t) { Log.w("currentLocation: " + t); }
                        }
                        p.setResult(null);
                    }
                });
    }

    private static void hookNoArg(Class<?> c, String name, XC_MethodHook cb) {
        try { XposedHelpers.findAndHookMethod(c, name, cb); } catch (Throwable ignored) { }
    }

    private static void swap(XC_MethodHook.MethodHookParam p, int listenerIdx, int providerIdx) {
        final LocationListener real = (LocationListener) p.args[listenerIdx];
        if (real == null) return;
        LocationListener proxy = (LocationListener) Proxy.newProxyInstance(
                LocationListener.class.getClassLoader(),
                new Class[]{LocationListener.class},
                new InvocationHandler() {
                    public Object invoke(Object o, Method m, Object[] a) throws Throwable {
                        String n = m.getName();
                        if ("onLocationChanged".equals(n)) return null;      // 真事件一律丢弃
                        if ("hashCode".equals(n)) return System.identityHashCode(real);
                        if ("equals".equals(n)) return real == (a == null ? null : a[0]);
                        if ("toString".equals(n)) return "CampusSimListener";
                        return m.invoke(real, a);
                    }
                });
        p.args[listenerIdx] = proxy;
        State.locListeners.add(real);
        Location f = State.fake;
        if (f != null) {
            final Location out = State.withProvider(f, String.valueOf(p.args[providerIdx]));
            State.MAIN.post(new Runnable() { public void run() { real.onLocationChanged(out); } });
        }
    }

    // ---------------------------------------------------------------- GNSS 状态
    private static void gnss() {
        XposedHelpers.findAndHookMethod(GnssStatus.class, "getSatelliteCount", new XC_MethodHook() {
            protected void afterHookedMethod(MethodHookParam p) { p.setResult(Cfg.satNow()); }
        });
        XposedHelpers.findAndHookMethod(GnssStatus.class, "getSvid", int.class, new XC_MethodHook() {
            protected void afterHookedMethod(MethodHookParam p) {
                p.setResult(58 + ((Integer) p.args[0]) % Math.max(1, Cfg.satNow()));
            }
        });
        XposedHelpers.findAndHookMethod(GnssStatus.class, "getCn0DbHz", int.class, new XC_MethodHook() {
            protected void afterHookedMethod(MethodHookParam p) {
                int i = (Integer) p.args[0];
                double span = Math.max(1, Cfg.cn0Max - Cfg.cn0Min);
                p.setResult((float) (Cfg.cn0Min + (i * 1.7) % span));
            }
        });
        XposedHelpers.findAndHookMethod(GnssStatus.class, "usedInFix", int.class, new XC_MethodHook() {
            protected void afterHookedMethod(MethodHookParam p) {
                p.setResult(((Integer) p.args[0]) < Math.max(4, Cfg.satNow() - 3));
            }
        });
        XposedHelpers.findAndHookMethod(GnssStatus.class, "getElevationDegrees", int.class, new XC_MethodHook() {
            protected void afterHookedMethod(MethodHookParam p) {
                p.setResult(20.0f + ((Integer) p.args[0] * 13) % 60);
            }
        });
        XposedHelpers.findAndHookMethod(GnssStatus.class, "getAzimuthDegrees", int.class, new XC_MethodHook() {
            protected void afterHookedMethod(MethodHookParam p) {
                p.setResult(((Integer) p.args[0] * 29) % 360);
            }
        });
    }

    // ---------------------------------------------------------------- AMap SDK
    private static void amap() {
        final Class<?> iface = XposedHelpers.findClassIfExists("com.amap.api.location.AMapLocationListener", cl);
        final Class<?> client = XposedHelpers.findClassIfExists("com.amap.api.location.AMapLocationClient", cl);
        if (iface == null || client == null) { return; }

        XposedHelpers.findAndHookMethod(client, "setLocationListener", iface, new XC_MethodHook() {
            protected void beforeHookedMethod(MethodHookParam p) {
                Object real = p.args[0];
                if (real == null) return;
                Object proxy = Proxy.newProxyInstance(iface.getClassLoader(), new Class[]{iface},
                        new InvocationHandler() {
                            public Object invoke(Object o, Method m, Object[] a) throws Throwable {
                                String n = m.getName();
                                if ("onLocationChanged".equals(n)) return null;   // 真 AMap 结果丢弃
                                if ("hashCode".equals(n)) return System.identityHashCode(real);
                                if ("equals".equals(n)) return real == (a == null ? null : a[0]);
                                if ("toString".equals(n)) return "CampusSimAMap";
                                return m.invoke(real, a);
                            }
                        });
                p.args[0] = proxy;
                amapReal.add(real);
                Log.i("amap listener wrapped");
            }
        });
        try {
            XposedHelpers.findAndHookMethod(client, "startLocation", new XC_MethodHook() {
                protected void afterHookedMethod(MethodHookParam p) {
                    Location f = State.fake;
                    if (f != null) pushAmap(f);
                }
            });
        } catch (Throwable ignored) { }
        amapDone = true;
        Log.i("amap hooks installed");
    }

    /** 把当前假定位以 AMapLocation 形式推给 App 的 AMap 监听器 */
    static void pushAmap(Location l) {
        if (amapReal.isEmpty()) return;
        try {
            // 传进来的 Location 已经按 Cfg.coord 转换过（默认 GCJ），这里原样下发
            Class<?> alc = XposedHelpers.findClass("com.amap.api.location.AMapLocation", cl);
            final Object fake = XposedHelpers.newInstance(alc, "gps");
            XposedHelpers.callMethod(fake, "setLatitude", l.getLatitude());
            XposedHelpers.callMethod(fake, "setLongitude", l.getLongitude());
            XposedHelpers.callMethod(fake, "setAccuracy", l.getAccuracy());
            XposedHelpers.callMethod(fake, "setSpeed", l.getSpeed());
            XposedHelpers.callMethod(fake, "setBearing", l.getBearing());
            XposedHelpers.callMethod(fake, "setAltitude", l.getAltitude());
            XposedHelpers.callMethod(fake, "setTime", l.getTime());
            XposedHelpers.callMethod(fake, "setLocationType", 1);
            XposedHelpers.callMethod(fake, "setErrorCode", 0);
            for (final Object real : amapReal) {
                State.MAIN.post(new Runnable() {
                    public void run() {
                        try { XposedHelpers.callMethod(real, "onLocationChanged", fake); }
                        catch (Throwable t) { Log.w("amap push: " + t); }
                    }
                });
            }
        } catch (Throwable t) {
            Log.w("amap build: " + t);
        }
    }

    // ---------------------------------------------------------------- GMS Fused
    private static void gms() {
        Class<?> lr = XposedHelpers.findClassIfExists("com.google.android.gms.location.LocationResult", cl);
        if (lr == null) return;
        gmsDone = true;
        try {
            XposedHelpers.findAndHookMethod(lr, "getLocations", new XC_MethodHook() {
                @SuppressWarnings("unchecked")
                protected void afterHookedMethod(MethodHookParam p) {
                    Location f = State.fake;
                    if (f == null) return;
                    java.util.ArrayList<Location> one = new java.util.ArrayList<Location>();
                    one.add(State.withProvider(f, LocationManagerConst.FUSED));
                    p.setResult(one);
                }
            });
        } catch (Throwable ignored) { }
        try {
            XposedHelpers.findAndHookMethod(lr, "getLastLocation", new XC_MethodHook() {
                protected void afterHookedMethod(MethodHookParam p) {
                    Location f = State.fake;
                    if (f != null) p.setResult(State.withProvider(f, LocationManagerConst.FUSED));
                }
            });
        } catch (Throwable ignored) { }
    }

    private HookLoc() { }
}
