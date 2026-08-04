package com.entropymod.harness;

import net.minecraft.SharedConstants;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.ai.attributes.Attribute;

import java.lang.reflect.Field;

/**
 * Reproduces enough of Fabric's start-up ordering that the mod's own classes can
 * be loaded headlessly.
 *
 * <p><b>Plain {@code Bootstrap.bootStrap()} is not enough, and the failure is a
 * hard crash rather than a bad value.</b> Vanilla freezes
 * {@code BuiltInRegistries} at the end of {@code bootStrap()}. Mod registration
 * works in game only because Fabric's registry-sync {@code BootstrapMixin}
 * replaces that freeze -- in a method literally called
 * {@code delayRegistryFreeze()} -- so mods can register during
 * {@code onInitialize}. A harness has no such mixin, so the registry really is
 * frozen, and merely touching {@code EffectBehaviors} constructs
 * {@code MagneticBootsBehavior}, which class-inits {@code EntropyAttributes},
 * which calls {@code Registry.registerForHolder} on a frozen registry and dies
 * with {@code ExceptionInInitializerError}.
 *
 * <p>So this unfreezes the attribute registry before any mod class is touched.
 * The last step is the subtle one: after registering, the new
 * {@code Holder.Reference} is still <em>unbound</em>, and every public accessor
 * that would bind it routes through {@code Holder.Reference.value()} -- the very
 * call that throws. The value is therefore read straight out of
 * {@code MappedRegistry.byValue} and pushed into the holder by reflection.
 *
 * <p>Idempotent, so every section can call it without coordinating.
 */
final class HarnessBootstrap {

	private static boolean done;

	static void init() {
		if (done) {
			return;
		}
		done = true;
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
		unfreeze(BuiltInRegistries.ATTRIBUTE);
		// Touching the class is what performs the registration -- see EntropyAttributes.
		com.entropymod.entropy.EntropyAttributes.register();
		bindHolders(BuiltInRegistries.ATTRIBUTE);
	}

	private static void unfreeze(Registry<?> registry) {
		setBoolean(registry, "frozen", false);
		// Present on MappedRegistry in this version; absent on some others, so a
		// missing field is not fatal.
		setBoolean(registry, "unregisteredIntrusiveHolders", null);
	}

	private static void setBoolean(Object target, String name, Boolean value) {
		for (Class<?> c = target.getClass(); c != null; c = c.getSuperclass()) {
			try {
				Field f = c.getDeclaredField(name);
				f.setAccessible(true);
				if (value == null) {
					f.set(target, null);
				} else {
					f.setBoolean(target, value);
				}
				return;
			} catch (ReflectiveOperationException ignored) {
				// try the superclass
			}
		}
	}

	/**
	 * Binds every {@code Holder.Reference} that is still unbound, reading the value
	 * out of the registry's own {@code byValue} map rather than through any
	 * accessor that would call {@code value()} and throw.
	 */
	@SuppressWarnings("unchecked")
	private static void bindHolders(Registry<Attribute> registry) {
		try {
			Field byValue = MappedRegistry.class.getDeclaredField("byValue");
			byValue.setAccessible(true);
			java.util.Map<Attribute, Object> map =
					(java.util.Map<Attribute, Object>) byValue.get(registry);

			Field valueField = null;
			for (java.util.Map.Entry<Attribute, Object> entry : map.entrySet()) {
				Object holder = entry.getValue();
				if (valueField == null) {
					valueField = holder.getClass().getDeclaredField("value");
					valueField.setAccessible(true);
				}
				if (valueField.get(holder) == null) {
					valueField.set(holder, entry.getKey());
				}
			}
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException("Could not bind attribute holders for the harness", e);
		}
	}

	private HarnessBootstrap() {}
}
