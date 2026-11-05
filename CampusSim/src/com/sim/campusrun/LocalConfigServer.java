package com.sim.campusrun;

import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * 本机 127.0.0.1 上的极简配置服务。
 * 跨应用读文件/Provider 会被包可见性拦，loopback socket 不受此限制，
 * 模块（跑在乐跑进程里）每 0.5s 拉一次即可。
 */
public final class LocalConfigServer {
    public static final int PORT = 47561;

    private static volatile String json = "";
    private static ServerSocket server;
    private static volatile boolean started;

    public static synchronized void start() {
        if (started) return;
        started = true;
        Thread t = new Thread(new Runnable() {
            public void run() {
                for (int attempt = 0; attempt < 5; attempt++) {
                    try {
                        ServerSocket ss = new ServerSocket();
                        ss.setReuseAddress(true);          // App 重启后端口还在 TIME_WAIT 也能立刻绑定
                        ss.bind(new java.net.InetSocketAddress(
                                InetAddress.getByName("127.0.0.1"), PORT), 8);
                        server = ss;
                        while (true) {
                            final Socket s = ss.accept();
                            new Thread(new Runnable() {
                                public void run() { serve(s); }
                            }).start();
                        }
                    } catch (Throwable e) {
                        try { Thread.sleep(400); } catch (Throwable ignored) { }
                    }
                }
            }
        }, "cfg-server");
        t.setDaemon(true);
        t.start();
    }

    public static void set(String j) { json = j; }

    private static void serve(Socket s) {
        try {
            s.setSoTimeout(1500);
            byte[] buf = new byte[512];
            s.getInputStream().read(buf);          // 请求内容不重要
            byte[] body = json.getBytes("UTF-8");
            OutputStream o = s.getOutputStream();
            o.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: "
                    + body.length + "\r\nConnection: close\r\n\r\n").getBytes("UTF-8"));
            o.write(body);
            o.flush();
        } catch (Throwable ignored) {
        } finally {
            try { s.close(); } catch (Throwable ignored) { }
        }
    }

    private LocalConfigServer() { }
}
