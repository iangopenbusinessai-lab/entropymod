---
name: entropy-effects
description: Verified per-effect implementation findings for Entropy Mod — attribute clamps and their floors, the javap-verified mixin target table, the @Shadow inherited-field crash trap, and the full tuning derivation for every shipped effect (Clumsy Digger durability, Bad Reputation pricing, Green Thumb intervals, Giant Size, Embrace the Moon physics, Phoenix Chambered Heart, Slippery Grip, Slashed Pockets). Read this before implementing, tuning, renaming or debugging any specific effect, and before writing any new mixin.
---

# Entropy Mod — per-effect implementation findings

> **Cross-references.** This content was split out of the root `CLAUDE.md`.
> References below to "Part 0", "Part 1", "Part 2" and the mapping table point at
> the root `CLAUDE.md`; references to "Open Question N" and the run lifecycle
> point at the `entropy-design` skill.

Every number here was verified by running it or by reading bytecode, not inferred
from docs or memory. **Do not re-derive these from scratch and do not "simplify"
them** — several are deliberately non-uniform, and the reasons are recorded inline.

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
| Phoenix Chambered Heart | GOOD / SURVIVAL | 25-50 | `checkTotemDeathProtection` mixin + run flag |

Glass Cannon Pact sits **above the rest of Tier 2** deliberately: the health cost
is permanent and compounds with whatever a long run has already accumulated.

**Two of the three were revised after their first ship**, and both revisions are
recorded in place below rather than as a separate history: Slippery Grip stopped
blocking sprinting and started punishing it, and Second Chance became **Phoenix
Chambered Heart** with a real vanilla effect stack instead of a bare `setHealth`.
Both id changes follow the established rule — a save from before carries an id
this build no longer defines, skipped with a warning at load.

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

#### Slippery Grip: sprinting is a single attribute modifier, and that is the whole effect
**Revised.** It used to force `LivingEntity.setSprinting(boolean)`'s argument to
`false`, so the player simply could never sprint. It now lets the sprint happen
and makes it **half the player's own walking speed**. Walking is untouched.

**Vanilla's sprint boost, javap-verified.** `LivingEntity.setSprinting(boolean)`
is four steps and nothing else:

```java
super.setSprinting(sprinting);                       // the shared entity flag
AttributeInstance i = getAttribute(MOVEMENT_SPEED);
i.removeModifier(SPRINTING_MODIFIER_ID);             // unconditionally
if (sprinting) i.addTransientModifier(SPEED_MODIFIER_SPRINTING);
```

and from `LivingEntity`'s `<clinit>`, `SPEED_MODIFIER_SPRINTING` is
`AttributeModifier(minecraft:sprinting, **0.30000001192092896**,
**ADD_MULTIPLIED_TOTAL**)`.

**So sprinting is not a movement mode — it is one ordinary attribute modifier,
added and removed at a single choke point.** That is the finding that decided the
implementation: this is reachable through attribute modifiers alone, with no
movement mixin and no per-tick correction.

**One modifier does both jobs.** `calculateValue`'s third pass is a *product*
(`e *= 1 + amount` per `ADD_MULTIPLIED_TOTAL` modifier), and every factor is
independent of every other. Writing `W` for the walking total — whatever it is,
including other speed effects in any pool — sprinting is
`W × (1 + 0.3) × (1 + c)`, so choosing `c` with `(1 + 0.3)(1 + c) = 0.5` collapses
the bracket to a constant `0.5` **whatever `W` is**. Cancelling vanilla's +30% and
applying the −50% are the same multiplication; splitting them into two modifiers
would be two numbers that have to agree.

`c = 0.5 / (1 + 0.30000001192092896) − 1 ≈ **−0.6153846**`, and it is computed
from the modifier vanilla has *just added*, read live off the instance, rather
than from the constant. A retune of vanilla's sprint bonus is then absorbed
instead of silently putting the effect off target; the recorded constant is only
the fallback. Measured, both baselines asserted:

| baseline | walking | vanilla sprint | cursed sprint |
|---|---|---|---|
| plain player | 0.1 | 0.13 | **0.05** |
| + Sure Footing (+15%, a *different* pool) | 0.115 | 0.1495 | **0.0575** |

**Walking is untouched by construction, not by care.** The compensator exists
only while sprinting — added at the tail of the same `setSprinting` call that adds
vanilla's, removed at the tail of the one that removes it — so a non-sprinting
player's attribute holds no modifier of this effect at all. The harness asserts
that as **raw bit equality** against a player without the effect, not a tolerance.

**The shape of the two mixins had to change with the mechanism, and this is the
durable lesson.** The old pair were `@ModifyVariable`s that both forced `false`;
chaining was safe *precisely because* both halves only ever forced the same value,
so order could not matter. An action that both **adds and removes** has no such
property: run the server half on the client (where `EffectHooks` answers "no
effect" by design) and it would delete the modifier the client half just added,
with the outcome decided by mixin ordering between two configs. The fix is to
scope each half to the side it is the authority for — the common mixin now checks
`player.level() instanceof ServerLevel`, the client mixin checks `LocalPlayer` —
so exactly one of them ever acts on a given entity. **Order-independent by
construction rather than by luck.**

The client half is still load-bearing, but its job is narrower than before: since
`MOVEMENT_SPEED` is client-syncable, the server's modifier set (vanilla's sprint
modifier *and* the compensator) reaches the client anyway. The client mixin is a
**prediction** fix — it stops the one-packet-latency speed pop at the start of
every sprint, and computes exactly what the incoming sync will confirm.

Two smaller rules the shared `SlipperyGripSprint` helper exists to hold:

- **`addOrUpdateTransientModifier`, never `addTransientModifier`.** `setSprinting(true)`
  is called repeatedly with the same value — vanilla's own line removes and re-adds
  every time — and the plain add throws `IllegalArgumentException` on a duplicate id.
  Here the project-wide idempotency rule is load-bearing on the *ordinary* path,
  not only on respawn.
- **The effect is no longer a `HookEffectBehavior`.** It has one thing to do on
  `apply`: a player who acquires the curse *while already sprinting* would keep
  vanilla's untouched bonus until the next state change. `apply` re-runs the same
  update, which is "set to X" rather than "adjust by X", so respawn/rejoin
  re-application needs no freshness check.

#### Phoenix Chambered Heart: ride the Totem of Undying's own escape hatch
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

Injected at HEAD and cancelling, so the Heart is checked **before** a totem in
hand — the run's one-shot is spent and the totem is kept. `checkTotemDeathProtection`
is `private`, so the subclass-override trap cannot apply; it does run for every
living entity, which is what the `instanceof Player` guard is for.

#### The granted outcome is three real vanilla effects — the numbers, verified
**Revised.** The first version set the player to one heart and stopped. The
trigger, the hook and the run flag are unchanged; only the outcome is different,
and it is now three ordinary `MobEffectInstance`s — the same objects a potion or a
beacon hands out. **Nothing here reimplements healing or absorption.**

| effect | amplifier | duration | what vanilla's formula actually produces |
|---|---|---|---|
| Regeneration X | 9 | 100t (5s) | **1.0 HP every tick** — up to **100 HP** healed |
| Health Boost X | 9 | 600t (30s) | **+40.0 max health** (20 → **60**, 30 hearts) |
| Absorption X | 9 | 600t (30s) | **40.0 absorption HP** (20 gold hearts), instantly |

Net: a **100 HP pool** at the instant the killing blow is refused — 60 max health,
filled in about 3 seconds, on top of 40 absorption available immediately.

**Amplifier 9 is level X, confirmed not assumed.** Vanilla builds the label as
`"potion.potency." + amplifier` — from the `makeConcatWithConstants` bootstrap
entry in `PotionContents.getPotionDescription`, since the string is only reachable
through the bootstrap-method table — and the shipped lang has
`potion.potency.1 = "II"`. So displayed level = amplifier + 1. The arithmetic below
says the same thing independently.

- **Health Boost and Absorption scale by one shared rule.**
  `MobEffect$AttributeTemplate.create(int amplifier)` is exactly
  `new AttributeModifier(id, amount * (amplifier + 1), operation)`. Both register
  `amount = 4.0` with `ADD_VALUE` — Health Boost on `MAX_HEALTH`, Absorption on
  `MAX_ABSORPTION` — so both are `4.0 × 10 = 40.0` at level X.
- **Absorption also *fills* what it grants.** `AbsorptionMobEffect.onEffectStarted`
  is `setAbsorptionAmount(max(getAbsorptionAmount(), 4 * (amplifier + 1)))`, so the
  40 HP arrive immediately. Health Boost does **not** — it raises the ceiling only,
  which is why Regeneration is a third grant rather than a flourish.
- **Regeneration X ticks every single tick, and this saturates.**
  `shouldApplyEffectTickThisTick(duration, amplifier)` is
  `int i = 50 >> amplifier; return i > 0 ? duration % i == 0 : true`. At amplifier
  9, `50 >> 9 == 0` → the `true` branch, i.e. every tick; `applyEffectTick` heals
  `1.0f` while `health < maxHealth`. **Saturation begins at amplifier 6**, so level
  X is well past it and a larger amplifier would change nothing — the same
  saturation shape as Exposed's 1.25 detection cap and the retired crop hook's 26x.

