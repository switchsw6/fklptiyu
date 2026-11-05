package com.sim.campusrun;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;

/**
 * 地名搜索：
 *   1) 高德网页版输入提示（免 key，国内直连可用）—— 返回 GCJ 坐标
 *   2) 用户填了高德 Web 服务 key 时走官方地理编码
 *   3) 系统 Geocoder
 *   4) 内置离线地名表（兜底，断网也能搜到主要城市/高校）
 *   5) Nominatim（最后兜底）
 * 结果统一转成 WGS-84（内部与配置都用 WGS）。
 */
final class Geo {

    static final class Item {
        String name = "";
        String addr = "";
        double latWgs;
        double lngWgs;

        String label() {
            if (addr == null || addr.isEmpty()) return name;
            return name + "  ·  " + addr;
        }
    }

    /** 依次尝试各数据源，返回前 n 条 */
    static List<Item> search(String q, String amapKey, int n) {
        List<Item> out = new ArrayList<Item>();
        if (q == null || q.trim().isEmpty()) return out;
        q = q.trim();

        amapTips(q, out);
        if (out.isEmpty() && amapKey != null && amapKey.length() > 8) amapGeocode(q, amapKey, out);
        if (out.isEmpty()) systemGeocoder(q, out);
        if (out.isEmpty()) offline(q, out);
        if (out.isEmpty()) nominatim(q, out);

        rank(q, out);
        if (out.size() > n) return new ArrayList<Item>(out.subList(0, n));
        return out;
    }


    /** 简单排序：名字以查询词开头、且是学校/校区/公园的排前面，避免"南开"第一条是商铺 */
    private static void rank(final String q, List<Item> out) {
        try {
            java.util.Collections.sort(out, new java.util.Comparator<Item>() {
                public int compare(Item a, Item b) {
                    return score(b) - score(a);
                }

                private int score(Item it) {
                    int s = 0;
                    String nm = it.name == null ? "" : it.name;
                    if (nm.startsWith(q)) s += 40;
                    else if (nm.contains(q)) s += 20;
                    if (nm.contains("大学")) s += 30;
                    if (nm.contains("学院")) s += 18;
                    if (nm.contains("校区")) s += 12;
                    if (nm.contains("中学") || nm.contains("小学")) s -= 10;
                    if (nm.contains("店") || nm.contains("酒店") || nm.contains("餐厅")) s -= 25;
                    return s;
                }
            });
        } catch (Throwable ignored) { }
    }

    // ------------------------------------------------------------ 高德输入提示
    private static void amapTips(String q, List<Item> out) {
        try {
            String url = "https://www.amap.com/service/poiTips?words=" + URLEncoder.encode(q, "UTF-8")
                    + "&city=&type=&spm=&csid=&csfp=&clientkey=&sceneId=&methodName=";
            String body = httpGet(url, 6000);
            java.util.Map<String, Object> root = Json.obj(Json.parse(body));
            java.util.Map<String, Object> data = Json.obj(root.get("data"));
            for (Object o : Json.arr(data.get("tip_list"))) {
                java.util.Map<String, Object> tip = Json.obj(Json.obj(o).get("tip"));
                String name = Json.str(tip, "name", "");
                double lng = Json.num(tip, "x", Double.NaN);
                double lat = Json.num(tip, "y", Double.NaN);
                if (name.isEmpty() || Double.isNaN(lat) || Double.isNaN(lng)) continue;
                Item it = new Item();
                it.name = name;
                String dist = Json.str(tip, "district", "");
                String addr = Json.str(tip, "address", "");
                it.addr = dist + (addr.isEmpty() ? "" : (" " + addr));
                double[] w = Gps.wgs(lat, lng);   // 高德给的是 GCJ
                it.latWgs = w[0];
                it.lngWgs = w[1];
                out.add(it);
            }
            if (!out.isEmpty()) Log.i("geo: amapTips " + out.size() + " hits for " + q);
        } catch (Throwable t) {
            Log.w("geo amapTips: " + t);
        }
    }

