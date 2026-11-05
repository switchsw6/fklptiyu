package com.sim.campusrun;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 纯几何的自动路线生成（离线可用）。
 *
 * 输入：有序锚点（起点 / 途经点… / 终点）+ 目标里程。
 * 做法：先按锚点连成骨架；若目标里程更长，就在每段按比例插入两个“绕行控制点”，
 *      用二分法解出垂直于该段的鼓出高度 h，使该段长度补到目标值；
 *      左右交替避免绕行环叠在一起；最后 Chaikin 削角 + 每 2m 采样。
 * 输出：可编辑的关键点（ctrl）+ 采样折线（预览用）。
 */
final class AutoRoute {

    static final class Result {
        List<double[]> nodes = new ArrayList<double[]>();   // 关键点（WGS），可编辑
        List<double[]> points = new ArrayList<double[]>();  // 采样折线（WGS），预览用
        double length;
        double target;
        boolean loop = false;      // true = 闭环（起点=终点）
        int laps = 1;              // 环绕圈数
        String note = "";
    }

    private static final double KLAT = 110540.0;

    static Result build(List<double[]> anchors, double targetMeters, long seed) {
        Result r = new Result();
        r.target = targetMeters;
        if (anchors == null || anchors.size() < 2) {
            r.note = "至少需要起点和终点";
            return r;
        }

        double lat0 = anchors.get(0)[0], lng0 = anchors.get(0)[1];
        double klng = 111320.0 * Math.cos(Math.toRadians(lat0));

        List<double[]> m = new ArrayList<double[]>();
        for (double[] p : anchors) m.add(new double[]{(p[1] - lng0) * klng, (p[0] - lat0) * KLAT});

        double skeleton = polyLen(m);
        double target = Math.max(targetMeters, skeleton * 0.9);

        if (target <= skeleton * 1.02) {
            r.nodes.addAll(anchors);
            r.points = densify(toWgs(m, lat0, lng0, klng), 2.0);
            r.length = skeleton;
            r.note = String.format("目标里程 ≈ 骨架长度，未做绕行（骨架 %.0f m）", skeleton);
            return r;
        }

        double extra = target - skeleton;

        // 迭代收敛：Chaikin 会削短，故按实测比值反复修正 scale，直到误差 ≤1%
        List<double[]> ctrl = null, shaped = null;
        double got = skeleton, scale = 1.0;
        int iter = 0;
        for (iter = 1; iter <= 6; iter++) {
            ctrl = insertDetours(m, skeleton, extra, scale, new Random(seed));
            shaped = chaikin(ctrl, 2);
            got = polyLen(shaped);
            if (Math.abs(got - target) / target <= 0.01) break;
            scale = scale * (target - skeleton) / Math.max(1e-6, got - skeleton);
            scale = Math.max(0.3, Math.min(3.0, scale));
        }
        if (ctrl == null || shaped == null) {
            ctrl = insertDetours(m, skeleton, extra, 1.0, new Random(seed));
            shaped = chaikin(ctrl, 2);
            got = polyLen(shaped);
        }

        double maxH = 0;
        for (double[] p : ctrl) maxH = Math.max(maxH, Math.abs(deviation(m, p)));

        r.nodes = dedupe(toWgs(sampleByDistance(shaped, 30.0), lat0, lng0, klng));
        r.points = densify(toWgs(shaped, lat0, lng0, klng), 2.0);
        r.length = got;

        double err = Math.abs(got - target) / target * 100;
        r.note = String.format("骨架 %.0f m → 生成 %.0f m（目标 %.0f m，偏差 %.1f%%）", skeleton, got, target, err);
        if (maxH > 900) r.note += "；绕行幅度已到上限，里程可能不足";
        if (err > 3) r.note += "；可点「重生成」换绕行方向再试";
        return r;
    }

