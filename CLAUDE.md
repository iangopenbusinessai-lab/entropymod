# CLAUDE.md — Entropy Mod

Living source of truth for this project. Read this before touching code. If you
resolve one of the open questions below, update this file in the same session —
don't let decisions live only in chat history.

## One-liner

Fabric mod for MC 26.1.2. Every interval (default 3 min), pick 1 of 3 random
Blessings, then next interval 1 of 3 random Curses, alternating forever.
Every pick = +1 Entropy. Goal: beat the Ender Dragon before Entropy hits the
cap (default 100).

---

## Part 0: Mapping migration note (read this first if anything won't compile)

This project's Loom setup uses **Mojang's official mappings**, not Yarn.
This matters because almost every Minecraft-tutorial or Stack Overflow
snippet you'll find online is written in Yarn names. As of Minecraft
1.21.11, Yarn mappings stopped being published entirely -- this project
targets 26.1.2, which is newer, so Yarn names don't even exist for it.

Confirmed correct names (verified against docs.fabricmc.net for MC 26.1.2,
not guessed) vs. the Yarn names you'll see in older guides:

| Yarn (don't use)      | Mojang (use this)              |
|------------------------|--------------------------------|
| `CustomPayload`        | `CustomPacketPayload`          |
| `CustomPayload.Id<T>`  | `CustomPacketPayload.Type<T>`  |
| `getId()` override     | `type()` override              |
| `PacketCodec`          | `StreamCodec`                  |
| `RegistryByteBuf`      | `RegistryFriendlyByteBuf`      |
| `PacketCodec.tuple`    | `StreamCodec.composite`        |
| `PayloadTypeRegistry.playS2C()` | `PayloadTypeRegistry.clientboundPlay()` |
| `PayloadTypeRegistry.playC2S()` | `PayloadTypeRegistry.serverboundPlay()` |
| `Text` / `Text.literal`| `Component` / `Component.literal` |
| `PlayerManager`        | `PlayerList`                   |
| `npc.Villager`         | `npc.villager.Villager` *(moved package)* |
| `getPlayerManager()`   | `getPlayerList()`              |
| `World`                | `Level`                        |
| `PlayerEntity`         | `Player`                       |
| `ServerPlayerEntity`   | `ServerPlayer`                 |
| `ActionResult`         | `InteractionResult`            |
| `StatusEffectInstance` | `MobEffectInstance`            |
| `ButtonWidget`         | `Button`                       |
| `.dimensions(x,y,w,h)` (button) | `.bounds(x,y,w,h)`     |
| `addDrawableChild`     | `addRenderableWidget`          |
| `DrawContext`          | *(see render pipeline note below)* |
| `TextRenderer` / `this.textRenderer` | `this.font`      |
| `ClientCommandManager` (client cmds) | `ClientCommands`         |
| `CommandManager` (server cmds)       | `Commands`               |

**One correction to the Fabric docs' own wording:** the networking doc page
says the server is accessible "via `ServerPlayerEntity.server`" as if it's a
public field. In this version's mappings it's **private**. The verified
working accessor (confirmed against two separate examples in the same docs
page) is: `((ServerLevel) player.level()).getServer()` -- `player.level()`
appears directly in the docs' server-receiver example, and the
`(ServerLevel)` cast appears in the docs' Lightning Tater example; combining
them is what actually compiles. A `serverLevel()` method (guessed before
this) does not exist on `ServerPlayer` in this mapping -- don't reuse that.


`Identifier` is correct as-is in both -- Mojang appears to have renamed
their own `ResourceLocation` to `Identifier` at some point, so this one
name is shared between old-Yarn-style code and current Mojang mappings.

**Bigger change: the screen render pipeline itself is different in this MC
version**, not just renamed. Screens no longer override
`render(DrawContext, mouseX, mouseY, delta)`. Instead they override
`extractRenderState(GuiGraphicsExtractor graphics, mouseX, mouseY, delta)`
(must call `super.extractRenderState(...)` first).

**This is now VERIFIED, not guessed** (commit `422687d`). Every GUI symbol
was checked with `javap` against the deobfuscated jar rather than inferred
from docs. If you need to check something else, this is the command:

```bash
javap -p -cp ~/.gradle/caches/fabric-loom/minecraftMaven/net/minecraft/\
minecraft-clientonly-deobf/26.1.2/minecraft-clientonly-deobf-26.1.2.jar \
  net.minecraft.client.gui.GuiGraphicsExtractor
```

Use `javap -p -c` for bytecode when argument *order* is what's in doubt.
Prefer this over the docs site -- it is the actual shipped API.

Facts that checking produced, three of which contradicted reasonable guesses:

- `GuiGraphicsExtractor` **is** in `net.minecraft.client.gui`. The old note
  called this the least-verified import in the project; it was right.
- **`mouseClicked` changed signature**: it is now
  `mouseClicked(MouseButtonEvent, boolean)`, *not*
  `(double mouseX, double mouseY, int button)`. Get coordinates from
  `event.x()` / `event.y()`. Any tutorial older than this version is wrong
  here, and the mismatch fails as "method does not override" rather than
  anything that points at the real cause.
- **`fill(x1, y1, x2, y2, argb)` is corner-to-corner, not `(x, y, w, h)`.**
  Its bytecode swaps each pair when out of order, so either winding works,
  but passing a width where a right edge belongs silently draws garbage.
- **`fill` colours need an explicit alpha byte.** `0x707070` has alpha 0 and
  draws *nothing*. Use `0xFF707070`. Note this differs from text colours,
  which `Font` promotes to opaque when the alpha bits are empty -- so
  `0xFFFFFF` works for text but is invisible as a fill. This asymmetry is a
  very easy way to lose an hour.
- Useful `GuiGraphicsExtractor` methods beyond `text`: `fill`, `fillGradient`,
  `outline`, `centeredText`, `textWithWordWrap`, `enableScissor` /
  `disableScissor`, `pose()` (a JOML `Matrix3x2fStack` with
  `pushMatrix`/`popMatrix`/`scale`/`translate` -- this is how you get
  smaller text, since Minecraft has exactly one font size).
- Custom widgets extend `AbstractWidget(x, y, w, h, Component)` and implement
  `extractWidgetRenderState(...)` + `updateWidgetNarration(...)`.
  `AbstractWidget.mouseClicked` hit-tests the full widget rect, plays the
  click sound, then calls `onClick` -- so a panel-sized widget gets
  whole-panel clicking for free. Prefer this over `Button` when the visual
  is custom, and over manual hit-testing in `Screen`.
- **`HudRenderCallback` does not exist in this Fabric API version.** Nearly
  every HUD tutorial online uses it. The current API is
  `HudElementRegistry.addLast(Identifier, HudElement)` (also `addFirst` /
  `attachElementBefore` / `attachElementAfter`, anchored on the ids in
  `VanillaHudElements`), and `HudElement` is a single method:
  `extractRenderState(GuiGraphicsExtractor, DeltaTracker)` -- the same
  graphics type the screen pipeline uses.
- **The HUD keeps rendering while a `Screen` is open.** Confirmed in
  `GameRenderer`'s bytecode: the `Gui.extractRenderState` call is gated only
  on a boolean parameter, while the `Screen` call is separately gated on
  `minecraft.screen != null`. Any HUD element that shouldn't show behind an
  open GUI must check `Minecraft.getInstance().screen == null` itself.
- `StreamCodec.composite` supports up to 12 component pairs here, so the
  old "arity limit" worry in the networking notes no longer applies.
- **`source.hasPermission(2)` does not exist.** This is the idiom in every
  server-command tutorial and it is gone in this version. `CommandSourceStack`
  has no `hasPermission` method at all; permissions now go through
  `PermissionSet` / `PermissionCheck`. The verified working form, taken from
  vanilla `GameRuleCommand`'s own bytecode, is:

  ```java
  Commands.literal("mycommand")
      .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
  ```

  The levels are `Commands.LEVEL_ALL` / `LEVEL_MODERATORS` / `LEVEL_GAMEMASTERS`
  (the old level 2) / `LEVEL_ADMINS` / `LEVEL_OWNERS`. `Commands.hasPermission`
  is a static helper returning a `PermissionProviderCheck`, which implements
  `Predicate`, which is what `.requires` wants. Note the two `hasPermission`s
  are different things — the static one on `Commands` exists, the instance one
  on the source does not.
- **There is no `Entity.getServer()`** in this mapping — the convenience
  accessor most code reaches for simply isn't there. But `ServerPlayer.level()`
  is a **covariant override returning `ServerLevel`**, so from a `ServerPlayer`
  the accessor is just `player.level().getServer()`, no cast needed. (The
  `(ServerLevel)` cast recorded above is still required when you only have a
  `Player`.)
- **`ServerEntityWorldChangeEvents` → `ServerEntityLevelChangeEvents`**, same
  World→Level rename as everything else, and the field is
  `AFTER_PLAYER_CHANGE_LEVEL` (not `AFTER_PLAYER_CHANGE`). Its functional method
  is `afterChangeLevel(ServerPlayer, ServerLevel origin, ServerLevel destination)`.
- **`DimensionDataStorage` → `SavedDataStorage`**, reached via
  `ServerLevel.getDataStorage()`. `SavedData` no longer has `save(CompoundTag)`
  / a load function; it is **codec-based** now:
  `new SavedDataType<>(Identifier, Supplier<T>, Codec<T>, DataFixTypes)` plus
  `storage.computeIfAbsent(TYPE)`. Any tutorial showing `SavedData.load`/`save`
  overrides predates this.
- **`AttributeModifier` is keyed by `Identifier`, not `UUID`.** Every older guide
  builds a `UUID.fromString("...")` constant table; that constructor is gone. The
  record is `AttributeModifier(Identifier, double, Operation)`, and
  `AttributeInstance` looks modifiers up by `Identifier` throughout
  (`getModifier`, `hasModifier`, `removeModifier`). Deriving the id from
  something stable you already have beats a hardcoded UUID table.
- **`addTransientModifier` throws `IllegalArgumentException` on a duplicate id**
  — verified by running it. Use **`addOrUpdateTransientModifier`** (or
  `addOrReplacePermanentModifier`) for anything that might be applied more than
  once; its bytecode removes the existing id first, which is what makes
  re-application idempotent. The failure mode here is a crash on respawn rather
  than a silent double-buff, which is at least loud — but it is still a crash.
- Server commands register via
  `CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> ...)`
  — **three** parameters, from `net.fabricmc.fabric.api.command.v2`. The
  client-side callback in `...client.command.v2` takes only two, so copying one
  shape into the other fails as a lambda-arity error that doesn't name the
  cause. `CommandSourceStack` is the server source type;
  `FabricClientCommandSource` is the client one.

If you hit a "cannot find symbol" error not covered by this table: check
`docs.fabricmc.net/develop/...` for the specific system (networking, GUI,
text, etc.) -- the docs site versions itself per MC release and the current
page reflects 26.1.2 specifically, which is what this project needs.

---

## Part 1: Evaluation of what's built so far

### `EntropyManager` (the timer/loop) — solid core, missing win-detection
**What it does well:** clean separation of tick-counting from pick-triggering,
phase alternation via `pickCount % 2` is simple and correct, entropy-cap check
is in the right place (start of `triggerPick`, before rolling).

**Weaknesses / gaps:**
- No detection of the actual *win condition*. Right now the only end state
  the code knows about is "entropy hit the cap" (loss). There's no listener
  for the Ender Dragon dying, so the mod currently can't tell a win from a
  loss, or stop the loop early on a win. This is arguably the single most
  important missing piece — see Open Question 1.
- `waitingOnChoice` is a single global flag. In multiplayer, one player
  sitting on an open GUI blocks the entire server's timer for everyone.
  Might be fine for solo play (which this was originally framed around) but
  worth confirming — see Open Question 2.
- ~~In-memory only~~ **RESOLVED.** `EntropyManager` is now a `SavedData` on the
  overworld's storage; entropy, pick count, acquired effects and history all
  survive a save/reload. See "Persistence — BUILT".
- ~~The loop runs unconditionally from server start~~ **RESOLVED.** `tick` is
  hard-gated on `RunState.IN_PROGRESS`; a fresh world counts nothing until the
  player clicks Start. See "Run Lifecycle" below. Note the *loss* end state
  (`gameOver`) is untouched by that work and is still separate.
- No countdown warning before a pick forces open — it just happens. Might be
  jarring; might be exactly the point (see Open Question 8).

### `EffectRegistry` / `EffectDefinition` (data model) — good bones, one real design gap
**What it does well:** effect-as-data instead of effect-as-code matches your
usual engine/UI-separation discipline (same pattern as Puzzikub's archetype
system, MelodyBrain's layered build). Adding effect #47 later is just a new
`register(...)` call, no core-loop changes needed.

**Weaknesses / gaps:**
- `minEntropy`/`maxEntropy` are hard cutoffs. Once entropy passes an effect's
  `maxEntropy`, it can never appear again. Earlier in this chat we talked
  about wanting higher entropy to still occasionally blend in lower-tier
  effects rather than being *purely* extreme — the current model can't do
  that; it needs either overlapping/open-ended ranges or a weighting system.
  See Open Question 3.
- No weighting at all within a tier — `rollThree` treats every eligible
  effect as equally likely. Fine for now with only 11 effects, but once
  Tier 2-4 + odd effects are all in the same pool, some effects (e.g. a
  "harmless funny" one vs. a "genuinely brutal" one) probably shouldn't have
  equal odds. Not urgent, flagging for later.
- Only Tier 1 (34 effects) is actually in the registry. Tiers 2-4 and all the
  odd/signature effects exist only in the design doc, not in code.
- `counterplay` flag exists on the data model but nothing reads/enforces it
  yet (e.g. "never roll 3 counterplay:false effects at once below entropy 40"
  isn't checked anywhere). `rollThree` is now the obvious place — it already
  filters by category, so a counterplay constraint slots in beside it.
- **The pool is thin enough that both fallbacks are reachable in normal play.**
  17 GOOD / 17 BAD means the no-repeat pool is empty by the 18th pick of either
  phase, and the last two picks of each phase legitimately show fewer
  than 3 cards. At the default
  entropy cap of 100 (~50 picks per phase) a run will hit this every time. This
  is a content problem, not a code problem — but it is not a rare edge case, and
  a fallback warning in the log is expected rather than a bug.
- **All 34 effects share the same 0-25 entropy range.** There is only one tier,
  so the range does nothing yet; it starts mattering when Tier 2 lands, and
  Open Question 3 (should high entropy still roll low-tier effects?) is the
  thing to settle before writing those ranges.

### Networking (`OpenChoicePayload`, `ChoiceMadePayload`) — functionally complete, unverified
**What it does well:** clean split of S2C (here are your choices) vs C2S
(here's my pick), manual codec avoids the `PacketCodec.tuple` arity limit.

**Weaknesses / gaps:**
- First version used Yarn-mapped class names (`RegistryByteBuf`,
  `PacketCodec`, `CustomPayload`) against a Mojang-mapped project, which
  doesn't compile — see "Part 0: Mapping migration note" above. Rewritten
  and verified against current Fabric docs; still genuinely uncompiled by
  an actual `javac` run, so treat as "should be right" rather than "proven
  right" until the first real `./gradlew build` succeeds.
- No handling for a player joining mid-pick (i.e., a pick is open, a new
  player joins — do they get the pending payload resent, or do they just
  miss this round entirely and see the world "paused" with no explanation)?
  See Open Question 4.
- No timeout/fallback if a client never responds (disconnect mid-pick,
  crash, etc.) — `waitingOnChoice` would stay `true` forever, freezing the
  loop. Needs at minimum a server-side timeout that auto-picks or skips.

### GUI (`ChoiceScreen`) — visual overhaul done, verified
**Read `ChoiceScreen.java`'s class javadoc before editing it** — it records
the verified-by-javap API facts that the file depends on.

**Resolved in commit `422687d`** (was: "no color theming", "buttons go
off-screen at higher GUI scale"):

- **GUI-scale responsiveness — RESOLVED.** Panel width is computed from
  `this.width`, not a fixed constant: three columns while each panel can hold
  `PANEL_MIN_WIDTH` (90), otherwise a vertical stack of three rows. Verified
  non-clipping from 2560px logical width down to 80px. Minecraft's own floor
  is 320x240 logical, which lands in the row-stack branch. This is a *hard*
  requirement, not cosmetic: `shouldCloseOnEsc()` is `false`, so anything
  that renders unusably traps the player with no way out.
- **Entropy-driven card coloring — RESOLVED.** Border + title + header rule
  interpolate in **HSL** (not RGB — RGB blending between these hue pairs goes
  muddy grey partway) across two segments split at `t = 0.8`: lightness-only,
  then a hue rotation to the endgame colour. Same colour on all three panels
  — it signals run state, not per-choice difference. Panel fill stays neutral
  grey and the header text stays pure white, both deliberately, so text
  contrast never depends on entropy.
  - **`EntropyColors` is the single source of truth for this** (commit
    `40dfb5f`). `ChoiceScreen` and the HUD both call
    `EntropyColors.colorAt(entropy, entropyCap, phase)`. Anything else that
    tints by run state should call it too rather than re-deriving the ramp —
    that is the whole point of it being its own class. The six keyframes and
    the `0.8` split live there and nowhere else.
  - **Do not "simplify" `EntropyPalette.lerpHue`.** It takes the shortest arc
    around the wheel on purpose. A naive lerp from hue 0 to 355 travels 355°
    the long way and cycles the curse ramp through the entire rainbow. This
    bug was caught twice — once in the HTML mockup, once guarded against here.
    The curse ramp should span ~80° total and never enter the green/cyan band.
  - The six `(h,s,l)` keyframes and the `0.8` split are named constants in
    `EntropyPalette`, expected to be tuned after seeing them in-game.
- **Whole-panel click targets.** Panels are a custom `AbstractWidget`, not
  `Button`, so the entire card is clickable.
- **Per-effect art is stubbed, not wired.** Each panel reserves a square image
  slot and draws a dashed placeholder via `ChoicePanel#drawEffectImage`. That
  method is the single swap-in point: when `EffectDefinition` gains an
  optional texture `Identifier`, blit it there and return early — no layout
  code changes.

**How to check it without waiting on the 3-minute timer:**
`/entropypreview`, `/entropypreview <good|bad> <entropy>`,
`/entropypreview <good|bad> <entropy> <cap>`, or append `long` to force compact
descriptions. This drives the HUD cache too, so it exercises both surfaces —
but see the warning below about what it is and isn't.
The colour/layout/description maths live in dependency-free static nested
classes (`EntropyPalette`, `PanelLayout`, `DescriptionStyle`) specifically so
a plain `java -cp build/classes/java/client` harness can exercise the real
shipped code without booting Minecraft. Do that rather than testing a copy.

**Remaining gaps:**
- Description overflow is currently hard-clipped at 2 lines (compact) or 3
  (normal). No ellipsis, no scroll, no tooltip. `DescriptionStyle
  .resolveDescriptionStyle` is the one place to change if that strategy
  should differ — it is isolated for exactly that reason.
- `isPauseScreen()` returns `true` — the world freezes while the GUI is open.
  Still a default rather than a decision. See Open Question 7.
- No entropy progress bar or "X picks until next tier" indicator; the header
  shows a bare `Entropy: n / cap`.

### Entropy cap on the wire — RESOLVED (commit `40dfb5f`)
Previously listed as a known risk: the client could not learn the server's
configured cap, so it assumed `DEFAULT_ENTROPY_CAP` and every colour would
have been wrong under a non-default cap.

`OpenChoicePayload` now carries `entropyCap` as a real record component with
a matching codec component, and `EntropyManager` sends its configured cap.
Verified by an encode/decode round-trip, not by inspection.

**If you add a field to a payload record, add its codec line in the same
edit.** For `StreamCodec.composite` the arity is type-checked against the
constructor reference, so a *missing* line fails to compile — but a line
pointing at the *wrong getter* compiles fine and silently sends the wrong
value. Round-trip it with each field set to a distinct value.

### Persistent entropy HUD (`EntropyHud`) — new in `40dfb5f`
Top-right `Entropy: N/cap` readout, tinted via `EntropyColors`, registered
with `HudElementRegistry.addLast`. Neutral grey until the first payload
arrives rather than guessing a phase; cache clears on disconnect.

Two things to know before changing it:
- The `screen == null` guard is **required** — see the mapping note above;
  the HUD really does render underneath open screens.
- **The client only hears from the server when a pick opens.** There is no
  entropy sync packet, so the HUD is a cache, not live state.
  `noteChoiceSubmitted()` bumps the count locally on submit to avoid a
  whole-interval stale readout, and the next payload re-syncs. If entropy
  ever changes server-side for any reason *other* than a pick, the HUD will
  be wrong until the next pick. A small periodic sync payload is the real
  fix if that becomes true.
- **Known overlap: vanilla draws status-effect icons in the top-right too.**
  With active potion effects the readout and the icons will collide. Nudging
  `y` down when `player.getActiveEffects()` is non-empty is the obvious fix;
  it was left alone because the top-right position was specified.

### Mixins — fourteen real ones now, plus the original placeholders
`EntropyModMixin` / `EntropyModClientMixin` are still unused example code.
**Fourteen real mixins now exist**: three for the original hook-driven effects,
eight from the mixin cluster, and three from the crop/event/meta session
(`ServerPlayerJumpMixin`, `ItemStackDurabilityMixin`, `VillagerPricesMixin`).
`CropGrowthMixin` was the fifteenth and has been **deleted** — see "Blight
Touched: the trample rewrite" below. See "The original three mixins" and "The mixin
cluster" under the Tier 1 content batch below — the latter carries the
javap-verified target table, which is the part worth reading before adding
another. The pattern is proven; later signature effects (Mirror World's inverted
input, Fisheye's forced FOV, Doppelganger's custom entity) can follow the same
shape.

### Effect execution — BUILT, and now with real content
Picking an effect routes through a per-effect class in
`com.entropymod.entropy.behavior`. **As of the permanent-effects session these
are no longer stubs** — all 20 Tier 1 effects change real game state.

**Adding a new effect is exactly three things, and nothing else:**

1. one `register(...)` line in `EffectRegistry`
2. one new class in `com.entropymod.entropy.behavior`
3. one `register(...)` line in `EffectBehaviors`

Most new effects should extend `AttributeEffectBehavior` and be ~10 lines. If a
new effect makes you edit a fourth file, that's a signal the abstraction is
missing something; fix the abstraction rather than the caller.

- `EffectBehavior` — `apply(EffectContext)` / `remove(EffectContext)`. Per
  Open Question 9 this is option (b), one class per effect, not a big switch.
- `EffectContext` — wraps the server, the `EffectDefinition`, **a specific
  target `ServerPlayer`**, and a `Reason` (`PICKED` vs `REAPPLIED`). The target
  is not optional: re-application happens one player at a time, and the old
  "first player in the list" shortcut would have buffed the wrong player the
  moment a second one existed.
- `EffectBehaviors` — id → behavior map. Ids are matched to `EffectRegistry` by
  **string**, which the compiler cannot check, so each behavior exposes an `ID`
  constant that both files use, and `validate()` reports both directions of
  mismatch at init.

**`apply` MUST be idempotent.** This is the most important rule in the codebase
now and the easiest to break. `apply` runs once on the pick and then *again on
every respawn, rejoin and dimension change* — an unbounded number of times.
`AttributeEffectBehavior` gets this right via `addOrUpdateTransientModifier`;
see the Part 0 note on why `addTransientModifier` is the trap.

### Permanent effects — the architecture shift, and why
**Every effect is permanent. `EffectDefinition` has no duration field at all.**

It used to have `durationTicks`, an int whose value encoded three different
lifetimes: `0` = fire once and never track, `-1` = until the next interval,
`>0` = a tick countdown. That was replaced rather than extended, because an
overloaded magic number cannot tell you which of its meanings is in play at the
point you read it, and every consumer had to branch on all three.

**If temporary effects ever return, they must not return as an int.** Add a
`sealed interface EffectDuration` with `Permanent` and `Ticks(int)` cases so the
compiler forces every call site to handle both. This is written into
`EffectDefinition`'s javadoc as well.

What this deleted, rather than left unreachable:
- `ActiveEffect` — the entire class. Its only job was holding `remainingTicks`
  and the interval-scoped flag.
- `ActiveEffectTracker.tickAndCollectExpired()` and `expireIntervalScoped()`.
- The expiry pass in `EntropyManager.tick()`, which is back to just counting.
- `EffectContext.announceApply()/announceRemove()` — the stub-era scaffolding.
  The pick announcement moved to `EntropyManager`, because it is a statement
  about the *run* ("you chose this"), and behaviors now also run on every
  respawn where announcing would be pure spam.

`ActiveEffectTracker` became **`AcquiredEffects`** — renamed because "tracker"
described countdown machinery that no longer exists. It is an insertion-ordered
`LinkedHashSet<String>` of effect ids and nothing more.

**The important consequence: three concerns collapsed into one set.** With
permanence, "currently active", "already picked", and "which categories are
occupied" are the same fact. They were three separate things under the old
model and are now one, which means they cannot drift apart. Do not reintroduce a
second set for any of them.

### Persistence — BUILT (was: "not built")
`EntropyManager` **is** a `SavedData`, stored on the overworld's
`SavedDataStorage`. This replaced the in-memory
`WeakHashMap<MinecraftServer, EntropyManager>`.

This became mandatory, not nice-to-have, the moment effects became permanent: in
singleplayer, quitting to the title screen stops the integrated server, so
without it every acquired effect and the entire run vanished on a trip through
the main menu. "Effects survive logout/login" cannot be delivered by
re-application alone if the list of what to re-apply is gone.

- Persisted: entropy, pick count, game-over, cap, interval, the acquired-effect
  ids, and the full pick history.
- **Not** persisted, deliberately: `tickCounter` (a saved partial countdown
  would let a player reload repeatedly to sit just below the threshold forever;
  losing at most one interval on load is the better failure) and
  `waitingOnChoice` (a pending choice cannot outlive the client answering it).
- **Every mutation must call `setDirty()`** or it is not written. That is the
  one easy-to-miss rule in the file.
- **Every codec field is `optionalFieldOf` with a default.** That is what lets a
  save from an older build load in a newer one. Adding a *required* field would
  make every existing save unloadable — don't.
- An effect id in a save that this build no longer defines is skipped with a
  warning, not a load failure.

### Re-application across death, relog and dimensions — BUILT
**Verified, not assumed:** vanilla's `ServerPlayer.restoreFrom` calls
`AttributeMap.assignPermanentModifiers` only inside an `ifeq` on its boolean
parameter, and `PlayerList.respawn` passes that parameter straight through —
`false` for a death. A death respawn also constructs a brand-new `ServerPlayer`.
So **attribute modifiers are dropped on death respawn.**

The fix is three hooks in `EntropyMod`, all calling `reapplyAll`:
`ServerPlayerEvents.JOIN`, `ServerPlayerEvents.AFTER_RESPAWN`, and
`ServerEntityLevelChangeEvents.AFTER_PLAYER_CHANGE_LEVEL` (an ordinary portal
moves the same entity and does *not* go through respawn, so without the third
hook a trip to the Nether would quietly drop everything).

**These hooks are not a safety net — they are the only thing that puts effects
back.** Modifiers are applied *transiently*, deliberately:
`addOrReplacePermanentModifier` would write them into the player's own NBT,
making the save file a second source of truth that could disagree with
`AcquiredEffects` — including leaving orphaned buffs behind if the mod were
removed. Transient means the acquired set is the **only** truth and attributes
are derived state, rebuilt from it every time. The cost is that re-application
is mandatory; that is a good trade for an invariant that cannot drift.

### No-repeat — BUILT, and distinct from anti-stacking
These are two different rules and are easy to confuse. They are applied in
priority order in `EffectRegistry.roll`:

| | No-repeat | Anti-stacking |
|---|---|---|
| Keyed on | effect **id** | effect **category** |
| Scope | the whole run, permanently | while a category is occupied |
| Status | **hard rule**, applied first | **soft rule**, dropped first |

**No-repeat:** once an effect is picked it never appears again for the rest of
the run. Under permanence, being re-offered something you already have is
meaningless — there is nothing to refresh.

**Priority matters and is deliberate.** If both filters together empty the pool,
anti-stacking is abandoned *first* and no-repeat is kept: a second MOVEMENT
effect is a real playable outcome, a duplicate of an effect you already own is
not. Only if no-repeat *alone* empties the pool is it dropped, flagged via
`RollResult.repeatFallback()`.

**The repeat fallback is genuinely reachable today**, not theoretical: with 17
GOOD and 17 BAD effects it fires on the 18th pick of either phase — asserted
exactly, at that pick number, by the headless harness. It is a
tested path, and it is still PLACEHOLDER policy — Open Question 6 remains open,
and more content is the real fix.

A partial result (1 or 2 cards) is not a fallback and is not flagged. It is the
honest answer when the pool is that small, and it is the normal experience for
the last two picks of a phase.

### The Tier 1 content batch — 34 effects, first real content
Originally ten GOOD and ten BAD, all permanent, all entropy 0-25; this replaced
the original 11 placeholder stubs. **It is a baseline, not the finished game** —
Tiers 2-4 and the odd/signature effects still slot in the same way.

**The movement/physics batch added three more** (`moon_walker`, `leaden_legs`,
`stone_feet`) and **the mixin cluster added five** (`magnetic_boots`,
`frost_walker_innate`, `iron_will`, `beast_whisperer`, `exposed`),
and the crop/event/meta session added six more (`green_thumb`, `second_guess`
GOOD; `blight_touched`, `leaky_pockets`, `clumsy_digger`, `bad_reputation` BAD),
bringing the registry to **34: 17 GOOD, 17 BAD** — symmetric again. See "Movement
and physics attributes", "The mixin cluster" and the crop/villager/Second Guess
sections below for the verification behind them.

**`heavy_footsteps` was renamed to `exposed`** once its real mechanic was
understood (it cancels sneaking rather than extending mob range). The id changed,
so a save from before the rename carries an effect id this build no longer
defines — that is handled the same way as any other unknown id: skipped with a
warning at load, rest of the run intact.

Eighteen are pure `AttributeEffectBehavior` subclasses (including
`magnetic_boots`, via a mod-registered attribute). Nine need a mixin, because no
vanilla attribute covers them: hunger rate, incoming damage, XP gain, water
freezing, projectile knockback, footstep volume, mob detection range, and animal
fleeing. Two more — `green_thumb` and `blight_touched` — need **neither**: both
are tick services driven from `ServerTickEvents.END_SERVER_TICK`, each having
started life on a mixin and outgrown it. That is now an established third shape
alongside attribute and mixin, and it is the right one whenever the effect is a
schedule or a per-player world query rather than a value vanilla already computes.

**Two effects were originally specced as "custom hook" and are attributes
instead**, which is strictly better and worth knowing before someone "fixes" it:

- **Featherlight / Glass Jaw** use `FALL_DAMAGE_MULTIPLIER` (base 1.0), not a
  damage event.
- **Efficient Miner / Dull Blade** use `BLOCK_BREAK_SPEED` (base 1.0), not a
  block-break-speed hook. This one matters for correctness, not just tidiness:
  the attribute is client-syncable, so the block-cracking animation matches the
  server. A server-only mixin would have desynced the visual from the real
  break time.

Five effects from the old stub set were **dropped**, not ported, because they
don't fit the permanent model or need work beyond this session: `field_repair`
(was duration-0, one-shot — has no home now), `prospectors_eye` (needs a
client-side block-glow renderer), `foggy_head` (FOV is client-side), `night_owl`
(fine as a permanent effect, just not in the specced 20), and `butterfingers`.
All are reasonable to re-add later as proper permanent effects.

#### Attribute clamps — javap-verified, do not re-derive from memory
These were checked by running the real `RangedAttribute` instances, not read
from a wiki. The floors are the part that matters.

| Attribute | default | min | max |
|---|---|---|---|
| `MAX_HEALTH` | 20.0 | **1.0** | 1024.0 |
| `ATTACK_SPEED` | 4.0 | **0.0** | 1024.0 |
| `ATTACK_DAMAGE` | 2.0 | 0.0 | 2048.0 |
| `MOVEMENT_SPEED` | 0.7 | 0.0 | 1024.0 |
| `LUCK` | 0.0 | **-1024.0** | 1024.0 |
| `FALL_DAMAGE_MULTIPLIER` | 1.0 | 0.0 | 100.0 |
| `BLOCK_BREAK_SPEED` | 1.0 | 0.0 | 1024.0 |
| `GRAVITY` | 0.08 | **-1.0** | **1.0** |
| `JUMP_STRENGTH` | 0.41999998688697815 | **0.0** | 32.0 |
| `ENTITY_INTERACTION_RANGE` | 3.0 | 0.0 | 64.0 |
| `BLOCK_INTERACTION_RANGE` | 4.5 | 0.0 | 64.0 |
| `SAFE_FALL_DISTANCE` | 3.0 | -1024.0 | 1024.0 |

- **`MAX_HEALTH` floors at 1.0, not 0.** `sanitizeValue(-5)` and
  `sanitizeValue(0)` both return `1.0`. Max health can never be driven to zero
  by stacked penalties — the worst case is half a heart. This is why
  `brittle_bones` is safely `counterplay = true`.
- **Lowering max health does NOT lower current health automatically.** A player
  at 20/20 becomes 20/16 and displays more hearts than they have until something
  calls `setHealth`. `BrittleBonesBehavior.afterApply` clamps it explicitly.
  Any future max-health penalty must do the same.
- **`ATTACK_SPEED` floors at 0.0, and 0 is unrecoverable** — the attack cooldown
  would never refill. `-20%` of base lands at 3.2, nowhere near it, and
  no-repeat prevents stacking. Check any future attack-speed penalty against
  this floor.
- **`ATTACK_DAMAGE` floors at 0.0 and the player's base is only 1.0**, so
  `weak_grip` (-2) reduces *bare-handed* attacks to 0 damage. Weapons add their
  own modifier and stay positive. That is an intended consequence with obvious
  counterplay (carry a weapon), not an oversight.
- **`LUCK` is the one attribute with real negative headroom** (min -1024), so
  `unlucky` needs no floor handling at all.
- `ATTACK_DAMAGE` is **not client-syncable** — the client tooltip won't reflect
  `steady_hands` / `weak_grip` even though the server applies them.

#### Movement and physics attributes — verified, read this before guessing again
The movement batch (`moon_walker`, `leaden_legs`, `stone_feet`) had to establish
whether gravity, jump and item-pickup range are attribute-driven **for players**
in 26.1.2. They were checked in bytecode and against the live registry, not
assumed — and the three answers are not the same answer, which is the point of
recording them.

- **`GRAVITY` is real and player-applicable.** Granted by
  `LivingEntity.createLivingAttributes()` (so every living entity has it, players
  included), and it is genuinely the value physics uses:
  `LivingEntity.getDefaultGravity()` is literally
  `getAttributeValue(Attributes.GRAVITY)`, and `Entity.applyGravity()` subtracts
  that from vertical motion every airborne tick. No motion mixin needed.
- **`JUMP_STRENGTH` is real and player-applicable — the horse association does
  NOT hold here.** `LivingEntity.getJumpPower(float)` is
  `getAttributeValue(JUMP_STRENGTH) * scale * getBlockJumpFactor() +
  getJumpBoostPower()`, and **neither `Player`, `Avatar` nor `ServerPlayer`
  overrides `getJumpPower`** — `ServerPlayer.jumpFromGround` calls `super` and
  only adds the jump stat and food exhaustion. This was the finding most likely
  to have gone the other way; it did not.
- **There is NO item-pickup-range attribute, and `INTERACTION_RANGE` is not it.**
  `ENTITY_INTERACTION_RANGE` / `BLOCK_INTERACTION_RANGE` feed exactly two
  methods, `Player.entityInteractionRange()` and `Player.blockInteractionRange()`
  — attack and use *reach*. Item pickup is a **separate, hardcoded** mechanic:
  `Player.aiStep()` collects with `getBoundingBox().inflate(1.0, 0.5, 1.0)`,
  literal constants in the bytecode, and `ItemEntity.playerTouch` reads no
  attribute and no range at all. Experience orbs come out of the same inflated
  box. **Do not reach for `INTERACTION_RANGE` to implement a magnet effect — it
  will change reach and not pickup.**

**`GRAVITY`'s floor is dangerous in a way `MAX_HEALTH`'s is not.** It clamps to
`[-1.0, 1.0]`, so unlike max health — whose 1.0 floor makes over-stacking
harmlessly bottom out — driving gravity to `0` leaves the player floating with no
way down, and **negative gravity is legal** and launches them upward permanently.
`JUMP_STRENGTH` floors at `0.0`, i.e. cannot jump at all. Neither is reachable
today because no-repeat prevents a second gravity or jump effect, but any future
tier that adds one **must** be checked against these floors rather than assumed
safe by analogy with the health case.

**Jump apex is not linear in jump strength — it goes as the square of velocity.**
Simulating the real per-tick integration (apply gravity, integrate, apply the
0.98 air drag) against vanilla's 0.42 / 0.08 reproduces the known 1.2522-block
jump, which is what validates the model. Against that:

| effect | apex | clears a 1-block ledge? |
|---|---|---|
| vanilla | 1.2522 | yes |
| Moon Walker, -30% gravity | 1.6573 | yes (clears 1.5) |
| Leaden Legs, +40% gravity | 0.9804 | **no — 2% short** |
| Stone Feet, -40% jump | 0.5140 | **no** |

**Both BAD effects currently block 1-block jumps, and the thresholds are cliffs,
not gradients.** For gravity the break point is between +35% (1.0037, clears) and
+40% (0.9804, does not). For jump strength it is between -10% (1.0474, clears)
and -15% (0.9465, does not) — because of the squaring, jump strength is far more
sensitive than it looks. `STEP_HEIGHT` is untouched, so slabs and stairs still
auto-step and the world stays traversable via ramps; that is why both stay
`counterplay = true`. Ship values are the specced -30/+40/-40. If they read as
broken rather than heavy in play, +30% gravity and -10% jump are the tunings that
keep the effect while preserving normal traversal.

**Leaden Legs and Stone Feet are genuinely different mechanics**, despite both
shortening a jump — worth stating because the obvious "simplification" is to
merge them. Gravity applies every tick in *both* directions, so Leaden Legs also
makes the player fall faster, hit terminal velocity sooner and accumulate fall
distance faster, interacting with fall damage and water landings. Stone Feet
changes only the initial upward impulse and leaves falling byte-identical to
vanilla. Confirmed in bytecode: `JUMP_STRENGTH` is read once, in
`getJumpPower(float)`; `GRAVITY` is read in `getDefaultGravity()` and consumed by
`applyGravity()` on every airborne tick.

#### Magnetic Boots — was deferred here, now SHIPPED in the mixin cluster
`magnetic_boots` was correctly deferred out of the movement/physics session
because item pickup range is hardcoded in `Player.aiStep()` and no attribute
governs it, which made it a mixin task rather than an attribute task. **The mixin
cluster session built it**, exactly along the lines predicted: a custom
registered attribute (`entropymod:pickup_range`) read by a `Player.aiStep`
redirect. It is now a plain `AttributeEffectBehavior`. Shipped at 1.5x, retuned to **2.0x**
after in-game testing found 1.5x imperceptible (pickup reach 1.3 -> 2.3 blocks
from player centre). See "The mixin cluster" below for the target details.

#### The original three mixins
`EffectHooks` is the single door between mixins and run state. Mixins are the
most fragile code here, so each is one line: they compute nothing and decide
nothing, they just multiply by what `EffectHooks` returns.

- `PlayerExhaustionMixin` → `Player.causeFoodExhaustion`. **Targets `Player`,
  not `FoodData`**: `FoodData.addExhaustion(float)` holds no reference to the
  player it belongs to, so a mixin there could not tell whose hunger it was.
- `LivingEntityDamageMixin` → `LivingEntity.hurtServer`, HEAD, so the multiplier
  lands *before* armour and enchantments and composes with them multiplicatively.
  The `instanceof Player` guard is load-bearing: `hurtServer` runs for every mob.
- `PlayerExperienceMixin` → `Player.giveExperiencePoints`. Uses
  `EffectHooks.scaleAmount`, which **rounds rather than truncates** — `(int)(1 *
  1.5f)` is 1, so Fast Learner would do nothing at all for the very common 1-XP
  orb.

`EffectHooks` never throws and never returns null; every path falls back to
`1.0f`. An exception escaping a mixin surfaces as a crash blaming vanilla rather
than this mod. It also returns `1.0f` client-side, where the acquired set does
not exist — the `instanceof ServerLevel` check is what makes the mixins safe on
both sides.

### The mixin cluster — 5 effects, 8 more mixins, all targets javap-verified
This session added `magnetic_boots`, `frost_walker_innate`, `iron_will`,
`beast_whisperer` (GOOD) and `exposed` (BAD). **All five shipped**; two
had their implementation route changed by what the investigation found, and those
changes are the valuable part of this record.

Mixin target names are far less guessable than attribute names, and three of the
findings below contradict what the obvious approach would have been. Read this
before writing another mixin.

#### Verified mixin targets

| Mixin | Target | Kind |
|---|---|---|
| `PlayerAttributesMixin` | `Player.createAttributes()` | `@Inject` RETURN |
| `PlayerItemPickupMixin` | `Player.aiStep()` → `AABB.inflate(DDD)` | `@Redirect` |
| `LivingEntityFrostWalkerMixin` | `LivingEntity.onChangedBlock(ServerLevel, BlockPos)` | `@Inject` TAIL |
| `LivingEntityKnockbackMixin` | `LivingEntity.hurtServer` → `knockback(DDD)V` | `@Redirect` |
| `EntityStepSoundMixin` | `Entity.playStepSound` → `playSound(SoundEvent,F,F)` | `@Redirect` |
| `LivingEntityVisibilityMixin` | `LivingEntity.getVisibilityPercent(Entity)` | `@Inject` RETURN |
| `PanicGoalMixin` | `PanicGoal.shouldPanic()` | `@Inject` RETURN |
| `AvoidEntityGoalMixin` | `AvoidEntityGoal.canUse()` | `@Inject` RETURN |
| `ServerPlayerJumpMixin` | `ServerPlayer.jumpFromGround()` | `@Inject` TAIL |
| `ItemStackDurabilityMixin` | `ItemStack.hurtAndBreak(I,ServerLevel,ServerPlayer,Consumer)` | `@ModifyVariable` |
| `VillagerPricesMixin` | `Villager.updateSpecialPrices(Player)` *(private)* | `@Inject` TAIL |

**Compiling proves nothing about a mixin.** Injection targets resolve at runtime,
so a wrong name or descriptor builds green and fails when the game loads. Verify
targets against the jar instead — for a `@Redirect`, confirm the exact
`owner.name:descriptor` string appears *inside the enclosing method*:

```bash
javap -p -c -cp <deobf jar> net.minecraft.world.entity.LivingEntity \
  | awk '/^  [a-zA-Z<@].*\(.*\);$/ { inm = (index($0,"hurtServer(")>0) } inm' \
  | grep -F "Method knockback:(DDD)V"
```

#### Frost Walker is DATA, not Java — the biggest surprise here
**`FrostWalkerEnchantment` does not exist.** The
`onEntityMoved(LivingEntity, Level, BlockPos, int)` that every older guide hooks
is gone. Frost Walker is a **data-driven enchantment**:
`data/minecraft/enchantment/frost_walker.json` declares a
`minecraft:location_changed` effect of type `minecraft:replace_disk` (radius 3 at
level 1, +1/level, clamped 16; 1 block high at offset `(0,-1,0)`; replaces water
with `frosted_ice[age=0]` where the block above is air and the spot is
unobstructed; fires a `block_place` game event; requires on-ground and not
riding).

So there is no Java method to hook and nothing to copy. `LivingEntityFrostWalkerMixin`
instead resolves **vanilla's own enchantment** out of the registry and calls
`Enchantment.runLocationChangedEffects(...)` on it, which evaluates the real
conditions and applies the real effect. A datapack that retunes Frost Walker
retunes this effect too, for free.

- The trigger is `LivingEntity.onChangedBlock(ServerLevel, BlockPos)` — that is
  the method which calls `EnchantmentHelper.runLocationChangedEffects`, i.e.
  vanilla's own schedule for this.
- `runLocationChangedEffects` needs an `EnchantedItemInUse`, but **`ReplaceDisk.apply`
  never reads that parameter** — verified in its bytecode, which never loads the
  slot. `ItemStack.EMPTY` in the feet slot is therefore a faithful stand-in for
  "no boots", which is the whole point of the effect.
- **The general lesson:** when a vanilla mechanic seems to have lost its class,
  check `data/minecraft/...` in the jar before concluding it was removed. Several
  systems moved from code to data in this era.

#### General mobs cannot hear — Heavy Footsteps' scope was redirected
Part (b) of Heavy Footsteps ("mobs notice you from farther away") was specced as
sound-driven. **It cannot be**, and this was the brief's stop condition firing:

- **`Sensing`, the mob perception class, has exactly one query:
  `hasLineOfSight(Entity)`.** There is no hearing, no noise radius, and nothing in
  ordinary hostile AI that reacts to footsteps.
- The only sound-driven perception in the game is the vibration/sculk system
  (Warden, sculk sensors), which reacts to game events. Wiring footsteps to that
  would have covered *one mob*, not "mobs" — a fake version of the effect.

What *does* exist is a general **detectability multiplier**.
`TargetingConditions.test` — the shared gate behind essentially every hostile
targeting goal — computes effective range as
`target.getVisibilityPercent(attacker) * range`, floored at 2. And
`LivingEntity.getVisibilityPercent` is **the same lever vanilla uses for
sneaking**: crouching returns `0.8`, i.e. mobs notice you from 20% closer.

So part (b) ships through that value, which is general across mob AI and composes
with sneaking and armour invisibility instead of overriding them. **It is
honestly not sound propagation.**

**Then in-game testing corrected it a second time, and this is the durable
finding:** a zombie (follow range 35) still acquired the player at 35 blocks with
the multiplier at 1.35, when 47.25 was expected. The cause is an asymmetry in
vanilla:

| step | method | uses visibility? |
|---|---|---|
| acquisition | `TargetingConditions.test` | **yes** — `max(followDistance * visibilityPercent, 2.0)` |
| retention | `TargetGoal.canContinueToUse` | **no** — `distanceToSqr > followDistance²`, raw |

A target acquired beyond the raw follow distance is dropped again immediately, so
**`getVisibilityPercent` can only ever REDUCE effective detection range, never
extend it.** That is exactly why vanilla only ever uses values ≤ 1.0 (sneaking's
0.8, invisibility's armour fraction). Anything above 1.0 is silently discarded.

Consequences, now reflected in the code:

- The multiplier is **1.25**, the exact inverse of sneaking's 0.8, so
  `0.8 × 1.25 = 1.0`: the curse *cancels the sneaking discount* rather than
  punishing the attempt. It is also the **saturation point** — standing behaviour
  is identical for every value ≥ 1.0 (the retention cap), and sneaking behaviour
  is identical for every value ≥ 1.25. Values above it do nothing at all.
- The description was factually wrong and is now "sneaking no longer hides you
  from mobs" rather than "mobs notice you from farther away".
- **Correction to a claim made here earlier: footstep volume does NOT widen the
  audible radius.** `SoundEvent.getRange(volume)` is
  `volume > 1.0f ? 16.0f * volume : 16.0f`, and footsteps are ~0.15 (0.375 at the
  2.5x multiplier) — far below the threshold. Louder, same 16-block radius.
  Widening it would need >6.7x. Note also `Player` overrides `playStepSound` and
  only falls through to `Entity`'s implementation on ordinary ground.

#### "Fleeing" is exactly two mechanisms, both generic — Beast Whisperer
The scope worry was per-species AI patching. It is not needed:

- **`PanicGoal.shouldPanic()`** is
  `mob.getLastDamageSource() != null && ...is(<tag>)`. Keyed on the damage
  *source*, so the attacker is reachable via `getEntity()` and the suppression can
  be scoped to one player. Every panicking mob inherits this one class.
  **Target `shouldPanic`, not `canUse`** — `canUse()` is
  `shouldPanic() || mob.isOnFire() || ...`, so cancelling there would also stop a
  burning animal running for water.
- **`AvoidEntityGoal`** is one generic class parameterised by the type to avoid,
  not a per-species reimplementation. `canUse()` resolves the nearest match into
  the protected `toAvoid` field, so a single mixin covers fox, ocelot and anything
  added later. Gate on the resolved target and rabbit-flees-wolf stays untouched.
  Inject at RETURN, not HEAD — `toAvoid` is only populated during `canUse`.

#### Iron Will: why the call site, and why explosions were never at risk
`LivingEntity.knockback(double, double, double)` **receives no `DamageSource`**,
so a mixin on that method could only ever be blanket knockback immunity.
Redirecting the call *inside* `hurtServer` puts the source in scope — a redirect
handler may capture the enclosing method's parameters after its own.

The three-way split was verified against the shipped tags, not assumed:

- `is_projectile` = arrow, trident, mob_projectile, unattributed_fireball,
  fireball, wither_skull, thrown, wind_charge.
- **`no_knockback` already contains `explosion` and `player_explosion`**, and the
  redirected call sits inside the branch that tag excludes. Explosion shove is
  applied by the explosion itself and never passes through here — so explosions
  are safe *by construction*, not merely by the gate.
- Melee (`player_attack` / `mob_attack`) is in neither tag, so it reaches the
  redirect and passes straight through.

**Known limit, deliberately not hidden:** Punch-enchantment knockback is applied
by a separate call and is *not* suppressed. A Punch bow still moves the player.

#### `@Shadow` does NOT resolve inherited fields — the crash-on-launch trap
**This is a different failure mode from the subclass-override trap below, and it
fails in the opposite direction: it does not silently do nothing, it hard-crashes
the game on every world launch.** It compiled green and shipped.

```
InvalidMixinException: @Shadow field keyPresses was not located in the target
class net.minecraft.client.player.KeyboardInput. No refMap loaded.
```

**The real hierarchy, javap-verified:**

| Class | Declares |
|---|---|
| `ClientInput` | `public Input keyPresses`, `protected Vec2 moveVector`, `tick()`, `getMoveVector()`, `hasForwardImpulse()`, `makeJump()` |
| `KeyboardInput extends ClientInput` | `private final Options options`, `calculateImpulse(ZZ)F`, `tick()` — **and no fields the mixin wanted** |

So **both** shadowed fields were wrong, not just the one named in the crash;
the exception simply reports the first failure and aborts. An earlier note in
this file said `moveVector` lives on `ClientInput` "specifically", implying
`keyPresses` did not — **that was wrong, they are both on `ClientInput`.**

**The rule, read out of Mixin's own source rather than inferred from the message:
`@Shadow` on a *field* resolves only against fields declared directly in the
target class, and never walks the superclass chain.**
`MixinPreProcessorStandard.attachFields` → `MixinTargetContext.findField` →
`TargetClassContext.findAliasedField`, whose entire search is
`for (FieldNode target : this.classNode.fields)` over the target's own declared
fields. There is no traversal parameter and no inherited case. (`@Shadow` on
*methods* is not the same — don't generalise this to methods.)

**The fix: declare the mixin as extending the target's actual superclass.**

```java
@Mixin(KeyboardInput.class)
public abstract class KeyboardInputMixin extends ClientInput {   // no @Shadow at all
```

Mixin explicitly supports this. `MixinInfo.Standard.validate` takes an early
`continue` when the mixin's `superName` equals the target's superName — the
fully-attached case, not even flagged "detached". The fields then work by plain
Java inheritance with no annotation involved, which **also solves the
cross-package `protected` problem** that made `moveVector` awkward in the first
place: a subclass may touch a protected member through its own `this` from any
package, whereas a cast to the target type cannot.

Verified end to end in bytecode rather than assumed: javac emits
`PUTFIELD KeyboardInputMixin.moveVector` (its own class as owner — the normal
encoding for inherited field access), and `MixinTargetContext.transformFieldRef`
rewrites exactly that case to the target, yielding
`PUTFIELD KeyboardInput.moveVector` in the merged method. JVM resolution walks up
to `ClientInput`, and the access is legal because the merged code lives in
`KeyboardInput` — same package as the protected field.

**Why the two working `@Shadow` fields in this project were never affected:**
`AvoidEntityGoal.toAvoid` and `PanicGoal.mob` are both declared **directly on
their mixin's target class**. That is the only configuration that works. Before
adding a `@Shadow` field, confirm the declaring class with `javap` and target
that class, or extend it.

**"No refMap loaded" is benign here and is not a second problem.** It is only a
status string appended to the message by `context.getReferenceMapper().getStatus()`.
`CameraMixin` applied successfully at `14:36:27` in the very same launch that
crashed on `KeyboardInputMixin` at `14:36:43`, under identical refmap conditions —
which proves the refmap situation is not broken. A Loom dev run uses named
mappings at runtime, so mixin names match source directly and no refmap is
needed. **Do not chase it.**

#### When a mixin applies cleanly and STILL changes nothing — read this first
The mixin cluster shipped with javap-verified targets and 116 green headless
checks, and two of its five effects did not work in game. Neither was a broken
injection. **"The mixin applied" and "the behaviour changed" are separate
claims, and the project has now been burned by the gap between them.** Three
distinct failure modes, all real, none visible to the build:

1. **A subclass overrides the method you targeted.** Targeting
   `LivingEntity.foo` does nothing for players if `Player` overrides `foo` and
   never calls `super`. Both of these exist here and were missed on the first
   pass: **`Player` overrides `playStepSound`** and **`ServerPlayer` overrides
   `onChangedBlock`**. Both happen to call `super` on the common path, so those
   two mixins survived — that was luck, not design. Before targeting anything on
   `Entity`/`LivingEntity`, check the whole chain and whether the override
   delegates:

   ```bash
   for c in ...player.Player ...entity.Avatar ...level.ServerPlayer; do
     javap -p -cp <jar> $c | grep -i '<methodName>'; done
   ```

2. **Something downstream re-clamps the value you changed.** The Heavy Footsteps
   case above: `getVisibilityPercent` is genuinely multiplied, and the result is
   genuinely thrown away by a second distance check that does not consult it.
   Modifying a value is not the same as changing the outcome — **trace every
   consumer of the value, not just the one you found.**

3. **The change is real but below the perceptual threshold.** Magnetic Boots at
   1.5x moved pickup reach from 1.3 to 1.8 blocks and read as "may work". Not a
   bug; the wrong magnitude, and indistinguishable from a bug from the player's
   seat.

**The diagnostic that actually resolved all of this was `run/logs/latest.log`,
which is in the repo.** It is the single highest-value evidence source in the
project and it beats reasoning from the source every time — it proved the
attribute registered, the effects applied with no error, all 28 behaviors wired,
and the entire history round-trip working. **Read it before forming a
hypothesis.** Useful greps:

```bash
grep -iE "entropymod|Entropy" run/logs/latest.log | tail -60
grep -iE "mixin|inject|redirect|does not have|Registered custom attribute" run/logs/latest.log
```

Corollary for future effects: **an effect is not done when the mixin applies.**
It is done when a value has been observed to change in game. Prefer effects whose
result is directly measurable, and when a magnitude is chosen, sanity-check what
it means in blocks/hearts/seconds before shipping it.

#### Blight Touched: the trample rewrite, and the death of the shared crop hook
**The `getGrowthSpeed` hook, its mixin and its multiplier are all gone.** Green
Thumb left that hook first; Blight Touched was the last effect on it, and rather
than leave a hook returning 1.0 for nobody, `CropGrowthMixin`,
`EffectHooks.cropGrowthMultiplier`, `cropGrowthMultiplierFor` and
`MIN_CROP_MULTIPLIER` were **deleted**. Same discipline as Green Thumb's
retirement: a neutral code path can be quietly re-wired by a later session that
doesn't know why it was emptied; code that doesn't exist cannot. The harness
asserts all four absences by reflection.

The knowledge that hook produced is still worth having and is preserved below
under "Crop growth timing" — it still describes vanilla, it is what proved Green
Thumb needed a different mechanism, and it is what proved Blight Touched needed
one too. **What it no longer describes is any shipped effect.**

**Why it was replaced: the old version could not be felt.** Halving growth speed
is a shift in a probability the player never observes, over tens of minutes,
against a baseline they have no reading of — failure mode 3 from the mixin-cluster
list above, "real, but below the perceptual threshold". This is the second effect
to fail that way (Magnetic Boots at 1.5x was the first), and the pattern is worth
naming: **an effect that only changes a rate the player cannot measure is
indistinguishable from an effect that does nothing.** Prefer effects whose result
is a discrete, visible event.

**The mechanic now:** every crop the player's feet enter is replaced by a dead
bush on the spot, at any growth stage. Instant, local, visible, obviously caused
by them, and it leaves a permanent scar on the world rather than a temporary
statistical drag.

- **Scope is deliberately wide.** No radius, no ownership check, no "crops you
  planted" test — cutting across a village farm ruins the village's farm. That
  is the intended chaos.
- **It is also much cheaper than the thing it replaced.** One block-state read
  per player per tick, against Green Thumb's 4913-block sweep. No proximity
  query, because the question is only ever "what block am I in".
- Covered blocks are `CropBlock`, `StemBlock` and `PitcherCropBlock` — the same
  three the old hook reached — **plus `AttachedStemBlock`**, which the hook did
  not, so that a stem which has already fruited dies like its neighbours instead
  of standing untouched in a dead field. The fruit block itself is separate and
  is not removed.
- The crop drops nothing. It is a block replacement, not a break.

#### Dead Bush placement — the finding most likely to have gone wrong, and didn't
This was the part of the design that could have silently reverted itself, and it
verifies clean in a stronger way than expected. **Do not re-derive this from
folklore about dead bushes needing sand.**

- **There is no `DeadBushBlock` class in this version.** `minecraft:dead_bush` is
  `net.minecraft.world.level.block.DryVegetationBlock`. Grepping for the old name
  returns nothing, which reads like "the block is gone" rather than "the class was
  renamed" — the same shape of mistake as the Frost Walker and `Villager` package
  findings. Resolved via the `invokedynamic` bootstrap-method table of
  `Blocks.<clinit>`, since the registration site is a lambda:
  `javap -v -p Blocks | awk '/BootstrapMethods:/{f=1} f'`.
- Its only survival rule is `VegetationBlock.canSurvive`, which is exactly
  `below.is(BlockTags.SUPPORTS_DRY_VEGETATION)`. No light check, no water check,
  nothing else.
- **The tag chain makes this safe by construction, not by luck:**

  | tag | contents |
  |---|---|
  | `supports_dry_vegetation` | `#sand`, `#terracotta`, `#supports_vegetation` |
  | `supports_vegetation` | `#substrate_overworld`, **`minecraft:farmland`** |
  | `supports_crops` | **`minecraft:farmland`** — and nothing else |

  Every crop this effect touches requires `SUPPORTS_CROPS` beneath it, which is
  farmland alone, and farmland is inside `SUPPORTS_DRY_VEGETATION`. **So the
  ground under any crop is always ground a dead bush survives on.** More generally
  `SUPPORTS_VEGETATION ⊆ SUPPORTS_DRY_VEGETATION`, so a dead bush survives
  anywhere *any* `VegetationBlock` survives. Tag merging from datapacks is
  additive, so this cannot be narrowed by a pack either.
- It even survives the farmland being trampled back to dirt afterwards, since
  `#dirt` is in `#substrate_overworld`.
- **The one real exception is the pitcher crop's upper half**, which stands on the
  lower half rather than on the ground. A dead bush placed there would have no
  valid support and would pop off on the next update. `BlightTouchedTrample.blight`
  redirects to `pos.below()` for that case; the upper half then breaks itself for
  lack of support, which is what `setBlockAndUpdate` (flag 3, `UPDATE_ALL`) is for
  — Green Thumb's `UPDATE_CLIENTS` would not have notified it.

**World persistence needed no work and cannot be got wrong.** This is a *world*
change, not player state — the first in the project. `Level.setBlockAndUpdate`
reaches `LevelChunk.setBlockState`, which calls `markUnsaved()`, so vanilla writes
it with the chunk like any other block edit. Nothing mod-side persists it, nothing
mod-side can lose it, and it survives the mod being removed.

#### Sweeping the path, not just the feet block
A single "what block are my feet in" check per tick is right for walking (0.28
blocks/tick means three or four ticks land in the same block) and **wrong for
anything faster** — elytra, a horse, and a boat on ice all cover more than a block
per tick, so a player flying over a field would skip most of it.

`TramplePath` sweeps the segment between the previous tick's position and this
one's. It is **free of Minecraft imports**, same discipline as `CropSchedule`,
`AcquiredEffects` and `EntropyPalette`, because a gap in the sweep is invisible in
play — it just looks like a crop that happened not to be trampled — so it needs to
be harness-drivable. Three rules, all asserted:

- Standing still yields exactly one cell, not one per sample.
- A fast move yields every cell on the line, contiguous and in travel order.
- **A move of more than `MAX_SEGMENT` (16 blocks) on any axis yields only the
  destination cell.** A teleport, a portal and a `/tp` all present as an enormous
  single-tick delta, and destroying every crop on the line between two points is
  not the effect. A dimension change is treated the same way.

Sampling is at `MAX_STEP` = 0.25 blocks on the largest axis rather than an exact
voxel traversal. Stated limit: a cell clipped by less than a quarter block at a
corner can be missed. That is deliberate — the exact algorithm is several times
the code for an outcome no player can tell from a near-miss.

**Feedback:** `level.levelEvent(LevelEvent.PARTICLES_DESTROY_BLOCK, pos,
Block.getId(state))` — one line, and it gives the destroyed crop's own break sound
and its own texture's break particles for free. Prefer this over hand-picking a
`SoundEvent` and a `ParticleType`.

#### Crop growth timing — derived, not measured; don't re-derive it from scratch
This is the kind of number a future balance pass would otherwise redo from
zero. The probability model below is **validated against an external known-good
figure** (vanilla row-planted wheat at ~24 minutes) and is asserted by
`./gradlew harness`.

**It no longer governs any shipped effect** — it described the retired
`getGrowthSpeed` hook, which both crop effects have now left. It is kept because
it still describes vanilla's own behaviour correctly, and because it is the
evidence that *both* rewrites were necessary rather than merely preferred: it is
what proved Green Thumb could not reach 90s through the hook, and the same
saturation shape is why a growth multiplier was never going to make Blight Touched
noticeable either.

**Two independent probabilities decide vanilla crop speed, and only one of them
is reachable from an effect.**

1. **The random-tick rate — not reachable.** `ServerLevel.tickChunk` draws
   `random_tick_speed` positions per game tick from each 16³ section, uniformly
   over its 4096 blocks. The gamerule's *registered default is 3*, so one
   specific crop block expects a random tick every `4096/3 = 1365.33` ticks —
   **68.27 seconds**. No per-effect code can change this.
2. **The growth roll — reachable.** `nextInt((int)(25.0F / speed) + 1) == 0`.

**The roll bound saturates at 1 once `speed > 25`**, because of the integer
truncation: `(int)(25/25.1) == 0`, so `nextInt(1) == 0` is always true and the
crop grows on *every* random tick. Above that, larger multipliers do **nothing at
all** — the same saturation shape as Exposed's 1.25 detection multiplier.

Base speeds `getGrowthSpeed` produces: **10.0** row-planted on hydrated farmland,
**5.0** for a fully-planted field (the crossing-neighbour halving), floor **1.0**
for the worst real layout (dry, isolated, crops on both axes). 26 was the
smallest whole multiplier that saturated every layout.

**Beetroot and torchflower both override `randomTick`** with an extra
`nextInt(3) != 0` gate before delegating to `CropBlock`, so only two thirds of
their random ticks reach the growth roll. This applies to the *natural* path
only — a granted advance skips `randomTick` entirely and is not gated.

Vanilla stage counts, javap-verified via `getMaxAge()` and reused rather than
re-derived: **wheat/carrots/potatoes 7, beetroot 3, torchflower 2, stems 7 (+ a
fruit-placement step), pitcher crop 4.**

#### Green Thumb: when vanilla's own systems cannot reach the target
**This is the established pattern for any future effect whose target lies beyond
what vanilla's tick or probability systems can deliver.** Read it before tuning a
multiplier harder.

**The ceiling that forced it.** Green Thumb scaled `getGrowthSpeed` and was tuned
all the way to saturation — one stage per random tick, the most that hook can
ever do. Wheat still took `7 × 68.27 = 478s`. A 90-second target needs a stage
every 257 ticks, 5.3× more often than random ticks arrive at all. **The binding
constraint was the rate, not the chance**, so no value of the multiplier could
reach it. Getting there through the hook would have needed
`/gamerule random_tick_speed 16` — a world setting, not an effect.

**The pattern.** Stop tuning vanilla's parameters; drive the outcome on a
schedule of your own — but still perform the change through **vanilla's own state
transition**, so the result is indistinguishable from a natural (very lucky)
tick. Both halves matter: the schedule is what beats the ceiling, and using
vanilla's transition is what keeps block updates, client visuals and every
subclass quirk correct for free.

`GreenThumbGrowth` grants stage advances directly. **Green Thumb declares no
multiplier constant any more** — removed rather than set to 1.0, because a neutral
constant can be quietly re-wired into the shared hook and double-applied, and a
field that does not exist cannot. The harness asserts its absence. (The shared
hook it left has since been deleted outright, when Blight Touched — the only other
effect on it — left too.)

**Why uniform 90s is achievable now and was not before.** A granted advance is
not rolled for, so the expected total is just `stages × interval`. Differing
stage counts stop mattering: divide the same budget by each crop's `getMaxAge()`.

| Crop | Stages | Interval | Total | |
|---|---|---|---|---|
| Wheat / carrots / potatoes | 7 | 255t | 1785t | **89.3s** |
| Beetroot | 3 | 600t | 1800t | **90.0s** |
| Torchflower | 2 | 900t | 1800t | **90.0s** |
| Pitcher crop | 4 | 450t | 1800t | **90.0s** |
| Pumpkin / melon stem | 7 + fruit | 240t | 1680t + ~120t | **90.0s** |

Intervals are `budget / stages` floored onto the 5-tick advance grid — an
interval the advance pass cannot observe would silently round up to the next grid
step. Stage counts are read from `getMaxAge()` **at runtime**, not a hardcoded
table, so a crop this build has never seen is timed correctly too.

**The stem's last step cannot be granted, and it is the one real irregularity.**
Reaching age 7 is seven ordinary advances, but producing the first fruit is not a
stage change — it is a placement with light, support and free-space conditions,
and **vanilla exposes no deterministic way to trigger it**. Even
`performBonemeal` on a mature stem just calls `randomTick` and re-rolls. So the
fruit step is retried at the scan cadence through vanilla's own `randomTick`,
keeping every condition intact. At an ordinary base speed that is ~6 attempts ≈
120 ticks, and the stem's stage budget is reduced by exactly that so the total
still lands on 90s — a number **derived from the same validated roll model**, not
picked. If nothing can host a fruit it simply keeps trying, at one cheap roll per
scan.

**Which vanilla transition each crop uses** — all public, so no mixin is needed:

| Type | Advance | Why this one |
|---|---|---|
| `CropBlock` | `setBlock(pos, getStateForAge(age+1), UPDATE_CLIENTS)` | Literally `randomTick`'s success branch. `getStateForAge` is **virtual**, so beetroot's shorter age property and torchflower's conversion into the flower are vanilla's problem, not ours |
| `StemBlock` age < 7 | `setBlock(pos, state.setValue(AGE, age+1), UPDATE_CLIENTS)` | The same branch in `StemBlock.randomTick` |
| `StemBlock` age 7 | `state.randomTick(...)` | The fruit step; see above |
| `PitcherCropBlock` | `performBonemeal(...)` | Resolves the lower half itself and grows by **exactly 1** — the only crop whose bonemeal is a single stage |

Three traps this avoided, each checked rather than assumed:

- **`AttachedStemBlock extends VegetationBlock`, not `StemBlock`.** So a stem that
  has already fruited is not matched and drops out of tracking by itself. Had it
  been a subclass, `state.getValue(StemBlock.AGE)` would have thrown on a block
  with no age property. (Blight Touched matches it explicitly and separately, for
  the opposite reason — it *wants* fruited stems.)
- **A pitcher crop is two blocks, and a radius scan finds both** — advancing it
  twice per interval. `PitcherCropBlock.isRandomlyTicking(state)` is vanilla's own
  "is there anything to do here" test (true only for the LOWER half, and only
  below max age), so using it as the eligibility predicate is what prevents that.
- **`BlockPos.betweenClosed` yields a mutable cursor.** Anything stored from the
  sweep must be `.immutable()` or every tracked key aliases one position.

**Cost, stated so it is inspectable:**

- **Rescan: O(players × 4913 block lookups) every 20 ticks** — a 17×17×17 box per
  player, amortising to ~246 block-state reads per player per tick. Vanilla's own
  random ticking reads ~1300 block states per second in a *single* loaded section.
- **Advance pass: O(tracked crops) every 5 ticks**, and the tracked set holds only
  immature crop blocks actually in range. Each entry costs one long compare; only
  entries genuinely due pay for a block read and a nearest-player query.
- **Nothing runs at all when no player holds the effect** — the acquired set is
  checked once per pass, before anything is scanned.

**`CropSchedule` is deliberately free of Minecraft imports**, the same discipline
`AcquiredEffects` and `EntropyPalette` follow. The two rules easiest to get wrong
— a crop that leaves the radius stops being advanced, and a fully-grown crop
stops being tracked — are therefore driven by the harness against the real class.
Both fall out of one mechanism: `refresh` keeps only the keys the caller reports
as eligible, so out-of-range, harvested and matured crops are forgotten by the
same path with no separate expiry to keep in sync. Eligibility is **re-verified
at the moment of each advance**, never trusted from the scan.

Scheduling state is **not persisted and is cleared on `SERVER_STOPPED`** — it is
about a player standing in a field right now, not part of the run, and in
singleplayer the next world is one trip through the main menu away.

**Blight Touched interaction: there is none any more, and that is the point.**
The two effects once composed through a single multiplier; both have since left
that hook and it has been deleted. They now operate on different axes entirely —
Green Thumb grants stage advances on a schedule, Blight Touched destroys blocks
the player steps on — so a run holding both grows crops to maturity in 90 seconds
*and* kills any of them the player walks over. Nothing about Green Thumb's timing
depends on Blight Touched, which the harness asserts directly rather than leaving
to inspection: the whole 90s interval table is re-derived in a section named for
that regression.

#### Leaky Pockets: 4% → 7% per jump
Retuned after play testing. At 4% the mean gap was 25 jumps, rare enough that the
curse could be held for a long stretch without ever being noticed — failure mode
3 from the mixin-cluster list ("real, but below the perceptual threshold"). 7%
means a mean gap of ~14 jumps, roughly every other minute of ordinary movement.
One constant, `LeakyPocketsBehavior.CHANCE`, asserted by the harness.

#### Clumsy Digger: "doesn't work, or is too rare" — neither, as it turns out
Diagnosed before tuning, per the "when a mixin applies cleanly and still changes
nothing" rule. **No bug was found, and the trigger path is fully correct:**

- `run/logs/debug-*.log.gz` shows `Mixing ItemStackDurabilityMixin ... into
  net.minecraft.world.item.ItemStack` — the injector matched. (`defaultRequire: 1`
  would have failed launch otherwise, so this was never really in doubt.)
- `ItemStack.hurtAndBreak(I, ServerLevel, ServerPlayer, Consumer)` **is** the real
  choke point: the `(I, LivingEntity, EquipmentSlot)` overload's bytecode ends in
  `invokevirtual hurtAndBreak:(ILServerLevel;LServerPlayer;LConsumer;)V`.
- Block mining genuinely reaches it: `Item.mineBlock` calls
  `hurtAndBreak(damagePerBlock, entity, MAINHAND)`, gated only on
  `getDestroySpeed != 0` (instant-break blocks cost no durability — vanilla) and
  `damagePerBlock > 0`.
- Vanilla consumes the modified value: the 4-arg body passes `amount` into
  `processDurabilityChange`, which is where Unbreaking is applied. The `+1` lands
  *before* Unbreaking, so an enchanted tool composes rather than double-counting.

**The finding that mattered: `CHANCE` was the wrong lever, and no value of it
could have fixed this.** The observable magnitude is entirely
`1 + CHANCE × EXTRA_DAMAGE` as a share of a tool's lifetime, and with a flat
`EXTRA_DAMAGE` of 1 even a **guaranteed** trigger only doubles wear — the same
saturation shape as Exposed's 1.25 detection cap and the crop hook's 26x. 0.04
cost 4% of an iron pickaxe; 0.07 cost 6.5%. Both are inside the range a player
cannot detect without counting blocks.

#### Clumsy Digger's per-tier cost — deliberately NON-UNIFORM, do not normalise it
`EXTRA_DAMAGE` is no longer a flat number. It scales with the tool's own maximum
durability against a 200-point reference:

```
extraDamage = round(BASE_EXTRA × maxDamage / REFERENCE_DURABILITY)
            = round(40 × maxDamage / 200)
```

**Better tools lose a larger share of their life, and that is the design.** A
curse that costs netherite 97% and gold 32% is a curse that punishes bringing out
the good gear. A future session must not "correct" the non-uniformity — the
harness asserts `netheriteShare > goldShare` specifically to stop that.

Real durabilities, read from `ToolMaterial` itself rather than typed in (**note
this version has a COPPER tier at 190**, between stone and iron):

| Tier | Max durability | +damage per proc | Blocks survived | vs. normal |
|---|---|---|---|---|
| Gold | 32 | +6 | 21.6 | **−32.4%** |
| Wood | 59 | +12 | 30.1 | −49.0% |
| Stone | 131 | +26 | 42.5 | −67.5% |
| Copper | 190 | +38 | 47.0 | −75.2% |
| Iron | 250 | +50 | 50.0 | −80.0% |
| Diamond | 1561 | +312 | 60.1 | −96.1% |
| Netherite | 2031 | +406 | 60.7 | **−97.0%** |

**The structural consequence, and the most important thing to know before
retuning: durability cancels out, so blocks survived is asymptotically capped.**

```
blocks = maxDur / (1 + CHANCE × BASE_EXTRA × maxDur / REFERENCE)
       → REFERENCE / (CHANCE × BASE_EXTRA) = 200 / (0.08 × 40) = 62.5 blocks
```

So `BASE_EXTRA` is really a control on **"how many blocks does any good tool
get"**, not on a percentage. At 40 the ceiling is ~62 blocks; at 20 it would be
~125; at 80, ~31. Three consequences fall straight out of that and are all
asserted:

- **Diamond and netherite are within 0.5 blocks of each other** (60.1 vs 60.7).
  Netherite's 30% durability advantage is completely erased — both are simply
  sitting on the ceiling.
- **Tier progression is compressed roughly 22-fold.** Normal durability spans 63x
  (gold 32 → netherite 2031); under the curse the span is 2.8x (21.6 → 60.7).
  Order is preserved and never inverted, but the tiers stop meaning much.
- **A cursed netherite pickaxe (60.7) is worth about an uncursed wooden one
  (59).** That is the single sharpest way to read the table.

**Flagged outliers, stated rather than left to be noticed in a table:**

- **Netherite at −97% is the extreme.** 2031 blocks becomes 61. If that reads as
  too much in play, lower `BASE_EXTRA` — it moves the ceiling directly.
- **Gold at −32% is the mild end**, and cursed gold (21.6) is the only tier that
  drops below cursed wood (30.1) — because gold's real durability is genuinely
  worse than wood's. Not a formula artefact.
- **Nothing is "barely affected".** Even wood loses half its life, so the bottom
  of the table is not a dead zone.

#### Clumsy Digger is scoped to mining tools — the hook is broader than the effect
`ItemStack.hurtAndBreak` is *every* source of durability loss, and for a while
this effect took all of it. Armour and the elytra were being hit at the tool
severity: a netherite chestplate lost ~90% of its life, an elytra managed under a
minute of flight. **The hook's breadth had been mistaken for the effect's scope.**

**The gate is a vanilla tag, `#minecraft:enchantable/mining`**, held as
`ClumsyDiggerBehavior.AFFECTED_ITEMS`. Why a tag and not a class check:

- **There is no class hierarchy left to test.** `DiggerItem` / `PickaxeItem` /
  `ShovelItem` are gone — tools are data-driven now (an `Item.Properties` carrying
  a `TOOL` data component). A hardcoded list of item classes has nothing to bind
  to.
- **There is no `#minecraft:tools` tag either.** The candidates are
  `#pickaxes` / `#axes` / `#shovels` / `#hoes` individually, or one of the
  `enchantable/*` tags.
- `enchantable/mining` = those four families **plus shears**, and is the closest
  vanilla concept to "mining implement". One tag rather than a union of four, and
  a modded or datapack-added pickaxe joins it for free.
- `enchantable/mining_loot` is the identical tag **minus shears** — a one-word
  change if shears should be excluded. Shears are kept deliberately: they are a
  tool and they break blocks.
- For contrast, `#minecraft:enchantable/durability` is what "anything with
  durability" actually means, and it contains all four armour slots, elytra,
  shield, swords, bow, crossbow, trident, mace, flint and steel, brush and fishing
  rod. That is the set this effect used to hit.

**Out of scope now, and asserted individually:** every armour item of every slot
(29 of them), elytra, shield, all weapons, flint and steel, brush, fishing rod.
The affected set is 29 items; all-damageable is 83.

**API note — `ItemStack.is(TagKey)` does not exist in this version.** This is the
idiom in every tutorial and it is gone; the only `is` overload takes a
`Predicate<Holder<Item>>`, and `TagKey` is a plain record, not a `Predicate`. Tag
membership goes through the item's holder, which is also renamed:

```java
stack.typeHolder().is(ItemTags.MINING_ENCHANTABLE)   // getItemHolder() -> typeHolder()
```

This is the form vanilla itself uses (`Holder.is(TagKey)` in `Enchantment`).

**The scope gate runs before the random roll**, deliberately — verified in
bytecode as `appliesTo` → `nextFloat` → `extraDamageFor` → `LOGGER.debug`. If it
ran after, the DEBUG line would report procs on armour that were then discarded,
which is exactly the kind of misleading diagnostic that caused this effect's
original misdiagnosis.

**How the harness checks this without a loaded tag set.** Evaluating
`typeHolder().is(tag)` for real needs a bootstrapped registry and loaded tags,
which the harness does not have. Instead it **reads the shipped tag JSON out of
the Minecraft jar on the classpath** (`/data/minecraft/tags/item/...`) and
resolves the nested `#minecraft:` references itself. That checks the claim that
actually matters — what the tag *contains* — against real game data rather than
restating the constant. Copy this for any future tag-scoped effect.

**A DEBUG log line fires on each successful roll**, naming the item and its max
durability, and — since the scope gate runs first — **only ever for tools**. This
effect has no sound, no message and no visible change, only a durability bar
moving faster, which is precisely why "not firing" and "firing rarely" were
indistinguishable in the original report.
`grep "Clumsy Digger" run/logs/debug-*.log.gz` shows every proc and what it hit;
an armour piece appearing there would itself be the bug. It costs nothing for
players without the effect, since the early returns precede it.

#### Villager pricing: mixin-only, and the class moved (Bad Reputation)
Two findings, both worth having permanently:

- **Fabric API has no trade-price event — it has no trade-related classes at all
  in this version.** So this is mixin-only. That was the brief's flagged risk and
  it resolved to "yes, mixin", not to a stop.
- **`Villager` moved package**: it is
  `net.minecraft.world.entity.npc.villager.Villager`, not
  `net.minecraft.world.entity.npc.Villager`. Grepping the old path returns
  nothing, which reads like "the class is gone" rather than "it moved" — the same
  shape of mistake as the Frost Walker finding. Villager *trades* are also
  data-driven now (`data/minecraft/villager_trade/...`).

The hook is `Villager.updateSpecialPrices(Player)`, which is **vanilla's own
per-player pricing pass** — where gossip reputation and Hero of the Village are
turned into `MerchantOffer.addToSpecialPriceDiff` adjustments. Injecting at TAIL
makes the surcharge additive with reputation instead of clobbering it, and the
vanilla trading UI renders the adjusted price for free.

**Subclass-override risk: structurally impossible here**, and checked explicitly
per the rule above. The method is `private` (so it cannot be overridden) *and*
there are no subclasses of `Villager` in the jar. Two independent guarantees.

**Scope:** wandering traders are unaffected — `WanderingTrader` extends
`AbstractVillager`, not `Villager`, and has no special-price mechanism. Out of
reach, not overlooked. Vanilla clamps the final cost to `[1, maxStackSize]`, so
a surcharge can never make a trade impossible.

#### Bad Reputation pricing: it is ADDITIVE, and the basis matters as much as the number
Retuned to 1.5–1.75x. The number alone would not have got there, because **this is
not a multiplier and cannot be made into one by scaling a constant.**

Vanilla's price, verified in `MerchantOffer.getModifiedCostCount`:

```
finalCount = clamp(baseCount
                 + max(0, floor(baseCount × demand × priceMultiplier))
                 + specialPriceDiff,          // ← flat int; this is our channel
                   1, maxStackSize)
```

`specialPriceDiff` is a **flat integer**. So a surcharge only behaves like a
multiplier if it is computed against the same denominator the player experiences.
The old version took `0.25 ×` **`getBaseCostA()`** — the raw base, before demand
and gossip — which drifted: the ratio the player felt depended on how much demand
and reputation had already moved the price.

**Fix: take the fraction of `getCostA()` instead.** At TAIL, `getCostA()` is
already exactly "the price this player would otherwise have paid" — demand and
the gossip/Hero-of-the-Village adjustment are both folded in by then. At
`SURCHARGE = 0.65` that holds the ratio steady regardless of demand or reputation:

| normal | with curse | ratio | | normal | with curse | ratio |
|---|---|---|---|---|---|---|
| 2 | 3 | 1.500x | | 12 | 20 | 1.667x |
| 3 | 5 | 1.667x | | 16 | 26 | 1.625x |
| 4 | 7 | 1.750x | | 20 | 33 | 1.650x |
| 5 | 8 | 1.600x | | 24 | 40 | 1.667x |
| 6 | 10 | 1.667x | | 32 | 53 | 1.656x |
| 8 | 13 | 1.625x | | 36 | 59 | 1.639x |

Two boundaries, both **vanilla's, not tuning misses**, and both asserted so they
are not refiled as bugs:

- **A 1-emerald trade becomes 2, i.e. 2.00x.** Prices are integers, so +100% *is*
  the smallest possible increase on a 1-emerald trade. There is no value of
  `SURCHARGE` that lands 1 emerald in the 1.5–1.75 band.
- **Above ~39 emeralds the single-stack clamp starts absorbing the surcharge**,
  and a trade already costing 64 is completely unaffected. That is vanilla's hard
  ceiling on trade cost.

**Counterplay got stronger, not weaker.** Because the surcharge is a fraction of
the already-discounted price, good reputation is worth more under this curse than
it was under the old flat-from-base version.

#### Second Guess: the first state-bearing effect
Everything before this was a pure function of membership in `AcquiredEffects`.
Second Guess carries one boolean of its own, `rerollUsed`, and the rules it
follows are the template for any future meta effect:

- **The flag lives in `EntropyManager`'s existing codec**, not in a parallel
  store. One `optionalFieldOf("reroll_used", false)` line — which also means a
  save written before this effect existed still loads, and a run with the reroll
  unspent omits the field entirely (that is `optionalFieldOf` working as
  intended, not a bug; asserting the field is always present would assert the
  opposite of the design).
- **The trigger has to be a button, not a command.** `ChoiceScreen` is modal and
  `isPauseScreen()` is true, so chat cannot be opened while a pick is pending —
  a `/entropyreroll` command would be untypeable exactly when it is needed. The
  button is part of the centred layout block so it cannot push off-screen at
  small GUI scales (`shouldCloseOnEsc()` is false; nothing may ever clip).
- **`requestReroll` delegates to the real `triggerPick`** and contains no roll
  logic — verified in bytecode the same way `/entropyforcepick` is: exactly one
  `triggerPick` call, zero references to `EffectRegistry.roll`,
  `OpenChoicePayload`, `ServerPlayNetworking` or `shuffle`.
- **It does not advance the loop.** No entropy, no pick count, no history entry,
  no interval reset — those only move in `onChoiceMade`, which a reroll never
  reaches. The reroll is consumed *only* if new choices actually opened, so an
  exhausted pool does not eat it for nothing.
- **"Once" does not depend on no-repeat.** The flag is on the run, so
  re-acquiring the effect cannot refund it. No-repeat is treated as a second line
  of defence only, because the repeat fallback can legitimately re-offer an
  already-taken effect once a phase's pool empties — a design resting on
  no-repeat alone would eventually be wrong.
- `rerollState` rides on `OpenChoicePayload` so the client knows how to draw the
  button, but it is **not authorisation**: `requestReroll` re-checks ownership,
  spent-ness and a pending pick server-side. A client sent `SPENT` that asks
  anyway is still refused.

#### Second Guess's button stayed live after use — an ORDERING bug, not a missing field
Reported as "the button remains clickable after a reroll, and clicking does
nothing". The instinct is that the client was not being told the truth. **It was
being told the truth one statement too early.**

`requestReroll` did this:

```java
PickTrigger result = triggerPick(server);   // builds AND SENDS the payload
if (result != OPENED) return false;
rerollUsed = true;                          // ← too late; already on the wire
```

`triggerPick` builds `OpenChoicePayload` from the reroll state, which reads
`rerollUsed`. So the replacement screen — **the one screen where it matters** —
advertised a reroll that had just been spent. Every *later* pick was already
correct, which is what made this look like a client rendering fault rather than a
server one.

Fix: spend first, refund on failure. The "not consumed if nothing opened"
guarantee is preserved by the refund rather than by the late assignment, and the
ordering is verifiable in bytecode — `putfield rerollUsed` (true) precedes
`invokevirtual triggerPick`, with a second `putfield` (false) on the failure path.

**The second half was a real payload change, though: a boolean could not express
the state.** `rerollAvailable` conflated "never had Second Guess" with "had it and
spent it", and those want opposite rendering — nothing at all vs. a visibly
greyed-out button. It is now a three-state `RerollState` enum
(`NOT_OWNED` / `AVAILABLE` / `SPENT`), sent **by name** like `EffectPhase`. This is
the shape the `EffectDuration` note argues for: when one value carries several
meanings, make it an enum so the compiler forces every call site to handle each
case.

Note the two accessors are deliberately different and must stay so:
`rerollState()` answers a *rendering* question and goes on the wire;
`isRerollAvailable()` answers an *authorisation* question and additionally
requires `waitingOnChoice`. Collapsing them would make the wire value depend on
when it was asked.

#### Become Hardcore — INVESTIGATED, NOT BUILT. Verdict: buildable as specified
> **Superseded as the implementation approach** — see "Run Lifecycle: first slice
> BUILT, ENDED half still design-only" below. The findings here remain accurate and are kept
> as reference; the effect will use a mod-tracked flag instead.

Full javap investigation, no code written. Recorded because the answer is
non-obvious in both directions and re-deriving it would cost a session.

**Verdict: real vanilla hardcore is reachable.** The flag can be flipped at
runtime, it is read *live* at the moment that matters, and it persists to
`level.dat`. This does not need a mod-built imitation.

**Where the flag lives.** `MinecraftServer.isHardcore()` → `WorldData.isHardcore()`
→ `PrimaryLevelData.isHardcore()` → `settings.difficultySettings().hardcore()`.

- `LevelSettings` is in **`net.minecraft.world.level`**, *not*
  `net.minecraft.world.level.storage` where the rest of the level-data classes
  live. Both it and the nested `LevelSettings$DifficultySettings`
  (`difficulty`, `hardcore`, `locked`) are **records**.
- **There is no `withHardcore()`.** Vanilla ships `withGameType`,
  `withDifficulty`, `withDifficultyLock`, `withDataConfiguration`, `copy()` — and
  deliberately no way to rebuild settings with a different hardcore value. So
  there is no public API path.
- **But `PrimaryLevelData.settings` is `private` and NOT final**, and vanilla
  itself rewrites it with `putfield` in `setDifficulty`, `setDifficultyLocked`,
  `setGameType` and `setDataConfiguration`. Replacing the record with one carrying
  `hardcore = true` is therefore an ordinary field write on the server — an
  accessor mixin, no `@Mutable` needed.
- **It persists.** `PrimaryLevelData.setTagData` writes `difficulty_settings`
  through `DifficultySettings.CODEC`, which includes `hardcore`. The flip lands in
  `level.dat` at the next save and the world is genuinely, permanently hardcore.

**The critical question — is it read live at death? YES.** This was the finding
that decided buildability, and it went the good way.

- **`ServerPlayer` does not reference `isHardcore` anywhere.** Death itself does
  not check it.
- **`PlayerList.respawn` does not reference it either.**
- In the entire common jar there are exactly **two** reads:
  `PlayerList.placeNewPlayer` (building `ClientboundLoginPacket` at join) and
  `ServerGamePacketListenerImpl.handleClientCommand` (the `PERFORM_RESPAWN`
  handler).
- In `handleClientCommand` the order is: bail if `getHealth() > 0`;
  `PlayerList.respawn(player, false, KILLED)`; `resetPosition()`;
  `restartClientLoadTimerAfterRespawn()`; **then `server.isHardcore()` at offset
  174**; and only then the hardcore branch.

So the decision is made **after death, when the player clicks the button**, from a
live read with nothing cached. An effect that flips the flag at any earlier point
takes effect on the very next death.

**What vanilla's hardcore death actually is** — the whole server-side mechanism,
not the reputation:

1. The player **respawns normally first** (`PlayerList.respawn` builds a new
   `ServerPlayer`).
2. `player.setGameMode(GameType.SPECTATOR)`.
3. `level.getGameRules().set(SPECTATORS_GENERATE_CHUNKS, false, server)`.

That is all of it. **No world deletion, no world-level "game over" flag, no
locking of the save.** Permanence is ordinary gamemode persistence:
`ServerPlayer.storeGameTypes` writes `playerGameType`, and
`calculateGameModeForNewPlayer` only overrides it when
`MinecraftServer.getForcedGameType()` is non-null — which `IntegratedServer`
returns only when the world is LAN-published *and not hardcore*. `PlayerList
.respawn` sets no gamemode at all. So Spectator survives relog with nothing
undoing it.

**Nothing is gated on how the server was launched.** No launch-time or
world-creation-time precondition exists that a later flip could fail to satisfy.

**The one real caveat: the client is told exactly once, at login.**

- `hardcore` is a component of `ClientboundLoginPacket` **only**. It is *not* on
  `ClientboundRespawnPacket`, and *not* in `CommonPlayerSpawnInfo`. No vanilla
  packet updates it mid-session.
- `ClientLevel$ClientLevelData.hardcore` is **`private final`** client-side.
- **This does not block the effect**, because `DeathScreen.init` wires *both* the
  "Spectate" and "Respawn" buttons to `LocalPlayer.respawn()`, which sends the
  identical `ServerboundClientCommandPacket(PERFORM_RESPAWN)`. Only the label
  differs. Even "Title Screen" calls `LocalPlayer.respawn()` first
  (`lambda$handleExitToTitleScreen$0`).
- Net effect of a stale client: for **that one death only**, the screen says "You
  died!" with a "Respawn" button instead of the hardcore title and "Spectate
  World" — the player clicks it and the server puts them in Spectator regardless.
  Outcome correct, cosmetics wrong. After any relog the login packet carries the
  flipped flag and hearts, death screen and the world-list label
  (`LevelSummary.isHardcore`, red "Hardcore") are all correct permanently.
- Closing that cosmetic gap is optional polish: a `@Mutable` shadow on
  `ClientLevelData.hardcore` plus a one-boolean payload, which this project's
  networking layer already supports.

**Interactions with what is already built:**

- **Re-application hooks need no change, and suppressing them would be worse.**
  Verified ordering: `PlayerList.respawn` is offset 156, `setGameMode(SPECTATOR)`
  is offset 187 — so `ServerPlayerEvents.AFTER_RESPAWN` (and therefore
  `reapplyAll`) fires *before* the spectator switch, exactly once, on a player who
  is still nominally alive. Leaving it alone keeps the invariant that attributes
  are always derived from `AcquiredEffects`; a spectator simply ignores max
  health, damage, fall damage and block-break speed.
- **The two tick services DO need a spectator guard, and currently lack one.**
  `BlightTouchedTrample` keys only on the acquired set and the player's position,
  so a dead player ghosting through a village farm would still trample it, and
  `GreenThumbGrowth` would still grow crops around a spectator. Both are small and
  neither is caused by this effect — but this effect is what makes a
  permanently-spectating player a normal state rather than a curiosity. Add
  `player.isSpectator()` checks when this is built.
- **Entropy cap and hardcore death are independent end states that do not know
  about each other.** `EntropyManager.triggerPick` sets `gameOver` on
  `entropy >= entropyCap`; hardcore death is pure vanilla and touches no mod
  state. Left as-is, **the loop keeps opening pick GUIs every interval for a dead
  spectator**, which is a visible wart and a design decision rather than a bug.
  See Open Question 12.
- **This is the first effect whose real state lives outside `AcquiredEffects`.**
  Every other effect is derived from that set and would vanish if the set were
  cleared; this one writes to `level.dat` and **cannot be undone by the mod at
  all** — not by clearing the acquired set, not by removing the mod. Worth stating
  plainly because it breaks the "AcquiredEffects is the single source of truth"
  invariant in a way nothing else does.
- **`/entropygrant become_hardcore` would irreversibly convert the test world.**
  This is the first debug grant with permanent consequences outside the mod's own
  state. Whatever else happens, that command needs a guard or a very loud warning.

#### Client-side effects need infrastructure that did not exist — read this first
**Before Tier 2 the client had no idea which effects the run held**, and there was
no reason for it to: every effect was server-authoritative (attributes, or mixins
on server classes). Tier 2's three effects all act on systems that exist *only* on
the client — `KeyboardInput` and `Camera` — so none of them could work at all
without a new channel.

`ClientEffectsPayload` (S2C) carries the acquired effect ids plus the movement
scramble. Sent by `EntropyManager.syncTo` / `syncToAll` from exactly three places:
player join, `onChoiceMade`, and `grantEffect`.

- **Deliberately not ridden along on `OpenChoicePayload`.** That only fires when a
  pick opens, so a `/entropygrant`-ed client effect would sit inert until the next
  interval — precisely the "is it broken or just slow" ambiguity this project has
  already been burned by twice.
- **Sent unconditionally on join, including for an empty run.** The client has to
  be told "you have nothing" too, or a cached set from a previous world survives
  into the next one.
- **It is state, not authorisation.** These effects change what the player's own
  inputs and camera do; the resulting movement still goes through vanilla's
  ordinary client-to-server validation.
- `ClientRunState` is a cache with the same discipline as `EntropyHud`: cleared on
  `DISCONNECT`, and every reader degrades to vanilla behaviour when it is empty.

#### Input handling: `KeyboardInput.tick()` is the whole story
Verified in bytecode. `KeyboardInput.tick()` does exactly two things:

```java
this.keyPresses = new Input(up, down, left, right, jump, shift, sprint); // from KeyMappings
this.moveVector = new Vec2(calculateImpulse(left, right),
                           calculateImpulse(forward, backward)).normalized();
```

So an `@Inject` at RETURN that rewrites both fields is indistinguishable from the
player having pressed different keys — everything downstream follows from those
two values.

- **Both fields must be rewritten, not just `keyPresses`.** They are derived
  together and then read independently: `moveVector` drives movement,
  `keyPresses` drives sprint/sneak logic and is what gets sent to the server.
  Permuting one alone desyncs them.
- `calculateImpulse(a, b)` is `a == b ? 0 : (a ? 1 : -1)` — private static, so it
  is reproduced in `MovementScramble.impulse`.
- **`Vec2`'s argument order is (strafe, forward).** Swapping that pair silently
  rotates all movement 90°, which would read as a bizarre scramble rather than a
  bug.
- `keyPresses` is public but `moveVector` is `protected` on the superclass
  `ClientInput`, so the mixin uses `@Shadow` for both — protected access across
  packages does not compile in mixin source even though the merged result is
  in-class.
- Class locations that are not guessable: **`ClientInput` and `KeyboardInput` are
  in `net.minecraft.client.player`**, not a `client.input` package (that package
  exists but holds only event/modifier types). The `Input` record itself is
  **common**, at `net.minecraft.world.entity.player.Input`.

#### Triggering a jump: use vanilla's own synthetic key-press
**`ClientInput.makeJump()` exists and is exactly this** — it rebuilds `keyPresses`
with `jump = true`, and `LocalPlayer.aiStep` calls it for auto-jump. So faking the
press is vanilla's own idiom, not a hack, and velocity injection is unnecessary.

Random Jump sets the same bit from `KeyboardInputMixin` rather than calling
`makeJump()` from a separate tick hook, because **timing is load-bearing**:
`LocalPlayer.aiStep` calls `input.tick()` and *then* reads
`input.keyPresses.jump()` later in the same method. Any other per-tick hook is
either overwritten by `tick()` or lands a tick late.

**The edge cases are handled by vanilla, and this was verified rather than
assumed.** `LivingEntity.aiStep`'s jump block dispatches on the player's actual
situation:

| Situation | What vanilla does | Result |
|---|---|---|
| In lava | `jumpInLiquid(FluidTags.LAVA)` | bob upward, as if holding space |
| In water | `jumpInLiquid(FluidTags.WATER)` | **swim up** — not a jump, not a no-op |
| On ground | `jumpFromGround()` | the real jump: correct height, sprint boost, exhaustion |
| Mid-air | nothing | the ground branch is gated on `onGround()` — **cannot double-jump** |
| On a ladder/vine | nothing | climbing is not `onGround()`; climb handling is in `travel`, untouched |

`noJumpDelay` still rate-limits to one jump per 10 ticks, and the forced press
ORs with the player's own key so it can never *cancel* a jump they were making.

#### Upside-Down Camera — VERDICT: ship. One argument, and targeting is unaffected
The stop condition did not fire. Evidence, all from bytecode:

**`Camera.setRotation(yRot, xRot)`** — whose single call site is
`Camera.alignWithEntity(float)` — is:

```java
this.rotation.rotationYXZ(PI - yRot*DEG2RAD, -xRot*DEG2RAD, 0.0f);
FORWARDS.rotate(rotation, forwards);
UP.rotate(rotation, up);
LEFT.rotate(rotation, left);
```

**The third argument of `rotationYXZ` is roll, and vanilla hardcodes it to zero.**
There is already a roll slot in the camera's own quaternion; the effect just fills
it. A single `@Redirect` on that call is the entire implementation, and every
derived value — the forward/up/left basis, `cachedViewRotMatrix`,
`cachedViewRotProjMatrix`, and the cull frustum — is recomputed by vanilla's own
unchanged code from the quaternion.

**Why it is playable, not broken.** `rotationYXZ(y, x, z)` is `Ry·Rx·Rz`, and
`Camera.FORWARDS` is `(0, 0, -1)` — which lies *on* the Z axis, so `Rz` leaves it
unchanged while `UP` and `LEFT` flip. This is a pure roll about the view axis, and
the consequence that matters is:

- **The centre of the screen still points at the same block.** Crosshair
  targeting, mining, attacking and item use are unaffected — the raycast comes
  from the *entity's* view vector, not the camera's roll. Nothing becomes
  unreachable or untargetable.
- **The HUD is unaffected**, being screen-space, so health, hunger and the hotbar
  stay upright and readable.
- Mouse look still works with both axes reading inverted, which is coherent for an
  upside-down view rather than broken.

So it is "hard but functional", which is the intended chaos, not "unplayable".
**Residual risk, stated plainly:** motion sickness is a real possibility that no
amount of bytecode reading can settle, and the effect is permanent with no removal
mechanism. **The revert is one constant** — `CameraMixin.ROLL_RADIANS` to `0.0f`
makes it a no-op with no other code change. If in-game testing says sickening
rather than disorienting, that is the lever.

#### The movement scramble is run state, and is persisted like Second Guess's flag
`MovementScramble` is a 4-character string over `F B L R`, where `charAt(i)` is
where input direction `i` actually sends you (`"FBLR"` is the identity). It lives
in `EntropyManager`'s existing codec as one `optionalFieldOf("move_scramble", "")`
— the same one-store rule Second Guess follows.

- **Assigned exactly once**, by `assignMoveScrambleIfAbsent`, which is idempotent
  *by construction* so it is safe to call from `apply()` — that runs again on every
  respawn, rejoin and dimension change, and a re-roll there would hand the player a
  different scramble on every death.
- **The identity is excluded from the roll.** 1 run in 24 would otherwise acquire a
  curse that does nothing, which is indistinguishable from it being broken. 23
  permutations remain, and the harness asserts all 23 are reachable and none is the
  identity.
- **A malformed value degrades to vanilla controls rather than throwing** — this is
  read inside input handling every client tick, so a load-time or wire-level
  garbage value must not be able to crash movement.
- `RandomizedControlsBehavior` is **the first effect with a non-empty `apply` that
  is not an attribute**, so it implements `EffectBehavior` directly rather than
  extending `HookEffectBehavior` (whose `apply` is final and empty).

#### Randomized Controls is anchored to a KEYBIND SNAPSHOT — the rebind exploit
**Permuting vanilla's direction booleans is completely counterable from the
Controls menu, and that was the original design's real flaw.** This effect
permutes *directions*; rebinding permutes *keys to directions*. Composed, they
cancel — a player under `"LFRB"` rebinds their four movement keys by the inverse
permutation and is playing vanilla again in about twenty seconds, at no cost.

So the presses fed to `MovementScramble.apply` no longer come from vanilla's
`Input` record. They come from `KeybindCapture.pressesFor`, which reads the
**physical keys as they were bound when the run started** (`KeybindSnapshot`,
captured at Start — see the run-lifecycle section). Rebinding afterwards cannot
reach the curse, because the keys it is defined over stopped moving when the run
began.

**Note this supersedes the raw-physical-WASD plan, which was never built.** The
snapshot is not hardcoded W/A/S/D — it is whatever the player had bound at Start,
so a player who plays on ESDF or arrows is cursed on *their* keys.

Two consequences that are visible behaviour, not implementation detail:

- Keys that were movement at Start keep doing exactly what the curse says, no
  matter what the Controls menu says later.
- **A key newly bound to a movement action after Start does nothing.** Vanilla
  would move the player; this does not, because that key is not one of the four
  the curse is defined over.

**Fallback, and the one genuinely reachable edge case.** With no usable snapshot
the code permutes vanilla's live directions — the old behaviour, still a working
curse, just not rebind-proof. Reachable two ways:

- **Before the run has started.** `/entropygrant randomized_controls` has no run-
  state gate, and the snapshot is only taken at Start. In ordinary solo play this
  is barely reachable — the start panel is modal, so chat cannot be opened — but a
  server console or a second player can do it. Handled, not prevented.
- A movement key bound to a `SCANCODE` key, which `glfwGetKey` cannot poll. One
  unpollable key discards the whole snapshot rather than honouring three of four
  directions, which would be a third behaviour nothing accounts for.

#### Reading raw key state: the accessors, and the guard vanilla gets for free
All javap-verified. Three of these contradict the obvious guess.

- **`Options.keyUp/keyDown/keyLeft/keyRight` are `public final KeyMapping`.** No
  accessor mixin needed.
- **`KeyMapping.getKey()` does not exist** — the current binding is a `protected`
  field. The public reader is **`saveString()`**, whose bytecode is exactly
  `this.key.getName()`. That is also the string `options.txt` stores, which is
  what makes it a good persisted form. **`getDefaultKey()` is public and is the
  wrong one** — it reports the factory binding, not the player's.
- **`InputConstants.getKey(String)` throws `IllegalArgumentException`** on an
  unknown name rather than returning null.
- **`InputConstants.isKeyDown` takes a `Window`, not a `long` handle**, in this
  version. Its body is `glfwGetKey(window.handle(), value) == GLFW_PRESS`, so it
  is **KEYSYM-only** — passing a mouse button through it queries an unrelated key.
  Mouse bindings go through `glfwGetMouseButton` instead.

**The guard that matters: `KeyboardInput.tick()` contains no screen check.**
Verified in bytecode — it is seven `KeyMapping.isDown()` calls and nothing else.
Vanilla's "typing in chat doesn't walk you into lava" behaviour comes entirely
from **`Minecraft.setScreen` calling `KeyMapping.releaseAll()`** (offset 188,
right after `screen.added()`), which clears the per-mapping down flags.
**`glfwGetKey` knows nothing about that** and reports W as held while the player
types "w" in chat. So `KeybindCapture.pressesFor` checks `screen == null` and
`isWindowActive()` itself. Losing that guard would not fail loudly — it would
make the player walk while typing.

#### There is NO client-side per-world persistence — investigated, and it changed the design
The brief called for the snapshot to be persisted **client-side**. It is not, and
this is the finding rather than a shortcut.

**Nothing in this version or in Fabric provides durable per-world client-side
storage.** `SavedData`/`SavedDataStorage` are server-side only. The client's own
durable state is *global*, not per-world (`options.txt`). The only per-world
client-reachable directory is the save folder itself, via
`Minecraft.getLevelSource()` — **which exists in singleplayer only**, so it
cannot serve the multiplayer case at all.

The nearest workable imitation would be a mod-managed file under
`Minecraft.gameDirectory` keyed by some world identity — `getSingleplayerServer()`
for local worlds, `getCurrentServer()` for remote ones. That identity is
unreliable in exactly the ways that matter: a renamed or copied world, two
servers behind one address, a world played from a second machine.

**So the snapshot is client-captured and server-persisted**, in `EntropyManager`'s
existing codec as `Map<UUID-string, KeybindSnapshot>`, and mirrored back to its
owner on `RunSyncPayload`. Three things this buys beyond correctness:

- Per-world **by construction**, with no world-identity guessing.
- Works identically in singleplayer and multiplayer.
- **Testable headlessly.** Requirement "the snapshot survives a relog" is a codec
  round-trip in the harness; a client-side file store could not be driven by it
  at all, since the harness classpath is the main source set only.

Keyed by **UUID string**, not `java.util.UUID`, so the codec needs nothing but
`Codec.STRING` and `KeybindSnapshot` stays Minecraft-import-free like
`MovementScramble` and `TramplePath`.

**Write-once per player is the anti-exploit guarantee, not tidiness.** If a later
capture could overwrite an existing snapshot, a client could re-anchor its curse
at will and rebinding would counter it again with one extra step. So
`storeKeybindSnapshotIfAbsent` refuses rather than replaces, exactly like
`assignMoveScrambleIfAbsent`.

**No "send me your keybinds" request packet exists**, and that is a design choice
worth keeping: `RunSyncPayload` carries the player's own stored snapshot, so a
client can see for itself that the server holds nothing for it and capture
unprompted. That one predicate covers every case except the player who clicks
Start — a second player already online, and anyone joining mid-run.

#### The Tier 2 content batch — 7 effects, and what each one needed
The first Tier 2 *content* batch, after the three input/camera effects. Registry
is now **47 effects: 23 GOOD, 24 BAD** (three added by the survival batch). All seven are entropy 25-50, permanent,
and wired through the ordinary three-step recipe.

| Effect | Phase / category | Mechanism |
|---|---|---|
| Embrace the Moon | GOOD / MOVEMENT | attributes (gravity + jump + safe fall) |
| Creative Flight | GOOD / MOVEMENT | `Abilities.mayfly` |
| Behemoth Gauntlets | GOOD / COMBAT | mixin on `Player.attack` |
| Crouch Invincibility | GOOD / SURVIVAL | mixin on `ServerPlayer.hurtServer` |
| Giant Size | BAD / DEBUFF | six attributes + collision fixup + Iron Skin's hook |
| Slashed Pockets | BAD / GEAR | two mixins + a tick sweep |
| Flamboyant | BAD / SURVIVAL | mixin on `ServerPlayer.hurtServer` |

**None of the seven needed client-side awareness**, and that was checked per
effect rather than carried over from the input/camera cluster. The reason is
uniform: each is either a **client-syncable attribute** (`GRAVITY` and `SCALE`
both report `isClientSyncable() == true`, verified against the live registry, so
the client predicts movement and renders the giant model for free), vanilla
state vanilla already syncs (`ClientboundPlayerAbilitiesPacket` for flight), or
purely server-authoritative damage. **`ClientEffectsPayload` was not touched.**
The one partial exception is recorded under Slashed Pockets below.

#### Extreme Gravity: the attribute OPERATION is a safety property (SUPERSEDED)
> **Renamed to Embrace the Moon and retuned to -78%** — see the section above.
> The reasoning below still holds; its NUMBERS are the old -65% version and
> are kept only to show how much the stacking margin shrank.

Ships at **-65% via `ADD_MULTIPLIED_TOTAL`**, giving gravity **0.028** (35% of
vanilla's 0.08). Apex numbers from the same per-tick simulation that reproduces
vanilla's known 1.2522-block jump:

| | gravity | apex | airtime | terminal velocity |
|---|---|---|---|---|
| vanilla | 0.0800 | 1.252 | 12t | 3.92/t |
| Moon Walker (Tier 1) | 0.0560 | 1.657 | 16t | 2.74/t |
| **Extreme Gravity** | **0.0280** | **2.866** | **29t** | **1.37/t** |

So it clears a **2.5-block ledge** where Moon Walker clears 1.5 — **173% of Moon
Walker's apex** and nearly double the airtime. Distinct mechanics, not two points
on one slider.

**The operation differs from Moon Walker's deliberately, and this is the durable
finding.** Verified in `AttributeInstance.calculateValue`'s bytecode:

- `ADD_MULTIPLIED_BASE` accumulates **additively** (`value += base * amount`), so
  two reductions sum: -30% and -65% together are -95%.
- `ADD_MULTIPLIED_TOTAL` composes **multiplicatively** (`value *= 1 + amount`),
  which for any amount > -1 **can never reach zero however many stack**.

That matters because **`GRAVITY`'s clamp gives no protection in the dangerous
direction** — its floor is -1.0, so `sanitizeValue` passes 0.0 straight through
(float forever, no way down) and negative values through unchanged (launched
upward permanently). Anti-stacking normally keeps two MOVEMENT effects apart, but
it is a *soft* rule dropped first when the pool empties, so the pairing is
genuinely reachable. Measured:

| stacked with Moon Walker | gravity | apex | airtime |
|---|---|---|---|
| as shipped (`TOTAL`) | 0.0196 | 3.755 | 40t |
| had it used `BASE` | 0.0040 | **9.891** | **150t** (7.5s) |

**Any future gravity effect should use `ADD_MULTIPLIED_TOTAL`.** Moon Walker is
left on `BASE` on purpose — it ships, it is tuned, and changing it would alter an
effect already played.

#### Giant Size: SCALE exists, and vanilla will NOT save you from it
**Verdict: buildable, no blocker.** `minecraft:scale` is a real registered
`RangedAttribute` — **default 1.0, min 0.0625, max 16.0** — read out of the live
registry, not a wiki. It is granted by `LivingEntity.createLivingAttributes()`,
so **players have it**; confirmed by querying
`DefaultAttributes.getSupplier(EntityType.PLAYER)` directly rather than reasoning
from the builder chain. 5x sits well inside the range. No mixin, no faked scaling.

It is also **client-syncable**, so model, hitbox and camera height follow with no
payload of this mod's own.

**The apply-time collision risk is real, and this is the finding that mattered:**

> `Entity.refreshDimensions()` calls `fudgePositionAfterSizeChange(...)` — the
> routine that nudges a grown entity out of blocks it now overlaps — **only after
> an `instanceof Player` check that branches past it for players.** Verified in
> bytecode.

So vanilla explicitly declines to rescue a player who grows inside terrain.
Growing 5x in a 2-block corridor leaves them embedded and suffocating, caused
entirely by the mod. `GiantSizeBehavior.afterApply` mitigates: refresh
dimensions, and if the new bounding box collides, search **upward** a block at a
time (bounded, `MAX_LIFT_BLOCKS = 8`) for a free position and `snapTo` it.

- **Idempotent**, which is mandatory — a player already free collides with
  nothing and is not moved. `apply` runs again on every respawn and rejoin.
- **Upward only**: downward pushes into the ground, sideways has no principled
  search order.
- **Giving up is a valid outcome.** Walled in deep underground, the player
  suffocates — the honest consequence of becoming enormous in a tunnel. Logged,
  not silently swallowed.

#### The survival batch — 3 effects, plus Embrace the Moon's second fall mechanic
Registry is now **47 effects: 23 GOOD, 24 BAD**.

| Effect | Phase / category | Entropy | Mechanism |
|---|---|---|---|
| Slippery Grip | BAD / MOVEMENT | 25-50 | two `setSprinting` mixins |
| Glass Cannon Pact | GOOD / COMBAT | **40-60** | two attributes |
| Second Chance | GOOD / SURVIVAL | 25-50 | `checkTotemDeathProtection` mixin + run flag |

Glass Cannon Pact sits **above the rest of Tier 2** deliberately: the health cost
is permanent and compounds with whatever a long run has already accumulated.

#### Embrace the Moon now has BOTH fall mechanics, and they don't collide
The doubled `SAFE_FALL_DISTANCE` threshold is joined by a **-50%
`FALL_DAMAGE_MULTIPLIER`**. Stacked, not swapped: the threshold decides *whether*
there is damage, the multiplier scales *how much*.

| fall | vanilla | threshold only | as shipped |
|---|---|---|---|
| 6 blocks | 3 | 0 | **0** |
| 10 blocks | 7 | 4 | **2** |
| 20 blocks | 17 | 14 | **7** |
| 40 blocks | 37 | 34 | **17** |

**Three effects now write to `FALL_DAMAGE_MULTIPLIER`** — this, Featherlight and
Glass Jaw — and no-repeat permits holding them together. Checked on a real
`AttributeInstance` rather than reasoned about:

- **They cannot clobber each other.** Each effect's modifier is keyed on its own
  `Identifier` (`entropymod:effect/<id>`), so the instance holds three distinct
  modifiers and vanilla's `calculateValue` combines all of them.
- **No double-application.** `addOrUpdateTransientModifier` replaces by id, so ten
  re-applications leave *two* modifiers, not twenty — asserted directly.

**The operation differs on purpose, and it matters.** Featherlight (-0.40) and
Glass Jaw (+0.40) are `ADD_MULTIPLIED_BASE`, accumulating additively — exact
inverses that cancel to 1.0, which is their design. The new term is
`ADD_MULTIPLIED_TOTAL`, applied after that pool:

| held | multiplier | effective |
|---|---|---|
| this alone | 1.0 × 0.5 | **0.50** |
| + Featherlight | (1 − 0.4) × 0.5 | **0.30** |
| + Glass Jaw | (1 + 0.4) × 0.5 | **0.70** |
| + both | (1 − 0.4 + 0.4) × 0.5 | **0.50** |

As `ADD_MULTIPLIED_BASE` it would have landed at **0.10** with Featherlight — a
90% reduction neither effect claims. Note the stacking *risk* here is nothing
like `GRAVITY`'s: this attribute floors at 0.0, which is merely full immunity.

#### Slippery Grip: refuse the transition, don't repair it afterwards
The hook is **`LivingEntity.setSprinting(boolean)` with the argument forced
false**. Both alternatives were investigated and both are worse:

- **Clearing the sprint key in the input record does not work.** `LocalPlayer`
  starts a sprint from three distinct triggers — the sprint key,
  double-tap-forward, and the sprint toggle — and it calls `setSprinting` from
  **four** separate places. The latter triggers never consult the key bit, so
  intercepting it would block one path of three and look intermittently broken.
- **Force-clearing a flag every tick works but is worse.** It leaves a one-tick
  sprint window every tick, and since the client decides sprinting locally and
  applies its own speed modifier, the client would believe it is moving faster
  than the server allows — rubber-banding.

`LivingEntity` is the right level: it **overrides** `Entity.setSprinting`, and
neither `Player` nor `ServerPlayer` overrides it again, so one target covers the
whole chain. It is also where vanilla adds and removes
`SPEED_MODIFIER_SPRINTING`, so passing `false` makes **vanilla's own code** strip
the speed bonus — nothing is undone by hand.

**There are two mixins and the client one is load-bearing.** `EffectHooks`
answers "no effect" client-side by design, so the common mixin cannot see the
effect there; without the client twin (reading `ClientRunState`, which
`ClientEffectsPayload` already populates) the client would apply a sprint speed
modifier the server does not have. **Contrast Slashed Pockets**, whose client gap
is cosmetic — this one is not.

Both are `@ModifyVariable`, deliberately: they **chain**, each independently
forcing false. Two cancellable `@Inject`s would race, since the first to cancel
stops the rest.

#### Second Chance: ride the Totem of Undying's own escape hatch
The hook is **`LivingEntity.checkTotemDeathProtection(DamageSource)`**. Its
caller in `hurtServer` is, javap-verified:

```java
if (this.isDeadOrDying()) {
    if (!this.checkTotemDeathProtection(source)) {
        ... death sound, die(source) ...
    }
}
```

So **returning `true` skips the death branch outright** — no death message, no
drops, no respawn screen — because vanilla's own control flow says so, rather
than because each was suppressed individually.

**Two consequences that decide the implementation:**

- **Restoring health is mandatory, not decoration.** This runs *after* health has
  been reduced to zero. A `true` return with no `setHealth` leaves the player
  alive on an empty bar and dead again next tick. Vanilla's own totem branch does
  the same `setHealth` for the same reason.
- **The `BYPASSES_INVULNERABILITY` check is kept**, mirroring vanilla's own first
  line: `/kill` and the void still kill. An effect that survived `/kill` would be
  a debugging trap.

Injected at HEAD and cancelling, so Second Chance is checked **before** a totem in
hand — the run's one-shot is spent and the totem is kept. `checkTotemDeathProtection`
is `private`, so the subclass-override trap cannot apply; it does run for every
living entity, which is what the `instanceof Player` guard is for.

**"Once per run" is a run flag, not the acquired set** — one
`optionalFieldOf("second_chance_used", false)` in `EntropyManager`'s existing
codec, exactly the store and shape Second Guess uses. Consuming does two things
and **only the first enforces "once"**:

1. The persisted flag is set. Survives respawn, relog and save/reload, and
   **re-acquiring cannot refund it**.
2. The id is dropped from `AcquiredEffects` — presentation, not enforcement, and
   it deliberately makes the effect eligible to be offered again, which the flag
   renders inert.

A design resting on the acquired set alone would eventually be wrong: the repeat
fallback can legitimately re-offer an already-taken effect once a phase's pool
empties. All of this is asserted, including that an *unspent* Second Chance also
survives a reload — the flag is not quietly defaulting to spent.

**`AcquiredEffects.remove` is new and is not a general un-pick.** Effects are
permanent; it exists for the one shape that consumes itself. Do not reach for it
to build a temporary effect.

#### Glass Cannon Pact: the health floor is safe, and stays safe stacked
`MAX_HEALTH` floors at **1.0**, not 0 — `sanitizeValue(0)` and
`sanitizeValue(-100)` both return 1.0. So unlike `GRAVITY`, whose -1.0 floor makes
over-stacking catastrophic, **stacked health penalties bottom out harmlessly at
half a heart** and the attribute system can never kill the player by itself.

Checked explicitly against Brittle Bones rather than by analogy:

| held | sum | max health |
|---|---|---|
| vanilla | 0 | 20.0 (10 hearts) |
| this alone | -2.0 | 18.0 (9 hearts) |
| Brittle Bones alone | -4.0 | 16.0 (8 hearts) |
| **both** | **-6.0** | **14.0 (7 hearts)** |

They are in **different categories** (COMBAT and SURVIVAL), so anti-stacking does
not separate them — the pairing is ordinary, not an edge case. Twenty stacked
would still leave the player alive, asserted.

Current health is clamped on apply, same as Brittle Bones: lowering max health
does not lower current health, so a player at 20/20 would otherwise display more
hearts than they have. **`ATTACK_DAMAGE` is not client-syncable**, so the tooltip
will not show the +50% — the same known cosmetic gap Steady Hands has.

#### Extreme Gravity is now EMBRACE THE MOON — renamed, and much bigger
The id changed from `extreme_gravity` to `embrace_the_moon`, so a save from
before the rename carries an id this build no longer defines. Handled like any
unknown id — skipped with a warning at load, rest of the run intact — exactly as
when `heavy_footsteps` became `exposed`.

It is now three attributes rather than one: gravity, jump strength, and safe fall
distance.

| case | gravity | jump | apex | airtime | terminal v |
|---|---|---|---|---|---|
| vanilla | 0.0800 | 0.42000 | 1.252 | 12t | 3.92/t |
| Moon Walker (Tier 1) | 0.0560 | 0.42000 | 1.657 | 16t | 2.74/t |
| *old* Extreme Gravity (-65%) | 0.0280 | 0.42000 | 2.866 | 29t | 1.37/t |
| gravity change alone (-78%) | 0.0176 | 0.42000 | 4.065 | 44t | 0.86/t |
| **as shipped, with the jump bonus** | **0.0176** | **0.44940** | **4.567** | **47t** | **0.86/t** |

A jump clears about **4.5 blocks** and hangs for 2.35 seconds — **276% of Moon
Walker's apex**.

**The jump bonus is +7% on the attribute, which is +12.35% apex.** Apex goes
roughly as the *square* of launch velocity, so taking the wanted apex percentage
as the attribute percentage overshoots by about double. Solved backwards against
the real integration: +10% apex needs +5.68%, +15% needs +8.48%.

#### `SAFE_FALL_DISTANCE` is a real attribute — the threshold, not the multiplier
This was an open question and it resolved cleanly: **no mixin, nothing faked.**
It is a registered `RangedAttribute` (**default 3.0, min -1024, max 1024**),
present on the player, and its consumer is verified in bytecode:

```
calculateFallPower(d)        = d + 1e-6 - getAttributeValue(SAFE_FALL_DISTANCE)
calculateFallDamage(d, mult) = floor(calculateFallPower(d) * mult
                                     * getAttributeValue(FALL_DAMAGE_MULTIPLIER))
```

**So it is genuinely a different mechanic from `FALL_DAMAGE_MULTIPLIER`**, which
scales what is left *after* the subtraction and is what Featherlight and Glass
Jaw use. Doubling 3.0 → 6.0:

| fall | vanilla | with this |
|---|---|---|
| 3 blocks | 0 | 0 |
| 6 blocks | 3 | **0** |
| 7 blocks | 4 | 1 |
| 20 blocks | 17 | 14 |

The reduction is a **constant 3 damage at every height** — that is the signature
of a moved threshold rather than a scaled remainder, and the harness asserts it
specifically so the two mechanics cannot be quietly conflated later.

#### The gravity stacking margin did NOT survive the increase — recheck, don't inherit
**This is the durable lesson from the retune.** Last session established that
`ADD_MULTIPLIED_TOTAL` was safer than `ADD_MULTIPLIED_BASE` for gravity. It was
tempting to treat that as settled and move on. Recomputed at -78%:

| operation | stacked with Moon Walker | outcome |
|---|---|---|
| **ADD_MULTIPLIED_TOTAL (shipped)** | **+0.01232** | apex 5.8, airtime 63t — floaty, playable |
| ADD_MULTIPLIED_BASE | **-0.00640** | **NEGATIVE — launched upward, permanently** |

At the old -65% the additive form still landed at **+0.004** — barely positive,
but positive. At -78% it **crosses zero**. `GRAVITY`'s clamp floors at -1.0, so
`sanitizeValue` passes a negative straight through. Multiplicative composition
cannot reach zero for any amount greater than -1, whatever else stacks.

**The rule: a safety margin computed at one magnitude is not evidence about
another.** Both the old and the new numbers are asserted, so the harness records
that the margin genuinely shrank rather than merely restating the conclusion.

#### Giant Size grew a full kit — and one part of it is a repair, not a buff
5x `SCALE` and the upward-rescue mitigation are unchanged. Added: **+10 hearts**,
**2-block step-up**, **double jump**, **-25% damage taken**, **+6.5 reach**.

- **`STEP_HEIGHT` is a real player attribute** (default 0.6, min 0.0, max 10.0),
  and `LivingEntity.maxUpStep()` is literally
  `getAttributeValue(Attributes.STEP_HEIGHT)`. No mixin. Set to exactly **2.0**.
  Note `Entity.maxUpStep()` returns a hardcoded `0.0f` — `LivingEntity` overrides
  it, so the attribute only reaches living entities.
- **Damage reduction rides Iron Skin's existing hook.** One term added to
  `EffectHooks.damageTakenMultiplier`, no parallel mechanism. Composes
  multiplicatively: with Iron Skin it is 0.60, with Fragile 0.9375, and it can
  never reach zero. Asserted, including that no second damage hook was added.

#### Doubling jump height is NOT ×√2 — the drag eats the difference
| approach | jump strength | apex | ratio |
|---|---|---|---|
| intuitive ×√2 (+41.42%) | 0.59397 | 2.324 | **1.856x** |
| **derived (+47.222%)** | **0.61833** | **2.504** | **2.0000x** |

The closed form `v²/2g` says √2. The real per-tick integration includes a `0.98`
air drag that costs proportionally more at higher launch velocity, so √2 lands
**7% short**. The harness solves the integration backwards and asserts the
shipped constant matches — copy that approach for any future jump tuning rather
than reaching for the closed form.

#### SCALE does NOT extend reach — it actively BREAKS it
Investigated to decide whether a reach bonus was a buff or a necessity. Three
facts, all javap-verified:

- `Player.entityInteractionRange()` and `blockInteractionRange()` are **each a
  bare `getAttributeValue(...)` and nothing else.** No scale term anywhere.
- `EntityDimensions.scale(f)` multiplies **`eyeHeight`** along with width and
  height, so at 5x the eye sits **8.1 blocks** above the feet (1.62 × 5).
- `isWithinBlockInteractionRange` measures from **`getEyePosition()`**.

**So an unmodified 5x player cannot reach the block they are standing on** — 8.1
blocks away against a default reach of 4.5. Not a difficulty; they could not
mine, place or open anything at ground level.

The bonus is sized from that: the eye rose by 6.48 blocks, so reach is extended
by **+6.5** to restore vanilla-equivalent reach *relative to the giant's own
body*. Final: block 11.0, entity 9.5, both far below the 64.0 ceiling.

**This is larger than the +2 originally specified, deliberately** — +2 gives 6.5,
still short of the 8.1 needed, so the effect would have shipped unable to touch
the ground. One constant (`REACH_BONUS`) if a smaller number is wanted.

**General lesson: any future effect that changes `SCALE` must also consider
reach**, because raising the eye silently shortens every interaction.

#### `AttributeEffectBehavior` now takes several attributes
Both reworked effects are multi-attribute (3 and 6), which the base class did not
support. Rather than have them implement `EffectBehavior` directly and re-type
the idempotency rules, the base class grew a `List<Change>` constructor — the
"fix the abstraction rather than the caller" case CLAUDE.md already calls for.
**Every existing single-attribute subclass is untouched**; the old constructor
delegates.

**The same modifier `Identifier` is used for every attribute of one effect, and
that is correct rather than a collision** — modifier ids only need to be unique
*within one `AttributeInstance`*, and each attribute has its own.

The harness now drives idempotency **per change**, not just for the first one:
ten applications, value unmoved, exactly one modifier, and removal restores the
base. "Idempotent" for a multi-attribute effect has to mean all of them.

#### Behemoth Gauntlets: the hook, and why "unarmed" is broader than it sounds
+20 flat bare-handed, x0.25 with anything in hand. **No attribute can express
this** — `ATTACK_DAMAGE` is one number read once per swing and already *includes*
the held weapon's own modifier, so no value of it is both.

The hook is a `@ModifyArg` on `Player.attack`'s call to
`Entity.hurtOrSimulate(DamageSource, float)`, `index = 1`. javap-verified as
occurring **exactly once** inside `attack` (so `defaultRequire: 1` is
unambiguous), and it sits after the attribute, cooldown scale, enchantments and
crit multiplier — so this composes with all of them.

**The discriminator is `getWeaponItem().isEmpty()`, i.e. the main-hand stack. So
holding *anything* — a sword, a pickaxe, a stack of dirt — counts as armed.**
Deliberate: it is the only distinction available at that point that does not
require inventing a definition of "weapon", and it makes the effect legible.

**Known limit:** the sweep attack deals damage through a separate
`doSweepAttack` call and is not scaled — which only arises in the armed case the
effect pushes players away from anyway.

#### ServerPlayer.hurtServer is the right damage target — and hurtServer is overridden TWICE
Flamboyant and Crouch Invincibility both target **`ServerPlayer.hurtServer`**,
not `LivingEntity.hurtServer` where Iron Skin and Fragile live. The
subclass-override trap is live here and was checked rather than assumed:

- **`ServerPlayer` overrides `hurtServer`, and `Player` overrides it again.**
  Both do call through (`ServerPlayer` -> `Player` -> `Avatar` ->
  `LivingEntity`), which is why the existing Iron Skin mixin works at all — but
  **`ServerPlayer.hurtServer` has three early returns before it reaches that
  super call**, so only the outermost override sees every hit.
- Targeting the player class directly makes both effects player-only by
  construction, with no `instanceof` guard to forget.
- `ServerPlayer` is concrete with no subclass in the jar.

**Flamboyant amplifies rather than killing directly.** The fire damage is raised
to `maxHealth x 1000` so vanilla keeps ownership of the death: cause-specific
death message ("burned to death", "tried to swim in lava"), sound, drops,
statistics, advancements. A direct `kill()` would report a generic death for a
very specific cause. Scaled from max health, not a fixed number, so armour
(80% cap), Resistance (a further 80%) and Iron Skin (20%) applied downstream
cannot leave the player on a sliver. Finite on purpose — `Float.MAX_VALUE` risks
non-finite arithmetic in vanilla's own damage maths.

Gated on the vanilla tag **`#minecraft:is_fire`**, whose shipped contents were
read out of the jar: `in_fire, campfire, on_fire, lava, hot_floor,
unattributed_fireball, fireball`. The harness asserts membership *and*
non-membership of `fall`, `drown`, `mob_attack`, `player_attack`, `cactus`,
`out_of_world`, `explosion`, `starve` and `magic` — "kills you on any damage" is
the obvious way to get this wrong and would be indistinguishable in casual play.

**Crouch Invincibility gates on `isCrouching()`, not `isShiftKeyDown()`** —
vanilla's pose-derived state, matching what the player sees, rather than the raw
input flag. The two disagree when a player is crouched under a 1-block gap or
shifting while forced upright. Evaluated per hit rather than latched, which
falls out of it being a `HookEffectBehavior` with a final empty `apply` — there
is no per-player state that *could* latch "has sneaked" into "is sneaking".

**Interaction, deliberate:** a run holding both survives fire while crouching.
The invincibility check cancels at HEAD before the amplification can reach a
player who is taking zero anyway. That is the coherent reading of "zero damage
from any source", and it is asserted so it cannot silently invert.

#### Slashed Pockets: which slots, and why three enforcement points
The 36 main slots are four rows of nine. Top to bottom on screen:

| row | slots | locked |
|---|---|---|
| storage, top | 9-17 | **yes** |
| storage, middle | 18-26 | **yes** |
| storage, bottom | 27-35 | no |
| hotbar | 0-8 | no |

So **"the upper half" is slots 9-26** — the top two rows as displayed, exactly 18
of 36. **The reading is recorded because it is not the only possible one:** "half
the inventory" could have meant half of the 27-slot storage area, which is 13.5
slots and would cut a row in two. Splitting on a row boundary at the true halfway
point of the whole main inventory is the only reading that is both exactly half
and visually clean. Equipment is outside the range by construction
(`SLOT_OFFHAND = 40`, `SLOT_BODY_ARMOR = 41`, `SLOT_SADDLE = 42`).

Three enforcement points, because no one of them is sufficient:

- **`Slot.mayPlace`** — refuses manual placement and shift-clicks. Injected at
  RETURN so it can only ever *remove* permission. Scoped by
  `container instanceof Inventory`, so chests and furnaces are untouched.
- **`Inventory.getFreeSlot`** — keeps automatic pickup out. **Without this the
  sweep would be doing all the work, visibly:** items would land and be spat back
  out a tick later, so walking over a pile of drops would make them bounce.
- **`SlashedPocketsSweep`, a per-tick service** — the backstop, and the only one
  that makes "inaccessible" unconditional. Commands, other mods and any vanilla
  path calling `setItem` bypass the first two. Third shape alongside attribute
  and mixin, same as `GreenThumbGrowth`; costs one hash lookup for players
  without the effect.

The one-time drop is gated on `isFreshPick()` — **not** on the slots being empty.
`apply` runs again on every respawn and rejoin, and re-running the drop there
would be harmless only *by accident* (because the sweep keeps them empty), which
is a poor reason for a side effect to be correct.

**This is the one effect in the batch where the client would benefit from
knowing.** `EffectHooks` returns "no effect" client-side, so client-side
prediction still permits the placement and the server corrects it — the item
snaps back. Cosmetic only: the server is authoritative and the sweep is
unconditional. The clean fix, if it ever grates, is a client-side reader of
`ClientRunState` for `mayPlace`; deliberately not built, since it would mean two
mixins racing on one method for one frame of polish.

#### `/entropygrant` is NOT gated by the run-lifecycle state
Confirmed in bytecode: `grantEffect` contains **zero** references to `runState`,
so it works exactly the same before and after Start — the same way it already
bypasses entropy and pick count.

**But in practice you must click Start first anyway**, for a different reason:
the start panel is modal, `shouldCloseOnEsc()` is false, and a client-tick guard
re-opens it whenever the state is `NOT_STARTED`. Chat is therefore unreachable
until the run starts, so the command cannot be *typed*. Granting from a server
console while `NOT_STARTED` does work — which is exactly the Randomized Controls
edge case recorded above.

#### The headless harness can now load mod classes — `HarnessBootstrap`
CLAUDE.md described this before it existed as a file; it exists now, in
`src/harness`. Plain `Bootstrap.bootStrap()` is **not** enough: vanilla freezes
`BuiltInRegistries` at the end of it, and merely touching `EffectBehaviors`
constructs `MagneticBootsBehavior`, which class-inits `EntropyAttributes`, which
registers into a frozen registry and dies with `ExceptionInInitializerError`.

`HarnessBootstrap.init()` reproduces Fabric's ordering: bootstrap, reflectively
unfreeze `ATTRIBUTE`, register, then **bind the holders** — reading the value out
of `MappedRegistry.byValue` directly, because every public accessor that would
bind a `Holder.Reference` routes through the unbound `value()` call you are
trying to fix. It is idempotent so each section can call it without coordinating.

This unlocked the check that matters most: **idempotency against the real
`AttributeInstance`** — apply ten times, assert the value has not moved and that
exactly one modifier exists rather than ten stacked copies.

`Checks.classReferences(Class, String)` is also new: it scans a compiled class's
constant pool for a name. It is the tool for "*which* vanilla method does this
hook call" when calling it for real would need a live entity — reflection cannot
tell two boolean-returning methods apart, but the callee's name is a UTF8 entry
in the caller's constant pool. It can prove presence or absence, not location.

#### Debug output has to reach the player, not just the log
`/entropyhistory` was reported as a hard regression — "prints nothing in-game
despite picks having been made" — and was suspected to be a `SavedData` migration
bug. **It was neither a regression nor a persistence bug.** The log showed the
full round trip working perfectly: `History requested by Player30 -- 11 pick(s).`
followed by all 11 entries. The command and its client receiver only ever called
`LOGGER.info`, so nothing was sent to chat and the player had no way to see it.

Both paths now send to chat (the failure branch too, which was also log-only, so
an unsupported server was indistinguishable from a working silent one). The rule:
**a debug command whose output only goes to the log is indistinguishable from a
broken one.** If a command is meant to be run in game, it must answer in game.

#### Registering a custom attribute — and the crash it nearly shipped
`entropymod:pickup_range` (`RangedAttribute`, default **1.0**, min 0, max 16,
syncable) is registered in `onInitialize` and added to the player in
`PlayerAttributesMixin` via `Player.createAttributes()`. Its default of 1.0 is
load-bearing: **a player without Magnetic Boots gets vanilla pickup by
construction**, because the mixin multiplies by exactly 1.0 rather than checking
a flag.

**Vanilla freezes `BuiltInRegistries` at the end of `Bootstrap.bootStrap()`.**
Mod registration works only because Fabric's registry-sync `BootstrapMixin`
replaces that freeze — in a method literally named **`delayRegistryFreeze()`** —
so mods can register during `onInitialize`. Two consequences, both real:

1. **Do not dereference a `Holder.Reference` during `onInitialize`.** The value is
   not bound until the (delayed) freeze, so `PICKUP_RANGE.value()` throws
   `IllegalStateException: Trying to access unbound value` and takes mod init down
   with it. The first version of `EntropyAttributes.register()` logged
   `PICKUP_RANGE.value()` and **the headless harness caught it as a hard startup
   crash before it ever ran in game.** Log the `Identifier` instead; anything
   needing the `Attribute` must wait until the game is running, which every
   `EffectBehavior` already does.
2. **Headless harnesses now need a prologue.** Plain `Bootstrap.bootStrap()` is no
   longer enough: touching `EffectBehaviors` constructs `MagneticBootsBehavior`,
   which class-inits `EntropyAttributes`, which dies on the frozen registry. The
   shared `HarnessBootstrap.init()` reproduces Fabric's ordering — bootstrap,
   reflectively unfreeze `ATTRIBUTE`, register, then bind the holder (read the raw
   value out of `MappedRegistry.byValue`, since every public accessor routes
   through the unbound `Holder.Reference.value()` you are trying to bind).

### Pick history — BUILT
`PickRecord` (pick number, phase, effect id/name/description, entropy at the
time) appended in `onChoiceMade`, exposed via `EntropyManager.getHistory()`.

- `entropyAtPick` is the value **before** that pick's +1 — i.e. the number the
  player was looking at when they chose.
- Name and description are stored as strings rather than an id reference on
  purpose: history records what the player actually saw, so it must survive an
  effect being retuned or deleted from the registry in a later version.
- Fetched **on demand** via `HistoryRequestPayload` (C2S, zero bytes) →
  `HistoryResponsePayload` (S2C, list), answered only to the player who asked.
  Deliberately not ridden along on `OpenChoicePayload`, which every pick pays
  for.
- No screen or keybind yet. `/entropyhistory` requests it and the client prints
  it to chat — interim path, replace with a real screen. It logged *only* until
  the diagnostic session, which is why it looked broken in game; see "Debug
  output has to reach the player".
- `EffectPhase`'s wire codec now lives in `network/EntropyCodecs.java` since
  two payloads need it. Sent by name, not ordinal, so reordering the enum
  can't flip GOOD and BAD on the wire.

### Debug commands — know which ones are REAL and which are FAKE
This distinction has already cost one session. Read it before adding another
test command.

| Command | Side | Real pipeline? |
|---|---|---|
| `/entropyforcepick` | server | **YES** — the real path |
| `/entropygrant <effect_id>` | server | **YES** — the real acquisition path |
| `/entropystatus` | server | **YES** — reads real manager state |
| `/entropyhistory` | client → server | **YES** — real request/response, prints to chat |
| `/entropypreview` | client only | **NO** — hardcoded fake data |

#### `/entropygrant` is the standard way to test a specific effect
**Use this before building anything that depends on an effect working.** Rolling
until the one effect you want to test happens to be offered is the friction that
made the last two sessions ship effects that were never observed in game; this
removes it. `/entropygrant green_thumb` and it is on, immediately.

It is a **real** path, held to the same standard as `/entropyforcepick`:
`EntropyManager.grantEffect` writes to the same `AcquiredEffects`, calls the same
`setDirty()`, and dispatches through the same private `applyToAll` that
`onChoiceMade` uses. A granted effect therefore persists across a save, is
re-applied on death/rejoin/dimension change, and occupies its category for
anti-stacking — indistinguishable from having picked it. If a future edit makes
it construct its own `EffectContext` or call a behavior directly, that is a bug.

Three properties that are deliberate, not incidental:

- **It does not advance the run.** Entropy, pick count, the interval timer and
  the history list are untouched. This is checkable rather than asserted:
  `grantEffect`'s bytecode contains **one** call to `applyToAll`, one to
  `acquired.add`, one to `setDirty`, and **zero** references to the `entropy`,
  `pickCount`, `history` or `tickCounter` fields.

  ```bash
  javap -p -c -cp build/classes/java/main com.entropymod.entropy.EntropyManager \
    | awk '/GrantResult grantEffect/{f=1} f&&/^$/{exit} f'
  ```

- **Re-granting is rejected, not re-applied.** Silently applying an
  already-acquired effect a second time would exercise — and therefore could
  hide — exactly the idempotency guarantee the whole respawn/rejoin design rests
  on. The debug tool must not mask the class of bug it exists to find.
- **A bad id is rejected out loud**, with the full list of valid ids sent to
  chat, and the argument tab-completes from `EffectRegistry` live.

**`/entropyforcepick` is the one that actually tests things.** It calls
`EntropyManager.forcePick`, which delegates straight to the private
`triggerPick` the tick loop uses — same entropy-cap check, same interval-scoped
expiry, same phase alternation, same anti-stacking roll, same
`OpenChoicePayload`. It bypasses the clock and *nothing else*. Its guards
(`gameOver`, `waitingOnChoice`) are exactly the ones `tick()` applies before it
would reach `triggerPick`, so a forced pick can't reach a state a real interval
firing couldn't. It returns a `PickTrigger` so the command can say *why* nothing
opened instead of failing silently.

**If a future session makes this command roll its own choices or build its own
payload, that is a bug**, not an optimisation — it would stop testing the thing
it exists to test.

Both server commands need permission level 2 (`LEVEL_GAMEMASTERS`), so **in
singleplayer the world must have Allow Cheats: ON** or they won't appear.

**Why `/entropypreview` got renamed (the trap, recorded so it isn't reset).**
It used to be called `/entropytest`, and the name was actively misleading: it
opens `ChoiceScreen` client-side with three hardcoded effect strings and never
contacts the server. Clicking a card in it sends no `ChoiceMadePayload`, so it
never reaches `onChoiceMade` — no history entry, nothing added to
`AcquiredEffects`, no anti-stacking, no `EffectBehavior` firing. On screen it is **indistinguishable
from a real pick**, which is exactly what made it dangerous: the effect-behavior
architecture session shipped with the reasonable-sounding belief that
`/entropytest` could verify it, and it could not.

It still exists, deliberately — instant colour/layout checks across the whole
entropy range with no world state are genuinely useful, and that's worth a
command. The fix was the name, not the feature.

**The general rule: a debug shortcut that fabricates data must say so in its
name.** If you add another, name it `preview`/`mock`/`fake`, and if it's meant
to test real behavior, make it call the real entry point rather than
reconstructing what the real entry point does.

### How to check this without booting Minecraft
**`./gradlew harness` — the harness is in the repo now** (`src/harness/java`,
its own source set, not wired into `build`). Every previous session rebuilt an
equivalent by hand in a scratch directory and threw it away, which is why the
same numbers kept being re-derived. 547 checks currently: the tuning constants as
actually compiled, the vanilla crop-growth model, Green Thumb's active schedule
and its per-crop intervals, Green Thumb's immunity to Blight Touched's rewrite,
Blight Touched's path sweep and its off-by-default gate, Tier 2's movement-scramble
model and its assign-once persistence, the crop-schedule
tracking rules, `/entropygrant`'s contract, Clumsy Digger's whole per-tier
durability table and its tools-only scope gate, Bad Reputation's whole price
table, `OpenChoicePayload`'s codec round-trip, the run-start gate and its
save migration, the keybind snapshot's write-once rule, and an assertion that the
`ENDED` half of the run lifecycle is genuinely absent rather than half-present,
and the whole Tier 2 content batch -- its registration and wiring, Extreme
Gravity's physics and stacking safety, Giant Size's SCALE range, Behemoth
Gauntlets' two branches driven individually, Flamboyant's fire-only gate against
the shipped tag, Crouch Invincibility's sneak test, Slashed Pockets' slot range,
and attribute idempotency against a real `AttributeInstance`.

**The start gate is tested by ticking with a `null` server, and that is the
assertion rather than a shortcut.** `triggerPick` dereferences the server almost
immediately, so a gate that let the loop run would blow up rather than quietly
passing — and the loop is ticked 500 past the interval length to guarantee it
would be reached. The counter staying at 0 is checked separately, because that is
what distinguishes "paused" from "counting invisibly".

**Clumsy Digger's table reads real durabilities out of `ToolMaterial` rather
than hardcoding them** (`ToolMaterial.NETHERITE.durability()`, etc.), which is
what makes it a check rather than a restatement — and it is how the COPPER tier
was noticed. No bootstrap is needed: `ToolMaterial`'s constants are plain records
over `TagKey`s. Copy that approach for anything else keyed on vanilla item data.

**The payload round-trip is the model to copy for any future payload change.** It
runs the real `StreamCodec` against `RegistryAccess.EMPTY` — no bootstrap needed,
because every component codec is a string, varint or enum-by-name — and it checks
**field by field with distinct values**, not with a whole-record `equals`. That
matters concretely here: `entropy` and `entropyCap` are both ints and adjacent, so
a swap would cancel out under equality and pass.

It has already earned its keep once: an earlier session's first run failed on a
hand-derived expected value for blighted wheat (the roll bound at speed 2.5 is
11, not 12). The model was right and the number written next to it was not.

**`Checks.hasMethod` is the counterpart to `Checks.hasConstant`** — it asserts a
retired *code path* was deleted rather than left returning a neutral value. Both
crop-hook retirements are pinned that way. Reach for it whenever a session removes
a mechanic rather than changing one.

Two things about it worth keeping:

- **It reads constants by reflection, never by reference.** `static final`
  primitives are inlined at the *caller's* compile time, so a harness that named
  `GreenThumbBehavior.MULTIPLIER` directly would compare its own compiled-in
  snapshot against itself and pass regardless of what ships.
- **It is not in `build`.** A check that needs updating after a deliberate retune
  must not block a release build; `./gradlew build` and `./gradlew harness` are
  separate answers to separate questions.

`AcquiredEffects`, `PickRecord`, `EffectRegistry` and the whole data model are
**free of Minecraft imports on purpose** — same discipline as `EntropyPalette`
on the client side. A plain `javac`/`java -cp build/classes/java/main` harness
drives the no-repeat rule, both fallbacks, and pool exhaustion against the real
shipped classes. Do that rather than testing a copy.

For anything that *does* need Minecraft classes (codecs, attributes), a harness
can still run headlessly against the full runtime classpath — call
`SharedConstants.tryDetectVersion()` then `Bootstrap.bootStrap()` first or any
registry access throws "Not bootstrapped". That is how the attribute clamps in
this file were established, and how the idempotency claim below was proven
against the real `AttributeInstance` rather than a mock.

Three things are worth re-checking with a harness whenever they're touched:

1. **The no-repeat rule and its fallbacks**, including deliberately draining a
   phase's pool — that path is reachable in normal play, not an edge case.
2. **The persistence codec.** A broken one doesn't misbehave subtly, it makes
   the world fail to load. Round-trip it field by field with distinct values,
   and check that a save missing newer fields still loads.
3. **Idempotency.** Apply a modifier ten times and assert the value didn't move.
   This is the guarantee the whole respawn/relog design rests on.

What none of this can prove, and what still needs a real game session: that the
effects are *felt* (speed, hearts, mining rate), that the mixins actually inject
against the live game, and that a real death/relog restores everything exactly
once.

Payload changes still need the round-trip check described under "Entropy cap on
the wire" above; `HistoryResponsePayload` has been verified that way
field-by-field (a whole-record `equals` would still pass if two same-typed
fields were swapped in a way that cancelled out).

What this *cannot* prove, and what still needs a real game session: that the
chat messages actually appear, that expiry lands on the right wall-clock
moment in a live server tick loop, and that the history request survives a real
client↔server round trip. `/entropyforcepick` is what makes that session
practical — without it you'd be waiting out a 3-minute interval per pick.

One structural claim *is* checkable without the game, and worth re-checking if
`forcePick` is ever edited: that it delegates rather than duplicates. Its
bytecode should contain exactly one call, to `triggerPick`, and
`EntropyCommands` should reference nothing but `EntropyManager` accessors:

```bash
javap -p -c -cp build/classes/java/main com.entropymod.entropy.EntropyManager
javap -p -c -cp build/classes/java/main com.entropymod.command.EntropyCommands
```

Note `runServer` needs `run/eula.txt` with `eula=true`, which isn't in the repo
— accepting Mojang's EULA is the repo owner's call, not a tool's.

### Config (interval length, entropy cap) — fields exist, no player-facing surface
`EntropyManager.setIntervalTicks()` / `setEntropyCap()` exist and work, but
there's no world-creation config screen, no GameRule, no command, and no
config file wiring any of them up. Right now the only way to change these is
to edit code and rebuild. See Open Question 5.

### Persistence — see "Persistence — BUILT" above
This used to be listed here as an unbuilt gap. It is built: `EntropyManager` is
a `SavedData` and a run now survives a restart.

---

## Part 2: Design invariants already locked in (don't relitigate these casually)

- Categories for anti-stacking: `MOVEMENT, SURVIVAL, TOOL, COMBAT, GEAR,
  COMPANION, UTILITY, DEBUFF, META` — max 1 active effect per category,
  **enforced by exclusion from the roll pool, not by replacement** (a
  conflicting option never appears in the three cards; picking never silently
  cancels something you already have). *Now enforced in code.*
- Effect behavior is one class per effect (`EffectBehavior` implementations in
  `com.entropymod.entropy.behavior`), never a central switch. New effect =
  new file + two registration lines, zero edits to existing code.
- **All effects are permanent.** No durations, no expiry. `EffectDefinition` has
  no duration field and must not regrow one as a magic int.
- **`EffectBehavior.apply` must be idempotent** — it runs again on every
  respawn, rejoin, and dimension change.
- **No-repeat: an effect picked once never appears again that run.** Distinct
  from anti-stacking (id vs category) and outranks it.
- **`AcquiredEffects` is the single source of truth** for what the player has —
  active set, already-picked set, and category occupancy all read from it, and
  attribute state is derived from it rather than stored alongside it.
- Phase alternates strictly GOOD → BAD → GOOD → BAD, never two of the same
  phase in a row
- Bad effects below entropy 40 must be counterplay-survivable (no
  unavoidable-death effects until later tiers)
- Picking is mandatory — no way to dismiss/skip a pick without choosing
- Fabric is the confirmed loader choice (not Forge/NeoForge/CurseForge —
  CurseForge is a distribution platform, not a loader)
- Effects are data-driven (`EffectDefinition` records), never hardcoded into
  the timer/GUI/networking layer

---

## Run Lifecycle: first slice BUILT, ENDED half still design-only

**Read this status table before touching anything below it.** The architecture
was recorded as one design; only its front half exists.

| Piece | Status |
|---|---|
| `NOT_STARTED` / `IN_PROGRESS` states, persisted | **BUILT** |
| Hard tick-loop gate while `NOT_STARTED` | **BUILT** |
| Modal start panel with a Start button | **BUILT** |
| Keybind snapshot captured at Start | **BUILT** |
| Randomized Controls anchored to that snapshot | **BUILT** |
| Interval/cap settings **on** the start panel | **NOT BUILT** — deferred |
| `ENDED` state | **NOT BUILT** — the constant does not exist |
| End screen | **NOT BUILT** |
| Dragon-death win detection | **NOT BUILT** — still Open Question 1 |
| Become Hardcore (death → loss) | **NOT BUILT** |
| Run-wide death counter | **NOT BUILT** |

`./gradlew harness` asserts the unbuilt half is *absent* rather than
half-present — `RunState` has exactly two constants, and there is no `endRun()`
or `getDeathCount()`. That is deliberate: a later session must be able to tell
"deferred" from "started and abandoned".

### Run states — BUILT (the first transition only)

```
NOT_STARTED  ->  IN_PROGRESS        [built]
             ->  ENDED              [not built; no constant exists]
```

`RunState` deliberately declares **only two constants**. An `ENDED` constant
"ready for later" would invite branching on a state nothing can produce.

- **`NOT_STARTED`** — the loop does not advance entropy and cannot trigger picks.
- **`IN_PROGRESS`** — the loop runs as it always has.

Persisted **by name** in `EntropyManager`'s existing codec, same `optionalFieldOf`
discipline as every other field.

**The gate sits before `tickCounter++`, not before `triggerPick`**, and that
placement is the whole difference between "paused" and "running invisibly and
firing the instant the gate lifts". A player who spent ten minutes on the start
panel would otherwise eat an immediate pick. The harness asserts the counter
stays at 0 across 4100 gated ticks.

`forcePick` is gated identically and returns a new `PickTrigger.RUN_NOT_STARTED`,
because `/entropyforcepick` bypasses **the clock and nothing else** — it must not
reach a state a real interval firing could not.

**`gameOver` is NOT this and was left alone.** That flag is the older
entropy-cap stop. Folding the two together belongs with the unbuilt `ENDED` work.

#### Loading a save written before run states existed
`run_state` is the one codec field that is `optionalFieldOf` **without** a
default — an `Optional<String>` — specifically so the constructor can tell "this
save predates the feature" from "this save says `NOT_STARTED`". Absent migrates
to `pickCount > 0 ? IN_PROGRESS : NOT_STARTED`, so a world already mid-run is not
re-gated and asked to click Start again.

**The signal is `pickCount`, never the acquired set.** `pickCount` moves only in
`onChoiceMade`. `/entropygrant` adds effects without advancing the run and works
while `NOT_STARTED`, so inferring from `acquired` would read a granted effect as a
started run. Asserted directly.

### The start panel — BUILT, button-only

`StartScreen`, shown while the server reports `NOT_STARTED`.

- **Modal, same contract as `ChoiceScreen`**: `shouldCloseOnEsc()` false,
  `isPauseScreen()` true. Title + rule + wrapped body + button are measured and
  centred as **one block**, and the button's Y is additionally clamped to the
  window so that on a very short window the *text* clips and the only way out
  never does.
- **Settings are NOT on it.** Interval length and entropy cap sliders plus a
  settings channel and validation is materially more work than the gate itself,
  and the gate is what the keybind snapshot needed. **Open Question 4 is
  therefore still open** — the panel is its intended home and now exists to
  receive it.
- **Still deliberately NOT in vanilla's world-creation flow** — that rejection
  stands unchanged.

#### The gate needs a tick guard, not just a payload — this is a real hazard
`RunSyncPayload` arrives **during the join sequence, while the client may still
be on the level-loading screen**, and vanilla calls `setScreen(null)` when that
finishes. A panel opened from the network handler alone is silently discarded —
and the path it fails on is a brand-new world, i.e. the only path that matters.

So `ClientTickEvents.END_CLIENT_TICK` re-opens the panel whenever the state is
`NOT_STARTED`, the player is in a world, and **`screen == null`** (so it cannot
stomp another GUI). That also makes the gate un-escapable rather than merely
un-closable.

#### Clicking Start: the ordering question, and why it dissolves
The state transition is server-authoritative; the keybind snapshot can only be
read on the client. The obvious design does two things on one click and has to
argue about ordering and races.

**Instead, the snapshot IS the start message.** One `KeybindSnapshotPayload`
carries the four keys plus `startRun`. One packet, one server handler, one
`setDirty()`. There is no interleaving that starts a run without a snapshot,
because they are the same packet — the ordering question is removed rather than
answered.

One ordering constraint survives, and it is safely internal to that handler:
`storeKeybindSnapshotIfAbsent` **refuses while `NOT_STARTED`** (nothing to anchor
to yet, and the player may still rebind before clicking), so `startRun()` must
land first.

**The screen never closes on its own click.** It waits for the server's
`RunSyncPayload` to report `IN_PROGRESS`. A client-side close would put the
player into a world whose loop had refused to start, with no way to ask again.
`StartScreen` does close itself in exactly one case — `canSend` is false, i.e.
not an Entropy Mod server — rather than trapping the player behind a button that
talks to nobody.

Nothing here trusts the client: `startRun()` is idempotent and re-checks the
state, and the snapshot is write-once per player.

### The three paths to ENDED

| # | Trigger | Outcome | Build status |
|---|---|---|---|
| 1 | Entropy reaches the configured cap | **Loss** | partially exists — `gameOver` today |
| 2 | Ender Dragon defeated | **Win** | **not built at all** |
| 3 | Death while Become Hardcore is held | **Loss** | effect not built |

**Path 2 is the first real implementation of dragon-death detection.** It has only
ever been Open Question 1 — there is no listener anywhere in the codebase today.
Q1's sub-question is still live and must be answered when this is built: whether a
re-fight of a resurrected dragon can re-trigger a win, or only the first kill
counts.

**Path 3 revises Become Hardcore, and this is the significant change.**

The Part 1 investigation (`043f5ad`, branch `investigate-become-hardcore`)
established that real vanilla hardcore *is* reachable — the flag is mutable, read
live at death, and persists to `level.dat`. **That approach is now rejected for
this effect.** Become Hardcore will instead be:

- **A mod-tracked flag**, held like any other acquired effect.
- **On death, vanilla death and respawn proceed completely normally.** No
  `setGameMode(SPECTATOR)`, no touching `PrimaryLevelData.settings`, no
  interaction with vanilla's hardcore mechanism whatsoever.
- **The mod separately marks the run `ENDED` (loss) and surfaces the end screen.**

**Why the simplification was chosen:** it never touches the irreversible real
hardcore mechanism. The investigation's own finding was that flipping the real
flag writes to `level.dat` and **cannot be undone by the mod at all** — not by
clearing `AcquiredEffects`, not by removing the mod. Routing the outcome through
mod state instead keeps the "run" concept entirely inside the mod's own model,
where it can be reasoned about and reverted.

**The original investigation's findings remain valid and are deliberately
preserved** as a reference — see "Become Hardcore — INVESTIGATED, NOT BUILT" in
Part 1. Everything it records about where the flag lives, that it is read live in
`ServerGamePacketListenerImpl.handleClientCommand`, and what vanilla's hardcore
death actually consists of is still accurate. It is simply **not the approach
this effect will use.** Do not treat that section as the implementation plan.

### The end screen

Shown on transition to `ENDED`. Displays:

- **Win or loss, and which of the three conditions triggered it** — the three are
  distinguishable and the screen must say which fired, not just "you lost".
- **Final entropy value.**
- **Total picks made** — reuses the existing `PickRecord` / history system.
  **No new tracking is needed here**; the count is the history's length.
- **The full pick history** — also already persisted, and this finally gives it a
  real screen instead of the `/entropyhistory` chat dump.
- **Total death count across the whole run** — **NEW.** Incremented on every
  player death regardless of cause. **This does not exist anywhere in the codebase
  today** and needs a new counter, persisted alongside the rest of the run state.
  Note it counts *all* deaths, not only a Become Hardcore death, so it is
  independent of path 3.

### Post-end world state

**No lock. The world stays fully playable after the end screen.**

Acknowledged as having little practical purpose given the mod's fast-paced design
intent — but enforcement logic was explicitly judged not worth building. Recorded
so a later session does not add it as an "obvious missing piece".

### Noted for a future session — NOT decided

Once `ENDED` genuinely exists as a state, **every per-tick system in the mod
should probably check "is the run still `IN_PROGRESS`" before doing anything**:
the entropy loop, `GreenThumbGrowth`'s growth ticks, `BlightTouchedTrample`'s
per-player trample check, and any future per-tick effect.

This is **flagged as a likely requirement, not a firm decision**, and is
**explicitly out of scope** for whichever session implements the architecture
above — unless it is raised again first.

### What this does to Open Questions 1 and 12

Both are **design decided, implementation pending** — see their entries in
Part 3. Neither is resolved, and neither should be marked resolved until the
architecture above is actually built.

---

## Part 3: Open Questions — need your call before the next build session

### 1. Win detection — DESIGN DECIDED, IMPLEMENTATION PENDING
**Not resolved.** The *framing* is settled by "Run Lifecycle" above — dragon death is path 2 of three into the `ENDED` state, and is
a win. But **nothing is built**: there is still no dragon-death listener anywhere
in the codebase, and this stays open until there is. Sub-question (c) below is
also still genuinely open and must be answered when it is built.

Right now there's no code path that checks for Ender Dragon death at all.
Options:
- (a) Listen for the vanilla dragon-death event server-side, and when it
  fires, stop the entropy timer and declare a win (regardless of current
  entropy value)
- (b) Same as (a), but also record *how much entropy was left* as a score,
  so replays can be compared
- (c) Something else — e.g. does re-entering the End count, or does it need
  to be the *first* dragon kill specifically (re-fights of the resurrected
  dragon shouldn't re-trigger a win)?

### 2. Is this strictly singleplayer, or does it need to support multiplayer?
This changes several things: whether entropy/picks are global or per-player,
what happens to effects like "10 pet wolves" or "TNT spawns on you" when
there are multiple players, and whether one player's open GUI should really
block the whole server's timer. The code right now is written in a way that
*works* for solo play but is structured as if multiplayer might happen
(broadcasts to `PlayerLookup.all`). Worth deciding explicitly rather than
finding out later.

### 3. Should high entropy still occasionally roll low-tier effects?
Current hard min/max cutoffs mean a Tier 1 effect can never appear again
once entropy passes 25. Do you want:
- (a) Keep hard cutoffs — simpler, tiers are fully distinct chapters
- (b) Overlapping ranges (e.g. Tier 1 effects stay eligible up to entropy 60
  at low weight) so there's some texture/variety even late-game
- (c) Some other blending rule

### 4. What's the config surface for interval length and entropy cap? — STILL OPEN, but it now has a home
**Not resolved. The surface is decided; the controls are not built.**

The venue question is answered: **`StartScreen` is where these live**, and it now
exists, is modal, and already runs at the exact moment settings would be locked
in. What is missing is only the controls themselves plus a client→server settings
channel and validation — deliberately deferred as materially more work than the
gate the last session was scoped to. `setIntervalTicks`/`setEntropyCap` still
exist, are still persisted, and still have no player-facing way to reach them.

Whatever lands must go **inside `StartScreen`'s centred layout block**, not below
it, or it will push the Start button off-screen at small GUI scales — and that
screen has no escape hatch.

The original options (a) GameRules / (c) config file are superseded for the
world-creation case by the panel, but a GameRule may still be worth having for
mid-run admin adjustment. Not decided.

### 5. Anti-stacking enforcement: replace, or exclude from the pool? — RESOLVED
**(b) Exclude from the pool.** Built. Categories with an already-active effect
are filtered out of the roll, so a conflicting option never appears among the
three cards and a pick can never silently cancel an effect the player already
has. The (a) replace-on-pick alternative was considered and rejected — don't
reintroduce it without revisiting the decision, the two feel very different.

*(Note for the record: `EffectCategory.java`'s javadoc previously described the
opposite rule. It was stale and has been corrected.)*

### 6. What happens when the game world runs out of legal effects? — STILL OPEN
Partially handled, deliberately as a placeholder rather than a decision.

**What's implemented:** a partial pool is returned as-is (2 survivors → 2
cards, which is fine and is *not* treated as an error). If the category filter
leaves the pool completely empty, it falls back to the unfiltered pool —
allowing one category collision — and logs a loud warning flagging itself as
placeholder policy. The loop can never strand.

**What's still undecided:** whether allowing a collision is actually the right
answer. Two of the old alternatives died with temporary effects (there is no
duration to extend and nothing to force-expire), leaving: offer fewer than 3
cards and accept it, or end the run early once the pool is exhausted.

**This got sharper, not softer, with permanence.** There are now *two* fallbacks
— no-repeat and anti-stacking — and the no-repeat one is reachable in ordinary
play: at 17 GOOD / 17 BAD, the 18th pick of a phase has nothing new to
offer. At the default cap of 100 that is well inside a normal run. More content
raises the bar but does not remove it; a cap of 100 needs ~50 effects per phase
to never repeat. Deciding what *should* happen when the well runs dry is now a
real design question, not a defensive nicety.

### 7. Should the GUI pause the world, or keep it running?
`shouldPause()` is currently `true` (world freezes while you decide). This
was a default, not a deliberate choice. Freezing removes time pressure from
the decision itself (the *only* pressure is the entropy clock); not freezing
adds "decide fast, mobs are still coming" stress on top. Which fits the
intended feel better?

### 8. Priority order for the "odd/signature" effects
Several of the weird effects (Doppelganger, Mirror World, Fisheye,
Colorblind Mode) each need their own chunk of real work (custom entity +
skin injection, movement-input mixin, render-layer mixin, screen shader).
Given limited session time, which 3-4 should be tackled first as the
"proof the weird stuff works" batch, vs. which can wait indefinitely?

### 9. `EffectExecutor` architecture — RESOLVED
**(b) Per-effect classes.** Built. `EffectBehavior` interface, one
implementation per effect in `com.entropymod.entropy.behavior`, wired by id in
`EffectBehaviors`. There is no `EffectExecutor` class and there shouldn't be —
the name is retired. All 11 Tier 1 effects have a stub; none have real
behavior. See "Effect execution" in Part 1 for the three-step recipe.

### 10. Persistence scope — RESOLVED
The question ("do in-flight temporary effects need to survive a restart?")
**dissolved when effects became permanent** — there are no in-flight temporary
effects any more. What remained was straightforward and is built:
`EntropyManager` is a `SavedData` persisting entropy, pick count, game-over,
config, the acquired-effect ids, and the full history. See "Persistence — BUILT"
in Part 1 for what is deliberately *not* saved and why.

One consequence the old note predicted is now real and handled: a saved run can
be loaded by a build whose registry has changed. Unknown effect ids are skipped
with a warning rather than failing the load, and every codec field is optional
so an older save still opens.

### 13. Is Giant Size still a BAD effect? — OPEN, deliberately NOT decided
**Giant Size is still filed BAD, and that classification is now questionable.**
Recorded here rather than settled quietly in either direction, because the effect
changed a lot after the phase was chosen.

**When it was filed BAD** it was 5x size and nothing else: you fit almost
nowhere, you suffocate if you grow in a tunnel, and you are an enormous target.
That reading was straightforward.

**What it is now** — 5x size, **+10 hearts**, **double jump height**, **2-block
step-up**, **-25% damage taken**, and **+6.5 blocks of reach**. Four of those
five additions are unambiguous power, and the fifth (reach) is a repair without
which the effect is unplayable rather than merely harsh.

The case each way, stated so the decision is made on the real numbers:

- **Still BAD.** The size penalty is severe and permanent: a 9-block-tall,
  3-block-wide player cannot enter caves, ravines, mineshafts, villages or any
  ordinary build. That is most of Minecraft. The stat bonuses do not buy any of
  it back, and the suffocation risk on every apply is real.
- **Now GOOD.** 20 hearts with 25% damage reduction is roughly 26 effective
  hearts, on top of mobility that outclasses several Tier 1 GOOD effects
  outright. A player offered this among three curses would likely take it
  *hoping* to get it, which is the practical test of a curse.

**Whichever way it goes, two things must move with it.** The phase field is not
the only thing keyed on this:

- **Anti-stacking.** It is `DEBUFF`, a category currently shared only with
  Exposed, Upside-Down Camera and Bad Reputation — all BAD. Moving it to GOOD
  would make it the only GOOD effect in that category, which changes what those
  curses compete with.
- **The counterplay rule.** BAD effects below entropy 40 must be
  counterplay-survivable; it is `true` today. As a GOOD effect that flag stops
  meaning anything, and the entropy 25-50 range should be re-examined at the same
  time.

**No code change was made in either direction.** The phase is exactly as it was.

### 11. A separate armour-durability curse — OPEN, deliberately unspecified
Clumsy Digger used to hit armour and the elytra, because
`ItemStack.hurtAndBreak` reaches every damageable item. That was never the intent
and has been scoped out — see "Clumsy Digger is scoped to mining tools" in Part 1.

**Armour wear is still a legitimate curse idea, and it is tracked here rather
than half-built.** Nothing about it is decided:

- **Severity is genuinely open, and must not be assumed to be Clumsy Digger's.**
  The tool numbers were approved *for tools*. Armour durability is consumed on a
  completely different schedule — per damage event rather than per block — so the
  same formula produced ~90% life loss and read as far harsher. Any armour curse
  needs its own derivation against how often armour actually takes damage.
- **The elytra is a separate question again.** It burns durability per second of
  flight, so a per-event penalty behaves like a flight-time cap. It may deserve
  its own effect, its own severity, or exemption.
- **The mechanism is already proven and cheap to reuse:** the same
  `ItemStackDurabilityMixin` hook with `#minecraft:enchantable/armor` (or the
  per-slot tags) instead of `enchantable/mining`. No new mixin needed.

**Do not fold this back into Clumsy Digger.** They are two effects with different
severities, and merging them is what produced the problem this section exists to
record. The anti-stacking category question (both would be `TOOL`/`GEAR`) is part
of the design decision.

### 12. Does dying under Become Hardcore end the entropy run? — DESIGN DECIDED, IMPLEMENTATION PENDING
**Not resolved.** The answer is now option (a) below, arrived at from a different
direction than this question anticipated: see "Run Lifecycle" above. Death while Become Hardcore is held ends the run as a loss —
**but via mod-tracked state, not via vanilla hardcore at all.** Vanilla death and
respawn proceed completely normally, so the "dead spectator" scenario this
question was framed around no longer arises. **Nothing is built**, so this stays
open.

The framing below predates that decision and assumes the real-hardcore approach;
it is kept because its analysis of the two end states not knowing about each other
is what motivated the run-lifecycle design in the first place.

Raised by the Become Hardcore investigation (Part 1), which established that the
effect is buildable and that vanilla's hardcore death leaves the player
permanently in Spectator.

**The two end states currently do not know about each other.** Entropy reaching
the cap sets `gameOver` and stops the loop; a hardcore death is pure vanilla and
touches no mod state. So as things stand, a dead spectator would keep being shown
a mandatory pick GUI every three minutes for the rest of the world's life.

Options, none chosen:

- (a) A hardcore death sets `gameOver` — one loss condition, two triggers.
- (b) The loop keeps running and the picks are cosmetic — bad, given the GUI is
  modal and `shouldCloseOnEsc()` is false.
- (c) The loop pauses while every player is a spectator, and resumes if one is
  ever restored.

This overlaps **Question 1** (win detection — beating the dragon is a third end
state and is still unbuilt) and **Question 2** (multiplayer: in a shared run, does
one player's hardcore death end everyone's run?). Worth settling all three
together rather than piecemeal, since they are the same question about what "the
run is over" means.

---

## Suggested next session order

Questions 5, 9 and 10 are resolved and built. The Tier 1 content batch (20
permanent effects) is in and is real, not stubs.

1. **Play a run.** Force several picks, feel each effect, then die and relog and
   confirm everything is still on exactly once. Nothing below is worth building
   on top of an unverified foundation, and the permanent/persistence/
   re-application work has never run in a live server. **Use `/entropygrant` to
   reach a specific effect** rather than rolling until it shows up — that is what
   it exists for, and an effect is not done until it has been observed in game.
2. Resolve Questions 1, 2, 7 — these still affect the *shape* of code written in
   every other step.
3. **Decide Question 6 properly.** It stopped being theoretical: with 10 effects
   per phase the no-repeat pool empties partway through a normal run, and the
   current answer (silently re-offer something you already own) is a placeholder
   that will be visible in play.
4. More content — the direct answer to (3). Either more Tier 1 breadth or the
   start of Tier 2. Settle Question 3 first if the new effects need entropy
   ranges that overlap Tier 1's.
5. Add win detection per Question 1.
6. Config surface (Question 4) — can happen in parallel, low-risk to defer.
7. A real pick-history screen to replace the `/entropyhistory` debug logging.
   The data is persisted now, so it survives long enough to be worth showing.
8. Odd/signature effects, prioritized per Question 8. **Fourteen mixins now exist
   and the pattern is well proven** — including a mod-registered attribute, a
   goal-AI hook, and driving a data-driven vanilla enchantment directly. Read
   "The mixin cluster" above before starting: its target table, and the note that
   compiling proves nothing about a mixin, are the two things that will save the
   most time. `magnetic_boots` shipped there and is no longer outstanding.
