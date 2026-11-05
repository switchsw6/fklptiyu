package com.sim.campusrun;

import android.app.Application;

/** 进程一起来就把配置服务拉起来，模块那边随时能拉到最新配置 */
public class App extends Application {

    private static android.content.Context appCtx;

    /** 给非组件类拿 Context 用（缓存类、搜索等） */
    static android.content.Context ctx() { return appCtx; }

    @Override
    public void onCreate() {
        super.onCreate();
        appCtx = getApplicationContext();
        try {
            LocalConfigServer.start();
            // 启动时从上次保存的文件回填，避免服务刚起来时是空的
            java.io.File f = new java.io.File(getFilesDir(), "campusim.json");
            if (f.exists() && f.length() > 10) {
                byte[] b = new byte[(int) f.length()];
                java.io.FileInputStream in = new java.io.FileInputStream(f);
                int n = in.read(b);
                in.close();
                if (n > 0) LocalConfigServer.set(new String(b, 0, n, "UTF-8"));
            }
        } catch (Throwable ignored) { }
    }
}
