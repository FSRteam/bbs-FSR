param([int]$X = 0, [int]$Y = 0, [int]$W = 1280, [int]$H = 720)
Add-Type @"
using System;
using System.Text;
using System.Runtime.InteropServices;
public static class Win {
    [DllImport("user32.dll")] public static extern bool SetProcessDPIAware();
    public delegate bool EnumProc(IntPtr hwnd, IntPtr lparam);
    [DllImport("user32.dll")] public static extern bool EnumWindows(EnumProc proc, IntPtr lparam);
    [DllImport("user32.dll")] public static extern int GetWindowText(IntPtr hwnd, StringBuilder sb, int max);
    [DllImport("user32.dll")] public static extern bool IsWindowVisible(IntPtr hwnd);
    [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr hwnd);
    [DllImport("user32.dll")] public static extern bool MoveWindow(IntPtr hwnd, int x, int y, int w, int h, bool repaint);
    [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr hwnd, int cmd);
}
"@
[Win]::SetProcessDPIAware() | Out-Null
$found = [IntPtr]::Zero
$cb = {
    param($hwnd, $lp)
    if (-not [Win]::IsWindowVisible($hwnd)) { return $true }
    $sb = New-Object System.Text.StringBuilder 256
    [Win]::GetWindowText($hwnd, $sb, 256) | Out-Null
    $t = $sb.ToString()
    if ($t -match "Minecraft") {
        Write-Output "FOUND hwnd=$hwnd title='$t'"
        $script:found = $hwnd
    }
    return $true
}
[Win]::EnumWindows($cb, [IntPtr]::Zero) | Out-Null
if ($found -ne [IntPtr]::Zero) {
    [Win]::ShowWindow($found, 9) | Out-Null
    [Win]::MoveWindow($found, $X, $Y, $W, $H, $true) | Out-Null
    [Win]::SetForegroundWindow($found) | Out-Null
    Write-Output "FOCUSED moved to ${X},${Y} size ${W}x${H}"
} else {
    Write-Output "NOT-FOUND"
}