**The `setHealth` is still mandatory, and none of the three can replace it.** The
hook runs *after* health has hit zero: Health Boost raises the ceiling without
raising current health, Absorption is a separate pool the death check does not
consult, and Regeneration's first tick has not happened yet. The player is put on
**1.0** first — the same value and the same reason as vanilla's own totem branch —
and Regeneration fills the boosted bar from there in ~59 ticks, inside its 100-tick
window.

**Composition with the permanent health effects: additive, no conflict.** Health
Boost is an ordinary `ADD_VALUE` modifier on `MAX_HEALTH` — the same attribute and
operation Thick Hide (+4.0), Brittle Bones (−4.0) and Glass Cannon Pact (−2.0) use.
They cannot clash, for the reason already recorded for the fall-damage trio:
**every modifier carries its own `Identifier`** (`minecraft:effect.health_boost`
against `entropymod:effect/<id>`), so they coexist on one `AttributeInstance` and
`calculateValue` sums the whole `ADD_VALUE` pool. A run holding Glass Cannon Pact
and Brittle Bones sits at 14.0 normally and **54.0** for the 30 seconds this is up,
and drops cleanly back to 14.0 after — asserted in both directions.

**Expiry is vanilla's, and leaves nothing dangling.** Verified end to end, because
a temporary max-health bonus is exactly the shape that strands a player above
their own ceiling. On the tick an effect expires,
`MobEffect.removeAttributeModifiers` drops its modifier **strictly by the
template's own id** (so the mod's modifiers are untouched), which marks the
instance dirty; `LivingEntity`'s effect loop ends in `refreshDirtyAttributes()` →
`onAttributeUpdated(...)`, whose first two branches are:

```java
if (MAX_HEALTH)     { if (getHealth() > max) setHealth(max); }
if (MAX_ABSORPTION) { if (getAbsorptionAmount() > max) setAbsorptionAmount(max); }
```

`MAX_ABSORPTION`'s registered default is **0.0**, so the absorption pool drains
itself. Regeneration holds no modifier at all and simply stops. **There is no
mod-side timer and nothing to clean up** — which is the whole reason for granting
real effects instead of imitating them.

**One cosmetic gap, and its fix.** Vanilla's lang defines `potion.potency.0`
through `.5` only, because no vanilla source produces an amplifier above 5 — so
level X would render as the raw key `potion.potency.9` in the inventory effect
list. Lang files from *every* namespace are merged into one map, so the mod's own
`assets/entropymod/lang/en_us.json` defines `.6`–`.9`. Worth knowing generally:
**a mod can supply missing `minecraft:` translation keys.**

**"Once per run" is a run flag, not the acquired set** — one
`optionalFieldOf("second_chance_used", false)` in `EntropyManager`'s existing
codec, exactly the store and shape Second Guess uses. **The key keeps its
pre-rename spelling deliberately:** renaming it would hand a fresh save back to
every world that had already spent one, since `optionalFieldOf` would find nothing
under the new name and default to `false`. The Java field name is cosmetic; the
codec key is not. Consuming does two things and **only the first enforces
"once"**:

1. The persisted flag is set. Survives respawn, relog and save/reload, and
   **re-acquiring cannot refund it**.
2. The id is dropped from `AcquiredEffects` — presentation, not enforcement, and
   it deliberately makes the effect eligible to be offered again, which the flag
   renders inert.

A design resting on the acquired set alone would eventually be wrong: the repeat
fallback can legitimately re-offer an already-taken effect once a phase's pool
empties. All of this is asserted, including that an *unspent* Heart also survives
a reload — the flag is not quietly defaulting to spent.

**`AcquiredEffects.remove` is new and is not a general un-pick.** Effects are
permanent; it exists for the one shape that consumes itself. Do not reach for it
to build a temporary effect.

#### Slippery Grip: the sprint-jump bypass, and the sweep that should have happened first
**Reported as "sprint-jumping ignores the curse". It did, and the cause was not a
broken mixin.** The hypothesis going in was a separate forward-velocity boost on
sprint-jumps; that was **confirmed**, and a second bypass was found alongside it
that turned out to matter more.

**Vanilla rewards sprinting in three independent places** — found by sweeping
`isSprinting()` across the movement path, which is the diagnostic that should
precede any attribute-based movement effect:

| # | site | what it grants | reads the attribute? |
|---|---|---|---|
| 1 | `LivingEntity.setSprinting` | `MOVEMENT_SPEED` x1.3 | yes — this is the modifier |
| 2 | `LivingEntity.jumpFromGround` | **flat +0.2 blocks/tick** forward | **no** |
| 3 | `Player.getFlyingSpeed` | air accel `0.02 -> 0.025999999` | **no** |

Bonus 2, javap-verified — the `isSprinting` branch of `jumpFromGround`:

```java
addDeltaMovement(new Vec3(-sin(g) * 0.2, 0.0, cos(g) * 0.2));   // g = yRot * 0.017453292f
```

**Bonus 3 is the one that made this severe**, and it is the durable finding:

```java
getFrictionInfluencedSpeed(f) =
    onGround() ? getSpeed() * (0.21600002f / (f*f*f))
               : getFlyingSpeed();
```

**While airborne `MOVEMENT_SPEED` is not consulted at all.** For the twelve-odd
ticks of every jump the compensator was not outvoted, it was *switched off* — and
a player who jumps continuously spends most of their time in that state. Note
`0.025999999 / 0.02` is `1.3`, the same 1.3 as the modifier, so vanilla applies
its sprint bonus twice by two unrelated routes.

**The magnitudes, from a per-tick model validated against three published vanilla
figures before anything was concluded from it** (walking 4.3172, sprinting
5.6123, sprint-jumping 7.1263 b/s — all reproduced from javap-verified constants;
the model is `src/harness/java/com/entropymod/harness/SprintModel.java`, and it is
Minecraft-import-free like `CropSchedule` and `TramplePath`):

| | vanilla | curse, speed modifier only | as shipped |
|---|---|---|---|
| walking | 4.3172 | 4.3172 | 4.3172 |
| sprinting, ground | 5.6123 | 2.1586 | 2.1586 |
| **sprint-jumping** | 7.1263 | **6.3298** | **2.7409** |

The middle column is the bug: **89% of vanilla's sprint-jump speed retained, 2.93x
the cursed ground sprint, and half again faster than simply walking** — the curse
was not evaded, it was inverted into a reason to sprint. **Scaling only the jump
impulse gives 5.0794 b/s, still faster than walking**; both bonuses are needed, and
fixing the reported one alone would have looked like a fix and not been one.

#### The fix: one factor, applied to all three, is exact rather than approximate
Vanilla's horizontal movement is **linear and homogeneous** in the triple (ground
acceleration, air acceleration, jump impulse) — the ground recurrence
`v <- 0.546 v + a` is linear, the airborne one `v <- 0.91 v + a` is linear, the
impulse is added, and the vertical motion that sets the airtime is untouched. So
scaling all three by one factor scales **every** sprinting motion by exactly that
factor, whatever the player is doing.

`SlipperyGripBehavior.sprintScale(v) = SPRINT_FRACTION / (1 + v)` = **0.5/1.3 =
0.3846**, and `compensatorAmount` is now *derived from it* (`sprintScale - 1`)
rather than restated, so the three halves cannot drift apart. Both new mixins read
it via `SlipperyGripSprint.sprintScaleFor`, off vanilla's **live**
`minecraft:sprinting` modifier — same discipline as the existing compensator.

**The check that proves it is the right rule, not merely a working one:**
`7.1263 / 5.6123 = 1.2698` and `2.7409 / 2.1586 = 1.2698`. Sprint-jumping is still
worth the same 27% over flat-out sprinting that it is in vanilla. **The system is
scaled, not clipped**, which is what keeps the movement legible instead of merely
slow — and it is asserted, so a later retune of one branch alone will fail.

#### Two more javap findings from that fix
- **`ServerPlayer` DOES override `jumpFromGround`** — exactly the subclass trap —
  but its bytecode opens with `invokespecial Player.jumpFromGround` before the
  jump stat and food exhaustion, so the super call is unconditional and a
  `LivingEntity` injection is always reached. Checked, not assumed.
- **`Player` overrides `getFlyingSpeed` and never calls `super`.** Targeting
  `LivingEntity.getFlyingSpeed` would build green and never run for a player.
  `Player` is the outermost override (`Avatar`, `ServerPlayer` and `LocalPlayer`
  do not override it), and its only consumer is the `getFrictionInfluencedSpeed`
  branch above — so nothing else can be disturbed.

