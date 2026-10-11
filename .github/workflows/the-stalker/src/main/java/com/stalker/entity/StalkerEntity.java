package com.stalker.entity;

import com.stalker.ModSounds;
import com.stalker.StalkerMissions;
import com.stalker.StalkerUtil;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundClearTitlesPacket;
import net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * THE STALKER.
 *
 * Máquina de estados simples (toda a lógica roda só no servidor):
 *
 *  STALKING  - observa de longe, parado. Se esconde atrás de blocos/folhas quando o jogador olha,
 *              chega mais perto quanto mais é encarado, e reaparece em outro ponto quando o jogador desvia o olhar.
 *  CHASING   - o jogador chegou perto demais (ou encarou por tempo demais): corre MUITO, pisca (teleporta)
 *              para perto, salta/agarra, faz sons. Mostra "RUN!!!" e liga o glitch/shake no cliente.
 *  Após o ataque a criatura some (teleporta para longe) e volta ao STALKING com um tempo de recarga.
 *
 * O estado "chasing" e o alvo são sincronizados via SynchedEntityData; o CLIENTE usa isso para ligar
 * glitch + tremor de tela (veja StalkerClientState) sem precisar de pacote customizado.
 * O título "RUN!!!" é enviado pelo servidor com os pacotes de título vanilla.
 */
public class StalkerEntity extends PathfinderMob {

	// ---------------------------------------------------------------- dados sincronizados
	private static final EntityDataAccessor<Boolean> CHASING =
			SynchedEntityData.defineId(StalkerEntity.class, EntityDataSerializers.BOOLEAN);
	private static final EntityDataAccessor<Integer> TARGET_ID =
			SynchedEntityData.defineId(StalkerEntity.class, EntityDataSerializers.INT);

	// ---------------------------------------------------------------- ajustes (mexa à vontade)
	/** Distância (blocos) em que o jogador "chegou perto demais" e a perseguição começa. */
	private static final double CHASE_TRIGGER_DIST = 12.0;
	/** Ticks acumulados encarando a criatura (com linha de visão) que forçam a perseguição. */
	private static final int LOOK_LIMIT_TICKS = 400;
	/** Dano do ataque (20 = 10 corações). */
	private static final float ATTACK_DAMAGE = 10.0F;
	/** Velocidade de corrida (multiplicador do atributo de velocidade, 0.40 base). */
	private static final double CHASE_SPEED = 1.35;
	/** Máximo de ticks perseguindo (60 s) antes de desistir. */
	private static final int MAX_CHASE_TICKS = 1200;
	/** Tempo de vida máximo se ficar só observando (10 min). */
	private static final int MAX_LIFE_TICKS = 12000;

	/** Luz de bloco (tochas etc.) no jogador a partir da qual ele está "seguro": ela não inicia perseguição e mantém distância. */
	private static final int LIGHT_SAFE = 12;
	/** Luz de bloco muito forte (colado numa tocha/lanterna): a perseguição é quebrada se durar ~2,5 s. */
	private static final int LIGHT_REPEL = 13;

	private enum Mode { STALKING, CHASING }

	// ---------------------------------------------------------------- estado (servidor)
	private Mode mode = Mode.STALKING;
	private int lifeTicks;
	private int noTargetTicks;
	private int lookTicks;            // quanto tempo foi encarado
	private int chaseCooldown = 100;  // não persegue antes disso (ticks)
	private int repositionTimer = 100;
	private int chaseTicks;
	private int blinkTimer;
	private int leapCooldown;
	private int titleTimer;
	private int whisperTimer = 200;
	private int breathTimer = 100;
	private int heartbeatTimer;
	private int safeTicks;            // ticks em lugar claro/fora do horário (para sumir de dia)
	private int phantomSteps;         // passos fantasmas restantes (atrás do jogador)
	private int footstepTimer = 300;
	private int msgTimer = 400;
	private int darknessTimer = 200;
	private int chatTimer = 500;
	private int missionTimer = 400;
	private int lightTicks;           // ticks seguidos sob luz forte durante a perseguição

	public StalkerEntity(EntityType<? extends PathfinderMob> type, Level level) {
		super(type, level);
		this.xpReward = 0;
		this.setPersistenceRequired();
	}