    /**
     * 沿真实路网生成（OSRM foot）。先按锚点求最短道路路径，再不断在最长的一段旁边
     * 找一个“真实道路上的绕行点”插进去，直到总里程接近目标。
     */
    static Result byRoad(List<double[]> anchors, double targetMeters) {
        Result r = new Result();
        r.target = targetMeters;
        if (anchors == null || anchors.size() < 2) {
            r.note = "至少需要起点和终点";
            return r;
        }

        // 1) 先求一条最短道路路径（起点→途经点→终点）
        Router.R base = Router.route(anchors);
        if (!base.ok) {
            r.note = "路网路由失败：" + base.err;
            return r;
        }
        List<double[]> bestPts = base.pts;
        double bestDist = base.dist;
        boolean isLoop = false;

        double err0 = Math.abs(bestDist - targetMeters) / Math.max(1, targetMeters);
        if (err0 > 0.05) {
            // 2) 里程不够/超了：用“环绕半径二分”找出最接近目标的一圈真实道路
            Router.R loop = buildRoadLoop(anchors, targetMeters);
            if (loop != null && loop.ok) {
                // 一圈不够就跑多圈（校园跑最常见的就是绕圈）
                int laps = 1;
                double len = loop.dist;
                double bestE = Math.abs(len - targetMeters);
                for (int k = 2; k <= 6; k++) {
                    double e = Math.abs(loop.dist * k - targetMeters);
                    if (e < bestE) { bestE = e; len = loop.dist * k; laps = k; }
                }
                if (Math.abs(len - targetMeters) < Math.abs(bestDist - targetMeters)) {
                    bestPts = repeatPath(loop.pts, laps);
                    bestDist = len;
                    isLoop = true;
                    r.laps = laps;
                }
            }
        }

        r.points = densify(bestPts, 2.0);
        r.nodes = dedupe(sampleByDistanceWgs(bestPts, 30.0));
        r.length = bestDist;
        r.loop = isLoop;
        double err = Math.abs(bestDist - targetMeters) / Math.max(1, targetMeters) * 100;
        r.note = String.format("路网（OSRM foot）%s%s%.0f m / 目标 %.0f m，偏差 %.1f%%",
                isLoop ? "环绕 " : "", r.laps > 1 ? (r.laps + " 圈 ") : "", bestDist, targetMeters, err);
        if (err > 5) {
            r.note += String.format("；街道间距会把里程“量化”，把目标改成 %.0f m 就能对上", bestDist);
        }
        return r;
    }

