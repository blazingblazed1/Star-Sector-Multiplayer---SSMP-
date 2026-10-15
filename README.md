Edit - 10/15/2026 Build 1.6.2 is almost available doing a little more testing before releasing this build here. Check back later if its not available in releases or check our discord.

many skeptics believe that what has already been done, cant be done. willing to pay for 3 videos showcasing combat and other features of this mod before full release

!!! WORK IN PROGRESS !!! Join the discord for uploading logs and setting up multiplayer sessions with friends or join lobbies here - https://discord.gg/WYTHXRYkb2

Make sure to check for the most recent update in (releases) before trying to join lobbies

Welcome to Starsector Multiplayer! 🚀

People have been trying to make Starsector multiplayer happen for about 10 years, and this is a real attempt at it.

If you'd like to contribute and start testing, head over to ⁠verify and type verify. When I get to it, I'll give you the Tester role. That gets you access to the files and the bug-report channels, so you can tell me about every bug you find.

Thanks for helping out!
Starsector Multiplayer: no longer a theory, it's reality.

Starsector now runs 4-player co-op, and you're not watching the same screen. Each player runs their own engine on their own PC. Hosting uses IROH, and you connect to your host with an in-game ticket. No port forwarding, no Hamachi: the host clicks "Copy Ticket," friends click "Paste Code" in-game.

You all spawn in the tutorial sector, the tutorial missions are synchronized, and you leave the tutorial sector together as a group jump. Then the game begins.

• Everyone has their own fleet for flying around, taking missions, or trading.
• You can join a friend's battle mid-fight when they're screaming in VC flying in circles trying not to die.
• Battle rewards are split by damage dealt and taken, so dedicated tank builds get rewarded.
• You can go pirate, sneak up on your friend, and steal his inventory.
• You can lead the Hegemony into war against your friends at the nasty Luddic Church.

There are bugs, but it works. The early stages are done and need testing.
Current status
The 4-player campaign works right now. I've tested with 1–2 other players and run four sandboxes on my own PC, and everything has worked so far. Before releasing a public branch, I'd like 10–20 playtesters for a few hours to make sure it's solid. Once the important bugs are fixed (which should only take me a couple of hours), I'll release it officially on Nexus Mods.

The long-term vision (feel free to skip)
• A procedural, octagon-shaped galaxy. Each faction gets a large home region with its HQ and is pushed to expand and go to war for more sectors and resources.
• A contested center where every faction fights a little but none can ever fully control it or wipe the others out.
• Finite mining, similar to Avorion: planet mining and asteroid mining.
• Players spawn in the middle, play a dedicated tutorial and mini story campaign, and the story after that is procedurally generated.
• Later: more ship types, custom weapons, and weapon building. Vanilla mechanics come first, though.

The full version likely won't be ready for about 6 months. I have big goals for this, so if you want to be part of it, let me know!

Full Changelog

0.1.2.3 -
Other players' ships show as blue allies
"(Player)" labels over other players' ships and fleets
Friend markers point to players in your system
Joiners can give tactical orders to their ships

0.1.2.4 -
Fixed battle labels showing "player N"
Battle join range check added

0.1.2.5 -
Guests can own battles, others can join
Join code stays the same between sessions
Auto re-host if the network helper dies

0.1.2.6 -
Fixed broken shields and firing for joiners
Strafing and omni shields work for joiners
Smoother own-ship movement for joiners
Battle join offer is now an Intel item

0.1.2.7 -
Shared NPC fleets added (experimental, off by default)
0.1.2.8 -
Fixed crash after joining from the Intel screen
Fixed "controls are locked" for joiners
Ship systems work for joiners
Joiner CR after battle works like vanilla

0.1.2.9 -
Trade screen draft preview

0.1.3.0 -
Trade requests from fleet click or Intel

0.1.3.1 -
Live player-to-player trading
3-2-1 countdown before a trade goes through

0.1.3.2 -
Flagship can't be traded
0.1.3.3 -
Officers stay with their owner when ships trade
Crew don't move with traded ships

0.1.3.4 -
Trade cancel and clear button steps

0.1.3.5 -
Trade sounds added

0.1.3.6 -
Host ignores save files sent by guests

0.1.3.7 -
Guests only accept saves from the host
2 GB save size limit
0.1.3.8 -
Trade preview removed from F8

0.1.3.9 -
README rewritten

0.1.4.0 -
Third-party licenses included

0.1.4.1 -
First public release

0.1.4.2 -
One-click installers for Windows and Linux
0.1.4.3 -
Fixed reconnecting players landing on the skirmish screen
Host resends the save to reconnecting players
Less NPC fleet flickering
Lobby shows each player's version and ping
Players list in Intel

0.1.4.4 -
Lobby cap raised from 4 to 10

0.1.4.5 -
Hyperspace speed setting
Each player's fleet and character saved by name

0.1.4.6 -
Host's world keeps running in menus (never pause)
Shared NPC fleets on by default
Fixed guest lobby button

0.1.4.7 -
Host controls the calendar
Planet orbits synced to guests
Transponder state synced
Guests can't fast-forward time
Lobby flags players with different mods
0.1.4.8 -
Fixed two crashes when engaging fleets

0.1.4.9 -
New players pick a vanilla start
Per-player reputation and commissions
Each player has their own colour
Autosave every 15 min and when a guest leaves
Shared market stock confirmed by the host
Market stock scales with player count
Your own mission targets stay visible
"IN BATTLE" label over fighting fleets

0.1.5.0 -
Loot split percentages round up
Joint battle reputation shared with everyone

0.1.5.1 -
Scoreboard on the tilde key
Out-of-system trades and gifts cost extra

0.1.5.2 -
Fixed planets out of place for guests
Fixed rejoin menu sometimes not appearing
0.1.5.3 (testers only) -
Debug logger: crash report, F9 bug report, F10 overlay

0.1.5.4 (testers only) -
Logs saved in the mod folder, not the desktop

0.1.5.5 (testers only) -
SSMP launcher with Single Player and Multiplayer
"Starsector (SSMP)" desktop shortcut

0.1.5.6 (testers only) -
Diagnostics build label added

0.1.5.7 (testers only) -
Log folders sorted by player name
Multibox test-copy script
0.1.5.8 (Logger Disabled) -
Fixed host stuck in planet/station menus with no buttons
Fixed joined ships acting like 0% CR
Fixed NPC fleets blinking
Debug logger switched off

0.1.5.9 (testers only) -
Debug logger back on
Background windows keep running when not focused
Unfocused windows capped at 30 fps

0.1.6.0 (testers only) -
JOIN BATTLE option in the player contact menu
Fixed save transfer failing with shared saves folders
Warning when two games share one saves folder

0.1.6.1 (testers only) -
Fixed battle notification spam during simultaneous battles
Joining now picks the correct battle

0.1.6.2 (Logger Disabled) -
Fixed bar missions breaking while the host is docked
Debug logger switched off
0.1.6.3 -
Joiners' target lock now reaches their ship
Autofire and guided missiles follow your locked target
Removed the build label from the version name

0.1.6.4 -
Drag-and-drop install, SSMP launcher left out
Installers no longer create a desktop shortcut
Multibox shortcuts open each copy's own Starsector
