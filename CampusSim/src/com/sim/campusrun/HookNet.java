package com.sim.campusrun;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanSettings;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.telephony.CellIdentityLte;
import android.telephony.CellInfo;
import android.telephony.CellInfoLte;
import android.telephony.CellSignalStrengthLte;
import android.telephony.TelephonyManager;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/** 周边环境面：WiFi 扫描 / 基站 CellInfo / BLE 扫描 */
final class HookNet {
    private static final String[] AP = {"CMCC-EDU", "ChinaNet-5G", "Campus-Lib-5G", "TP-LINK_8A2C", "Redmi_2301"};
    private static final String[] BLE_MAC = {"AA:BB:CC:00:11:21", "D4:36:39:1A:2B:33", "C8:47:8C:5E:1F:44"};

    private static volatile boolean wifiLogged;
    private static volatile boolean cellLogged;

    static void install() {
        try { wifi(); } catch (Throwable t) { Log.w("wifi: " + t); }
        try { cell(); } catch (Throwable t) { Log.w("cell: " + t); }
        try { ble(); } catch (Throwable t) { Log.w("ble: " + t); }
    }

    // ---------------------------------------------------------------- WiFi
    /** 真实扫描结果的模板缓存：没有新扫描时也要能给出非空列表（乐跑会判定“没有搜索到WIFI”） */
    private static ScanResult template;

    private static void wifi() {
        XposedHelpers.findAndHookMethod(WifiManager.class, "getScanResults", new XC_MethodHook() {
            @SuppressWarnings("unchecked")
            protected void afterHookedMethod(MethodHookParam p) {
                if (!Cfg.enabled || !Cfg.wifi) return;
                List<ScanResult> real = (List<ScanResult>) p.getResult();
                if (real != null && !real.isEmpty()) template = real.get(0);
                double[] fix = State.lastFix;
                if (fix == null) return;
                int n = Math.max(1, Cfg.wifiNow());
                List<ScanResult> out = new ArrayList<ScanResult>();
                for (int i = 0; i < n; i++) {
                    ScanResult s = null;
                    if (real != null && i < real.size()) s = clone2(real.get(i));
                    else if (template != null) s = clone2(template);
                    if (s == null) continue;
                    s.SSID = AP[i % AP.length];
                    s.BSSID = bssid(fix[0], fix[1], i);
                    s.level = -52 - i * 8 - (int) (Math.random() * 3);
                    s.frequency = (i % 3 == 0) ? 5180 + i * 5 : 2412 + i * 5;
                    s.capabilities = "[WPA2-PSK-CCMP][ESS]";
                    s.timestamp = System.nanoTime();
                    out.add(s);
                }
                if (!out.isEmpty()) {
                    p.setResult(out);
                    if (!wifiLogged) { wifiLogged = true; Log.i("wifi scan -> " + out.size() + " APs"); }
                } else Log.w("wifi: 无真实扫描模板，无法伪造 AP 列表");
            }
        });
        XposedHelpers.findAndHookMethod(WifiInfo.class, "getSSID", new XC_MethodHook() {
            protected void afterHookedMethod(MethodHookParam p) {
                if (Cfg.enabled && Cfg.wifi) p.setResult("\"" + AP[0] + "\"");
            }
        });
        XposedHelpers.findAndHookMethod(WifiInfo.class, "getBSSID", new XC_MethodHook() {
            protected void afterHookedMethod(MethodHookParam p) {
                double[] fix = State.lastFix;
                if (Cfg.enabled && Cfg.wifi && fix != null) p.setResult(bssid(fix[0], fix[1], 0));
            }
        });
    }

    private static ScanResult clone2(ScanResult s) {
        try {
            Constructor<ScanResult> c = ScanResult.class.getConstructor(ScanResult.class);
            return c.newInstance(s);
        } catch (Throwable t) {
            return s;   // 直接改原对象（多数场景可接受）
        }
    }

    /** BSSID 由坐标派生：同地点稳定、换地点变化 */
    private static String bssid(double lat, double lng, int idx) {
        long a = (long) (lat * 1e6), b = (long) (lng * 1e6);
        int p0 = (int) ((a >> 8) & 0xff) ^ (idx * 37);
        int p3 = (int) (b & 0xff) ^ (idx * 11);
        return String.format("%02X:%02X:%02X:%02X:%02X:%02X",
                p0 & 0xff, (0x2C + idx) & 0xff, 0x8A, p3 & 0xff, (idx * 31) & 0xff, 0x5E);
    }

