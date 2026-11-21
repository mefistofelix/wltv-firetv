# Downloads the newest WLTV release APK and installs it on a Fire TV over network ADB.
# Usage: tools\install.ps1 [-Device 192.168.1.43:5555]
param([string]$Device = "192.168.1.43:5555")
$ErrorActionPreference = "Stop"
$out = Join-Path $PSScriptRoot "..\out\apk"
New-Item -ItemType Directory -Force $out | Out-Null
gh release download -R mefistofelix/wltv-firetv -p "*.apk" -D $out --clobber
$apk = Get-ChildItem $out -Filter *.apk | Sort-Object LastWriteTime | Select-Object -Last 1
adb connect $Device | Out-Null
adb -s $Device install -r $apk.FullName
adb -s $Device shell am start -n com.mefistofelix.wltv/.MainActivity
