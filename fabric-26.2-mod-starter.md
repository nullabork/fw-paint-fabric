# Fabric mod starter — Minecraft 26.2 (verified setup)

A reusable scaffold for a **client-side or common Fabric mod targeting Minecraft 26.2**,
distilled from building the Finda mod. Every version and API note here was confirmed by
actually compiling and running against 26.2 on Windows with JDK 25 — not from docs.

Replace `<modid>` (e.g. `coolmod`), `<package>` (e.g. `co.aaron.coolmod`), and
`<ModName>` (e.g. `CoolMod`) throughout.

---

## 1. Target versions

| Component | Value | Notes |
|---|---|---|
| Minecraft | `26.2` | 26.1+ ships **unobfuscated** |
| Fabric Loader | `0.19.3` | |
| Fabric API | `0.152.0+26.2` | depend on `*` to accept patch bumps |
| Loom plugin | `net.fabricmc.fabric-loom` `1.17-SNAPSHOT` | the **non-remapping** plugin, NOT `fabric-loom` |
| Gradle | `9.5.1` | via the wrapper (no global install) |
| Java | **25** | required by 26.2 |

## 2. Prerequisites

- **JDK 25** installed: `winget install EclipseAdoptium.Temurin.25.JDK`. Gradle auto-detects it
  from `C:\Program Files\Eclipse Adoptium\`. (Daemon-JVM auto-download does NOT work on Windows,
  so install it.)
- No global Gradle needed — the wrapper bootstraps 9.5.1.

## 3. ⚠️ 26.2 gotchas — read before writing code

These are the things that cost the most time. They are NOT in most tutorials (which target 1.21.x).

- **Unobfuscated game.** Use the **`net.fabricmc.fabric-loom`** plugin (it does not remap).
  → **No `mappings` line.** → Use plain `implementation` (NOT `modImplementation`). → `jar` not `remapJar`.
- **Java 25 daemon.** Loom checks the daemon JVM. Pin it with `gradle/gradle-daemon-jvm.properties`
  (`toolchainVersion=25`) plus a `java { toolchain { languageVersion = 25 } }` block.
- `ResourceLocation` is now **`Identifier`** (`net.minecraft.resources.Identifier`).
  `ResourceKey.location()` → **`.identifier()`**. Build ids with `Identifier.fromNamespaceAndPath(ns, path)`
  or `Identifier.parse(s)`.
- **Render-state GUI.** `GuiGraphics` → **`GuiGraphicsExtractor`**. Screens override
  **`extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick)`**
  (call `super.extractRenderState(...)` for background + widgets). Draw with
  `g.text(font, str, x, y, argb)`, `g.centeredText(...)`, `g.fill(...)`.
  **Text colours MUST include the alpha byte (`0xFFRRGGBB`) — `0xRRGGBB` renders invisible.**
- `mouseClicked(MouseButtonEvent event, boolean doubled)` — read coords via `event.x()/y()/button()`
  (`net.minecraft.client.input.MouseButtonEvent`).
- Open screens with **`Minecraft.getInstance().setScreenAndShow(screen)`** (no `setScreen`).
- Keybinds: register via **`KeyMappingHelper.registerKeyMapping(new KeyMapping(name, InputConstants.Type.KEYSYM, GLFW_KEY_x, KeyMapping.Category.MISC))`** (module `fabric-key-mapping-api-v1`).
- HUD overlays: **`HudElementRegistry.addLast(Identifier, (g, deltaTracker) -> ...)`** — `HudRenderCallback`
  is gone. `HudElement.extractRenderState(GuiGraphicsExtractor, DeltaTracker)`. (module `fabric-rendering-v1`)
- Action-bar text: `LocalPlayer.sendOverlayMessage(Component)` (no `displayClientMessage`).
- **The client does NOT sync worldgen registries** (noise / noise_settings /
  multi_noise_biome_source_parameter_list). For those, build vanilla registries with
  **`VanillaRegistries.createLookup()`** instead of `connection.registryAccess()`. The biome
  registry IS synced.
- Discover any other moved symbol fast with: `javap -p -classpath <merged.jar> <fqcn>` against
  `~/.gradle/caches/fabric-loom/26.2/minecraft-merged.jar`, or `./gradlew genSources` and grep.

## 4. File tree

```
<project>/
  settings.gradle
  build.gradle
  gradle.properties
  gradlew  gradlew.bat
  gradle/
    wrapper/gradle-wrapper.properties     # Gradle 9.5.1
    wrapper/gradle-wrapper.jar
    gradle-daemon-jvm.properties          # toolchainVersion=25
  src/main/resources/
    fabric.mod.json
    assets/<modid>/lang/en_us.json
  src/main/java/<package>/
    <ModName>Client.java                  # ClientModInitializer (client mod)
    # or <ModName>.java  -> ModInitializer (common/server-side mod)
```

## 5. File contents (copy, then replace `<...>`)

### gradle.properties
```properties
org.gradle.jvmargs=-Xmx2G
org.gradle.parallel=true

minecraft_version=26.2
loader_version=0.19.3
loom_version=1.17-SNAPSHOT
fabric_version=0.152.0+26.2

mod_version=1.0.0
maven_group=<package>
archives_base_name=<modid>
```

### settings.gradle
```groovy
pluginManagement {
    repositories {
        maven { url = 'https://maven.fabricmc.net/' }
        gradlePluginPortal()
    }
}

