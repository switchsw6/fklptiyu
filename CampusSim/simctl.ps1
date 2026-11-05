param(
  [ValidateSet("status","apply","start","stop","push","log","route","server")] [string]$Action = "status",
  [string]$File = "",
  [string]$Serial = "QO6LWWJ7S88D6THM"
)
$ErrorActionPreference = "Continue"
$devJson = "/data/local/tmp/campusim.json"
$appJson = "/sdcard/Android/data/com.sim.campusrun/files/campusim.json"

switch ($Action) {
  "status" {
    Write-Host "--- module ---"
    adb -s $Serial shell "pm path com.sim.campusrun"
    Write-Host "--- target app ---"
    adb -s $Serial shell "pm path com.lptiyu.tanke"
    Write-Host "--- active config ---"
    adb -s $Serial shell "su -c 'head -c 400 $devJson'"
    Write-Host "`n--- saved from map UI ---"
    adb -s $Serial shell "su -c 'ls -l $appJson'"
  }
  "apply" {
    # 把 App 内地图页保存的 JSON 落到 /data/local/tmp，模块 0.5s 内热加载
    adb -s $Serial shell "su -c 'cp $appJson $devJson && chmod 644 $devJson && echo applied && head -c 300 $devJson'"
  }
  "start" {
    adb -s $Serial shell "su -c ""sed -i 's/`"enabled`": *false/`"enabled`": true/' $devJson; grep -o '\`"enabled\`": *[a-z]*' $devJson"""
  }
  "stop" {
    adb -s $Serial shell "su -c ""sed -i 's/`"enabled`": *true/`"enabled`": false/' $devJson; grep -o '\`"enabled\`": *[a-z]*' $devJson"""
  }
  "push" {
    if (-not $File) { $File = Join-Path (Split-Path $PSScriptRoot -Parent) "tools\campusim.sample.json" }
    adb -s $Serial push $File $devJson
    adb -s $Serial shell "chmod 644 $devJson"
    Write-Host "pushed $File"
  }
  "log" {
    adb -s $Serial logcat -c
    adb -s $Serial logcat -s CampusSim
  }
  "route" {
    adb -s $Serial shell "su -c ""grep -o '\`"route\`": \[[^]]*\]' $devJson | head -c 600"""
  }
  "server" {
    python (Join-Path (Split-Path $PSScriptRoot -Parent) "tools\mkserver.py") --serial $Serial
  }
}
