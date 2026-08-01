package com.entropymod.network;

import com.entropymod.EntropyMod;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Client -> Server. Sent when the player clicks one of the 3 choices in the GUI. */
public record ChoiceMadePayload(String chosenEffectId) implements CustomPacketPayload {

	public static final Identifier ID = EntropyMod.id("choice_made");
	public static final CustomPacketPayload.Type<ChoiceMadePayload> TYPE = new CustomPacketPayload.Type<>(ID);

	// NOTE: .map() keeps STRING_UTF8's own buffer type (ByteBuf), it doesn't
	// widen to RegistryFriendlyByteBuf. That's fine -- PayloadTypeRegistry
	// accepts codecs of any buffer type that's a supertype of the one it's
	// registered for. See the matching note in OpenChoicePayload.java.
	public static final StreamCodec<ByteBuf, ChoiceMadePayload> CODEC =
			ByteBufCodecs.STRING_UTF8.map(ChoiceMadePayload::new, ChoiceMadePayload::chosenEffectId);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