    // ---------------------------------------------------------------- 基站
    private static void cell() {
        XposedHelpers.findAndHookMethod(TelephonyManager.class, "getAllCellInfo", new XC_MethodHook() {
            @SuppressWarnings("unchecked")
            protected void afterHookedMethod(MethodHookParam p) {
                if (!Cfg.enabled || !Cfg.cell) return;
                double[] fix = State.lastFix;
                if (fix == null) return;
                try {
                    List<CellInfo> list = new ArrayList<CellInfo>();
                    int cn = Math.max(1, Cfg.cellNow());
                    for (int i = 0; i < cn; i++) {
                        CellIdentityLte cid = (CellIdentityLte) XposedHelpers.newInstance(CellIdentityLte.class);
                        XposedHelpers.setIntField(cid, "mCi", 134217728 + i * 41);
                        XposedHelpers.setIntField(cid, "mPci", 120 + i * 13);
                        XposedHelpers.setIntField(cid, "mTac", 41234 + (int) (fix[0] * 100) % 7);
                        XposedHelpers.setIntField(cid, "mEarfcn", 1650 + i * 25);
                        CellSignalStrengthLte ss = (CellSignalStrengthLte) XposedHelpers.newInstance(CellSignalStrengthLte.class);
                        XposedHelpers.setIntField(ss, "mRsrp", -82 - i * 6);
                        XposedHelpers.setIntField(ss, "mRsrq", -10 - i);
                        XposedHelpers.setIntField(ss, "mRssnr", 16 - i * 2);
                        CellInfoLte ci = (CellInfoLte) XposedHelpers.newInstance(CellInfoLte.class);
                        XposedHelpers.setObjectField(ci, "mCellIdentityLte", cid);
                        XposedHelpers.setObjectField(ci, "mCellSignalStrengthLte", ss);
                        XposedHelpers.setBooleanField(ci, "mRegistered", i == 0);
                        list.add(ci);
                    }
                    p.setResult(list);
                    if (!cellLogged) { cellLogged = true; Log.i("cell info -> " + list.size() + " cells"); }
                } catch (Throwable t) {
                    Log.w("cell build: " + t);
                }
            }
        });
    }

    // ---------------------------------------------------------------- BLE
    private static void ble() {
        XposedHelpers.findAndHookMethod(BluetoothLeScanner.class, "startScan",
                List.class, ScanSettings.class, ScanCallback.class,
                new XC_MethodHook() {
                    protected void beforeHookedMethod(final MethodHookParam p) {
                        if (!Cfg.enabled || !Cfg.ble) return;
                        final ScanCallback cb = (ScanCallback) p.args[2];
                        final ScanSettings st = (ScanSettings) p.args[1];
                        if (cb == null) return;
                        State.MAIN.postDelayed(new Runnable() {
                            public void run() {
                                try {
                                    BluetoothAdapter ad = BluetoothAdapter.getDefaultAdapter();
                                    if (ad == null) return;
                                    Method m = ScanCallback.class.getDeclaredMethod("onScanResult", int.class,
                                            android.bluetooth.le.ScanResult.class);
                                    m.setAccessible(true);
                                    Constructor<android.bluetooth.le.ScanResult> c =
                                            android.bluetooth.le.ScanResult.class.getDeclaredConstructor(
                                                    BluetoothDevice.class, ScanSettings.class,
                                                    int.class, int.class, long.class);
                                    c.setAccessible(true);
                                    for (int i = 0; i < BLE_MAC.length; i++) {
                                        BluetoothDevice dev = ad.getRemoteDevice(BLE_MAC[i]);
                                        android.bluetooth.le.ScanResult sr =
                                                c.newInstance(dev, st, 255 - i * 12, -68 - i * 9, 0L);
                                        m.invoke(cb, ScanSettings.CALLBACK_TYPE_ALL_MATCHES, sr);
                                    }
                                } catch (Throwable t) {
                                    Log.w("ble push: " + t);
                                }
                            }
                        }, 700);
                    }
                });
    }

    private HookNet() { }
}
