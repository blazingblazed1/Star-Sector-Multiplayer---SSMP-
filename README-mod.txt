== SSMP 0.9.4.1 ==

SSMP (StarMP) - Starsector co-op multiplayer over Iroh peer-to-peer (no VPN or port forwarding needed)
Up to 10 players (tested live with 4). Starsector 0.98a-RC8.

!! NO ANTI-CHEAT !!  Every player's game is trusted. Anyone in your session can affect your game (fleet, credits,
cargo, battle results, the save the host sends). Only play with people you trust.

Install: run single-click-install-windows.bat (or put the StarMP folder in Starsector\mods yourself), then tick
StarMP in the Starsector launcher's Mods list. Every player needs the same version. Windows Firewall may ask about smnet.exe (the network helper): allow it.
Linux: bash single-click-install-linux.sh (optionally followed by your Starsector folder). On NixOS / packaged installs
(game in a read-only folder) it installs into ~/.local/share/starsector/mods. Since 0.9.1.5 the Linux helper is a fully
static program, so it runs on any Linux (NixOS included) with nothing extra installed. Both installers check at the end
that the network helper starts ("Network helper check: OK") and say so if it can't (e.g. antivirus blocked smnet.exe).
Nothing else needs installing: the helper only uses parts of Windows / Linux itself, and Starsector brings its own Java.

[[LAUNCHER]]
Starting the game: use the "Starsector (SSMP)" desktop shortcut the installer makes (or mods\StarMP\bin\ssmp-launcher.exe).
  - IROH: online play through a join code (no port forwarding, no VPN). Ticks SSMP, starts the crash logger, then
    opens Starsector's normal launcher.
  - LAN: local network only (same Wi-Fi / router, or a VPN such as Hamachi). Iroh is not used at all: the host opens
    a plain TCP port (24780) and games on your network are found automatically on the JOIN screen - no join codes.
    If a game isn't listed, type the host's address (like 25.1.2.3). The first time, Windows may ask to allow
    the helper (smnet) through the firewall - allow it on private networks. Same as ticking "LAN mode" in the game.
  To play without SSMP, untick it in Starsector's own Mods screen.
  Your other mods are never changed, and you can still tick / untick anything in Starsector's own Mods screen.
  The launcher is optional: drop the StarMP folder into Starsector\mods, tick it, and start Starsector normally -
  Host / Join work and use IROH (tick "LAN mode" in the game for LAN).

