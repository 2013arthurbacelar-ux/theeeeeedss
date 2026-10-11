package com.stalker.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.stalker.TheStalkerMod;
import com.stalker.entity.StalkerEntity;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;

/**
 * Renderer do Stalker.
 *
 * Reaproveita o modelo humanoide vanilla (sem precisar de classe de Model própria), esticado em Y
 * para ficar alto e magro. A textura é quase preta com OLHOS BRANCO-VERMELHOS; como o brilho do
 * bloco é forçado para 15, os olhos "brilham" no escuro e o resto vira uma silhueta.
 *
 * Quer um modelo 100% custom (Blockbench)? Troque HumanoidModel por uma classe EntityModel sua
 * e registre uma ModelLayerLocation com EntityModelLayerRegistry.
 */
public class StalkerRenderer extends HumanoidMobRenderer<StalkerEntity, HumanoidRenderState, HumanoidModel<HumanoidRenderState>> {

	private static final Identifier TEXTURE = TheStalkerMod.id("textures/entity/the_stalker.png");

	public StalkerRenderer(EntityRendererProvider.Context context) {
		super(context, new HumanoidModel<>(context.bakeLayer(ModelLayers.ZOMBIE)), 0.3F);
	}

	@Override
	public HumanoidRenderState createRenderState() {
		return new HumanoidRenderState();
	}

	@Override
	public Identifier getTextureLocation(HumanoidRenderState state) {
		return TEXTURE;
	}

	/**
	 * Só a CABEÇA é visível (o resto da textura é transparente) e ela mostra o PNG do monstro (olho + sorriso).
	 * Escala 1,7x: a cabeça fica grande e flutuando, olhando para o jogador.
	 */
	@Override
	protected void scale(HumanoidRenderState state, PoseStack poseStack) {
		poseStack.scale(1.7F, 1.7F, 1.7F);
	}

	/** Luz de bloco no máximo => olhos visíveis no escuro (a textura do corpo é quase preta). */
	@Override
	protected int getBlockLightLevel(StalkerEntity entity, BlockPos pos) {
		return 15;
	}
}
