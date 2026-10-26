# Adds (or removes) the DATUM-Editor folder to the user's PATH. Used by Add-Command.bat / Remove-Command.bat.
# Only the current user's PATH is changed (no administrator rights needed).
param([string]$Dir, [switch]$Remove)
$dir = (Resolve-Path -LiteralPath $Dir).Path.TrimEnd('\')
$key = Get-Item -Path 'HKCU:\Environment'
# Read the raw value so that entries like %USERPROFILE%\... stay as they are
$old = $key.GetValue('Path', '', [Microsoft.Win32.RegistryValueOptions]::DoNotExpandEnvironmentNames)
$parts = @($old -split ';' | Where-Object { $_ -ne '' -and $_.TrimEnd('\') -ne $dir })
if (-not $Remove) { $parts += $dir }
Set-ItemProperty -Path 'HKCU:\Environment' -Name 'Path' -Value ($parts -join ';') -Type ExpandString
# Tell Windows that the environment changed (new windows will see the new PATH)
[Environment]::SetEnvironmentVariable('DATUM_EDITOR_PATH_REFRESH', '1', 'User')
[Environment]::SetEnvironmentVariable('DATUM_EDITOR_PATH_REFRESH', $null, 'User')
if ($Remove) {
    Write-Host ('Removed from PATH: ' + $dir)
} else {
    Write-Host ('Added to PATH: ' + $dir)
    Write-Host 'Open a NEW Command Prompt or PowerShell window and type:  datum   (or: datum file.txt)'
    Write-Host 'If you move this folder, run Add-Command.bat again.'
}
