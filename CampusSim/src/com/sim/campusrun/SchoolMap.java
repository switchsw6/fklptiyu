package com.sim.campusrun;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 校园地图：跑道路网 + 跑区多边形 + 打卡点。坐标统一 WGS-84。
 *
 * JSON:
 * { "school":"...", "id":"...", "version":1, "coord":"wgs84",
 *   "zone":[[lat,lng],...],
 *   "roads":[{"name":"环湖跑道","points":[[lat,lng],...]}],
 *   "checkpoints":[{"name":"东门","lat":..,"lng":..,"radius":25}] }
 */
final class SchoolMap {

    String school = "";
    String id = "";
    int version = 1;
    final List<String> roadNames = new ArrayList<String>();
    final List<List<double[]>> roads = new ArrayList<List<double[]>>();
    final List<double[]> zone = new ArrayList<double[]>();
    final List<double[]> checkpoints = new ArrayList<double[]>();

    boolean hasRoads() {
        for (List<double[]> r : roads) if (r.size() >= 2) return true;
        return false;
    }

    int roadCount() {
        int n = 0;
        for (List<double[]> r : roads) if (r.size() >= 2) n++;
        return n;
    }

    double totalLength() {
        double s = 0;
        for (List<double[]> r : roads) {
            for (int i = 1; i < r.size(); i++) {
                s += Route.haversine(r.get(i - 1)[0], r.get(i - 1)[1], r.get(i)[0], r.get(i)[1]);
            }
        }
        return s;
    }

    /** 点是否在跑区内（无跑区则视为在区内） */
    boolean inZone(double lat, double lng) {
        if (zone.size() < 3) return true;
        int n = zone.size();
        boolean inside = false;
        for (int i = 0, j = n - 1; i < n; j = i++) {
            double yi = zone.get(i)[0], xi = zone.get(i)[1];
            double yj = zone.get(j)[0], xj = zone.get(j)[1];
            if (((yi > lat) != (yj > lat)) && (lng < (xj - xi) * (lat - yi) / (yj - yi + 1e-12) + xi)) {
                inside = !inside;
            }
        }
        return inside;
    }

    // ---------------------------------------------------------------- 序列化
    String toJson() {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n  \"school\": \"").append(esc(school)).append("\",\n");
        sb.append("  \"id\": \"").append(esc(id)).append("\",\n");
        sb.append("  \"version\": ").append(version).append(",\n");
        sb.append("  \"coord\": \"wgs84\",\n");
        sb.append("  \"zone\": [");
        for (int i = 0; i < zone.size(); i++) {
            sb.append(String.format("%s[%.6f,%.6f]", i == 0 ? "" : ",", zone.get(i)[0], zone.get(i)[1]));
        }
        sb.append("],\n  \"roads\": [");
        boolean first = true;
        for (int i = 0; i < roads.size(); i++) {
            List<double[]> r = roads.get(i);
            if (r.size() < 2) continue;
            if (!first) sb.append(",");
            first = false;
            sb.append("\n    {\"name\": \"").append(esc(i < roadNames.size() ? roadNames.get(i) : ("道路" + (i + 1))))
                    .append("\", \"points\": [");
            for (int k = 0; k < r.size(); k++) {
                sb.append(String.format("%s[%.6f,%.6f]", k == 0 ? "" : ",", r.get(k)[0], r.get(k)[1]));
            }
            sb.append("]}");
        }
        sb.append("\n  ],\n  \"checkpoints\": [");
        for (int i = 0; i < checkpoints.size(); i++) {
            sb.append(String.format("%s[%.6f,%.6f]", i == 0 ? "" : ",", checkpoints.get(i)[0], checkpoints.get(i)[1]));
        }
        sb.append("]\n}\n");
        return sb.toString();
    }

    static SchoolMap fromJson(String s) {
        SchoolMap m = new SchoolMap();
        try {
            Map<String, Object> o = Json.obj(Json.parse(s));
            m.school = Json.str(o, "school", "");
            m.id = Json.str(o, "id", "");
            m.version = (int) Json.num(o, "version", 1);
            for (Object p : Json.arr(o.get("zone"))) m.zone.add(pt(p));
            for (Object r : Json.arr(o.get("roads"))) {
                Map<String, Object> ro = Json.obj(r);
                String name = Json.str(ro, "name", "道路");
                List<double[]> pts = new ArrayList<double[]>();
                for (Object p : Json.arr(ro.get("points"))) pts.add(pt(p));
                if (pts.size() >= 2) {
                    m.roads.add(pts);
                    m.roadNames.add(name);
                }
            }
            for (Object p : Json.arr(o.get("checkpoints"))) m.checkpoints.add(pt(p));
        } catch (Throwable ignored) { }
        return m;
    }

    private static double[] pt(Object o) {
        List<Object> a = Json.arr(o);
        return new double[]{((Number) a.get(0)).doubleValue(), ((Number) a.get(1)).doubleValue()};
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    // ---------------------------------------------------------------- 文件
    File fileFor(File dir) {
        String name = (id == null || id.isEmpty()) ? "school_map.json" : ("school_" + id + ".json");
        return new File(dir, name);
    }

    boolean save(File dir) {
        try {
            if (!dir.exists()) dir.mkdirs();
            File f = fileFor(dir);
            FileOutputStream os = new FileOutputStream(f);
            os.write(toJson().getBytes("UTF-8"));
            os.close();
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    static SchoolMap load(File f) {
        try {
            if (!f.exists() || f.length() < 8) return null;
            byte[] b = new byte[(int) f.length()];
            FileInputStream in = new FileInputStream(f);
            int n = in.read(b);
            in.close();
            if (n <= 0) return null;
            return fromJson(new String(b, 0, n, "UTF-8"));
        } catch (Throwable t) {
            return null;
        }
    }
}