    /**
     * 校园路网图搜索：起点/途经点/终点自动吸附到绘制好的道路上，A* 连通，
     * 里程不足就在图上插“闭合环”或“岔路往返”，全部落在跑道内。
     */
    static Result byGraph(List<double[]> anchors, double targetMeters, SchoolMap map) {
        long t0 = System.currentTimeMillis();
        Result r = new Result();
        r.target = targetMeters;
        if (map == null || !map.hasRoads()) {
            r.note = "没有校园路网数据";
            return r;
        }
        RoadGraph g = RoadGraph.build(map, 5.0, 4.0);
        if (g.size < 4) {
            r.note = "路网顶点不足（道路太短或都在跑区外）";
            return r;
        }

        List<double[]> vias = new ArrayList<double[]>(anchors);
        int[] seq = routeThrough(g, vias);
        if (seq == null) {
            r.note = "路网不连通：把两条道路接起来或重画";
            return r;
        }
        double len = g.pathLen(seq);
        int[] bestSeq = seq;
        double bestLen = len;
        double bestErr = Math.abs(len - targetMeters);

        boolean loop = anchors.size() > 2
                && Math.abs(anchors.get(0)[0] - anchors.get(anchors.size() - 1)[0]) < 1e-6
                && Math.abs(anchors.get(0)[1] - anchors.get(anchors.size() - 1)[1]) < 1e-6;

        // 里程不够：不追求最短，随机挑“远处的路口”当途经点，一路绕过去
        java.util.Random rnd = new java.util.Random(System.nanoTime());
        int iter = 0;
        int stuck = 0;
        while (len < targetMeters * 0.97 && iter++ < 24 && stuck < 6) {
            double need = targetMeters - len;
            double[] via = pickVia(g, bestSeq, need, rnd);
            if (via == null) break;
            List<double[]> trial = new ArrayList<double[]>(vias);
            trial.add(trial.size() - 1, via);          // 插在终点之前
            int[] s2 = routeThrough(g, trial);
            if (s2 == null) { stuck++; continue; }
            double l2 = g.pathLen(s2);
            if (l2 < len + 15) { stuck++; continue; }  // 没绕到路，换个点再试
            vias = trial;
            len = l2;
            seq = s2;
            stuck = 0;
            if (Math.abs(l2 - targetMeters) < bestErr) {
                bestErr = Math.abs(l2 - targetMeters);
                bestSeq = s2;
                bestLen = l2;
            }
            if (bestLen >= targetMeters * 0.97) break;
        }

        // 弯路凑不够（例如整条路就是一个闭环）：整圈重复 n 次，取最接近目标的 n
        int[] cyc = turnIntoCycle(g, bestSeq);
        if (cyc != null) {
            int bestN = 0;
            double bestErr2 = Math.abs(bestLen - targetMeters);
            double bestL2 = bestLen;
            for (int n = 1; n <= 6; n++) {
                int[] rep = repeatSeq(cyc, n);
                if (rep == null) continue;
                double l2 = g.pathLen(rep);
                double e2 = Math.abs(l2 - targetMeters);
                if (e2 < bestErr2) { bestErr2 = e2; bestL2 = l2; bestN = n; bestSeq = rep; }
            }
            if (bestN > 0) bestLen = bestL2;
        }

        // 还差就折返补齐（沿真实道路走出去再回来）
        if (bestLen < targetMeters * 0.97) {
            List<Integer> cur = new ArrayList<Integer>();
            for (int i : bestSeq) cur.add(i);
            double l2 = bestLen;
            int round = 0;
            while (l2 < targetMeters * 0.97 && round++ < 6) {
                double need = targetMeters - l2;
                boolean improved = false;
                for (int k = 1; k < cur.size(); k += Math.max(1, cur.size() / 6)) {
                    List<Integer> leg = walk(g, cur.get(k), Math.max(20, need / 2));
                    if (leg.size() < 2) continue;
                    List<Integer> cand = new ArrayList<Integer>(cur);
                    int at = k + 1;
                    for (int i = 1; i < leg.size(); i++) cand.add(at++, leg.get(i));
                    for (int i = leg.size() - 2; i >= 1; i--) cand.add(at++, leg.get(i));
                    double lc = polyLenNodes(g, cand);
                    if (Math.abs(lc - targetMeters) < Math.abs(l2 - targetMeters)) {
                        cur = cand;
                        l2 = lc;
                        improved = true;
                    }
                    if (Math.abs(l2 - targetMeters) / Math.max(1, targetMeters) < 0.03) break;
                }
                if (!improved) break;
            }
            if (Math.abs(l2 - targetMeters) < bestErr) {
                bestErr = Math.abs(l2 - targetMeters);
                List<double[]> pts = g.toPoints(toArray(cur));
                bestLen = l2;
                r.points = densify(finish(pts, loop), 2.0);
                r.nodes = dedupe(sampleByDistanceWgs(finish(pts, loop), 30.0));
                r.length = bestLen;
                r.loop = loop;
                double err = Math.abs(bestLen - targetMeters) / Math.max(1, targetMeters) * 100;
                r.note = String.format("校园路网 %.0f m / 目标 %.0f m，偏差 %.1f%%（%d 段路 · %d ms）",
                        bestLen, targetMeters, err, map.roadCount(), System.currentTimeMillis() - t0);
                return r;
            }
        }

        List<double[]> road = finish(g.toPoints(bestSeq), loop);
        r.points = densify(road, 2.0);
        r.nodes = dedupe(sampleByDistanceWgs(road, 30.0));
        r.length = bestLen;
        r.loop = loop;
        double err = Math.abs(bestLen - targetMeters) / Math.max(1, targetMeters) * 100;
        r.note = String.format("校园路网 %.0f m / 目标 %.0f m，偏差 %.1f%%（%d 段路 · %d ms）",
                bestLen, targetMeters, err, map.roadCount(), System.currentTimeMillis() - t0);
        return r;
    }