**`@ModifyArg`, not `@Redirect`, when two sides share a call site.** The common and
client halves both target `jumpFromGround`'s single `addDeltaMovement(Vec3)` call;
**two `@Redirect`s on one instruction is an apply-time conflict**, whereas
`@ModifyArg` handlers chain and each side returns the vector untouched when it is
not the authority. Side scoping is still required (`ServerLevel` / `LocalPlayer`)
— see the shape rule in CLAUDE.md — because scaling twice would square the factor.

**Both client halves are load-bearing, unlike Slashed Pockets' cosmetic gap.**
Neither bonus is an attribute, so there is nothing for the server to sync; each
side computes its own, and a server-only fix would leave the player visibly
lurching at vanilla speed and be corrected only by rubber-banding.

**Known limit, stated rather than left to be found: creative flight is untouched.**
`getFlyingSpeed`'s `abilities.flying` branch rewards sprinting on a different basis
(x2, not x1.3) and flight ignores `MOVEMENT_SPEED` entirely, so a run holding
Creative Flight has always been able to fly out from under this curse and still
can. Extending the effect there is a decision about how two effects compose, not
part of repairing this one.

#### "Releasing Ctrl doesn't stop sprinting" — INVESTIGATED, verdict: vanilla. No fix.
Reported as a possible regression from the sprint mixins: releasing the sprint key
while still holding forward does not end the sprint; it persists until forward is
released too. **That is vanilla's own behaviour, unchanged by this project.**
Recorded because it looks exactly like a stuck-state bug the mixins could have
caused, and re-deriving the answer would cost a session.

**`Input.sprint()` is read EXACTLY ONCE in `LocalPlayer.aiStep`** — a grep of the
method's bytecode returns a count of 1 — and that one read is the *start* branch,
at offset 432:

```java
// offsets 425-441: the only consumer of the sprint key in the whole method
if (this.input.keyPresses.sprint()) this.setSprinting(true);

// offsets 443-481: the stop path, which never consults sprint()
if (this.isSprinting()) {
    if (this.isSwimming()) { if (shouldStopSwimSprinting()) setSprinting(false); }
    else                   { if (shouldStopRunSprinting())  setSprinting(false); }
}
```

and `shouldStopRunSprinting()`, read off its own bytecode:

```java
return !isSprintingPossible(getAbilities().flying)          // food <= 6, shallow water, restricted
    || !input.hasForwardImpulse()                           // <-- the actual stop condition
    || (horizontalCollision && !minorHorizontalCollision);
```

**So the sprint key is a latch, not a hold.** It *begins* a sprint; the only things
that *end* one are losing forward impulse, failing `isSprintingPossible`, or a
major horizontal collision. `ClientInput.hasForwardImpulse()` is exactly
`moveVector.y > 1.0E-5f` — pure input, no speed term — so "sprint persists until W
is released" is a literal restatement of the shipped stop condition. (The
double-tap-forward route via `sprintTriggerTime`, offsets 376-422, is a second
*start* path and equally irrelevant to stopping.)

**Ruling out this project, checked rather than argued:**

- **No project source calls `setSprinting(boolean)` at all.** Every occurrence in
  `src/` is a javadoc mention, a *read* of `isSprinting()` used as a guard, or the
  `@Inject(method = "setSprinting", at = @At("TAIL"))` declaration itself.
- **Neither sprint mixin can alter the flag or the argument.** Both are plain
  TAIL `@Inject`s with a bare `CallbackInfo` — no `cancellable = true`, no
  `ci.cancel()`, no `@ModifyVariable`, no `@Overwrite`. The only `@ModifyVariable`
  in the file is a javadoc line describing the **removed** first version, which
  *did* force the argument false. That version is gone, and this report is what
  its ghost would look like if it were not.
- **Nothing on the stop path reads speed.** `shouldStopRunSprinting`,
  `isSprintingPossible`, `isMovingSlowly`, `isMobilityRestricted` and
  `hasEnoughFoodToDoExhaustiveManoeuvres` between them contain **zero** references
  to `getSpeed`, `MOVEMENT_SPEED`, `getAttributeValue` or `getDeltaMovement`. So
  the compensator cannot reach the state machine even indirectly — which is the
  non-obvious half, since a speed-derived stop condition would have made Slippery
  Grip change sprint *timing* as a side effect of changing sprint *speed*.
- The one nearby piece of project code is `KeyboardInputMixin`, which rebuilds the
  `Input` record for Randomized Controls / Random Jump — and it passes
  `in.sprint()` through **unchanged**. (It does permute `moveVector`, so under
  Randomized Controls the key that supplies forward impulse is not W; that is that
  effect's documented behaviour, not this one's.)

**The general lesson, and the reason this is filed next to the sprint-jump
finding:** the previous session's bug was real and the mixins *were* at fault, so
the prior for "the mixins did it again" was reasonable — and wrong. The cheap
discriminator is to locate the vanilla state machine and count the reads of the
input in question **before** looking at the mod at all. One `grep -c` on
`aiStep`'s bytecode settled this; reasoning from the mixins would not have, because
their shape is consistent with either answer.

#### Phoenix Chambered Heart's remaining kit: Speed III, Blindness, the Wither sting
Added to the **same** trigger moment as the three grants above, not on any schedule
of their own.

| grant | amplifier | duration | what vanilla actually produces |
|---|---|---|---|
| Speed III | 2 | 600t (30s) | **+0.6 `ADD_MULTIPLIED_TOTAL`** on `MOVEMENT_SPEED` = **x1.6** |
| Blindness | **0** | 100t (5s) | nothing amplifier-sensitive at all — see below |
| `entity.wither.death` | — | — | played to the rescued player only |

**Speed III is exact, and needs no simulation.** `MobEffects`' `<clinit>` registers
Speed as `MOVEMENT_SPEED`, `0.20000000298023224`, `ADD_MULTIPLIED_TOTAL`, and
`AttributeTemplate.create` scales by `amplifier + 1` → **0.6000000089406967** at
amplifier 2. Ground speed is *linear* in the attribute, so the attribute multiplier
**is** the speed multiplier: **4.3172 -> 6.9075 b/s** walking, **5.6123 -> 8.9797**
sprinting. Amplifier 2 = displayed III by the same `"potion.potency." + amplifier`
convention confirmed for the level X grants, and it sits inside vanilla's own
`potion.potency.0`–`.5` lang range, so **no mod-side key is needed** (unlike the
level X grants).

Because the operation is `ADD_MULTIPLIED_TOTAL` it composes as a **product**:
sprinting during the window is `x1.3 x 1.6 = x2.08` of walking base, and **a run
also holding Slippery Grip keeps that curse intact** — the compensator is a
separate factor in the same product, so sprinting stays at half the (now boosted)
walking speed rather than one effect cancelling the other.

**Blindness's amplifier is 0 because no other value would mean anything**, and this
was verified as an *absence* rather than assumed from "it has no levels". Vanilla
registers it as a **bare `new MobEffect(HARMFUL, 2039587)`** — the plain class, not
a subclass — with **no `addAttributeModifier` call attached**. The harness drives
`createModifiers` at amplifiers 0–9 and asserts **zero** modifiers at every one, and
asserts the concrete class is `MobEffect` itself, so nothing can read an amplifier.
A higher value would change the tooltip and nothing else.

**The sound is player-only, and that took a packet.** `SoundEvents.WITHER_DEATH`
(`entity.wither.death`) is a plain `SoundEvent`, **not** a `Holder.Reference`, so it
is wrapped with `BuiltInRegistries.SOUND_EVENT.wrapAsHolder(...)` — asserted to come
back as `Kind.REFERENCE`, so the packet carries a registry id rather than inlining
the whole definition — and sent as a `ClientboundSoundPacket` to
`player.connection`.

**Every `Level.playSound` overload is a broadcast**, and this is the trap worth
recording: its `Entity` parameter is the player to **exclude**, not the one to
target, and vanilla's own `Player.playServerSideSound` passes `null` there, i.e.
tells everyone. Reaching for it would read as correct and be wrong. There is no
`playNotifySound` on `ServerPlayer` in this version. **Broadcasting instead is a
one-line change** to
`level.playSound(null, x, y, z, SoundEvents.WITHER_DEATH, SoundSource.PLAYERS, 1f, 1f)`.

#### Danger Sense: Glowing is on the MOB, and cannot be scoped to one observer
**Verdict: buildable as specified, with one real limitation that is not fixable
from an effect.** The mechanism was confirmed end to end rather than assumed:

```java
LivingEntity.isCurrentlyGlowing()                       // override
    = (!level().isClientSide && hasEffect(MobEffects.GLOWING))
      || super.isCurrentlyGlowing();

Entity.isCurrentlyGlowing()
    = level().isClientSide ? getSharedFlag(6) : hasGlowingTag;

LivingEntity.updateDirtyEffects() -> updateGlowingStatus()
    = setSharedFlag(6, isCurrentlyGlowing());
```

So the Glowing `MobEffectInstance` is applied **to the target mob, server-side**,
and drives shared entity flag 6. Two consequences:

- **No per-tick push of our own is needed.** `updateGlowingStatus` is reached from
  `updateDirtyEffects`, i.e. exactly when the effect set changes. Adding the
  effect is sufficient.