	public static AttributeSupplier.Builder createAttributes() {
		return Mob.createMobAttributes()
				.add(Attributes.MAX_HEALTH, 80.0)
				.add(Attributes.MOVEMENT_SPEED, 0.40)
				.add(Attributes.FOLLOW_RANGE, 96.0)
				.add(Attributes.ATTACK_DAMAGE, ATTACK_DAMAGE)
				.add(Attributes.STEP_HEIGHT, 1.0);
	}

	@Override
	protected void defineSynchedData(SynchedEntityData.Builder builder) {
		super.defineSynchedData(builder);
		builder.define(CHASING, false);
		builder.define(TARGET_ID, -1);
	}

	@Override
	protected void registerGoals() {
		this.goalSelector.addGoal(0, new FloatGoal(this)); // não se afoga; o resto é feito em serverTick()
	}

	// ---------------------------------------------------------------- acesso do cliente
	public boolean isChasing() { return this.entityData.get(CHASING); }
	public int getTargetId()   { return this.entityData.get(TARGET_ID); }

	// ---------------------------------------------------------------- tick
	@Override
	public void tick() {
		super.tick();
		if (this.level() instanceof ServerLevel serverLevel) {
			serverTick(serverLevel);
		}
	}

	private void serverTick(ServerLevel level) {
		lifeTicks++;

		ServerPlayer player = findTarget(level);
		if (player == null) {
			if (++noTargetTicks > 200) this.discard();
			return;
		}
		noTargetTicks = 0;

		if (mode == Mode.CHASING) {
			tickChasing(level, player);
		} else {
			tickStalking(level, player);
		}
	}

	private ServerPlayer findTarget(ServerLevel level) {
		// true = ignora jogadores em creative/spectator
		return level.getNearestPlayer(this.getX(), this.getY(), this.getZ(), 128.0, true) instanceof ServerPlayer sp ? sp : null;
	}

	// ================================================================ STALKING
	private void tickStalking(ServerLevel level, ServerPlayer player) {
		double dist = this.distanceTo(player);
		boolean looking = isPlayerLooking(player);

		// Fica olhando para o jogador (cabeça/corpo viram).
		this.getLookControl().setLookAt(player, 60.0F, 60.0F);
		this.getNavigation().stop();

		// Despawn: só aparece à noite/escuro. Se o ambiente ficar claro por muito tempo, some.
		if (StalkerUtil.isDark(level, player.blockPosition())) safeTicks = 0;
		else if (++safeTicks > 400) { this.discard(); return; }
		if (lifeTicks > MAX_LIFE_TICKS && !looking) { this.discard(); return; }

		if (chaseCooldown > 0) chaseCooldown--;

		// --- acumula "olhar" -------------------------------------------------
		if (looking) lookTicks++;
		else lookTicks = Math.max(0, lookTicks - 2);

		// --- gatilho da perseguição -----------------------------------------
		boolean lit = blockLightAt(level, player) >= LIGHT_SAFE;
		if (chaseCooldown == 0 && !lit) {
			boolean tooClose = dist < CHASE_TRIGGER_DIST;
			boolean stareTooLong = lookTicks > LOOK_LIMIT_TICKS && dist < 40.0;
			if (tooClose || stareTooLong) {
				startChase(level, player);
				return;
			}
		}

		// --- quanto mais olha, mais perto fica -------------------------------
		// Enquanto encarada: a cada 2,5 s "dá um passo" (teleporta ~4 blocos mais perto, já escondida se possível).
		if (looking && lookTicks % 50 == 0 && dist > CHASE_TRIGGER_DIST + 4.0) {
			if (relocate(level, player, dist - 4.0, true, false)) {
				playAt(ModSounds.BREATH, 0.8F, 0.8F);
			}
		}
		// Encarada por 5 s sem chegar perto: se esconde (some atrás de algo) – o jogador a perde de vista.
		if (looking && lookTicks > 0 && lookTicks % 100 == 99) {
			relocate(level, player, dist, true, false);
		}

		// --- quando NÃO está sendo observada, muda de posição na periferia ---
		if (!looking && --repositionTimer <= 0) {
			repositionTimer = 60 + this.random.nextInt(120);
			double newDist = Mth.clamp(dist - (2 + this.random.nextInt(5)), CHASE_TRIGGER_DIST + 6.0, 60.0);
			// outOfView=true: só teleporta para um ponto fora do campo de visão => nunca "pisca" na cara do jogador
			relocate(level, player, newDist, this.random.nextBoolean(), true);
		}

		stalkingAmbience(level, player, dist);
		stalkingTricks(level, player, dist, lit);
	}

