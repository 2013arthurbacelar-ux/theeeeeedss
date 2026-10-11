package com.stalker;

import com.stalker.entity.StalkerEntity;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.BossEvent;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * MISSÕES do Stalker.
 *
 * Ele manda uma missão com tempo limite. Uma BARRA VERMELHA no topo da tela mostra a missão, o progresso e a
 * CONTAGEM REGRESSIVA; nos últimos 30 s o jogo APITA (cada vez mais rápido). Se o tempo acabar, ele aparece
 * do seu lado e te pega.
 *
 *   DIAMONDS - ter 12 diamantes no inventário
 *   OAK      - ter 12 troncos de carvalho no inventário
 *   KILLS    - matar 12 zumbis/esqueletos (contam juntos)
 *
 * Uma missão por jogador por vez. É iniciada pelo próprio Stalker (quando está observando) ou pelo comando
 * /stalker mission.
 */
public final class StalkerMissions {
	private StalkerMissions() {}

	public enum Type {
		//        texto da barra                    meta  tempo (ticks)  mensagem do Stalker
		DIAMONDS("Pegue 12 diamantes",              12,   18000,         "bring me 12 diamonds. or i will find you."),
		OAK     ("Pegue 12 troncos de carvalho",    12,   6000,          "bring me 12 oak logs. dont be late."),
		KILLS   ("Mate 12 zumbis/esqueletos",       12,   9600,          "kill 12 zombies and skeletons. i am watching.");

		final String text;
		final int goal;
		final int ticks;
		final String chat;

		Type(String text, int goal, int ticks, String chat) {
			this.text = text;
			this.goal = goal;
			this.ticks = ticks;
			this.chat = chat;
		}
	}

	private static final class Active {
		final Type type;
		int ticksLeft;
		int progress;
		final ServerBossEvent bar;

		Active(Type type, ServerBossEvent bar) {
			this.type = type;
			this.ticksLeft = type.ticks;
			this.bar = bar;
		}
	}

	private static final Map<UUID, Active> ACTIVE = new HashMap<>();
	/** Jogador -> tick do servidor a partir do qual pode receber outra missão. */
	private static final Map<UUID, Integer> COOLDOWN = new HashMap<>();
	/** Pausa entre missões (5 minutos). */
	private static final int COOLDOWN_TICKS = 6000;

