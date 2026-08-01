package com.entropymod.network;

import com.entropymod.EntropyMod;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Client -&gt; Server. "Send me the pick history."
 *
 * <p>Carries no data -- the server already knows who is asking, from the
 * connection. History is fetched on demand rather than ridden along on
 * {@link OpenChoicePayload} because it grows with every pick and the vast
 * majority of picks never need it.
 */
public record HistoryRequestPayload() implements CustomPacketPayload {

	public static final Identifier ID = EntropyMod.id("history_request");
	public static final CustomPacketPayload.Type<HistoryRequestPayload> TYPE = new CustomPacketPayload.Type<>(ID);

	/** Must be declared before CODEC -- static initialisers run top to bottom. */
	public static final HistoryRequestPayload INSTANCE = new HistoryRequestPayload();

	/** unit() writes and reads zero bytes and always yields INSTANCE. */
	public static final StreamCodec<ByteBuf, HistoryRequestPayload> CODEC = StreamCodec.unit(INSTANCE);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
