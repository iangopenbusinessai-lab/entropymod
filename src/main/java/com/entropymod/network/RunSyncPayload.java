package com.entropymod.network;

import com.entropymod.EntropyMod;
import com.entropymod.entropy.KeybindSnapshot;
import com.entropymod.entropy.RunState;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Server -&gt; Client. "This is the run's lifecycle state, and this is the
 * keybind snapshot I hold for <em>you</em>."
 *
 * <p>Sent on join and again whenever the state changes, from
 * {@code EntropyManager.syncRunTo}/{@code syncRunToAll}. Deliberately separate
 * from {@code ClientEffectsPayload}: that one is about which effects the run
 * holds and is broadcast identically to everybody, whereas the snapshot here is
 * <b>per-player</b> -- two players have different keybinds -- so folding them
 * together would mean either sending everyone's snapshot to everyone or making a
 * broadcast payload secretly per-recipient.
 *
 * <p><b>This payload drives three client decisions, and that is why the snapshot
 * rides along rather than being fetched separately:</b>
 *
 * <ol>
 *   <li>{@code NOT_STARTED} -&gt; open the modal start panel.</li>
 *   <li>{@code IN_PROGRESS} -&gt; close the start panel if it is open. The
 *       client never closes it on its own click; it waits to be told the
 *       transition really happened, so a refused start cannot strand the
 *       player outside the gate.</li>
 *   <li>{@code IN_PROGRESS} with an absent snapshot -&gt; capture keybinds now
 *       and send them up. This is the whole late-joiner and other-players path:
 *       no "please send me your keybinds" request packet is needed, because the
 *       client can see for itself that the server has nothing for it.</li>
 * </ol>
 *
 * <p>The state travels <b>by name</b>, like {@code EffectPhase} in
 * {@link EntropyCodecs}, so reordering the enum cannot silently change meaning
 * on the wire. An unrecognised name parses to {@code NOT_STARTED} rather than
 * throwing inside a network handler -- see {@link RunState#parse}.
 */
public record RunSyncPayload(String runState, KeybindSnapshot snapshot) implements CustomPacketPayload {

	public static final Identifier ID = EntropyMod.id("run_sync");
	public static final CustomPacketPayload.Type<RunSyncPayload> TYPE = new CustomPacketPayload.Type<>(ID);

	// Five string components, four of them the snapshot. Every one is a distinct
	// getter; the harness round-trips them with distinct values because a swapped
	// pair of same-typed components would compile and encode fine -- see the note
	// in OpenChoicePayload.
	public static final StreamCodec<RegistryFriendlyByteBuf, RunSyncPayload> CODEC = StreamCodec.composite(
			ByteBufCodecs.STRING_UTF8, RunSyncPayload::runState,
			ByteBufCodecs.STRING_UTF8, p -> p.snapshot().forward(),
			ByteBufCodecs.STRING_UTF8, p -> p.snapshot().back(),
			ByteBufCodecs.STRING_UTF8, p -> p.snapshot().left(),
			ByteBufCodecs.STRING_UTF8, p -> p.snapshot().right(),
			(state, forward, back, left, right) ->
					new RunSyncPayload(state, new KeybindSnapshot(forward, back, left, right))
	);

	public static RunSyncPayload of(RunState state, KeybindSnapshot snapshot) {
		return new RunSyncPayload(state.name(), snapshot == null ? KeybindSnapshot.EMPTY : snapshot);
	}

	/** The state as an enum, degrading to {@code NOT_STARTED} rather than throwing. */
	public RunState state() {
		return RunState.parse(runState);
	}

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
