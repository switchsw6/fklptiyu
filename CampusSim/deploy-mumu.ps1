param(
  [string]$Apk = "",
  [string]$Package = "com.sim.campusrun",
  [string]$TestPkg = "com.cnspeedtest.globalspeed",
  [int[]]$Ports = @(16384, 16416, 7555, 21503),
  [switch]$SkipInstall
)
# MuMu / 任意 x86_64 模拟器部署脚本（ASCII-only）
$ErrorActionPreference = "Continue"
$here = $PSScriptRoot
if (-not $Apk) { $Apk = Join-Path $here "build\CampusSim.apk" }

function Find-Device {
    foreach ($p in $Ports) {
        adb connect ("127.0.0.1:" + $p) | Out-Null
    }
    Start-Sleep -Seconds 2
    $lines = adb devices | Select-Object -Skip 1 | Where-Object { $_ -match "\sdevice$" }
    if (-not $lines) { return $null }
    return (($lines[0] -split "\s+")[0])
}

$serial = Find-Device
if (-not $serial) {
    Write-Host "no device. start MuMu first and enable adb, then rerun."
    adb devices
    exit 1
}
Write-Host "device: $serial"
adb -s $serial shell "getprop ro.product.cpu.abi; getprop ro.build.version.release; getprop ro.product.model"

Write-Host "--- root check ---"
adb -s $serial shell "su -c id" 2>&1 | Select-Object -First 1

Write-Host "--- lsposed check ---"
$lsp = adb -s $serial shell "ls /data/adb/modules 2>/dev/null" 2>&1
$lsp | Select-String -Pattern "lsposed|zygisk" | ForEach-Object { "  " + $_ }
if (-not ($lsp -match "lsposed")) {
    Write-Host "WARN: LSPosed not found in /data/adb/modules."
    Write-Host "      MuMu: enable root -> install Magisk (or use built-in root) -> flash LSPosed Zygisk zip -> reboot."
}

if (-not $SkipInstall) {
    Write-Host "--- install module ---"
    adb -s $serial push $Apk /data/local/tmp/CampusSim.apk
    adb -s $serial shell "su -c 'pm install -r -g /data/local/tmp/CampusSim.apk'"
}

Write-Host "--- module path / lsposed db ---"
$sim = (adb -s $serial shell "pm path $Package").Trim().Replace("package:", "")
Write-Host "  $sim"
$dbTmp = Join-Path $env:TEMP "lspd_mumu.db"
adb -s $serial shell "su -c 'cp /data/adb/lspd/config/modules_config.db /data/local/tmp/m.db; chmod 666 /data/local/tmp/m.db'" 2>$null
adb -s $serial pull /data/local/tmp/m.db $dbTmp 2>$null
if (Test-Path $dbTmp) {
    python (Join-Path $here "_deploy.py") $dbTmp $sim (Join-Path $env:TEMP "lspd_mumu_out.db") | Select-Object -First 3
    adb -s $serial push (Join-Path $env:TEMP "lspd_mumu_out.db") /data/local/tmp/m2.db
    adb -s $serial shell "su -c 'rm -f /data/adb/lspd/config/modules_config*.db*; cp /data/local/tmp/m2.db /data/adb/lspd/config/modules_config.db; chown root:root /data/adb/lspd/config/modules_config.db; chmod 600 /data/adb/lspd/config/modules_config.db; restorecon /data/adb/lspd/config/modules_config.db'"
    Write-Host "  lsposed db patched"
} else {
    Write-Host "  WARN: cannot pull lsposed db; enable scope manually in LSPosed manager."
}

Write-Host "--- reboot ---"
adb -s $serial reboot
Start-Sleep -Seconds 40
adb -s $serial wait-for-device
adb -s $serial shell "getprop sys.boot_completed"

Write-Host "--- verify injection (test app) ---"
adb -s $serial shell "monkey -p $TestPkg -c android.intent.category.LAUNCHER 1" | Out-Null
Start-Sleep -Seconds 8
$log = (adb -s $serial shell "su -c 'ls -t /data/adb/lspd/log/modules_*.log | head -1'").Trim()
adb -s $serial shell "su -c 'grep -a \"inject ->\" $log | tail -3'"
adb -s $serial shell "su -c 'grep -a \"hb \" $log | tail -3'"

Write-Host "done. If inject lines appear, module is alive on this emulator."
