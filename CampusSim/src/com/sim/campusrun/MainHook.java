package com.sim.campusrun;

import android.location.Location;
import android.os.SystemClock;

import java.util.ArrayList;
import java.util.List;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/** 入口：作用域只挂目标 App 主进程 */
public class MainHook implements IXposedHookLoadPackage {

    public static final String[] TARGETS = {"com.lptiyu.tanke"};

    /** 目标 App 的 PathClassLoader（App/SDK 类必须用它找） */
    public static volatile ClassLoader appCl;

    private static Thread ticker;
    private static Route route;
    private static long seenVersion = -1;
    private static boolean finishedLogged;
    private static boolean coordLogged;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lp) {
        Cfg.load();
        boolean hit = Cfg.isTarget(lp.packageName);
        for (String p : TARGETS) if (p.equals(lp.packageName)) hit = true;
        if (!hit) return;
        if (!lp.packageName.equals(lp.processName)) return;

        appCl = lp.classLoader;
        Log.i("inject -> " + lp.packageName + " (" + lp.processName + ") enabled=" + Cfg.enabled);
        safe(new Runnable() { public void run() { HookHide.install(); } });
        safe(new Runnable() { public void run() { HookLoc.install(); } });
        safe(new Runnable() { public void run() { HookSensor.install(); } });
        safe(new Runnable() { public void run() { HookNet.install(); } });
            safe(new Runnable() { public void run() { HookAntiKill.install(); } });
            safe(new Runnable() { public void run() { HookDump.install(); } });
            safe(new Runnable() { public void run() { HookEmu.install(appCl); } });
        startTicker();
        startLateHooks();
    }

    private static void safe(Runnable r) {
        try { r.run(); } catch (Throwable t) { Log.w("hook fail: " + t); }
    }

    /** SDK 类可能延迟加载：后台重试挂 AMap / GMS 钩子 */
    private static void startLateHooks() {
        Thread t = new Thread(new Runnable() {
            public void run() {
                for (int i = 0; i < 90; i++) {
                    try {
                        if (HookLoc.installSdkHooks()) { Log.i("sdk hooks ready (retry #" + i + ")"); return; }
                    } catch (Throwable ignored) { }
                    try { Thread.sleep(2000); } catch (Throwable ignored) { return; }
                }
                Log.w("sdk hooks never installed");
            }
        }, "campus-late");
        t.setDaemon(true);
        t.start();
    }

    private static void startTicker() {
        if (ticker != null) return;
        ticker = new Thread(new Runnable() { public void run() { loop(); } }, "campus-tick");
        ticker.setDaemon(true);
        ticker.start();
    }

    private static void loop() {
        int n = 0;
        long lastNs = SystemClock.elapsedRealtimeNanos();
        long lastFixNs = 0, lastHbNs = 0, lastYawNs = 0;
        double stepAcc = 0;

        while (true) {
            try {
                if ((n++ % 25) == 0) Cfg.load();

                if (Cfg.routeSig != seenVersion) {
                    List<double[]> pts = new ArrayList<double[]>();
                    synchronized (Cfg.route) { pts.addAll(Cfg.route); }
                    List<double[]> cps = new ArrayList<double[]>();
                    synchronized (Cfg.checkpoints) { cps.addAll(Cfg.checkpoints); }
                    Route r = Route.build(pts, cps);
                    if (r != null && r.ready()) {
                        route = r;
                        seenVersion = Cfg.routeSig;
                        finishedLogged = false;
                        Log.i("route loaded: " + pts.size() + " pts, " + cps.size()
                                + " cps, total=" + String.format("%.0fm", r.total()) + " mode=" + Cfg.mode);
                    }
                }

                if (!Cfg.enabled || route == null) {
                    Thread.sleep(200);
                    lastNs = SystemClock.elapsedRealtimeNanos();
                    continue;
                }

                long now = SystemClock.elapsedRealtimeNanos();
                double dt = Math.min(0.5, (now - lastNs) / 1e9);
                lastNs = now;

                double[] r = route.advance(dt);
                State.spm = r[5];
                State.resting = route.resting();

                // 跑完（once 模式到达终点）：只打一条日志，之后原地站着
                if (route.finished() && !finishedLogged) {
                    finishedLogged = true;
                    Log.i(String.format("finished: dist=%.0fm rests=%d cp=%d/%d",
                            route.dist(), route.restCount(), route.cpPassed(), route.cpTotal()));
                }

                // 1Hz 出定位（暂停时速度为 0，位置不动）
                if (now - lastFixNs >= 1000000000L) {
                    lastFixNs = now;
                    // 配置里存 WGS-84；乐跑这类 App 把拿到的坐标当 GCJ 直接渲染，
                    // 不做转换就会整体平移几百米（形状不变）。
                    double outLat = r[0], outLng = r[1];
                    if ("gcj".equals(Cfg.coord)) {
                        double[] g = Gps.gcj(r[0], r[1]);
                        outLat = g[0];
                        outLng = g[1];
                    }
                    // 朝向：分段方向做平滑，跑起来才给 bearing（静止时 Android 给 0）
                    double brgRaw = r[2];
                    if (r[3] > 0.6) {
                        if (State.heading < 0) {
                            State.heading = brgRaw;
                        } else {
                            double dbrg = ((brgRaw - State.heading + 540.0) % 360.0) - 180.0;
                            State.heading = (State.heading + dbrg * 0.35
                                    + (Math.random() - 0.5) * 3.0 + 360.0) % 360.0;
                            State.headingRate = dbrg * 0.35;   // deg/s，给陀螺仪用
                        }
                    } else {
                        State.headingRate = 0;
                    }
                    // 设备朝向相对行进方向有个慢慢变的固定偏角（拿在手里/装兜里/换手）
                    if (now - lastYawNs > 120000000000L) {
                        lastYawNs = now;
                        State.yawBiasTarget = (Math.random() * 2 - 1) * 45.0;
                    }
                    double dYaw = State.yawBiasTarget - State.yawBias;
                    double yawStep = Math.max(-1.2, Math.min(1.2, dYaw * 0.04));
                    State.yawBias += yawStep;

                    Location l = new Location(LocationManagerConst.GPS);
                    l.setLatitude(outLat);
                    l.setLongitude(outLng);
                    l.setBearing((float) (r[3] > 0.6 ? (State.heading < 0 ? brgRaw : State.heading) : 0.0));
                    l.setBearingAccuracyDegrees(7.0f);
                    l.setSpeed((float) r[3]);
                    l.setAccuracy(3.2f + (float) Math.random() * 1.8f);
                    l.setAltitude(r[4]);
                    l.setTime(System.currentTimeMillis());
                    l.setElapsedRealtimeNanos(now);
                    State.fake = l;
                    State.lastFix = new double[]{outLat, outLng, r[2], r[3], r[4]};
                    State.emitLoc(l);
                    HookLoc.pushAmap(l);
                    if (!coordLogged) {
                        coordLogged = true;
                        Log.i(String.format("coord=%s wgs=%.6f,%.6f -> emit=%.6f,%.6f (Δ%.0fm)",
                                Cfg.coord, r[0], r[1], outLat, outLng,
                                Route.haversine(r[0], r[1], outLat, outLng)));
                    }
                }

                // 步数：与速度严格联动（stride = v/(spm/60)）
                if (!State.resting && State.spm > 0.5) {
                    stepAcc += State.spm / 60.0 * dt;
                    while (stepAcc >= 1.0) {
                        stepAcc -= 1.0;
                        HookSensor.fireStep(now);
                    }
                }
                // ACC / MAG / GYRO 共用同一路 50Hz 合成，开任意一个都要跑
                if (Cfg.accel || Cfg.mag || Cfg.gyro) HookSensor.fireAccel(now);

                if (Cfg.heartbeat > 0 && now - lastHbNs >= Cfg.heartbeat * 1000000000L) {
                    lastHbNs = now;
                    Log.i(String.format("hb fix=%.6f,%.6f v=%.2f spm=%.0f brg=%.0f dev=%.0f rest=%d cp=%d/%d dist=%.0f rests=%d paused=%d%s",
                            r[0], r[1], r[3], r[5],
                            State.heading < 0 ? r[2] : State.heading,
                            (State.heading < 0 ? 0 : (State.heading + State.yawBias + 360) % 360),
                            route.resting() ? 1 : 0,
                            route.cpPassed(), route.cpTotal(), route.dist(), route.restCount(),
                            Cfg.paused ? 1 : 0, route.finished() ? " FIN" : ""));
                }

                Thread.sleep(20);
            } catch (InterruptedException ie) {
                return;
            } catch (Throwable t) {
                Log.w("tick: " + t);
                try { Thread.sleep(500); } catch (Throwable ignored) { }
            }
        }
    }
}
