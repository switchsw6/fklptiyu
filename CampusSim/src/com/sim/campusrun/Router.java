package com.sim.campusrun;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 路网寻路（OSRM HTTP API，foot profile）。
 *
 * 用公开实例路由：默认 routing.openstreetmap.de/routed-foot，可在配置里换成自建 OSRM / GraphHopper。
 * 返回的是真实道路几何（GeoJSON coordinates = [lng,lat]），坐标一律 WGS-84。
 */
final class Router {

    static volatile String base = "https://routing.openstreetmap.de/routed-foot";
    static final int TIMEOUT_MS = 9000;

    static final class R {
        List<double[]> pts = new ArrayList<double[]>();   // WGS [lat,lng]
        double dist = 0;                                  // 米
        double duration = 0;
        boolean ok = false;
        String err = "";
    }

    /** 依次经过 waypoints（WGS [lat,lng]），返回整条道路几何 */
    static R route(List<double[]> waypoints) {
        R out = new R();
        if (waypoints == null || waypoints.size() < 2) {
            out.err = "至少两个点";
            return out;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < waypoints.size(); i++) {
            if (i > 0) sb.append(';');
            sb.append(trim(waypoints.get(i)[1])).append(',').append(trim(waypoints.get(i)[0]));
        }
        String url = base + "/route/v1/foot/" + sb + "?overview=full&geometries=geojson&steps=false";
        String body = get(url);
        if (body == null) {
            out.err = "网络不可用";
            return out;
        }
        try {
            Map<String, Object> j = Json.obj(Json.parse(body));
            String code = Json.str(j, "code", "");
            if (!"Ok".equals(code)) {
                out.err = "OSRM " + code;
                return out;
            }
            List<Object> routes = Json.arr(j.get("routes"));
            if (routes.isEmpty()) {
                out.err = "无路线";
                return out;
            }
            Map<String, Object> r0 = Json.obj(routes.get(0));
            out.dist = Json.num(r0, "distance", 0);
            out.duration = Json.num(r0, "duration", 0);
            Map<String, Object> geo = Json.obj(r0.get("geometry"));
            for (Object o : Json.arr(geo.get("coordinates"))) {
                List<Object> c = Json.arr(o);
                if (c.size() < 2) continue;
                double lng = ((Number) c.get(0)).doubleValue();
                double lat = ((Number) c.get(1)).doubleValue();
                out.pts.add(new double[]{lat, lng});
            }
            out.ok = out.pts.size() >= 2;
            if (!out.ok) out.err = "几何为空";
        } catch (Throwable t) {
            out.err = "解析失败: " + t;
        }
        return out;
    }

    /** 把一个任意坐标吸附到最近的道路上，返回 WGS [lat,lng]；失败返回 null */
    static double[] nearest(double lat, double lng) {
        String url = base + "/nearest/v1/foot/" + trim(lng) + "," + trim(lat);
        String body = get(url);
        if (body == null) return null;
        try {
            Map<String, Object> j = Json.obj(Json.parse(body));
            List<Object> wps = Json.arr(j.get("waypoints"));
            if (wps.isEmpty()) return null;
            Map<String, Object> w = Json.obj(wps.get(0));
            List<Object> loc = Json.arr(w.get("location"));
            if (loc.size() < 2) return null;
            return new double[]{((Number) loc.get(1)).doubleValue(), ((Number) loc.get(0)).doubleValue()};
        } catch (Throwable t) {
            return null;
        }
    }

    private static String trim(double v) {
        return String.format(java.util.Locale.US, "%.6f", v);
    }

    private static String get(String urlStr) {
        HttpURLConnection c = null;
        try {
            URL u = new URL(urlStr);
            c = (HttpURLConnection) u.openConnection();
            c.setConnectTimeout(TIMEOUT_MS);
            c.setReadTimeout(TIMEOUT_MS);
            c.setRequestProperty("User-Agent", "CampusSim/1.8");
            int code = c.getResponseCode();
            InputStream in = (code >= 200 && code < 300) ? c.getInputStream() : c.getErrorStream();
            if (in == null) return null;
            BufferedReader br = new BufferedReader(new InputStreamReader(in, "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            br.close();
            return sb.toString();
        } catch (Throwable t) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private Router() { }
}
