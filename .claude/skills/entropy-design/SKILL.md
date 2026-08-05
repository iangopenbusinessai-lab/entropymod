---
name: entropy-design
description: Entropy Mod design state — per-component status and known gaps, the run lifecycle (start gate, the three paths to ENDED, end screen), and all open design questions with their trade-offs. Read this when planning what to build next, deciding scope, resolving an open question, or checking whether a subsystem is built, partially built, or deliberately deferred.
---

# Entropy Mod — design state, run lifecycle, and open questions

> **Cross-references.** This content was split out of the root `CLAUDE.md`.
> References below to "Part 0", "Part 1", "Part 2" and the mapping table point at
> the root `CLAUDE.md`; references to "Open Question N" and the run lifecycle
> point at the `entropy-design` skill.

**Status discipline:** this document distinguishes "deferred" from "started and
abandoned". `./gradlew harness` asserts that unbuilt halves are genuinely
*absent* rather than half-present — keep it that way.

## Part 1: Evaluation of what's built so far

### `EntropyManager` (the timer/loop) — solid core, missing win-detection
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
the name is retired. See "Effect execution" in Part 1 for the three-step recipe.

### 10. Persistence scope — RESOLVED
The question ("do in-flight temporary effects need to survive a restart?")
**dissolved when effects became permanent.** See "Persistence — BUILT" in Part 1
for what is and isn't saved, and why.

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
