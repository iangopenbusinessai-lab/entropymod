package com.entropymod.network;

import com.entropymod.EntropyMod;
import com.entropymod.entropy.EffectDefinition;
import com.entropymod.entropy.EffectPhase;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.List;

/**
 * Server -> Client. Tells the client to open the choice GUI with these 3 options.
 *
 * REWRITTEN for Mojang mappings (this project does not use Yarn -- see CLAUDE.md
 * "Mapping migration note"). Verified against docs.fabricmc.net/develop/networking
 * (current as of MC 26.1.2): CustomPacketPayload (not CustomPayload), StreamCodec
 * (not PacketCodec), RegistryFriendlyByteBuf (not RegistryByteBuf).
 */
public record OpenChoicePayload(
		EffectPhase phase,
		int entropy,
		Choice choice1,
		Choice choice2,
		Choice choice3
) implements CustomPacketPayload {

	public static final Identifier ID = EntropyMod.id("open_choice");
	public static final CustomPacketPayload.Type<OpenChoicePayload> TYPE = new CustomPacketPayload.Type<>(ID);

	// NOTE: .map() on a StreamCodec keeps the SAME buffer type (B) as the codec
	// it's called on -- it doesn't widen it. ByteBufCodecs.STRING_UTF8 is
	// StreamCodec<ByteBuf, String>, so mapping it stays StreamCodec<ByteBuf, ...>,
	// not StreamCodec<RegistryFriendlyByteBuf, ...>. This is fine: composite()
	// accepts component codecs of any buffer type that's a supertype of the
	// composite's buffer type (ByteBuf is a supertype of RegistryFriendlyByteBuf),
	// per Fabric/NeoForge docs: "Most methods... look for ? super B".
	private static final StreamCodec<ByteBuf, EffectPhase> PHASE_CODEC =
			ByteBufCodecs.STRING_UTF8.map(EffectPhase::valueOf, EffectPhase::name);

	public static final StreamCodec<RegistryFriendlyByteBuf, OpenChoicePayload> CODEC = StreamCodec.composite(
			PHASE_CODEC, OpenChoicePayload::phase,
			ByteBufCodecs.VAR_INT, OpenChoicePayload::entropy,
			Choice.CODEC, OpenChoicePayload::choice1,
			Choice.CODEC, OpenChoicePayload::choice2,
			Choice.CODEC, OpenChoicePayload::choice3,
			OpenChoicePayload::new
	);

	/** One of the 3 rollable options. Kept as its own record so the codec stays a simple composite. */
	public record Choice(String id, String name, String description) {
		public static final StreamCodec<RegistryFriendlyByteBuf, Choice> CODEC = StreamCodec.composite(
				ByteBufCodecs.STRING_UTF8, Choice::id,
				ByteBufCodecs.STRING_UTF8, Choice::name,
				ByteBufCodecs.STRING_UTF8, Choice::description,
				Choice::new
		);
	}

	public static OpenChoicePayload fromChoices(EffectPhase phase, int entropy, List<EffectDefinition> choices) {
		EffectDefinition a = choices.get(0);
		EffectDefinition b = choices.size() > 1 ? choices.get(1) : choices.get(0);
		EffectDefinition c = choices.size() > 2 ? choices.get(2) : choices.get(0);
		return new OpenChoicePayload(
				phase, entropy,
				new Choice(a.id(), a.displayName(), a.description()),
				new Choice(b.id(), b.displayName(), b.description()),
				new Choice(c.id(), c.displayName(), c.description())
		);
	}

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
