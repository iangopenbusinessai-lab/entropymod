# Entropy

A Fabric mod for Minecraft 1.26.1.2. Every interval (default 3 min), you must
choose 1 of 3 random Blessings (good), then next interval 1 of 3 random Curses
(bad), repeating. Each pick raises Entropy by 1. Beat the Ender Dragon before
Entropy hits the cap (default 100).

## Status: skeleton / proof-of-concept

What's working:
- `EntropyManager` — server-side tick timer, entropy/pick counter, phase alternation
- `EffectRegistry` — data-driven effect definitions (Tier 1 pool seeded, ~11 effects)
- Real networking (`OpenChoicePayload` S2C, `ChoiceMadePayload` C2S)
- Real client GUI (`ChoiceScreen`) — 3 buttons, mandatory pick, no escape-to-cancel
- `/entropytest` client command to open the GUI instantly for testing, without
  waiting on the timer or full server round-trip

What's NOT built yet (see code TODOs):
- Effect behavior execution (`EffectExecutor` — applying/removing actual game
  effects like speed boosts, mob spawns, etc. Right now picking an effect just
  logs it and broadcasts a chat message.)
- Persistence — entropy/pick count reset on server restart. Needs a
  `PersistentState` (or similar) attached to the world.
- Anti-stacking rule (max 1 active effect per category) — categories are
  defined on `EffectDefinition` but not enforced anywhere yet.
- Tiers 2-4 and the "odd/signature" effects (see design doc) — only Tier 1 is
  ported into `EffectRegistry` so far.
- World-creation config screen for interval length / entropy cap (currently
  hardcoded defaults, but the fields on `EntropyManager` are already settable).

## Building

This project was scaffolded from the official Fabric example mod template and
targets Minecraft 26.1.2 / Fabric Loader 0.19.3 / Java 25 (see
`gradle.properties`). Build/run it locally with:

```
./gradlew build       # builds the mod jar
./gradlew runClient   # launches a dev client with the mod loaded
./gradlew runServer   # launches a dev server with the mod loaded
```

This requires internet access to Fabric's Maven and the Gradle plugin portal
(the sandbox this was built in does not have that access, so none of the
above has actually been compiled or run yet — do a build locally first thing
to catch anything that needs fixing).

## Project layout

```
src/main/java/com/entropymod/
  EntropyMod.java              # main entrypoint, registers networking + tick hook
  entropy/
    EntropyManager.java        # the timer/loop/state
    EffectDefinition.java      # data record for one effect
    EffectCategory.java        # anti-stacking categories
    EffectPhase.java           # GOOD / BAD
    EffectRegistry.java        # the effect pool (add new effects here)
  network/
    OpenChoicePayload.java     # server -> client: here are your 3 choices
    ChoiceMadePayload.java     # client -> server: here's my pick

src/client/java/com/entropymod/client/
  EntropyModClient.java        # registers the payload receiver + /entropytest
  gui/ChoiceScreen.java        # the actual choice GUI
```

## Design doc

See `entropy-modpack-effects.md` (kept alongside this repo, not inside it) for
the full effect pool across all 4 tiers plus the odd/signature effect ideas
not yet ported into `EffectRegistry`.
