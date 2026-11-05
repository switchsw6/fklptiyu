package com.sim.campusrun;

import android.app.Activity;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;

/** 地图绘制页（assets/mapdraw.html）+ 保存桥；模块本体仍只作用于目标 App 进程 */
public class MainActivity extends Activity {
    private WebView web;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        web.setWebViewClient(new WebViewClient());
        web.addJavascriptInterface(new Bridge(), "CampusBridge");
        // 该 ROM 的 WebView（Chromium 83）在硬件层上会把瓦片图层合成丢掉，强制软件层渲染
        web.setLayerType(android.view.View.LAYER_TYPE_SOFTWARE, null);
        setContentView(web);
        web.loadUrl("file:///android_asset/draw-lite.html");
    }

    public class Bridge {
        /** 地图页点“保存到手机”时调用：写入 App 私有目录，随后用 simctl apply 落到 /data/local/tmp */
        @JavascriptInterface
        public String saveConfig(String json) {
            try {
                File dir = new File(getExternalFilesDir(null), "");
                if (!dir.exists()) dir.mkdirs();
                File f = new File(dir, "campusim.json");
                FileOutputStream os = new FileOutputStream(f);
                os.write(json.getBytes("UTF-8"));
                os.close();
                final String path = f.getAbsolutePath();
                runOnUiThread(new Runnable() {
                    public void run() {
                        Toast.makeText(MainActivity.this, "已保存：" + path, Toast.LENGTH_LONG).show();
                    }
                });
                return path;
            } catch (Throwable t) {
                Log.w("saveConfig: " + t);
                return "ERR " + t;
            }
        }

        @JavascriptInterface
        public String loadConfig() {
            try {
                File f = new File(getExternalFilesDir(null), "campusim.json");
                if (!f.exists()) return "";
                byte[] b = new byte[(int) f.length()];
                java.io.FileInputStream in = new java.io.FileInputStream(f);
                int n = in.read(b);
                in.close();
                return n > 0 ? new String(b, 0, n, "UTF-8") : "";
            } catch (Throwable t) {
                return "";
            }
        }

        @JavascriptInterface
        public String status() {
            return "enabled=" + Cfg.enabled + " route=" + Cfg.route.size()
                    + " cps=" + Cfg.checkpoints.size() + " pace=" + (int) Cfg.paceSecPerKm;
        }
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }
}
