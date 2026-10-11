package com.stalker.client;

import com.stalker.ModEntities;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;

/** Ponto de entrada do cliente: renderer, estado dos efeitos e HUD. */
public class TheStalkerClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		EntityRendererRegistry.register(ModEntities.STALKER, StalkerRenderer::new);
		StalkerClientState.init();
		StalkerHud.init();
	}
}
