package com.entropymod.network;

import com.entropymod.EntropyMod;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Client -&gt; Server. "Spend Second Guess and reroll the pending choices."
 *
 * <p>Carries no data. Which player is asking comes from the connection, and
 * <b>everything else is decided server-side</b> -- whether the effect is owned,
 * whether the reroll is unspent, and whether a pick is actually pending are all
 * re-checked in {@code EntropyManager.requestReroll}. The {@code rerollState}
 * field on {@link OpenChoicePayload} only decides how the button is drawn; it is
 * not trusted as authorisation, so a client that was sent {@code SPENT} and asks
 * anyway is still refused.
 */
public record RerollRequestPayload() implements CustomPacketPayload {

	public static final Identifier ID = EntropyMod.id("reroll_request");
	public static final CustomPacketPayload.Type<RerollRequestPayload> TYPE = new CustomPacketPayload.Type<>(ID);

	/** Must be declared before CODEC -- static initialisers run top to bottom. */
	public static final RerollRequestPayload INSTANCE = new RerollRequestPayload();

	/** unit() writes and reads zero bytes and always yields INSTANCE. */
	public static final StreamCodec<ByteBuf, RerollRequestPayload> CODEC = StreamCodec.unit(INSTANCE);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