	/** Respiração, sussurros e batimento cardíaco que aumentam com a proximidade. */
	private void stalkingAmbience(ServerLevel level, ServerPlayer player, double dist) {
		if (dist < 30.0 && --breathTimer <= 0) {
			breathTimer = 90 + this.random.nextInt(90);
			playAt(ModSounds.BREATH, 0.6F, 0.7F + this.random.nextFloat() * 0.2F);
		}
		if (dist < 45.0 && --whisperTimer <= 0) {
			whisperTimer = 200 + this.random.nextInt(300);
			playAt(ModSounds.WHISPER, 0.7F, 0.9F);
		}
		if (dist < 24.0 && --heartbeatTimer <= 0) {
			heartbeatTimer = (int) Mth.clamp(dist * 2.2, 20, 60); // mais perto = mais rápido
			float vol = (float) Mth.clamp(1.2 - dist / 24.0, 0.2, 1.0);
			level.playSound(null, player.getX(), player.getY(), player.getZ(),
					ModSounds.HEARTBEAT, SoundSource.HOSTILE, vol, 1.0F);
		}
	}

	/**
	 * Truques psicológicos enquanto ela observa:
	 *  - PASSOS FANTASMA atrás de você (e às vezes uma porta abrindo ao longe);
	 *  - mensagens sussurradas na barra de ação;
	 *  - pulsos de ESCURIDÃO (efeito Darkness) quando ela está perto;
	 *  - APAGA tochas ao seu redor (elas caem como item);
	 *  - se você está sob luz forte (tochas/lanternas) ela mantém distância — luz = segurança.
	 */
	private void stalkingTricks(ServerLevel level, ServerPlayer player, double dist, boolean lit) {
		// 1) passos fantasma
		if (dist < 32.0 && --footstepTimer <= 0) {
			footstepTimer = 400 + this.random.nextInt(600);
			phantomSteps = 5 + this.random.nextInt(4);
			if (this.random.nextInt(4) == 0) { // porta abrindo ao longe, atrás de você
				Vec3 look = player.getViewVector(1.0F);
				level.playSound(null, player.getX() - look.x * 12.0, player.getY(), player.getZ() - look.z * 12.0,
						SoundEvents.WOODEN_DOOR_OPEN, SoundSource.BLOCKS, 1.0F, 0.9F);
			}
		}
		if (phantomSteps > 0 && this.tickCount % 9 == 0) {
			Vec3 look = player.getViewVector(1.0F);
			double back = 1.5 + phantomSteps; // cada passo mais perto que o anterior
			SoundEvent step = this.random.nextBoolean() ? SoundEvents.GRASS_STEP : SoundEvents.STONE_STEP;
			level.playSound(null, player.getX() - look.x * back, player.getY(), player.getZ() - look.z * back,
					step, SoundSource.HOSTILE, 0.9F, 0.7F);
			phantomSteps--;
		}

		// 2) mensagens na barra de ação
		if (dist < 26.0 && --msgTimer <= 0) {
			msgTimer = 500 + this.random.nextInt(700);
			String[] msgs = { "Atras de voce.", "Ele esta te vendo.", "Nao olhe.", "Voce nao esta sozinho.",
					"Ele sabe onde voce esta.", "Fique perto da luz." };
			player.connection.send(new ClientboundSetActionBarTextPacket(
					Component.literal(msgs[this.random.nextInt(msgs.length)])
							.withStyle(ChatFormatting.DARK_RED, ChatFormatting.ITALIC)));
		}

		// 2b) fala no chat: "<The Stalker> i hate you."
		if (dist < 40.0 && --chatTimer <= 0) {
			chatTimer = 700 + this.random.nextInt(900);
			String[] lines = { "i hate you.", "dont cry.", "did you." };
			StalkerMissions.say(player, lines[this.random.nextInt(lines.length)]);
			playAt(ModSounds.WHISPER, 0.8F, 0.8F);
		}

		// 2c) de vez em quando ele te dá uma missão (veja StalkerMissions)
		if (dist < 45.0 && --missionTimer <= 0) {
			missionTimer = 3600 + this.random.nextInt(3600);
			StalkerMissions.tryStart(player);
		}

		// 3) pulsos de escuridão
		if (dist < 18.0 && --darknessTimer <= 0) {
			darknessTimer = 300 + this.random.nextInt(300);
			player.addEffect(new MobEffectInstance(MobEffects.DARKNESS, 140, 0, false, false));
		}

		// 4) apaga tochas perto do jogador
		if (dist < 22.0 && this.tickCount % 40 == 0 && this.random.nextInt(3) == 0) {
			snuffTorch(level, player);
		}

		// 5) luz = segurança: ela recua e não ataca
		if (lit && dist < CHASE_TRIGGER_DIST + 2.0 && this.tickCount % 40 == 0) {
			relocate(level, player, 20.0, true, false);
		}
	}

