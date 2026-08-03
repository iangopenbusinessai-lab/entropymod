package com.entropymod.network;

import com.entropymod.EntropyMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.List;

/**
 * Server -&gt; Client. "These are the effects you currently hold."
 *
 * <p><b>New infrastructure, and the prerequisite for every client-side effect.</b>
 * Until Tier 2 every effect was server-authoritative -- attributes and
 * server-class mixins -- so the client was never told the acquired set and had no
 * reason to care. Randomized Movement Controls, Upside-Down Camera and Random
 * Jump all act on systems that exist only on the client ({@code KeyboardInput}
 * and {@code Camera}), so without this they cannot work at all.
 *
 * <p>Sent on join and after any change to the acquired set -- see
 * {@code EntropyManager.syncTo}/{@code syncToAll}. Deliberately <b>not</b>
 * ridden along on {@code OpenChoicePayload}: that only fires when a pick opens,
 * which would leave a granted effect inert until the next interval.
 *
 * <p><b>This is state, not authorisation.</b> Nothing the client does with it is
 * trusted -- these three effects are cosmetic-to-the-server by nature (they
 * change what the player's own inputs and camera do), and the resulting movement
 * still goes through vanilla's ordinary client-to-server movement validation.
 *
 * <p>The scramble rides along rather than getting its own payload because it is
 * meaningless without the effect id that enables it, and sending them separately
 * would create a window where the client knows one but not the other.
 */
public record ClientEffectsPayload(
		List<String> effectIds,
		String moveScramble
) implements CustomPacketPayload {

	public static final Identifier ID = EntropyMod.id("client_effects");
	public static final CustomPacketPayload.Type<ClientEffectsPayload> TYPE = new CustomPacketPayload.Type<>(ID);

	// Every record component MUST have a matching line here -- see the note in
	// OpenChoicePayload. Both components are strings/string-lists, so a swapped
	// pair would compile; the harness round-trips them with distinct values.
	public static final StreamCodec<RegistryFriendlyByteBuf, ClientEffectsPayload> CODEC = StreamCodec.composite(
			ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()), ClientEffectsPayload::effectIds,
			ByteBufCodecs.STRING_UTF8, ClientEffectsPayload::moveScramble,
			ClientEffectsPayload::new
	);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
