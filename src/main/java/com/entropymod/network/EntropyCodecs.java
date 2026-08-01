package com.entropymod.network;

import com.entropymod.entropy.EffectPhase;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/** Codecs shared by more than one payload, so the wire format is defined once. */
public final class EntropyCodecs {

	/**
	 * NOTE: .map() on a StreamCodec keeps the SAME buffer type (B) as the codec it
	 * is called on -- it does not widen it. ByteBufCodecs.STRING_UTF8 is
	 * StreamCodec&lt;ByteBuf, String&gt;, so this stays StreamCodec&lt;ByteBuf, ...&gt;.
	 * That is fine: composite() accepts component codecs of any buffer type that is
	 * a supertype of the composite's own (ByteBuf is a supertype of
	 * RegistryFriendlyByteBuf). See the longer note in OpenChoicePayload.
	 *
	 * <p>Sent by name rather than ordinal so reordering the enum cannot silently
	 * flip GOOD and BAD on the wire.
	 */
	public static final StreamCodec<ByteBuf, EffectPhase> PHASE =
			ByteBufCodecs.STRING_UTF8.map(EffectPhase::valueOf, EffectPhase::name);

	private EntropyCodecs() {}
}