- **The glow is visible to EVERY player tracking the mob, not only the holder.**
  Flag 6 lives in `SHARED_FLAGS_ID` synced data. **There is no per-viewer glow
  anywhere in vanilla** — no API, no packet, nothing to piggyback on. Making it
  private would mean rewriting the tracked-data packet per connection: real work,
  not a flag. Reported rather than silently decided; this project is
  singleplayer-scoped so in practice there is no observable difference.

**Glowing is a bare `MobEffect(NEUTRAL, 9740385)`** — no subclass, no attribute
template — so like Blindness the amplifier is meaningless. Asserted as zero
modifiers at amplifiers 0–4.

#### Refresh-and-expire beats add/remove when you don't own the state
Danger Sense grants a **20-tick** glow and re-grants it every **10 ticks** while
the mob is in range. It never calls `removeEffect`.

**That is not laziness, it is the only correct option.** A blanket
`removeEffect(GLOWING)` would also strip a glow that came from a spectral arrow
or a command, and the effect has no way to tell whose glow it is. Re-granting
cannot damage another source either: `MobEffectInstance.update` keeps the
stronger/longer of the two, so a spectral arrow's 10 seconds survives our
1-second refreshes intact.

**The stated cost is a trailing glow of ≤1s after a mob leaves the radius**, which
also removes boundary flicker. The harness pins `scan < duration <= 2 * scan` so a
later retune cannot silently reintroduce either flicker or a long lingering glow.

**Generalises: when granting vanilla state you do not exclusively own, prefer a
short refreshed grant over explicit removal.** Expiry is free, composes with other
sources, and needs no bookkeeping — the same reason Phoenix Chambered Heart grants
real `MobEffectInstance`s instead of imitating them.

#### An entity query is O(entities), NOT O(volume) — the radius comparison that misleads
The instinct is to compare Danger Sense's 32-block radius against Green Thumb's 8
and worry. **The comparison is invalid**, and the reason is the durable finding:

| | Green Thumb | Danger Sense |
|---|---|---|
| what is scanned | block positions | entities |
| box | 17³ = **4,913** | 65³ = **274,625** |
| real cost | O(volume) | **O(entities in the box)** |

`ServerLevel.getEntitiesOfClass(AABB, ...)` is served by the level's entity
section storage, so cost is proportional to entities *present*, bounded by the mob
cap — typically a handful. A 274,625-position block sweep would be infeasible;
the entity query at the same radius is nearly free, and **widening the radius is
cheap in a way widening a block scan never is.**

Cost, in the project's usual form: **O(players holding it × entities in a 65-block
box) every 10 ticks**, each hit costing one `instanceof`, one squared-distance
compare and at most one `addEffect`. Nothing runs when nobody holds it.

**The AABB is a box and the effect is a sphere**, so a `distanceToSqr` filter
follows the query. Without it the corners reach `32 × √3 = 55.4` blocks — a **73%
over-range** that would be nearly impossible to spot in play. Asserted in both
directions, including at the corner distance specifically.

**"Hostile" is `net.minecraft.world.entity.monster.Enemy`**, vanilla's own marker
interface — not `MobCategory.MONSTER`, which is a *spawning* category answering a
different question. Boundary cases that follow, recorded so they are not filed as
bugs: endermen and zombified piglins **do** glow; wolves, bees and iron golems do
**not**, even when actively hostile, because their hostility is a state rather
than a type.

#### Double Jump: `makeJump()` cannot work mid-air, and jumping is client-only
**Two findings, both of which redirected the implementation.**

**1. Random Jump's mechanism does not transfer.** The obvious move is
`ClientInput.makeJump()`. `LivingEntity.aiStep`'s jump block is, javap-verified:

```java
if (this.jumping && this.isAffectedByFluids()) {
    ...
    else if ((this.onGround() || (inWater && fluidHeight <= threshold))
             && this.noJumpDelay == 0) { this.jumpFromGround(); this.noJumpDelay = 10; }
} else { this.noJumpDelay = 0; }
```

The jump is **gated on `onGround()`**. A forced press while airborne reaches that
branch and is discarded — the effect would appear broken rather than absent.

**`jumpFromGround()` itself has no such gate.** Its only guard is
`getJumpPower() <= 1e-5`; it then sets `deltaMovement.y = max(jumpPower, y)` and
adds the sprint impulse. Calling it directly mid-air is a real jump with correct
height, sprint behaviour and (on `ServerPlayer`) the jump statistic and food
exhaustion. Same discipline as Green Thumb: **drive the schedule yourself, perform
the change through vanilla's own transition.**

**2. Player jumping is entirely client-driven.** `LivingEntity.jumping` is written
in exactly one place for a player — `LocalPlayer.applyInput()` — and
**`ServerPlayer` never writes it at all** (zero `putfield`s). The server never runs
the jump block for a player and has no jump state machine to keep in step with. So
there is **no server half to write** and none of the two-sided scoping problem
Slippery Grip's mixins needed; the motion reaches the server as ordinary movement
packets, exactly as a normal jump does.

Driven from `END_CLIENT_TICK` rather than a fifth mixin on the movement path. The
jump is a velocity change consumed by the next tick's movement, so the one-tick
offset is imperceptible.

#### The charge is read from `onGround()`, and every refused state was decided
**Reading the charge from `onGround()` rather than counting jumps is what makes a
third jump impossible by construction** — there is no counter to get out of step,
and walking off a ledge without jumping still grants exactly one air jump.

The rising edge is also consumed on the ground, which is what forces a
release-and-press for the second jump instead of a held key spending the charge on
the tick after takeoff.

**Every state was decided explicitly**, because "does nothing" and "is broken" are
indistinguishable to a player:

| state | decision | why |
|---|---|---|
| ordinary fall / after a normal jump | **jumps** | the effect |
| creative flight (`abilities.flying`) | **refused** | vertical movement already unlimited; a spent charge would be an invisible no-op |
| elytra (`isFallFlying()`) | **refused** | an upward poke mid-glide fights the flight model and reads as a stutter |
| water / lava | **refused** | vanilla already routes a held jump to `jumpInLiquid` (continuous upward motion); spending the charge there would silently disarm the effect for the moment the player surfaces |
| ladder / vine (`onClimbable()`) | **refused** | already free vertical movement, and a charge spent there is one not available on the fall after |
| riding | **refused** | the jump would apply to the passenger, not the mount |

**In every refused state the charge is NOT consumed** — that is the half that
matters, and it is asserted. So is the follow-on: holding the key *through* a
refused state and then leaving it must not auto-fire a jump the player never
asked for, which is why the held flag is updated on every tick regardless of
outcome.

`DoubleJumpState` is **free of Minecraft imports** — same discipline as
`MovementScramble` and `TramplePath` — specifically so all of the above is driven
headlessly against shipped code. Note the client driver itself is **not**
harness-reachable: the harness classpath is the main source set only, the same
property that pushed the keybind snapshot to server-side persistence.

#### Double Jump's real bug: the state machine was right, the SAMPLING POINT was wrong
Reported as "it just instantly jumps, and doesn't let me double jump". **This is
the most valuable bug in the project so far, because it shipped with a green
harness and the harness was testing a state that cannot occur.**

**Root cause — hypothesis (a), with a precise mechanism.** Inside
`LivingEntity.aiStep`, javap-verified by offset:

| offset | what |
|---|---|
| 293 | profiler `push("jump")` |
| **460** | **`jumpFromGround()`** |
| 486 | profiler `push("travel")` |
| **615** | **`travel()`** → `travelInAir` → `move()` — *and `move()` is what writes `onGround`* |

The driver runs on `END_CLIENT_TICK`, i.e. after all of that. **So on the very
tick the player jumps from the ground, `onGround()` already reads false.** What
the driver actually observed:

| tick | in game | observed | old machine |
|---|---|---|---|
| N-1 | standing, key up | held=false, ground=true | recharge → 1 charge |
| N | key pressed; vanilla jumps; travel lifts the player | held=**true**, ground=**false** | rising edge + airborne + charge → **air jump fires here** |
| N+1 | rising, key held | held=true, ground=false | not rising; charge already spent |

Both jumps landed on the same tick. The visible result is **not** a doubled leap —
`jumpFromGround` sets `deltaMovement.y = max(jumpPower, y)`, so it re-raises y to
full jump power one tick in rather than adding, giving a slightly longer,
stronger-feeling single jump. (The sprint impulse **is** additive, so a sprinting
player got that twice.) And the charge was gone before the player was meaningfully
airborne. That is exactly both halves of the report.

**Note (b) was a real symptom but not the cause** — the charge *was* consumed
before the player could use it, but as a consequence of (a), not independently.

**The fix** is one guard, `leftGroundThisTick = wasOnGround && !onGround`, encoding
the rule: *a press first observed on the tick the player left the ground belongs to
the ground jump.* It is consumed, not spent. The cost is bounded and correct rather
than a fudge — pressing jump on the exact tick you walk off a ledge is a press
vanilla itself turned into a ground jump, because `onGround()` was still true when
`aiStep`'s jump block ran.