    /** 把一个路径补成闭合环（终点连回起点），失败返回 null */
    private static int[] turnIntoCycle(RoadGraph g, int[] seq) {
        if (seq.length < 3) return null;
        int s = seq[0], t = seq[seq.length - 1];
        int[] back = g.path(t, s);
        if (back == null || back.length < 2) return null;
        int[] out = new int[seq.length + back.length - 1];
        System.arraycopy(seq, 0, out, 0, seq.length);
        System.arraycopy(back, 1, out, seq.length, back.length - 1);
        return out;
    }

    /** 把闭环重复 n 遍（首尾相接，去掉接缝重复点） */
    private static int[] repeatSeq(int[] cyc, int n) {
        if (cyc.length < 3 || n < 1) return null;
        int[] out = new int[(cyc.length - 1) * n + 1];
        int at = 0;
        for (int k = 0; k < n; k++) {
            int from = (k == 0) ? 0 : 1;
            for (int i = from; i < cyc.length; i++) out[at++] = cyc[i];
        }
        return out;
    }

    private static int[] toArray(List<Integer> l) {
        int[] a = new int[l.size()];
        for (int i = 0; i < a.length; i++) a[i] = l.get(i);
        return a;
    }

    private static List<double[]> finish(List<double[]> pts, boolean loop) {
        List<double[]> out = new ArrayList<double[]>(pts);
        if (loop && out.size() > 1) out.add(new double[]{out.get(0)[0], out.get(0)[1]});
        return out;
    }

    /** 依次经过所有锚点的图上路径；任一锚点吸附失败或不通返回 null */
    private static int[] routeThrough(RoadGraph g, List<double[]> anchors) {
        List<Integer> joined = new ArrayList<Integer>();
        int prev = -1;
        for (double[] a : anchors) {
            int n = g.nearest(a[0], a[1]);
            if (n < 0) return null;
            if (prev < 0) joined.add(n);
            else {
                int[] seg = g.path(prev, n);
                if (seg == null) return null;
                for (int i = 1; i < seg.length; i++) joined.add(seg[i]);
            }
            prev = n;
        }
        return toArray(joined);
    }

    /** 随机挑一个“离当前路径有点距离”的路网节点当绕行点 */
    private static double[] pickVia(RoadGraph g, int[] path, double need, java.util.Random rnd) {
        double want = Math.max(60, Math.min(900, need * 0.35));
        double bestScore = Double.MAX_VALUE;
        int best = -1;
        for (int tries = 0; tries < 240; tries++) {
            int i = rnd.nextInt(g.size);
            double d = minDistToPath(g, path, i);
            if (d < 25) continue;                     // 就在路上，加了也没用
            double score = Math.abs(d - want);
            if (score < bestScore) { bestScore = score; best = i; }
            if (bestScore < 20) break;
        }
        if (best < 0) return null;
        return new double[]{g.pts[best][0], g.pts[best][1]};
    }

    private static double minDistToPath(RoadGraph g, int[] path, int node) {
        double best = Double.MAX_VALUE;
        for (int i = 0; i < path.length; i += 3) {
            int p = path[i];
            double d = Route.haversine(g.pts[node][0], g.pts[node][1], g.pts[p][0], g.pts[p][1]);
            if (d < best) best = d;
        }
        return best;
    }

