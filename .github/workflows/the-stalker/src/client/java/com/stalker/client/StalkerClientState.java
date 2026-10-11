package com.stalker.client;

import com.stalker.entity.StalkerEntity;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;

import java.util.List;

/**
 * Estado dos efeitos de perseguição no cliente.
 *
 * Cada tick: procura um Stalker em modo CHASING cujo alvo seja o jogador local. Se existe,
 * a "intensidade" sobe rápido até ~1; se não existe (perseguição acabou, jogador morreu,
 * criatura sumiu/despawnou) ela cai a zero, desligando glitch + tremor.
 */
public final class StalkerClientState {
	private StalkerClientState() {}

	/** 0..1 — usada pelo HUD (glitch). */
	private static float intensity = 0.0F;
	private static float target = 0.0F;


	public static void init() {
		ClientTickEvents.END_CLIENT_TICK.register(StalkerClientState::tick);
	}

	private static void tick(Minecraft mc) {
		LocalPlayer player = mc.player;

		// Sem mundo / morto / tela de morte => efeitos desligam IMEDIATAMENTE.
		if (player == null || mc.level == null || player.isDeadOrDying() || mc.screen instanceof DeathScreen) {
			target = 0.0F;
			intensity = 0.0F;
			return;
		}

		List<StalkerEntity> chasers = mc.level.getEntitiesOfClass(
				StalkerEntity.class,
				player.getBoundingBox().inflate(96.0),
				e -> e.isChasing() && e.getTargetId() == player.getId());

		if (chasers.isEmpty()) {
			target = 0.0F;
		} else {
			// Quanto mais perto, mais forte (0.6 longe .. 1.0 colado).
			double nearest = Double.MAX_VALUE;
			for (StalkerEntity e : chasers) nearest = Math.min(nearest, e.distanceTo(player));
			target = (float) Mth.clamp(1.0 - nearest / 90.0, 0.0, 1.0) * 0.4F + 0.6F;
		}

		// Sobe rápido (0.25/tick), desce mais devagar (0.08/tick) para um fade-out suave.
		float speed = target > intensity ? 0.25F : 0.08F;
		intensity = Mth.approach(intensity, target, speed);
	}

	public static float intensity() { return intensity; }
	public static boolean active()  { return intensity > 0.01F; }

}
