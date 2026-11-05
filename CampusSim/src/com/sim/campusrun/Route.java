package com.sim.campusrun;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 运动模型 v2：路线推进 + OU 速度扰动 + 随机休息 + 随机起点/方向 + 打卡点校验。
 * 速度与步频严格联动：stride = v / (spm/60)，保证"步数×步幅 ≈ 距离"。
 */
public final class Route {

    private final List<double[]> pts = new ArrayList<double[]>();
    private final double[] segLen;
    private final double total;
    private final long t0 = android.os.SystemClock.elapsedRealtimeNanos();

    private final Random rnd;
    private final boolean once;      // true = 起点→终点跑完即停
    private volatile boolean finished;
    private int dir = 1;
    private double s = 0;          // 当前里程（米，0..total）
    private double dist = 0;       // 累计里程
    private double v;              // 当前速度 m/s
    private double spm;
    private double restRemain = 0;    // 剩余休息秒（double，按 dt 递减）
    private double nextRestDist;   // 下一次休息触发的累计里程
    private int restCount = 0;
    private double restSeconds = 0;
    /** 分段配速：每 100m 一个目标系数，平滑跟随 */
    private double segMul = 1.0, segMulTarget = 1.0, nextSegDist = 100;

    private final List<double[]> cps;
    private final boolean[] cpDone;
    private int cpPassed = 0;

    private Route(List<double[]> p, List<double[]> checkpoints) {
        pts.addAll(p);
        int n = pts.size();
        segLen = new double[n];
        once = "once".equals(Cfg.mode);
        double acc = 0;
        for (int i = 0; i < n; i++) {
            // 单程模式没有"最后一点回到起点"这一段，否则总里程会多算返程
            double d;
            if (once && i == n - 1) {
                d = 0;
            } else {
                double[] a = pts.get(i), b = pts.get((i + 1) % n);
                d = haversine(a[0], a[1], b[0], b[1]);
            }
            segLen[i] = d;
            acc += d;
        }
        total = acc;
        cps = new ArrayList<double[]>(checkpoints);
        cpDone = new boolean[cps.size()];

        rnd = Cfg.seed != 0 ? new Random(Cfg.seed) : new Random();
        v = targetSpeed();
        spm = Cfg.spmBase;

        if (Cfg.randomStart && total > 10) s = rnd.nextDouble() * total;
        if (Cfg.randomDir && rnd.nextBoolean()) dir = -1;
        nextRestDist = restInterval();
    }

    public static Route build(List<double[]> p, List<double[]> cps) {
        return p.size() >= 2 ? new Route(p, cps) : null;
    }

    public boolean ready() { return total > 5.0; }
    public double total() { return total; }
    public double dist() { return dist; }
    public boolean resting() { return restRemain > 0 || v < 0.2; }
    public double spm() { return spm; }
    public double speed() { return v; }
    public int cpPassed() { return cpPassed; }
    public int cpTotal() { return cps.size(); }
    public int restCount() { return restCount; }
    public double restSeconds() { return restSeconds; }
    public boolean finished() { return finished; }

    private double targetSpeed() { return 1000.0 / Math.max(150.0, Cfg.paceSecPerKm); }

    /** 下一次休息的里程间隔（指数分布） */
    private double restInterval() {
        double perKm = Cfg.restsPerKm;
        if (perKm <= 0) return Double.MAX_VALUE;
        // 每次抽间隔时对"每公里休息次数"做一次 ±restJitter 的抖动
        if (Cfg.restJitter > 0) {
            perKm += (rnd.nextDouble() * 2 - 1) * Cfg.restJitter;
        }
        if (perKm <= 0.02) return Double.MAX_VALUE;
        double u = Math.max(1e-6, rnd.nextDouble());
        return -Math.log(u) / (perKm / 1000.0);
    }

    private double gauss() {
        return rnd.nextGaussian();
    }

