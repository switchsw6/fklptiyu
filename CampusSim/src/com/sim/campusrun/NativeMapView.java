package com.sim.campusrun;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.util.LruCache;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 原生地图控件：自绘高德瓦片（Web Mercator），完全不经�?WebView�? * 手势：单指拖动平移、双�?双击缩放、点击加点（�?Activity 监听）�? */
public class NativeMapView extends View {

    public interface Listener {
        void onTap(double latWgs, double lngWgs);
        void onChange();
    }

    private static final int TILE = 256;
    private static final String TILE_URL =
            "https://webrd01.is.autonavi.com/appmaptile?lang=zh_cn&size=1&scale=1&style=8&x=%d&y=%d&z=%d";

    private double zoom = 17.0;              // 连续缩放
    private double centerX, centerY;         // world px（当�?tileZoom 下）
    private double centerLatGcj = 30.4620, centerLngGcj = 114.4350;

    private final Paint pLine = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pNode = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pCp = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pGrid = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pScale = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final LruCache<String, Bitmap> mem = new LruCache<String, Bitmap>(96) {
        protected int sizeOf(String k, Bitmap b) { return 1; }
    };
    private final Set<String> loading = new HashSet<String>();
    private final ExecutorService pool = Executors.newFixedThreadPool(4);
    private final File cacheDir;

    private final List<double[]> nodes = new ArrayList<double[]>();   // WGS
    private final List<double[]> cps = new ArrayList<double[]>();     // WGS
    private boolean closed = false;

    private Listener listener;
    private float lastX, lastY, downX, downY;
    private boolean dragging, moved;
    private int dragNode = -1;
    private float dragOffX, dragOffY;
    private Runnable longPress;
    private final ScaleGestureDetector scaleDetector;