    // ------------------------------------------------------------ 高德官方地理编码
    private static void amapGeocode(String q, String key, List<Item> out) {
        try {
            String url = "https://restapi.amap.com/v3/geocode/geo?output=json&key=" + key
                    + "&address=" + URLEncoder.encode(q, "UTF-8");
            java.util.Map<String, Object> m = Json.obj(Json.parse(httpGet(url, 6000)));
            for (Object o : Json.arr(m.get("geocodes"))) {
                java.util.Map<String, Object> g = Json.obj(o);
                String loc = Json.str(g, "location", "");
                int c = loc.indexOf(',');
                if (c <= 0) continue;
                double lng = Double.parseDouble(loc.substring(0, c));
                double lat = Double.parseDouble(loc.substring(c + 1));
                Item it = new Item();
                it.name = Json.str(g, "formatted_address", q);
                it.addr = Json.str(g, "level", "");
                double[] w = Gps.wgs(lat, lng);
                it.latWgs = w[0];
                it.lngWgs = w[1];
                out.add(it);
            }
        } catch (Throwable t) {
            Log.w("geo amap: " + t);
        }
    }

    // ------------------------------------------------------------ 系统 Geocoder
    private static void systemGeocoder(String q, List<Item> out) {
        try {
            android.location.Geocoder g = new android.location.Geocoder(
                    com.sim.campusrun.App.ctx(), java.util.Locale.CHINA);
            List<android.location.Address> as = g.getFromLocationName(q, 6);
            if (as == null) return;
            for (android.location.Address a : as) {
                if (!a.hasLatitude() || !a.hasLongitude()) continue;
                Item it = new Item();
                StringBuilder nm = new StringBuilder();
                for (int i = 0; i <= a.getMaxAddressLineIndex(); i++) {
                    String l = a.getAddressLine(i);
                    if (l != null && !l.isEmpty()) nm.append(nm.length() > 0 ? " " : "").append(l);
                }
                it.name = a.getFeatureName() != null ? a.getFeatureName() : nm.toString();
                it.addr = nm.toString();
                it.latWgs = a.getLatitude();
                it.lngWgs = a.getLongitude();
                out.add(it);
            }
        } catch (Throwable t) {
            Log.w("geo geocoder: " + t);
        }
    }

    // ------------------------------------------------------------ 离线表
    private static void offline(String q, List<Item> out) {
        for (String[] e : OfflinePlaces.ALL) {
            if (e[0].contains(q) || q.contains(e[0])) {
                Item it = new Item();
                it.name = e[0];
                it.addr = "内置地名表";
                it.latWgs = Double.parseDouble(e[1]);
                it.lngWgs = Double.parseDouble(e[2]);
                out.add(it);
            }
        }
    }

    // ------------------------------------------------------------ Nominatim
    private static void nominatim(String q, List<Item> out) {
        try {
            String url = "https://nominatim.openstreetmap.org/search?format=json&limit=5&accept-language=zh-CN&q="
                    + URLEncoder.encode(q, "UTF-8");
            for (Object o : Json.arr(Json.parse(httpGet(url, 4000)))) {
                java.util.Map<String, Object> m = Json.obj(o);
                double lat = Json.num(m, "lat", Double.NaN);
                double lng = Json.num(m, "lon", Double.NaN);
                if (Double.isNaN(lat) || Double.isNaN(lng)) continue;
                Item it = new Item();
                it.name = Json.str(m, "display_name", q);
                it.addr = "OpenStreetMap";
                it.latWgs = lat;
                it.lngWgs = lng;
                out.add(it);
            }
        } catch (Throwable t) {
            Log.w("geo nominatim: " + t);
        }
    }

    // ------------------------------------------------------------ 工具
    static String httpGet(String url, int timeoutMs) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(timeoutMs);
        c.setReadTimeout(timeoutMs);
        c.setRequestProperty("User-Agent",
                "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36");
        c.setRequestProperty("Referer", "https://www.amap.com/");
        InputStream in = c.getInputStream();
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        in.close();
        return new String(bo.toByteArray(), "UTF-8");
    }

    private Geo() { }
}
