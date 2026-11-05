package com.sim.campusrun;

/** WGS-84 <-> GCJ-02（高德瓦片用 GCJ，配置里统一存 WGS） */
final class Gps {
    private static final double A = 6378245.0;
    private static final double EE = 0.00669342162296594323;

    static boolean outOfChina(double lat, double lng) {
        return lng < 72.004 || lng > 137.8347 || lat < 0.8293 || lat > 55.8271;
    }

    private static double tLat(double x, double y) {
        double r = -100 + 2 * x + 3 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * Math.sqrt(Math.abs(x));
        r += (20 * Math.sin(6 * x * Math.PI) + 20 * Math.sin(2 * x * Math.PI)) * 2 / 3;
        r += (20 * Math.sin(y * Math.PI) + 40 * Math.sin(y / 3 * Math.PI)) * 2 / 3;
        r += (160 * Math.sin(y / 12 * Math.PI) + 320 * Math.sin(y * Math.PI / 30)) * 2 / 3;
        return r;
    }

    private static double tLng(double x, double y) {
        double r = 300 + x + 2 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * Math.sqrt(Math.abs(x));
        r += (20 * Math.sin(6 * x * Math.PI) + 20 * Math.sin(2 * x * Math.PI)) * 2 / 3;
        r += (20 * Math.sin(x * Math.PI) + 40 * Math.sin(x / 3 * Math.PI)) * 2 / 3;
        r += (150 * Math.sin(x / 12 * Math.PI) + 300 * Math.sin(x / 30 * Math.PI)) * 2 / 3;
        return r;
    }

    /** WGS -> GCJ */
    static double[] gcj(double lat, double lng) {
        if (outOfChina(lat, lng)) return new double[]{lat, lng};
        double dLat = tLat(lng - 105, lat - 35), dLng = tLng(lng - 105, lat - 35);
        double radLat = lat / 180 * Math.PI;
        double magic = Math.sin(radLat);
        magic = 1 - EE * magic * magic;
        double sq = Math.sqrt(magic);
        dLat = (dLat * 180) / ((A * (1 - EE)) / (magic * sq) * Math.PI);
        dLng = (dLng * 180) / (A / sq * Math.cos(radLat) * Math.PI);
        return new double[]{lat + dLat, lng + dLng};
    }

    /** GCJ -> WGS（迭代反解） */
    static double[] wgs(double lat, double lng) {
        double wLat = lat, wLng = lng;
        for (int i = 0; i < 6; i++) {
            double[] g = gcj(wLat, wLng);
            wLat += lat - g[0];
            wLng += lng - g[1];
        }
        return new double[]{wLat, wLng};
    }

    private Gps() { }
}
