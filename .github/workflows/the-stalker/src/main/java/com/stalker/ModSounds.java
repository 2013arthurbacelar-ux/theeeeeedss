package com.stalker;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;

/** Sons customizados. Os eventos são mapeados para arquivos .ogg em assets/stalker/sounds.json. */
public final class ModSounds {
	private ModSounds() {}

	public static final SoundEvent BREATH      = register("stalker_breath");
	public static final SoundEvent STEP        = register("stalker_step");
	public static final SoundEvent WHISPER     = register("stalker_whisper");
	public static final SoundEvent SCREAM      = register("stalker_scream");
	public static final SoundEvent HEARTBEAT   = register("stalker_heartbeat");
	public static final SoundEvent CHASE_START = register("stalker_chase_start");
	public static final SoundEvent BEEP        = register("stalker_beep");

	private static SoundEvent register(String name) {
		Identifier id = TheStalkerMod.id(name);
		return Registry.register(BuiltInRegistries.SOUND_EVENT, id, SoundEvent.createVariableRangeEvent(id));
	}

	public static void init() {}
}
