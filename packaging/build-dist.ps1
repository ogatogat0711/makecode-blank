param(
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$AppName = "MakeCode設計書メーカー",
    [string]$Version = "0.1.0"
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$inputDir = Join-Path $root "target\dist-input"
$dist = Join-Path $root "dist"

if (-not $JavaHome -or -not (Test-Path "$JavaHome\bin\jpackage.exe")) {
    throw "JDK 17 の場所を -JavaHome で指定してください（jpackage.exe が必要です）"
}

Write-Host "1/5 アプリをビルドします"
$env:JAVA_HOME = $JavaHome
mvn -q -f "$root\pom.xml" clean package
if ($LASTEXITCODE -ne 0) { throw "mvn package に失敗しました" }

Write-Host "2/5 依存ライブラリを集めます"
Remove-Item -Recurse -Force $inputDir -ErrorAction SilentlyContinue
mvn -q -f "$root\pom.xml" dependency:copy-dependencies "-DoutputDirectory=$inputDir" -DincludeScope=runtime
if ($LASTEXITCODE -ne 0) { throw "依存ライブラリの収集に失敗しました" }
Copy-Item "$root\target\makecode-blank-$Version.jar" $inputDir

Write-Host "3/5 3Dビューアをビルドします"
Push-Location "$root\viewer"
cargo build --release
Pop-Location
if (-not (Test-Path "$root\viewer\target\release\makecode-viewer.exe")) { throw "3Dビューアのビルドに失敗しました" }
Copy-Item "$root\viewer\target\release\makecode-viewer.exe" $inputDir

Write-Host "4/5 配布用フォルダを作ります（数分かかります）"
Remove-Item -Recurse -Force $dist -ErrorAction SilentlyContinue
if (Test-Path $dist) { throw "$dist を消せませんでした。アプリやエクスプローラーで開いていないか確認してください" }
& "$JavaHome\bin\jpackage.exe" `
    --type app-image `
    --name $AppName `
    --app-version $Version `
    --input $inputDir `
    --main-jar "makecode-blank-$Version.jar" `
    --main-class app.Launcher `
    --icon "$PSScriptRoot\app.ico" `
    --dest $dist `
    --add-modules java.se,jdk.jsobject,jdk.xml.dom,jdk.crypto.ec,jdk.localedata,jdk.unsupported `
    --java-options "-Dfile.encoding=UTF-8" `
    --java-options "-Dstdout.encoding=UTF-8" `
    --java-options "-Dprism.maxvram=1G"
if ($LASTEXITCODE -ne 0) { throw "jpackage に失敗しました" }

Copy-Item "$PSScriptRoot\使い方.txt" "$dist\$AppName\"

Write-Host "5/5 ZIP にまとめます"
$zip = Join-Path $dist "$AppName-$Version-windows.zip"
Compress-Archive -Path "$dist\$AppName" -DestinationPath $zip -Force

$size = [math]::Round((Get-Item $zip).Length / 1MB, 1)
Write-Host ""
Write-Host "完成しました: $zip （$size MB）"
Write-Host "配布前に dist\$AppName\$AppName.exe を起動して動作を確認してください。"
