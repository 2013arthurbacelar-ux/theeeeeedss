package com.stalker.client;

import com.stalker.TheStalkerMod;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.gui.GuiGraphics;

import java.util.Random;

/**
 * HUD do modo Chasing:
 *   1) ANTES de toda a HUD vanilla: empurra uma translação aleatória na pilha de matrizes => hotbar, vida,
 *      chat, título "RUN!!!" etc. TREMEM junto.
 *   2) DEPOIS do chat: devolve a matriz e desenha o overlay de GLITCH por cima de tudo
 *      (scanlines, barras horizontais deslocadas, separação RGB, pixels de ruído, vinheta vermelha, flashes).
 *
 * Observação: é um overlay 2D (rápido e compatível). Uma distorção "de verdade" dos pixels do mundo exige
 * um post-process shader (PostChain) — veja o README, seção "Upgrade opcional".
 */
public final class StalkerHud {
	private StalkerHud() {}

	private static final Random RNG = new Random();

	public static void init() {
		// Só o overlay de glitch (sem tremor de tela), desenhado depois do chat.
		HudElementRegistry.attachElementAfter(VanillaHudElements.CHAT,
				TheStalkerMod.id("shake_end_and_glitch"), (graphics, deltaTracker) -> {
					if (StalkerClientState.active()) {
						drawGlitch(graphics, StalkerClientState.intensity());
					}
				});
	}

	// ------------------------------------------------------------------ glitch
	private static void drawGlitch(GuiGraphics g, float intensity) {
		int w = g.guiWidth();
		int h = g.guiHeight();

		// Semente muda ~20x por segundo => o glitch "pisca" em vez de ficar animado suave demais.
		RNG.setSeed(System.currentTimeMillis() / 50L);

		// --- vinheta vermelha pulsante nas bordas --------------------------------
		double pulse = 0.5 + 0.5 * Math.sin(System.nanoTime() / 1.0e9 * 9.0);
		int edgeAlpha = (int) ((50 + 60 * pulse) * intensity);
		int edge = argb(edgeAlpha, 180, 0, 0);
		int bw = Math.max(6, w / 12), bh = Math.max(6, h / 10);
		g.fill(0, 0, w, bh, edge);
		g.fill(0, h - bh, w, h, edge);
		g.fill(0, 0, bw, h, edge);
		g.fill(w - bw, 0, w, h, edge);

		// --- scanlines (linhas horizontais finas) ----------------------------------
		int scanAlpha = (int) (55 * intensity);
		int scan = argb(scanAlpha, 0, 0, 0);
		int phase = RNG.nextInt(4);
		for (int y = phase; y < h; y += 4) {
			g.fill(0, y, w, y + 1, scan);
		}

		// --- barras horizontais com separação de cor (RGB split) -----------------
		int bars = 4 + (int) (8 * intensity);
		for (int i = 0; i < bars; i++) {
			int y = RNG.nextInt(h);
			int bhgt = 2 + RNG.nextInt(22);
			int x0 = RNG.nextInt(w / 3);
			int x1 = w - RNG.nextInt(w / 3);
			int shift = (RNG.nextInt(2) == 0 ? -1 : 1) * (4 + RNG.nextInt(28));
			int a = (int) (95 * intensity);
			g.fill(x0 + shift, y, x1 + shift, y + bhgt, argb(a, 255, 0, 40));   // canal vermelho deslocado
			g.fill(x0 - shift, y, x1 - shift, y + bhgt, argb(a, 0, 255, 255));  // canal ciano deslocado
			if (RNG.nextInt(4) == 0) {
				g.fill(x0, y, x1, y + 1 + RNG.nextInt(3), argb((int) (170 * intensity), 255, 255, 255)); // linha branca de interferência
			}
		}

		// --- blocos grandes de "pixels corrompidos" ----------------------------------
		int blocks = 2 + RNG.nextInt(4);
		for (int i = 0; i < blocks; i++) {
			int bwid = 20 + RNG.nextInt(Math.max(21, w / 4));
			int bhei = 6 + RNG.nextInt(30);
			int x = RNG.nextInt(Math.max(1, w - bwid));
			int y = RNG.nextInt(Math.max(1, h - bhei));
			g.fill(x, y, x + bwid, y + bhei, argb((int) (70 * intensity), RNG.nextInt(256), RNG.nextInt(256), RNG.nextInt(256)));
		}

		// --- ruído de pixels --------------------------------------------------------
		int dots = (int) (60 * intensity);
		for (int i = 0; i < dots; i++) {
			int x = RNG.nextInt(w);
			int y = RNG.nextInt(h);
			int s = 1 + RNG.nextInt(4);
			int v = RNG.nextInt(256);
			g.fill(x, y, x + s, y + s, argb((int) (150 * intensity), v, RNG.nextBoolean() ? 0 : v, RNG.nextBoolean() ? 0 : v));
		}

		// --- flash raro de tela inteira ------------------------------------------------
		int roll = RNG.nextInt(30);
		if (roll == 0) {
			g.fill(0, 0, w, h, argb((int) (60 * intensity), 255, 255, 255));
		} else if (roll == 1) {
			g.fill(0, 0, w, h, argb((int) (80 * intensity), 120, 0, 0));
		}
	}

	private static int argb(int a, int r, int gr, int b) {
		a = Math.max(0, Math.min(255, a));
		return (a << 24) | ((r & 255) << 16) | ((gr & 255) << 8) | (b & 255);
	}
}
