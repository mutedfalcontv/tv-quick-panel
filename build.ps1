# Builds tv-quick-panel.apk with the plain SDK toolchain (aapt2 -> javac -> d8 -> apksigner).
$ErrorActionPreference = "Stop"

# Env overrides let CI build on a clean runner; the defaults stay the local dev setup.
$Root      = if ($env:APP_ROOT) { $env:APP_ROOT } else { Split-Path -Parent $MyInvocation.MyCommand.Path }
$Sdk       = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } elseif ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } else { "C:\Android\android-sdk" }
$BtVersion = if ($env:ANDROID_BUILD_TOOLS) { $env:ANDROID_BUILD_TOOLS } else { "36.0.0" }
$Platform  = if ($env:ANDROID_PLATFORM) { $env:ANDROID_PLATFORM } else { "android-36" }
$Bt        = "$Sdk\build-tools\$BtVersion"
$Jar       = "$Sdk\platforms\$Platform\android.jar"
# JAVA_HOME on developer machines often points at an old JDK, so use an explicit override.
$Jdk       = if ($env:TVQP_JAVA_HOME) { $env:TVQP_JAVA_HOME } else { "C:\Program Files\Eclipse Adoptium\jdk-21.0.9.10-hotspot" }
$Build     = "$Root\build"

# d8/apksigner are .bat wrappers that resolve java from PATH; force the JDK that compiled us.
$env:JAVA_HOME = $Jdk
$env:PATH = "$Jdk\bin;$env:PATH"

if (Test-Path $Build) { Remove-Item $Build -Recurse -Force }
New-Item -ItemType Directory -Path "$Build\gen","$Build\classes","$Build\dex" -Force | Out-Null

Write-Host "== aapt2 compile"
& "$Bt\aapt2.exe" compile --dir "$Root\res" -o "$Build\res.zip"
if ($LASTEXITCODE -ne 0) { throw "aapt2 compile failed" }

Write-Host "== aapt2 link"
& "$Bt\aapt2.exe" link -o "$Build\base.apk" -I $Jar --manifest "$Root\AndroidManifest.xml" `
    -R "$Build\res.zip" --java "$Build\gen" --auto-add-overlay `
    --min-sdk-version 21 --target-sdk-version 34
if ($LASTEXITCODE -ne 0) { throw "aapt2 link failed" }

Write-Host "== javac"
$Sources = @(Get-ChildItem -Recurse -File "$Root\src" -Filter *.java | ForEach-Object { $_.FullName })
$Sources += @(Get-ChildItem -Recurse -File "$Build\gen" -Filter *.java | ForEach-Object { $_.FullName })
# An @argfile instead of splatting the array: some PowerShell 7 hosts mangle
# long native argument lists, and javac then compiles nothing yet exits 0.
$Sources | Out-File "$Build\sources.txt" -Encoding ascii
& "$Jdk\bin\javac.exe" -nowarn -source 11 -target 11 -classpath $Jar -d "$Build\classes" "@$Build\sources.txt"
if ($LASTEXITCODE -ne 0) { throw "javac failed" }
if (-not (Get-ChildItem -Recurse -File "$Build\classes" -Filter *.class)) { throw "javac produced no class files" }

Write-Host "== d8"
$Classes = @(Get-ChildItem -Recurse -File "$Build\classes" -Filter *.class | ForEach-Object { $_.FullName })
$Classes | Out-File "$Build\classes.txt" -Encoding ascii
& "$Jdk\bin\java.exe" -cp "$Bt\lib\d8.jar" com.android.tools.r8.D8 --min-api 21 --lib $Jar --output "$Build\dex" "@$Build\classes.txt"
if ($LASTEXITCODE -ne 0) { throw "d8 failed" }
if (-not (Test-Path "$Build\dex\classes.dex")) { throw "d8 produced no classes.dex" }

Write-Host "== inject classes.dex"
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::Open("$Build\base.apk", [System.IO.Compression.ZipArchiveMode]::Update)
try {
    $existing = $zip.GetEntry("classes.dex")
    if ($existing) { $existing.Delete() }
    [void][System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile(
        $zip, "$Build\dex\classes.dex", "classes.dex", [System.IO.Compression.CompressionLevel]::Optimal)
} finally { $zip.Dispose() }

Write-Host "== zipalign"
& "$Bt\zipalign.exe" -f -p 4 "$Build\base.apk" "$Build\aligned.apk"
if ($LASTEXITCODE -ne 0) { throw "zipalign failed" }

Write-Host "== sign"
$Ks = "$env:USERPROFILE\.android\debug.keystore"
& "$Bt\apksigner.bat" sign --ks $Ks --ks-pass pass:android --key-pass pass:android `
    --ks-key-alias androiddebugkey --out "$Root\tv-quick-panel.apk" "$Build\aligned.apk"
if ($LASTEXITCODE -ne 0) { throw "apksigner failed" }

& "$Bt\apksigner.bat" verify --print-certs "$Root\tv-quick-panel.apk" | Select-Object -First 3
Write-Host ("== done: {0} ({1} KB)" -f "$Root\tv-quick-panel.apk", [math]::Round((Get-Item "$Root\tv-quick-panel.apk").Length / 1KB, 1))
