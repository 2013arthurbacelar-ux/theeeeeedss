package com.stalker;

import com.stalker.entity.StalkerEntity;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.Level;

/**
 * Spawner próprio (em vez de biome modifications): a cada 10s, para cada jogador no Overworld,
 * existe uma chance de aparecer UM Stalker longe dele, em um ponto ESCURO.
 * Como o brilho considera a hora do dia, isso cobre "noite" e "cavernas/locais escuros".
 */
public final class StalkerSpawner {
	private StalkerSpawner() {}

	/** Intervalo entre tentativas (ticks). */
	private static final int INTERVAL = 200;
	/** Chance por tentativa por jogador. */
	private static final float CHANCE = 0.30F;
	private static final double MIN_DIST = 38.0;
	private static final double MAX_DIST = 55.0;

	public static void init() {
		ServerTickEvents.END_WORLD_TICK.register(StalkerSpawner::tick);
	}

	private static void tick(ServerLevel level) {
		if (!Level.OVERWORLD.equals(level.dimension())) return;
		if (level.getDifficulty() == Difficulty.PEACEFUL) return;
		if (level.getGameTime() % INTERVAL != 0) return;

		for (ServerPlayer player : level.players()) {
			if (player.isSpectator() || player.isCreative()) continue;
			if (!StalkerUtil.isDark(level, player.blockPosition())) continue;

			// Só um Stalker por jogador (num raio de 128 blocos).
			if (!level.getEntitiesOfClass(StalkerEntity.class, player.getBoundingBox().inflate(128.0)).isEmpty()) continue;
			if (level.random.nextFloat() > CHANCE) continue;

			spawnNear(level, player);
		}
	}

	private static void spawnNear(ServerLevel level, ServerPlayer player) {
		for (int attempt = 0; attempt < 12; attempt++) {
			double ang = level.random.nextDouble() * Math.PI * 2.0;
			double dist = MIN_DIST + level.random.nextDouble() * (MAX_DIST - MIN_DIST);
			int x = Mth.floor(player.getX() + Math.cos(ang) * dist);
			int z = Mth.floor(player.getZ() + Math.sin(ang) * dist);

			BlockPos pos = StalkerUtil.findStandable(level, x, z, player.getBlockY());
			if (pos == null || !StalkerUtil.isDark(level, pos)) continue;

			StalkerEntity stalker = new StalkerEntity(ModEntities.STALKER, level);
			stalker.setPos(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
			level.addFreshEntity(stalker);
			return;
		}
	}
}
