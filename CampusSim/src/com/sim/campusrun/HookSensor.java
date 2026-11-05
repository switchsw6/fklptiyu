package com.sim.campusrun;

import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.SystemClock;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/** 传感器 hook：步数计数/检测器 + 加速度波形，全部用合成事件替代真机事件 */
final class HookSensor {
    private static final Map<SensorEventListener, Sensor> proxy2sensor = new ConcurrentHashMap<SensorEventListener, Sensor>();
    private static final Map<SensorEventListener, SensorEventListener> proxy2real = new ConcurrentHashMap<SensorEventListener, SensorEventListener>();
    private static double phase = 0;

    static void install() {
        for (String name : new String[]{"registerListener"}) {
            hook(name, SensorEventListener.class, Sensor.class, int.class);
            hook(name, SensorEventListener.class, Sensor.class, int.class, Handler.class);
            hook(name, SensorEventListener.class, Sensor.class, int.class, int.class);
            try { hook2(name, SensorEventListener.class, Sensor.class, int.class, int.class, Handler.class); } catch (Throwable ignored) { }
        }
    }

    private static void hook(String name, Class<?>... types) {
        Object[] args = new Object[types.length + 1];
        System.arraycopy(types, 0, args, 0, types.length);
        args[types.length] = new XC_MethodHook() {
            protected void beforeHookedMethod(MethodHookParam p) { replace(p); }
        };
        try { XposedHelpers.findAndHookMethod(SensorManager.class, name, args); } catch (Throwable ignored) { }
    }

    private static void hook2(String name, Class<?>... types) { hook(name, types); }

    @SuppressWarnings("unchecked")
    private static void replace(XC_MethodHook.MethodHookParam p) {
        if (!Cfg.enabled) return;
        final SensorEventListener real = (SensorEventListener) p.args[0];
        final Sensor sensor = (Sensor) p.args[1];
        if (real == null || sensor == null) return;
        int t = sensor.getType();
        boolean ours = t == Sensor.TYPE_STEP_COUNTER || t == Sensor.TYPE_STEP_DETECTOR
                || (Cfg.accel && (t == Sensor.TYPE_ACCELEROMETER || t == Sensor.TYPE_LINEAR_ACCELERATION))
                || (Cfg.mag && (t == Sensor.TYPE_MAGNETIC_FIELD || t == Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED))
                || (Cfg.gyro && (t == Sensor.TYPE_GYROSCOPE || t == Sensor.TYPE_GYROSCOPE_UNCALIBRATED
                || t == Sensor.TYPE_ROTATION_VECTOR));
        if (!ours) return;

        SensorEventListener proxy = (SensorEventListener) Proxy.newProxyInstance(
                SensorEventListener.class.getClassLoader(),
                new Class[]{SensorEventListener.class},
                new InvocationHandler() {
                    public Object invoke(Object o, Method m, Object[] a) throws Throwable {
                        String n = m.getName();
                        if ("onSensorChanged".equals(n)) return null;      // 真机事件丢弃
                        if ("onAccuracyChanged".equals(n)) return null;
                        if ("hashCode".equals(n)) return System.identityHashCode(real);
                        if ("equals".equals(n)) return real == (a == null ? null : a[0]);
                        if ("toString".equals(n)) return "CampusSimSensor";
                        return null;
                    }
                });
        p.args[0] = proxy;
        proxy2sensor.put(proxy, sensor);
        proxy2real.put(proxy, real);
        if (hookedTypes.add(t)) {
            Log.i("sensor hooked type=" + t + " name=" + sensor.getName());
        }
    }

    private static final java.util.Set<Integer> hookedTypes = new java.util.HashSet<Integer>();

    /** 一次步：STEPS_COUNTER 累加 + STEP_DETECTOR 脉冲 */
    static void fireStep(long ns) {
        State.stepBase++;
        float total = (float) State.stepBase;
        for (Map.Entry<SensorEventListener, Sensor> e : proxy2sensor.entrySet()) {
            Sensor s = e.getValue();
            SensorEventListener real = proxy2real.get(e.getKey());
            if (real == null) continue;
            if (s.getType() == Sensor.TYPE_STEP_COUNTER) {
                deliver(real, s, new float[]{total}, ns);
            } else if (s.getType() == Sensor.TYPE_STEP_DETECTOR) {
                deliver(real, s, new float[]{1.0f}, ns);
            }
        }
    }

