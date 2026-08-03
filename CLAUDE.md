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

### Mixins — fifteen real ones now, plus the original placeholders
`EntropyModMixin` / `EntropyModClientMixin` are still unused example code.
**Fifteen real mixins now exist**: three for the original hook-driven effects,
eight from the mixin cluster, and four from the crop/event/meta session
(`CropGrowthMixin`, `ServerPlayerJumpMixin`, `ItemStackDurabilityMixin`,
`VillagerPricesMixin`). See "The original three mixins" and "The mixin
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
`magnetic_boots`, via a mod-registered attribute). Ten need a mixin, because no
vanilla attribute covers them: hunger rate, incoming damage, XP gain, water
freezing, projectile knockback, footstep volume, mob detection range, and animal
fleeing.

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
| `CropGrowthMixin` | `CropBlock.getGrowthSpeed` *(static)* | `@Inject` RETURN — Blight Touched only now |
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

#### Crops: hook `getGrowthSpeed`, not `randomTick` (Blight Touched)
**This hook is Blight Touched's alone now.** Green Thumb used to share it and no
longer contributes anything to it — see "Green Thumb: when vanilla's own systems
cannot reach the target" below for why, and for the pattern that replaced it.
Everything in this section still applies to Blight Touched unchanged.

Vanilla grows a crop with
`random.nextInt((int)(25.0F / getGrowthSpeed(...)) + 1) == 0`, so scaling that
speed is the whole effect: higher speed, smaller divisor, better odds.

**`CropBlock.randomTick` is the obvious target and the worse one.**
`BeetrootBlock` and `TorchflowerCropBlock` both override it — the subclass trap
above — and although both happen to call `super`, relying on that is luck.

**`CropBlock.getGrowthSpeed` is strictly better on both counts.** It is `static`,
so it cannot be overridden at all, and it is called from **three** classes rather
than one: `CropBlock`, `StemBlock` and `PitcherCropBlock`. One hook therefore
covers wheat, carrots, potatoes, beetroot and torchflower (all via
`CropBlock.randomTick`), plus pumpkin and melon stems, plus the pitcher crop.

- **Scope limit, stated rather than hidden:** plants that don't use
  `getGrowthSpeed` are unaffected — sugar cane, cactus, bamboo, saplings, nether
  wart, cocoa, sweet berry bushes. "Crops" here means farmland crops and stems.
- **Scoping to a player is possible and clean.** A random tick has no owner, but
  it does have a `ServerLevel` and a `BlockPos`, and
  `EntityGetter.getNearestPlayer(x, y, z, radius, false)` answers "who is within
  8 blocks". *Nearest*, not *any*, so two players with opposing effects cannot
  both apply. The stop condition in the brief did not fire.
- **Never let the multiplier reach 0.** `25.0F / 0` is `Infinity`,
  `(int)Infinity` is `Integer.MAX_VALUE`, and `nextInt(MAX_VALUE + 1)` overflows
  to `nextInt(Integer.MIN_VALUE)`, which **throws inside a block tick**.
  `EffectHooks.cropGrowthMultiplier` floors the result for exactly this reason.
  **This matters more now than it used to:** with only Blight Touched left on the
  hook, the multiplier is always ≤ 1.0, i.e. always on the dangerous side. Green
  Thumb's old 26x used to dominate the composition and keep it far away.

#### Crop growth timing — derived, not measured; don't re-derive it from scratch
This is the kind of number a future balance pass would otherwise redo from
zero. The probability model below is **validated against an external known-good
figure** (vanilla row-planted wheat at ~24 minutes) and is asserted by
`./gradlew harness`. It still governs Blight Touched, and it is what proved Green
Thumb needed a different mechanism entirely.

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

`GreenThumbGrowth` grants stage advances directly and no longer touches
`cropGrowthMultiplier` at all. **Green Thumb declares no multiplier constant any
more** — removed rather than set to 1.0, because a neutral constant can be
quietly re-wired into the shared hook and double-applied, and a field that does
not exist cannot. The harness asserts its absence.

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
  with no age property.
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

**Blight Touched interaction:** the two no longer compose through one multiplier.
Green Thumb's advances fire on schedule regardless, and Blight still slows the
natural random-tick path. That is close to the old behaviour in practice — at
26× × 0.5 the roll was *still* saturated, so Blight was fully masked then too.
The one place it now bites is the stem's fruit step, which is genuinely
probabilistic: blighted, that takes ~11 attempts instead of ~6.

#### Leaky Pockets: 4% → 7% per jump
Retuned after play testing. At 4% the mean gap was 25 jumps, rare enough that the
curse could be held for a long stretch without ever being noticed — failure mode
3 from the mixin-cluster list ("real, but below the perceptual threshold"). 7%
means a mean gap of ~14 jumps, roughly every other minute of ordinary movement.
One constant, `LeakyPocketsBehavior.CHANCE`, asserted by the harness.

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
- `rerollAvailable` rides on `OpenChoicePayload` so the client knows whether to
  draw the button, but it is **not authorisation**: `requestReroll` re-checks
  ownership, spent-ness and a pending pick server-side.

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
same numbers kept being re-derived. 64 checks currently: the tuning constants as
actually compiled, the vanilla crop-growth model, Green Thumb's active schedule
and its per-crop intervals, Blight Touched's untouched contribution to the shared
hook, the crop-schedule tracking rules, the growth-roll overflow guard, and
`/entropygrant`'s contract.

It has already earned its keep once: this session's first run failed on a
hand-derived expected value for blighted wheat (the roll bound at speed 2.5 is
11, not 12). The model was right and the number written next to it was not.

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

## Part 3: Open Questions — need your call before the next build session

### 1. Win detection — how does the mod know you beat the dragon?
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

### 4. What's the config surface for interval length and entropy cap?
Needs to be settable at world creation per your original ask ("3 min
default, but a setting"). Options:
- (a) Vanilla GameRules (simplest — works with `/gamerule`, no custom UI
  needed, but feels less "designed")
- (b) A real custom world-creation config screen (more polished, more work)
- (c) A config file (Mod Menu / Cloth Config style) — editable outside a
  running world, but doesn't fit "chosen at world creation" as naturally
- (d) Some combination (GameRules for now, real screen later)

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
8. Odd/signature effects, prioritized per Question 8. **Eleven mixins now exist
   and the pattern is well proven** — including a mod-registered attribute, a
   goal-AI hook, and driving a data-driven vanilla enchantment directly. Read
   "The mixin cluster" above before starting: its target table, and the note that
   compiling proves nothing about a mixin, are the two things that will save the
   most time. `magnetic_boots` shipped there and is no longer outstanding.
