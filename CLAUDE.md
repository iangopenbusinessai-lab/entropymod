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


## Part 1: Core architecture — the rules that hold everywhere

*(Per-component status and known gaps moved to the `entropy-design` skill.
Per-effect implementation findings moved to the `entropy-effects` skill.)*

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


### When a mixin applies cleanly and STILL changes nothing — read this first
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

### Two findings from the survival-batch revision that generalise
*(Derivations, tables and javap evidence are in the `entropy-effects` skill, under
Slippery Grip and Phoenix Chambered Heart. These are here because they are not
really about those effects.)*

**1. Grant real vanilla `MobEffectInstance`s rather than imitating them.** Phoenix
Chambered Heart's death save grants Regeneration/Health Boost/Absorption X instead
of setting a health number: **+40.0 max health, 40.0 absorption granted filled,
1.0 HP healed every tick**. The part that generalises is **expiry** —
`LivingEntity.refreshDirtyAttributes()` → `onAttributeUpdated` clamps health and
absorption down when their maxima fall, so **there is no mod-side timer and
nothing to clean up**, and the grants compose with the permanent `MAX_HEALTH`
effects because each modifier carries its own `Identifier`. A hand-rolled version
would have had to get all of that right by hand.

**1b. Changing an attribute is not the same as changing a mechanic — sweep for
every branch that grants the same thing.** Slippery Grip halved sprint speed
through `MOVEMENT_SPEED` and shipped; sprint-jumping ignored it almost entirely.
No injection was broken. Vanilla rewards sprinting in **three** independent
places, and the attribute is only one: `jumpFromGround` adds a **flat 0.2
blocks/tick** forward impulse that reads no attribute, and
`Player.getFlyingSpeed` returns `0.02 -> 0.025999999`. The second is the one that
matters most, because `getFrictionInfluencedSpeed` is
`onGround() ? getSpeed() * ... : getFlyingSpeed()` — **while airborne
`MOVEMENT_SPEED` is not consulted at all**, so for the whole of every jump the
compensator was inert rather than merely outvoted. Measured, the curse's sprint
was 2.16 b/s on the ground and **6.33 b/s while jumping — faster than walking**,
i.e. inverted into a reason to sprint.

This is a **fourth** failure mode alongside the three above, and the diagnostic
that finds it is not a log: it is `javap | grep isSprinting` (or whatever the
condition is) across the *whole* movement path, before writing anything. The fix
generalises too — scale **every** branch by one shared factor read from the live
value, never a second constant. Vanilla's horizontal movement is linear and
homogeneous in (ground accel, air accel, impulse), so one factor scales every
sprinting motion by exactly that factor and preserves vanilla's own internal
ratios (sprint-jumping stays worth the same 27% over flat sprinting).

**1c. An entity query is O(entities); a block sweep is O(volume).** Danger Sense's
32-block radius is a 274,625-position box — 56x Green Thumb's — and is nearly free,
because `getEntitiesOfClass` is served by entity section storage. Never reason
about a proximity effect's cost from its radius without first asking which of the
two it is. Corollary: the query is a *box* and effects are *spheres*, so a missing
`distanceToSqr` filter silently leaks the range by 73% (`r × √3`).

**1d. A tick-driven effect must model its SAMPLING POINT, not the idealised event
order.** Double Jump shipped with a green harness that fed a state
`END_CLIENT_TICK` can never observe: `aiStep` calls `jumpFromGround` at offset 460
but `travel`/`move` — which writes `onGround` — at 615, so on the jump tick
`onGround()` already reads false. The logic was right and wrong in place. Establish
what your hook observes relative to the vanilla code you care about **by offset**,
then test that order. (Fifth failure mode; unlike the others it is not about mixins.)

**1e. A mob's ground acceleration is its `MOVEMENT_SPEED` SQUARED, and its real
`FOLLOW_RANGE` is not the attribute's default.** `Mob.setSpeed(f)` also calls
`setZza(f)`, so a mob's forward input is its speed rather than a player's
normalised 1.0 — read a creeper's 0.25 the player way and you overstate it 4x.
Separately, `Attributes.FOLLOW_RANGE` defaults to 32.0 but
`Mob.createMobAttributes()` overrides it to 16.0. **Always read a mob's attributes
from `DefaultAttributes.getSupplier(EntityType.X)`, never from the attribute.**
Spawning anything also means reusing `SafeSpawn` — see `entropy-effects`.

**1f. The component you can test is not the component that breaks.** Unstable and
Creeper Magnet shipped with the cadence asserted over 200 consecutive cycles and
failed in play as "fired once, then silence" — the schedules were perfect and
`SafeSpawn` was returning null on three triggers in four, because every line of it
needed a `ServerLevel` and none of it was harness-reachable. **Split a scheduled
effect's pure decisions out as Minecraft-free statics so they can be driven, and
make every failure path name its own cause in the log** — the diagnosis here
needed the world save's heightmap only because the log said *that* it failed and
not *why*. See `entropy-effects`.

**2. A mixin's *shape* is part of its correctness when two sides share a target.**
Slippery Grip's two mixins used to be `@ModifyVariable`s that both forced
`setSprinting`'s argument false, which chained safely **only because both halves
forced the same value**. Making each half *add or remove* an attribute modifier
destroyed that: the common mixin runs on the client too (where `EffectHooks`
answers "no effect" by design) and would delete what the client half just added,
with the winner decided by injection order. **Rule: when a common and a client
mixin target one method, scope each to the side it is the authority for** — here
`player.level() instanceof ServerLevel` and `instanceof LocalPlayer` — so exactly
one ever acts. This failure mode is invisible to the build.


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
same numbers kept being re-derived. 925 checks currently: the tuning constants as
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
Phoenix Chambered Heart's granted amounts and its composition with the permanent
max-health effects, Slippery Grip's walk/sprint speeds at two different
baselines, attribute idempotency against a real `AttributeInstance`, and the spawn
batch -- both cadences (Unstable's fixed 30s, Creeper Magnet's genuinely re-rolled
30s-2min), Unstable's blast table against vanilla's own damage formula, and the
creeper's real speed and follow range read from `DefaultAttributes`.

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


---

## Where the rest of this document lives

This file used to carry every verified finding inline and had grown past the size
where Claude Code warns about a single memory file. The material was **moved, not
deleted** — it now loads on demand instead of in every session.

| Skill | Load it when | What's in it |
|---|---|---|
| `entropy-effects` | Implementing, tuning, renaming or debugging a specific effect; **writing any new mixin**; **spawning any entity** | Attribute clamps and floors, the javap-verified mixin target table, the `@Shadow` crash trap, Frost Walker's data-driven finding, `SafeSpawn`/`SpawnSchedule` (the shared groundwork every spawn-based effect must reuse), and the full derivation behind every shipped effect's constants |
| `entropy-design` | Planning what to build next, deciding scope, resolving an open question | Per-component status and gaps, the run lifecycle, the three paths to `ENDED`, and all open design questions |

**Two things to read before you start, because they will not be loaded for you:**

- **Anything that won't compile → Part 0 above**, not a tutorial online. Almost
  every Minecraft snippet you'll find is written in Yarn names; this project uses
  Mojang mappings and targets a version Yarn never covered.
- **Before writing a mixin → load `entropy-effects` first.** Three traps live
  there that build green and fail at runtime: `@Shadow` does not resolve
  inherited fields (hard crash on every world launch), a subclass may override
  the method you targeted, and a value you successfully modify may be re-clamped
  downstream. Compiling proves nothing about a mixin.