    /** 在路径中间某个节点插入一整圈，返回新序列 */
    private static List<Integer> tryCycle(RoadGraph g, List<Integer> seq, double target) {
        int[] bestCyc = null;
        int bestAt = -1;
        double bestErr = Double.MAX_VALUE;
        double cur = polyLenNodes(g, seq);
        int step = Math.max(1, seq.size() / 12);
        for (int i = 0; i < seq.size(); i += step) {
            int[] cyc = g.shortestCycle(seq.get(i));
            if (cyc == null) continue;
            double cl = g.pathLen(cyc);
            double err = Math.abs(cur + cl - target);
            if (err < bestErr) { bestErr = err; bestCyc = cyc; bestAt = i; }
        }
        if (bestCyc == null) return null;
        List<Integer> grown = new ArrayList<Integer>(seq);
        int at = bestAt + 1;
        for (int i = 1; i < bestCyc.length; i++) grown.add(at++, bestCyc[i]);
        return grown;
    }

    /** 从 start 沿路走约 dist 米，返回顶点序列（用于折返） */
    private static List<Integer> walk(RoadGraph g, int start, double dist) {
        List<Integer> out = new ArrayList<Integer>();
        out.add(start);
        int prev = -1, cur = start;
        double acc = 0;
        int guard = 0;
        while (acc < dist && guard++ < 2000) {
            int nxt = -1;
            double bestW = -1;
            for (int k = 0; k < g.adj[cur].length; k++) {
                int v = g.adj[cur][k];
                if (v == prev) continue;
                if (g.adj[cur][k] > bestW) { bestW = g.adj[cur][k]; nxt = v; }
            }
            if (nxt < 0) break;
            acc += bestW;
            prev = cur;
            cur = nxt;
            out.add(cur);
            if (cur == start) break;
        }
        return out;
    }

    private static double polyLenNodes(RoadGraph g, List<Integer> seq) {
        double s = 0;
        for (int i = 1; i < seq.size(); i++) {
            int a = seq.get(i - 1), b = seq.get(i);
            s += Route.haversine(g.pts[a][0], g.pts[a][1], g.pts[b][0], g.pts[b][1]);
        }
        return s;
    }

    /**
     * 沿路网环绕：以起点为圆心撒一圈点、各自吸附到最近道路，按方位角排序串成闭环。
     * 这样既在真实道路上，又是一圈，不会原路折返。
     */
    private static Router.R buildRoadLoop(List<double[]> anchors, double targetMeters) {
        double oLat = anchors.get(0)[0], oLng = anchors.get(0)[1];
        List<double[]> must = new ArrayList<double[]>();
        for (int i = 1; i < anchors.size(); i++) must.add(anchors.get(i));

        Router.R best = null;
        double bestErr = Double.MAX_VALUE;
        long t0 = System.currentTimeMillis();
        double lo = 40, hi = Math.max(200, targetMeters);

        for (int iter = 0; iter < 5; iter++) {
            if (System.currentTimeMillis() - t0 > 15000) break;
            double R = (lo + hi) / 2;
            Router.R rr = ringRoute(oLat, oLng, must, R);
            if (rr == null || !rr.ok) break;
            double err = Math.abs(rr.dist - targetMeters);
            if (err < bestErr) { bestErr = err; best = rr; }
            if (err / Math.max(1, targetMeters) < 0.04) break;
            if (rr.dist < targetMeters) lo = R; else hi = R;
        }
        return best;
    }

    /** 闭环几何重复 n 圈（首尾都是起点，直接拼接即可） */
    private static List<double[]> repeatPath(List<double[]> pts, int laps) {
        List<double[]> out = new ArrayList<double[]>();
        if (laps <= 1) return new ArrayList<double[]>(pts);
        for (int i = 0; i < laps; i++) {
            for (int j = 0; j < pts.size(); j++) {
                if (i > 0 && j == 0) continue;   // 接缝处去重
                out.add(pts.get(j));
            }
        }
        return out;
    }

