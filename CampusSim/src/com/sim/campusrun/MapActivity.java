package com.sim.campusrun;

import android.app.Activity;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 原生地图 + 编辑 + 运行控制。
 * 画线 / 打卡点 / 途经点；选中节点可设起终点、删除、按米微调、长按拖动；
 * 给定 起点→途经点→终点 与目标里程，本地几何算法自动生成路线并预览。
 */
public class MapActivity extends Activity implements NativeMapView.Listener {

    private NativeMapView map;
    private TextView hud;
    private EditText edPace, edSpm, edRest, edRad, edTarget;
    private EditText edVmax, edSat, edWifiN, edCellN;
    private Button bJit;
    // 波动设置（界面单位，随状态存档）
    private double jPace = 30, jRest = 0.2;
    private int jSpm = 5, jSat = 1, jWifi = 1, jCell = 1;
    private Button bDraw, bCp, bWp, bCoord, bStart, bPause, bResume, bStop, bRoad;
    private Button bStartPt, bEndPt, bDel, bFit, bSave, bUndo, bClear;
    private Button bGen, bApply, bRegen, bNewRoad, bExport, bImport, bDownload;
    private Button bRoadDraw, bZone, bMore, bRoot;
    private Button zoomInBtn, zoomOutBtn;
    private LinearLayout panelRef;
    private String pendingQuery = "";
    private String amapKey = "";
    /** 搜索/指定的默认地点（WGS-84） */
    private double defLat = Double.NaN, defLng = Double.NaN;
    private String defName = "";
    /** 是否沿真实路网生成（OSRM foot）；关掉就用几何绕行 */
    private boolean roadMode = true;
    private String state = "待机";
    private long runToken = 0;
    private String coord = "gcj";
    private String mode = "draw";                 // draw | cp | wp
    private final List<double[]> waypoints = new ArrayList<double[]>();
    private String autoNote = "";
    private AutoRoute.Result lastAuto;
    /** 校园地图：路网 + 跑区 + 打卡点 */
    private SchoolMap school = new SchoolMap();
    private List<double[]> curRoad;
    /** 生成来源：school 校园路网 | road OSRM 路网 | geom 几何 */
    private String srcMode = "school";
    /** 界面上的改动是否已下发到模块 */
    private boolean dirty = false;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);

        FrameLayout root = new FrameLayout(this);
        map = new NativeMapView(this, null);
        map.setListener(this);
        root.addView(map, new FrameLayout.LayoutParams(-1, -1));

        hud = new TextView(this);
        hud.setTextSize(12);
        hud.setTextColor(0xFFE6EDF3);
        hud.setBackgroundColor(0xE60D1218);
        hud.setPadding(24, 18, 24, 18);
        FrameLayout.LayoutParams hl = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
        hl.setMargins(20, 20, 20, 0);
        root.addView(hud, hl);

        zoomInBtn = new Button(this);
        zoomInBtn.setText("+");
        zoomInBtn.setTextSize(20);
        zoomInBtn.setAllCaps(false);
        zoomInBtn.setBackgroundColor(0xF20D1218);
        zoomInBtn.setTextColor(0xFFE6EDF3);
        FrameLayout.LayoutParams zl1 = new FrameLayout.LayoutParams(dp(52), dp(52),
                Gravity.BOTTOM | Gravity.END);
        zl1.setMargins(0, 0, 20, dp(120));
        root.addView(zoomInBtn, zl1);
        zoomInBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { map.zoomBy(1); }
        });

        zoomOutBtn = new Button(this);
        zoomOutBtn.setText("−");
        zoomOutBtn.setTextSize(20);
        zoomOutBtn.setAllCaps(false);
        zoomOutBtn.setBackgroundColor(0xF20D1218);
        zoomOutBtn.setTextColor(0xFFE6EDF3);
        FrameLayout.LayoutParams zl2 = new FrameLayout.LayoutParams(dp(52), dp(52),
                Gravity.BOTTOM | Gravity.END);
        zl2.setMargins(0, 0, 20, dp(60));
        root.addView(zoomOutBtn, zl2);
        zoomOutBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { map.zoomBy(-1); }
        });

        ScrollView bottom = new ScrollView(this);
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.VERTICAL);
        bar.setBackgroundColor(0xF20D1218);
        bar.setPadding(18, 10, 18, 20);

        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        bDraw = mkBtn(row1, "画线", true);
        bWp = mkBtn(row1, "途经点", false);
        bRoadDraw = mkBtn(row1, "道路", false);
        bZone = mkBtn(row1, "跑区", false);
        bUndo = mkBtn(row1, "撤销", false);
        bClear = mkBtn(row1, "清空", false);
        bRoot = mkBtn(row1, "ROOT", false);
        bMore = mkBtn(row1, "⋯", false);
        bar.addView(row1);

        bRoot.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                bRoot.setText("申请中");
                toast("正在请求 root 授权...");
                Root.request(new Root.Cb() {
                    public void done(final int st, final String detail) {
                        runOnUiThread(new Runnable() {
                            public void run() {
                                bRoot.setText(st == Root.GRANTED ? "ROOT✓" : "ROOT✗");
                                updateHud();
                                if (st == Root.GRANTED) {
                                    toast("已获取 root\n" + detail, Toast.LENGTH_LONG);
                                    writeConfigAsRoot();
                                } else {
                                    toast("root 未授权: " + detail, Toast.LENGTH_LONG);
                                }
                            }
                        });
                    }
                });
            }
        });

        // 高级面板：默认收起，只保留一行常用按钮
        final LinearLayout panel = new LinearLayout(this);
        panelRef = panel;
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setVisibility(View.GONE);
        bar.addView(panel);

        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        bStartPt = mkBtn(row2, "起点", false);
        bEndPt = mkBtn(row2, "终点", false);
        bDel = mkBtn(row2, "删除", false);







        panel.addView(row2);

        LinearLayout row3 = new LinearLayout(this);
        row3.setOrientation(LinearLayout.HORIZONTAL);
        bFit = mkBtn(row3, "适配", false);
        bCoord = mkBtn(row3, "坐标:GCJ", false);
        bSave = mkBtn(row3, "保存", false);
        panel.addView(row3);

        LinearLayout row4 = new LinearLayout(this);
        row4.setOrientation(LinearLayout.HORIZONTAL);
        edTarget = mkField(row4, "目标里程 m", "800");
        bGen = mkBtn(row4, "生成", true);
        bApply = mkBtn(row4, "应用", true);
        bRegen = mkBtn(row4, "重生成", false);
        panel.addView(row4);

        LinearLayout row5 = new LinearLayout(this);
        row5.setOrientation(LinearLayout.HORIZONTAL);
        bStart = mkBtn(row5, "开始", true);
        bPause = mkBtn(row5, "暂停", false);
        bResume = mkBtn(row5, "继续", false);
        bStop = mkBtn(row5, "结束", false);
        panel.addView(row5);

        LinearLayout row6 = new LinearLayout(this);
        row6.setOrientation(LinearLayout.HORIZONTAL);
        edPace = mkField(row6, "配速 s/km", "330");
        edSpm = mkField(row6, "步频 spm", "172");
        edRest = mkField(row6, "休息 次/km", "0.8");
        edRad = mkField(row6, "打卡半径 m", "25");
        panel.addView(row6);

        LinearLayout row7 = new LinearLayout(this);
        row7.setOrientation(LinearLayout.HORIZONTAL);
        edVmax = mkField(row7, "速度上限 m/s", "6.5");
        edSat = mkField(row7, "卫星数", "12");
        edWifiN = mkField(row7, "WiFi数", "4");
        edCellN = mkField(row7, "基站数", "3");
        bJit = mkBtn(row7, "波动", false);
        panel.addView(row7);

        LinearLayout row8 = new LinearLayout(this);
        row8.setOrientation(LinearLayout.HORIZONTAL);
        bNewRoad = mkBtn(row8, "新道路", false);
        bExport = mkBtn(row8, "导出", false);
        bImport = mkBtn(row8, "导入", false);
        bDownload = mkBtn(row8, "下载", false);
        panel.addView(row8);

        android.text.TextWatcher w = new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            public void onTextChanged(CharSequence s, int a, int b, int c) { }
            public void afterTextChanged(android.text.Editable s) { updateHud(); }
        };
        edPace.addTextChangedListener(w);
        edSpm.addTextChangedListener(w);
        edRest.addTextChangedListener(w);
        edRad.addTextChangedListener(w);

        bottom.addView(bar);
        root.addView(bottom, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        setContentView(root);

        bDraw.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { setMode("draw"); } });
        bWp.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { setMode("wp"); } });

        bRoadDraw.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { setMode("road"); }
        });
        bZone.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { setMode("zone"); }
        });
        bMore.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                boolean show = panel.getVisibility() != View.VISIBLE;
                panel.setVisibility(show ? View.VISIBLE : View.GONE);
                bMore.setText(show ? "×" : "⋯");
                updateZoomPos();
            }
        });
        bUndo.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { undo(); }
        });
        bClear.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { clearAll(); }
        });

        bStartPt.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { setEndpoint(true); }
        });
        bEndPt.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { setEndpoint(false); }
        });
        bDel.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { deleteSelected(); }
        });

        bFit.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { map.fitRoute(); updateHud(); }
        });
        bJit.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { openJitter(); }
        });
        bCoord.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { toggleCoord(); }
        });
        bSave.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { saveOnly(); }
        });

        bGen.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { generate(System.nanoTime(), false); }
        });
        bApply.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { applyPreview(); }
        });
        bRegen.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { generate(System.nanoTime(), true); }
        });

        bNewRoad.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { newRoad(); }
        });
        bExport.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { exportMap(); }
        });
        bImport.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { importMap(); }
        });
        bDownload.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { downloadMap(); }
        });

        bStart.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { start(); } });
        bPause.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { control("已暂停", true, true); }
        });
        bResume.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { control("运行中", false, true); }
        });
        bStop.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { control("已结束", false, false); }
        });

        setMode("draw");
        updateHud();
        restore();
        restoreAll();
        try {
            String q0 = getIntent() != null ? getIntent().getStringExtra("q") : null;
            if (q0 != null && !q0.isEmpty()) pendingQuery = q0;
        } catch (Throwable ignored) { }
        if (!pendingQuery.isEmpty()) openSearch();
    }

    // ---------------------------------------------------------------- 编辑
    // ---------------------------------------------------------------- 波动设置
    private void openJitter() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(12), dp(16), dp(8));

        LinearLayout r1 = new LinearLayout(this);
        r1.setOrientation(LinearLayout.HORIZONTAL);
        final EditText ePace = mkField(r1, "配速 ± s/km", fmt(jPace));
        final EditText eSpm = mkField(r1, "步频 ± spm", String.valueOf(jSpm));
        box.addView(r1);

        LinearLayout r2 = new LinearLayout(this);
        r2.setOrientation(LinearLayout.HORIZONTAL);
        final EditText eRest = mkField(r2, "休息 ± 次/km", fmt(jRest));
        final EditText eSat = mkField(r2, "卫星 ±", String.valueOf(jSat));
        box.addView(r2);

        LinearLayout r3 = new LinearLayout(this);
        r3.setOrientation(LinearLayout.HORIZONTAL);
        final EditText eWifi = mkField(r3, "WiFi ±", String.valueOf(jWifi));
        final EditText eCell = mkField(r3, "基站 ±", String.valueOf(jCell));
        box.addView(r3);

        TextView tip = new TextView(this);
        tip.setTextSize(11);
        tip.setTextColor(0xFF8FA3B8);
        tip.setText("配速波动是按分段目标算的：\u00b130 s/km 表示每段配速在 ±30 秒内浮动；\n"
                + "卫星/WiFi/基站是数量漂移，填 0 表示固定不变。");
        box.addView(tip);

        new android.app.AlertDialog.Builder(this)
                .setTitle("波动设置")
                .setView(box)
                .setPositiveButton("保存", new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int w) {
                        jPace = parseDouble(ePace, 30);
                        jSpm = parseInt(eSpm, 5);
                        jRest = parseDouble(eRest, 0.2);
                        jSat = parseInt(eSat, 1);
                        jWifi = parseInt(eWifi, 1);
                        jCell = parseInt(eCell, 1);
                        saveState();
                        toast("波动已保存：配速 ±" + fmt(jPace) + "s 步频 ±" + jSpm
                                + " 休息 ±" + fmt(jRest) + " 卫星 ±" + jSat
                                + " WiFi ±" + jWifi + " 基站 ±" + jCell, Toast.LENGTH_LONG);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private static String fmt(double v) {
        if (Math.abs(v - Math.rint(v)) < 1e-6) return String.valueOf((long) Math.rint(v));
        return String.valueOf(Math.rint(v * 100) / 100.0);
    }

    // ---------------------------------------------------------------- 标题栏搜索图标
    @Override
    public boolean onCreateOptionsMenu(android.view.Menu menu) {
        android.view.MenuItem mi = menu.add(0, 1001, 0, "搜索");
        mi.setIcon(android.R.drawable.ic_menu_search);
        mi.setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(android.view.MenuItem item) {
        if (item != null && item.getItemId() == 1001) {
            openSearch();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    // ---------------------------------------------------------------- 放大镜搜索弹窗
    private void openSearch() {
        final LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(12), dp(16), dp(8));

        final EditText input = new EditText(this);
        input.setHint("输入地名，如 南开、武汉理工大学");
        input.setSingleLine(true);
        input.setText(defName == null ? "" : defName);
        box.addView(input);

        final LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        final Button go = new Button(this);
        go.setText("搜索");
        go.setAllCaps(false);
        Button def = new Button(this);
        def.setText("设为默认");
        def.setAllCaps(false);
        btns.addView(go, new LinearLayout.LayoutParams(0, -2, 1f));
        btns.addView(def, new LinearLayout.LayoutParams(0, -2, 1f));
        box.addView(btns);

        final EditText keyInput = new EditText(this);
        keyInput.setHint("高德 Web key（可选，留空就用免 key 接口）");
        keyInput.setSingleLine(true);
        keyInput.setText(amapKey);
        box.addView(keyInput);

        final TextView tip = new TextView(this);
        tip.setTextSize(11);
        tip.setTextColor(0xFF8FA3B8);
        tip.setText("搜索来源：高德输入提示 → 高德 key → 系统 → 内置地名表 → OSM");
        box.addView(tip);

        final ListView list = new ListView(this);
        box.addView(list, new LinearLayout.LayoutParams(-1, dp(320)));

        final android.app.AlertDialog dlg = new android.app.AlertDialog.Builder(this)
                .setTitle("搜索地点")
                .setView(box)
                .setNegativeButton("关闭", null)
                .create();

        final List<Geo.Item> results = new ArrayList<Geo.Item>();
        final ArrayAdapter<String> adapter = new ArrayAdapter<String>(this,
                android.R.layout.simple_list_item_1, new ArrayList<String>());
        list.setAdapter(adapter);

        go.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                amapKey = keyInput.getText().toString().trim();
                saveState();   // key 改了就存，免得搜不到结果时丢
                final String q = input.getText().toString().trim();
                if (q.isEmpty()) { toast("先输入地名"); return; }
                tip.setText("搜索中: " + q);
                new Thread(new Runnable() {
                    public void run() {
                        final List<Geo.Item> hits = Geo.search(q, amapKey, 12);
                        runOnUiThread(new Runnable() {
                            public void run() {
                                results.clear();
                                results.addAll(hits);
                                adapter.clear();
                                for (Geo.Item it : hits) adapter.add(it.label());
                                adapter.notifyDataSetChanged();
                                tip.setText(hits.isEmpty() ? ("没找到: " + q)
                                        : ("找到 " + hits.size() + " 条，点一条定位"));
                                if (!hits.isEmpty()) {
                                    // 第一条直接跳过去，方便快速看结果
                                    Geo.Item first = hits.get(0);
                                    defName = first.name;
                                    defLat = first.latWgs;
                                    defLng = first.lngWgs;
                                    map.setCenter(defLat, defLng, 18);
                                    saveState();
                                }
                            }
                        });
                    }
                }, "campus-search").start();
            }
        });

        def.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (Double.isNaN(defLat)) { toast("先搜索一个地点"); return; }
                applyDefaultPoint();
                dlg.dismiss();
            }
        });

        list.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
            public void onItemClick(android.widget.AdapterView<?> parent, View view, int pos, long id) {
                if (pos < 0 || pos >= results.size()) return;
                Geo.Item it = results.get(pos);
                defName = it.name;
                defLat = it.latWgs;
                defLng = it.lngWgs;
                map.setCenter(defLat, defLng, 19);
                toast("已定位: " + it.name);
                saveState();
                dlg.dismiss();
            }
        });

        dlg.show();
        if (!pendingQuery.isEmpty()) {
            input.setText(pendingQuery);
            pendingQuery = "";
            go.post(new Runnable() { public void run() { go.performClick(); } });
        }
    }

    /** 设成默认起点：把当前默认地点当作路线起点（没有路线时新建一个点） */
    private void applyDefaultPoint() {
        if (Double.isNaN(defLat)) { toast("先搜索一个地址"); return; }
        List<double[]> n = map.nodes();
        List<double[]> c = map.cps();
        if (n.isEmpty()) {
            n.add(new double[]{defLat, defLng});
            map.setData(n, c, map.closed());
            toast("已把默认地点设为起点");
        } else {
            n.set(0, new double[]{defLat, defLng});
            map.setData(n, c, map.closed());
            toast("已把默认地点设为起点（原起点被替换）");
        }
        map.setCenter(defLat, defLng, 18);
        dirty = true;
        updateHud();
        saveState();
    }

    private void setMode(String m) {
        mode = m;
        bDraw.setSelected("draw".equals(m));
        if (bCp != null) bCp.setSelected("cp".equals(m));
        bWp.setSelected("wp".equals(m));
        bRoadDraw.setSelected("road".equals(m));
        bZone.setSelected("zone".equals(m));
        if ("road".equals(m) && (curRoad == null || school.roads.isEmpty())) newRoad();
        if ("zone".equals(m)) toast("跑区模式：沿校园边界点一圈，自动路线不会跑出去");
        else if ("road".equals(m)) toast("道路模式：点地图画跑道，点「新道路」开始下一条");
        else toast("draw".equals(m) ? "画线模式：点地图加点"
                : ("cp".equals(m) ? "打卡点模式：点地图加打卡点" : "途经点模式：自动路线会依次经过"));
    }

    // ---------------------------------------------------------------- 校园地图
    private void newRoad() {
        curRoad = new ArrayList<double[]>();
        school.roads.add(curRoad);
        school.roadNames.add("道路" + school.roads.size());
        dirty = true;
        updateHud();
    }

    private void exportMap() {
        try {
            File dir = getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS);
            if (dir == null) dir = getFilesDir();
            if (school.id == null || school.id.isEmpty()) school.id = "school_" + System.currentTimeMillis();
            if (school.school == null || school.school.isEmpty()) school.school = "未命名学校";
            school.version++;
            boolean ok = school.save(dir) && school.save(getFilesDir());
            toast(ok ? ("已导出：" + school.fileFor(dir).getAbsolutePath())
                    : "导出失败", Toast.LENGTH_LONG);
        } catch (Throwable t) {
            toast("导出失败：" + t);
        }
    }

    private void importMap() {
        try {
            File dir = getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS);
            SchoolMap m = null;
            if (dir != null) {
                File[] fs = dir.listFiles();
                if (fs != null) {
                    for (File f : fs) {
                        if (f.getName().endsWith(".json") && f.getName().startsWith("school")) {
                            m = SchoolMap.load(f);
                            if (m != null) break;
                        }
                    }
                }
            }
            if (m == null) m = SchoolMap.load(school.fileFor(getFilesDir()));
            if (m == null) { toast("没找到校园地图 json"); return; }
            school = m;
            curRoad = school.roads.isEmpty() ? null : school.roads.get(school.roads.size() - 1);
            pushSchoolToMap();
            toast("已导入：" + school.school + "（" + school.roadCount() + " 段路）", Toast.LENGTH_LONG);
            updateHud();
        } catch (Throwable t) {
            toast("导入失败：" + t);
        }
    }

    private void downloadMap() {
        final String url = "https://raw.githubusercontent.com/campusim/maps/main/schools/"
                + (school.id == null || school.id.isEmpty() ? "index" : school.id) + ".json";
        toast("下载中…");
        new Thread(new Runnable() {
            public void run() {
                final SchoolMap m = fetchSchool(url);
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (m == null) { toast("下载失败（" + url + "）", Toast.LENGTH_LONG); return; }
                        school = m;
                        curRoad = school.roads.isEmpty() ? null : school.roads.get(school.roads.size() - 1);
                        school.save(getFilesDir());
                        pushSchoolToMap();
                        toast("已下载：" + school.school + "（" + school.roadCount() + " 段路）", Toast.LENGTH_LONG);
                        updateHud();
                    }
                });
            }
        }).start();
    }

    private SchoolMap fetchSchool(String url) {
        try {
            java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(8000);
            if (c.getResponseCode() != 200) return null;
            java.io.InputStream in = c.getInputStream();
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            in.close();
            return SchoolMap.fromJson(new String(bo.toByteArray(), "UTF-8"));
        } catch (Throwable t) {
            return null;
        }
    }

    private void pushSchoolToMap() {
        map.setRoads(school.roads);
        map.setZone(school.zone);
        // 道路/跑区一改就落盘，重启不丢
        try { school.save(getFilesDir()); } catch (Throwable ignored) { }
    }

    private void undo() {
        dirty = true;
        if ("road".equals(mode) && curRoad != null && !curRoad.isEmpty()) {
            curRoad.remove(curRoad.size() - 1);
            pushSchoolToMap();
            toast("已撤销道路点，剩 " + curRoad.size());
            updateHud();
            return;
        }
        if ("zone".equals(mode) && !school.zone.isEmpty()) {
            school.zone.remove(school.zone.size() - 1);
            pushSchoolToMap();
            toast("已撤销跑区点，剩 " + school.zone.size());
            updateHud();
            return;
        }
        if ("wp".equals(mode)) {
            if (waypoints.isEmpty()) { toast("没有途经点可撤销"); return; }
            waypoints.remove(waypoints.size() - 1);
            map.setWaypoints(waypoints);
            toast("已撤销途经点，剩 " + waypoints.size() + " 个");
            updateHud();
            return;
        }
        List<double[]> n = map.nodes(), c = map.cps();
        boolean removed = false;
        if ("cp".equals(mode)) {
            if (!c.isEmpty()) { c.remove(c.size() - 1); removed = true; }
        } else if (!n.isEmpty()) {
            n.remove(n.size() - 1);
            removed = true;
        }
        if (!removed) { toast("没有可撤销的内容"); return; }
        map.setData(n, c, map.closed());
        toast("已撤销，剩 " + n.size() + " 点 / " + c.size() + " 打卡点");
        updateHud();
    }

    private void clearAll() {
        dirty = true;
        map.setData(new ArrayList<double[]>(), new ArrayList<double[]>(), false);
        map.setSelected(-1);
        map.setStartIndex(0);
        map.setEndIndex(-1);
        waypoints.clear();
        map.setWaypoints(waypoints);
        school.roads.clear();
        school.roadNames.clear();
        school.zone.clear();
        curRoad = null;
        pushSchoolToMap();
        lastAuto = null;
        autoNote = "";
        map.setPreview(null);
        toast("已清空");
        updateHud();
    }

    private void deleteSelected() {
        dirty = true;
        int sel = map.selectedIndex();
        if (sel < 0) { toast("先点选一个节点"); return; }
        map.removeNode(sel);
        toast("已删除第 " + (sel + 1) + " 点");
        updateHud();
    }

    private void nudge(int dLngM, int dLatM) {
        dirty = true;
        int sel = map.selectedIndex();
        if (sel < 0) { toast("先点选一个节点再微调"); return; }
        map.moveNode(sel, dLatM, dLngM);
        updateHud();
    }

    private void setEndpoint(boolean start) {
        if (map.nodes().isEmpty()) { toast("先画路径点"); return; }
        int sel = map.selectedIndex();
        if (sel < 0) sel = map.nearestIndexToCenter();
        if (sel < 0) { toast("先画路径点"); return; }
        if (start) { map.setStartIndex(sel); toast("起点 = 第 " + (sel + 1) + " 点"); }
        else { map.setEndIndex(sel); toast("终点 = 第 " + (sel + 1) + " 点"); }
        dirty = true;
        updateHud();
    }

    private void toggleCoord() {
        coord = "gcj".equals(coord) ? "wgs" : "gcj";
        bCoord.setText("坐标:" + coord.toUpperCase());
        boolean pushed = pushConfig(false, map.nodes().size() >= 2);
        toast("下发坐标系：" + coord.toUpperCase() + (pushed ? "" : "（需 ≥2 个点才会下发）"));
        updateHud();
    }

    // ---------------------------------------------------------------- 自动路线
    private void generate(long seed, final boolean regen) {
        if (map.nodes().isEmpty()) { toast("\u5148\u753b\u8d77\u70b9\u548c\u7ec8\u70b9"); return; }
        List<double[]> n = map.nodes();
        int si = Math.max(0, Math.min(map.startIndex(), n.size() - 1));
        int ei = Math.max(0, Math.min(map.endIndex(), n.size() - 1));
        final List<double[]> anchors = new ArrayList<double[]>();
        anchors.add(n.get(si));
        anchors.addAll(waypoints);
        if (ei != si) anchors.add(n.get(ei));
        else anchors.add(n.get(si));
        if (anchors.size() < 3 && ei == si) { toast("\u8d77\u70b9\u548c\u7ec8\u70b9\u76f8\u540c\uff0c\u4e14\u6ca1\u6709\u9014\u7ecf\u70b9"); return; }

        final double target = parseDouble(edTarget, 800);
        final String src = srcMode;
        final SchoolMap smap = school;
        toast("school".equals(src) ? "\u6821\u56ed\u8def\u7f51\u5bfb\u8def\u4e2d\u2026"
                : ("road".equals(src) ? "\u6cbf OSRM \u8def\u7f51\u5bfb\u8def\u4e2d\u2026" : "\u51e0\u4f55\u751f\u6210\u4e2d\u2026"));
        new Thread(new Runnable() {
            public void run() {
                AutoRoute.Result r = null;
                String warn = "";
                if ("school".equals(src)) {
                    r = AutoRoute.byGraph(anchors, target, smap);
                    if (r == null || r.points.size() < 2) {
                        warn = "\u6821\u56ed\u8def\u7f51\u4e0d\u53ef\u7528\uff1a"
                                + (r == null ? "\u65e0\u7ed3\u679c" : r.note) + "\uff1b\u56de\u9000 OSRM\n";
                        r = null;
                    }
                }
                if (r == null && ("school".equals(src) || "road".equals(src))) {
                    r = AutoRoute.byRoad(anchors, target);
                    if (r == null || r.points.size() < 2) {
                        warn += "\u8def\u7f51\u4e0d\u53ef\u7528\uff0c\u5df2\u56de\u9000\u51e0\u4f55\u751f\u6210\n";
                        r = AutoRoute.build(anchors, target, System.nanoTime());
                    }
                } else if ("geom".equals(src)) {
                    r = AutoRoute.build(anchors, target, System.nanoTime());
                }
                final AutoRoute.Result res = r;
                final String w = warn;
                runOnUiThread(new Runnable() {
                    public void run() { applyResult(res, regen, w); }
                });
            }
        }).start();
    }

    private void applyResult(AutoRoute.Result r, boolean regen, String warn) {
        if (r == null || r.points.size() < 2) { toast("\u751f\u6210\u5931\u8d25\uff1a" + (r == null ? "\u65e0\u7ed3\u679c" : r.note)); return; }
        lastAuto = r;
        map.setPreview(r.points);
        autoNote = (warn == null ? "" : warn) + r.note;
        map.fitRoute();
        toast((regen ? "\u5df2\u91cd\u751f\u6210\uff1a" : "\u5df2\u751f\u6210\uff1a") + autoNote, Toast.LENGTH_LONG);
        updateHud();
    }

    private void applyPreview() {
        if (lastAuto == null || lastAuto.nodes.size() < 2) { toast("先生成路线"); return; }
        List<double[]> nodes = new ArrayList<double[]>();
        for (double[] p : lastAuto.nodes) nodes.add(new double[]{p[0], p[1]});
        map.setData(nodes, map.cps(), lastAuto.loop);
        map.setStartIndex(0);
        map.setEndIndex(lastAuto.loop ? 0 : nodes.size() - 1);
        map.setPreview(null);
        waypoints.clear();
        map.setWaypoints(waypoints);
        map.fitRoute();
        dirty = true;
        toast("已应用：" + String.format("%.0f m / %d 个节点", lastAuto.length, nodes.size()));
        updateHud();
    }

    // ---------------------------------------------------------------- 运行控制
    private void start() {
        if (map.nodes().size() < 2) { toast("先画至少两个点"); return; }
        runToken = System.currentTimeMillis();
        if (!pushConfig(false, true)) return;
        state = "运行中";
        toast("已开始：模块 0.5 秒内进入跑步状态");
        updateHud();
    }

    private void control(String st, boolean paused, boolean enabled) {
        if (!pushConfig(paused, enabled)) return;
        state = st;
        toast(st);
        updateHud();
    }

    private boolean pushConfig(boolean paused, boolean enabled) {
        if (enabled && map.nodes().size() < 2) {
            toast("至少需要 2 个路径点，当前 " + map.nodes().size() + " 个");
            return false;
        }
        String json = buildJson(paused, enabled);
        LocalConfigServer.set(json);
        dirty = false;
        try {
            File f = new File(getFilesDir(), "campusim.json");
            FileOutputStream os = new FileOutputStream(f);
            os.write(json.getBytes("UTF-8"));
            os.close();
        } catch (Throwable ignored) { }
        try {
            File dir = getExternalFilesDir(null);
            if (!dir.exists()) dir.mkdirs();
            File f = new File(dir, "campusim.json");
            FileOutputStream os = new FileOutputStream(f);
            os.write(json.getBytes("UTF-8"));
            os.close();
        } catch (Throwable ignored) { }
        return true;
    }

    private void saveOnly() {
        if (pushConfig(false, true)) toast("已保存");
    }

    private String buildJson(boolean paused, boolean enabled) {
        List<double[]> all = map.nodes();
        int si = Math.max(0, Math.min(map.startIndex(), all.size() - 1));
        int ei = Math.max(0, Math.min(map.endIndex(), all.size() - 1));
        boolean loop = (si == ei);

        List<double[]> route = new ArrayList<double[]>();
        if (loop) route.addAll(all);
        else for (int i = si; i <= ei; i++) route.add(all.get(i));
        if (route.size() < 2) route.addAll(all);

        StringBuilder sb = new StringBuilder();
        sb.append("{\n  \"enabled\": ").append(enabled).append(",\n");
        sb.append("  \"paused\": ").append(paused).append(",\n");
        sb.append("  \"mode\": \"").append(loop ? "loop" : "once").append("\",\n");
        sb.append("  \"coord\": \"").append(coord).append("\",\n");
        sb.append("  \"runToken\": ").append(runToken).append(",\n");
        // route 已经按 S..E 切好，落盘时索引必须对应切好的这条线（否则重开 App 起点会错位）
        int saveSi = 0;
        int saveEi = route.size() - 1;
        sb.append("  \"startIndex\": ").append(saveSi).append(",\n");
        sb.append("  \"endIndex\": ").append(saveEi).append(",\n");
        sb.append("  \"closed\": ").append(map.closed()).append(",\n");
        sb.append("  \"targets\": [\"com.lptiyu.tanke\"],\n  \"route\": [");
        for (int i = 0; i < route.size(); i++) {
            sb.append(String.format("%s[%.6f,%.6f]", i == 0 ? "" : ",", route.get(i)[0], route.get(i)[1]));
        }
        sb.append("],\n  \"checkpoints\": [");
        List<double[]> c = map.cps();
        for (int i = 0; i < c.size(); i++) {
            sb.append(String.format("%s[%.6f,%.6f]", i == 0 ? "" : ",", c.get(i)[0], c.get(i)[1]));
        }
        sb.append("],\n  \"waypoints\": [");
        for (int i = 0; i < waypoints.size(); i++) {
            sb.append(String.format("%s[%.6f,%.6f]", i == 0 ? "" : ",", waypoints.get(i)[0], waypoints.get(i)[1]));
        }
        sb.append("],\n");
        sb.append("  \"checkpointRadius\": ").append(parseInt(edRad, 25)).append(",\n");
        sb.append("  \"paceSecPerKm\": ").append(parseInt(edPace, 330)).append(",\n");
        sb.append("  \"speedJitter\": 0.06,\n");
        sb.append("  \"spmBase\": ").append(parseInt(edSpm, 172)).append(",\n");
        sb.append("  \"restsPerKm\": ").append(edRest.getText().toString().trim()).append(",\n");
        sb.append("  \"restMinSec\": 15, \"restMaxSec\": 60,\n");
        sb.append("  \"maxSpeed\": ").append(parseDouble(edVmax, 6.5)).append(",\n");
        sb.append("  \"minSpeed\": 0.6,\n");
        sb.append("  \"satCount\": ").append(parseInt(edSat, 12)).append(",\n");
        sb.append("  \"wifiCount\": ").append(parseInt(edWifiN, 4)).append(",\n");
        sb.append("  \"cellCount\": ").append(parseInt(edCellN, 3)).append(",\n");
        sb.append("  \"countJitter\": ").append(jSat).append(",\n");
        sb.append("  \"paceJitterSec\": ").append(jPace).append(",\n");
        sb.append("  \"spmJitter\": ").append(jSpm).append(",\n");
        sb.append("  \"restJitter\": ").append(jRest).append(",\n");
        sb.append("  \"satJitter\": ").append(jSat).append(",\n");
        sb.append("  \"wifiJitter\": ").append(jWifi).append(",\n");
        sb.append("  \"cellJitter\": ").append(jCell).append(",\n");
        sb.append("  \"mag\": true, \"gyro\": true,\n");
        sb.append("  \"randomStart\": ").append(loop).append(", \"randomDir\": false,\n");
        sb.append("  \"heartbeat\": 10, \"seed\": 0,\n");
        sb.append("  \"wifi\": true, \"cell\": true, \"ble\": true, \"accel\": true\n}\n");
        return sb.toString();
    }

    // ---------------------------------------------------------------- 恢复
    private void restore() {
        try {
            SchoolMap sm = SchoolMap.load(school.fileFor(getFilesDir()));
            if (sm == null) {
                File[] fs = getFilesDir().listFiles();
                if (fs != null) {
                    for (File f : fs) {
                        if (f.getName().startsWith("school") && f.getName().endsWith(".json")) {
                            sm = SchoolMap.load(f);
                            if (sm != null) break;
                        }
                    }
                }
            }
            if (sm != null) {
                school = sm;
                curRoad = school.roads.isEmpty() ? null : school.roads.get(school.roads.size() - 1);
                pushSchoolToMap();
            }
        } catch (Throwable ignored) { }
        try {
            File f = new File(getFilesDir(), "campusim.json");
            if (!f.exists() || f.length() < 10) return;
            byte[] b = new byte[(int) f.length()];
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            int n = in.read(b);
            in.close();
            if (n <= 0) return;
            java.util.Map<String, Object> m = Json.obj(Json.parse(new String(b, 0, n, "UTF-8")));

            List<double[]> route = parsePts(Json.arr(m.get("route")));
            List<double[]> cps = parsePts(Json.arr(m.get("checkpoints")));
            List<double[]> wps = parsePts(Json.arr(m.get("waypoints")));
            boolean closed = Json.bool(m, "closed", false);
            if (!route.isEmpty()) {
                map.setData(route, cps, closed);
                int si = (int) Json.num(m, "startIndex", 0);
                int ei = (int) Json.num(m, "endIndex", route.size() - 1);
                map.setStartIndex(Math.max(0, Math.min(si, route.size() - 1)));
                map.setEndIndex(Math.max(0, Math.min(ei, route.size() - 1)));
            }
            waypoints.clear();
            waypoints.addAll(wps);
            map.setWaypoints(waypoints);

            edPace.setText(String.valueOf((int) Json.num(m, "paceSecPerKm", 330)));
            edSpm.setText(String.valueOf((int) Json.num(m, "spmBase", 172)));
            edRest.setText(String.valueOf(Json.num(m, "restsPerKm", 0.8)));
            edRad.setText(String.valueOf((int) Json.num(m, "checkpointRadius", 25)));
            edVmax.setText(String.valueOf(Json.num(m, "maxSpeed", 6.5)));
            edSat.setText(String.valueOf((int) Json.num(m, "satCount", 12)));
            edWifiN.setText(String.valueOf((int) Json.num(m, "wifiCount", 4)));
            edCellN.setText(String.valueOf((int) Json.num(m, "cellCount", 3)));
            coord = Json.str(m, "coord", "gcj");
            if (bCoord != null) bCoord.setText("坐标:" + coord.toUpperCase());
            runToken = (long) Json.num(m, "runToken", 0);

            boolean enabled = Json.bool(m, "enabled", false);
            boolean paused = Json.bool(m, "paused", false);
            state = !enabled ? "已结束" : (paused ? "已暂停" : "运行中");
            map.fitRoute();
            updateHud();
        } catch (Throwable t) { }
    }

    // ---------------------------------------------------------------- 完整存档
    private void saveState() {
        try {
            StringBuilder b = new StringBuilder();
            b.append('{');
            b.append("\"v\":1,");
            b.append("\"defName\":").append(q(defName)).append(',');
            b.append("\"defLat\":").append(Double.isNaN(defLat) ? "null" : String.valueOf(defLat)).append(',');
            b.append("\"defLng\":").append(Double.isNaN(defLng) ? "null" : String.valueOf(defLng)).append(',');
            b.append("\"mode\":").append(q(mode)).append(',');
            b.append("\"coord\":").append(q(coord)).append(',');
            b.append("\"src\":").append(q(srcMode)).append(',');
            b.append("\"roadMode\":").append(roadMode).append(',');
            b.append("\"state\":").append(q(state)).append(',');
            b.append("\"closed\":").append(map.closed()).append(',');
            b.append("\"zoom\":").append(map.zoomLevel()).append(',');
            double[] cw = map.centerWgs();
            b.append("\"clat\":").append(cw[0]).append(',');
            b.append("\"clng\":").append(cw[1]).append(',');
            b.append("\"start\":").append(map.startIndex()).append(',');
            b.append("\"end\":").append(map.endIndex()).append(',');
            b.append("\"route\":").append(pts(map.nodes())).append(',');
            b.append("\"cps\":").append(pts(map.cps())).append(',');
            b.append("\"wps\":").append(pts(waypoints)).append(',');
            b.append("\"zone\":").append(pts(school.zone)).append(',');
            b.append("\"pace\":").append(parseInt(edPace, 330)).append(',');
            b.append("\"spm\":").append(parseInt(edSpm, 172)).append(',');
            b.append("\"rest\":").append(parseDouble(edRest, 0.8)).append(',');
            b.append("\"rad\":").append(parseInt(edRad, 25)).append(',');
            b.append("\"target\":").append(parseInt(edTarget, 800)).append(',');
            b.append("\"vmax\":").append(parseDouble(edVmax, 6.5)).append(',');
            b.append("\"sat\":").append(parseInt(edSat, 12)).append(',');
            b.append("\"wifiN\":").append(parseInt(edWifiN, 4)).append(',');
            b.append("\"cellN\":").append(parseInt(edCellN, 3)).append(',');
            b.append("\"jPace\":").append(jPace).append(',');
            b.append("\"jRest\":").append(jRest).append(',');
            b.append("\"jSpm\":").append(jSpm).append(',');
            b.append("\"jSat\":").append(jSat).append(',');
            b.append("\"jWifi\":").append(jWifi).append(',');
            b.append("\"jCell\":").append(jCell).append(',');
            b.append("\"amapKey\":").append(q(amapKey)).append(',');
            b.append("\"runToken\":").append(runToken);
            b.append('}');
            Prefs.save(this, b.toString());
        } catch (Throwable ignored) { }
    }

    private void restoreAll() {
        String txt = Prefs.load(this);
        if (txt == null || txt.length() < 5) return;
        try {
            java.util.Map<String, Object> m = Json.obj(Json.parse(txt));
            defName = Json.str(m, "defName", "");
            double dla = Json.num(m, "defLat", Double.NaN);
            double dlo = Json.num(m, "defLng", Double.NaN);
            defLat = dla;
            defLng = dlo;

            List<double[]> route = parsePts(Json.arr(m.get("route")));
            List<double[]> cps = parsePts(Json.arr(m.get("cps")));
            List<double[]> wps = parsePts(Json.arr(m.get("wps")));
            if (!route.isEmpty()) {
                map.setData(route, cps, Json.bool(m, "closed", false));
                int si = (int) Json.num(m, "start", 0);
                int ei = (int) Json.num(m, "end", route.size() - 1);
                map.setStartIndex(Math.max(0, Math.min(si, route.size() - 1)));
                map.setEndIndex(Math.max(0, Math.min(ei, route.size() - 1)));
            }
            waypoints.clear();
            waypoints.addAll(wps);
            map.setWaypoints(waypoints);
            List<double[]> z = parsePts(Json.arr(m.get("zone")));
            if (!z.isEmpty()) { school.zone.clear(); school.zone.addAll(z); }

            edPace.setText(String.valueOf((int) Json.num(m, "pace", 330)));
            edSpm.setText(String.valueOf((int) Json.num(m, "spm", 172)));
            edRest.setText(String.valueOf(Json.num(m, "rest", 0.8)));
            edRad.setText(String.valueOf((int) Json.num(m, "rad", 25)));
            edTarget.setText(String.valueOf((int) Json.num(m, "target", 800)));
            edVmax.setText(String.valueOf(Json.num(m, "vmax", 6.5)));
            edSat.setText(String.valueOf((int) Json.num(m, "sat", 12)));
            edWifiN.setText(String.valueOf((int) Json.num(m, "wifiN", 4)));
            edCellN.setText(String.valueOf((int) Json.num(m, "cellN", 3)));
            jPace = Json.num(m, "jPace", 30);
            jRest = Json.num(m, "jRest", 0.2);
            jSpm = (int) Json.num(m, "jSpm", 5);
            jSat = (int) Json.num(m, "jSat", 1);
            jWifi = (int) Json.num(m, "jWifi", 1);
            jCell = (int) Json.num(m, "jCell", 1);

            amapKey = Json.str(m, "amapKey", "");
            coord = Json.str(m, "coord", coord);
            if (bCoord != null) bCoord.setText("坐标:" + coord.toUpperCase());
            srcMode = Json.str(m, "src", srcMode);
            if (bRoad != null) bRoad.setText("源:" + ("road".equals(srcMode) ? "路网"
                    : ("geom".equals(srcMode) ? "几何" : "校园")));
            roadMode = Json.bool(m, "roadMode", roadMode);
            state = Json.str(m, "state", state);
            runToken = (long) Json.num(m, "runToken", runToken);

            double z2 = Json.num(m, "zoom", 0);
            double clat = Json.num(m, "clat", Double.NaN);
            double clng = Json.num(m, "clng", Double.NaN);
            if (!Double.isNaN(clat) && !Double.isNaN(clng)) {
                map.setCenter(clat, clng, z2 > 0 ? z2 : 0);
            } else if (!route.isEmpty()) {
                map.fitRoute();
            }
            setMode(Json.str(m, "mode", mode));
            updateHud();
        } catch (Throwable ignored) { }
    }

    @Override
    protected void onPause() {
        saveState();
        super.onPause();
    }

    private static String q(String s) {
        if (s == null) return "\"\"";
        StringBuilder b = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\\') b.append('\\');
            if (c == '\n') { b.append("\\n"); continue; }
            b.append(c);
        }
        return b.append('"').toString();
    }

    private static String pts(List<double[]> l) {
        StringBuilder b = new StringBuilder("[");
        for (int i = 0; i < l.size(); i++) {
            double[] p = l.get(i);
            if (i > 0) b.append(',');
            b.append('[').append(p[0]).append(',').append(p[1]).append(']');
        }
        return b.append(']').toString();
    }

    private List<double[]> parsePts(List<Object> arr) {
        List<double[]> out = new ArrayList<double[]>();
        for (Object o : arr) {
            List<Object> p = Json.arr(o);
            if (p.size() >= 2 && p.get(0) instanceof Number && p.get(1) instanceof Number) {
                out.add(new double[]{((Number) p.get(0)).doubleValue(), ((Number) p.get(1)).doubleValue()});
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- 回调
    @Override
    public void onTap(double lat, double lng) {
        dirty = true;
        if ("road".equals(mode)) {
            if (curRoad == null) newRoad();
            curRoad.add(new double[]{lat, lng});
            pushSchoolToMap();
            toast("道路点 " + curRoad.size());
            updateHud();
            return;
        }
        if ("zone".equals(mode)) {
            school.zone.add(new double[]{lat, lng});
            pushSchoolToMap();
            toast("跑区点 " + school.zone.size());
            updateHud();
            return;
        }
        if ("wp".equals(mode)) {
            waypoints.add(new double[]{lat, lng});
            map.setWaypoints(waypoints);
            toast("途经点 " + waypoints.size() + " 已添加");
            updateHud();
            return;
        }
        List<double[]> n = map.nodes();
        List<double[]> c = map.cps();
        if ("cp".equals(mode)) c.add(new double[]{lat, lng});
        else n.add(new double[]{lat, lng});
        map.setData(n, c, map.closed());
        updateHud();
    }

    @Override
    public void onChange() { updateHud(); }

    @Override
    public boolean onKeyDown(int code, android.view.KeyEvent e) {
        if (code == android.view.KeyEvent.KEYCODE_PLUS || code == android.view.KeyEvent.KEYCODE_EQUALS
                || code == android.view.KeyEvent.KEYCODE_PAGE_UP) { map.zoomBy(1); updateHud(); return true; }
        if (code == android.view.KeyEvent.KEYCODE_MINUS || code == android.view.KeyEvent.KEYCODE_PAGE_DOWN) {
            map.zoomBy(-1); updateHud(); return true;
        }
        return super.onKeyDown(code, e);
    }

    // ---------------------------------------------------------------- HUD / 控件
    private void updateHud() {
        List<double[]> n = map.nodes();
        double len = 0;
        for (int i = 1; i < n.size(); i++) {
            len += Route.haversine(n.get(i - 1)[0], n.get(i - 1)[1], n.get(i)[0], n.get(i)[1]);
        }
        if (map.closed() && n.size() > 2) {
            len += Route.haversine(n.get(n.size() - 1)[0], n.get(n.size() - 1)[1], n.get(0)[0], n.get(0)[1]);
        }
        int pace = parseInt(edPace, 330);
        int spm = parseInt(edSpm, 172);
        int steps = (int) Math.round(len / (1000.0 / pace) * spm / 60);
        int t = (int) Math.round(len * pace / 1000);
        double v = 1000.0 / Math.max(120, pace);
        int sel = map.selectedIndex();
        boolean loop = (map.startIndex() == map.endIndex());

        StringBuilder miss = new StringBuilder();
        List<double[]> cps = map.cps();
        int rad = parseInt(edRad, 25);
        for (int i = 0; i < cps.size(); i++) {
            if (distToRoute(cps.get(i)) > rad) miss.append((char) ('A' + i)).append(' ');
        }

        hud.setText(String.format(
                "状态 %s · %s · 坐标 %s%n总长 %.0f m · 预计 %d:%02d · 步数≈%d%n配速 %d s/km（%.2f m/s，%.1f km/h）· 步频 %d%n起点#%d → 终点#%d · 途经 %d · %s%n点位 %d · 打卡 %d%s · 选中 %s · z%.1f%s%s",
                state, loop ? "环绕" : "单程", coord.toUpperCase(),
                len, t / 60, t % 60, steps, pace, v, v * 3.6, spm,
                map.startIndex() + 1, map.endIndex() + 1, waypoints.size(),
                loop ? "跑完继续绕圈" : "到终点自动停",
                n.size(), cps.size(),
                miss.length() > 0 ? ("（⚠ 不在路线上: " + miss.toString().trim() + "）") : "",
                sel < 0 ? "无" : ("#" + (sel + 1)), map.zoomLevel(),
                autoNote.isEmpty() ? "" : ("\n自动: " + autoNote),
                (school.hasRoads() ? String.format("%n校园图 %s · 路 %d 段 %.0f m · 跑区 %d 点",
                        school.school.isEmpty() ? "(未命名)" : school.school,
                        school.roadCount(), school.totalLength(), school.zone.size()) : "")
                        + (dirty ? "\n⚠ 改动未下发（点「开始」或「保存」生效）" : "")));
    }

    private double distToRoute(double[] p) {
        List<double[]> n = map.nodes();
        if (n.isEmpty()) return Double.MAX_VALUE;
        double best = Double.MAX_VALUE;
        boolean closed = map.closed();
        int last = closed ? n.size() : n.size() - 1;
        for (int i = 0; i < last; i++) {
            double[] a = n.get(i), b = n.get((i + 1) % n.size());
            best = Math.min(best, distToSeg(p, a, b));
        }
        return best;
    }

    private double distToSeg(double[] p, double[] a, double[] b) {
        double kx = 111320.0 * Math.cos(Math.toRadians(p[0]));
        double px = (p[1] - a[1]) * kx, py = (p[0] - a[0]) * 110540.0;
        double bx = (b[1] - a[1]) * kx, by = (b[0] - a[0]) * 110540.0;
        double len2 = bx * bx + by * by;
        double tt = len2 > 1e-9 ? (px * bx + py * by) / len2 : 0;
        tt = Math.max(0, Math.min(1, tt));
        return Math.hypot(px - bx * tt, py - by * tt);
    }

    private Button mkBtn(LinearLayout parent, String text, boolean primary) {
        Button bt = new Button(this);
        bt.setText(text);
        bt.setTextSize(12);
        bt.setAllCaps(false);
        bt.setSingleLine(true);
        bt.setTextSize(12);
        bt.setPadding(6, 4, 6, 4);
        if (primary) bt.setTextColor(0xFF58A6FF);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
        lp.setMargins(0, 0, 6, 0);
        parent.addView(bt, lp);
        return bt;
    }

    private EditText mkText(LinearLayout parent, String hint, String def) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setText(def);
        e.setTextSize(13);
        e.setSingleLine(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 3f);
        lp.setMargins(0, 0, 6, 0);
        parent.addView(e, lp);
        return e;
    }

    private EditText mkField(LinearLayout parent, String label, String def) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextSize(10);
        t.setTextColor(0xFF8FA3B8);
        col.addView(t);
        EditText e = new EditText(this);
        e.setText(def);
        e.setTextSize(14);
        e.setSingleLine(true);
        e.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        e.setTextColor(0xFFE6EDF3);
        e.setBackgroundColor(0xFF16202B);
        e.setPadding(14, 8, 14, 8);
        col.addView(e);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
        lp.setMargins(0, 0, 8, 0);
        parent.addView(col, lp);
        return e;
    }

    /** root 已授权时，把当前配置额外写到 /data/local/tmp/campusim.json（模块的文件兜底路径） */
    private void writeConfigAsRoot() {
        new Thread(new Runnable() {
            public void run() {
                try {
                    final String json = buildJson(false, true);
                    java.io.File f = new java.io.File(getFilesDir(), "campusim.json");
                    java.io.FileOutputStream os = new java.io.FileOutputStream(f);
                    os.write(json.getBytes("UTF-8"));
                    os.close();
                    Root.Exec r = Root.su("cp " + f.getAbsolutePath()
                            + " /data/local/tmp/campusim.json && chmod 644 /data/local/tmp/campusim.json && echo OK", 6000);
                    final String msg = (r.out + r.err).trim();
                    runOnUiThread(new Runnable() {
                        public void run() {
                            toast(msg.contains("OK") ? "配置已写入 /data/local/tmp/campusim.json"
                                    : "写配置失败: " + msg, Toast.LENGTH_LONG);
                        }
                    });
                } catch (Throwable e) {
                    runOnUiThread(new Runnable() {
                        public void run() { toast("写配置异常: " + e); }
                    });
                }
            }
        }, "campus-root-cfg").start();
    }

    /** 面板展开时缩放按钮藏起来（否则会被面板压住），收起后回到右下角 */
    private void updateZoomPos() {
        if (panelRef == null || zoomInBtn == null) return;
        boolean expanded = panelRef.getVisibility() == View.VISIBLE;
        zoomInBtn.setVisibility(expanded ? View.INVISIBLE : View.VISIBLE);
        zoomOutBtn.setVisibility(expanded ? View.INVISIBLE : View.VISIBLE);
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    private void toast(String s, int len) { Toast.makeText(this, s, len).show(); }

    private int parseInt(EditText e, int def) {
        try { return Integer.parseInt(e.getText().toString().trim()); } catch (Throwable t) { return def; }
    }

    private double parseDouble(EditText e, double def) {
        try { return Double.parseDouble(e.getText().toString().trim()); } catch (Throwable t) { return def; }
    }
}
