# FW Paint (fw-paint-fabric)

A client-side mod for **Minecraft 26.2** — for **Fabric and NeoForge** — that paints with blocks:
solid extrude/fill, colour/brightness gradients, and 3D noise patterns. This README is the
**developer/build guide**; user-facing documentation lives in
[`docs/curseforge-description.md`](docs/curseforge-description.md).

## Requirements

| What | Version | Notes |
|---|---|---|
| JDK | **25** (Temurin recommended) | Minecraft 26.2 requires Java 25 |
| Minecraft | 26.2 | pinned in `gradle.properties` |
| Fabric Loader | 0.19.3+ | pinned in `gradle.properties` |
| Fabric API | 0.152.0+26.2 | compile dep; also needed in any Fabric instance running the jar |
| Fabric Loom | 1.17-SNAPSHOT | Gradle plugin; 26.2 ships unobfuscated, Loom runs non-remapping |
| NeoForge | 26.2.0.11-beta | pinned in `gradle.properties` (`neo_version`) |
| ModDevGradle | 2.0.141 | NeoForge's Gradle plugin (`moddev_version`) |
| Gradle | — | none needed; the wrapper (`gradlew`) downloads itself |

Installing JDK 25:

- **Windows**: `winget install EclipseAdoptium.Temurin.25.JDK` — Gradle auto-detects it from
  `C:\Program Files\Eclipse Adoptium\`. (Gradle's daemon-JVM auto-download does **not** work on
  Windows, so install the JDK yourself.)
- **Linux (Debian/Ubuntu)**: `sudo apt install temurin-25-jdk` (Adoptium repo) or download from
  [adoptium.net](https://adoptium.net). Any JDK 25 works; the build uses a Java 25 toolchain.

## Module layout (multiloader)

All game code is loader-free and lives in **`common/`** — 26.x ships unobfuscated with Mojang
names, so the exact same compiled classes run on both loaders. **`fabric/`** and **`neoforge/`**
are thin shells: an entrypoint class each (~80 lines) that registers keybinds and forwards tick /
render / input events into the common core, plus the loader metadata file. Each loader jar bundles
common's classes and assets, so the shipped jars are self-contained.

## Build

```bash
# Linux / macOS / Git Bash
./gradlew build

# Windows (cmd / PowerShell)
gradlew.bat build
```

- Outputs: `fabric/build/libs/fw-paint-fabric-<version>.jar` and
  `neoforge/build/libs/fw-paint-neoforge-<version>.jar`.
- `build` also runs the unit tests; a red build means failing tests, not just compile errors.
- First build downloads Minecraft + dependencies for both loaders — allow a few minutes and disk
  space under `~/.gradle` / `%USERPROFILE%\.gradle`.

## Run the dev client

```bash
./gradlew runClient             # Fabric (alias for :fabric:runClient)
./gradlew :neoforge:runClient   # NeoForge
```

Launches Minecraft 26.2 with the mod loaded (offline dev session; Realms/auth warnings in the log
are normal). Each loader keeps its own world/config under `fabric/run/` and `neoforge/run/`.

## Tests

```bash
./gradlew test
```

Unit tests live in `common/` and cover the pure, Minecraft-free maths: gradient
ordering/deviation (`GradientRampTest`), texture pixel analysis (`TextureStatsTest`), noise
(`NoiseTest`), flood fill (`FloodFillTest`), noise placement ordering (`NoisePlacerTest`), and
pick numbering (`PicksTest`). Anything touching Minecraft classes is exercised via `runClient`,
not tests.

## Install the jar into a real instance

Copy the jar matching the instance's loader into its `mods/` folder:

- **Fabric**: `fw-paint-fabric-<version>.jar` — the instance must also contain **Fabric API**
  for 26.2.
- **NeoForge**: `fw-paint-neoforge-<version>.jar` — no other dependency.

The mod is client-side only; servers need nothing.

## Versioning & releases

- The version lives in `gradle.properties` (`mod_version`) — bump it there; both
  `fabric.mod.json` and `neoforge.mods.toml` pick it up at build time.
- CI (`.github/workflows/build.yml`): every push builds + tests and uploads both jars as the
  `fw-paint-jars` artifact; pushing a tag like `v1.2.0` publishes a GitHub Release with both jars
  attached (body from `docs/release-notes/<tag>.md`).

## Project layout

```
common/src/main/java/co/fax/wang/
  Gradient.java          loader-free core: keybinds, tick driving, shared state
  GradientScreen.java    the K screen (Solid / Gradient / Noise Paint / Settings / Help tabs)
  BlockPickerPanel.java  reusable scrollable block list      HelpPanel.java  in-game manual
  HudOverlay.java        helper-text rendering    HudPlacementScreen.java  move-the-text screen
  MarkerManager.java     marker modes/persistence + in-world rendering
  PaintPlacer.java       unified Single/Face/3D placement for all paint types
  GradientChoice.java    gradient palette/pick maths          GradientCaches.java  session memory
  NoisePlacer.java       noise ordering + region helpers      BlockPlacement.java  multiplayer-safe placing
  GradientRamp.java      gradient-order maths     BlockTextures.java / TextureStats.java  texture analysis
  Noise.java / NoiseType.java / FloodFill.java / Picks.java / CurveFunction.java / FaceOverlay.java
  PlacementMode.java / PaintType.java / SolidMatch.java / GradientMode.java / GradientSource.java
  config/GradientConfig.java + config/ConfigManager.java   Gson config -> config/gradient.json
common/src/test/java/co/fax/wang/   unit tests (pure maths only)
fabric/src/main/java/co/fax/wang/fabric/GradientFabric.java       Fabric entrypoint
neoforge/src/main/java/co/fax/wang/neoforge/GradientNeoForge.java NeoForge entrypoint
fabric-26.2-mod-starter.md   26.2 API migration notes (worth reading before touching GUI/render code)
```

## Gotchas

- **26.2 is unobfuscated** — no mappings/remapping; Loom runs in non-remapping mode and NeoForge
  uses the same Mojang names, which is what makes the shared `common/` module possible. Don't add
  mapping-dependent tooling.
- 26.2 renamed/replaced several APIs (`GuiGraphics` → `GuiGraphicsExtractor`, `setScreen` →
  `setScreenAndShow`, HUD/keybind registration) — see `fabric-26.2-mod-starter.md` before porting
  snippets from older versions.
- **Nothing in `common/` may import loader classes** — its compile classpath contains only the
  vanilla jar, so a stray `net.fabricmc`/`net.neoforged` import fails the build by design.
- Config is plain Gson with defaults for every field, so adding config fields is
  backwards-compatible; removing/renaming needs a migration (see `ConfigManager.migrateLegacyTools`).

## License

MIT.
