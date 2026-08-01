package com.entropymod.network;

import com.entropymod.EntropyMod;
import com.entropymod.entropy.EffectPhase;
import com.entropymod.entropy.PickRecord;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.List;

/**
 * Server -&gt; Client. The full pick history, in order, in reply to a
 * {@link HistoryRequestPayload}. Sent only to the player who asked.
 */
public record HistoryResponsePayload(List<Entry> entries) implements CustomPacketPayload {

	public static final Identifier ID = EntropyMod.id("history_response");
	public static final CustomPacketPayload.Type<HistoryResponsePayload> TYPE = new CustomPacketPayload.Type<>(ID);

	/**
	 * Wire form of one {@link PickRecord}. A separate type from PickRecord itself so
	 * the server-side model can gain fields (timestamps, whether the pick came from
	 * a fallback roll) without every one of them becoming a protocol change.
	 */
	public record Entry(
			int pickNumber,
			EffectPhase phase,
			String effectId,
			String effectName,
			String effectDescription,
			int entropyAtPick
	) {
		// Every record component MUST have a matching line here. composite() drives
		// encode and decode from the same list, so a field added above but not here
		// is silently dropped on the wire rather than failing to compile -- and a
		// line pointing at the WRONG getter compiles fine and sends wrong data.
		// Round-trip with distinct values per field after any edit. (CLAUDE.md.)
		public static final StreamCodec<ByteBuf, Entry> CODEC = StreamCodec.composite(
				ByteBufCodecs.VAR_INT, Entry::pickNumber,
				EntropyCodecs.PHASE, Entry::phase,
				ByteBufCodecs.STRING_UTF8, Entry::effectId,
				ByteBufCodecs.STRING_UTF8, Entry::effectName,
				ByteBufCodecs.STRING_UTF8, Entry::effectDescription,
				ByteBufCodecs.VAR_INT, Entry::entropyAtPick,
				Entry::new
		);

		public static Entry from(PickRecord record) {
			return new Entry(record.pickNumber(), record.phase(), record.effectId(),
					record.effectName(), record.effectDescription(), record.entropyAtPick());
		}
	}

	public static final StreamCodec<ByteBuf, HistoryResponsePayload> CODEC =
			Entry.CODEC.apply(ByteBufCodecs.list())
					.map(HistoryResponsePayload::new, HistoryResponsePayload::entries);

	public static HistoryResponsePayload from(List<PickRecord> history) {
		return new HistoryResponsePayload(history.stream().map(Entry::from).toList());
	}

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
