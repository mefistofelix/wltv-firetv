# Draws the launcher icon and the TV banner as PNG files (old Fire OS launchers cannot show vector icons).
# Usage: tools\make_icons.ps1   -> res/mipmap-*/ic_launcher.png (48..192 px) and res/drawable-xhdpi/banner.png (320x180)
Add-Type -AssemblyName System.Drawing
$res = Join-Path $PSScriptRoot "..\app\src\main\res"

function New-Color([string]$hex) { [System.Drawing.ColorTranslator]::FromHtml($hex) }

# TV screen with a play triangle, on a 96x96 grid
function Draw-Art($g, [float]$s, [float]$ox, [float]$oy) {
    $state = $g.Save()
    $g.TranslateTransform($ox, $oy)
    $g.ScaleTransform($s, $s)
    $screen = New-Object System.Drawing.Drawing2D.GraphicsPath
    $screen.AddArc(10, 20, 14, 14, 180, 90); $screen.AddArc(72, 20, 14, 14, 270, 90)
    $screen.AddArc(72, 64, 14, 14, 0, 90); $screen.AddArc(10, 64, 14, 14, 90, 90); $screen.CloseFigure()
    $lg = New-Object System.Drawing.Drawing2D.LinearGradientBrush(
        (New-Object System.Drawing.PointF(10, 20)), (New-Object System.Drawing.PointF(86, 78)),
        (New-Color "#2FA8FF"), (New-Color "#1B5FD9"))
    $g.FillPath($lg, $screen)
    $pen = New-Object System.Drawing.Pen((New-Color "#9AD4FF"), 3)
    $g.DrawLine($pen, 38, 8, 48, 19); $g.DrawLine($pen, 58, 8, 48, 19)
    $g.FillRectangle((New-Object System.Drawing.SolidBrush (New-Color "#9AD4FF")), 34, 82, 28, 4)
    $tri = [System.Drawing.PointF[]]@((New-Object System.Drawing.PointF(40, 35)), (New-Object System.Drawing.PointF(40, 63)), (New-Object System.Drawing.PointF(63, 49)))
    $g.FillPolygon([System.Drawing.Brushes]::White, $tri)
    $g.Restore($state)
}

function New-Canvas([int]$w, [int]$h) {
    $bmp = New-Object System.Drawing.Bitmap($w, $h, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $g.TextRenderingHint = [System.Drawing.Text.TextRenderingHint]::AntiAlias
    $g.Clear([System.Drawing.Color]::Transparent)
    return $bmp, $g
}

function New-Background([int]$w, [int]$h) {
    New-Object System.Drawing.Drawing2D.LinearGradientBrush(
        (New-Object System.Drawing.Point(0, 0)), (New-Object System.Drawing.Point($w, $h)),
        (New-Color "#1C2738"), (New-Color "#080B10"))
}

$sizes = @{ mdpi = 48; hdpi = 72; xhdpi = 96; xxhdpi = 144; xxxhdpi = 192 }
foreach ($d in $sizes.Keys) {
    $n = $sizes[$d]
    $bmp, $g = New-Canvas $n $n
    $r = $n * 0.18
    $bg = New-Object System.Drawing.Drawing2D.GraphicsPath
    $bg.AddArc(0, 0, 2 * $r, 2 * $r, 180, 90); $bg.AddArc($n - 2 * $r - 1, 0, 2 * $r, 2 * $r, 270, 90)
    $bg.AddArc($n - 2 * $r - 1, $n - 2 * $r - 1, 2 * $r, 2 * $r, 0, 90); $bg.AddArc(0, $n - 2 * $r - 1, 2 * $r, 2 * $r, 90, 90)
    $bg.CloseFigure()
    $g.FillPath((New-Background $n $n), $bg)
    Draw-Art $g ($n / 96.0) 0 0
    $dir = Join-Path $res "mipmap-$d"; New-Item -ItemType Directory -Force $dir | Out-Null
    $bmp.Save((Join-Path $dir "ic_launcher.png"), [System.Drawing.Imaging.ImageFormat]::Png)
    $g.Dispose(); $bmp.Dispose()
}

$bmp, $g = New-Canvas 320 180
$g.FillRectangle((New-Background 320 180), 0, 0, 320, 180)
Draw-Art $g 1.3 14 28
$font = New-Object System.Drawing.Font("Segoe UI", 44, [System.Drawing.FontStyle]::Bold, [System.Drawing.GraphicsUnit]::Pixel)
$g.DrawString("WLTV", $font, [System.Drawing.Brushes]::White, 142, 52)
$small = New-Object System.Drawing.Font("Segoe UI", 16, [System.Drawing.FontStyle]::Regular, [System.Drawing.GraphicsUnit]::Pixel)
$g.DrawString("TV in diretta", $small, (New-Object System.Drawing.SolidBrush (New-Color "#9AD4FF")), 147, 108)
$dir = Join-Path $res "drawable-xhdpi"; New-Item -ItemType Directory -Force $dir | Out-Null
$bmp.Save((Join-Path $dir "banner.png"), [System.Drawing.Imaging.ImageFormat]::Png)
$g.Dispose(); $bmp.Dispose()
