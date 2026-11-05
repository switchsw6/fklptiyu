#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""把 GPX 轨迹或「中心点 + 周长」转换成 campusim.conf 的 route= 行。

用法:
  python mkroute.py gpx  track.gpx  [--simplify 1.0] [--out campusim.conf]
  python mkroute.py loop 30.4620 114.4350 --len 500 [--out campusim.conf]
  python mkroute.py push <conf>        # 直接 adb push 到 /data/local/tmp/campusim.conf
"""
import math, sys, os, subprocess


def hav(a, b):
    R = 6371000.0
    la1, lo1 = map(math.radians, a)
    la2, lo2 = map(math.radians, b)
    h = math.sin((la2 - la1) / 2) ** 2 + math.cos(la1) * math.cos(la2) * math.sin((lo2 - lo1) / 2) ** 2
    return 2 * R * math.asin(math.sqrt(h))


def from_gpx(path, tol=1.0):
    import xml.etree.ElementTree as ET
    t = ET.parse(path)
    pts = []
    for el in t.iter():
        tag = el.tag.split('}')[-1]
        if tag == 'trkpt' or tag == 'rtept' or tag == 'wpt':
            pts.append((float(el.get('lat')), float(el.get('lon'))))
    out = []
    for p in pts:                       # 按距离抽稀，避免几百个点塞进 conf
        if not out or hav(out[-1], p) >= tol:
            out.append(p)
    if out and hav(out[0], out[-1]) > 30:  # 首尾未闭合则闭合
        out.append(out[0])
    return out


def loop(lat, lng, length=500.0, sides=8, ratio=0.62):
    """生成一个周长约 length 米的闭合多边形（跑道形）"""
    per = length / sides
    r = per / (2 * math.sin(math.pi / sides))
    dlat = 1.0 / 111320.0
    dlng = 1.0 / (111320.0 * math.cos(math.radians(lat)))
    pts = []
    for i in range(sides):
        ang = 2 * math.pi * i / sides
        pts.append((lat + r * math.sin(ang) * dlat, lng + r * math.cos(ang) * dlng * ratio))
    pts.append(pts[0])
    return pts


def emit(pts, out=None, fmt="json", extra=None):
    pts = [[round(a, 6), round(b, 6)] for a, b in pts]
    if fmt == "conf":
        lines = ["# campusim route (WGS-84)"]
        for la, lo in pts:
            lines.append("route=%.6f,%.6f" % (la, lo))
        txt = "\n".join(lines) + "\n"
    else:
        cfg = {
            "enabled": True,
            "targets": ["com.lptiyu.tanke"],
            "route": pts,
            "checkpoints": [],
            "checkpointRadius": 25,
            "paceSecPerKm": 330,
            "speedJitter": 0.06,
            "spmBase": 172,
            "spmJitter": 4,
            "restsPerKm": 0.8,
            "restMinSec": 15,
            "restMaxSec": 60,
            "randomStart": True,
            "randomDir": True,
            "heartbeat": 10,
            "seed": 0,
            "wifi": True, "cell": True, "ble": True, "accel": True,
        }
        if extra:
            cfg.update(extra)
        txt = json.dumps(cfg, ensure_ascii=False, indent=2) + "\n"
    if out:
        with open(out, "w", encoding="utf-8") as f:
            f.write(txt)
        print("%d pts -> %s (%s)" % (len(pts), out, fmt))
    else:
        print(txt)


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        return
    mode = sys.argv[1]
    fmt = sys.argv[sys.argv.index("--format") + 1] if "--format" in sys.argv else "json"
    out = sys.argv[sys.argv.index("--out") + 1] if "--out" in sys.argv else None
    if mode == "gpx":
        tol = float(sys.argv[sys.argv.index("--simplify") + 1]) if "--simplify" in sys.argv else 1.0
        emit(from_gpx(sys.argv[2], tol), out, fmt)
    elif mode == "loop":
        lat, lng = float(sys.argv[2]), float(sys.argv[3])
        ln = float(sys.argv[sys.argv.index("--len") + 1]) if "--len" in sys.argv else 500.0
        emit(loop(lat, lng, ln), out, fmt)
    elif mode == "push":
        subprocess.check_call(["adb", "push", sys.argv[2], "/data/local/tmp/campusim.json"])
        subprocess.check_call(["adb", "shell", "chmod", "644", "/data/local/tmp/campusim.json"])
    else:
        print(__doc__)


if __name__ == "__main__":
    main()
