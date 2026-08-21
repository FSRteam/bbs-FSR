param([string]$Image = "shot.png")
Add-Type -AssemblyName System.IO
Add-Type -AssemblyName System.Runtime.WindowsRuntime
Add-Type @"
using System;
using System.Runtime.InteropServices;
public static class Dpi2 { [DllImport("user32.dll")] public static extern bool SetProcessDPIAware(); }
"@
[Dpi2]::SetProcessDPIAware() | Out-Null

# Load WinRT OCR
$null = [Windows.Media.Ocr.OcrEngine, Windows.Foundation, ContentType=WindowsRuntime]
$null = [Windows.Globalization.Language, Windows.Foundation, ContentType=WindowsRuntime]
$null = [Windows.Graphics.Imaging.BitmapDecoder, Windows.Foundation, ContentType=WindowsRuntime]
$null = [Windows.Storage.StorageFile, Windows.Foundation, ContentType=WindowsRuntime]

function Await($winrtTask, $resultType) {
    $asTask = [System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object {
        $_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1'
    } | Select-Object -First 1
    $netTask = $asTask.MakeGenericMethod($resultType).Invoke($null, @($winrtTask))
    $netTask.Wait() | Out-Null
    $netTask.Result
}

$langs = [Windows.Media.Ocr.OcrEngine]::AvailableLanguages
$engine = $null
foreach ($code in @("en-US", "zh-CN", "zh-Hans-CN")) {
    foreach ($l in $langs) {
        if ($l.LanguageTag -like "$code*") { $engine = [Windows.Media.Ocr.OcrEngine]::TryCreateFromLanguage($l); break }
    }
    if ($engine) { break }
}
if (-not $engine) { $engine = [Windows.Media.Ocr.OcrEngine]::TryCreateFromUserProfileLanguages() }
if (-not $engine) { Write-Output "NO-OCR-ENGINE"; exit 1 }

$file = Await ([Windows.Storage.StorageFile]::GetFileFromPathAsync((Resolve-Path $Image).Path)) ([Windows.Storage.StorageFile])
$stream = Await ($file.OpenAsync([Windows.Storage.FileAccessMode]::Read)) ([Windows.Storage.Streams.IRandomAccessStream])
$decoder = Await ([Windows.Graphics.Imaging.BitmapDecoder]::CreateAsync($stream)) ([Windows.Graphics.Imaging.BitmapDecoder])
$bitmap = Await ($decoder.GetSoftwareBitmapAsync()) ([Windows.Graphics.Imaging.SoftwareBitmap])

$result = Await ($engine.RecognizeAsync($bitmap)) ([Windows.Media.Ocr.OcrResult])

foreach ($line in $result.Lines) {
    $text = $line.Text
    $minX = -1; $minY = -1; $maxX = -1; $maxY = -1
    foreach ($word in $line.Words) {
        $r = $word.BoundingRect
        if ($minX -lt 0 -or $r.X -lt $minX) { $minX = [int]$r.X }
        if ($minY -lt 0 -or $r.Y -lt $minY) { $minY = [int]$r.Y }
        if (($r.X + $r.Width) -gt $maxX) { $maxX = [int]($r.X + $r.Width) }
        if (($r.Y + $r.Height) -gt $maxY) { $maxY = [int]($r.Y + $r.Height) }
    }
    Write-Output ("({0},{1})-({2},{3}) {4}" -f $minX, $minY, $maxX, $maxY, $text)
}
