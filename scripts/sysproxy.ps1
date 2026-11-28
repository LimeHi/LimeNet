param(
  [ValidateSet('on', 'off')] [string]$Mode = 'off',
  [string]$Server = '127.0.0.1:10809'
)

$ErrorActionPreference = 'Stop'
$key = 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Internet Settings'

if ($Mode -eq 'on') {
  Set-ItemProperty -Path $key -Name ProxyEnable -Value 1 -Type DWord
  Set-ItemProperty -Path $key -Name ProxyServer -Value $Server -Type String
  Set-ItemProperty -Path $key -Name ProxyOverride -Value '<local>' -Type String
} else {
  Set-ItemProperty -Path $key -Name ProxyEnable -Value 0 -Type DWord
}

$src = @'
using System;
using System.Runtime.InteropServices;
public class LimeNetWinInet {
  [DllImport("wininet.dll", SetLastError = true)]
  public static extern bool InternetSetOption(IntPtr h, int opt, IntPtr buf, int len);
}
'@
Add-Type -TypeDefinition $src -ErrorAction SilentlyContinue
[LimeNetWinInet]::InternetSetOption([IntPtr]::Zero, 39, [IntPtr]::Zero, 0) | Out-Null
[LimeNetWinInet]::InternetSetOption([IntPtr]::Zero, 37, [IntPtr]::Zero, 0) | Out-Null

Write-Output 'ok'