[[/LAUNCHER]]
Main menu (replaces Starsector's): SINGLE PLAYER, MULTIPLAYER (lobby), OPTIONS (your name, LAN mode), QUIT GAME.
Multiplayer: HOST GAME -> COPY the join code -> send it (LAN: nothing to send, friends see your game in the list). Friends: JOIN GAME (code pasted automatically) -> JOIN.
  The lobby flags a player whose version or enabled mods differ from yours.
  Everyone presses READY; host presses START CAMPAIGN. The host's newest save is copied to everyone;
  then Load Game -> pick that save. The host's join code stays the same between sessions.

Campaign:
  - Each player flies their own fleet. Other players show "(Player)" and their name; a blue P marker around your fleet
    points at friends in the same system (or in hyperspace within the range set in F8).
  - The host runs the one shared world. It doesn't stop for menus: while the host is in a market, a planet or
    station dialog, a map/cargo tab or the Esc menu, the world keeps running for everyone (F8 "Never pause").
    While the host talks to or fights a fleet, the host's world holds (the game requires it).
    Only the pause key pauses everyone. Guests follow the host's pause and the host's date, and can't fast-forward.
  - Scoreboard: press ` / ~ (change the key in F8) on the map. Click a player to contact them from anywhere (send
    credits, propose a trade); click yourself to change your portrait. Or click another player's fleet.
  - Out-of-system tax: sending to a player in another star system (or more than 5 light-years away in hyperspace - free within 5 only when both of you are in hyperspace)
    costs the sender extra: 100% of credits sent, 50% of the value of items and ships. Same system: free.
  - Trading: both players add cargo, ships and credits; press the check mark when ready. When both are ready a
    3-second countdown runs (X stops it; X again clears both offers; X with empty offers cancels the trade).
    Officers stay with their owner. Your flagship can't be traded.
  - Battles: when anyone fights, the others get a notice and an Intel entry. Within 700 units press Join the battle.
    You command only your own ships; losses and damage are applied to your own fleet. Loot is split: half by damage
    dealt, half by damage taken, each rounded up to the next 5%. Reputation isn't split: everyone in the fight gets
    the same standing change.
  - NPC fleets are shared (the host's are the real ones; F8 can turn this off). Fleets you destroy are gone for
    everyone. Hostile NPCs can see and chase any player; when one catches you, your encounter opens. While anyone
    talks to or fights a fleet, it holds still for everyone.
  - New players start fresh: anyone the host's save doesn't know yet picks one of the five vanilla starts and begins
    near Corvus at level 1, with new-captain reputation and no commission - also everyone joining a new campaign.
  - Your stuff is yours: the host's save keeps every player's fleet, cargo, officers, skills, reputation, commission
    and position under their player name. Join again with the same name and you get them back. The host autosaves
    when a player leaves and every 15 minutes.
  - Markets are shared (the host's stock; storage too). More players, more stock: +40% per extra player when a
    market restocks. If someone else bought it first, it goes back and you're refunded.
  - Missions and bounties are your own. "IN BATTLE" shows over any fleet that is fighting.
  - Hyperspace travel is faster while playing together (F8, default x1.5; the host's setting is used).

F8 on the campaign map: status, host / join / copy code / disconnect (reconnect without leaving the game), options.

Background windows (0.1.5.9): Starsector normally freezes a paused game whose window isn't focused. SSMP turns that
off so alt-tabbed and multiboxed copies keep running (and keep following the host); an unfocused window is capped
at 30 fps while connected (10 fps otherwise) to save CPU/GPU.

Logs: Starsector\saves\common\starmp_log.txt.data   and   starsector-core\starsector.log

Debug log, crash report and bug reports (diagnostics builds only - not active in this build):
  - While the game runs, "StarMP crash report - DO NOT DELETE - <name> - <time>.txt" sits in the folder
    mods\StarMP\crash logs\<your name> with the last ~30 seconds. Close the game normally (Quit from the main menu) and it is
    deleted. If the game crashes, freezes or is force-closed it stays, with the end of starsector.log added - send
    it to the mod author.
  - F9: saves "StarMP bug report - <name> - <time>.txt" (last 60 seconds) to mods\StarMP\player logs\<your name>. When
    something looks wrong, have everyone nearby press F9 at about the same time. F10: performance overlay on/off.
  - What's in them: your player name, the time and time zone, the mod version and your mod list; what YOUR screen
    shows - your fleet, other players' fleets and NPC fleets (positions, speeds, states), and in battles every
    ship, shot and your own key/mouse input; performance (fps, frame times, memory, network traffic, ping); and
    your PC: CPU / RAM / GPU model and speed, SSD or HDD, Wi-Fi or Ethernet and link speed, OS version, CPU / GPU /
    RAM use. Your Windows user name is removed from file paths.
  - What's NOT in them: no other programs, no files, no passwords, no browsing - nothing outside the game and
    these hardware numbers. Nothing is uploaded anywhere: the files stay on your PC until YOU send them. The only
    extra network use is a tiny clock-sync ping to the host so everyone's logs line up.
  - Multiboxing (several game windows on one PC, for testing): run "Multibox - make test copies.bat" in the StarMP
    folder. It makes full copies of Starsector in "Starsector Multibox\Copy 2..N" next to your install (each about the
    size of your install), each with its own name (Tester2, Tester3...) and a desktop shortcut "Starsector (SSMP) #N".
    All copies write their logs into THIS install's crash logs / player logs folders. Run it again to update them.
  - The F8 option "reports go to" can put them on the desktop instead, or turn the crash report off.
Host settings and permissions (0.9.0.0): HOST GAME first opens HOST SETTINGS (also the lobby's SETTINGS button and F8 ->
  Host settings & permissions): max players (2-10), who can pause (everyone / host + admins / host only), who can
  fast-forward everyone, who can kick, votes on/off, map pings on/off, never pause, shared NPC fleets, hyperspace speed.
  Players without a permission start a vote instead (Intel pop-up or F8: Vote YES / NO); more than half of the players
  must say yes within 20 s. F8 -> Players: kick (host + admins by default) and make/remove admins (host).
  Fast-forward for everyone (F8): every player's campaign runs at the host's fast-forward speed together.
Map pings: middle-click on the map or in a joined battle - everyone looking at the same place sees it for 6 s.
  F8 -> My settings: pings on/off per layer (combat, star systems, hyperspace).
Main map (Tab): every other player has a "P" marker (system view: where they are; sector view: their spot / system).
Chat: Enter on the campaign map or in a battle, type, Enter sends (Esc cancels). The window is see-through above the
  ability bar: drag inside it to move it, drag its edge to resize, mouse wheel to scroll back. F8 -> My settings: on/off.
Scoreboard (`): click your portrait to pick any portrait in the game; click your name to change it (once every
  5 minutes; the host moves your saved character to the new name and saves).
Guests' own toolbar (all ability bars), map filters, map zoom and refit filters are now kept with their character.
Bot test (load-testing on one PC, Windows): run "SSMP bot test.bat" in the StarMP folder.
  1  starts YOUR game with diagnostics on (load your campaign, F8 -> Host, F8 -> Copy the join code).
  2  starts up to 9 bot clients from this same install - nothing is copied. Each bot gets its own saves + logs folder
     in %LOCALAPPDATA%\SSMP-bots\botN, skips the Starsector launcher, runs in a small window with no sound at a capped
     frame rate (~1.7 GB RAM each), joins your code and loads your save once you press F8 -> Send this campaign.
     Bots take the mercenary start, fly to you (follow / wander / mixed), join every battle you start nearby and fight
     the nearest enemy. Bots that join later get your save automatically.
  3  stops all bots.   4  records your game's network / frame-time numbers every second while you play.
  5  shows the recorded numbers (upload/download KB/s, packets/s, helper pipe time, host fps, per campaign/combat).
  Bot mode only exists in games started by this script (STARMP_BOT=1); a normal game never runs it.
  After a bot test, use Load Game instead of Continue once: Continue may point at a bot's copy of your save.
Third-party licenses for the network helper: THIRD_PARTY_LICENSES.txt
