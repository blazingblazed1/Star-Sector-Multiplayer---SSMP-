# SSMP bot test (Windows). Started by "SSMP bot test.bat" in the StarMP folder.
#
# Load-tests SSMP on one PC: you play the host in your normal game, and up to 9 bot clients join it. Bots are normal
# Starsector windows started from THIS install (nothing is copied) with their own saves + logs folders under
# %LOCALAPPDATA%\SSMP-bots\botN. They skip the Starsector launcher, run in a small window with no sound at a capped
# frame rate, join your code, load your save, fly around / follow you and join your battles by themselves.
# Bot mode only exists when a game is started by this script (environment variable STARMP_BOT=1).
#
# Nothing here touches the registry or anything outside this install and %LOCALAPPDATA%\SSMP-bots.
$ErrorActionPreference = 'Stop'
$mod  = Split-Path -Parent $PSScriptRoot                 # ...\Starsector\mods\StarMP
$game = Split-Path -Parent (Split-Path -Parent $mod)     # ...\Starsector
$core = Join-Path $game 'starsector-core'
$java = Join-Path $game 'jre\bin\javaw.exe'      # javaw: no console window
$vm   = Join-Path $game 'vmparams'
$root = Join-Path $env:LOCALAPPDATA 'SSMP-bots'
foreach ($p in @($core, $java, $vm)) { if (!(Test-Path $p)) { Write-Host "Not found: $p - this must run from <Starsector>\mods\StarMP. Nothing done."; exit 1 } }

function Q([string]$a) { if ($a -match '\s') { return '"' + $a + '"' } else { return $a } }

# vmparams = "java.exe <jvm flags> -classpath <jars> com.fs.starfarer.StarfarerLauncher" (one line)
function BuildArgs([string]$saves, [string]$logs, [string]$shots, [int]$heapMb, [bool]$bot, [string]$res) {
  $tok = (Get-Content $vm -Raw).Trim() -split '\s+'
  $out = New-Object System.Collections.Generic.List[string]
  for ($i = 1; $i -lt $tok.Count; $i++) {
    $t = $tok[$i]
    if ($t -eq '-classpath' -or $t -eq '-cp') {
      if ($bot) {
        $out.Add('-Xms256m'); $out.Add("-Xmx$($heapMb)m")
        $out.Add('-DlaunchDirect=true'); $out.Add("-DstartRes=$res"); $out.Add('-DstartFS=false'); $out.Add('-DstartSound=false')
      }
      if ($saves) { $out.Add((Q "-Dcom.fs.starfarer.settings.paths.saves=$saves")) }
      if ($logs)  { $out.Add((Q "-Dcom.fs.starfarer.settings.paths.logs=$logs")) }
      if ($shots) { $out.Add((Q "-Dcom.fs.starfarer.settings.paths.screenshots=$shots")) }
    }
    if ($bot -and ($t -like '-Xmx*' -or $t -like '-Xms*' -or $t -eq '-XX:+AlwaysPreTouch')) { continue }
    if ($saves -and $t -like '-Dcom.fs.starfarer.settings.paths.saves=*') { continue }
    if ($logs  -and $t -like '-Dcom.fs.starfarer.settings.paths.logs=*') { continue }
    if ($shots -and $t -like '-Dcom.fs.starfarer.settings.paths.screenshots=*') { continue }
    $out.Add($t)
  }
  return ($out -join ' ')
}

function BotProcs { @(Get-CimInstance Win32_Process -Filter "Name='javaw.exe'" | Where-Object { $_.CommandLine -like '*SSMP-bots*' }) }

function FreeGb { [math]::Round((Get-CimInstance Win32_OperatingSystem).FreePhysicalMemory / 1MB, 1) }

function StartHost {
  Write-Host 'Starting your game with SSMP diagnostics on (network / frame-time stats every second).'
  Write-Host 'Play normally: load your campaign, F8 -> Host, copy the join code (F8 -> Copy), then come back here.'
  $env:STARMP_DIAG = '1'; $env:STARMP_LAUNCHER = '1'
  try { Start-Process -FilePath $java -ArgumentList (BuildArgs '' '' '' 0 $false '') -WorkingDirectory $core | Out-Null }
  finally { Remove-Item Env:STARMP_DIAG, Env:STARMP_LAUNCHER -ErrorAction SilentlyContinue }
}