    /** 以 (oLat,oLng) 为圆心、半径 R 撒 6 个点吸附到道路，按方位角串成一圈 */
    private static Router.R ringRoute(double oLat, double oLng, List<double[]> must, double R) {
        double kx = 111320.0 * Math.cos(Math.toRadians(oLat));
        double ky = 110540.0;
        int k = 6;
        List<double[]> ring = new ArrayList<double[]>();
        for (int i = 0; i < k; i++) {
            double ang = 2 * Math.PI * i / k;
            double lat = oLat + Math.cos(ang) * R / ky;
            double lng = oLng + Math.sin(ang) * R / kx;
            double[] snap = Router.nearest(lat, lng);
            if (snap != null) ring.add(snap);
        }
        if (ring.size() < 4) return null;

        List<double[]> all = new ArrayList<double[]>(ring);
        all.addAll(must);
        final double fLat = oLat, fLng = oLng;
        java.util.Collections.sort(all, new java.util.Comparator<double[]>() {
            public int compare(double[] a, double[] b) {
                double aa = Math.atan2(a[1] - fLng, a[0] - fLat);
                double bb = Math.atan2(b[1] - fLng, b[0] - fLat);
                return Double.compare(aa, bb);
            }
        });

        List<double[]> seq = new ArrayList<double[]>();
        seq.add(new double[]{oLat, oLng});
        seq.addAll(all);
        seq.add(new double[]{oLat, oLng});
        return Router.route(seq);
    }

    /** 点到当前几何的最小距离（米）：用来避免绕行点吸附回原路 */
    private static double minDistToPath(List<double[]> pts, double[] p) {
        double best = Double.MAX_VALUE;
        for (double[] q : pts) {
            double d = Route.haversine(p[0], p[1], q[0], q[1]);
            if (d < best) best = d;
            if (best < 1) return 0;
        }
        return best;
    }

    /** WGS 折线按距离重采样 */
    private static List<double[]> sampleByDistanceWgs(List<double[]> pts, double step) {
        List<double[]> out = new ArrayList<double[]>();
        if (pts.isEmpty()) return out;
        out.add(pts.get(0));
        double acc = 0;
        for (int i = 1; i < pts.size(); i++) {
            acc += Route.haversine(pts.get(i - 1)[0], pts.get(i - 1)[1], pts.get(i)[0], pts.get(i)[1]);
            if (acc >= step || i == pts.size() - 1) {
                out.add(pts.get(i));
                acc = 0;
            }
        }
        return out;
    }

    /** 每段插入 2 个控制点：A → P1 → P2 → B */
    private static List<double[]> insertDetours(List<double[]> m, double skeleton, double extra,
                                                double scale, Random rnd) {
        List<double[]> out = new ArrayList<double[]>();
        out.add(m.get(0));
        for (int i = 0; i < m.size() - 1; i++) {
            double[] a = m.get(i), b = m.get(i + 1);
            double d = dist(a, b);
            double need = skeleton > 0 ? (d / skeleton) * extra * scale : 0;
            int sign = (i % 2 == 0) ? 1 : -1;
            if (rnd.nextBoolean()) sign = -sign;
            double h = solveH(d, need);
            double ux = (b[0] - a[0]) / Math.max(1e-6, d);
            double uy = (b[1] - a[1]) / Math.max(1e-6, d);
            double nx = -uy * h * sign, ny = ux * h * sign;
            out.add(new double[]{a[0] + ux * d / 3 + nx, a[1] + uy * d / 3 + ny});
            out.add(new double[]{a[0] + ux * 2 * d / 3 + nx, a[1] + uy * 2 * d / 3 + ny});
            out.add(b);
        }
        return out;
    }

    /** 解出垂直鼓出高度 h，使该段长度 = d + extra */
    private static double solveH(double d, double extra) {
        if (extra <= 0.5 || d < 1) return 0;
        double lo = 0, hi = Math.max(60, d * 3 + extra);
        for (int i = 0; i < 44; i++) {
            double mid = (lo + hi) / 2;
            double add = 2 * Math.hypot(d / 3, mid) + d / 3 - d;
            if (add < extra) lo = mid; else hi = mid;
        }
        return (lo + hi) / 2;
    }