**The durable lesson, and it generalises well beyond this effect:**

> **A headless test of a tick-sampled state machine must model the SAMPLING POINT,
> not the idealised event sequence.**

The old test fed `tick(held=true, onGround=true)` for the press tick — a
combination `END_CLIENT_TICK` can never observe. The logic was correct in
isolation and wrong in place. This is a *fifth* failure mode alongside the four in
CLAUDE.md, and unlike those it is not about mixins at all: it is about where in the
tick your code reads state. **When adding a tick-driven effect, first establish
what your hook observes relative to the vanilla code you care about — by offset,
not by intuition.** The harness now drives the real order, and the regression check
fails against the old implementation.

Corollary already applied: the effect now emits one DEBUG line per air jump. It has
no sound, no message and no HUD, so "fired on the wrong tick" and "did not fire"
were indistinguishable from the player's seat — the same reason Clumsy Digger
logs its procs.

#### Ore Sense: render mechanism mapped in full — and why it STOPPED at the renderer
> **Supersedes the previous Ore Sense entry below on two points, both of which
> were wrong.** Read this one.

**The detection layer is built and tested; the renderer is NOT, and the effect is
deliberately not registered** in either `EffectRegistry` or `EffectBehaviors` — a
registered effect that draws nothing would be a dead entry in the roll pool, which
is worse than an absent one. The harness asserts that absence so it cannot be
half-shipped by accident.

**Correction 1 — `debugFilledBox()` is NOT see-through.** The previous entry
called `RenderPipelines.DEBUG_FILLED_BOX` "the nearest vanilla precedent" for
render-through-terrain. It is not a precedent at all: `DEBUG_FILLED_SNIPPET` is
built with `new DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, false)` — an
ordinary depth **test**, merely without depth **write**. Debug boxes are occluded
by terrain like anything else. The see-through value is `CompareOp.ALWAYS_PASS`,
which exists but is used by no render type that is publicly reachable.

**Correction 2 — the whole construction path is closed to mods.** This is the part
that stopped the work, and none of it was visible last session:

| what you need | visibility |
|---|---|
| `RenderPipeline.builder(Snippet...)` | **public** ✅ |
| `RenderPipeline.Builder.with*` (shaders, format, depth, cull, colour target) | **public** ✅ |
| `RenderSetup.builder(RenderPipeline)` / `RenderSetupBuilder` | **public** ✅ |
| **`RenderType.create(String, RenderSetup)`** | **package-private** ❌ |
| `RenderType`'s constructor | private ❌ |
| `RenderPipelines.MATRICES_PROJECTION_SNIPPET`, `DEBUG_FILLED_SNIPPET` | **private** ❌ |
| `RenderPipelines.register`, `PIPELINES_BY_LOCATION` | **private** ❌ |
| a Fabric API for registering a render pipeline | **does not exist** ❌ |

`FabricRenderPipeline` exists in `fabric-rendering-v1` and is **not** it — its only
member is `usePipelineDrawModeForGui()`. A scan of the Fabric API jars found no
pipeline-registration hook of any kind.

So a working see-through render type needs **two accessor mixins into vanilla
rendering internals**: an `@Invoker` for `RenderType.create`, and an `@Accessor`
for one of the private snippets (the snippets carry the projection-matrix uniforms
the `core/position_color` shaders require — building a pipeline from a bare
`builder()` means declaring those uniforms by hand). That is a materially
different risk class from anything this project has done: the Camera mixin, its
only render-adjacent precedent, is a single `@Redirect` on a public method.

**Shader compilation is the remaining unknown.** `ShaderManager.apply` calls
`RenderPipelines.getStaticPipelines()` and `GpuDevice.precompilePipeline(pipeline,
shaderSource)` on resource reload — registered pipelines only. `GpuDevice
.precompilePipeline(RenderPipeline)` *is* public and `clearPipelineCache()` exists,
which strongly implies the device caches compiled pipelines keyed on the object and
would compile on first use. **Strongly implies is not verified**, and this project
does not ship rendering on an inference.

**What IS built and verified (headlessly):**

- **The block set is a mod-supplied tag**, `entropymod:ore_sense_targets`, because
  **there is no `#minecraft:ores` tag** — `BlockTags` has eight per-material tags
  and no union. The JSON is those eight plus **`minecraft:ancient_debris` and
  `minecraft:nether_quartz_ore`, which are in none of them and are included
  deliberately** — an ore sense silent on ancient debris would read as a bug, not a
  scope decision. `nether_gold_ore` needs no special case; it is already inside
  `#minecraft:gold_ores`, and the harness pins that by reading vanilla's shipped
  JSON rather than asserting it.
- **Client-side only.** The client already holds the blocks in its `ClientLevel`,
  so there is no server involvement and no payload beyond the existing
  `ClientEffectsPayload` bit.
- **Cost: O(4,913) block reads every 20 ticks** — deliberately the identical volume
  and cadence to Green Thumb's rescan, this project's known-safe size, amortising
  to ~246 reads per tick. The renderer would read the **cache**, never rescan:
  scanning per frame would be 4,913 × 60 ≈ **295,000 block reads a second**.
- **The sphere-vs-box correction is applied and asserted.** A bare 17-cube reaches
  `8 × √3 = 13.86` blocks at its corners — a 73% over-range that would read as "the
  radius is just bigger than 8". All eight corners are asserted rejected.
- `BlockPos.betweenClosed` yields a **mutable cursor**, so entries are stored
  `.immutable()` — the same trap Green Thumb's sweep documents.

**To finish it in one pass:** add the two accessor mixins; build a pipeline from
the accessed snippet with `DepthStencilState(ALWAYS_PASS, false)`, QUADS,
`POSITION_COLOR`, `core/position_color`, `BlendFunction.TRANSLUCENT`; wrap it via
`RenderSetup.builder(pipeline).createRenderSetup()` and the invoked
`RenderType.create`; draw from `LevelRenderEvents.AFTER_TRANSLUCENT_TERRAIN` using
`LevelRenderContext.poseStack()` + `bufferSource()`; call
`GpuDevice.precompilePipeline` once at client init if first-use compilation turns
out not to happen. **Then look at it before calling it done.**

#### Ore Sense: INVESTIGATED, NOT BUILT — findings, and why it stopped here
**No code was written for this effect and it is not registered.** The
investigation is complete and is recorded because it is the expensive part; the
renderer is not, and shipping unverified render code would break this project's
own rule that an effect is done when a change has been *observed*, not when it
compiles.

**Finding 1 — there is no `#minecraft:ores` tag.** `BlockTags` ships only
per-material tags: `COAL_ORES`, `IRON_ORES`, `COPPER_ORES`, `GOLD_ORES`,
`REDSTONE_ORES`, `LAPIS_ORES`, `DIAMOND_ORES`, `EMERALD_ORES`. There is no union
tag, so "ores" must be assembled from those eight — and note that **ancient debris
and nether quartz ore are in none of them**, while `GOLD_ORES` *does* include
nether gold ore. Resolve the contents by reading the shipped tag JSON out of the
jar, exactly as Clumsy Digger's harness check does for
`#minecraft:enchantable/mining`; do not hardcode a list.

**Finding 2 — `WorldRenderEvents` does not exist.** Every world-render tutorial
online uses `net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents`. In
this Fabric API version (`fabric-rendering-v1` 23.3.1) that class is **gone**,
replaced by `...rendering.v1.level.LevelRenderEvents`, whose events take a
`LevelRenderContext` (`poseStack()`, `bufferSource()`, `submitNodeCollector()`) or
a `LevelExtractionContext` (`level()`, `camera()`, `deltaTracker()`). Same shape
of trap as `HudRenderCallback` → `HudElementRegistry`. The usable hooks are
`AFTER_TRANSLUCENT_TERRAIN` and `BEFORE_GIZMOS`.

**Finding 3 — the render stack itself is different, and this is what stopped it.**
`RenderType` has moved to `net.minecraft.client.renderer.rendertype.RenderType`
and no longer exposes static factories like `lines()`; render state is now built
from `RenderPipeline` **snippets** (`RenderPipelines.LINES_SNIPPET`,
`DEBUG_FILLED_SNIPPET`) with an explicit `withDepthStencilState(DepthStencilState)`,
and drawing goes through `RenderType.create(String, RenderSetup)` and a
`SubmitNodeCollector`. `ShapeRenderer.renderShape(PoseStack, VertexConsumer,
VoxelShape, ...)` is the box-drawing helper and still exists.

So the see-through requirement means **defining a custom `RenderPipeline` with
depth testing disabled** — `RenderPipelines.DEBUG_FILLED_BOX` is the nearest
vanilla precedent — rather than picking an existing constant. That is a real piece
of work against a stack that changed this version, and none of it is verifiable
without launching the game.

