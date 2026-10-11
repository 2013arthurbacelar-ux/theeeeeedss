package com.stalker;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** Funções auxiliares de mundo usadas pelo spawner e pela IA. */
public final class StalkerUtil {
	private StalkerUtil() {}

	/** Luz efetiva (já considera a hora do dia) <= 7: noite ou caverna/local escuro. */
	public static boolean isDark(ServerLevel level, BlockPos pos) {
		return level.getMaxLocalRawBrightness(pos) <= 7;
	}

	private static boolean isFree(Level level, BlockPos pos) {
		BlockState s = level.getBlockState(pos);
		return s.getCollisionShape(level, pos).isEmpty() && s.getFluidState().isEmpty();
	}

	/**
	 * Procura, na coluna (x,z), um chão onde a criatura (3 blocos de altura livres) possa ficar.
	 * Busca de aroundY+4 até aroundY-8 para evitar topos de árvores/ montanhas.
	 */
	public static BlockPos findStandable(Level level, int x, int z, int aroundY) {
		for (int dy = 4; dy >= -8; dy--) {
			BlockPos pos = new BlockPos(x, aroundY + dy, z);
			if (!level.hasChunkAt(pos)) return null;
			BlockPos below = pos.below();
			if (level.getBlockState(below).isFaceSturdy(level, below, Direction.UP)
					&& isFree(level, pos) && isFree(level, pos.above()) && isFree(level, pos.above(2))) {
				return pos;
			}
		}
		return null;
	}

	/** O jogador tem linha de visão (sem blocos sólidos/folhas no meio) até o ponto? */
	public static boolean canSee(ServerLevel level, ServerPlayer player, Vec3 point) {
		ClipContext ctx = new ClipContext(player.getEyePosition(), point,
				ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player);
		return level.clip(ctx).getType() == HitResult.Type.MISS;
	}
}
