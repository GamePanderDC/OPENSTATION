param(
    [Parameter(Mandatory = $true)][string]$RootDir,
    [Parameter(Mandatory = $true)][string]$SdkRoot,
    [Parameter(Mandatory = $true)][string]$JavaHome,
    [string]$OutApk = ""
)

$ErrorActionPreference = "Stop"
$rroSrcDir = Join-Path $RootDir "overlay\webview_rro"
if (-not (Test-Path "$rroSrcDir\AndroidManifest.xml")) {
    return
}
if (-not $OutApk) {
    $OutApk = Join-Path $RootDir "firmware\OpenStationWebViewOverlay.apk"
}
$outDir = Split-Path -Parent $OutApk
New-Item -ItemType Directory -Force -Path $outDir | Out-Null

$btDir = Get-ChildItem "$SdkRoot\build-tools\*" -Directory -ErrorAction SilentlyContinue |
    Where-Object { (Test-Path "$($_.FullName)\aapt2.exe") -and (Test-Path "$($_.FullName)\zipalign.exe") -and (Test-Path "$($_.FullName)\apksigner.bat") } |
    Sort-Object Name -Descending | Select-Object -First 1
$platJar = Get-ChildItem "$SdkRoot\platforms\android-*\android.jar" -ErrorAction SilentlyContinue |
    Sort-Object FullName -Descending | Select-Object -First 1 -ExpandProperty FullName
if (-not $btDir -or -not $platJar) {
    throw "Не найдены Android build-tools (aapt2/zipalign/apksigner) или platforms\android-*\android.jar в $SdkRoot для сборки OpenStationWebViewOverlay.apk"
}

$rroBuildDir = Join-Path $RootDir "build\webview_rro"
New-Item -ItemType Directory -Force -Path $rroBuildDir | Out-Null
$rroCompiled = Join-Path $rroBuildDir "compiled.zip"
$rroUnaligned = Join-Path $rroBuildDir "unaligned.apk"
$rroAligned = Join-Path $rroBuildDir "aligned.apk"
Remove-Item -Force -ErrorAction SilentlyContinue $rroCompiled, $rroUnaligned, $rroAligned

& "$($btDir.FullName)\aapt2.exe" compile --dir "$rroSrcDir\res" -o $rroCompiled
if ($LASTEXITCODE -ne 0) { throw "aapt2 compile for webview_rro failed" }
& "$($btDir.FullName)\aapt2.exe" link -I $platJar --manifest "$rroSrcDir\AndroidManifest.xml" -o $rroUnaligned $rroCompiled
if ($LASTEXITCODE -ne 0) { throw "aapt2 link for webview_rro failed" }
& "$($btDir.FullName)\zipalign.exe" -f 4 $rroUnaligned $rroAligned
if ($LASTEXITCODE -ne 0) { throw "zipalign for webview_rro failed" }

$rroKs = Join-Path $env:USERPROFILE ".android\debug.keystore"
if (-not (Test-Path $rroKs)) {
    $rroKs = Join-Path $rroBuildDir "debug.keystore"
    if (-not (Test-Path $rroKs)) {
        & "$JavaHome\bin\keytool.exe" -genkeypair -v -keystore $rroKs -storepass android -alias androiddebugkey -keypass android -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Android Debug,O=Android,C=US" | Out-Null
        if ($LASTEXITCODE -ne 0) { throw "keytool debug.keystore generation failed" }
    }
}

$prevJava = $env:JAVA_HOME
$env:JAVA_HOME = $JavaHome
try {
    & "$($btDir.FullName)\apksigner.bat" sign --ks $rroKs --ks-key-alias androiddebugkey --ks-pass pass:android --key-pass pass:android --out $OutApk $rroAligned
    if ($LASTEXITCODE -ne 0) { throw "apksigner for webview_rro failed" }
} finally {
    $env:JAVA_HOME = $prevJava
}
Write-Host ("  + OpenStationWebViewOverlay.apk {0,8:N0} байт" -f (Get-Item $OutApk).Length) -ForegroundColor Gray
