# CampusSim
本项目基于Vibe Coding写作模式开发，需求与验收由人主导，AI辅助编码

把「校园跑」这件事在**自己的设备**上完整地跑一遍：轨迹、朝向、步频步幅、传感器、WiFi/基站环境全部自洽，并沿着**你画出来的校园跑道**走。

LSPosed 模块 + 可视化路线编辑器 · 无 native 依赖 · arm64 / x86_64 通用 · **v1.0.0**

> 源码在本仓库；编译好的 APK 见 [Releases](../../releases)。
> 使用前请先读文末的 [免责声明](#免责声明)。

---

## 目录

- [特性](#特性)
- [仓库结构](#仓库结构)
- [构建](#构建)
- [安装：先选一条路](#安装先选一条路)
- [教程 A：Magisk + LSPosed](#教程-amagisk--lsposed)
- [教程 B：KernelSU + ZygiskNext + LSPosed](#教程-bkernelsu--zygisknext--lsposed)
- [教程 C：APatch + ZygiskNext + LSPosed](#教程-capatch--zygisknext--lsposed)
- [教程 D：模拟器（MuMu / 雷电 / 夜神 / BlueStacks / AVD）](#教程-d模拟器mumu--雷电--夜神--bluestacks--avd)
- [把模块导入 LSPosed 并验证](#把模块导入-lsposed-并验证)
- [使用教程](#使用教程)
- [配置项](#配置项)
- [校园地图格式](#校园地图格式)
- [常见问题](#常见问题)
- [已知限制](#已知限制)
- [更新日志](#更新日志)
- [免责声明](#免责声明)
- [License](#license)

---

## 特性

| | |
|---|---|
| **路线** | 手机端自绘高德地图（z3–z22），画线 / 打卡点 / 途经点 / **校园跑道** / **跑区**；节点可选中、设起终点、删除、3 m 微调、长按拖动 |
| **自动路线** | 校园路网图搜索（毫秒级、离线）→ OSRM 公开路网 → 几何绕行，逐级回退；里程按「随机绕行 → 整圈重复 → 折返」补足 |
| **运动模型** | OU 速度、每 100 m 分段配速平滑跟随、步频由速度反算（步数×步幅≈距离）、按里程随机休息 |
| **朝向** | `bearing` 平滑 + 噪声；设备朝向 = 行进方向 + 每 2 分钟变的 ±45° 偏角；陀螺绕 z 轴 = 真实转向速率 |
| **环境** | WiFi 扫描列表、LTE 基站、BLE 扫描、GNSS 卫星与载噪比，条数全部可配 |
| **隐藏** | mock 标记、`Settings.Secure.mock_location`、AppOps、`su` 路径与 `exec("su")` |
| **控制** | App 内 开始 / 暂停 / 继续 / 结束，0.5 s 生效，不需要重装、不需要重启 |
| **参数波动** | 面板「波动」按钮弹窗分项设置：配速 ±s/km、步频 ±spm、休息 ±次/km、卫星 ±、WiFi ±、基站 ±；数量类按 97/71/83 秒周期慢速漂移 |
| **地址搜索** | 标题栏右上角放大镜 → 弹窗搜地名（支持拼音，如 `nankai`）；结果列表按「学校/校区优先」排序，点一条即定位；数据源：高德输入提示（免 key）→ 高德 Web key → 系统 Geocoder → 内置地名表 → OSM |
| **状态记忆** | 退出/重进自动恢复上次的一切：路线、打卡点、途经点、参数、视角、模式、坐标制式、默认地点、高德 key |

---

## 仓库结构

```text
.
├── CampusSim/
│   ├── AndroidManifest.xml
│   ├── build.ps1            # 一键构建（javac → d8 → aapt2 → zipalign → apksigner）
│   ├── patch-lsposed.py     # 重装后同步 LSPosed 里的 apk_path
│   ├── deploy-mumu.ps1      # 模拟器部署（自动探测 adb 端口）
│   ├── simctl.ps1           # status / apply / start / stop / push / log
│   ├── mkroute.py           # GPX / 环线 → 配置 JSON
│   ├── README.md            # 旧版说明（保留）
│   └── src/com/sim/campusrun/
│       ├── MainHook.java       入口：装 hook、20 ms ticker、1 Hz 出定位
│       ├── Route.java          运动模型：速度/步频/休息/打卡/单程环绕
│       ├── Cfg.java            配置加载（socket 优先，文件兜底）
│       ├── HookLoc.java        LocationManager / GNSS / 高德 SDK
│       ├── HookSensor.java     步数 / 加速度 / 陀螺 / 磁力 / 旋转矢量
│       ├── HookNet.java        WiFi / 基站 / BLE
│       ├── HookHide.java       mock 标记 / Settings / AppOps / su
│       ├── MapActivity.java    主界面：编辑、参数、运行控制、导入导出
│       ├── NativeMapView.java  自绘地图控件（瓦片下载 + 缓存 + 绘制）
│       ├── AutoRoute.java      自动路线：图搜索 / OSRM / 几何
│       ├── RoadGraph.java      校园路网图：采样、合并、A*、环、折返
│       ├── Router.java         OSRM HTTP 客户端
│       ├── SchoolMap.java      校园地图数据模型与 JSON 读写
│       └── LocalConfigServer.java
└── tools/
    ├── mapdraw.html         # 电脑端 Leaflet 画线页（备用）
    ├── draw-lite.html       # 手机端网格画布（备用）
    └── mkserver.py          # 本地静态托管 + POST /push
```

---

## 构建

```powershell
cd CampusSim
powershell -ExecutionPolicy Bypass -File .\build.ps1
# 产物：CampusSim/build/CampusSim.apk
```

| 依赖 | 说明 |
|---|---|
| JDK | 17+（编译参数为 `-source/-target 8`） |
| Android build-tools | **35**（34 的 d8 处理匿名类会 NPE） |
| `android.jar` | API 30 |
| Xposed API | `api-82.jar`（legacy） |

脚本内部六步：`javac → d8 → aapt2 link → 打包 dex+assets → zipalign → apksigner`。三个已固化的坑：

1. **路径不能有中文**：javac/aapt2/d8 对非 ASCII 路径不稳，统一在 `C:\csim\build` 下暂存；
2. **类一多 d8 命令行会爆**：先 `jar cf` 打包再喂给 d8（`--file-list` 是 R8 的参数，d8 不认）；
3. **PowerShell 5.1 按 ANSI 读脚本**：`build.ps1` 必须保持 ASCII-only，否则中文注释会吞掉下一行。

---

## 安装：先选一条路

| 你的情况 | 走哪条 |
|---|---|
| 手机已解锁 Bootloader，能刷 `boot.img` | **教程 A：Magisk**（最常见、最稳） |
| 手机没有可刷的 boot，但内核是 GKI（5.10+） | **教程 B：KernelSU** |
| 想用内核补丁方式拿 root | **教程 C：APatch** |
| 只有模拟器 | **教程 D：模拟器** |
| 只是想画路线、不用注入 | 装 APK 即可，App 本身不需要 root |

**共同前提**：LSPosed 需要 **Zygisk**。Magisk 自带（设置里打开即可）；KernelSU / APatch 要额外装 **ZygiskNext**；模拟器往往连 Magisk 都装不上。

所需文件：

- LSPosed（Zygisk 版）：<https://github.com/LSPosed/LSPosed/releases>
- ZygiskNext：<https://github.com/Dr-TSNG/ZygiskNext/releases>
- Magisk：<https://github.com/topjohnwu/Magisk/releases>
- CampusSim APK：本仓库 Releases

---

## 教程 A：Magisk + LSPosed

### A1. 检查设备

```bash
adb shell getprop ro.product.cpu.abi          # arm64-v8a
adb shell getprop ro.build.version.release    # 10 / 11 / 12 ...
adb shell getprop ro.boot.verifiedbootstate   # green = 还没解锁
```

`green` 说明未解锁：开发者选项开「OEM 解锁」→ `fastboot flashing unlock`（**会清空数据**）。OPPO/realme 新机型拿不到深度测试就别折腾，改走 KernelSU。

### A2. 装 Magisk

1. 安装 Magisk APK；
2. 从固件包取 `boot.img`（Android 13 部分机型是 `init_boot.img`）拷进手机；
3. Magisk →「安装」→「选择并修补一个文件」→ 选 `boot.img` → 得到 `magisk_patched-xxxx.img`；
4. 电脑上刷入：

```bash
adb pull /sdcard/Download/magisk_patched-xxxx.img
adb reboot bootloader
fastboot flash boot magisk_patched-xxxx.img     # 部分机型：fastboot flash init_boot ...
fastboot reboot
```

5. 打开 Magisk App 看到版本号即成功。

### A3. 打开 Zygisk（关键）

Magisk → 设置 → **Zygisk** 打开 → **重启手机**。

```bash
adb shell su -c "ls /data/adb/modules"        # 有目录即可
```

> 不开 Zygisk，LSPosed 一个模块都不会生效。

### A4. 刷 LSPosed

1. 下载 **LSPosed Zygisk 版 zip**（`LSPosed-vX.X.X-xxxx-zygisk-release.zip`）；
2. Magisk → 底部「模块」→「从本地安装」→ 选 zip → **重启**；
3. 重启后出现 **LSPosed** 管理器图标。没有图标但模块存在时：

```bash
adb shell am start -n org.lsposed.manager/.ui.activity.MainActivity
```

### A5. 装 CampusSim 并导入

```bash
adb install -r CampusSim-1.3.3.apk
```

然后按 [把模块导入 LSPosed 并验证](#把模块导入-lsposed-并验证) 操作。

---

## 教程 B：KernelSU + ZygiskNext + LSPosed

KernelSU 通过内核层给 root，**本身不带 Zygisk**，必须补 ZygiskNext。

### B1. 判断能不能用

```bash
adb shell uname -r                           # 例：5.10.101-android12-9-xxxx
adb shell getprop ro.boot.verifiedbootstate
```

官方 KernelSU 主要支持 **GKI 内核**（5.10+）。老内核要么用 KernelSU-Next / 自编译内核，要么退回 Magisk。

### B2. 装 KernelSU

1. 安装 [KernelSU Manager](https://github.com/tiann/KernelSU/releases)；
2. App 里「未安装」时，按引导修补 `boot.img`（或刷 AnyKernel3 zip）；
3. 刷入并重启：

```bash
fastboot flash boot kernelsu_patched.img
fastboot reboot
```

4. 验证提权：

```bash
adb shell su -c id      # uid=0（首次会弹授权，点允许）
```

### B3. 装 ZygiskNext

1. 下载 ZygiskNext zip；
2. KernelSU → 底部「模块」→「从本地安装」→ 选 zip → **重启**；
3. 验证：`adb shell su -c "ls /data/adb/modules"` 里出现 `zygisknext`。

（`ReZygisk` / `Zygisk-Assistant` 可等效替代。）

### B4. 装 LSPosed

KernelSU →「模块」→ 从本地安装 LSPosed Zygisk 版 zip → **重启** → 出现 LSPosed 管理器。

### B5. 装 CampusSim 并导入

```bash
adb install -r CampusSim-1.3.3.apk
```

> KernelSU 的「模块」页只管系统级模块（ZygiskNext / LSPosed 本体）；CampusSim 是普通 APK 模块，**只出现在 LSPosed 管理器里**，看不到是正常的。

---

## 教程 C：APatch + ZygiskNext + LSPosed

APatch = KernelPatch + 轻量模块系统，同样不带 Zygisk。

### C1. 前置

```bash
adb shell uname -r
adb shell getprop ro.boot.verifiedbootstate
```

兼容面比 Magisk 窄，先查 [APatch 兼容说明](https://github.com/bmax121/APatch)。

### C2. 装 APatch

1. 安装 [APatch APK](https://github.com/bmax121/APatch/releases)；
2. APatch →「安装」→ 选 `boot.img` → **设置 SuperKey（务必记住）** → 修补；
3. 刷入：

```bash
fastboot flash boot apatch_patched.img
fastboot reboot
```

4. 提权验证：`adb shell su -c id`（首次输入 SuperKey）。

### C3. 装 ZygiskNext + LSPosed

APatch →「模块」→ 从本地安装 ZygiskNext zip → 重启 → 再装 LSPosed Zygisk 版 zip → 重启。

### C4. 装 CampusSim 并导入

```bash
adb install -r CampusSim-1.3.3.apk
```

---

## 教程 D：模拟器（MuMu / 雷电 / 夜神 / BlueStacks / AVD）

**先认清一点**：模拟器自带的那点 root 只够 `su`，**不足以加载 LSPosed**，还得能装 Magisk（再开 Zygisk）。装不上就换真机 —— 但 App 的路线编辑、校园地图、导入导出**不需要 root**，模拟器里照样能用。

### D0. 端口与连接

| 模拟器 | adb 端口 | 多开实例 |
|---|---|---|
| MuMu 12（Android 12） | `16384` | 16385, 16386 … |
| 雷电 LDPlayer 9 | `5555` | 5555 + 实例号 |
| 夜神 Nox | `62001` | 62025, 62026 … |
| BlueStacks 5 | `5555` | 5555 + 实例号 |
| Android Studio AVD | `5554` | 5556, 5558 … |

```bash
adb connect 127.0.0.1:16384      # 以 MuMu 为例
adb devices                      # 出现 127.0.0.1:16384  device
adb shell su -c id               # 有输出 = 自带 root 可用
adb shell uname -m               # x86_64 / aarch64
```

本仓库自带脚本会自动探测端口并完成安装与校验：

```powershell
powershell -File CampusSim\deploy-mumu.ps1
```

### D1. MuMu 12

1. MuMu 设置中心 → 其他 → **Root 权限** → 开启 → 重启模拟器；
2. `adb connect 127.0.0.1:16384`；
3. 装 Magisk：

```bash
adb install -r Magisk-v27.apk
adb shell am start -n com.topjohnwu.magisk/.ui.MainActivity
```

Magisk 里选「安装 → 直接安装（推荐）」。若提示需要 `boot.img`，说明该镜像不支持直装：
- 换社区提供的 **MuMu 12 Magisk 镜像/一键脚本**；
- 或直接改用真机。

4. 装好 Magisk 后：**设置 → 打开 Zygisk → 重启**；
5. 刷 LSPosed Zygisk 版 zip → 重启；
6. `adb install -r CampusSim-1.3.3.apk` → 按下一节导入模块。

> MuMu 12 是 x86_64 Android 12。目标 App 若含 arm64 原生库，需要模拟器的 **ARM 转译**；装不上或一开就闪退属于模拟器侧限制。

### D2. 雷电 LDPlayer 9

LDPlayer 设置 → 其他设置 → **ROOT 权限** 开启 → 保存并重启 → `adb connect 127.0.0.1:5555` → 后续同 D1（Magisk → Zygisk → LSPosed → CampusSim）。

### D3. 夜神 Nox

夜神多开器 → 设置 → **ROOT 权限** 开启 → 重启 → `adb connect 127.0.0.1:62001` → 后续同 D1。

### D4. BlueStacks 5

设置 → 高级 → **Android 调试（ADB）** 开启 → `adb connect 127.0.0.1:5555`。
BlueStacks 的 root 常是"伪 root"（只有 su 二进制），**装不了 Magisk → 也就跑不了 LSPosed**。建议改用 MuMu / 雷电 / 真机，只把 BS5 用来做界面演示。

### D5. Android Studio AVD

1. 建 AVD 时选 **Google APIs** 镜像（**不要** Play Store 版），Android 11 / 12；
2. `adb root` 可用，但这只是 root shell，**没有 Zygisk**；
3. 要在 AVD 里跑 LSPosed，需要自己用 `magiskboot` 修补 `ramdisk.img`（进阶操作）；
4. 推荐：AVD 只做界面与路线调试，注入用真机。

### D6. 模拟器常见问题

| 现象 | 处理 |
|---|---|
| `adb connect` 被拒 | 模拟器没开 adb 调试，或端口不对（多开实例端口不同） |
| `adb devices` 显示 `unauthorized` | 模拟器里点"允许 USB 调试" |
| `su` 无输出 | root 没开或需要重启实例 |
| 装不上 Magisk | 镜像不支持（BlueStacks 常见），换 MuMu/雷电或真机 |
| LSPosed 模块列表空 | Zygisk 没开 / 没重启 |
| 目标 App 闪退 | 缺 ARM 转译，属模拟器限制 |

---

## 把模块导入 LSPosed 并验证

不管上面走哪条路，这一步都一样：

1. 确认 `CampusSim-1.3.3.apk` 已安装到**主用户**（不要装进分身/工作资料，否则模块不生效）；
2. 打开 **LSPosed 管理器**；
3. 底部导航选 **「模块」**；
4. 列表里会出现 **CampusSim**（安装后自动出现，**不需要手动导入文件**）；
   - 没出现：下拉刷新；确认没装进分身；重启一次管理器；
5. 点进 **CampusSim** → 打开右上角**启用开关**；
6. 点 **「作用域」** → 勾选 **步道乐跑**（`com.lptiyu.tanke`）→ 返回；
   - **只勾这一个**，勾多了会拖慢其它应用启动；
   - 搜不到目标应用：它没装在当前用户，或被隐私空间隔离了；
7. **强制停止步道乐跑**（设置 → 应用 → 强行停止），或重启手机；
8. 打开一次步道乐跑，然后验证：

```bash
adb shell su -c "grep -a 'inject ->' /data/adb/lspd/log/modules_*.log | tail -1"
# CampusSim inject -> com.lptiyu.tanke (com.lptiyu.tanke) enabled=true

adb shell su -c "grep -a 'hb ' /data/adb/lspd/log/modules_*.log | tail -3"
# CampusSim hb fix=30.462837,114.435386 v=3.03 spm=172 ... dist=0 paused=0
```

看到 `inject ->` 与 `hb ... dist=...` 就成功了。（`v` 速度、`spm` 步频、`dist` 已跑米数。）

### 重装 APK 后必做：同步 apk_path

LSPosed 记录的是 APK 的**绝对路径**，重装后路径变化会导致模块不加载（日志里没有 `inject ->`）。两种处理：

**手动**：LSPosed 管理器 → 模块 → CampusSim → 关掉开关再打开 → 重启。

**脚本**：

```bash
adb shell su -c "cp /data/adb/lspd/config/modules_config.db /data/local/tmp/m.db && chmod 666 /data/local/tmp/m.db"
adb pull /data/local/tmp/m.db m.db
python CampusSim/patch-lsposed.py m.db "$(adb shell pm path com.sim.campusrun | sed 's/package://')" m2.db
adb push m2.db /data/local/tmp/m2.db
adb shell su -c "rm -f /data/adb/lspd/config/modules_config*.db*; \
  cp /data/local/tmp/m2.db /data/adb/lspd/config/modules_config.db; \
  chown root:root /data/adb/lspd/config/modules_config.db; \
  chmod 600 /data/adb/lspd/config/modules_config.db; \
  restorecon /data/adb/lspd/config/modules_config.db"
adb reboot
```

---

## 使用教程

### 1. 界面

```
┌──────────────────────────────┐
│ HUD（左上角，实时数据）         │
│                              │
│        地图（自绘高德瓦片）      │
│                              │
├──────────────────────────────┤
│ 画线 打卡点 途经点 道路 跑区 撤销 清空 ⋯ │
└──────────────────────────────┘
```

底部默认只有一行，点 `⋯` 展开全部（再点 `×` 收起）：

| 行 | 内容 |
|---|---|
| 节点 | 起点 / 终点 / 删除 / ← → ↑ ↓（每次 3 m） |
| 视图 | 适配 / ＋ / － / 坐标:GCJ / 保存 / 源:校园 |
| 生成 | 目标里程 / 生成 / 应用 / 重生成 |
| 运行 | 开始 / 暂停 / 继续 / 结束 |
| 参数 | 配速 s/km、步频 spm、休息 次/km、打卡半径 m、速度上限、卫星数、WiFi 数、基站数 |
| 文件 | 新道路 / 导出 / 导入 / 下载 |

手势：单指拖动平移 · 双指 / 鼠标滚轮 / 双击缩放 · 点节点选中 · **长按节点 350 ms 后拖动** · PageUp/PageDown 缩放。

### 2. 最快跑一次

1. 点 **画线** → 地图上点两个点（起点、终点）；
2. `⋯` → 填**目标里程**（例如 800）；
3. 点 **生成** → 绿色虚线预览 + HUD 显示实际里程与偏差；
4. 点 **应用** → 虚线转成可编辑路径点；
5. 点 **开始** → HUD 状态变「运行中」；
6. 打开步道乐跑开始跑步。

> 改完路线/参数后 HUD 会出现 **⚠ 改动未下发**，点「开始」或「保存」才真正下发。

### 3. 编辑路线

| 操作 | 做法 |
|---|---|
| 加点 | **画线** 模式点地图 |
| 调整 | 点节点选中（黄圈）→ `←` `→` `↑` `↓` 微调 3 m，或**长按拖动** |
| 设起终点 | 选中节点 → **起点** / **终点**（S/E 标记） |
| 删除 | 选中 → **删除** |
| 撤销 | 按当前模式撤销最后一个点（画线/打卡/途经点/道路/跑区各管各的） |
| 清空 | 清掉路径点、打卡点、途经点和校园图 |
| 适配 | 自动缩放居中 |
| 环绕 / 单程 | 起点 == 终点 → 环绕；不同 → 单程（到终点停） |

**打卡点**：切到 **打卡点** 模式点地图，生成 A/B/C；HUD 会提示打卡点是否在路线上。

### 4. 画校园跑道（推荐）

1. **道路** 模式沿跑道点几下，拐弯处多点点；
2. 画下一条点 **新道路**（端点 4 m 内自动连通）；
3. 需要限制范围就 **跑区** 模式沿校园边界点一圈；
4. `⋯` → **导出** 得到 `school_<id>.json`，可提交到仓库 `schools/` 分享；
5. 换设备后 **导入** 或 **下载** 恢复。

有校园图后，`源:校园` 生成的路线**只沿你画的跑道走**，毫秒级完成。

### 5. 三种生成来源

点 `源:…` 循环切换，前一级失败自动降级：

| 来源 | 说明 | 耗时 |
|---|---|---|
| **校园** | 本地图搜索（A*），只走跑道；里程按「随机绕行 → 整圈 → 折返」补足 | 10–50 ms |
| **路网** | OSRM 公开步行路网（需联网），沿真实街道 | 5–20 s |
| **几何** | 离线兜底，插绕行环 | < 10 ms（可能穿建筑） |

**里程偏差**：路网可达长度是离散的，HUD 会直接告诉你实际值：

```
自动: 校园路网 1680 m / 目标 2000 m，偏差 16.0%
```

把目标改成 1680 再生成 → 0 偏差。

### 6. 运行控制

| 按钮 | 行为 | 模块侧 |
|---|---|---|
| 开始 | 下发 `enabled=true` + 新 `runToken` | 重建路线、里程归零 |
| 暂停 | `paused=true` | 速度归零、进度保留 |
| 继续 | `paused=false` | 接着跑 |
| 结束 | `enabled=false` | 停止注入定位与传感器 |

### 7. 参数建议

| 参数 | 建议 |
|---|---|
| 配速 s/km | 慢跑 330–400，快跑 270–320（1000/pace = 平均速度 m/s） |
| 步频 spm | 160–180 最自然；步幅自动反算，不用手调 |
| 休息 次/km | 0.5–1.5，单次 15–60 s |
| 打卡半径 | 默认 25 m |
| 速度上限 | 默认 6.5 m/s，配速特别快时注意别被夹住 |
| 卫星 / WiFi / 基站数 | 12 / 4 / 3 已足够自然 |

### 8. 坐标系

内部与配置都是 **WGS-84**，下发时按「坐标」按钮转换：

- **GCJ**（默认）：国内 App 通常把框架坐标当 GCJ 渲染，不转换会整体平移。武汉一带实测 **-258 m 北 / +528 m 东（合 588 m）**：

```
coord=gcj wgs=30.463067,114.435633 -> emit=30.460724,114.441125 (Δ587m)
```

- **WGS**：目标 App 自己转换时用。

**轨迹整体平移** → 切一下「坐标」再跑。

### 9. HUD 字段

```
状态 运行中 · 环绕 · 坐标 GCJ
总长 1255 m · 预计 6:27 · 步数≈1108
配速 308 s/km（3.25 m/s，11.7 km/h）· 步频 172
起点#1 → 终点#15 · 途经 1 · 跑完继续绕圈
点位 15 · 打卡 0 · 选中 无 · z17.0
自动: 校园路网 1255 m / 目标 1255 m，偏差 0.0%（1 段路 · 24 ms）
校园图 武汉工程大学 · 路 4 段 3200 m · 跑区 6 点
⚠ 改动未下发（点「开始」或「保存」生效）
```

### 10. 电脑端辅助（可选）

```powershell
python tools\mkserver.py      # 打开 http://127.0.0.1:8765/mapdraw.html
powershell -File CampusSim\simctl.ps1 status | start | log
```

---

## 配置项

配置是 JSON：App 通过 `127.0.0.1:47561` 下发（0.5 s 生效），也可手写 `/data/local/tmp/campusim.json`。

| 字段 | 默认 | 说明 |
|---|---|---|
| `enabled` | `false` | 总开关 |
| `paused` | `false` | 暂停（速度 0，进度保留） |
| `mode` | `"loop"` | `loop` 环绕 / `once` 单程 |
| `runToken` | `0` | 变化即重建路线（重跑归零） |
| `route` | `[]` | 路径点 `[[lat,lng],…]`，WGS-84 |
| `checkpoints` | `[]` | 打卡点 |
| `checkpointRadius` | `25` | 打卡半径（米） |
| `coord` | `"gcj"` | 下发坐标系 `gcj` / `wgs` |
| `startIndex` / `endIndex` | `0` / `-1` | 起终点下标（相同 = 环绕） |
| `closed` | `false` | 画线是否首尾相连（仅显示） |
| `paceSecPerKm` | `330` | 配速（秒/公里） |
| `speedJitter` | `0.06` | 速度 OU 噪声 |
| `minSpeed` / `maxSpeed` | `0.6` / `6.5` | 速度硬上下限（m/s） |
| `spmBase` / `spmJitter` | `172` / `4` | 步频基线（实际由速度反算） |
| `segJitter` | `0.12` | 每 100 m 分段配速抖动 |
| `restsPerKm` | `0.8` | 每公里休息次数 |
| `restMinSec` / `restMaxSec` | `15` / `60` | 休息时长范围 |
| `randomStart` / `randomDir` | 环绕时 true / false | 随机起点 / 方向 |
| `wifi` / `cell` / `ble` | `true` | 各模块开关 |
| `accel` / `mag` / `gyro` | `true` | 传感器开关 |
| `wifiCount` / `cellCount` | `4` / `3` | AP / 基站条数 |
| `satCount` / `cn0Min` / `cn0Max` | `12` / `22` / `40` | 卫星数与载噪比区间 |
| `heartbeat` | `10` | 心跳日志间隔（秒） |

---

## 校园地图格式

一校一个 JSON，坐标统一 **WGS-84**：

```json
{
  "school": "武汉工程大学（流芳校区）",
  "id": "whgcdx-lf",
  "version": 3,
  "coord": "wgs84",
  "zone":  [[30.4701, 114.4291], [30.4701, 114.4310], [30.4680, 114.4310]],
  "roads": [
    {"name": "环湖跑道", "points": [[30.4701, 114.4291], [30.4703, 114.4295]]},
    {"name": "主干道",   "points": [[30.4703, 114.4295], [30.4680, 114.4298]]}
  ],
  "checkpoints": [[30.4712, 114.4301]]
}
```

- `roads`：多段折线，端点 <4 m 自动连通；
- `zone`：跑区多边形（空数组 = 不限制）；
- `checkpoints`：打卡点（预留）。

App 内「导出」写到 `Android/data/com.sim.campusrun/files/Download/school_<id>.json`，可直接 PR 到仓库 `schools/` 目录分享。

---

## 常见问题

**Q1 没有 `inject ->` 日志？**
① Zygisk（或 ZygiskNext）没开；② LSPosed 里模块未启用 / 作用域没勾 `com.lptiyu.tanke`；③ 重装过 APK → `apk_path` 失效（见上文）；④ 目标 App 装在分身里。

**Q2 `logcat -s CampusSim` 没输出？**
LSPosed 日志不走 logcat：`adb shell su -c "grep -a CampusSim /data/adb/lspd/log/modules_*.log | tail"`。

**Q3 轨迹整体平移几百米？**
坐标系问题，点「坐标」切换。日志可确认 `coord=gcj ... (Δ587m)`。

**Q4 打卡点打不上？**
点要落在路线上（HUD 有"⚠ 不在路线上"提示）；半径默认 25 m，可调大。

**Q5 生成了但里程对不上？**
看 HUD 里的实际值，把目标改成那个数就是 0 偏差。

**Q6 `源:校园` 提示"路网不连通/锚点太远"？**
锚点离道路 >120 m 先拖到路上；两条道路端点没接上就把端点画近点（4 m 内自动连通）。

**Q7 `源:路网` 很慢或失败？**
公开 OSRM 有速率限制、国内访问不稳；有校园图时别用它，失败会自动降级。

**Q8 磁力计/旋转矢量像没生效？**
目标 App 只注册了加速度计与陀螺仪（日志 `sensor hooked type=1/4`），这两路是预置。

**Q9 地图一片白？**
部分区域 z18 瓦片本身空白（荒地/水面）；放大到 z19+ 会有 10 m 网格辅助定位。

**Q10 能不 root 用吗？**
注入必须 root；但路线编辑、校园地图、导入导出不需要 root。

---

## 已知限制

1. **里程离散**：路网可达长度受街道/跑道间距限制，HUD 会给出实际值。
2. **OSRM 依赖外网**：公开实例有速率限制，生成 5–20 s。
3. **磁力计 / 旋转矢量空转**：目标 App 未注册这两路。
4. **陀螺仪波形偏干净**：转向 + 单频摆动 + 均匀噪声，若有频域校验仍可能露。
5. **Root 检测**：仅处理 `su` 路径与 `exec("su")`，RootBeer 深检未覆盖。
6. **人脸打卡**：无法软件模拟。
7. **APK 用 debug 证书签名**：正式分发请换自己的 keystore。

---

## 更新日志

**1.0.0**
- 校园地图：跑道多折线 + 跑区多边形，本地图搜索（A*），生成毫秒级
- 里程补足三策略：随机绕行点 → 整圈重复 → 折返补齐
- 朝向模型：`bearing` 平滑、设备偏角慢漂、陀螺跟随转向
- 传感器参数化：磁力/陀螺开关、WiFi/基站条数、卫星数与载噪比
- UI 重构：默认单行工具栏，`⋯` 展开高级面板
- 坐标系开关（GCJ/WGS），修正 588 m 整体平移
- 构建：d8 改走 jar 喂入、ASCII 暂存目录

---

## 免责声明

1. **用途限定**：本项目仅供**计算机技术学习**、Android Hook 机制研究，以及在**自有设备 / 自建测试环境**中的功能验证。
2. **禁止用途**：不得用于冒用他人身份、伪造体育成绩或考勤以获取学分/奖励、规避任何学校或平台的考核与管理，或任何违法行为。
3. **合规责任**：体育成绩认定规则由各学校/平台自行制定。使用前请自行确认所在学校学生守则与当地法律法规。**是否使用、如何使用及由此产生的全部后果（成绩作废、纪律处分、账号封禁、法律责任等），由使用者自行承担。**
4. **无担保**：软件按"现状"提供，不附带任何明示或默示担保；作者不对使用造成的直接或间接损失（数据丢失、设备损坏、账号封禁等）负责。
5. **第三方服务**：运行时可能访问地图瓦片、OSRM 公共路由实例等第三方服务，其可用性与条款由各提供方决定；请勿对公共实例发起高频请求。
6. **Root 风险**：解锁 Bootloader、刷入 Magisk/KernelSU/APatch、修改系统分区可能导致变砖、丢保修、清空数据，或触发银行/支付类应用风控，请自行评估并备份。
7. **无隶属关系**：本项目与任何学校、教育平台、地图服务商、模块框架均无隶属或合作关系，相关名称与商标归各自所有者。

**继续使用即表示你已知悉并同意以上全部条款。** 不同意请立即停止使用并删除本软件。

---

## License

[MIT](LICENSE)
