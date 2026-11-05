package com.sim.campusrun;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * 把 SchoolMap 的跑道路路折线采样成图，提供 A* 最短路与“绕圈补里程”。
 *
 * 顶点：折线按 step 米重采样（默认 5 m），相距 < mergeM（默认 4 m）的顶点合并，
 *       路口自然连通；跑区外的点直接丢掉。
 */
final class RoadGraph {

    double[][] pts;                 // WGS [lat,lng]
    int[][] adj;                    // 邻接顶点
    double[][] adjW;                // 边权（米）
    int size;

    private RoadGraph() { }

    static RoadGraph build(SchoolMap map, double step, double mergeM) {
        RoadGraph g = new RoadGraph();
        List<double[]> vs = new ArrayList<double[]>();
        List<List<Integer>> nb = new ArrayList<List<Integer>>();
        Map<String, Integer> index = new HashMap<String, Integer>();

        for (List<double[]> road : map.roads) {
            if (road.size() < 2) continue;
            List<double[]> samp = sample(road, step);
            int prev = -1;
            for (double[] p : samp) {
                if (!map.inZone(p[0], p[1])) { prev = -1; continue; }
                int id = nodeFor(vs, nb, index, p, mergeM);
                if (prev >= 0 && prev != id) {
                    link(nb, prev, id);
                }
                prev = id;
            }
        }

        g.size = vs.size();
        g.pts = vs.toArray(new double[0][]);
        g.adj = new int[g.size][];
        g.adjW = new double[g.size][];
        for (int i = 0; i < g.size; i++) {
            List<Integer> l = nb.get(i);
            g.adj[i] = new int[l.size()];
            g.adjW[i] = new double[l.size()];
            for (int k = 0; k < l.size(); k++) {
                int j = l.get(k);
                g.adj[i][k] = j;
                g.adjW[i][k] = Route.haversine(g.pts[i][0], g.pts[i][1], g.pts[j][0], g.pts[j][1]);
            }
        }
        return g;
    }

    private static double[] a(List<double[]> vs, int i) { return vs.get(i); }

