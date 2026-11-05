package com.sim.campusrun;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 运行期配置。优先读 /data/local/tmp/campusim.json（mapdraw.html 生成），
 * 没有则退回旧格式 /data/local/tmp/campusim.conf（key=value）。
 */
public final class Cfg {
    public static final String JSON_PATH = "/data/local/tmp/campusim.json";
    public static final String CONF_PATH = "/data/local/tmp/campusim.conf";

    public static volatile boolean enabled = false;
    public static volatile boolean paused = false;
    /** once = 起点到终点跑完就停；loop = 闭环绕圈 */
    public static volatile String mode = "loop";
    /** 只有轨迹/参数变化才重建 Route，paused/enabled 变化不影响进度 */
    public static volatile long routeSig = 0;
    public static volatile String targets = "com.lptiyu.tanke";

    public static volatile double paceSecPerKm = 330.0;
    public static volatile double speedJitter = 0.06;
    public static volatile double spmBase = 172.0;
    public static volatile double spmJitter = 4.0;

    public static volatile double restsPerKm = 0.0;
    public static volatile int restMinSec = 15;
    public static volatile int restMaxSec = 60;

    public static volatile boolean randomStart = false;
    public static volatile boolean randomDir = false;
    public static volatile long seed = 0;
    public static volatile int heartbeat = 10;

    public static volatile boolean wifi = true;
    public static volatile boolean cell = true;
    public static volatile boolean ble = true;
    public static volatile boolean accel = true;
    /** 磁力计 / 陀螺仪：乐跑会上传 run_gyroscope 与 Sensor_MAG/ROT，缺了就是异常 */
    public static volatile boolean mag = true;
    /** 反自杀：拦截加固壳的 Process.killProcess/System.exit（模拟器调试用，默认关） */
    public static volatile boolean antiKill = false;
    /** 逆向：把运行时解出来的 dex 落盘 */
    public static volatile boolean dexDump = false;
    /** 逆向：UniverseSec* 日志打 Java 栈 */
    public static volatile boolean logTrace = false;
    /** 只打印 r0 的方法调用（定位检测方法） */
    public static volatile boolean probeR0 = false;
    /** 绕过环境检测：r0 返回安全值、RootBeer 返回 false */
    public static volatile boolean bypassEmu = false;
    public static volatile boolean gyro = true;
    /** 速度上下限（m/s）：分段配速不能出现不可能值 */
    public static volatile double minSpeed = 0.6;
    public static volatile double maxSpeed = 6.5;
    /** 每 100m 分段配速的相对抖动 */
    public static volatile double segJitter = 0.12;
    /** 周边环境数量：WiFi 扫描条数 / 基站条数 */
    public static volatile int wifiCount = 4;
    public static volatile int cellCount = 3;
    /** 卫星数与载噪比范围 */
    public static volatile int satCount = 12;
    /** 数量波动幅度（卫星/WiFi/基站数在 ±N 内慢速漂移） */
    public static volatile int countJitter = 1;
    /** 各参数单独的波动幅度（界面单位） */
    public static volatile double paceJitterSec = 30;   // 配速 ±s/km
    public static volatile double restJitter = 0.2;     // 休息 ±次/km
    public static volatile int satJitter = 1;           // 卫星数 ±
    public static volatile int wifiJitter = 1;          // WiFi 数 ±
    public static volatile int cellJitter = 1;          // 基站数 ±
    public static volatile double cn0Min = 22;
    public static volatile double cn0Max = 40;

    public static volatile double checkpointRadius = 25.0;
    public static final List<double[]> route = new ArrayList<double[]>();
    public static final List<double[]> checkpoints = new ArrayList<double[]>();
    public static volatile int routeVersion = 0;
    /** 下发坐标系：gcj = 乐跑这类按 GCJ 渲染的国产 App（默认）；wgs = 自己做转换的 App */
    public static volatile String coord = "gcj";
    /** 每次点“开始”换一个新值：让 Route 重建 → 进度归零（暂停/继续不变） */
    public static volatile long runToken = 0;

