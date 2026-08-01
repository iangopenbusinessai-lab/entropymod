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
		int entropyCap,
		Choice choice1,
		Choice choice2,
		Choice choice3
) implements CustomPacketPayload {

	public static final Identifier ID = EntropyMod.id("open_choice");
	public static final CustomPacketPayload.Type<OpenChoicePayload> TYPE = new CustomPacketPayload.Type<>(ID);

	// The phase codec moved to EntropyCodecs once HistoryResponsePayload needed it
	// too -- the wire format for EffectPhase is defined in exactly one place. The
	// buffer-type note that used to live here is now in that class.
	private static final StreamCodec<ByteBuf, EffectPhase> PHASE_CODEC = EntropyCodecs.PHASE;

	// Every record component MUST have a matching line here -- composite drives
	// encode and decode from the same list, so a field added to the record but
	// not to this codec is silently dropped on the wire rather than failing to
	// compile. entropyCap is what the client needs to colour its accent ramp;
	// without it the client can only assume the default cap.
	public static final StreamCodec<RegistryFriendlyByteBuf, OpenChoicePayload> CODEC = StreamCodec.composite(
			PHASE_CODEC, OpenChoicePayload::phase,
			ByteBufCodecs.VAR_INT, OpenChoicePayload::entropy,
			ByteBufCodecs.VAR_INT, OpenChoicePayload::entropyCap,
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

	public static OpenChoicePayload fromChoices(EffectPhase phase, int entropy, int entropyCap,
												List<EffectDefinition> choices) {
		EffectDefinition a = choices.get(0);
		EffectDefinition b = choices.size() > 1 ? choices.get(1) : choices.get(0);
		EffectDefinition c = choices.size() > 2 ? choices.get(2) : choices.get(0);
		return new OpenChoicePayload(
				phase, entropy, entropyCap,
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
