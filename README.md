# Gradient

A client-side Fabric mod for Minecraft 26.2 (`gradient`), generated from the
starter-fabric-client template.

## Build / run

```bash
./gradlew build       # -> build/libs/gradient-1.0.0.jar
./gradlew runClient   # dev Minecraft 26.2 with the mod loaded
./gradlew test        # unit tests
```

## Included example

Press **K** in-game to open an example settings screen (`ExampleScreen`) backed by a
persisted config (`config/gradient.json`). It demonstrates the 26.2 keybind, screen
(render-state + text-alpha), and config patterns. Edit or delete `ExampleScreen`,
`ExampleUtil`, and the `config` package as you build out your mod.

See `fabric-26.2-mod-starter.md` for the 26.2-specific API notes and the version-update guide.
