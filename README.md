# SSFML - Sector Space Fabric Mod Loader

A [Fabric Loader](https://fabricmc.net/) bridge that lets [Sector Space](https://store.steampowered.com/app/3978250/Sector_Space/) load Fabric-style Mixin mods.

This is a work in progress fork, it is unofficial, not entirely affiliated with the developer of Sector Space.

Thank you, Darenkel for initial setup of the SSFML files.

See Official Sector Space discord for communication.

## How it works

SSFML wraps Fabric Loader's `Knot` launcher and tells it how to boot Sector Space as its "game," using a custom `GameProvider` (`SectorSpaceProvider`) instead of a native Fabric integration. Mods are discovered from a `mods/` folder next to the game jar, controlled by a simple config file.

## Requirements

- **JDK 25** or later (set via Gradle toolchain, Gradle will resolve this automatically if not already installed via the Foojay resolver plugin)
- Your own copy of **`Sector Space.jar`**, placed in `app/libs/`

`Sector Space.jar` is **not included** in this repository, it's the game's own file and isn't SSFML's to redistribute. Copy it from your Sector Space install directory into `app/libs/` before building.

## Building

```bash
./gradlew jar    # Linux/macOS
gradlew.bat jar  # Windows
```

The built jar is written to `app/build/libs/`.

## Installing

1. Build or download a release build if one is available.
2. Place the built jar in your Sector Space install directory (wherever `Sector Space.jar` actually lives), or anywhere you want to run it.
3. Run it once so it installs needed libs and generates a mod folder and config. On first launch outside game directory, a setup window opens asking you to locate your Sector Space install, this only happens once and is saved for future launches.

## Modding

1. Drop mod `.jar` files into the `mods/` folder next to your Sector Space install (created automatically on first run).
2. Start the game once. SSFML looks through `mods/` and generates a `mods/mod_list.cfg`, listing every mod it found:

   ```
   # Auto-generated mod list, start game to update.
   # Otherwise, add in per-line format: ExJar.jar, true/false
   ExampleMod.jar, false
   ```

3. Every mod is **disabled (false) by default**. Edit `mod_list.cfg` and set the mods you want to `true`.
4. Restart the game to apply mods. Only mods marked `true` will load. `mod_list.cfg` is re-synced against the `mods/` folder on every launch, new jars appear as `false`, and jars you remove disappear from the list automatically.

## Logs

If something goes wrong, check `SSFML_startup_log.txt` in your game folder, it mirrors everything printed to the console to a plain text file.

## Project structure

```
app/
  src/main/java/com/sector/bridge/
    KnotLauncher.java           - Entry point, spawns the Fabric/Knot process
    ModLoader.java              - mod_list.cfg sync and enable/disable logic
    SectorSpaceProvider.java    - Fabric GameProvider implementation for Sector Space
    StartupLogger.java          - Mirrors console output to SSFML_startup_log.txt
    LocVerifierApp.java         - First-run setup UI if not in game directory.
    LocVerifierCFG.java         - Persisted config for game path and version.
  libs/                         - Fabric Loader, Mixin, ASM, and your own Sector Space.jar.
```

## Contributing

This is a personal for fun project and fork of the original SSFML code to clean things up, this code might not have to change and will probably be kept minimal to ensure future compatability.
Though additional forks are always fun so feel free to fork, make issues, PRs, etc.
