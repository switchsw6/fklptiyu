package com.sim.campusrun;

import android.hardware.Sensor;
import android.hardware.SensorEventListener;
import android.location.Location;
import android.location.LocationListener;
import android.os.Handler;
import android.os.Looper;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** 进程内共享状态 */
public final class State {
    public static volatile Location fake;
    public static volatile double[] lastFix;      // {lat,lng,bearing,speed,alt}
    public static volatile double heading = -1;      // 平滑后的行进方位角（deg，0=北）
    public static volatile double headingRate = 0;   // 方位角变化率（deg/s），陀螺仪用
    public static volatile double yawBias = 0;       // 设备朝向相对行进方向的固定偏角
    public static volatile double yawBiasTarget = 0;
    public static volatile double spm = 172;      // 当前步频
    public static volatile boolean resting = false;
    public static volatile long stepBase;
    public static volatile double stepFrac;

    public static final java.util.List<LocationListener> locListeners =
            new CopyOnWriteArrayList<LocationListener>();

    public static final Map<SensorEventListener, Sensor> sensorListeners =
            new ConcurrentHashMap<SensorEventListener, Sensor>();

    public static final Handler MAIN = new Handler(Looper.getMainLooper());

    static {
        stepBase = 90000L + (android.os.SystemClock.elapsedRealtime() / 900L) % 5000L;
    }

    public static Location withProvider(Location src, String provider) {
        Location l = new Location(src);
        l.setProvider(provider);
        return l;
    }

    public static void emitLoc(final Location l) {
        for (final LocationListener ll : locListeners) {
            MAIN.post(new Runnable() {
                public void run() {
                    try { ll.onLocationChanged(new Location(l)); } catch (Throwable ignored) { }
                }
            });
        }
    }

    private State() { }
}
