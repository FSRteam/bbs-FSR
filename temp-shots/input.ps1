param(
    [int]$ClickX = -1,
    [int]$ClickY = -1,
    [int]$KeyVk = 0,
    [string]$TypeText = "",
    [int]$DoubleClick = 0
)
Add-Type -AssemblyName System.Windows.Forms
Add-Type @"
using System;
using System.Runtime.InteropServices;
public static class Native {
    [DllImport("user32.dll")] public static extern bool SetProcessDPIAware();
    [DllImport("user32.dll")] public static extern bool SetCursorPos(int x, int y);
    [DllImport("user32.dll")] public static extern void mouse_event(uint dwFlags, uint dx, uint dy, uint dwData, UIntPtr dwExtraInfo);
    [DllImport("user32.dll")] public static extern void keybd_event(byte bVk, byte bScan, uint dwFlags, UIntPtr dwExtraInfo);
}
"@
[Native]::SetProcessDPIAware() | Out-Null

function Click-At([int]$x, [int]$y) {
    [Native]::SetCursorPos($x, $y) | Out-Null
    Start-Sleep -Milliseconds 80
    [Native]::mouse_event(0x0002, 0, 0, 0, [UIntPtr]::Zero)
    Start-Sleep -Milliseconds 40
    [Native]::mouse_event(0x0004, 0, 0, 0, [UIntPtr]::Zero)
}

if ($ClickX -ge 0 -and $ClickY -ge 0) {
    if ($DoubleClick -eq 1) {
        Click-At $ClickX $ClickY
        Start-Sleep -Milliseconds 90
        Click-At $ClickX $ClickY
    } else {
        Click-At $ClickX $ClickY
    }
    Write-Output "clicked $ClickX,$ClickY"
}

if ($KeyVk -ne 0) {
    [System.Windows.Forms.SendKeys]::SendWait("") | Out-Null
    [Native]::keybd_event([byte]$KeyVk, 0, 0, [UIntPtr]::Zero)
    Start-Sleep -Milliseconds 60
    [Native]::keybd_event([byte]$KeyVk, 0, 2, [UIntPtr]::Zero)
    Write-Output "pressed vk=$KeyVk"
}

if ($TypeText -ne "") {
    [System.Windows.Forms.SendKeys]::SendWait($TypeText)
    Write-Output "typed '$TypeText'"
}