**Finding 4 — cost, and it is the good news.** Unlike Danger Sense, this genuinely
is a volume scan: an 8-block radius is a 17³ box = **4,913 positions**, exactly
Green Thumb's number. But it can be **entirely client-side** — the client already
has the blocks in its own `ClientLevel`, so no server involvement and no payload
are needed beyond the `ClientEffectsPayload` bit saying the run holds the effect.
A cached scan on the Green Thumb cadence (rescan every 20 ticks, redraw from the
cached list every frame) keeps per-frame cost at O(ores found), which for a real
vein is tens of boxes. **Do not scan per frame.**

**Recommended shape if it is picked up:** `OreSenseScan` in the main source set,
Minecraft-free where possible so the radius boundary is harness-testable (the same
sphere-vs-box leak Danger Sense guards against applies here: an unfiltered 17³ box
reaches `8 × √3 = 13.9` blocks); the tag union resolved from the eight shipped
tags; a client-side cache refreshed every 20 ticks; and a custom no-depth pipeline
registered once at client init.

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


#### `SafeSpawn` — the shared spawn-position groundwork. Reuse it, don't rewrite it
The first two effects that put a threatening entity into the world (Unstable and
Creeper Magnet) both needed "where near this player can something actually be
spawned". **Solving it twice would have produced two subtly different answers**,
and a wrong answer is severe and hard to see: an entity in stone suffocates or is
shoved through the wall, and one behind a wall gives no warning at all. It lives
in `com.entropymod.entropy.spawn.SafeSpawn` and **is the thing to reach for for
any future spawn-based effect** (Loyal Pack, The Entourage, and the rest of the
companion cluster).

**The position test is vanilla's own, and naming it explicitly is load-bearing.**
`SpawnPlacementTypes.ON_GROUND.isSpawnPositionOk(level, pos, type)` is what the
natural spawner uses; read out of `SpawnPlacementTypes$1`'s bytecode it is four
things: inside the world border, `below.isValidSpawn(...)` (solid ground this type
can stand on), and `NaturalSpawner.isValidEmptySpawnBlock` at both the feet and
the head — which in turn requires a non-full collision shape, no fluid, not a
signal source, not in `#prevent_mob_spawning_inside`, and not
`type.isBlockDangerous`. So "not embedded" and "on solid ground" come from the
definition the rest of the game already uses, including for modded blocks and
datapack tag edits.

**Do NOT use `SpawnPlacements.isSpawnPositionOk(type, level, pos)` instead.** That
looks the placement type up per entity, and anything with no natural spawn — TNT
included — gets `NO_RESTRICTIONS`, i.e. *anywhere*, which is the one answer this
must never give. `ON_GROUND` is used for both effects deliberately, TNT included:
TNT dropped into mid-air falls somewhere unpredictable, while TNT on the floor the
player is standing on is a threat they can judge.

Four more rules, each of which would be invisible in play if wrong:

- **Line of sight is required**, via the same clip `LivingEntity.hasLineOfSight`
  uses (`ClipContext.Block.COLLIDER`, `Fluid.NONE`, from the player's eye,
  requiring `HitResult.Type.MISS`). It is a *visibility* test, not a facing test —
  a creeper appearing behind an unturned back still passes. What it rules out is
  the wall, and that is the counterplay guarantee for both effects.
- **The distance band is re-checked after the vertical search.** The horizontal
  offset is drawn in the band, then the search moves the candidate up to 4 blocks
  up or down — so a candidate 5 out and 4 down is really 6.4 away. Same
  box-vs-sphere leak Danger Sense and Ore Sense guard against.
- **`Level.isLoaded(BlockPos)`, not `hasChunkAt`.** It is `isInValidBounds()` AND
  a loaded chunk, so it rejects a candidate the vertical search pushed past the
  world floor or ceiling as well as one in an unloaded chunk. (`hasChunkAt`
  answers only the second, and its underlying `hasChunk` is deprecated in this
  version — the only deprecation warning this project has hit.)
- **`Mth.floor`, not a cast**, for the block coordinate. A cast truncates toward
  zero and is off by one everywhere west or north of the origin.

**Returning `null` is a real outcome, not an error.** A player sealed into a 1x2
shaft has nowhere valid; the trigger is spent, a DEBUG line is logged and the next
interval asks again. Callers must not retry in a loop. Cost is bounded: at most 24
horizontal candidates × 9 vertical steps of a few block reads, and a clip only for
a candidate that already passed everything else — once per 30 s per player.

#### `SpawnSchedule` — Random Jump's timer, generalised rather than re-typed
`com.entropymod.entropy.spawn.SpawnSchedule<K>` is `ClientRunState.tickForcedJump`
lifted out and parameterised: a per-key countdown that re-rolls in `[min, max]` on
every fire. **A fixed cadence is `min == max`**, which makes the re-roll
`nextInt(1)` and therefore always 0 — Unstable's fixed 30 s and Creeper Magnet's
30 s–2 min run through identical arithmetic with no second branch to keep in step.

Minecraft-import-free, same discipline as `CropSchedule`, `TramplePath`,
`MovementScramble` and `DoubleJumpState`, and the `Random` is injectable — because
**a broken cadence is close to undetectable in play.** "A hazard appeared" looks
the same whatever the gap was, so a schedule that draws once and repeats that
number forever is indistinguishable from a working one without a stopwatch. The
harness therefore collects 200 real gaps and asserts *spread*, not just range.

Two rules it exists to hold, both asserted:

- **The first trigger is a full interval away.** A brand-new key schedules on its
  first tick rather than firing on it — a curse that fires the instant it is
  picked reads as part of the pick, not as a recurring hazard.
