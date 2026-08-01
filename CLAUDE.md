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
- In-memory only, `WeakHashMap<MinecraftServer, EntropyManager>`. Entropy,
  pick count, **pick history, and the active-effect list** all reset on every
  server restart. Needs a real `PersistentState` (or equivalent) to survive a
  save/reload. Note this got bigger with the tracking work — Question 10 now
  has four things to decide about, not two.
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
- **The Tier 1 pool is thin enough that anti-stacking bites quickly.** GOOD has
  6 effects across only 4 categories (MOVEMENT ×2, UTILITY ×2, SURVIVAL,
  TOOL); occupying MOVEMENT + UTILITY leaves just 2 eligible choices, and
  three long-duration picks can empty it entirely and trip the collision
  fallback. This is a content problem, not a code problem — porting Tiers 2-4
  makes it go away. Worth knowing before reading a fallback warning in the log
  as a bug.

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
`/entropytest`, `/entropytest <good|bad> <entropy>`,
`/entropytest <good|bad> <entropy> <cap>`, or append `long` to force compact
descriptions. This drives the HUD cache too, so it exercises both surfaces —
but note it is a *client-only* path and does not prove the server serialises
anything.

`/entropyhistory` is the other test command, and unlike `/entropytest` it
*does* hit the server: it sends a real `HistoryRequestPayload` and logs
whatever comes back. Temporary — replace with a real screen.
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

### Mixins — placeholders only, correctly scaffolded
Both mixin stubs are unused example code, kept only to prove the pattern is
wired up correctly for later signature effects (Mirror World's inverted
input, Fisheye's forced FOV, Doppelganger's custom entity, etc.). Nothing to
evaluate yet — there's no real mixin logic written.

### Effect execution — architecture BUILT, content still empty
**The plumbing is done; the effects still do nothing.** Picking an effect now
routes through a real per-effect class instead of inline code in
`EntropyManager`, but every one of those classes is a stub that logs and sends
a chat message. Nothing touches the game world yet — no status effects, no
entity spawning, no attribute modifiers.

**Adding a new effect is now exactly three things, and nothing else:**

1. one `register(...)` line in `EffectRegistry`
2. one new class in `com.entropymod.entropy.behavior` implementing
   `EffectBehavior`
3. one `register(...)` line in `EffectBehaviors`

No other file should need to change — not `EntropyManager`, not the GUI, not
networking. If a new effect makes you edit a fourth file, that's a signal the
abstraction is missing something; fix the abstraction rather than the caller.

- `EffectBehavior` — `apply(EffectContext)` / `remove(EffectContext)`. Per
  CLAUDE.md Open Question 9 this is option (b), one class per effect, not one
  big switch.
- `EffectContext` — wraps the `MinecraftServer` **and the `EffectDefinition`
  being applied**, so a behavior can read its own duration/category/description
  without a registry lookup. This is the extension point: add fields *here*
  rather than changing `apply`'s signature, which would touch every effect.
  `player()` is nullable on purpose — the server ticks briefly with nobody
  online during world load, and a real behavior that dereferences it blindly
  crashes there.
- `EffectBehaviors` — id → behavior map. Ids are matched to `EffectRegistry` by
  **string**, which the compiler cannot check: a typo yields a silently
  do-nothing effect rather than a build failure. `EffectBehaviors.validate()`
  runs at mod init and logs both directions of mismatch for exactly this
  reason. An unregistered id returns a logging no-op, never null.
- `EffectContext.announceApply()` / `announceRemove()` are **scaffolding**.
  They exist so 11 stubs don't repeat the same broadcast boilerplate. As each
  effect gets a real implementation it stops calling them; when none do, delete
  both.

### Active effect tracking + expiry — BUILT
`ActiveEffectTracker` owns the live effect list and the two rules that read it
(expiry, and category occupancy for anti-stacking). `EntropyManager.tick()`
processes expiry every tick, not just on interval boundaries.

`durationTicks` semantics, all three verified headlessly:

- **`0`** — applied once, never added to the active list, `remove()` is
  *never* called. `FieldRepairBehavior.remove` is deliberately an empty method
  with a comment rather than an announcement, so a message there would be a
  visible sign the contract broke upstream.
- **`-1`** — "until the next interval". **Implemented as an event, not a
  countdown**: `ActiveEffect.tick()` is a no-op for these, and they are expired
  by `expireIntervalScoped()` at the top of `triggerPick`. This is the whole
  point — converting `-1` into `intervalTicks` at apply time compiles fine and
  looks right, then silently goes wrong the moment the interval is
  reconfigured. Don't "simplify" it back into a countdown.
- **`>0`** — decremented once per server tick; `remove()` fires on the exact
  tick it reaches 0.

Expiry is deliberately **not** gated on `waitingOnChoice`: a duration is
elapsed time from when it was applied, not a count of ticks the interval clock
happened to be running. (Moot in singleplayer — the choice screen pauses the
world — but correct if that ever changes.)

Re-picking an effect that is still live refreshes its timer instead of
double-tracking it. That is *not* the replace rule; it only ever matches an
identical id, and is normally unreachable because anti-stacking excludes the
whole category first. It exists to keep the collision-fallback path safe.