    /** 50Hz 体感波形：ACC / MAG / GYRO 同步（乐跑会上传 run_gyroscope 与 MAG/ROT，缺一路就异常） */
    static void fireAccel(long ns) {
        if (!Cfg.accel && !Cfg.mag && !Cfg.gyro) return;
        double spmNow = State.spm;
        boolean resting = State.resting || spmNow < 0.5;
        double f = Math.max(0.6, (resting ? 0 : spmNow) / 60.0);
        phase += 2 * Math.PI * f * 0.02;
        if (phase > 1e6) phase = 0;

        float amp = resting ? 0.06f : (1.1f + (float) (Math.random() * 0.35));
        float az = (float) (9.81 + amp * Math.sin(phase));
        float ax = (float) (0.35 * Math.sin(phase * 0.5) + (Math.random() - 0.5) * 0.12);
        float ay = (float) (0.22 * Math.cos(phase * 0.5) + (Math.random() - 0.5) * 0.12);

        // 磁力计：地磁 ~50µT，水平分量随“设备朝向”变化（行进方向 + 手里/兜里的固定偏角）
        double devYaw = ((State.heading < 0 ? 0 : State.heading) + State.yawBias + 360.0) % 360.0;
        double br = Math.toRadians(devYaw);
        float bx = (float) (22.0 * Math.cos(br) + (Math.random() - 0.5) * 0.8);
        float by = (float) (22.0 * Math.sin(br) + (Math.random() - 0.5) * 0.8);
        float bz = (float) (38.0 + 0.6 * Math.sin(phase) + (Math.random() - 0.5) * 0.5);

        // 陀螺仪：绕竖直轴的角速度 = 真实转向速率（deg/s→rad/s）+ 步态小幅摆动
        float gz = resting ? (float) ((Math.random() - 0.5) * 0.03)
                : (float) (Math.toRadians(State.headingRate)
                + 0.10 * Math.sin(phase) + (Math.random() - 0.5) * 0.05);
        float gx = (float) ((Math.random() - 0.5) * 0.05);
        float gy = (float) ((Math.random() - 0.5) * 0.05);
        // 旋转矢量：设备姿态 = 偏航(设备朝向) + 俯仰(手持约 -30°) + 轻微横滚
        float yawRad = (float) Math.toRadians(devYaw);
        float pitchRad = (float) Math.toRadians(-30.0 + 4.0 * Math.sin(phase * 0.1));
        float rollRad = (float) Math.toRadians((Math.random() - 0.5) * 6.0);
        float cy = (float) Math.cos(yawRad / 2), sy = (float) Math.sin(yawRad / 2);
        float cp = (float) Math.cos(pitchRad / 2), sp = (float) Math.sin(pitchRad / 2);
        float cr = (float) Math.cos(rollRad / 2), sr = (float) Math.sin(rollRad / 2);
        float qw = cr * cp * cy + sr * sp * sy;
        float qx = sr * cp * cy - cr * sp * sy;
        float qy = cr * sp * cy + sr * cp * sy;
        float qz = cr * cp * sy - sr * sp * cy;
        float[] rot = new float[]{qx, qy, qz, qw};

        for (Map.Entry<SensorEventListener, Sensor> e : proxy2sensor.entrySet()) {
            Sensor s = e.getValue();
            SensorEventListener real = proxy2real.get(e.getKey());
            if (real == null) continue;
            switch (s.getType()) {
                case Sensor.TYPE_ACCELEROMETER:
                    if (Cfg.accel) deliver(real, s, new float[]{ax, ay, az}, ns);
                    break;
                case Sensor.TYPE_LINEAR_ACCELERATION:
                    if (Cfg.accel) deliver(real, s, new float[]{ax, ay, az - 9.81f}, ns);
                    break;
                case Sensor.TYPE_MAGNETIC_FIELD:
                    if (Cfg.mag) deliver(real, s, new float[]{bx, by, bz}, ns);
                    break;
                case Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED:
                    if (Cfg.mag) deliver(real, s, new float[]{bx, by, bz, 0f, 0f, 0f}, ns);
                    break;
                case Sensor.TYPE_GYROSCOPE:
                    if (Cfg.gyro) deliver(real, s, new float[]{gx, gy, gz}, ns);
                    break;
                case Sensor.TYPE_GYROSCOPE_UNCALIBRATED:
                    if (Cfg.gyro) deliver(real, s, new float[]{gx, gy, gz, 0f, 0f, 0f}, ns);
                    break;
                case Sensor.TYPE_ROTATION_VECTOR:
                    if (Cfg.gyro) deliver(real, s, rot, ns);
                    break;
                default:
                    break;
            }
        }
    }

    private static void deliver(final SensorEventListener real, final Sensor sensor, final float[] vals, final long ns) {
        final SensorEvent ev = newEvent(sensor, vals, ns);
        if (ev == null) return;
        State.MAIN.post(new Runnable() {
            public void run() {
                try { real.onSensorChanged(ev); } catch (Throwable ignored) { }
            }
        });
    }

    /** @hide SensorEvent 合成：createSensorEvent(len) + 反射填 mValues/mTimestamp/sensor */
    private static SensorEvent newEvent(Sensor s, float[] vals, long ns) {
        try {
            Method create = SensorEvent.class.getDeclaredMethod("createSensorEvent", int.class);
            create.setAccessible(true);
            SensorEvent e = (SensorEvent) create.invoke(null, vals.length);
            Field fv = SensorEvent.class.getDeclaredField("mValues");
            fv.setAccessible(true);
            fv.set(e, vals);
            Field ft = SensorEvent.class.getDeclaredField("mTimestamp");
            ft.setAccessible(true);
            ft.setLong(e, ns);
            Field fs = SensorEvent.class.getField("sensor");
            fs.set(e, s);
            return e;
        } catch (Throwable t) {
            return null;
        }
    }

    private HookSensor() { }
}
