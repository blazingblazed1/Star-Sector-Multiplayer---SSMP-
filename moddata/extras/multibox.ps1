# SSMP multibox test copies (Windows). Started by "Multibox - make test copies.bat" in the StarMP folder.
# Makes full copies of this Starsector install in "<parent>\Starsector Multibox\Copy 2..N", each with its own SSMP
# player name (Tester2, Tester3...), its logs pointed at THIS install's StarMP\crash logs + player logs, and a desktop
# shortcut "Starsector (SSMP) #N". Run it again to update the copies (their saves and names are kept).
$ErrorActionPreference = 'Stop'
$mod  = Split-Path -Parent $PSScriptRoot                 # ...\mods\StarMP
$game = Split-Path -Parent (Split-Path -Parent $mod)     # ...\Starsector
if (!(Test-Path (Join-Path $game 'starsector.exe'))) { Write-Host "This must run from <Starsector>\mods\StarMP. Nothing done."; exit 1 }
$mbRoot = Join-Path (Split-Path -Parent $game) 'Starsector Multibox'
Write-Host "Main install: $game"
Write-Host "Copies go to: $mbRoot"
$n = Read-Host 'How many game clients in total, including this main install? [6]'
if ([string]::IsNullOrWhiteSpace($n)) { $n = 6 }
$n = [int]$n
if ($n -lt 2) { Write-Host 'Nothing to do.'; exit 0 }
$mainCommon = Join-Path $game 'saves\common'
$mainSet = Join-Path $mainCommon 'starmp_settings.txt.data'
$base = @(); if (Test-Path $mainSet) { $base = @(Get-Content $mainSet | Where-Object { $_ -notmatch '^(name|logRoot)=' }) }
$desk = [Environment]::GetFolderPath('Desktop')
$sh = New-Object -ComObject WScript.Shell
for ($k = 2; $k -le $n; $k++) {
  $dest = Join-Path $mbRoot "Copy $k"
  Write-Host "Copy $k ..."
  robocopy $game $dest /MIR /XD (Join-Path $game 'saves') (Join-Path $mod 'crash logs') (Join-Path $mod 'player logs') /XF starmp_host_identity.txt /NFL /NDL /NJH /NJS /NP | Out-Null
  if ($LASTEXITCODE -ge 8) { Write-Host "Copy $k failed (robocopy code $LASTEXITCODE). Is there enough disk space?"; exit 1 }
  $common = Join-Path $dest 'saves\common'
  New-Item -ItemType Directory -Force $common | Out-Null
  # Starsector's own settings: copied once, then left alone
  if (Test-Path $mainCommon) {
    Get-ChildItem $mainCommon -File | Where-Object { $_.Name -notlike 'starmp_*' } | ForEach-Object {
      $t = Join-Path $common $_.Name; if (!(Test-Path $t)) { Copy-Item $_.FullName $t } }
  }
  # SSMP settings: this install's options, the copy's own name (kept on re-runs), logs to the main install
  $set = Join-Path $common 'starmp_settings.txt.data'
  $name = $null; if (Test-Path $set) { $name = Get-Content $set | Where-Object { $_ -match '^name=' } | Select-Object -First 1 }
  if (!$name) { $name = "name=Tester$k" }
  Set-Content -Path $set -Value (@($base) + $name + "logRoot=$mod") -Encoding ASCII
  $s = $sh.CreateShortcut((Join-Path $desk "Starsector (SSMP) #$k.lnk"))
  $l = Join-Path $dest 'mods\StarMP\bin\ssmp-launcher.exe'
  if (Test-Path $l) { $s.TargetPath = $l } else { $s.TargetPath = Join-Path $dest 'starsector.exe' }  # builds without the SSMP launcher: the copy's own Starsector
  $s.WorkingDirectory = $dest
  $s.IconLocation = (Join-Path $dest 'starsector.exe') + ',0'
  $s.Description = "Starsector multibox copy $k (SSMP)"
  $s.Save()
  Write-Host "  $($name.Substring(5)) - shortcut 'Starsector (SSMP) #$k' on the desktop"
}
Write-Host ''
Write-Host "Done. All copies write their crash logs and player logs to $mod (one folder per player name)."
Write-Host 'Tip: in each copy''s Starsector launcher pick a small windowed resolution.'
