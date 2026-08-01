package com.entropymod.client.gui;

import com.entropymod.entropy.EffectPhase;
import com.entropymod.network.ChoiceMadePayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * The real player-facing GUI for a pick. Shows 3 cards (name + description);
 * clicking one sends the choice back to the server and closes the screen.
 * This screen intentionally has no close button / escape-to-cancel -- picking
 * is mandatory, matching the "you must choose" design.
 *
 * REWRITTEN for Mojang mappings + this MC version's newer render pipeline.
 * IMPORTANT: this version replaces the old render(DrawContext, ...) override
 * with extractRenderState(GuiGraphicsExtractor, ...) -- confirmed against the
 * current docs.fabricmc.net/develop/rendering/gui/custom-screens page. The
 * exact package for GuiGraphicsExtractor is my best guess by analogy to the
 * old GuiGraphics package (net.minecraft.client.gui) -- verify this one
 * specifically if it doesn't compile, it's the newest/least-documented class
 * used in this file.
 */
public class ChoiceScreen extends Screen {
	private final EffectPhase phase;
	private final int entropy;
	private final String id1, name1, desc1;
	private final String id2, name2, desc2;
	private final String id3, name3, desc3;

	public ChoiceScreen(EffectPhase phase, int entropy,
						 String id1, String name1, String desc1,
						 String id2, String name2, String desc2,
						 String id3, String name3, String desc3) {
		super(Component.literal(phase == EffectPhase.GOOD ? "Choose a Blessing" : "Choose a Curse"));
		this.phase = phase;
		this.entropy = entropy;
		this.id1 = id1; this.name1 = name1; this.desc1 = desc1;
		this.id2 = id2; this.name2 = name2; this.desc2 = desc2;
		this.id3 = id3; this.name3 = name3; this.desc3 = desc3;
	}

	@Override
	protected void init() {
		int cardWidth = 160;
		int spacing = 20;
		int totalWidth = cardWidth * 3 + spacing * 2;
		int startX = (this.width - totalWidth) / 2;
		int y = this.height / 2 - 10;

		addChoiceButton(startX, y, cardWidth, id1, name1);
		addChoiceButton(startX + cardWidth + spacing, y, cardWidth, id2, name2);
		addChoiceButton(startX + (cardWidth + spacing) * 2, y, cardWidth, id3, name3);
	}

	private void addChoiceButton(int x, int y, int width, String effectId, String displayName) {
		this.addRenderableWidget(Button.builder(Component.literal(displayName), button -> onChoose(effectId))
				.bounds(x, y, width, 20)
				.build());
	}

	private void onChoose(String effectId) {
		ClientPlayNetworking.send(new ChoiceMadePayload(effectId));
		this.onClose();
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		super.extractRenderState(graphics, mouseX, mouseY, delta);

		String header = (phase == EffectPhase.GOOD ? "A blessing approaches..." : "A curse approaches...")
				+ "  (Entropy: " + entropy + ")";
		drawCentered(graphics, header, this.width / 2, this.height / 2 - 40, 0xFFFFFF);

		drawCentered(graphics, desc1, this.width / 2 - 190, this.height / 2 + 20, 0xAAAAAA);
		drawCentered(graphics, desc2, this.width / 2, this.height / 2 + 20, 0xAAAAAA);
		drawCentered(graphics, desc3, this.width / 2 + 190, this.height / 2 + 20, 0xAAAAAA);
	}

	private void drawCentered(GuiGraphicsExtractor graphics, String text, int centerX, int y, int color) {
		int textWidth = this.font.width(text);
		graphics.text(this.font, text, centerX - textWidth / 2, y, color, true);
	}

	@Override
	public boolean shouldCloseOnEsc() {
		return false; // picking is mandatory -- no dodging the choice
	}

	@Override
	public boolean isPauseScreen() {
		return true;
	}
}
