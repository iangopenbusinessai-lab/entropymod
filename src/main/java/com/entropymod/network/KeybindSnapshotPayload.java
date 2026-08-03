package com.entropymod.network;

import com.entropymod.EntropyMod;
import com.entropymod.entropy.KeybindSnapshot;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Client -&gt; Server. "Here are my four movement keys" -- and, if
 * {@link #startRun()} is set, "...and I clicked Start."
 *
 * <h2>Why one payload carries both, rather than two payloads</h2>
 *
 * <p>The start transition is server-authoritative and the snapshot is
 * client-only, so a naive design has the client do two things on one click and
 * has to argue about their ordering. <b>Making the snapshot the payload of the
 * start message removes the ordering question entirely:</b> one packet, one
 * server handler, one {@code setDirty()}. There is no interleaving in which the
 * run starts without a snapshot, and no second message that could be dropped,
 * reordered, or sent by a client that skipped the first.
 *
 * <p>The server still applies them in a required order <em>within</em> that
 * handler -- start first, then store -- because
 * {@code EntropyManager.storeKeybindSnapshotIfAbsent} refuses to record a
 * snapshot while the run is {@code NOT_STARTED} (there is nothing to anchor
 * yet). That ordering is internal to one synchronous handler, which is exactly
 * where an ordering constraint is safe to have.
 *
 * <p><b>{@code startRun} is a request, not an instruction.</b>
 * {@code EntropyManager.startRun} is idempotent and re-checks the state, so a
 * duplicated or replayed packet cannot restart a live run, and a client that
 * sets the flag whenever it likes achieves nothing beyond the first transition.
 * The same is true of the snapshot: first one per player wins, so a client
 * cannot re-anchor its curse later by sending a fresh capture.
 */
public record KeybindSnapshotPayload(KeybindSnapshot snapshot, boolean startRun)
		implements CustomPacketPayload {

	public static final Identifier ID = EntropyMod.id("keybind_snapshot");
	public static final CustomPacketPayload.Type<KeybindSnapshotPayload> TYPE =
			new CustomPacketPayload.Type<>(ID);

	// Four same-typed string components in a row -- exactly the shape where a
	// mis-wired getter compiles and silently sends the wrong field. Round-tripped
	// field by field with distinct values in the harness.
	public static final StreamCodec<RegistryFriendlyByteBuf, KeybindSnapshotPayload> CODEC =
			StreamCodec.composite(
					ByteBufCodecs.STRING_UTF8, p -> p.snapshot().forward(),
					ByteBufCodecs.STRING_UTF8, p -> p.snapshot().back(),
					ByteBufCodecs.STRING_UTF8, p -> p.snapshot().left(),
					ByteBufCodecs.STRING_UTF8, p -> p.snapshot().right(),
					ByteBufCodecs.BOOL, KeybindSnapshotPayload::startRun,
					(forward, back, left, right, start) ->
							new KeybindSnapshotPayload(
									new KeybindSnapshot(forward, back, left, right), start)
			);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