	/** Quebra UMA tocha (comum/alma) num raio de 7 blocos do jogador. */
	private void snuffTorch(ServerLevel level, ServerPlayer player) {
		BlockPos c = player.blockPosition();
		for (BlockPos bp : BlockPos.betweenClosed(c.offset(-7, -3, -7), c.offset(7, 3, 7))) {
			BlockState bs = level.getBlockState(bp);
			if (bs.is(Blocks.TORCH) || bs.is(Blocks.WALL_TORCH) || bs.is(Blocks.SOUL_TORCH) || bs.is(Blocks.SOUL_WALL_TORCH)) {
				BlockPos pos = bp.immutable();
				level.playSound(null, pos, SoundEvents.FIRE_EXTINGUISH, SoundSource.BLOCKS, 1.0F, 1.0F);
				level.destroyBlock(pos, true);
				return;
			}
		}
	}

	private static int blockLightAt(ServerLevel level, ServerPlayer player) {
		return level.getBrightness(LightLayer.BLOCK, player.blockPosition());
	}

	// ---------------------------------------------------------------- usado pelos comandos /stalker
	/** Força a perseguição agora (comando /stalker chase). */
	public void forceChase(ServerPlayer player) {
		if (this.level() instanceof ServerLevel level) {
			chaseCooldown = 0;
			startChase(level, player);
		}
	}

	/** Emboscada (missão falhou): aparece a ~4 blocos e já parte para cima, saltando de imediato. */
	public void ambush(ServerPlayer player) {
		if (!(this.level() instanceof ServerLevel level)) return;
		if (!relocate(level, player, 4.0, false, false)) {
			this.setPos(player.getX() + 2.0, player.getY(), player.getZ());
		}
		chaseCooldown = 0;
		startChase(level, player);
		leapCooldown = 0;
	}

	/** Teleporta para ~dist blocos do jogador (comando /stalker spawn). */
	public boolean placeNear(ServerPlayer player, double dist) {
		return this.level() instanceof ServerLevel level && relocate(level, player, dist, false, false);
	}

	// ================================================================ CHASING
	private void startChase(ServerLevel level, ServerPlayer player) {
		mode = Mode.CHASING;
		chaseTicks = 0;
		blinkTimer = 40 + this.random.nextInt(30);
		leapCooldown = 40;
		titleTimer = 0;
		this.entityData.set(CHASING, true);
		this.entityData.set(TARGET_ID, player.getId());

		// Grito de início + "sting" que o jogador ouve em cima dele.
		level.playSound(null, player.getX(), player.getY(), player.getZ(),
				ModSounds.CHASE_START, SoundSource.HOSTILE, 1.5F, 1.0F);
		playAt(ModSounds.SCREAM, 2.0F, 0.8F);
		sendRunTitle(player);
	}

	/** Mostra "RUN!!!" em vermelho, grande e centralizado (título vanilla do Minecraft). */
	private void sendRunTitle(ServerPlayer player) {
		player.connection.send(new ClientboundSetTitlesAnimationPacket(0, 70, 10)); // fadeIn, stay, fadeOut
		player.connection.send(new ClientboundSetSubtitleTextPacket(
				Component.literal("it's behind you").withStyle(ChatFormatting.DARK_RED, ChatFormatting.ITALIC)));
		player.connection.send(new ClientboundSetTitleTextPacket(
				Component.literal("RUN!!!").withStyle(ChatFormatting.RED, ChatFormatting.BOLD)));
	}