    /** 点到骨架折线的最近距离（判断绕行幅度） */
    private static double deviation(List<double[]> m, double[] p) {
        double best = Double.MAX_VALUE;
        for (int i = 1; i < m.size(); i++) {
            double[] a = m.get(i - 1), b = m.get(i);
            double vx = b[0] - a[0], vy = b[1] - a[1];
            double len2 = vx * vx + vy * vy;
            double t = len2 > 1e-9 ? ((p[0] - a[0]) * vx + (p[1] - a[1]) * vy) / len2 : 0;
            t = Math.max(0, Math.min(1, t));
            best = Math.min(best, Math.hypot(p[0] - (a[0] + vx * t), p[1] - (a[1] + vy * t)));
        }
        return best;
    }

    private static List<double[]> chaikin(List<double[]> p, int iters) {
        List<double[]> cur = p;
        for (int k = 0; k < iters; k++) {
            if (cur.size() < 3) return cur;
            List<double[]> nx = new ArrayList<double[]>();
            nx.add(cur.get(0));
            for (int i = 0; i < cur.size() - 1; i++) {
                double[] a = cur.get(i), b = cur.get(i + 1);
                nx.add(new double[]{a[0] * 0.75 + b[0] * 0.25, a[1] * 0.75 + b[1] * 0.25});
                nx.add(new double[]{a[0] * 0.25 + b[0] * 0.75, a[1] * 0.25 + b[1] * 0.75});
            }
            nx.add(cur.get(cur.size() - 1));
            cur = nx;
        }
        return cur;
    }

    private static List<double[]> densify(List<double[]> pts, double step) {
        List<double[]> out = new ArrayList<double[]>();
        if (pts.isEmpty()) return out;
        out.add(pts.get(0));
        for (int i = 1; i < pts.size(); i++) {
            double[] a = pts.get(i - 1), b = pts.get(i);
            double d = Route.haversine(a[0], a[1], b[0], b[1]);
            int n = Math.max(1, (int) Math.ceil(d / step));
            for (int k = 1; k <= n; k++) {
                double f = (double) k / n;
                out.add(new double[]{a[0] + (b[0] - a[0]) * f, a[1] + (b[1] - a[1]) * f});
            }
        }
        return out;
    }

    private static List<double[]> toWgs(List<double[]> m, double lat0, double lng0, double klng) {
        List<double[]> out = new ArrayList<double[]>();
        for (double[] p : m) out.add(new double[]{lat0 + p[1] / KLAT, lng0 + p[0] / klng});
        return out;
    }

    /** 去掉相邻重复点（分段接缝处会重复一次），否则模块里会出现 0 长度段 */
    private static List<double[]> dedupe(List<double[]> pts) {
        List<double[]> out = new ArrayList<double[]>();
        for (double[] p : pts) {
            if (!out.isEmpty()) {
                double[] last = out.get(out.size() - 1);
                if (Math.abs(last[0] - p[0]) < 1e-7 && Math.abs(last[1] - p[1]) < 1e-7) continue;
            }
            out.add(p);
        }
        return out;
    }

    /** 按距离重采样（米制折线），用于把平滑后的曲线变成可编辑的关键点 */
    private static List<double[]> sampleByDistance(List<double[]> pts, double step) {
        List<double[]> out = new ArrayList<double[]>();
        if (pts.isEmpty()) return out;
        out.add(pts.get(0));
        double acc = 0;
        for (int i = 1; i < pts.size(); i++) {
            acc += dist(pts.get(i - 1), pts.get(i));
            if (acc >= step || i == pts.size() - 1) {
                out.add(pts.get(i));
                acc = 0;
            }
        }
        return out;
    }

    private static double dist(double[] a, double[] b) { return Math.hypot(a[0] - b[0], a[1] - b[1]); }

    private static double polyLen(List<double[]> m) {
        double s = 0;
        for (int i = 1; i < m.size(); i++) s += dist(m.get(i - 1), m.get(i));
        return s;
    }

    private AutoRoute() { }
}
