param([switch]$Install, [string]$Serial = "QO6LWWJ7S88D6THM")
$ErrorActionPreference = "Stop"
# NOTE: ASCII-only script. Workspace path may contain non-ASCII characters,
# so all paths derive from $PSScriptRoot (filesystem) instead of literals.
$here = $PSScriptRoot
# Toolchain discovery: prefer a local "toolchain" folder (dev layout), otherwise
# fall back to the Android SDK from ANDROID_HOME / ANDROID_SDK_ROOT.
$tc   = Join-Path (Split-Path $here -Parent) "toolchain"
$sdk  = $env:ANDROID_HOME
if (-not $sdk) { $sdk = $env:ANDROID_SDK_ROOT }

function First-Existing([string[]]$candidates) {
    foreach ($p in $candidates) { if ($p -and (Test-Path $p)) { return $p } }
    return $null
}

$btC = @( (Join-Path $tc "bt35\android-15") )
$ajC = @( (Join-Path $tc "pf\android-11\android.jar") )
$apiC = @( (Join-Path $tc "api-82.jar"), (Join-Path $here "libs\api-82.jar") )
if ($sdk) {
    $btC += (Join-Path $sdk "build-tools\35.0.0")
    $btC += (Join-Path $sdk "build-tools\35.0.1")
    $btC += (Join-Path $sdk "build-tools\36.0.0")
    $ajC += (Join-Path $sdk "platforms\android-30\android.jar")
    $ajC += (Join-Path $sdk "platforms\android-31\android.jar")
    $ajC += (Join-Path $sdk "platforms\android-33\android.jar")
}
$bt = First-Existing $btC
$aj = First-Existing $ajC
$api = First-Existing $apiC
if (-not $bt)  { throw "build-tools 35 not found. Set ANDROID_HOME or put it under ..\toolchain\bt35\android-15" }
if (-not $aj)  { throw "android.jar not found. Set ANDROID_HOME or put it under ..\toolchain\pf\android-11\android.jar" }
if (-not $api) { throw "Xposed api-82.jar not found. Put it under ..\toolchain\api-82.jar or CampusSim\libs\api-82.jar" }
$javac   = Join-Path $env:JAVA_HOME "bin\javac.exe"
$keytool = Join-Path $env:JAVA_HOME "bin\keytool.exe"
$b = Join-Path $here "build"
# ASCII staging dir: d8/aapt2 are unreliable with non-ASCII paths
$stage = "C:\csim\build"
$ajStage = "C:\csim\android.jar"
Remove-Item $b -Recurse -Force -ErrorAction SilentlyContinue
Remove-Item $stage -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path (Join-Path $b "classes"), (Join-Path $b "dex"), $stage | Out-Null
if (-not (Test-Path $ajStage)) { Copy-Item $aj $ajStage -Force }

Write-Host "[1/6] javac"
$srcs = @(Get-ChildItem -Path (Join-Path $here "src") -Recurse -Filter *.java | ForEach-Object { $_.FullName })
Write-Host ("      sources = " + $srcs.Count)
if ($srcs.Count -eq 0) { throw "no java sources found under $here\src" }
$jc = @("-nowarn","-source","8","-target","8","-bootclasspath",$aj,"-cp",$api,
        "-encoding","UTF-8","-d",$stage) + $srcs
& $javac $jc
if ($LASTEXITCODE -ne 0) { throw "javac failed ($LASTEXITCODE)" }

Write-Host "[2/6] d8"
# 打成 jar 再喂给 d8：单参数，既避免命令行过长，也避免 d8 处理目录
$jar = Join-Path $env:JAVA_HOME "bin\jar.exe"
$stageJar = "C:\csim\classes.jar"
if (Test-Path $stageJar) { Remove-Item $stageJar -Force }
& $jar cf $stageJar -C $stage .
if ($LASTEXITCODE -ne 0) { throw "jar failed ($LASTEXITCODE)" }
& (Join-Path $bt "d8.bat") --lib $ajStage --min-api 29 --output (Join-Path $b "dex") $stageJar
if (-not (Test-Path (Join-Path $b "dex\classes.dex"))) { throw "d8 produced no dex" }

Write-Host "[3/6] aapt2 link"
& (Join-Path $bt "aapt2.exe") link -o (Join-Path $b "base.apk") -I $ajStage `
    --manifest (Join-Path $here "AndroidManifest.xml") --min-sdk-version 21 --target-sdk-version 30
if ($LASTEXITCODE -ne 0) { throw "aapt2 failed" }

Write-Host "[4/6] pack classes.dex + assets"
New-Item -ItemType Directory -Force -Path (Join-Path $here "assets") | Out-Null
$draw = Join-Path (Split-Path $here -Parent) "tools\mapdraw.html"
if (Test-Path $draw) { Copy-Item $draw (Join-Path $here "assets\mapdraw.html") -Force }
$lite = Join-Path (Split-Path $here -Parent) "tools\draw-lite.html"
if (Test-Path $lite) { Copy-Item $lite (Join-Path $here "assets\draw-lite.html") -Force }
$vendor = Join-Path (Split-Path $here -Parent) "tools\vendor"
if (Test-Path $vendor) {
  New-Item -ItemType Directory -Force -Path (Join-Path $here "assets\vendor") | Out-Null
  Copy-Item (Join-Path $vendor "*") (Join-Path $here "assets\vendor") -Force -Recurse
}
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zipPath = Join-Path $b "base.apk"
$zip = [System.IO.Compression.ZipFile]::Open($zipPath, 'Update')
$items = @(@{ p = (Join-Path $b "dex\classes.dex"); n = "classes.dex" })
$assetDir = Join-Path $here "assets"
foreach ($f in Get-ChildItem $assetDir -Recurse -File) {
  $rel = $f.FullName.Substring($assetDir.Length + 1).Replace('\', '/')
  $items += @{ p = $f.FullName; n = "assets/" + $rel }
}
foreach ($it in $items) {
  $old = $zip.GetEntry($it.n); if ($old) { $old.Delete() }
  $e = $zip.CreateEntry($it.n, [System.IO.Compression.CompressionLevel]::Optimal)
  $s = $e.Open(); $bytes = [System.IO.File]::ReadAllBytes($it.p); $s.Write($bytes, 0, $bytes.Length); $s.Close()
  Write-Host ("      + " + $it.n + " (" + $bytes.Length + "B)")
}
$zip.Dispose()

Write-Host "[5/6] zipalign"
& (Join-Path $bt "zipalign.exe") -f -p 4 $zipPath (Join-Path $b "aligned.apk")
if ($LASTEXITCODE -ne 0) { throw "zipalign failed" }

Write-Host "[6/6] sign"
$ks = Join-Path $here "debug.keystore"
if (-not (Test-Path $ks)) {
  & $keytool -genkeypair -keystore $ks -storepass android -keypass android -alias androiddebugkey `
    -dname "CN=Android Debug,O=Android,C=US" -keyalg RSA -keysize 2048 -validity 10000 | Out-Null
}
& (Join-Path $bt "apksigner.bat") sign --ks $ks --ks-pass pass:android --key-pass pass:android `
  --min-sdk-version 21 --out (Join-Path $b "CampusSim.apk") (Join-Path $b "aligned.apk")
if ($LASTEXITCODE -ne 0) { throw "apksigner failed" }

Get-Item (Join-Path $b "CampusSim.apk") | Select-Object FullName, Length

if ($Install) {
  adb -s $Serial install -r (Join-Path $b "CampusSim.apk")
  adb -s $Serial shell "pm list packages | grep campusrun"
}

