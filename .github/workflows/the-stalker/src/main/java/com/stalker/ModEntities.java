package com.stalker;

import com.stalker.entity.StalkerEntity;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;

/** Registro dos tipos de entidade do mod. */
public final class ModEntities {
	private ModEntities() {}

	public static final ResourceKey<EntityType<?>> STALKER_KEY =
			ResourceKey.create(Registries.ENTITY_TYPE, TheStalkerMod.id("the_stalker"));

	public static final EntityType<StalkerEntity> STALKER = Registry.register(
			BuiltInRegistries.ENTITY_TYPE,
			STALKER_KEY,
			EntityType.Builder.of(StalkerEntity::new, MobCategory.MONSTER)
					.sized(0.6F, 2.3F)          // alto e magro
					.clientTrackingRange(10)    // visível de longe (10 chunks) para o stalking à distância
					.fireImmune()
					.build(STALKER_KEY));

	/** Só força o carregamento da classe (os campos estáticos fazem o registro). */
	public static void init() {}
}