	public static void init() {
		ServerTickEvents.END_SERVER_TICK.register(StalkerMissions::tick);
		// Contagem de abates para a missão KILLS.
		ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
			if (source.getEntity() instanceof ServerPlayer player) onKill(player, entity);
		});
	}

	// ------------------------------------------------------------------ API
	public static boolean hasMission(ServerPlayer player) {
		return ACTIVE.containsKey(player.getUUID());
	}

	/** Chamado pelo Stalker: só inicia se o jogador não tem missão e já passou a pausa. */
	public static void tryStart(ServerPlayer player) {
		if (hasMission(player)) return;
		Integer until = COOLDOWN.get(player.getUUID());
		if (until != null && player.level().getServer() != null && player.level().getServer().getTickCount() < until) return;
		Type[] types = Type.values();
		start(player, types[player.getRandom().nextInt(types.length)]);
	}

	/** Inicia uma missão (substitui a atual, se houver). */
	public static void start(ServerPlayer player, Type type) {
		cancel(player);
		ServerBossEvent bar = new ServerBossEvent(
				Component.literal("MISSAO: " + type.text),
				BossEvent.BossBarColor.RED,
				BossEvent.BossBarOverlay.PROGRESS);
		bar.addPlayer(player);
		bar.setProgress(1.0F);
		ACTIVE.put(player.getUUID(), new Active(type, bar));

		say(player, type.chat);
		player.level().playSound(null, player.getX(), player.getY(), player.getZ(),
				ModSounds.WHISPER, SoundSource.HOSTILE, 1.2F, 0.8F);
	}

	/** Cancela a missão sem punição. */
	public static void cancel(ServerPlayer player) {
		Active a = ACTIVE.remove(player.getUUID());
		if (a != null) a.bar.removeAllPlayers();
	}

	// ------------------------------------------------------------------ lógica
	private static void tick(MinecraftServer server) {
		if (ACTIVE.isEmpty()) return;

		Iterator<Map.Entry<UUID, Active>> it = ACTIVE.entrySet().iterator();
		while (it.hasNext()) {
			Map.Entry<UUID, Active> entry = it.next();
			Active a = entry.getValue();
			ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());

			// Saiu do jogo ou morreu: a missão some, sem punição.
			if (player == null || !player.isAlive()) {
				a.bar.removeAllPlayers();
				it.remove();
				continue;
			}

			a.ticksLeft--;

			// Progresso por inventário (a cada 5 ticks).
			if (a.type != Type.KILLS && a.ticksLeft % 5 == 0) {
				a.progress = countItems(player, a.type == Type.DIAMONDS ? Items.DIAMOND : Items.OAK_LOG);
			}

			// Cumpriu!
			if (a.progress >= a.type.goal) {
				a.bar.removeAllPlayers();
				it.remove();
				COOLDOWN.put(entry.getKey(), server.getTickCount() + COOLDOWN_TICKS);
				say(player, "...fine. for now.");
				continue;
			}

			// Tempo esgotado: ele vem te pegar.
			if (a.ticksLeft <= 0) {
				a.bar.removeAllPlayers();
				it.remove();
				COOLDOWN.put(entry.getKey(), server.getTickCount() + COOLDOWN_TICKS);
				fail(player);
				continue;
			}

			// Barra: texto + contagem regressiva (atualiza 2x por segundo).
			if (a.ticksLeft % 10 == 0) {
				int sec = a.ticksLeft / 20;
				a.bar.setProgress(Mth.clamp((float) a.ticksLeft / a.type.ticks, 0.0F, 1.0F));
				a.bar.setName(Component.literal(String.format("MISSAO: %s   [%d/%d]   %02d:%02d",
						a.type.text, a.progress, a.type.goal, sec / 60, sec % 60)));
			}

			// APITO de aviso: a cada 2 s nos últimos 30 s, a cada 1 s nos últimos 10 s, a cada 0,5 s nos últimos 5 s.
			int t = a.ticksLeft;
			boolean beep = (t <= 100 && t % 10 == 0) || (t <= 200 && t % 20 == 0) || (t <= 600 && t % 40 == 0);
			if (beep) {
				float pitch = t <= 100 ? 1.6F : (t <= 200 ? 1.3F : 1.0F);
				player.level().playSound(null, player.getX(), player.getY(), player.getZ(),
						ModSounds.BEEP, SoundSource.MASTER, 1.0F, pitch);
			}
		}
	}

	private static void onKill(ServerPlayer player, LivingEntity victim) {
		Active a = ACTIVE.get(player.getUUID());
		if (a == null || a.type != Type.KILLS) return;
		EntityType<?> t = victim.getType();
		if (t == EntityType.ZOMBIE || t == EntityType.SKELETON || t == EntityType.HUSK
				|| t == EntityType.DROWNED || t == EntityType.STRAY || t == EntityType.ZOMBIE_VILLAGER) {
			a.progress++;
		}
	}

	private static int countItems(ServerPlayer player, Item item) {
		Inventory inv = player.getInventory();
		int n = 0;
		for (int i = 0; i < inv.getContainerSize(); i++) {
			ItemStack stack = inv.getItem(i);
			if (stack.is(item)) n += stack.getCount();
		}
		return n;
	}

	/** Falhou: ele aparece do seu lado e te pega. */
	private static void fail(ServerPlayer player) {
		say(player, "time is up.");
		if (!(player.level() instanceof ServerLevel level)) return;

		StalkerEntity stalker;
		var near = level.getEntitiesOfClass(StalkerEntity.class, player.getBoundingBox().inflate(128.0));
		if (near.isEmpty()) {
			stalker = new StalkerEntity(ModEntities.STALKER, level);
			stalker.setPos(player.getX(), player.getY(), player.getZ());
			level.addFreshEntity(stalker);
		} else {
			stalker = near.get(0);
		}
		stalker.ambush(player);
	}

	/** Mensagem de chat "falada" pelo Stalker. */
	public static void say(ServerPlayer player, String text) {
		player.sendSystemMessage(Component.literal("<The Stalker> ").withStyle(ChatFormatting.DARK_RED)
				.append(Component.literal(text).withStyle(ChatFormatting.RED)));
	}
}