function StartBots {
  $code = ''
  try { $code = (Get-Clipboard -Raw).Trim() } catch {}
  if (-not $code.StartsWith('SM1:')) { $code = (Read-Host 'Paste the host join code (SM1:...)').Trim() }
  if (-not $code.StartsWith('SM1:')) { Write-Host 'That is not an SSMP join code.'; return }
  Write-Host "Join code: $($code.Substring(0, [Math]::Min(24, $code.Length)))..."
  $running = (BotProcs).Count
  $free = FreeGb
  $n = Read-Host "How many bots to start? (running now: $running, free RAM $free GB, ~1.7 GB per bot) [4]"
  if ([string]::IsNullOrWhiteSpace($n)) { $n = 4 }; $n = [int]$n
  if ($running + $n -gt 9) { $n = 9 - $running; Write-Host "SSMP allows 9 guests - starting $n." }
  if ($n -le 0) { return }
  if ($n * 1.7 -gt $free - 1) { $ok = Read-Host "That needs about $([math]::Round($n * 1.7, 1)) GB and $free GB is free. Start anyway? (y/N)"; if ($ok -ne 'y') { return } }
  $mode = Read-Host 'Bot behaviour: mixed (follow you, sometimes wander) / follow / wander / idle [mixed]'
  if ([string]::IsNullOrWhiteSpace($mode)) { $mode = 'mixed' }
  $fps = Read-Host 'Bot frame rate cap [30]'; if ([string]::IsNullOrWhiteSpace($fps)) { $fps = 30 }
  $heap = Read-Host 'Bot memory (MB) - raise it for big mod lists [1024]'; if ([string]::IsNullOrWhiteSpace($heap)) { $heap = 1024 }
  $min = Read-Host 'Start bot windows minimized? (y/N - minimized is untested on Windows; if bots stall, use normal windows)'
  $style = 'Normal'; if ($min -eq 'y') { $style = 'Minimized' }
  $mainSet = Join-Path $game 'saves\common\starmp_settings.txt.data'
  $base = @(); if (Test-Path $mainSet) { $base = @(Get-Content $mainSet | Where-Object { $_ -notmatch '^(name|logRoot)=' }) }
  $started = 0
  for ($k = 1; $k -le 9 -and $started -lt $n; $k++) {
    $dir = Join-Path $root "bot$k"
    if ((BotProcs | Where-Object { $_.CommandLine -like "*\bot$k\*" }).Count -gt 0) { continue }  # already running
    $saves = Join-Path $dir 'saves'; $logs = Join-Path $dir 'logs'; $shots = Join-Path $dir 'screenshots'
    New-Item -ItemType Directory -Force (Join-Path $saves 'common'), $logs, $shots | Out-Null
    Set-Content -Path (Join-Path $saves 'common\starmp_settings.txt.data') -Value (@($base) + "name=Bot$k") -Encoding ASCII
    $env:STARMP_BOT = '1'; $env:STARMP_BOT_CODE = $code; $env:STARMP_BOT_MODE = $mode; $env:STARMP_BOT_FPS = "$fps"
    try { Start-Process -FilePath $java -ArgumentList (BuildArgs $saves $logs $shots ([int]$heap) $true '960x600') -WorkingDirectory $core -WindowStyle $style | Out-Null }
    finally { Remove-Item Env:STARMP_BOT, Env:STARMP_BOT_CODE, Env:STARMP_BOT_MODE, Env:STARMP_BOT_FPS -ErrorAction SilentlyContinue }
    $started++
    Write-Host "  Bot$k starting (log: $logs\starsector.log)"
    if ($started -lt $n) { Start-Sleep -Seconds 10 }
  }
  Write-Host ''
  Write-Host 'Bots take a minute to load and then join you. When the players list shows them, in your game press'
  Write-Host 'F8 -> Send this campaign. Bots load it, pick a mercenary start, fly to you and join your battles.'
  Write-Host 'Bots that join later get your save automatically.'
}

function StopBots {
  $p = BotProcs
  if ($p.Count -eq 0) { Write-Host 'No bots running.'; return }
  foreach ($x in $p) { Stop-Process -Id $x.ProcessId -Force -ErrorAction SilentlyContinue }
  Write-Host "Stopped $($p.Count) bot(s)."
}

function Num([string]$line, [string]$pattern) { $m = [regex]::Match($line, $pattern); if ($m.Success) { return [double]$m.Groups[1].Value } else { return $null } }

# One "PERF" line (written once a second by a game started with option 1) -> one row
function PerfRow([string]$line) {
  $pm = [regex]::Match($line, '\| peers ([^|]*)')
  [pscustomobject]@{
    time    = ([regex]::Match($line, '\| (\d{12,}) \|')).Groups[1].Value
    ctx     = ([regex]::Match($line, 'ctx=(\w+)')).Groups[1].Value
    players = $(if ($pm.Success) { ($pm.Groups[1].Value.Trim() -split ', ').Count } else { 0 })
    fps     = Num $line 'fps=([\d.]+)'
    outKB   = Num $line 'out ([\d.]+) KB/s'
    outPk   = Num $line 'out [\d.]+ KB/s (\d+) pk'
    inKB    = Num $line 'net in ([\d.]+) KB/s'
    inPk    = Num $line 'net in [\d.]+ KB/s (\d+) pk'
    pipeUs  = Num $line 'writes avg ([\d.]+)us'
    pipeMax = Num $line 'writes avg [\d.]+us max ([\d.]+)us'
    ships   = Num $line 'combat \(owner\): ships (\d+)'
  }
}