    /**
     * 推进 dt 秒。
     * @return {lat, lng, bearingDeg, speed, altitude, spm}
     */
    public double[] advance(double dt) {
        if (dt <= 0) return pos(0);
        if (dt > 2) dt = 1.0;                        // 后台/唤醒防跳

        // 暂停：原地站着（速度 0、步频 0），不推进里程
        if (Cfg.paused) {
            v = 0; spm = 0;
            return pos(0);
        }
        if (finished) { v = 0; spm = 0; return pos(0); }

        double target = targetSpeed();

        // ---- 休息状态机（按里程触发）
        if (restRemain > 0) {
            restRemain -= dt;
            restSeconds += dt;
            v = Math.max(0, v - v * dt / 0.6 - 0.15 * dt);
            if (v < 0.05) v = 0;
            spm = v > 0.3 ? Cfg.spmBase * 0.7 : 0;
            if (restRemain < 0) restRemain = 0;
        } else {
            if (dist >= nextRestDist && Cfg.restsPerKm > 0) {
                int d = Cfg.restMinSec + (Cfg.restMaxSec > Cfg.restMinSec
                        ? rnd.nextInt(Cfg.restMaxSec - Cfg.restMinSec + 1) : 0);
                if (d > 0) {
                    restRemain = d;
                    restCount++;
                    Log.i("rest @" + String.format("%.0fm", dist) + " " + d + "s");
                }
                nextRestDist = dist + restInterval();
            }
            // ---- 每 100m 换一次分段配速目标，再平滑追上去（乐跑会看“分段100m配速”）
            if (dist >= nextSegDist) {
                nextSegDist += 100;
                segMulTarget = 1 + (rnd.nextDouble() * 2 - 1) * Cfg.segJitter;
            }
            segMul += (segMulTarget - segMul) * Math.min(1, dt / 6.0);
            double segTarget = target * segMul;

            // ---- 速度：一阶惯性 + OU 噪声，并夹在物理合理区间
            double tau = 2.5;
            double sigma = target * Cfg.speedJitter * 0.45;
            v += (segTarget - v) * (dt / tau) + gauss() * sigma * Math.sqrt(dt);
            double lo = Math.max(Cfg.minSpeed, target * 0.55);
            double hi = Math.min(Cfg.maxSpeed, target * 1.55);
            if (hi < lo) hi = lo;
            v = Math.min(hi, Math.max(lo, v));

            // ---- 步频：由“当前速度 / 基准步幅”反算，保证 步数×步幅 ≈ 距离
            //      （速度被 maxSpeed 夹住时，步频必须跟着降，否则乐跑会判步数与距离不符）
            double baseStride = target / (Math.max(60.0, Cfg.spmBase) / 60.0);
            double spmT = 60.0 * v / Math.max(0.5, baseStride) + gauss() * Cfg.spmJitter;
            spmT = Math.min(210, Math.max(80, spmT));
            spm += (spmT - spm) * Math.min(1, dt * 1.5);
        }

        double step = v * dt;
        dist += step;
        if (once) {
            s = s + dir * step;
            if (s >= total) { s = total - 0.01; finished = true; v = 0; spm = 0; }
            if (s <= 0) { s = 0; finished = true; v = 0; spm = 0; }
        } else {
            s = s + dir * step;
            if (s >= total) s -= total;
            if (s < 0) s += total;
        }

        checkCheckpoints();
        return pos(v);
    }

    private void checkCheckpoints() {
        if (cpDone.length == 0 || cpPassed == cpDone.length) return;
        double[] p = latLng();
        for (int i = 0; i < cps.size(); i++) {
            if (cpDone[i]) continue;
            double[] c = cps.get(i);
            if (haversine(p[0], p[1], c[0], c[1]) <= Cfg.checkpointRadius) {
                cpDone[i] = true;
                cpPassed++;
                Log.i("checkpoint " + (char) ('A' + i) + " passed (" + cpPassed + "/" + cpDone.length + ")");
            }
        }
    }

    /** 当前坐标 */
    public double[] latLng() {
        int n = pts.size();
        int i = segIndex();
        double rem = s;
        for (int k = 0; k < i; k++) rem -= segLen[k];
        if (rem < 0) rem = 0;
        double[] a = pts.get(i), b = pts.get((i + 1) % n);
        double f = segLen[i] > 1e-6 ? Math.min(1.0, rem / segLen[i]) : 0;
        return new double[]{a[0] + (b[0] - a[0]) * f, a[1] + (b[1] - a[1]) * f};
    }

    private double[] pos(double speed) {
        double[] p = latLng();
        int n = pts.size();
        int i = segIndex();
        double[] a = pts.get(i), b = pts.get((i + 1) % n);
        double brg = bearing(a[0], a[1], b[0], b[1]);
        if (dir < 0) brg = (brg + 180) % 360;
        double alt = 18.0 + Math.sin((android.os.SystemClock.elapsedRealtimeNanos() - t0) / 4.0e10) * 5.0;
        return new double[]{p[0], p[1], brg, speed, alt, spm};
    }

    /** 有界遍历，避免单程模式最后一段长度为 0 时死循环 */
    private int segIndex() {
        int n = pts.size();
        int i = 0;
        double rem = s;
        while (i < n - 1 && rem > segLen[i]) {
            rem -= segLen[i];
            i++;
        }
        return i;
    }

    // ---------------------------------------------------------------- 几何
    public static double haversine(double la1, double lo1, double la2, double lo2) {
        final double R = 6371000.0;
        double dLa = Math.toRadians(la2 - la1), dLo = Math.toRadians(lo2 - lo1);
        double h = Math.sin(dLa / 2) * Math.sin(dLa / 2)
                + Math.cos(Math.toRadians(la1)) * Math.cos(Math.toRadians(la2))
                * Math.sin(dLo / 2) * Math.sin(dLo / 2);
        return 2 * R * Math.asin(Math.sqrt(h));
    }

    public static double bearing(double la1, double lo1, double la2, double lo2) {
        double y = Math.sin(Math.toRadians(lo2 - lo1)) * Math.cos(Math.toRadians(la2));
        double x = Math.cos(Math.toRadians(la1)) * Math.sin(Math.toRadians(la2))
                - Math.sin(Math.toRadians(la1)) * Math.cos(Math.toRadians(la2)) * Math.cos(Math.toRadians(lo2 - lo1));
        return (Math.toDegrees(Math.atan2(y, x)) + 360.0) % 360.0;
    }
}
