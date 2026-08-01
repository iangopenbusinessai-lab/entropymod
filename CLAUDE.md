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
- Only Tier 1 (11 effects) is actually in the registry. Tiers 2-4 and all the
  odd/signature effects exist only in the design doc, not in code.
- `counterplay` flag exists on the data model but nothing reads/enforces it
  yet (e.g. "never roll 3 counterplay:false effects at once below entropy 40"
  isn't checked anywhere). `rollThree` is now the obvious place — it already
  filters by category, so a counterplay constraint slots in beside it.
- **The pool is thin enough that both fallbacks are reachable in normal play.**
  10 effects per phase means the no-repeat pool is empty by the 11th pick of a
  phase, and picks 9 and 10 legitimately show fewer than 3 cards. At the default
  entropy cap of 100 (~50 picks per phase) a run will hit this every time. This
  is a content problem, not a code problem — but it is not a rare edge case, and
  a fallback warning in the log is expected rather than a bug.
- **All 20 effects share the same 0-25 entropy range.** There is only one tier,
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

### Mixins — three real ones now, plus the original placeholders
`EntropyModMixin` / `EntropyModClientMixin` are still unused example code.
**Three real mixins now exist** for the hook-driven effects — see "The three
mixins" under the Tier 1 content batch below. The pattern is proven; later
signature effects (Mirror World's inverted input, Fisheye's forced FOV,
Doppelganger's custom entity) can follow the same shape.

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

**The repeat fallback is genuinely reachable today**, not theoretical: with 10
effects per phase it fires on the 11th pick of that phase. It is a tested path
(see the headless harness), and it is still PLACEHOLDER policy — Open Question 6
remains open, and more content is the real fix.

A partial result (1 or 2 cards) is not a fallback and is not flagged. It is the
honest answer when the pool is that small, and it is the normal experience for
picks 9 and 10 of a phase.

### The Tier 1 content batch — 20 effects, first real content
Ten GOOD, ten BAD, all permanent, all entropy 0-25. This replaced the original
11 placeholder stubs. **It is a baseline, not the finished game** — Tiers 2-4
and the odd/signature effects still slot in the same way.

Fourteen are pure `AttributeEffectBehavior` subclasses. Six need a mixin,
because no vanilla attribute covers them: hunger rate, incoming damage, and XP
gain.

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

#### The three mixins
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
- No screen or keybind yet. `/entropyhistory` requests it and the client logs
  the result — temporary debug path, replace with a real screen.
- `EffectPhase`'s wire codec now lives in `network/EntropyCodecs.java` since
  two payloads need it. Sent by name, not ordinal, so reordering the enum
  can't flip GOOD and BAD on the wire.

### Debug commands — know which ones are REAL and which are FAKE
This distinction has already cost one session. Read it before adding another
test command.

| Command | Side | Real pipeline? |
|---|---|---|
| `/entropyforcepick` | server | **YES** — the real path |
| `/entropystatus` | server | **YES** — reads real manager state |
| `/entropyhistory` | client → server | **YES** — real request/response |
| `/entropypreview` | client only | **NO** — hardcoded fake data |

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
play: 10 effects per phase means the 11th pick of a phase has nothing new to
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
   re-application work has never run in a live server.
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
8. Odd/signature effects, prioritized per Question 8. The three mixins added for
   hunger/damage/XP prove the pattern these will need.