    private static volatile long lastMod = -1L;
    private static volatile long lastLen = -1L;
    /** 曾经成功从 App 拿到过配置：socket 断开时保持现状，不回落到可能过期的文件 */
    private static volatile boolean haveSocketCfg = false;

    public static boolean isTarget(String pkg) {
        if (pkg == null) return false;
        String t = targets;
        if (t == null || t.length() == 0) return false;
        for (String s : t.split(",")) if (s.trim().equals(pkg)) return true;
        return false;
    }

    public static synchronized void load() {
        try {
            // 1) 优先从 App 的本机 socket 读（开始/暂停/结束即时生效，不需要 root）
            if (loadFromSocket()) return;
            // App 退到后台/被杀：保持最后一次下发的配置，不要回落到 /data/local/tmp 的旧文件
            if (haveSocketCfg) return;

            // 2) 退回文件：/data/local/tmp/campusim.json（simctl / mkserver 推送）
            File jf = new File(JSON_PATH);
            File cf = new File(CONF_PATH);
            File f = jf.exists() ? jf : (cf.exists() ? cf : null);
            if (f == null) return;
            long mod = f.lastModified();
            long len = f.length();
            if (mod == lastMod && len == lastLen) return;
            lastMod = mod;
            lastLen = len;

            if (f == jf) loadJson(readAll(f)); else loadLegacy(readAll(f));
            routeVersion++;
        } catch (Throwable t) {
            Log.w("cfg: " + t);
        }
    }

    private static String lastSocketText = "";

    /** 从 App 的 127.0.0.1:47561 拉配置（跨应用不受包可见性限制，也不需要 root） */
    private static boolean loadFromSocket() {
        java.net.Socket s = null;
        try {
            s = new java.net.Socket();
            s.connect(new java.net.InetSocketAddress("127.0.0.1", LocalConfigServer.PORT), 300);
            s.setSoTimeout(600);
            java.io.OutputStream o = s.getOutputStream();
            o.write("GET /cfg HTTP/1.0\r\n\r\n".getBytes("UTF-8"));
            o.flush();
            java.io.InputStream in = s.getInputStream();
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            String raw = bo.toString("UTF-8");
            int idx = raw.indexOf("\r\n\r\n");
            String body = idx >= 0 ? raw.substring(idx + 4) : raw;
            if (body.length() < 10 || body.charAt(0) != '{') return false;
            haveSocketCfg = true;
            if (body.equals(lastSocketText)) return true;
            lastSocketText = body;
            loadJson(body);
            routeVersion++;
            return true;
        } catch (Throwable t) {
            return false;
        } finally {
            try { if (s != null) s.close(); } catch (Throwable ignored) { }
        }
    }

    private static String readAll(File f) throws Exception {
        StringBuilder b = new StringBuilder();
        BufferedReader r = new BufferedReader(new FileReader(f));
        String line;
        while ((line = r.readLine()) != null) b.append(line).append('\n');
        r.close();
        return b.toString();
    }