	private void tickChasing(ServerLevel level, ServerPlayer player) {
		// Jogador morreu / saiu / virou creative => perseguição acaba (e o cliente desliga os efeitos).
		if (!player.isAlive() || player.isSpectator() || player.isCreative()) {
			endChase(level, player, false);
			return;
		}

		chaseTicks++;
		double dist = this.distanceTo(player);

		// Desiste se o jogador escapou muito longe ou se passou tempo demais.
		if (dist > 64.0 || chaseTicks > MAX_CHASE_TICKS) {
			endChase(level, player, false);
			return;
		}

		// Luz forte (colado numa tocha/lanterna) por ~2,5 s quebra a perseguição: ele recua.
		if (blockLightAt(level, player) >= LIGHT_REPEL) {
			if (++lightTicks > 50) {
				lightTicks = 0;
				player.connection.send(new ClientboundSetActionBarTextPacket(
						Component.literal("A luz o afastou...").withStyle(ChatFormatting.YELLOW, ChatFormatting.ITALIC)));
				endChase(level, player, false);
				return;
			}
		} else {
			lightTicks = Math.max(0, lightTicks - 1);
		}

		// Mantém o título na tela durante a perseguição.
		if (++titleTimer >= 60) {
			titleTimer = 0;
			sendRunTitle(player);
		}

		// --- movimento rápido ---------------------------------------------
		this.getLookControl().setLookAt(player, 90.0F, 90.0F);
		this.getNavigation().moveTo(player, CHASE_SPEED);

		// --- sons -----------------------------------------------------------
		if (chaseTicks % 35 == 0) playAt(ModSounds.BREATH, 1.4F, 0.6F);
		if (chaseTicks % 90 == 0 && this.random.nextInt(2) == 0) playAt(ModSounds.WHISPER, 1.2F, 0.7F);
		if (chaseTicks % 140 == 0) playAt(ModSounds.SCREAM, 1.5F, 0.9F + this.random.nextFloat() * 0.3F);

		// --- pisca: some e reaparece mais perto, em outro ângulo ------------
		if (--blinkTimer <= 0) {
			blinkTimer = 50 + this.random.nextInt(60);
			if (dist > 9.0) {
				double newDist = Mth.clamp(dist * 0.5, 7.0, 14.0);
				if (relocate(level, player, newDist, false, false)) {
					playAt(ModSounds.WHISPER, 1.4F, 1.2F);
				}
			}
		}

		// --- salto / agarrar --------------------------------------------------
		if (leapCooldown > 0) leapCooldown--;
		if (leapCooldown == 0 && dist > 3.0 && dist < 8.0 && this.hasLineOfSight(player) && this.onGround()) {
			Vec3 dir = player.position().subtract(this.position()).normalize();
			this.setDeltaMovement(dir.x * 1.2, 0.45, dir.z * 1.2);
			this.hurtMarked = true;
			leapCooldown = 60;
			playAt(ModSounds.SCREAM, 1.6F, 1.1F);
		}

		// --- contato = ataque -------------------------------------------------
		if (this.getBoundingBox().inflate(0.6).intersects(player.getBoundingBox())) {
			grab(level, player);
		}
	}

