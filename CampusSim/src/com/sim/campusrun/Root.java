package com.sim.campusrun;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;

/**
 * root 权限探测与命令执行。
 * 用来适配 VMOS / 免 Magisk 环境：首次调用会触发系统的"超级用户"授权弹窗。
 */
final class Root {

    static final int UNKNOWN = 0;
    static final int GRANTED = 1;
    static final int DENIED = 2;
    static final int NO_SU = 3;

    static volatile int state = UNKNOWN;
    static volatile String detail = "";

    interface Cb {
        void done(int state, String detail);
    }

    /** 异步申请/检测 root；回调保证在主线程之外的调用者自己切线程 */
    static void request(final Cb cb) {
        Thread t = new Thread(new Runnable() {
            public void run() {
                int st;
                String det;
                try {
                    Exec ex = Root.run("id", 4000);
                    if (ex.code == 0 && ex.out.contains("uid=0")) {
                        st = GRANTED;
                        det = ex.out.trim();
                    } else if (ex.code == 127 || ex.err.contains("not found")) {
                        st = NO_SU;
                        det = "no su binary";
                    } else {
                        st = DENIED;
                        det = (ex.err.trim() + " " + ex.out.trim()).trim();
                    }
                } catch (Throwable e) {
                    st = DENIED;
                    det = String.valueOf(e);
                }
                state = st;
                detail = det;
                if (cb != null) cb.done(st, det);
            }
        }, "campus-root");
        t.setDaemon(true);
        t.start();
    }

    /** 已授权时同步执行 root 命令 */
    static Exec su(String cmd, int timeoutMs) throws Exception {
        return Root.run(cmd, timeoutMs);
    }

    static final class Exec {
        int code;
        String out = "";
        String err = "";
    }

    private static Exec run(String cmd, int timeoutMs) throws Exception {
        ProcessBuilder pb = new ProcessBuilder("su", "-c", cmd);
        pb.redirectErrorStream(false);
        final Process p = pb.start();
        final Exec r = new Exec();
        final StringBuilder ob = new StringBuilder();
        final StringBuilder eb = new StringBuilder();

        Thread to = new Thread(new Runnable() {
            public void run() {
                try {
                    BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
                    String l;
                    while ((l = br.readLine()) != null) ob.append(l).append('\n');
                } catch (Throwable ignored) { }
            }
        });
        Thread te = new Thread(new Runnable() {
            public void run() {
                try {
                    BufferedReader br = new BufferedReader(new InputStreamReader(p.getErrorStream()));
                    String l;
                    while ((l = br.readLine()) != null) eb.append(l).append('\n');
                } catch (Throwable ignored) { }
            }
        });
        to.setDaemon(true);
        te.setDaemon(true);
        to.start();
        te.start();
        try {
            OutputStream os = p.getOutputStream();
            os.write('\n');
            os.flush();
        } catch (Throwable ignored) { }

        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            try {
                r.code = p.exitValue();
                break;
            } catch (IllegalThreadStateException e) {
                try { Thread.sleep(50); } catch (InterruptedException ignored) { }
            }
        }
        try { p.destroy(); } catch (Throwable ignored) { }
        to.join(500);
        te.join(500);
        r.out = ob.toString();
        r.err = eb.toString();
        return r;
    }

    static String stateText() {
        switch (state) {
            case GRANTED: return "root:已授权";
            case DENIED: return "root:被拒绝";
            case NO_SU: return "root:无su";
            default: return "root:未知";
        }
    }

    private Root() { }
}