- **`retainAll` is unconditional.** The obvious guard ("only prune when
  `remaining.size() > live.size()`") is wrong and the harness caught it: one player
  leaving while another joins leaves the sizes equal and the departed timer behind
  forever. Callers pass a `Set` so the retain stays a hash lookup per entry. The
  same latent guard exists in `BlightTouchedTrample.forgetDepartedPlayers`, where
  a stale trail is harmless — but do not copy it into anything new.

#### Unstable: the fuse is vanilla's, and both ends of the distance band are cliffs
30-second fixed cadence, **80-tick fuse**, **5.0–7.0 blocks**. It is a real
`PrimedTnt` built with the same public constructor `TntBlock.prime` uses, added
with `addFreshEntity`, followed by the same `SoundEvents.TNT_PRIMED` and
`GameEvent.PRIME_FUSE`. Nothing is imitated: `explosionPower` stays vanilla's
`4.0F`, the explosion is `PrimedTnt.tick`'s own, so the `tntExplodes` gamerule,
terrain damage, knockback and damage source are all vanilla's.

**Owner is `null`, deliberately** — what `TntBlock.prime(Level, BlockPos)` passes
for TNT nobody lit. `EntityReference.of(null)` returns null cleanly (bytecode-
verified), and the consequence is the death message: *"Player blew up"* rather than
*"Player was blown up by Player"*.

**The fuse is 80 ticks because that is `PrimedTnt`'s own default**, not because 4
seconds tested well. Reusing vanilla's number means the player's already-trained
TNT timing transfers unchanged; any other value makes correctly-remembered timing
wrong and reads as broken rather than as difficult. It is set explicitly from
`UnstableBehavior.FUSE_TICKS` anyway, so the constant is the authority.

**The distance band comes out of vanilla's own damage formula**, read off
`ExplosionDamageCalculator.getEntityDamageAmount`:

```
maxDist = 2 * radius                 // radius 4.0 -> 8.0
d       = distance / maxDist
impact  = (1 - d) * seenFraction
damage  = (impact^2 + impact) / 2 * 7 * maxDist + 1
```

At full exposure, for a player who does nothing at all:

| distance | damage | |
|---|---|---|
| 4.0 | 22.00 | **lethal from full** |
| **5.0 (min)** | **15.44** | 7.7 hearts |
| 6.0 | 9.75 | 4.9 hearts |
| **7.0 (max)** | **4.94** | 2.5 hearts |
| 8.0 | 1.00 | the trailing `+1`, and nothing else |

**Both ends are cliffs and neither is visible from the constants.** The minimum is
5.0 because the design criterion is *ignoring it completely, from full health,
unarmoured, must cost most of the bar and must not kill* — and 4.0 kills. The
maximum is 7.0 because at 8.0 the blast does nothing, so a wider band would spend
half its triggers on pure terrain vandalism. Both are asserted, including the
one-block-either-side comparison, so a retune cannot lose the reasoning.

**Counterplay = true, across the whole 25–50 band rather than only its top.**
Escaping means reaching 8.0 blocks — 3 blocks from the worst case — which at the
project's own measured **4.3172 b/s** walking figure (`SprintModel`) takes
**0.69 s** against a 4.0 s fuse. Even after half a second of reaction there are
**over 2.5 seconds of slack**. The warning is loud and omnidirectional:
`TNT_PRIMED` at volume 1.0 has `SoundEvent.getRange` 16 blocks, more than twice
the maximum spawn distance, plus vanilla's per-tick smoke plume, plus `SafeSpawn`'s
line-of-sight requirement. **The two properties that carry the verdict are
independent of entropy** — the minimum-distance blast is non-lethal from full, and
the escape costs a fifth of the time available — so nothing about it gets less fair
later in a run. Stated rather than hidden: a player already below four hearts who
ignores a minimum-distance trigger dies, which is the same bar Flamboyant clears.

#### Creeper Magnet: a mob's ground speed is its MOVEMENT_SPEED **squared**
30 s–2 min re-rolled cadence, **20 ticks (exactly 1.0 s) of Invisibility**,
**8.0–12.0 blocks**. Built with `EntityType.CREEPER.create(level,
EntitySpawnReason.EVENT)` plus vanilla's own `finalizeSpawn` against the position's
real `DifficultyInstance`, so it keeps the entire goal set from
`Creeper.registerGoals`. Two omissions are the point:

- **`setPersistenceRequired()` is NOT called.** One creeper every 30–120 seconds
  forever would fill the world if none could ever despawn.
- **`checkSpawnRules` is NOT consulted.** That is the light-level gate for
  *natural* spawning; honouring it would turn this into "a creeper appears, but
  only at night". `EntitySpawnReason.EVENT` says this is a summon.

The invisibility is granted with **`showParticles = false`** — invisibility *with*
particles surrounds the creeper in telltale motes and defeats the whole point.

**The claim that decides whether 1 second is an appearance or a stalk is
arithmetic, and it turns on a non-obvious vanilla detail.** `Mob.setSpeed(f)` calls
`super.setSpeed(f)` **and `setZza(f)`, so a mob's forward input is its speed value
rather than the normalised 1.0 a player's `ClientInput` supplies** — and
`moveRelative` multiplies the two. **A mob's effective ground acceleration is
therefore `speed` squared, not `speed`.** A creeper at 0.25 is `0.0625`, against a
player's `0.1`:

| | accel | steady-state |
|---|---|---|
| creeper chasing | 0.0625 | **2.70 b/s** |
| player walking | 0.1 | 4.32 b/s |
| creeper, read the player way | 0.25 | 10.79 b/s — **4x overstated** |

That is also the sanity check on the finding: creepers really are slower than a
walking player, which the raw attributes alone would deny. From rest a creeper
covers **2.54 blocks** in the whole invisible second, so from the 8-block minimum
it is still **5.46 blocks** away when it becomes visible, against `SwellGoal`'s
3-block ignition radius (`distanceToSqr < 9.0`). **A 2.46-block margin — the window
is far too short to be a threat in its own right, which is what makes it read as an
appearance.**

**`Attributes.FOLLOW_RANGE`'s registered default is 32.0, but
`Mob.createMobAttributes()` overrides it to 16.0.** This effect's first draft set
the maximum spawn distance to 16 on the strength of the 32.0 default and **the
harness caught it**. The distinction is not cosmetic, because of the same
acquisition/retention asymmetry recorded under Exposed: `TargetingConditions.test`
scales follow distance by visibility when *acquiring*, but
`TargetGoal.canContinueToUse` compares against the **raw** range when *retaining* —
so a creeper spawned at 16 blocks is handed a target and drops it on the next tick.
12.0 leaves a four-block margin. **General rule: read a mob's attribute from
`DefaultAttributes.getSupplier(EntityType.X)`, never from the attribute's own
`getDefaultValue()`.**

The player is set as the initial target. Not a new capability —
`NearestAttackableTargetGoal` would acquire them within a tick or two at these
distances — it is what makes the trigger reliable instead of occasionally producing
a creeper that strolls off. Vanilla still owns releasing it.

**Both effects are SURVIVAL, and sharing the category is the decision.**
Anti-stacking is keyed on category, and "a lethal hazard materialises next to you
on a timer" is one kind of thing rather than two; a run holding both would be
qualitatively different from a run holding either. Unstable's cadence is fixed and
Creeper Magnet's is random for a matching reason: TNT is a threat you have to
*escape*, where planning around the clock is part of the counterplay, while a
creeper is a threat you have to *notice*, where unpredictability is the whole
texture.

#### "Fired once, then silence" — the schedule was innocent. Read this before suspecting one.
Reported as: Unstable and Creeper Magnet held together, Creeper Magnet fired
exactly once and then nothing for 5+ minutes, Unstable apparently never. The
natural hypothesis — and the one that was put to this session — was that
`SpawnSchedule` schedules once, fires once and never re-arms, since a shared
mechanism failing identically for two effects with different intervals beats two
coincidentally identical bugs. **That hypothesis was wrong, and `run/logs/debug.log`
refuted it in one grep.**

```
[20:19:00] Creeper Magnet: no safe spawn position near Player215
[20:19:53] Creeper Magnet: no safe spawn position near Player215     gap  53 s
[20:20:34] Creeper Magnet: no safe spawn position near Player215     gap  41 s
[20:21:47] Unstable: no safe spawn position near Player215           grant +30 s
[20:22:17] Unstable: no safe spawn position near Player215           gap  30 s
[20:22:20] Creeper Magnet: no safe spawn position near Player215     gap 106 s
[20:23:40] Unstable: no safe spawn position near Player215           gap  83 s
[20:24:10] Unstable: no safe spawn position near Player215           gap  30 s
```

Every Creeper Magnet gap is inside [30 s, 120 s]; every logged Unstable gap is a
multiple of exactly 30 s. **Both schedules were firing perfectly, the entire
time.** The gaps that are *missing* from the log are the successful ones — the
spawners log only on failure — which is also how you can read off that Unstable
did fire twice (20:22:47 and 20:23:17, silent) and Creeper Magnet once, matching
the one creeper the player saw.

**The real fault: `SafeSpawn.findNear` returned null on roughly three triggers in
four**, and the trigger was consumed. "Fires reliably but usually finds nowhere"
and "stopped firing" are the same thing from the player's seat.

**The lesson worth keeping is about where to look, not about schedules.** The
cadence was *already* covered — the shipped harness drove 200 consecutive Creeper
Magnet gaps and asserted their spread. What was not covered was `SafeSpawn`, and
it could not be: as first written every line of `findNear` needed a `ServerLevel`,
so no part of its geometry was harness-reachable. **The tested component was the
one that worked.**

#### Diagnosing it needed the WORLD SAVE, because the log line did not say why
`"no safe spawn position near X"` records *that* the search failed and not *which
rule rejected*. With four gates (chunk loaded, vanilla's ON_GROUND test, the
distance band, line of sight) that is four hypotheses and no way to choose. The
root cause had to be reconstructed instead from `run/saves/<world>/`:

- `players/data/<uuid>.dat` gave the player's real position — **(-15.3, 112.0,
  -83.5), Survival, on ground, overworld**. Y=112 is a peak.
- the region file's packed `MOTION_BLOCKING` heightmap gave the actual terrain:
  ground falling away to **-10 and rising to +6** within twelve blocks.

That is a genuinely useful technique and worth keeping — **`level.dat`, `players/data/*.dat`
and the region heightmaps are readable with about 40 lines of Python, and they
answer "what did the world actually look like where this happened"** in a way no
log can. But it should not have been necessary. This is the same lesson CLAUDE.md
already records for `/entropyhistory` — *diagnostic output that does not say why is
indistinguishable from no diagnostic output* — and it was re-learned at the cost of
a session. `SafeSpawn.Attempt` now carries per-gate rejection counts and both
spawners log them.

#### Three real defects in `SafeSpawn`, one of them measured against that terrain
**1. `VERTICAL_SEARCH` was 4, and that is not enough for terrain people stand on.**
Measured against the real heightmap around the player's actual position,
**34.4% of candidate columns in both bands had their surface more than 4 blocks
from the player's Y** — so for a third of the map the search looked at, no step in
the window ever reached ground and the column was rejected without any of the
later gates running. At 8 the same terrain admits **93%**. Now 8. Interesting
terrain is exactly where players stand; a window that only works on flat ground is
not a window.

**2. The band was drawn horizontally and tested in 3D.** The radius was drawn as a
horizontal distance, the vertical search then moved the candidate up to four
blocks, and the resulting *3D* distance was re-checked against the same band — so
**finding valid ground could disqualify a column for having been found.** The two
rules were fighting each other, and on the test terrain this removed a further
~9% on top of everything else. It is also the wrong reading of the design: "TNT
appears 5 to 7 blocks away" means five to seven blocks *across the ground*; nobody
counts the drop down a slope. The band is now horizontal
(`SafeSpawn.withinBand`), the vertical offset is bounded separately, and the
horizontal re-check after flooring is kept — flooring still moves a candidate up
to ~0.7 blocks radially, so without it the band leaks at both ends.

**3. Line of sight aimed at the ground, not at the entity.** The target was the
centre of the *feet* block — half a block above the surface — so the ray grazed
the terrain for its whole length and clipped on any rise or rounded slope. That is
not what the question means: the player would have seen the creeper's head over
the rise perfectly well. Aim points are now taken up the spawned entity's own
height (`AIM_FRACTIONS = {0.5, 1.0}`) and the candidate passes if **any** is
visible. In every model run, this was the single dominant rejection.