	/** Agarra o jogador: dano alto + atordoamento, depois some. */
	private void grab(ServerLevel level, ServerPlayer player) {
		player.hurtServer(level, level.damageSources().mobAttack(this), ATTACK_DAMAGE);
		// Atordoado: quase imóvel, visão escura e tontura.
		player.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 50, 5));
		player.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, 40, 0));
		player.addEffect(new MobEffectInstance(MobEffects.NAUSEA, 120, 0));
		player.setDeltaMovement(Vec3.ZERO);
		player.hurtMarked = true;
		level.playSound(null, player.getX(), player.getY(), player.getZ(),
				ModSounds.SCREAM, SoundSource.HOSTILE, 2.0F, 0.6F);

		endChase(level, player, true);
	}

	/** Encerra a perseguição; se atacou, some para longe e fica em recarga (volta a perseguir depois). */
	private void endChase(ServerLevel level, ServerPlayer player, boolean attacked) {
		mode = Mode.STALKING;
		this.entityData.set(CHASING, false);   // => o cliente desliga glitch/shake
		this.entityData.set(TARGET_ID, -1);
		this.getNavigation().stop();
		lookTicks = 0;
		repositionTimer = 100;
		chaseCooldown = attacked ? 600 + this.random.nextInt(600) : 300;

		player.connection.send(new ClientboundClearTitlesPacket(true));

		// Desaparece: vai para longe, escondida. Se não achar ponto, simplesmente some.
		if (!relocate(level, player, 50.0, true, false)) {
			this.discard();
		}
	}

	// ================================================================ utilidades
	/** O jogador está olhando (±28°) para a criatura, com linha de visão livre? */
	private boolean isPlayerLooking(ServerPlayer player) {
		Vec3 toMe = this.getEyePosition().subtract(player.getEyePosition()).normalize();
		return player.getViewVector(1.0F).dot(toMe) > 0.88 && player.hasLineOfSight(this);
	}

	/** O ponto está dentro do campo de visão (~45°) do jogador? */
	private static boolean isInPlayerView(ServerPlayer player, Vec3 point) {
		Vec3 dir = point.subtract(player.getEyePosition()).normalize();
		return player.getViewVector(1.0F).dot(dir) > 0.7;
	}

	/**
	 * Teleporta a criatura para um ponto a ~dist blocos do jogador.
	 *
	 * @param preferHidden tenta um ponto SEM linha de visão (atrás de árvore/pedra/parede); se não achar, aceita qualquer um
	 * @param outOfView    só aceita pontos fora do campo de visão do jogador (para não "piscar" na cara dele)
	 * @return true se achou lugar
	 */
	private boolean relocate(ServerLevel level, ServerPlayer player, double dist, boolean preferHidden, boolean outOfView) {
		double baseAng = Math.atan2(this.getZ() - player.getZ(), this.getX() - player.getX());

		for (int pass = 0; pass < 2; pass++) {
			boolean needHidden = preferHidden && pass == 0;
			for (int i = 0; i < 14; i++) {
				// 1ª metade: perto do ângulo atual; 2ª metade: qualquer ângulo.
				double ang = i < 7 ? baseAng + (this.random.nextDouble() - 0.5) * 1.6
				                   : this.random.nextDouble() * Math.PI * 2.0;
				double d = dist + (this.random.nextDouble() * 3.0 - 1.5);
				int x = Mth.floor(player.getX() + Math.cos(ang) * d);
				int z = Mth.floor(player.getZ() + Math.sin(ang) * d);

				BlockPos pos = StalkerUtil.findStandable(level, x, z, player.getBlockY());
				if (pos == null) continue;

				Vec3 feet = new Vec3(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
				Vec3 eye = feet.add(0, 1.9, 0);
				if (outOfView && isInPlayerView(player, eye)) continue;
				if (needHidden && StalkerUtil.canSee(level, player, eye)) continue;

				this.setPos(feet.x, feet.y, feet.z);
				this.getNavigation().stop();
				this.setDeltaMovement(Vec3.ZERO);
				return true;
			}
		}
		return false;
	}

	private void playAt(SoundEvent sound, float volume, float pitch) {
		this.level().playSound(null, this.getX(), this.getY(), this.getZ(), sound, SoundSource.HOSTILE, volume, pitch);
	}

	// ---------------------------------------------------------------- comportamento vanilla alterado
	/** Passos pesados customizados (só durante a corrida, para o stalking ser silencioso). */
	@Override
	protected void playStepSound(BlockPos pos, BlockState state) {
		if (isChasing()) this.playSound(ModSounds.STEP, 0.9F, 0.8F + this.random.nextFloat() * 0.2F);
	}

	/** Praticamente imortal: apanhar só a faz "piscar" para perto (ou sumir). Só /kill e o void a matam. */
	@Override
	public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
		if (source.is(DamageTypeTags.BYPASSES_INVULNERABILITY)) {
			return super.hurtServer(level, source, amount);
		}
		if (mode == Mode.CHASING) blinkTimer = 0; // reage ao golpe teleportando na próxima tick
		return false;
	}

	@Override
	public boolean removeWhenFarAway(double distanceToClosestPlayer) {
		return false; // o despawn é feito manualmente em serverTick()
	}

	/** Não é salva no mundo: ao recarregar o save ela simplesmente não existe mais (e some o estado de perseguição). */
	@Override
	public boolean shouldBeSaved() {
		return false;
	}

	@Override
	protected SoundEvent getAmbientSound() { return null; }
	@Override
	protected SoundEvent getHurtSound(DamageSource source) { return null; }
	@Override
	protected SoundEvent getDeathSound() { return null; }
}
