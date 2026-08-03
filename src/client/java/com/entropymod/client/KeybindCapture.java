package com.entropymod.client;

import com.entropymod.entropy.KeybindSnapshot;
import com.entropymod.entropy.MovementScramble;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import org.lwjgl.glfw.GLFW;

import java.util.List;

/**
 * Reads the player's movement keybinds, and reads whether those specific
 * physical keys are down right now.
 *
 * <p>The only class in the mod that touches raw input. Kept separate from
 * {@link ClientRunState} so that class stays free of Minecraft imports.
 *
 * <h2>The accessors, javap-verified rather than assumed</h2>
 *
 * <ul>
 *   <li>{@code Options.keyUp/keyDown/keyLeft/keyRight} are <b>public final
 *       {@code KeyMapping}</b> fields. No accessor mixin needed.</li>
 *   <li>{@code KeyMapping.getKey()} does <b>not</b> exist -- the current binding
 *       lives in a {@code protected} field. The public way to read it is
 *       {@link net.minecraft.client.KeyMapping#saveString()}, whose bytecode is
 *       exactly {@code this.key.getName()}. That is also the string
 *       {@code options.txt} stores, which is what makes it a good persisted
 *       form. ({@code getDefaultKey()} is public too and is the wrong one --
 *       it reports the factory binding, not the player's.)</li>
 *   <li>{@link InputConstants#getKey(String)} parses it back, and <b>throws
 *       {@code IllegalArgumentException}</b> on an unknown name rather than
 *       returning null.</li>
 *   <li>{@link InputConstants#isKeyDown} takes a {@code Window}, not a
 *       {@code long} handle, in this version. Its body is
 *       {@code glfwGetKey(window.handle(), value) == GLFW_PRESS}, so it is
 *       <b>KEYSYM-only</b> -- passing a mouse button or a scancode through it
 *       would query an unrelated key.</li>
 * </ul>
 *
 * <h2>Why this has to gate on the open screen itself</h2>
 *
 * <p>{@code KeyboardInput.tick()} contains no screen check -- verified in
 * bytecode, it is seven {@code KeyMapping.isDown()} calls and nothing else.
 * Vanilla's "typing in chat doesn't walk you into lava" behaviour comes entirely
 * from {@code Minecraft.setScreen} calling {@code KeyMapping.releaseAll()},
 * which clears the per-mapping down flags. <b>{@code glfwGetKey} knows nothing
 * about that</b> and would happily report W as held while the player types "w"
 * in chat. So {@link #pressesFor} checks {@code screen == null} and
 * {@code isWindowActive()} itself. Losing this guard would not fail loudly; it
 * would make the player walk while typing.
 */
public final class KeybindCapture {

	/**
	 * The player's movement keys right now, in {@link MovementScramble#ORDER}
	 * (forward, back, left, right).
	 */
	public static KeybindSnapshot capture() {
		Options options = Minecraft.getInstance().options;
		if (options == null) {
			return KeybindSnapshot.EMPTY;
		}
		return new KeybindSnapshot(
				options.keyUp.saveString(),
				options.keyDown.saveString(),
				options.keyLeft.saveString(),
				options.keyRight.saveString());
	}

	/**
	 * Whether each of the snapshot's four keys is physically down, in
	 * {@link MovementScramble#ORDER}.
	 *
	 * @return the four states, or {@code null} if this snapshot cannot be polled
	 *         at all -- which the caller must treat as "fall back to vanilla's own
	 *         direction booleans" rather than as "nothing is pressed"
	 */
	public static boolean[] pressesFor(KeybindSnapshot snapshot) {
		if (snapshot == null || !snapshot.isPresent()) {
			return null;
		}
		Minecraft client = Minecraft.getInstance();
		if (client == null || client.getWindow() == null) {
			return null;
		}

		// See the class javadoc: this is the guard that vanilla gets for free from
		// releaseAll() and raw polling does not.
		if (client.screen != null || !client.isWindowActive()) {
			return new boolean[MovementScramble.LENGTH];
		}

		boolean[] pressed = new boolean[MovementScramble.LENGTH];
		List<String> names = snapshot.keys();
		for (int i = 0; i < MovementScramble.LENGTH; i++) {
			Boolean down = isDown(client, names.get(i));
			if (down == null) {
				// One unpollable key means the whole snapshot is unusable. Honouring
				// three of four directions and leaving the fourth on live bindings
				// would be a third behaviour nothing else in the design accounts for.
				return null;
			}
			pressed[i] = down;
		}
		return pressed;
	}

	/**
	 * @return whether that key is held, or {@code null} if its state cannot be
	 *         queried (a SCANCODE binding, or a name this build cannot parse)
	 */
	private static Boolean isDown(Minecraft client, String keyName) {
		InputConstants.Key key;
		try {
			key = InputConstants.getKey(keyName);
		} catch (IllegalArgumentException | NullPointerException e) {
			return null;
		}
		int value = key.getValue();
		if (value < 0) {
			// An unbound key. Legitimately never down -- not an error, and not a
			// reason to abandon the other three.
			return Boolean.FALSE;
		}
		return switch (key.getType()) {
			case KEYSYM -> InputConstants.isKeyDown(client.getWindow(), value);
			// isKeyDown is glfwGetKey and would misread a mouse button, so this is
			// the matching GLFW call rather than the same one with a different int.
			case MOUSE -> GLFW.glfwGetMouseButton(client.getWindow().handle(), value) == GLFW.GLFW_PRESS;
			// SCANCODE bindings carry a platform scancode, which glfwGetKey does not
			// accept. Rare enough to degrade rather than special-case.
			default -> null;
		};
	}

	private KeybindCapture() {}
}