# The host game's live diagnostics file only holds the last ~30-60 s (and is deleted when the game closes normally),
# so recording polls it and keeps every new second in host-stats.csv.
function HostLog {
  $places = @((Join-Path $mod 'crash logs'), [Environment]::GetFolderPath('Desktop'))   # F8 "reports go to": mod folder or desktop
  $logs = Get-ChildItem -Path $places -Recurse -Depth 2 -Filter 'StarMP crash report*.txt' -ErrorAction SilentlyContinue |
          Where-Object { $_.FullName -notmatch '\\Bot[0-9]\\' } | Sort-Object LastWriteTime -Descending
  foreach ($l in $logs) { if (Select-String -Path $l.FullName -Pattern '\| PERF \|' -Quiet) { return $l } }
  return $null
}

function Record {
  New-Item -ItemType Directory -Force $root | Out-Null
  $csv = Join-Path $root 'host-stats.csv'
  $seen = @{}
  if (Test-Path $csv) { foreach ($r in (Import-Csv $csv)) { $seen[$r.time] = 1 } }
  Write-Host "Recording host stats to $csv - press any key to stop."
  while ($true) {
    $f = HostLog
    if ($f) {
      $new = @()
      try { $lines = (Select-String -Path $f.FullName -Pattern '\| PERF \|').Line } catch { $lines = @() }
      foreach ($line in $lines) { $r = PerfRow $line; if ($r.time -and -not $seen.ContainsKey($r.time)) { $seen[$r.time] = 1; $new += $r } }
      if ($new.Count -gt 0) {
        $new | Export-Csv -Path $csv -NoTypeInformation -Append
        $l = $new[-1]
        Write-Host ("  {0,-8} players {1}  upload {2,6:N1} KB/s  {3,4} pk/s   download {4,5:N1} KB/s   pipe {5,6:N1} us   host fps {6,5:N1}" -f $l.ctx, $l.players, $l.outKB, $l.outPk, $l.inKB, $l.pipeUs, $l.fps)
      }
    } else { Write-Host '  waiting for the host game (start it with option 1)...' }
    for ($w = 0; $w -lt 20; $w++) { if ([Console]::KeyAvailable) { [void][Console]::ReadKey($true); Write-Host 'Stopped recording.'; return }; Start-Sleep -Milliseconds 500 }
  }
}

function Report {
  $csv = Join-Path $root 'host-stats.csv'
  if (!(Test-Path $csv)) { Write-Host 'Nothing recorded yet - use option 4 while you play with the bots.'; return }
  $rows = @(Import-Csv $csv | ForEach-Object { foreach ($p in 'players','fps','outKB','outPk','inKB','inPk','pipeUs','pipeMax','ships') { if ($_.$p -ne '') { $_.$p = [double]$_.$p } }; $_ })
  Write-Host "$($rows.Count) seconds recorded in $csv"
  foreach ($c in @('campaign', 'combat')) {
    $r = @($rows | Where-Object { $_.ctx -eq $c -and $_.players -gt 0 })
    if ($r.Count -eq 0) { continue }
    $m = { param($p) $s = $r | Where-Object { $_.$p -ne '' } | Measure-Object -Property $p -Average -Maximum; '{0,7:N1} avg {1,8:N1} max' -f $s.Average, $s.Maximum }
    Write-Host ''
    Write-Host "  $c - $($r.Count) s with players connected (most at once: $(($r | Measure-Object players -Maximum).Maximum))"
    Write-Host "    upload KB/s   $(& $m 'outKB')"
    Write-Host "    packets out/s $(& $m 'outPk')"
    Write-Host "    download KB/s $(& $m 'inKB')"
    Write-Host "    packets in/s  $(& $m 'inPk')"
    Write-Host "    pipe write us $(& $m 'pipeUs')   (worst single write $((($r | Measure-Object pipeMax -Maximum).Maximum)) us)"
    Write-Host "    host fps      $(& $m 'fps')"
    if ($c -eq 'combat') { Write-Host "    ships         $(& $m 'ships')" }
  }
}

while ($true) {
  Write-Host ''
  Write-Host "=== SSMP bot test ===   bots running: $((BotProcs).Count)   free RAM: $(FreeGb) GB"
  Write-Host '  1  Start my game with diagnostics (do this first, then host from F8)'
  Write-Host '  2  Start bots (join code from the clipboard)'
  Write-Host '  3  Stop all bots'
  Write-Host '  4  Record host network / frame stats (while you play)'
  Write-Host '  5  Show the recorded stats'
  Write-Host '  6  Delete the bot folders (their saves, logs and the stats)'
  Write-Host '  Q  Quit (bots keep running until option 3 or you close them)'
  switch ((Read-Host 'Choose').Trim().ToUpper()) {
    '1' { StartHost }
    '2' { StartBots }
    '3' { StopBots }
    '4' { Record }
    '5' { Report }
    '6' { if ((BotProcs).Count -gt 0) { Write-Host 'Stop the bots first (3).' } elseif (Test-Path $root) { Remove-Item -Recurse -Force $root; Write-Host "Deleted $root" } else { Write-Host 'Nothing to delete.' } }
    'Q' { exit 0 }
    default {}
  }
}