// Optional: lets Gradle auto-download JDKs for the compile toolchain.
plugins {
    id 'org.gradle.toolchains.foojay-resolver-convention' version '0.9.0'
}

rootProject.name = '<modid>'
```

### build.gradle
```groovy
plugins {
    // 26.1+ uses the NON-remapping loom plugin; needs no mappings (game is unobfuscated).
    id 'net.fabricmc.fabric-loom' version "${loom_version}"
    id 'java'
}

version = project.mod_version
group = project.maven_group
base { archivesName = project.archives_base_name }

repositories {
    maven { url = 'https://maven.fabricmc.net/' }
    mavenCentral() // for JUnit
}

dependencies {
    minecraft "com.mojang:minecraft:${project.minecraft_version}"
    // No `mappings` line — game is unobfuscated. Plain `implementation`, not `modImplementation`.
    implementation "net.fabricmc:fabric-loader:${project.loader_version}"
    implementation "net.fabricmc.fabric-api:fabric-api:${project.fabric_version}"

    testImplementation platform("org.junit:junit-bom:5.11.3")
    testImplementation "org.junit.jupiter:junit-jupiter"
    testRuntimeOnly "org.junit.platform:junit-platform-launcher"
}

test { useJUnitPlatform() }

java {
    toolchain { languageVersion = JavaLanguageVersion.of(25) }  // MC 26.2 requires Java 25
    withSourcesJar()
}

processResources {
    inputs.property "version", project.version
    filesMatching("fabric.mod.json") { expand "version": project.version }
}

tasks.withType(JavaCompile).configureEach {
    options.encoding = 'UTF-8'
    options.release = 25
}
```

### gradle/gradle-daemon-jvm.properties
```properties
toolchainVersion=25
```

### gradle/wrapper/gradle-wrapper.properties
```properties
distributionBase=GRADLE_USER_HOME
distributionPath=wrapper/dists
distributionUrl=https\://services.gradle.org/distributions/gradle-9.5.1-bin.zip
networkTimeout=10000
validateDistributionUrl=true
zipStoreBase=GRADLE_USER_HOME
zipStorePath=wrapper/dists
```

### src/main/resources/fabric.mod.json
```json
{
  "schemaVersion": 1,
  "id": "<modid>",
  "version": "${version}",
  "name": "<ModName>",
  "description": "<one-line description>",
  "authors": ["<you>"],
  "license": "MIT",
  "environment": "client",
  "entrypoints": {
    "client": ["<package>.<ModName>Client"]
  },
  "depends": {
    "fabricloader": ">=0.19.3",
    "minecraft": "~26.2",
    "java": ">=25",
    "fabric-api": "*"
  }
}
```
> For a common/server mod: use `"environment": "*"` and a `"main": ["<package>.<ModName>"]` entrypoint
> implementing `ModInitializer`.

### src/main/java/<package>/<ModName>Client.java
```java
package <package>;

import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class <ModName>Client implements ClientModInitializer {
    public static final Logger LOG = LoggerFactory.getLogger("<modid>");

    @Override
    public void onInitializeClient() {
        LOG.info("<ModName> initialised");
        // register keybinds / HUD / events here — see the gotchas section for 26.2 APIs
    }
}
```

### src/main/resources/assets/<modid>/lang/en_us.json
```json
{
  "key.<modid>.example": "Example keybind",
  "category.<modid>": "<ModName>"
}
```

## 6. Bootstrap the Gradle wrapper (one-time, no global Gradle)

From the project root (Git Bash / PowerShell `Invoke-WebRequest`):
```bash
base=https://raw.githubusercontent.com/gradle/gradle/v9.5.1
curl -L "$base/gradle/wrapper/gradle-wrapper.jar" -o gradle/wrapper/gradle-wrapper.jar
curl -L "$base/gradlew"     -o gradlew
curl -L "$base/gradlew.bat" -o gradlew.bat
```
(The `gradle-wrapper.properties` above already pins 9.5.1, so `./gradlew` will fetch Gradle itself.)

## 7. Build / run / test
```bash
./gradlew build       # -> build/libs/<modid>-1.0.0.jar  (+ runs tests)
./gradlew runClient   # dev Minecraft 26.2 with the mod loaded (Loom provides the loader)
./gradlew test        # unit tests
./gradlew genSources  # decompile MC sources for reference
```
First run downloads Gradle + JDK + decompiles MC (several minutes); later runs are fast.

## 8. Headless worldgen in tests (if you need real registries)
```java
SharedConstants.tryDetectVersion();
Bootstrap.bootStrap();
HolderLookup.Provider registries = VanillaRegistries.createLookup();
```
Lets JUnit use real biome/worldgen data without launching the game.

## 9. Install into a real client (CurseForge / vanilla launcher)
1. Have a **26.2 Fabric** instance (CurseForge instance, or Fabric installer → 26.2). It must already
   contain **Fabric API for 26.2** in its `mods/`.
2. Copy `build/libs/<modid>-1.0.0.jar` into that instance's `mods/` folder.
3. Only put 26.2-compatible mods in that folder — old-version jars crash the game.
4. Config writes to the instance's `config/`, logs to its `logs/`.