    private static int nodeFor(List<double[]> vs, List<List<Integer>> nb, Map<String, Integer> index,
                               double[] p, double mergeM) {
        String key = key(p);
        Integer hit = index.get(key);
        if (hit != null) return hit;
        // 邻近合并：只查同格与相邻格
        int best = -1;
        double bestD = mergeM;
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                String k2 = key(p[0] + dy * 1e-5, p[1] + dx * 1e-5);
                Integer cand = index.get(k2);
                if (cand == null) continue;
                double d = Route.haversine(p[0], p[1], vs.get(cand)[0], vs.get(cand)[1]);
                if (d < bestD) { bestD = d; best = cand; }
            }
        }
        if (best >= 0) return best;
        vs.add(p);
        nb.add(new ArrayList<Integer>());
        index.put(key, vs.size() - 1);
        return vs.size() - 1;
    }

    private static String key(double[] p) { return key(p[0], p[1]); }

    private static String key(double lat, double lng) {
        return Math.round(lat * 100000) + "," + Math.round(lng * 100000);
    }

    private static void link(List<List<Integer>> nb, int a, int b) {
        if (!nb.get(a).contains(b)) nb.get(a).add(b);
        if (!nb.get(b).contains(a)) nb.get(b).add(a);
    }

    /** 折线按 step 米重采样（保留首尾） */
    static List<double[]> sample(List<double[]> line, double step) {
        List<double[]> out = new ArrayList<double[]>();
        out.add(line.get(0));
        double acc = 0;
        for (int i = 1; i < line.size(); i++) {
            double[] a = line.get(i - 1), b = line.get(i);
            double d = Route.haversine(a[0], a[1], b[0], b[1]);
            if (d < 1e-6) continue;
            acc += d;
            while (acc >= step) {
                double back = (acc - step) / d;   // 从 b 往回
                double lat = b[0] + (a[0] - b[0]) * back;
                double lng = b[1] + (a[1] - b[1]) * back;
                out.add(new double[]{lat, lng});
                acc -= step;
            }
        }
        double[] last = line.get(line.size() - 1);
        double[] tail = out.get(out.size() - 1);
        if (Route.haversine(tail[0], tail[1], last[0], last[1]) > 0.5) out.add(last);
        return out;
    }

    int nearest(double lat, double lng) {
        int best = -1;
        double bd = Double.MAX_VALUE;
        for (int i = 0; i < size; i++) {
            double d = Route.haversine(lat, lng, pts[i][0], pts[i][1]);
            if (d < bd) { bd = d; best = i; }
        }
        return best;
    }

    /** Dijkstra/A*：返回顶点序列（含首尾），不通返回 null */
    int[] path(int s, int t) {
        if (s < 0 || t < 0 || s >= size || t >= size) return null;
        double[] dist = new double[size];
        int[] prev = new int[size];
        Arrays.fill(dist, Double.MAX_VALUE);
        Arrays.fill(prev, -1);
        PriorityQueue<double[]> pq = new PriorityQueue<double[]>(16, new java.util.Comparator<double[]>() {
            public int compare(double[] a, double[] b) { return Double.compare(a[0], b[0]); }
        });
        dist[s] = 0;
        pq.add(new double[]{0, s});
        while (!pq.isEmpty()) {
            double[] cur = pq.poll();
            int u = (int) cur[1];
            if (cur[0] > dist[u] + 1e-9) continue;
            if (u == t) break;
            for (int k = 0; k < adj[u].length; k++) {
                int v = adj[u][k];
                double nd = dist[u] + adjW[u][k];
                if (nd < dist[v] - 1e-9) {
                    dist[v] = nd;
                    prev[v] = u;
                    pq.add(new double[]{nd, v});
                }
            }
        }
        if (dist[t] == Double.MAX_VALUE) return null;
        List<Integer> rev = new ArrayList<Integer>();
        for (int v = t; v != -1; v = prev[v]) rev.add(v);
        int[] out = new int[rev.size()];
        for (int i = 0; i < out.length; i++) out[i] = rev.get(out.length - 1 - i);
        return out;
    }

    double pathLen(int[] p) {
        double s = 0;
        for (int i = 1; i < p.length; i++) {
            s += Route.haversine(pts[p[i - 1]][0], pts[p[i - 1]][1], pts[p[i]][0], pts[p[i]][1]);
        }
        return s;
    }

    /** 从 v 出发找一条最短的闭合环（v→n1→…→n2→v），失败返回 null */
    int[] shortestCycle(int v) {
        int[] best = null;
        double bestLen = Double.MAX_VALUE;
        for (int i = 0; i < adj[v].length; i++) {
            for (int j = i + 1; j < adj[v].length; j++) {
                int a = adj[v][i], b = adj[v][j];
                int[] mid = pathAvoiding(a, b, v);
                if (mid == null) continue;
                double len = adjW[v][i] + adjW[v][j];
                for (int k = 1; k < mid.length; k++) {
                    len += Route.haversine(pts[mid[k - 1]][0], pts[mid[k - 1]][1], pts[mid[k]][0], pts[mid[k]][1]);
                }
                if (len < bestLen) {
                    bestLen = len;
                    int[] cyc = new int[mid.length + 2];
                    cyc[0] = v;
                    System.arraycopy(mid, 0, cyc, 1, mid.length);
                    cyc[cyc.length - 1] = v;
                    best = cyc;
                }
            }
        }
        return best;
    }

    private int[] pathAvoiding(int s, int t, int ban) {
        double[] dist = new double[size];
        int[] prev = new int[size];
        Arrays.fill(dist, Double.MAX_VALUE);
        Arrays.fill(prev, -1);
        PriorityQueue<double[]> pq = new PriorityQueue<double[]>(16, new java.util.Comparator<double[]>() {
            public int compare(double[] a, double[] b) { return Double.compare(a[0], b[0]); }
        });
        dist[s] = 0;
        pq.add(new double[]{0, s});
        while (!pq.isEmpty()) {
            double[] cur = pq.poll();
            int u = (int) cur[1];
            if (cur[0] > dist[u] + 1e-9) continue;
            if (u == t) break;
            for (int k = 0; k < adj[u].length; k++) {
                int v = adj[u][k];
                if (v == ban) continue;
                double nd = dist[u] + adjW[u][k];
                if (nd < dist[v] - 1e-9) {
                    dist[v] = nd;
                    prev[v] = u;
                    pq.add(new double[]{nd, v});
                }
            }
        }
        if (dist[t] == Double.MAX_VALUE) return null;
        List<Integer> rev = new ArrayList<Integer>();
        for (int v = t; v != -1; v = prev[v]) rev.add(v);
        int[] out = new int[rev.size()];
        for (int i = 0; i < out.length; i++) out[i] = rev.get(out.length - 1 - i);
        return out;
    }

    /** 顶点序列 -> WGS 折线 */
    List<double[]> toPoints(int[] seq) {
        List<double[]> out = new ArrayList<double[]>();
        for (int i : seq) out.add(new double[]{pts[i][0], pts[i][1]});
        return out;
    }
}
