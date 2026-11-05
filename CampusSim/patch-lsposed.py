#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""LSPosed apk_path 同步工具。

重装 CampusSim 后，LSPosed 记录的 APK 绝对路径会失效（模块不再注入）。
这个脚本把 modules_config.db 里的 apk_path 改成新路径，并顺手清掉一处
误勾的作用域（com.coloros.childrenspace）。

用法：
    ADB=$(which adb)
    adb shell su -c "cp /data/adb/lspd/config/modules_config.db /data/local/tmp/m.db && chmod 666 /data/local/tmp/m.db"
    adb pull /data/local/tmp/m.db m.db
    python patch-lsposed.py m.db "$(adb shell pm path com.sim.campusrun | sed 's/package://')" m2.db
    adb push m2.db /data/local/tmp/m2.db
    adb shell su -c "rm -f /data/adb/lspd/config/modules_config*.db*; \
        cp /data/local/tmp/m2.db /data/adb/lspd/config/modules_config.db; \
        chown root:root /data/adb/lspd/config/modules_config.db; \
        chmod 600 /data/adb/lspd/config/modules_config.db; \
        restorecon /data/adb/lspd/config/modules_config.db"
    adb reboot

参数：<输入 db> <新 apk 路径> <输出 db>
"""
import os
import sqlite3
import sys

MODULE = "com.sim.campusrun"


def main():
    if len(sys.argv) < 4:
        print(__doc__)
        return 1
    src, apk, out = sys.argv[1], sys.argv[2], sys.argv[3]

    c = sqlite3.connect(src)
    c.execute("update modules set apk_path=? where module_pkg_name=?", (apk, MODULE))
    c.execute("delete from scope where module_pkg_name=? and app_pkg_name='com.coloros.childrenspace'",
              (MODULE,))
    c.commit()
    print("modules:", list(c.execute("select module_pkg_name, apk_path from modules")))
    print("scope:", list(c.execute("select module_pkg_name, app_pkg_name from scope")))
    c.close()

    if os.path.exists(out):
        os.remove(out)
    c2 = sqlite3.connect(src)
    c2.execute("VACUUM INTO ?", (out,))
    c2.close()
    print("ok:", out, os.path.getsize(out), "bytes")
    return 0


if __name__ == "__main__":
    sys.exit(main())