    // ---------------------------------------------------------------- JSON
    private static void loadJson(String text) {
        Map<String, Object> m = Json.obj(Json.parse(text));
        enabled = Json.bool(m, "enabled", true);
        paused = Json.bool(m, "paused", false);
        mode = Json.str(m, "mode", "loop");
        Object tg = m.get("targets");
        if (tg instanceof List) {
            StringBuilder b = new StringBuilder();
            for (Object o : Json.arr(tg)) if (o != null) { if (b.length() > 0) b.append(','); b.append(String.valueOf(o)); }
            if (b.length() > 0) targets = b.toString();
        } else if (m.get("target") instanceof String) {
            targets = (String) m.get("target");
        }

        paceSecPerKm = clamp(Json.num(m, "paceSecPerKm", 330), 150, 900);
        speedJitter = clamp(Json.num(m, "speedJitter", 0.06), 0, 0.5);
        spmBase = clamp(Json.num(m, "spmBase", 172), 100, 220);
        spmJitter = clamp(Json.num(m, "spmJitter", 4), 0, 30);
        restsPerKm = clamp(Json.num(m, "restsPerKm", 0), 0, 50);
        restMinSec = (int) clamp(Json.num(m, "restMinSec", 15), 0, 600);
        restMaxSec = (int) clamp(Json.num(m, "restMaxSec", 60), 0, 600);
        if (restMaxSec < restMinSec) restMaxSec = restMinSec;
        checkpointRadius = clamp(Json.num(m, "checkpointRadius", 25), 5, 200);
        heartbeat = (int) clamp(Json.num(m, "heartbeat", 10), 0, 300);
        randomStart = Json.bool(m, "randomStart", false);
        randomDir = Json.bool(m, "randomDir", false);
        seed = (long) Json.num(m, "seed", 0);
        wifi = Json.bool(m, "wifi", true);
        cell = Json.bool(m, "cell", true);
        ble = Json.bool(m, "ble", true);
        accel = Json.bool(m, "accel", true);
        mag = Json.bool(m, "mag", true);
        antiKill = Json.bool(m, "antiKill", false);
        dexDump = Json.bool(m, "dexDump", false);
        logTrace = Json.bool(m, "logTrace", false);
        probeR0 = Json.bool(m, "probeR0", false);
        bypassEmu = Json.bool(m, "bypassEmu", false);
        gyro = Json.bool(m, "gyro", true);
        minSpeed = clamp(Json.num(m, "minSpeed", 0.6), 0.1, 3.0);
        maxSpeed = clamp(Json.num(m, "maxSpeed", 6.5), 2.0, 12.0);
        segJitter = clamp(Json.num(m, "segJitter", 0.12), 0, 0.6);
        wifiCount = (int) clamp(Json.num(m, "wifiCount", 4), 1, 12);
        cellCount = (int) clamp(Json.num(m, "cellCount", 3), 1, 8);
        satCount = (int) clamp(Json.num(m, "satCount", 12), 4, 32);
        countJitter = (int) clamp(Json.num(m, "countJitter", 1), 0, 4);
        paceJitterSec = clamp(Json.num(m, "paceJitterSec", 30), 0, 300);
        restJitter = clamp(Json.num(m, "restJitter", 0.2), 0, 5);
        satJitter = (int) clamp(Json.num(m, "satJitter", countJitter), 0, 8);
        wifiJitter = (int) clamp(Json.num(m, "wifiJitter", countJitter), 0, 8);
        cellJitter = (int) clamp(Json.num(m, "cellJitter", countJitter), 0, 8);
        // 配速波动换算成分段倍率
        if (paceJitterSec > 0) {
            segJitter = Math.max(0, Math.min(0.6, paceJitterSec / Math.max(150.0, paceSecPerKm)));
        } else {
            segJitter = 0;
        }
        cn0Min = clamp(Json.num(m, "cn0Min", 22), 5, 45);
        cn0Max = clamp(Json.num(m, "cn0Max", 40), 10, 55);
        if (cn0Max <= cn0Min) cn0Max = cn0Min + 5;
        runToken = (long) Json.num(m, "runToken", runToken);
        coord = Json.str(m, "coord", "gcj");

        List<double[]> rt = parsePts(Json.arr(m.get("route")));
        if (rt.size() >= 2) {
            synchronized (route) { route.clear(); route.addAll(rt); }
        }
        List<double[]> cps = parsePts(Json.arr(m.get("checkpoints")));
        synchronized (checkpoints) { checkpoints.clear(); checkpoints.addAll(cps); }

        long sig = 17;
        for (double[] p : rt) sig = sig * 31 + Double.valueOf(p[0]).hashCode() + Double.valueOf(p[1]).hashCode();
        for (double[] p : cps) sig = sig * 31 + Double.valueOf(p[0]).hashCode();
        sig = sig * 31 + Double.valueOf(paceSecPerKm).hashCode()
                + Double.valueOf(spmBase).hashCode()
                + Double.valueOf(restsPerKm).hashCode()
                + Double.valueOf(speedJitter).hashCode()
                + Double.valueOf(spmJitter).hashCode()
                + mode.hashCode() + (randomStart ? 1 : 0) + (randomDir ? 2 : 0) + seed
                + runToken;          // 点“开始”换 token → 重建 Route，进度归零
        routeSig = sig;

        Log.i("cfg(json): route=" + rt.size() + " cp=" + cps.size() + " pace=" + (int) paceSecPerKm
                + " spm=" + (int) spmBase + " jit=" + speedJitter + " rest/km=" + restsPerKm
                + " mode=" + mode + " paused=" + paused + " enabled=" + enabled);
    }

