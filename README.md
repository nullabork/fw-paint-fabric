# FW Paint (fw-paint-fabric)

A client-side [Fabric](https://fabricmc.net/) mod for **Minecraft 26.2** that paints with blocks —
solid extrude/fill, colour/brightness gradients, and 3D noise patterns. This README is the
**developer/build guide**; user-facing documentation lives in
[`docs/curseforge-description.md`](docs/curseforge-description.md).

## Requirements

| What | Version | Notes |
|---|---|---|
| JDK | **25** (Temurin recommended) | Minecraft 26.2 requires Java 25 |
| Minecraft | 26.2 | pinned in `gradle.properties` |
| Fabric Loader | 0.19.3+ | pinned in `gradle.properties` |
| Fabric API | 0.152.0+26.2 | compile dep; also needed in any instance running the jar |
| Fabric Loom | 1.17-SNAPSHOT | Gradle plugin; 26.2 ships unobfuscated, Loom runs non-remapping |
| Gradle | — | none needed; the wrapper (`gradlew`) downloads itself |

Installing JDK 25:

- **Windows**: `winget install EclipseAdoptium.Temurin.25.JDK` — Gradle auto-detects it from
  `C:\Program Files\Eclipse Adoptium\`. (Gradle's daemon-JVM auto-download does **not** work on
  Windows, so install the JDK yourself.)
- **Linux (Debian/Ubuntu)**: `sudo apt install temurin-25-jdk` (Adoptium repo) or download from
  [adoptium.net](https://adoptium.net). Any JDK 25 works; the build uses a Java 25 toolchain.

## Build

```bash
# Linux / macOS / Git Bash
./gradlew build

# Windows (cmd / PowerShell)
gradlew.bat build
```

- Output: `build/libs/fw-paint-fabric-<version>.jar` (plus a `-sources` jar).
- `build` also runs the unit tests; a red build means failing tests, not just compile errors.
- First build downloads Minecraft + dependencies — allow a few minutes and disk space under
  `~/.gradle` / `%USERPROFILE%\.gradle`.

## Run the dev client

```bash
./gradlew runClient
```

Launches Minecraft 26.2 with the mod loaded (offline dev session; Realms/auth warnings in the log
are normal). The dev instance keeps its own world/config under `run/`.

## Tests

```bash
./gradlew test
```

Unit tests cover the pure, Minecraft-free maths: gradient ordering/deviation (`GradientRampTest`),
texture pixel analysis (`TextureStatsTest`), noise (`NoiseTest`), flood fill (`FloodFillTest`),
noise placement ordering (`NoisePlacerTest`), and pick numbering (`PicksTest`). Anything touching
Minecraft classes is exercised via `runClient`, not tests.

## Install the jar into a real instance

Copy `build/libs/fw-paint-fabric-<version>.jar` into the instance's `mods/` folder. The instance
must be **Fabric for Minecraft 26.2** and already contain **Fabric API** for 26.2. The mod is
client-side only (`"environment": "client"`); servers need nothing.

## Versioning & releases

- The version lives in `gradle.properties` (`mod_version`) — bump it there; `fabric.mod.json` picks
  it up at build time.
- CI (`.github/workflows/build.yml`): every push builds + tests and uploads the jar as the
  `fw-paint-jar` artifact; pushing a tag like `v1.1.0` publishes a GitHub Release with the jar
  attached.

## Project layout

```
src/main/java/co/fax/wang/
  Gradient.java          client entrypoint: keybinds, HUD, tick wiring, shared state
  GradientScreen.java    the K screen (Solid / Gradient / Noise Paint / Settings tabs)
  BlockPickerPanel.java  reusable scrollable block list
  HudOverlay.java        helper-text rendering    HudPlacementScreen.java  move-the-text screen
  MarkerManager.java     marker placement/persistence + in-world rendering
  GradientPlacer.java    gradient line placement  NoisePlacer.java  noise flood fill
  SolidPlacer.java       solid extrude + 3D fill  BlockPlacement.java  multiplayer-safe placing
  GradientRamp.java      gradient-order maths     BlockTextures.java / TextureStats.java  texture analysis
  Noise.java / NoiseType.java / FloodFill.java / Picks.java / CurveFunction.java
  ToolMode.java / PaintType.java / SolidMatch.java / GradientMode.java / GradientSource.java
  config/GradientConfig.java + config/ConfigManager.java   Gson config -> config/gradient.json
src/test/java/co/fax/wang/   unit tests (pure maths only)
fabric-26.2-mod-starter.md   26.2 API migration notes (worth reading before touching GUI/render code)
```

## Gotchas

- **26.2 is unobfuscated** — no mappings/remapping; Loom runs in non-remapping mode. Don't add
  mapping-dependent tooling.
- 26.2 renamed/replaced several APIs (`GuiGraphics` → `GuiGraphicsExtractor`, `HudRenderCallback` →
  `HudElementRegistry`, `setScreen` → `setScreenAndShow`, keybind registration via
  `KeyMappingHelper`) — see `fabric-26.2-mod-starter.md` before porting snippets from older versions.
- Config is plain Gson with defaults for every field, so adding config fields is
  backwards-compatible; removing/renaming needs a migration (see `ConfigManager.migrateLegacyTools`).

## License

MIT.
