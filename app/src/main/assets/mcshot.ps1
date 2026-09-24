# Mobile Claude screenshot helper for Windows (Windows PowerShell 5.1+).
# The app pipes this to powershell.exe over SSH and prepends:
#   $Mode = 'list' | 'shot'; $Kind = 'screen' | 'output' | 'active' | 'window'; $Target = '...'
#   list -> "kind<TAB>id<TAB>label" lines;  shot -> PNG as base64
#
# Programs started by sshd can't see the desktop (they run outside the
# user's interactive session), so the actual GUI work runs as a one-off
# scheduled task inside the logged-in user's session, and this outer part
# only waits for its result file.
#
# Keep this file ASCII: it reaches PowerShell through the console code page.

$ErrorActionPreference = 'Stop'

$inner = @'
param($Mode, $Kind, $Target, $Out)
$ErrorActionPreference = 'Stop'
try {
    Add-Type -AssemblyName System.Windows.Forms, System.Drawing
    Add-Type -TypeDefinition @"
using System;
using System.Text;
using System.Collections.Generic;
using System.Runtime.InteropServices;

public static class McWin {
    public delegate bool EnumProc(IntPtr h, IntPtr l);
    [StructLayout(LayoutKind.Sequential)] public struct RECT { public int L, T, R, B; }

    [DllImport("user32.dll")] static extern bool EnumWindows(EnumProc cb, IntPtr l);
    [DllImport("user32.dll")] static extern bool IsWindowVisible(IntPtr h);
    [DllImport("user32.dll")] public static extern bool IsIconic(IntPtr h);
    [DllImport("user32.dll")] public static extern bool IsWindow(IntPtr h);
    [DllImport("user32.dll", CharSet = CharSet.Unicode)] static extern int GetWindowText(IntPtr h, StringBuilder s, int n);
    [DllImport("user32.dll")] static extern int GetWindowTextLength(IntPtr h);
    [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
    [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr h, int cmd);
    [DllImport("user32.dll")] static extern int GetWindowLong(IntPtr h, int i);
    [DllImport("user32.dll")] static extern IntPtr GetWindow(IntPtr h, uint cmd);
    [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr h, out uint pid);
    [DllImport("user32.dll")] public static extern bool PrintWindow(IntPtr h, IntPtr hdc, uint flags);
    [DllImport("user32.dll")] public static extern bool SetProcessDPIAware();
    [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr h, out RECT r);
    [DllImport("dwmapi.dll")] static extern int DwmGetWindowAttribute(IntPtr h, int a, out RECT r, int size);
    [DllImport("dwmapi.dll")] static extern int DwmGetWindowAttribute(IntPtr h, int a, out int v, int size);

    // Windows a person would call "open windows": visible, titled, not
    // owned popups, not tool windows, not cloaked (hidden UWP frames).
    public static List<IntPtr> TopWindows() {
        var list = new List<IntPtr>();
        EnumWindows(delegate (IntPtr h, IntPtr l) {
            if (!IsWindowVisible(h) || GetWindowTextLength(h) == 0) return true;
            if (GetWindow(h, 4) != IntPtr.Zero) return true;          // GW_OWNER
            if ((GetWindowLong(h, -20) & 0x80) != 0) return true;      // WS_EX_TOOLWINDOW
            int cloaked;
            if (DwmGetWindowAttribute(h, 14, out cloaked, 4) == 0 && cloaked != 0) return true;
            list.Add(h);
            return true;
        }, IntPtr.Zero);
        return list;
    }

    public static string Title(IntPtr h) {
        var s = new StringBuilder(512);
        GetWindowText(h, s, 512);
        return s.ToString();
    }

    // Visible bounds without the invisible resize border / shadow.
    public static RECT Bounds(IntPtr h) {
        RECT r;
        if (DwmGetWindowAttribute(h, 9, out r, 16) != 0) GetWindowRect(h, out r);
        return r;
    }
}
"@
    [McWin]::SetProcessDPIAware() | Out-Null

    function Save($bmp) {
        $bmp.Save($Out, [System.Drawing.Imaging.ImageFormat]::Png)
        $bmp.Dispose()
    }

    function Grab($x, $y, $w, $h) {
        $b = New-Object System.Drawing.Bitmap $w, $h
        $g = [System.Drawing.Graphics]::FromImage($b)
        $g.CopyFromScreen($x, $y, 0, 0, (New-Object System.Drawing.Size $w, $h))
        $g.Dispose()
        Save $b
    }

    function GrabWindow([IntPtr]$h) {
        if ($h -eq [IntPtr]::Zero -or -not [McWin]::IsWindow($h)) { throw 'E_NOWINDOW' }
        if ([McWin]::IsIconic($h)) {
            [McWin]::ShowWindow($h, 9) | Out-Null   # SW_RESTORE; minimized windows have no pixels
            Start-Sleep -Milliseconds 400
        }
        $vis = [McWin]::Bounds($h)
        $wr = New-Object McWin+RECT
        [McWin]::GetWindowRect($h, [ref]$wr) | Out-Null
        $w = $wr.R - $wr.L; $hh = $wr.B - $wr.T
        if ($w -le 0 -or $hh -le 0) { throw 'E_NOWINDOW' }
        # PrintWindow renders the window even if other windows cover it, and
        # doesn't bring it to the front on the PC.
        $full = New-Object System.Drawing.Bitmap $w, $hh
        $g = [System.Drawing.Graphics]::FromImage($full)
        $hdc = $g.GetHdc()
        $ok = [McWin]::PrintWindow($h, $hdc, 2)   # PW_RENDERFULLCONTENT
        $g.ReleaseHdc($hdc); $g.Dispose()
        if (-not $ok) {
            $full.Dispose()
            Grab $vis.L $vis.T ($vis.R - $vis.L) ($vis.B - $vis.T)
            return
        }
        $crop = New-Object System.Drawing.Rectangle ($vis.L - $wr.L), ($vis.T - $wr.T), ($vis.R - $vis.L), ($vis.B - $vis.T)
        $crop.Intersect((New-Object System.Drawing.Rectangle 0, 0, $w, $hh))
        if ($crop.Width -le 0 -or $crop.Height -le 0) { Save $full; return }
        $b = $full.Clone($crop, $full.PixelFormat)
        $full.Dispose()
        Save $b
    }

    if ($Mode -eq 'list') {
        $lines = New-Object System.Collections.Generic.List[string]
        $i = 0
        foreach ($s in [System.Windows.Forms.Screen]::AllScreens) {
            $name = $s.DeviceName.TrimStart('\', '.')
            $lines.Add("output`t$i`t$name  $($s.Bounds.Width)x$($s.Bounds.Height)")
            $i++
        }
        foreach ($h in [McWin]::TopWindows()) {
            $procId = [uint32]0
            [McWin]::GetWindowThreadProcessId($h, [ref]$procId) | Out-Null
            $proc = '?'
            try { $proc = (Get-Process -Id $procId).ProcessName } catch { }
            $title = ([McWin]::Title($h)) -replace "[`t`r`n]", ' '
            $lines.Add("window`t$($h.ToInt64())`t$proc - $title")
        }
        [IO.File]::WriteAllLines($Out, $lines, (New-Object System.Text.UTF8Encoding $false))
    } else {
        switch ($Kind) {
            'screen' {
                $v = [System.Windows.Forms.SystemInformation]::VirtualScreen
                Grab $v.X $v.Y $v.Width $v.Height
            }
            'output' {
                $b = ([System.Windows.Forms.Screen]::AllScreens[[int]$Target]).Bounds
                Grab $b.X $b.Y $b.Width $b.Height
            }
            'active' { GrabWindow ([McWin]::GetForegroundWindow()) }
            'window' { GrabWindow ([IntPtr][long]$Target) }
            default { throw "E_KIND $Kind" }
        }
    }
} catch {
    [IO.File]::WriteAllText("$Out.err", $_.Exception.Message)
} finally {
    [IO.File]::WriteAllText("$Out.done", '1')
}
'@

try {
    $work = Join-Path $env:LOCALAPPDATA 'MobileClaude'
    New-Item -ItemType Directory -Force -Path $work | Out-Null
    $id = [guid]::NewGuid().ToString('N').Substring(0, 8)
    $innerPath = Join-Path $work 'shot.ps1'
    [IO.File]::WriteAllText($innerPath, $inner, (New-Object System.Text.UTF8Encoding $true))
    $out = Join-Path $work "out-$id"

    $psArgs = "-NoProfile -NonInteractive -ExecutionPolicy Bypass -WindowStyle Hidden -File `"$innerPath`" -Mode $Mode -Kind `"$Kind`" -Target `"$Target`" -Out `"$out`""
    # conhost --headless runs it with no console window at all: nothing
    # flashes on the PC and the active window keeps its focus.
    $exe = 'powershell.exe'; $arg = $psArgs
    $conhost = Join-Path $env:WINDIR 'System32\conhost.exe'
    if (Test-Path $conhost) { $exe = $conhost; $arg = "--headless powershell.exe $psArgs" }

    $task = "MobileClaudeShot-$id"
    $user = try { [Security.Principal.WindowsIdentity]::GetCurrent().Name } catch { "$env:USERDOMAIN\$env:USERNAME" }
    $action = New-ScheduledTaskAction -Execute $exe -Argument $arg
    $principal = New-ScheduledTaskPrincipal -UserId $user -LogonType Interactive -RunLevel Limited
    $settings = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -ExecutionTimeLimit (New-TimeSpan -Minutes 2)
    Register-ScheduledTask -TaskName $task -Action $action -Principal $principal -Settings $settings -Force | Out-Null
    try {
        Start-ScheduledTask -TaskName $task
        $deadline = (Get-Date).AddSeconds(25)
        while (-not (Test-Path "$out.done")) {
            if ((Get-Date) -gt $deadline) { throw 'E_NOSESSION' }
            Start-Sleep -Milliseconds 80
        }
        if (Test-Path "$out.err") { throw ([IO.File]::ReadAllText("$out.err")) }
        if ($Mode -eq 'list') {
            [Console]::Out.Write("de`twindows`twindows`nscreen`tall`t-`nactive`tactive`t-`n")
            $bytes = [IO.File]::ReadAllBytes($out)
            # Titles are UTF-8; send them as base64 so the console code page can't mangle them.
            [Console]::Out.Write("b64`t" + [Convert]::ToBase64String($bytes) + "`n")
        } else {
            [Console]::Out.Write([Convert]::ToBase64String([IO.File]::ReadAllBytes($out)))
        }
    } finally {
        Unregister-ScheduledTask -TaskName $task -Confirm:$false -ErrorAction SilentlyContinue
        Remove-Item "$out*" -Force -ErrorAction SilentlyContinue
    }
} catch {
    [Console]::Error.WriteLine($_.Exception.Message)
    exit 2
}