### Anti-stacking — BUILT (exclude-from-pool)
`EffectRegistry.rollThree(phase, entropy, random, excludedCategories)` filters
out every effect whose category already has an active effect, so a conflicting
option never appears among the three cards. A pick can therefore never silently
cancel something the player already has.

- A **partial** result is not a fallback. If only two effects survive the
  filter, the player sees two cards — that's the honest answer.
- The fallback fires only when the filter leaves the pool **completely empty**,
  in which case the unfiltered pool is used and `RollResult.categoryFallback()`
  is set so `EntropyManager` can log it loudly. **This is placeholder policy,
  not a decision** — see Open Question 6, still open.
- A genuinely empty eligible pool (entropy past every effect's range) is a
  *different* failure and is deliberately not reported as a fallback.

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

### How to check the tracking logic without booting Minecraft
`ActiveEffect`, `ActiveEffectTracker`, `PickRecord`, `EffectRegistry` and the
whole data model are **free of Minecraft imports on purpose** — same discipline
as `EntropyPalette` on the client side. A plain
`javac`/`java -cp build/classes/java/main` harness can drive expiry timing,
the `-1` interval-scoped rule, and anti-stacking against the real shipped
classes. Do that rather than testing a copy, and rather than waiting on a
3-minute timer in-game.

Keeping that dependency-free is a load-bearing property, not an accident:
`EntropyManager` deliberately does *not* fire behaviors from inside the
tracker, it asks the tracker which effects expired and fires them itself. Don't
"tidy" that into a callback the tracker invokes — it would drag
`MinecraftServer` into the one part of this that's currently testable.

Payload changes still need the round-trip check described under "Entropy cap on
the wire" above; `HistoryResponsePayload` has been verified that way
field-by-field (a whole-record `equals` would still pass if two same-typed
fields were swapped in a way that cancelled out).

What this *cannot* prove, and what still needs a real game session: that the
chat messages actually appear, that expiry lands on the right wall-clock
moment in a live server tick loop, and that the history request survives a real
client↔server round trip.

### Config (interval length, entropy cap) — fields exist, no player-facing surface
`EntropyManager.setIntervalTicks()` / `setEntropyCap()` exist and work, but
there's no world-creation config screen, no GameRule, no command, and no
config file wiring any of them up. Right now the only way to change these is
to edit code and rebuild. See Open Question 5.

### Persistence — not built
Flagged above under `EntropyManager`, repeating here because it's a genuine
gap, not a nice-to-have: without this, entropy resets every time the server
restarts, which breaks the entire "race against entropy" premise for any
session that spans more than one sitting.

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
answer. The alternatives are all still live — extend the incumbent effect's
duration and skip the interval; force-expire the oldest active effect to make
room; offer fewer than 3 cards and accept it. Pick one before this ships.
The fallback is easiest to trigger with the current thin Tier 1 pool and gets
rarer as Tiers 2-4 land, so this may matter less than it looks.

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

### 10. Persistence scope — just the counters, or active effects too?
Minimum viable persistence is just saving `entropy`, `pickCount`, and config
values. But if a Curse like "Weapon Ban, 3 min" is active when the server
restarts, should that survive the restart (remaining duration saved), or is
it acceptable for in-flight temporary effects to just be lost on restart
(only permanent/one-time effects and the counters need to survive)?

Now a four-way decision, not two — the tracking work added state:
`ActiveEffectTracker`'s list (with `remainingTicks` per entry, and the
interval-scoped flag) and the `PickRecord` history. History is the easy one:
it's append-only plain data and should obviously persist. Active effects are
the hard one, and note that persisting them means `EffectBehavior.remove()`
can be called in a session where `apply()` never ran — which is already
documented as a requirement on the interface, but nothing enforces it while
every behavior is a stub.

---

## Suggested next session order

Questions 5 and 9 are now resolved and their code is built (stubs, not
content). Remaining, in dependency order:

1. Resolve Questions 1, 2, 7 — these still affect the *shape* of code written
   in every other step, so deciding late means rework.
2. Give the 11 Tier 1 stubs real behavior. Suggested order, easiest first, so
   the plumbing gets proven before the hard ones: `night_owl` (a plain
   `MobEffectInstance`) → `sure_footing` / `heavy_boots` (attribute modifiers,
   and they're inverses so build them together) → `field_repair` (the only
   duration-0 effect, proves that path) → `featherlight` → the rest.
   Each one is a single file; nothing else should need to change.
3. Add win detection per Question 1.
4. Port Tiers 2-4 into `EffectRegistry` (mechanical, low-risk, do anytime) —
   worth doing early anyway, since it's what stops the anti-stacking collision
   fallback from firing.
5. Decide Question 6 for real, once step 4 shows how often it actually fires.
6. Add persistence (Question 10) once the above is stable enough that it's
   worth surviving a restart.
7. Config surface (Question 4) — can happen in parallel, low-risk to defer.
8. A real pick-history screen to replace the `/entropyhistory` debug logging.
9. Odd/signature effects, prioritized per Question 8.
