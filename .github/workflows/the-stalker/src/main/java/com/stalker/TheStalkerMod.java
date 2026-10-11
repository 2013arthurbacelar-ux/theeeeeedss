package com.stalker;

import com.stalker.entity.StalkerEntity;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Ponto de entrada comum (servidor + cliente). */
public class TheStalkerMod implements ModInitializer {
	public static final String MOD_ID = "stalker";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	/** Atalho para criar Identifiers do mod. */
	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}

	@Override
	public void onInitialize() {
		ModSounds.init();
		ModEntities.init();

		// Atributos padrão (vida, velocidade, dano...) da criatura.
		FabricDefaultAttributeRegistry.register(ModEntities.STALKER, StalkerEntity.createAttributes());

		// Spawner próprio: só à noite / em locais escuros, longe do jogador.
		StalkerSpawner.init();

		// Comandos /stalker spawn | chase | remove
		StalkerCommands.init();

		// Missões com contagem regressiva (diamantes / carvalho / abates)
		StalkerMissions.init();

		LOGGER.info("The Stalker carregado. Nao olhe para tras.");
	}
}