Modelled against the exact terrain that produced the bug, the three together
roughly double the per-candidate acceptance rate — Unstable **14.5% → 26.0%**,
Creeper Magnet **31.6% → 48.7%**. **Stated honestly: that model under-predicts the
real failure rate** (it predicted ~2% total failure for the shipped version
against ~75% observed), because a heightmap proxy is much more forgiving than a
real `ClipContext` traversal against actual collision shapes. The improvement
direction is solid; the absolute numbers are not evidence, and the new per-gate
log line is what will settle the remainder.

#### The fix that makes the SYMPTOM impossible rather than rarer
`SpawnSchedule.rearm(key, ticks)`, called by both spawners when a trigger fires
but finds nowhere to put anything: **40 ticks instead of the whole interval.**
Even if the position search still fails sometimes, the worst case becomes "fires a
bit late" rather than "silent for two minutes" — and standing somewhere
permanently hopeless costs one bounded search every two seconds, not one per tick.
It cannot distort a working cadence, because it is only reachable on a failure;
a successful trigger keeps the interval `tick` drew.

#### PERMANENT TESTING REQUIREMENT for any scheduled or recurring effect
1. **Drive MANY consecutive cycles, not one, and compare late behaviour to
   early.** Both effects now run 2000 consecutive fires (16h40m of play at
   Unstable's cadence), asserting the timer is still armed after the last one and
   that the last 1000 gaps have the same mean as the first 1000. A schedule that
   degraded after N fires would pass a single-cycle check and a 200-cycle check.
2. **Assert what the trigger DOES, not only that it fires.** This bug lived
   entirely in the half of the effect the harness could not reach. If a trigger's
   action needs a `ServerLevel`, split its pure rules out as Minecraft-free
   statics — as `withinBand`, `horizontalDistanceSqr` and `verticalOffset` now are
   — so the parts that encode a *decision* are drivable. Same discipline as
   `TramplePath` and `CropSchedule`, applied to the right component this time.
3. **Every failure path must name its own cause in the log.** A recurring effect
   that can silently decline to act needs to say which rule declined, or a bug
   report about it is unanswerable.
4. **Statistical thresholds must be derived, not rounded.** A first draft of the
   re-roll check asserted ">1500 distinct gaps in 2000" and failed at 1182 — the
   coupon-collector expectation for 2000 draws over 1801 values is **1208**, so
   the assertion was impossible rather than the code wrong. The check now compares
   against that expectation with a ±15% band.

#### "Half a heart from a TNT that should do 15" — a band measured in the wrong quantity
Reported as: Unstable dealing about half a heart while standing still, against a
table that said 15.44 at 5 blocks. An order of magnitude out. **Two compounding
causes, and the dominant one was introduced by the fix to the previous bug.**

**Cause 1, dominant: the spawn band was decoupled from the quantity the damage
depends on.** The "fired once then silence" fix moved `SafeSpawn`'s band from 3D
to horizontal and widened `VERTICAL_SEARCH` from 4 to 8. That is correct for
Creeper Magnet — a creeper walks to you, so the starting height difference is not
part of what the band means. It is **wrong for Unstable**, because vanilla's
explosion damage reads the *3D* distance and `ServerExplosion.hurtEntities` does:

```
d = sqrt(entity.distanceToSqr(center)) / (2 * radius);
if (d > 1.0) continue;          // past 8.0 blocks: skipped outright, no damage
```

A horizontal band of 5–7 with a vertical search of 8 permits a 3D distance of
`sqrt(7² + 8²)` = **10.63 blocks** while still calling itself "5 to 7". Measured
against the exact terrain it was played on (heightmap decoded from the region
file, player at (-15.3, 112.0, -83.5)):

| | old, horizontal [5,7] | new, spherical [4.5,6.5] |
|---|---|---|
| actually inside the band in 3D | **54.2%** | **100%** |
| beyond the 8-block cull (zero damage) | **27.6%** | **0%** |
| median 3D distance | 6.85 | 5.56 |
| max 3D distance | **10.61** | 6.44 |
| damage at full exposure, mean | **5.45** | **11.99** |
| damage at full exposure, minimum | **0.00** | 7.52 |

**Cause 2, and a real gap in the original derivation: exposure was assumed to be
1.0.** `hurtEntities` computes `ServerExplosion.getSeenPercent(centre, entity)` —
a grid of rays from the entity's bounding box to the explosion centre, returning
the unobstructed fraction — and passes it as the third argument of
`getEntityDamageAmount`, where it multiplies `impact` *before* the quadratic. The
first derivation set it to 1.0 and presented the result as *the* damage rather
than as its ceiling.

**Both causes produce exactly the reported symptom**, which is why it was so
specific: a blast at the cull edge and a fully-obstructed blast both drive
`impact` to 0, and the formula's trailing `+ 1` is then the whole result — **1.0
damage, half a heart.**

#### The corrected model, and the naming that let the old one mislead
```
maxDist = 2 * radius                       // 8.0 for TNT
if (distance > maxDist) return 0            // <-- the cull, missing before
impact  = (1 - distance/maxDist) * exposure // <-- exposure, missing before
damage  = (impact² + impact) / 2 * 7 * maxDist + 1
```

| distance | exp 1.00 | exp 0.75 | exp 0.50 | exp 0.25 |
|---|---|---|---|---|
| 2.0 | **37.75 — kills** | 25.61 | 15.44 | 7.23 |
| 3.0 | **29.44 — kills** | 20.28 | 12.48 | 6.06 |
| 4.0 | **22.00 — kills** | 15.44 | 9.75 | 4.94 |
| **4.29** | **20.01 — the threshold** | 14.13 | 9.00 | 4.62 |
| 4.5 *(min)* | 18.61 | 13.20 | 8.46 | 4.40 |
| 5.5 | 12.48 | 9.10 | 6.06 | 3.36 |
| 6.5 *(max)* | 7.23 | 5.49 | 3.87 | 2.37 |
| 8.0 | 1.00 | 1.00 | 1.00 | 1.00 |
| 8.5 | **0 — culled entirely** | 0 | 0 | 0 |

**Only the exposure-1.00 column may be used for tuning**, because the counterplay
rule is a worst-case rule. The other columns explain what play *feels* like, and
they are why the same TNT can read as devastating in the open and trivial in a
trench.

**The old function was called `blastDamageAt(distance)` and that naming did real
damage.** It returned an upper bound under a name that reads like the value, so a
table of ceilings got tuned against as though it were expected damage. It is
deleted, not kept returning the ceiling: the model is now `blastDamage(distance,
exposure)` with `maxBlastDamage(distance)` as the explicitly-named worst case, and
the harness asserts the old name is **absent**.

#### Rule for any future blast effect
1. **Measure the band in whatever quantity the effect's own mechanic depends on.**
   `SafeSpawn.DistanceMode` now makes callers say which they mean. A blast wants
   `SPHERICAL`; a mob that walks to you wants `HORIZONTAL`. A band measured in the
   wrong quantity is not merely imprecise — over a quarter of its spawns did
   *nothing*.
2. **Never model explosion damage without `getSeenPercent`.** Exposure is a first-
   class term, not a correction, and it ranges to 0 in ordinary terrain.
3. **Never model it without the `d > 1.0` cull.** Past `2 * radius` the entity is
   skipped, so the answer is 0 and not "a bit above the +1 floor".
4. **Log the value actually delivered, not just the tuned intention.** Only
   failures were logged, so there was no record of where any TNT had landed and
   the whole 3D-versus-horizontal gap had to be reconstructed from the world save.
   `UnstableSpawner` now logs each spawn's real distance and the damage it implies.
   **A tuned number that is never compared against what shipped is a number nobody
   is checking.**

#### Unstable's retune: 100-tick fuse, 4.5–6.5 blocks, and the range that was refused
**A 2–4 block band was requested and was not shipped**, because every point in it
kills a stationary full-health unarmoured player: 22.0, 29.4 and 37.75 against a
20 HP bar. The lethal threshold — solved from the quadratic in
`lethalThresholdDistance`, not read off a table — is **4.29 blocks**, so **4.5 is
the closest survivable minimum that exists**, and it leaves 1.39 HP. That is the
tightest margin in the project and it is deliberate; it is the answer to "closest
range that stays genuinely survivable".

**The fuse went to 100 ticks (5.0 s), giving up the old "it is vanilla's own 80,
so the player's TNT intuition transfers" argument on purpose.** The band moved
closer, so the reaction budget moved up to pay for it, and the direction is the
safe one: a player calibrated on vanilla now has a second *in hand* rather than a
second short.

**Escape budget, recomputed rather than carried over.** Reaching the 8.0-block
cull from the 4.5-block minimum is 3.5 blocks: **0.81 s** walking at the project's
measured 4.3172 b/s, 0.62 s sprinting. Against the 5.0 s fuse that is **3.69 s of
slack** after half a second of reaction — *better* than the old band's 2.81 s,
despite the TNT being closer, because the extra second of fuse more than pays for
the extra half block of running. That is the trade the two changes make together,
and it is why they were made together.
