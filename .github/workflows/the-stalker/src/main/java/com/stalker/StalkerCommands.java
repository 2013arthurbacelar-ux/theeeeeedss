package com.stalker;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.stalker.entity.StalkerEntity;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;

/**
 * Comandos de teste/diversão:
 *   /stalker spawn   - cria um Stalker a ~30 blocos de você (modo observação)
 *   /stalker chase   - inicia a perseguição agora (cria um se não houver)
 *   /stalker remove  - remove todos os Stalkers num raio de 256 blocos
 *   /stalker mission diamonds|oak|kills|cancel - inicia/cancela uma missão
 *
 * Obs.: sem exigência de permissão (qualquer jogador usa). Em servidor público, adicione
 * .requires(...) no literal "stalker" conforme a sua versão da API de permissões.
 */
public final class StalkerCommands {
	private StalkerCommands() {}

	public static void init() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
				dispatcher.register(Commands.literal("stalker")
						.then(Commands.literal("spawn").executes(ctx -> spawn(ctx.getSource())))
						.then(Commands.literal("chase").executes(ctx -> chase(ctx.getSource())))
						.then(Commands.literal("remove").executes(ctx -> remove(ctx.getSource())))
						.then(Commands.literal("mission")
								.then(Commands.literal("diamonds").executes(ctx -> mission(ctx.getSource(), StalkerMissions.Type.DIAMONDS)))
								.then(Commands.literal("oak").executes(ctx -> mission(ctx.getSource(), StalkerMissions.Type.OAK)))
								.then(Commands.literal("kills").executes(ctx -> mission(ctx.getSource(), StalkerMissions.Type.KILLS)))
								.then(Commands.literal("cancel").executes(ctx -> cancelMission(ctx.getSource()))))));
	}

	private static StalkerEntity create(ServerLevel level, ServerPlayer player, double dist) {
		StalkerEntity s = new StalkerEntity(ModEntities.STALKER, level);
		s.setPos(player.getX(), player.getY(), player.getZ());
		level.addFreshEntity(s);
		if (!s.placeNear(player, dist)) {
			s.discard();
			return null;
		}
		return s;
	}

	private static int spawn(CommandSourceStack src) throws CommandSyntaxException {
		ServerPlayer p = src.getPlayerOrException();
		if (create(src.getLevel(), p, 30.0) == null) {
			src.sendFailure(Component.literal("Nao achei lugar para o Stalker aparecer."));
			return 0;
		}
		src.sendSuccess(() -> Component.literal("The Stalker esta te observando..."), false);
		return 1;
	}

	private static int chase(CommandSourceStack src) throws CommandSyntaxException {
		ServerPlayer p = src.getPlayerOrException();
		ServerLevel level = src.getLevel();
		List<StalkerEntity> list = level.getEntitiesOfClass(StalkerEntity.class, p.getBoundingBox().inflate(128.0));
		StalkerEntity s = list.isEmpty() ? create(level, p, 14.0) : list.get(0);
		if (s == null) {
			src.sendFailure(Component.literal("Nao achei lugar para o Stalker aparecer."));
			return 0;
		}
		s.forceChase(p);
		return 1;
	}

	private static int mission(CommandSourceStack src, StalkerMissions.Type type) throws CommandSyntaxException {
		StalkerMissions.start(src.getPlayerOrException(), type);
		return 1;
	}

	private static int cancelMission(CommandSourceStack src) throws CommandSyntaxException {
		StalkerMissions.cancel(src.getPlayerOrException());
		src.sendSuccess(() -> Component.literal("Missao cancelada."), false);
		return 1;
	}

	private static int remove(CommandSourceStack src) throws CommandSyntaxException {
		ServerPlayer p = src.getPlayerOrException();
		List<StalkerEntity> list = src.getLevel().getEntitiesOfClass(StalkerEntity.class, p.getBoundingBox().inflate(256.0));
		list.forEach(StalkerEntity::discard);
		int n = list.size();
		src.sendSuccess(() -> Component.literal("Stalkers removidos: " + n), false);
		return n;
	}
}