    private static List<double[]> parsePts(List<Object> list) {
        List<double[]> out = new ArrayList<double[]>();
        for (Object e : list) {
            if (e instanceof List) {
                List<Object> p = Json.arr(e);
                if (p.size() >= 2) out.add(new double[]{toD(p.get(0)), toD(p.get(1))});
            } else if (e instanceof String) {
                String[] s = ((String) e).split(",");
                if (s.length >= 2) {
                    try { out.add(new double[]{Double.parseDouble(s[0].trim()), Double.parseDouble(s[1].trim())}); }
                    catch (Throwable ignored) { }
                }
            }
        }
        return out;
    }

    private static double toD(Object o) {
        return o instanceof Number ? ((Number) o).doubleValue() : 0;
    }

    // ---------------------------------------------------------------- 旧格式
    private static void loadLegacy(String text) {
        boolean en = false;
        String tgt = targets;
        List<double[]> rt = new ArrayList<double[]>();
        for (String line : text.split("\n")) {
            line = line.trim();
            if (line.length() == 0 || line.startsWith("#")) continue;
            int eq = line.indexOf('=');
            if (eq < 0) continue;
            String k = line.substring(0, eq).trim(), v = line.substring(eq + 1).trim();
            if (k.equals("enabled")) en = v.equals("1") || v.equalsIgnoreCase("true");
            else if (k.equals("paceSecPerKm")) paceSecPerKm = clamp(num(v, paceSecPerKm), 150, 900);
            else if (k.equals("stepSpm")) spmBase = clamp(num(v, spmBase), 100, 220);
            else if (k.equals("wifi")) wifi = v.equals("1");
            else if (k.equals("cell")) cell = v.equals("1");
            else if (k.equals("ble")) ble = v.equals("1");
            else if (k.equals("accel")) accel = v.equals("1");
            else if (k.equals("target")) tgt = v;
            else if (k.equals("route")) {
                String[] p = v.split(",");
                if (p.length >= 2) {
                    try { rt.add(new double[]{Double.parseDouble(p[0].trim()), Double.parseDouble(p[1].trim())}); }
                    catch (Throwable ignored) { }
                }
            }
        }
        enabled = en;
        targets = tgt;
        if (rt.size() >= 2) { synchronized (route) { route.clear(); route.addAll(rt); } }
        routeSig = System.currentTimeMillis();
        mode = "loop";
        paused = false;
        Log.i("cfg(legacy conf): route=" + rt.size());
    }

    private static double num(String v, double def) {
        try { return Double.parseDouble(v); } catch (Throwable t) { return def; }
    }

    private static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private Cfg() { }

    /** 慢速漂移：t 秒为单位，period 秒一周期，返回 -amp..amp */
    static int drift(int base, int amp, double periodSec, double phase) {
        if (amp <= 0) return base;
        double t = System.currentTimeMillis() / 1000.0;
        double v = Math.sin(t * 2 * Math.PI / periodSec + phase) * amp;
        int r = base + (int) Math.round(v);
        return Math.max(1, r);
    }

    static int satNow() { return Math.max(4, drift(satCount, satJitter, 97, 0.0)); }
    static int wifiNow() { return drift(wifiCount, wifiJitter, 71, 1.7); }
    static int cellNow() { return drift(cellCount, cellJitter, 83, 3.1); }
}
