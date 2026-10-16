# SSMP 0.1.6.4

> The desync & crash diagnostics logger is in this source but switched off: `Diag.BUILD_LOGGER = false` in
> `java/diag/Diag.java`. Builds made that way ship `StarMP/bin/logger.off`, which tells the SSMP launcher not to start
> a crash report. Set it to `true` to make a diagnostics build again.

# SSMP Source (private)

Source for SSMP / StarMP, the Starsector 0.98a-RC8 co-op multiplayer mod. Public releases: the `SSMP` repo.

## Layout
- `java/` – the mod (Starsector API, Java 17). Package `starmp`.
- `rust-smnet/` – the network helper (Iroh / QUIC transport). The mod starts it and talks to it over stdin/stdout (one JSON object per line).
- `moddata/` – mod data files and `mod_info.json` template.
- `tools/` – build, package and sandbox-check scripts.
- `README-mod.txt` – the README shipped inside the mod folder.
- `THIRD_PARTY_LICENSES.txt` – licenses of the Rust crates compiled into the helper (ships with every release).

## Build
```sh
STARSECTOR=/path/to/starsector-core sh tools/build.sh   # StarMP.jar (+ sandbox check)
sh tools/build-helper.sh                                # smnet (Linux) + smnet.exe (Windows)
sh tools/package.sh 0.1.4.3                              # dist/SSMP-0.1.4.3.zip
```
The version in `java/StarMPPlugin.java` (`VERSION`) must match the one passed to `package.sh`.

## Notes
- Starsector's mod sandbox blocks `java.io` file classes and `java.lang.reflect`; `tools/sandbox_check.py` replicates the filter and fails the build if a blocked class is referenced.
- `STARMP_TEST_*` environment variables are test hooks (auto-spawned pirates, auto-join, etc.); they do nothing unless set.
- No anti-cheat: every peer is trusted.
