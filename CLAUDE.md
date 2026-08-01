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
(must call `super.extractRenderState(...)` first). This is confirmed from
current docs, but is genuinely new/unfamiliar territory -- if `ChoiceScreen`
doesn't compile, this is the first place to look, and `GuiGraphicsExtractor`'s
exact package is the least-verified import in the whole project.

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
- In-memory only, `WeakHashMap<MinecraftServer, EntropyManager>`. Entropy and
  pick count reset to 0 on every server restart. Needs a real
  `PersistentState` (or equivalent) to survive a save/reload.
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
  isn't checked anywhere).

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

### GUI (`ChoiceScreen`) — functional MVP, deliberately minimal
**What it does well:** mandatory pick (no escape-to-cancel) matches the
"you must choose" design intent. Clean 3-button layout.

**Weaknesses / gaps:**
- No visual distinction between Blessing/Curse screens beyond the title text
  (no color theming, no icon per effect, no flavor art). Purely functional
  right now — fine for testing, will need a pass before this feels like a
  "real mod" rather than a prototype.
- `shouldPause()` returns `true` — the world (and single-player game) freezes
  while the GUI is open. This was a default choice, not a deliberate design
  decision. Whether the world should keep ticking during a pick (mobs still
  approaching, adding pressure to decide fast) is a real design question.
  See Open Question 8.
- No indication of *why* the phase is Good or Bad beyond the header text —
  no entropy progress bar, no "X picks until next tier" indicator.

### Mixins — placeholders only, correctly scaffolded
Both mixin stubs are unused example code, kept only to prove the pattern is
wired up correctly for later signature effects (Mirror World's inverted
input, Fisheye's forced FOV, Doppelganger's custom entity, etc.). Nothing to
evaluate yet — there's no real mixin logic written.

### Effect execution — does not exist yet
This is the biggest missing piece overall. Picking an effect currently just
logs it and sends a chat message. There is no `EffectExecutor`, no actual
status-effect application, no wolf-spawning, no TNT-spawning, nothing that
touches the actual game world. Every effect described in the design doc is
still just a string in `EffectRegistry` with no behavior behind it.

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
  COMPANION, UTILITY, DEBUFF, META` — max 1 active effect per category
  *(rule stated, not yet enforced in code — see gaps above)*
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

### 5. Anti-stacking enforcement: replace, or exclude from the pool?
Two different behaviors, both reasonable, need to pick one:
- (a) When a new effect is picked, any existing effect in the same category
  is immediately expired/replaced
- (b) Categories with an already-active effect are excluded from the roll
  pool entirely, so you never even see a conflicting option — forces
  variety in what shows up in the 3 choices

### 6. What happens when the game world runs out of legal effects?
E.g. if every category is occupied by an active effect and entropy is at a
point where no eligible effect exists in an unoccupied category — `rollThree`
would return fewer than 3 (or 0) options. Needs a defined fallback (extend
current effect's duration? force-expire the oldest active effect to make
room? allow same-category rerolls as a last resort?).

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

### 9. `EffectExecutor` architecture — one big switch, or per-effect classes?
Before writing any real effect behavior, worth locking in the pattern:
- (a) One `EffectExecutor` class with a big `switch(effectId)` for
  apply/remove — simplest, but grows unwieldy past ~30 effects
- (b) Each effect is its own small class implementing an `apply(context)` /
  `remove(context)` interface, registered alongside its `EffectDefinition` —
  more files, but matches the "additive architecture" pattern you already
  use in Puzzikub (new effect = new file, zero changes to existing code)

### 10. Persistence scope — just the counters, or active effects too?
Minimum viable persistence is just saving `entropy`, `pickCount`, and config
values. But if a Curse like "Weapon Ban, 3 min" is active when the server
restarts, should that survive the restart (remaining duration saved), or is
it acceptable for in-flight temporary effects to just be lost on restart
(only permanent/one-time effects and the counters need to survive)?

---

## Suggested next session order

1. Resolve Questions 1, 2, 7, 9 first — these affect the *shape* of code
   written in every other step, so deciding late means rework.
2. Build `EffectExecutor` for the 11 Tier 1 effects using whichever pattern
   Question 9 lands on.
3. Wire up anti-stacking per Question 5.
4. Add win detection per Question 1.
5. Add persistence (Question 10) once the above is stable enough that it's
   worth surviving a restart.
6. Config surface (Question 4) — can happen in parallel, low-risk to defer.
7. Port Tiers 2-4 into `EffectRegistry` (mechanical, low-risk, do anytime).
8. Odd/signature effects, prioritized per Question 8.