    public NativeMapView(Context c, AttributeSet a) {
        super(c, a);
        cacheDir = new File(c.getCacheDir(), "tiles");
        if (!cacheDir.exists()) cacheDir.mkdirs();
        setWillNotDraw(false);

        pLine.setStyle(Paint.Style.STROKE);
        pLine.setStrokeWidth(dp(5));
        pLine.setStrokeCap(Paint.Cap.ROUND);
        pLine.setColor(0xFF2F81F7);

        pNode.setStyle(Paint.Style.FILL);
        pNode.setColor(0xFF1F6FEB);

        pCp.setStyle(Paint.Style.FILL);
        pCp.setColor(0xFFF0883E);

        pText.setColor(0xFFE6EDF3);
        pText.setTextSize(dp(12));

        pGrid.setColor(0xFF3D5568);
        pGrid.setTextSize(dp(10));

        pScale.setColor(0xFF8FA3B8);
        pScale.setStrokeWidth(dp(2));

        scaleDetector = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector d) {
                setZoom(zoom + Math.log(d.getScaleFactor()) / Math.log(2));
                return true;
            }
        });

        centerX = lonToX(centerLngGcj, (int) Math.floor(zoom));
        centerY = latToY(centerLatGcj, (int) Math.floor(zoom));
    }

    private float dp(float v) { return v * getResources().getDisplayMetrics().density; }

    // ---------------------------------------------------------------- 数据
    public void setData(List<double[]> n, List<double[]> c, boolean cl) {
        // 必须先把入参拷贝出来：调用方可能直接把 nodes() 传回来，
        // 那样 clear() 会把入参一起清空，表现就是“撤销 = 清空”
        List<double[]> nn = new ArrayList<double[]>(n);
        List<double[]> cc = new ArrayList<double[]>(c);
        if (selected >= nn.size()) selected = nn.size() - 1;
        if (startIdx >= nn.size()) startIdx = 0;
        if (endIdx >= nn.size()) endIdx = nn.size() - 1;
        nodes.clear();
        nodes.addAll(nn);
        cps.clear();
        cps.addAll(cc);
        closed = cl;
        invalidate();
    }

    /** 返回副本，外部随便改都不会影响控件内部状态 */
    public List<double[]> nodes() { return new ArrayList<double[]>(nodes); }

    public List<double[]> cps() { return new ArrayList<double[]>(cps); }
    public boolean closed() { return closed; }
    public void setClosed(boolean b) { closed = b; invalidate(); }
    public double zoomLevel() { return zoom; }
    public void zoomBy(double d) { setZoom(zoom + d); }
    public void panBy(float dxPx, float dyPx) {
        float s = scale();
        centerX += dxPx / s;
        centerY += dyPx / s;
        invalidate();
    }

    public void centerOnRoute() { fitRoute(); }

    /** 按轨迹（含预览线与途经点）的外接矩形自动选中心与缩放 */
    public void fitRoute() {
        java.util.List<double[]> all = new ArrayList<double[]>();
        all.addAll(nodes);
        all.addAll(waypoints);
        all.addAll(preview);
        if (all.isEmpty()) return;
        double minLat = 90, maxLat = -90, minLng = 180, maxLng = -180;
        for (double[] p : all) {
            minLat = Math.min(minLat, p[0]); maxLat = Math.max(maxLat, p[0]);
            minLng = Math.min(minLng, p[1]); maxLng = Math.max(maxLng, p[1]);
        }
        double[] cG = Gps.gcj((minLat + maxLat) / 2, (minLng + maxLng) / 2);
        centerLatGcj = cG[0]; centerLngGcj = cG[1];
        double[] minG = Gps.gcj(minLat, minLng), maxG = Gps.gcj(maxLat, maxLng);
        int w = getWidth() > 0 ? getWidth() : 1000, h = getHeight() > 0 ? getHeight() : 1000;
        int pick = 17;
        for (int z = 18; z >= 3; z--) {
            double dx = Math.abs(lonToX(maxG[1], z) - lonToX(minG[1], z));
            double dy = Math.abs(latToY(minG[0], z) - latToY(maxG[0], z));
            if (dx < w * 0.8 && dy < h * 0.8) { pick = z; break; }
        }
        zoom = pick;
        centerX = lonToX(centerLngGcj, pick);
        centerY = latToY(centerLatGcj, pick);
        invalidate();
        if (listener != null) listener.onChange();
    }

    // ---- 选点 / 起点终点
    private int selected = -1;
    private int startIdx = 0;
    private int endIdx = -1;

    public int selectedIndex() { return selected; }

    /** 离屏幕中心最近的节点（没手动选中时的兜底） */
    public int nearestIndexToCenter() {
        int best = -1; double bd = Double.MAX_VALUE;
        float cx = getWidth() / 2f, cy = getHeight() / 2f;
        for (int i = 0; i < nodes.size(); i++) {
            float[] pt = screen(nodes.get(i)[0], nodes.get(i)[1]);
            double d = Math.hypot(pt[0] - cx, pt[1] - cy);
            if (d < bd) { bd = d; best = i; }
        }
        return best;
    }
    public void setSelected(int i) { selected = i; invalidate(); if (listener != null) listener.onChange(); }
    public int startIndex() { return startIdx; }
    public int endIndex() { return endIdx < 0 ? Math.max(0, nodes.size() - 1) : endIdx; }
    public void setStartIndex(int i) { startIdx = i; invalidate(); }
    public void setEndIndex(int i) { endIdx = i; invalidate(); }

    /** 点是否落在某个节点上（屏幕 22dp 内） */
    private int hitNode(float x, float y) {
        for (int i = 0; i < nodes.size(); i++) {
            float[] pt = screen(nodes.get(i)[0], nodes.get(i)[1]);
            if (Math.hypot(pt[0] - x, pt[1] - y) < dp(26)) return i;
        }
        return -1;
    }
    public void setListener(Listener l) { listener = l; }

    /** 把视角移到指定 WGS-84 坐标；z<=0 表示保持当前层级 */
    public void setCenter(double latWgs, double lngWgs, double z) {
        double[] g = Gps.gcj(latWgs, lngWgs);
        centerLatGcj = g[0];
        centerLngGcj = g[1];
        if (z > 0) zoom = Math.max(3, Math.min(MAX_ZOOM, z));
        int tz = tileZoom();
        centerX = lonToX(centerLngGcj, tz);
        centerY = latToY(centerLatGcj, tz);
        invalidate();
        if (listener != null) listener.onChange();
    }

    /** 当前视角中心的 WGS-84 坐标（存档用） */
    public double[] centerWgs() {
        return Gps.wgs(centerLatGcj, centerLngGcj);
    }

    private void setZoom(double z) {
        z = Math.max(3, Math.min(MAX_ZOOM, z));
        double[] ll = centerLatLng();
        zoom = z;
        int tz = tileZoom();
        centerX = lonToX(ll[1], tz);
        centerY = latToY(ll[0], tz);
        invalidate();
        if (listener != null) listener.onChange();
    }

    /** 瓦片只到 z18（高德上限），更高层级靠放大瓦片实现，画面糊但落点更精确 */
    static final int MAX_ZOOM = 22;
    static final int MAX_TILE_Z = 18;

    private int tileZoom() { return Math.min(MAX_TILE_Z, (int) Math.floor(zoom)); }

    private float scale() { return (float) Math.pow(2, zoom - tileZoom()); }

    // ---------------------------------------------------------------- 投影
    private static double worldSize(int z) { return TILE * Math.pow(2, z); }

    static double lonToX(double lng, int z) { return (lng + 180.0) / 360.0 * worldSize(z); }

    static double latToY(double lat, int z) {
        double s = Math.sin(Math.toRadians(lat));
        return (0.5 - Math.log((1 + s) / (1 - s)) / (4 * Math.PI)) * worldSize(z);
    }

    static double xToLon(double x, int z) { return x / worldSize(z) * 360.0 - 180.0; }

    static double yToLat(double y, int z) {
        double n = Math.PI - 2.0 * Math.PI * y / worldSize(z);
        return Math.toDegrees(Math.atan(Math.sinh(n)));
    }

    /** 视图中心（GCJ�?*/
    private double[] centerLatLng() {
        int tz = tileZoom();
        return new double[]{yToLat(centerY, tz), xToLon(centerX, tz)};
    }

    /** WGS �?-> 屏幕像素 */
    private float[] screen(double latWgs, double lngWgs) {
        double[] g = Gps.gcj(latWgs, lngWgs);
        int tz = tileZoom();
        double wx = lonToX(g[1], tz), wy = latToY(g[0], tz);
        float s = scale();
        return new float[]{
                (float) (getWidth() / 2f + (wx - centerX) * s),
                (float) (getHeight() / 2f + (wy - centerY) * s)
        };
    }

    /** 屏幕像素 -> WGS �?*/
    private double[] toWgs(float sx, float sy) {
        int tz = tileZoom();
        float s = scale();
        double wx = centerX + (sx - getWidth() / 2f) / s;
        double wy = centerY + (sy - getHeight() / 2f) / s;
        double lat = yToLat(wy, tz), lng = xToLon(wx, tz);
        return Gps.wgs(lat, lng);
    }

    // ---------------------------------------------------------------- 触摸
    private final android.view.GestureDetector gesture =
            new android.view.GestureDetector(getContext(), new android.view.GestureDetector.SimpleOnGestureListener() {
                @Override
                public boolean onDoubleTap(MotionEvent e) {
                    setZoom(zoom + 1);
                    return true;
                }
            });

    /** 鼠标滚轮 / 触控板滚动缩放（scrcpy、OTG 鼠标、投屏都走这里） */
    @Override
    public boolean onGenericMotionEvent(MotionEvent e) {
        if (e.getActionMasked() == MotionEvent.ACTION_SCROLL) {
            float v = e.getAxisValue(MotionEvent.AXIS_VSCROLL);
            if (v == 0) v = e.getAxisValue(MotionEvent.AXIS_HSCROLL);
            if (v != 0) {
                setZoom(zoom - v * 0.6);
                return true;
            }
        }
        return super.onGenericMotionEvent(e);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        scaleDetector.onTouchEvent(e);
        gesture.onTouchEvent(e);
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                lastX = downX = e.getX(); lastY = downY = e.getY();
                dragging = true; moved = false;
                // 清掉上一轮可能残留的长按任务，避免把上一次的节点拖走
                if (longPress != null) { removeCallbacks(longPress); longPress = null; }
                // 长按节点 350ms 进入拖动（此时不再平移地图）
                final int hit = hitNode(e.getX(), e.getY());
                if (hit >= 0) {
                    dragNode = -1;
                    longPress = new Runnable() {
                        public void run() {
                            if (!dragging || hit >= nodes.size()) return;
                            // 只要手指还落在该节点的命中圈内就开始拖动
                            // （允许长按期间轻微移动，避免必须“纹丝不动”）
                            float[] sp = screen(nodes.get(hit)[0], nodes.get(hit)[1]);
                            double d = Math.hypot(lastX - sp[0], lastY - sp[1]);
                            if (d > dp(30)) return;
                            dragOffX = sp[0] - lastX;
                            dragOffY = sp[1] - lastY;
                            dragNode = hit;
                            setSelected(hit);
                            invalidate();
                        }
                    };
                    postDelayed(longPress, 350);
                }
                break;
            }
            case MotionEvent.ACTION_MOVE: {
                float dx = e.getX() - lastX, dy = e.getY() - lastY;
                if (Math.abs(e.getX() - downX) + Math.abs(e.getY() - downY) > dp(6)) moved = true;
                if (dragNode >= 0 && dragNode < nodes.size()) {
                    // 拖动节点：跟手更新该点坐标（带按下偏移，避免跳一下）
                    List<double[]> n = nodes();
                    n.set(dragNode, toWgs(e.getX() + dragOffX, e.getY() + dragOffY));
                    nodes.clear();
                    nodes.addAll(n);
                    invalidate();
                } else if (dragging && e.getPointerCount() == 1) {
                    float s = scale();
                    centerX -= dx / s; centerY -= dy / s;
                    invalidate();
                }
                lastX = e.getX(); lastY = e.getY();
                break;
            }
            case MotionEvent.ACTION_UP: {
                boolean wasDrag = dragNode >= 0;
                dragNode = -1;
                dragging = false;
                if (wasDrag) {
                    if (listener != null) listener.onChange();
                } else if (!moved) {
                    int hit = hitNode(e.getX(), e.getY());
                    if (hit >= 0) { setSelected(hit); }
                    else if (listener != null) {
                        double[] ll = toWgs(e.getX(), e.getY());
                        listener.onTap(ll[0], ll[1]);
                    }
                }
                break;
            }
            case MotionEvent.ACTION_CANCEL:
                if (longPress != null) { removeCallbacks(longPress); longPress = null; }
                dragNode = -1;
                dragging = false;
                break;
        }
        return true;
    }

    /** 微调：按米移动某个节点 */
    public void moveNode(int idx, double dNorthM, double dEastM) {
        if (idx < 0 || idx >= nodes.size()) return;
        double[] p = nodes.get(idx);
        double lat = p[0] + dNorthM / 110540.0;
        double lng = p[1] + dEastM / (111320.0 * Math.cos(Math.toRadians(p[0])));
        List<double[]> n = nodes();
        n.set(idx, new double[]{lat, lng});
        nodes.clear();
        nodes.addAll(n);
        invalidate();
        if (listener != null) listener.onChange();
    }

    /** 删除节点 */
    public void removeNode(int idx) {
        if (idx < 0 || idx >= nodes.size()) return;
        List<double[]> n = nodes();
        n.remove(idx);
        setData(n, cps, closed);
        selected = -1;
        invalidate();
        if (listener != null) listener.onChange();
    }

    // ---- 预览线（自动生成的方案，虚线）
    private List<double[]> preview = new ArrayList<double[]>();

    public void setPreview(List<double[]> p) {
        preview = p == null ? new ArrayList<double[]>() : new ArrayList<double[]>(p);
        invalidate();
    }

    public List<double[]> preview() { return new ArrayList<double[]>(preview); }

    // ---- 途经点（自动路线的锚点，画成蓝色菱形）
    private List<double[]> waypoints = new ArrayList<double[]>();

    public void setWaypoints(List<double[]> w) {
        waypoints = w == null ? new ArrayList<double[]>() : new ArrayList<double[]>(w);
        invalidate();
    }

    // ---- 校园地图图层：道路 + 跑区
    private java.util.List<java.util.List<double[]>> roads = new ArrayList<java.util.List<double[]>>();
    private java.util.List<double[]> zone = new ArrayList<double[]>();

    public void setRoads(java.util.List<java.util.List<double[]>> r) {
        roads = new ArrayList<java.util.List<double[]>>();
        if (r != null) for (java.util.List<double[]> one : r) roads.add(new ArrayList<double[]>(one));
        invalidate();
    }

    public void setZone(java.util.List<double[]> z) {
        zone = z == null ? new ArrayList<double[]>() : new ArrayList<double[]>(z);
        invalidate();
    }

    /** 跑区：半透明填充 + 虚线边界 */
    private void drawZoneLayer(Canvas c) {
        if (zone.size() < 3) return;
        Path p = new Path();
        for (int i = 0; i < zone.size(); i++) {
            float[] pt = screen(zone.get(i)[0], zone.get(i)[1]);
            if (i == 0) p.moveTo(pt[0], pt[1]); else p.lineTo(pt[0], pt[1]);
        }
        p.close();
        Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(0x183FB950);
        c.drawPath(p, fill);
        Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth(dp(2));
        edge.setColor(0xAA3FB950);
        edge.setPathEffect(new android.graphics.DashPathEffect(new float[]{dp(12), dp(8)}, 0));
        c.drawPath(p, edge);
    }

    /** 道路图层：深色描边 + 亮色路面 */
    private void drawRoads(Canvas c) {
        if (roads.isEmpty()) return;
        Paint caseP = new Paint(Paint.ANTI_ALIAS_FLAG);
        caseP.setStyle(Paint.Style.STROKE);
        caseP.setStrokeWidth(dp(9));
        caseP.setColor(0xCC0B2E4A);
        Paint lineP = new Paint(Paint.ANTI_ALIAS_FLAG);
        lineP.setStyle(Paint.Style.STROKE);
        lineP.setStrokeWidth(dp(5.5f));
        lineP.setColor(0xFF7FB6E8);
        lineP.setStrokeCap(Paint.Cap.ROUND);
        lineP.setStrokeJoin(Paint.Join.ROUND);
        for (java.util.List<double[]> road : roads) {
            if (road.size() < 2) continue;
            Path p = new Path();
            for (int i = 0; i < road.size(); i++) {
                float[] pt = screen(road.get(i)[0], road.get(i)[1]);
                if (i == 0) p.moveTo(pt[0], pt[1]); else p.lineTo(pt[0], pt[1]);
            }
            c.drawPath(p, caseP);
            c.drawPath(p, lineP);
        }
    }

    private void drawWaypoints(Canvas c) {
        if (waypoints.isEmpty()) return;
        Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        fill.setColor(0xFF58A6FF);
        Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(dp(2));
        stroke.setColor(0xFFFFFFFF);
        Paint txt = new Paint(Paint.ANTI_ALIAS_FLAG);
        txt.setColor(0xFF0D1218);
        txt.setTextSize(dp(10));
        txt.setTextAlign(Paint.Align.CENTER);
        for (int i = 0; i < waypoints.size(); i++) {
            float[] pt = screen(waypoints.get(i)[0], waypoints.get(i)[1]);
            float r = dp(9);
            Path p = new Path();
            p.moveTo(pt[0], pt[1] - r);
            p.lineTo(pt[0] + r, pt[1]);
            p.lineTo(pt[0], pt[1] + r);
            p.lineTo(pt[0] - r, pt[1]);
            p.close();
            c.drawPath(p, fill);
            c.drawPath(p, stroke);
            c.drawText(String.valueOf(i + 1), pt[0], pt[1] + dp(4), txt);
        }
    }

    // ---------------------------------------------------------------- 绘制
    @Override
    protected void onDraw(Canvas c) {
        c.drawColor(0xFF121820);
        int tz = tileZoom();
        float s = scale();
        int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0) return;

        double worldPx = worldSize(tz);
        double left = centerX - w / 2.0 / s;
        double top = centerY - h / 2.0 / s;
        int x0 = (int) Math.floor(left / TILE), x1 = (int) Math.floor((left + w / s) / TILE);
        int y0 = (int) Math.floor(top / TILE), y1 = (int) Math.floor((top + h / s) / TILE);
        int maxIdx = (int) Math.pow(2, tz) - 1;

        int loaded = 0, total = 0;
        for (int tx = x0; tx <= x1; tx++) {
            for (int ty = y0; ty <= y1; ty++) {
                if (ty < 0 || ty > maxIdx) continue;
                int wx = ((tx % (maxIdx + 1)) + maxIdx + 1) % (maxIdx + 1);
                total++;
                Bitmap b = tileBitmap(tz, wx, ty);
                float px = (float) ((tx * TILE - left) * s);
                float py = (float) ((ty * TILE - top) * s);
                int size = (int) Math.ceil(TILE * s) + 1;
                if (b != null) {
                    loaded++;
                    c.drawBitmap(b, new Rect(0, 0, b.getWidth(), b.getHeight()),
                            new Rect((int) px, (int) py, (int) px + size, (int) py + size), null);
                } else {
                    // 占位：画网格
                    Paint ph = pGrid;
                    ph.setStyle(Paint.Style.STROKE);
                    c.drawRect(px, py, px + size, py + size, ph);
                }
            }
        }
        drawTilesBadge(c, loaded, total, tz);
        if (zoom >= 19) drawFineGrid(c);
        drawZoneLayer(c);
        drawRoads(c);
        drawPreview(c);
        drawWaypoints(c);
        drawRoute(c);
        drawScale(c);
    }

    private void drawTilesBadge(Canvas c, int loaded, int total, int z) {
        pText.setColor(0xFF8FA3B8);
        c.drawText("瓦片 " + loaded + "/" + total + " · z" + z, dp(10), getHeight() - dp(10), pText);
    }

    /** 高倍（z≥19）时瓦片已放大发白，叠一层 10m 细网格便于定位 */
    private void drawFineGrid(Canvas c) {
        int tz = tileZoom();
        float s = scale();
        double mPerPx = 156543.03392 * Math.cos(Math.toRadians(centerLatGcj)) / Math.pow(2, zoom);
        double stepM = 10;
        double stepPx = stepM / mPerPx;
        if (stepPx < dp(12)) stepM = 50;
        stepPx = stepM / mPerPx;
        Paint g = new Paint();
        g.setColor(0x334A6B8A);
        g.setStrokeWidth(1);
        int w = getWidth(), h = getHeight();
        double originX = centerX * s, originY = centerY * s;
        double startX = originX - (w / 2.0);
        double startY = originY - (h / 2.0);
        double firstX = Math.ceil(startX / stepPx) * stepPx;
        double firstY = Math.ceil(startY / stepPx) * stepPx;
        for (double x = firstX; x < startX + w; x += stepPx) {
            float px = (float) (x - startX);
            c.drawLine(px, 0, px, h, g);
        }
        for (double y = firstY; y < startY + h; y += stepPx) {
            float py = (float) (y - startY);
            c.drawLine(0, py, w, py, g);
        }
    }

    /** 自动生成的预览线：绿色虚线 */
    private void drawPreview(Canvas c) {
        if (preview.size() < 2) return;
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(dp(4));
        p.setColor(0xFF3FB950);
        p.setPathEffect(new android.graphics.DashPathEffect(new float[]{dp(10), dp(6)}, 0));
        Path path = new Path();
        for (int i = 0; i < preview.size(); i++) {
            float[] pt = screen(preview.get(i)[0], preview.get(i)[1]);
            if (i == 0) path.moveTo(pt[0], pt[1]); else path.lineTo(pt[0], pt[1]);
        }
        c.drawPath(path, p);
    }

    private void drawRoute(Canvas c) {        if (nodes.isEmpty()) return;
        Path path = new Path();
        float[] first = null;
        for (int i = 0; i < nodes.size(); i++) {
            float[] pt = screen(nodes.get(i)[0], nodes.get(i)[1]);
            if (i == 0) { path.moveTo(pt[0], pt[1]); first = pt; }
            else path.lineTo(pt[0], pt[1]);
        }
        if (closed && first != null) path.lineTo(first[0], first[1]);
        c.drawPath(path, pLine);

        for (int i = 0; i < nodes.size(); i++) {
            float[] pt = screen(nodes.get(i)[0], nodes.get(i)[1]);
            c.drawCircle(pt[0], pt[1], dp(7), pNode);
            Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
            ring.setStyle(Paint.Style.STROKE); ring.setStrokeWidth(dp(2)); ring.setColor(Color.WHITE);
            c.drawCircle(pt[0], pt[1], dp(7), ring);
            if (i == selected) {
                Paint sel = new Paint(Paint.ANTI_ALIAS_FLAG);
                sel.setStyle(Paint.Style.STROKE); sel.setStrokeWidth(dp(3)); sel.setColor(0xFFFFD166);
                c.drawCircle(pt[0], pt[1], dp(12), sel);
            }
            if (i == startIdx || i == endIndex()) {
                Paint badge = new Paint(Paint.ANTI_ALIAS_FLAG);
                badge.setColor(0xFF1A7F4B); badge.setTextSize(dp(11)); badge.setTextAlign(Paint.Align.CENTER);
                c.drawText(i == startIdx ? "S" : "E", pt[0], pt[1] - dp(11), badge);
            }
            if (i == 0) {
                pText.setColor(0xFFE6EDF3);
                c.drawText("起", pt[0] + dp(10), pt[1] - dp(10), pText);
            }
        }
        for (int i = 0; i < cps.size(); i++) {
            float[] pt = screen(cps.get(i)[0], cps.get(i)[1]);
            c.drawCircle(pt[0], pt[1], dp(11), pCp);
            Paint t = new Paint(Paint.ANTI_ALIAS_FLAG);
            t.setColor(0xFF111111); t.setTextSize(dp(12)); t.setTextAlign(Paint.Align.CENTER);
            c.drawText(String.valueOf((char) ('A' + i)), pt[0], pt[1] + dp(4), t);
        }
    }

    private void drawScale(Canvas c) {
        // 50m / 100m 之类
        double mPerPx = 156543.03392 * Math.cos(Math.toRadians(centerLatGcj)) / Math.pow(2, zoom);
        int[] cands = {20, 50, 100, 200, 500};
        int pick = 100;
        for (int m : cands) { if (m / mPerPx > dp(60)) { pick = m; break; } }
        float px = (float) (pick / mPerPx);
        float x = getWidth() - px - dp(16), y = getHeight() - dp(28);
        c.drawLine(x, y, x + px, y, pScale);
        c.drawLine(x, y - dp(5), x, y + dp(5), pScale);
        c.drawLine(x + px, y - dp(5), x + px, y + dp(5), pScale);
        pText.setColor(0xFF8FA3B8);
        c.drawText(pick + " m", x + px / 2 - dp(12), y - dp(8), pText);
    }

    // ---------------------------------------------------------------- 瓦片
    private Bitmap tileBitmap(final int z, final int x, final int y) {
        final String key = z + "/" + x + "/" + y;
        Bitmap b = mem.get(key);
        if (b != null) return b;
        File f = new File(cacheDir, z + "_" + x + "_" + y + ".png");
        if (f.exists()) {
            Bitmap d = BitmapFactory.decodeFile(f.getAbsolutePath());
            if (d != null) { mem.put(key, d); return d; }
        }
        if (!loading.contains(key)) {
            loading.add(key);
            pool.execute(new Runnable() {
                public void run() {
                    try {
                        HttpURLConnection conn = (HttpURLConnection) new URL(
                                String.format(TILE_URL, x, y, z)).openConnection();
                        conn.setConnectTimeout(8000);
                        conn.setReadTimeout(8000);
                        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 11)");
                        if (conn.getResponseCode() == 200) {
                            InputStream in = conn.getInputStream();
                            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                            byte[] buf = new byte[8192];
                            int n;
                            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
                            in.close();
                            byte[] data = bo.toByteArray();
                            Bitmap bm = BitmapFactory.decodeByteArray(data, 0, data.length);
                            if (bm != null) {
                                mem.put(key, bm);
                                try {
                                    FileOutputStream os = new FileOutputStream(f);
                                    os.write(data); os.close();
                                } catch (Throwable ignored) { }
                                postInvalidate();
                            }
                        }
                        conn.disconnect();
                    } catch (Throwable ignored) {
                    } finally {
                        loading.remove(key);
                    }
                }
            });
        }
        return null;
    }
}

