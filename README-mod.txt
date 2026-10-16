== SSMP 0.1.6.4 ==

SSMP (StarMP) - Starsector co-op multiplayer over Iroh peer-to-peer (no VPN or port forwarding needed)
Up to 10 players (tested live with 4). Starsector 0.98a-RC8.

!! NO ANTI-CHEAT !!  Every player's game is trusted. Anyone in your session can affect your game (fleet, credits,
cargo, battle results, the save the host sends). Only play with people you trust.

Install: run single-click-install-windows.bat (or put the StarMP folder in Starsector\mods yourself), then tick
StarMP in the Starsector launcher's Mods list. Every player needs the same version. Windows Firewall may ask about smnet.exe (the network helper): allow it.

[[LAUNCHER]]
Starting the game: use the "Starsector (SSMP)" desktop shortcut the installer makes (or mods\StarMP\bin\ssmp-launcher.exe).
  - Single Player: unticks SSMP in the mod list, then opens Starsector's normal launcher. Vanilla play, no logging.
  - Multiplayer: ticks SSMP, then opens Starsector's normal launcher.
  Your other mods are never changed, and you can still tick / untick anything in Starsector's own Mods screen.
  Diagnostics builds only: started any other way (your old shortcut), Host / Join stay greyed out.

[[/LAUNCHER]]
Main menu (replaces Starsector's): SINGLE PLAYER, MULTIPLAYER (lobby), OPTIONS (your name, LAN mode), QUIT GAME.
Multiplayer: HOST GAME -> COPY the join code -> send it. Friends: JOIN GAME (code pasted automatically) -> JOIN.
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
  - Out-of-system tax: sending to a player in another star system (or more than 2 light-years away in hyperspace)
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
Third-party licenses for the network helper: THIRD_PARTY_LICENSES.txt
