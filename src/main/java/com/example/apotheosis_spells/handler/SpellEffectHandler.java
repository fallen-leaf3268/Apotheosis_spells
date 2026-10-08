package com.example.apotheosis_spells.handler;

import com.example.apotheosis_spells.ApotheosisSpells;
import com.example.apotheosis_spells.affix.SpellAffix;
import com.example.apotheosis_spells.api.SpellEffects;
import io.redspace.ironsspellbooks.api.events.SpellDamageEvent;
import io.redspace.ironsspellbooks.api.events.SpellOnCastEvent;
import io.redspace.ironsspellbooks.api.magic.MagicData;
import io.redspace.ironsspellbooks.api.registry.SpellRegistry;
import io.redspace.ironsspellbooks.api.spells.AbstractSpell;
import io.redspace.ironsspellbooks.api.spells.CastSource;
import io.redspace.ironsspellbooks.api.spells.CastType;
import io.redspace.ironsspellbooks.api.spells.ICastData;
import io.redspace.ironsspellbooks.capabilities.magic.TargetEntityCastData;
import io.redspace.ironsspellbooks.damage.SpellDamageSource;
import io.redspace.ironsspellbooks.entity.spells.target_area.TargetedAreaEntity;
import io.redspace.ironsspellbooks.network.SyncManaPacket;
import io.redspace.ironsspellbooks.network.casting.OnClientCastPacket;
import io.redspace.ironsspellbooks.setup.PacketDistributor;
import io.redspace.ironsspellbooks.spells.TargetAreaCastData;
import io.redspace.ironsspellbooks.spells.TargetedTargetAreaCastData;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.HashSet;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Mod.EventBusSubscriber(modid = ApotheosisSpells.MODID)
public final class SpellEffectHandler {
    private static final ThreadLocal<Boolean> ECHOING = ThreadLocal.withInitial(() -> false);
    private static final ThreadLocal<EchoContext> ECHO_CONTEXT = new ThreadLocal<>();
    private static final Set<UUID> MANA_DIRTY = new HashSet<>();
    private static final Set<String> FAILED_ECHOES = new HashSet<>();
    private static final int ECHO_DELAY_TICKS = 4;
    private static final Map<UUID, ArrayDeque<PendingEcho>> PENDING_ECHOES = new HashMap<>();
    private static final ThreadLocal<DamageSettlement> DAMAGE = new ThreadLocal<>();

    private record PendingEcho(SpellCastHooks.Snapshot snapshot, int level, CastSource source,
                               net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension, int dueTick,
                               UUID target, String equipmentSlot, EchoArea area) {}

    private record EchoArea(Vec3 center, float radius, boolean followsTarget) {}

    private record EchoContext(MagicData magic, String equipmentSlot) {}

    public record EchoAnimation(UUID playerId, ResourceLocation animation) {
        private static final net.minecraftforge.network.simple.SimpleChannel CHANNEL =
                net.minecraftforge.network.NetworkRegistry.newSimpleChannel(
                        new ResourceLocation(ApotheosisSpells.MODID, "echo_animation"), () -> "1", "1"::equals, "1"::equals);

        public static void register() {
            CHANNEL.messageBuilder(EchoAnimation.class, 0, net.minecraftforge.network.NetworkDirection.PLAY_TO_CLIENT)
                    .encoder((message, buffer) -> {
                        buffer.writeUUID(message.playerId());
                        buffer.writeResourceLocation(message.animation());
                    })
                    .decoder(buffer -> new EchoAnimation(buffer.readUUID(), buffer.readResourceLocation()))
                    .consumerMainThread((message, context) -> net.minecraftforge.fml.DistExecutor.unsafeRunWhenOn(
                            net.minecraftforge.api.distmarker.Dist.CLIENT, () -> () -> EchoAnimationClient.play(message)))
                    .add();
        }

        static void send(ServerPlayer player, ResourceLocation animation) {
            var message = new EchoAnimation(player.getUUID(), animation);
            CHANNEL.send(net.minecraftforge.network.PacketDistributor.TRACKING_ENTITY.with(() -> player), message);
            CHANNEL.send(net.minecraftforge.network.PacketDistributor.PLAYER.with(() -> player), message);
        }
    }

    private static final class EchoAnimationClient {
        private static void play(EchoAnimation message) {
            var minecraft = net.minecraft.client.Minecraft.getInstance();
            if (minecraft.level == null) return;
            var player = minecraft.level.getPlayerByUUID(message.playerId());
            if (player == null || !player.isAlive()) return;
            boolean busy = io.redspace.ironsspellbooks.player.ClientMagicData.getSyncedSpellData(player).isCasting()
                    || player == minecraft.player && io.redspace.ironsspellbooks.player.ClientMagicData.isCasting();
            if (!busy) io.redspace.ironsspellbooks.render.animation.AnimationHelper.animatePlayerStart(player, message.animation());
            ApotheosisSpells.Diagnostics.log("ECHO_ANIMATION_CLIENT", () -> ApotheosisSpells.Diagnostics.player(player)
                    + " animation=" + message.animation() + " result=" + (busy ? "skipped_current_cast" : "requested"));
        }
    }

    public static String echoEquipmentSlot(MagicData magic) {
        var context = ECHO_CONTEXT.get();
        return context != null && context.magic() == magic ? context.equipmentSlot() : null;
    }

    private SpellEffectHandler() {}

    @SubscribeEvent
    public static void onSpellDamage(SpellDamageEvent event) {
        SpellDamageSource source = event.getSpellDamageSource();
        if (source == null || !(source.getEntity() instanceof ServerPlayer player)) return;
        var snapshot = SpellCastHooks.forDamage(source);
        if (snapshot == null) return;
        LivingEntity target = event.getEntity();
        SpellEffects effects = snapshot.effects();
        float amount = effects.modifyDamage(new SpellEffects.DamageContext(player, target, source, event.getAmount()), event.getAmount());
        if (Float.isFinite(amount) && amount >= 0) {
            float before = event.getAmount();
            event.setAmount(amount);
            ApotheosisSpells.Diagnostics.log("DAMAGE_AFFIX", () -> ApotheosisSpells.Diagnostics.player(player)
                    + " sourceSpell=" + source.spell().getSpellId() + " originSpell=" + snapshot.spellId()
                    + " target=" + target.getId() + " health=" + target.getHealth() + '/' + target.getMaxHealth()
                    + " before=" + before + " after=" + event.getAmount());
        }
    }

    public static float settledDamage(boolean succeeded, float damage) {
        return succeeded && Float.isFinite(damage) ? Math.max(0, damage) : 0;
    }

    public static final class DamageSettlement implements AutoCloseable {
        private final DamageSettlement previous;
        private final LivingEntity target;
        private final net.minecraft.world.damagesource.DamageSource source;
        private float damage;

        public DamageSettlement(LivingEntity target, net.minecraft.world.damagesource.DamageSource source) {
            this.previous = DAMAGE.get();
            this.target = target;
            this.source = source;
            DAMAGE.set(this);
        }

        public float amount(boolean succeeded) { return settledDamage(succeeded, damage); }

        @Override
        public void close() {
            if (previous == null) DAMAGE.remove();
            else DAMAGE.set(previous);
        }
    }

    public static void recordSettledDamage(LivingEntity target, net.minecraft.world.damagesource.DamageSource source, float damage) {
        var settlement = DAMAGE.get();
        if (settlement != null && settlement.target == target && settlement.source == source) settlement.damage = damage;
    }

    public static int discountedCost(int cost, float refundFraction) {
        if (!Float.isFinite(refundFraction)) return Math.max(0, cost);
        return Math.max(0, Math.round(cost * (1f - Math.max(0, Math.min(1, refundFraction)))));
    }

    public static void afterDamage(LivingEntity target, SpellDamageSource source, float damage) {
        if (!(source.getEntity() instanceof ServerPlayer player) || !Float.isFinite(damage) || damage <= 0) return;
        var snapshot = SpellCastHooks.forDamage(source);
        if (snapshot == null) return;
        snapshot.effects().afterDamage(new SpellEffects.DamageContext(player, target, source, damage));
    }

    public static void restoreMana(ServerPlayer player, float amount) {
        if (!Float.isFinite(amount) || amount <= 0) return;
        MagicData.getPlayerMagicData(player).addMana(amount);
        MANA_DIRTY.add(player.getUUID());
    }

    @SubscribeEvent
    public static void onSpellCast(SpellOnCastEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || ECHOING.get()) return;
        var snapshot = SpellCastHooks.currentSnapshot();
        if (snapshot == null || !snapshot.owner().equals(player.getUUID())
                || !snapshot.spellId().equals(event.getSpellId())) return;
        snapshot.effects().beforeCast(event);
    }

    public static void afterCast(ServerPlayer player, AbstractSpell spell, int level, CastSource source,
                                 MagicData magic, SpellCastHooks.Snapshot snapshot) {
        afterCast(player, spell, level, source, magic, snapshot, 0);
    }

    public static void afterCast(ServerPlayer player, AbstractSpell spell, int level, CastSource source,
                                 MagicData magic, SpellCastHooks.Snapshot snapshot, float paidMana) {
        if (snapshot == null || snapshot.echo() || ECHOING.get() || !snapshot.owner().equals(player.getUUID())
                || !snapshot.spellId().equals(spell.getSpellId())) return;
        snapshot.effects().afterCast(new SpellEffects.CastContext(player, spell, level, source, magic, snapshot, paidMana));
    }

    public static void queueEcho(SpellEffects.CastContext context, float chance) {
        ServerPlayer player = context.player();
        AbstractSpell spell = context.spell();
        int level = context.level();
        CastSource source = context.source();
        MagicData magic = context.magic();
        SpellCastHooks.Snapshot snapshot = context.snapshot();
        if (chance <= 0 || !Float.isFinite(chance)) return;
        var castData = magic.getAdditionalCastData();
        var area = captureEchoArea(castData);
        String skip = FAILED_ECHOES.contains(spell.getSpellId()) ? "disabled_after_error"
                : "irons_spellbooks:sacrifice".equals(spell.getSpellId()) ? "consumed_target"
                : !SpellAffix.supportsEcho(spell) ? "unsupported_cast_state"
                : castData != null && castData.getClass() != TargetEntityCastData.class
                        && castData.getClass() != TargetAreaCastData.class
                        && castData.getClass() != TargetedTargetAreaCastData.class ? "unsupported_target_data"
                : (castData instanceof TargetAreaCastData || castData instanceof TargetedTargetAreaCastData)
                        && area == null ? "invalid_area_data"
                : spell.getRecastCount(level, player) > 0 || magic.getPlayerRecasts().hasRecastForSpell(spell) ? "native_recast"
                : !player.isAlive() || player.isRemoved() ? "inactive_player" : null;
        if (skip != null) {
            ApotheosisSpells.Diagnostics.log("ECHO_SKIP", () -> ApotheosisSpells.Diagnostics.player(player)
                    + " spell=" + spell.getSpellId() + " reason=" + skip);
            return;
        }
        float roll = player.getRandom().nextFloat();
        ApotheosisSpells.Diagnostics.log("ECHO_ROLL", () -> ApotheosisSpells.Diagnostics.player(player)
                + " spell=" + spell.getSpellId() + " chance=" + chance + " roll=" + roll
                + " selected=" + (roll < chance));
        if (roll >= chance) return;
        var echo = new PendingEcho(snapshot, level, source, player.level().dimension(),
                player.server.getTickCount() + ECHO_DELAY_TICKS,
                castData instanceof TargetEntityCastData target ? target.getTargetUUID() : null,
                java.util.Objects.requireNonNullElse(magic.getCastingEquipmentSlot(), ""), area);
        PENDING_ECHOES.computeIfAbsent(player.getUUID(), key -> new ArrayDeque<>()).addLast(echo);
        ApotheosisSpells.Diagnostics.log("ECHO_QUEUED", () -> ApotheosisSpells.Diagnostics.player(player)
                + " spell=" + snapshot.spellId() + " level=" + level + " source=" + source
                + " delayTicks=" + ECHO_DELAY_TICKS + " dueTick=" + echo.dueTick()
                + " attributes=" + snapshot.attributes().size() + " target=" + echo.target()
                + " equipmentSlot=" + echo.equipmentSlot() + " area=" + echo.area());
    }

    private static EchoArea captureEchoArea(ICastData castData) {
        TargetedAreaEntity entity;
        Vec3 center;
        boolean followsTarget;
        if (castData != null && castData.getClass() == TargetedTargetAreaCastData.class) {
            entity = ((TargetedTargetAreaCastData) castData).getAreaEntity();
            center = entity == null ? null : entity.position();
            followsTarget = true;
        } else if (castData != null && castData.getClass() == TargetAreaCastData.class) {
            var data = (TargetAreaCastData) castData;
            entity = data.getCastingEntity();
            center = data.getCenter();
            followsTarget = false;
        } else return null;
        if (entity == null || center == null || !Double.isFinite(center.x)
                || !Double.isFinite(center.y) || !Double.isFinite(center.z)
                || !Float.isFinite(entity.getRadius()) || entity.getRadius() < 0) return null;
        return new EchoArea(center, entity.getRadius(), followsTarget);
    }

    private static void tickEchoes(ServerPlayer player, MagicData magic) {
        var queue = PENDING_ECHOES.get(player.getUUID());
        if (queue == null) return;
        if (!player.isAlive() || player.isRemoved()) {
            cancelEchoes(player, "inactive_player");
            return;
        }
        var ready = new ArrayList<PendingEcho>();
        while (!queue.isEmpty() && player.server.getTickCount() - queue.peekFirst().dueTick() >= 0) {
            ready.add(queue.removeFirst());
        }
        if (queue.isEmpty()) PENDING_ECHOES.remove(player.getUUID());
        for (var echo : ready) {
            var snapshot = echo.snapshot().asEcho();
            if (!player.isAlive() || player.isRemoved() || !player.level().dimension().equals(echo.dimension())
                    || FAILED_ECHOES.contains(snapshot.spellId()) || magic.getPlayerRecasts().hasRecastForSpell(snapshot.spellId())) {
                ApotheosisSpells.Diagnostics.log("ECHO_CANCEL", () -> ApotheosisSpells.Diagnostics.player(player)
                        + " spell=" + snapshot.spellId() + " reason=state_changed");
                continue;
            }
            var spell = SpellRegistry.getSpell(snapshot.spellId());
            var target = echo.target() == null ? null : echo.target().equals(player.getUUID())
                    ? player : player.serverLevel().getEntity(echo.target());
            if (echo.target() != null && (!(target instanceof LivingEntity living) || !living.isAlive() || living.isRemoved())) {
                ApotheosisSpells.Diagnostics.log("ECHO_CANCEL", () -> ApotheosisSpells.Diagnostics.player(player)
                        + " spell=" + snapshot.spellId() + " reason=target_unavailable target=" + echo.target());
                continue;
            }
            var currentCastData = magic.getAdditionalCastData();
            var previousEchoContext = ECHO_CONTEXT.get();
            boolean previousEchoing = ECHOING.get();
            TargetedAreaEntity echoArea = null;
            ECHOING.set(true);
            try (var scope = SpellCastHooks.enter(snapshot, player)) {
                ECHO_CONTEXT.set(new EchoContext(magic, echo.equipmentSlot()));
                ICastData echoCastData;
                if (echo.area() != null) {
                    var area = echo.area();
                    echoArea = new TargetedAreaEntity(player.level(), area.radius(), 0xFFFFFF);
                    echoArea.setPos(area.followsTarget() ? target.position() : area.center());
                    if (area.followsTarget()) {
                        echoArea.setOwner(target);
                        echoCastData = new TargetedTargetAreaCastData((LivingEntity) target, echoArea);
                    } else {
                        echoCastData = new TargetAreaCastData(area.center(), echoArea);
                    }
                } else {
                    echoCastData = target instanceof LivingEntity living ? new TargetEntityCastData(living) : null;
                }
                magic.setAdditionalCastData(echoCastData);
                float manaBefore = magic.getMana();
                ApotheosisSpells.Diagnostics.log("ECHO_EXECUTE", () -> ApotheosisSpells.Diagnostics.player(player)
                        + " spell=" + snapshot.spellId() + " level=" + echo.level() + " dueTick=" + echo.dueTick()
                        + " serverTick=" + player.server.getTickCount() + " attributes=" + snapshot.attributes().size()
                        + " target=" + echo.target() + " equipmentSlot=" + echo.equipmentSlot());
                spell.onCast(player.level(), echo.level(), player, echo.source(), magic);
                PacketDistributor.sendToPlayer(player, new OnClientCastPacket(snapshot.spellId(), echo.level(),
                        echo.source(), magic.getAdditionalCastData()));
                playEchoAnimation(player, spell, magic);
                ApotheosisSpells.Diagnostics.log("ECHO_COMPLETE", () -> ApotheosisSpells.Diagnostics.player(player)
                        + " spell=" + snapshot.spellId() + " mana=" + manaBefore + "->" + magic.getMana());
            } catch (RuntimeException exception) {
                if (FAILED_ECHOES.add(snapshot.spellId())) {
                    ApotheosisSpells.LOGGER.warn("Disabled failing echo for {}", snapshot.spellId(), exception);
                }
            } finally {
                magic.setAdditionalCastData(currentCastData);
                try {
                    if (echoArea != null) echoArea.discard();
                } finally {
                    if (previousEchoContext == null) ECHO_CONTEXT.remove();
                    else ECHO_CONTEXT.set(previousEchoContext);
                    if (previousEchoing) ECHOING.set(true); else ECHOING.remove();
                }
            }
        }
    }

    private static void playEchoAnimation(ServerPlayer player, AbstractSpell spell, MagicData magic) {
        if (magic.isCasting()) {
            ApotheosisSpells.Diagnostics.log("ECHO_ANIMATION", () -> ApotheosisSpells.Diagnostics.player(player)
                    + " spell=" + spell.getSpellId() + " result=skipped_current_cast");
            return;
        }
        var finish = spell.getCastFinishAnimation();
        var animation = finish.getForPlayer().or(() -> spell.getCastType() == CastType.INSTANT && finish.isPass
                        ? spell.getCastStartAnimation().getForPlayer() : java.util.Optional.empty())
                .orElseGet(() -> io.redspace.ironsspellbooks.api.spells.SpellAnimations.ANIMATION_INSTANT_CAST.getForPlayer().orElseThrow());
        EchoAnimation.send(player, animation);
        ApotheosisSpells.Diagnostics.log("ECHO_ANIMATION", () -> ApotheosisSpells.Diagnostics.player(player)
                + " spell=" + spell.getSpellId() + " animation=" + animation + " result=sent");
    }

    private static void cancelEchoes(Player player, String reason) {
        var queue = PENDING_ECHOES.remove(player.getUUID());
        if (queue == null) return;
        ApotheosisSpells.Diagnostics.log("ECHO_CANCEL", () -> ApotheosisSpells.Diagnostics.player(player)
                + " pending=" + queue.size() + " reason=" + reason);
    }

    public static void afterCompletion(ServerPlayer player, SpellEffects effects) {
        effects.afterCompletion(player);
    }

    public static void duringCast(ServerPlayer player, SpellCastHooks.Snapshot snapshot) {
        if (snapshot == null) return;
        var castType = SpellRegistry.getSpell(snapshot.spellId()).getCastType();
        if (castType != CastType.LONG && castType != CastType.CONTINUOUS) return;
        snapshot.effects().duringCast(player);
    }

    public static void blink(ServerPlayer player, int maxDistance) {
        Vec3 look = player.getLookAngle();
        Vec3 flat = new Vec3(look.x, 0, look.z);
        if (flat.lengthSqr() < 1.0e-4) return;
        flat = flat.normalize();
        for (double distance = maxDistance; distance >= 1; distance--) {
            Vec3 delta = flat.scale(distance);
            var bounds = player.getBoundingBox().move(delta);
            if (player.level().getWorldBorder().isWithinBounds(bounds) && player.level().noCollision(player, bounds)) {
                player.teleportTo(player.getX() + delta.x, player.getY(), player.getZ() + delta.z);
                player.fallDistance = 0;
                return;
            }
        }
    }

    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !(event.player instanceof ServerPlayer player)) return;
        MagicData magic = MagicData.getPlayerMagicData(player);
        if (magic.isCasting()) {
            if (player.tickCount % 5 == 0) duringCast(player, SpellCastHooks.active(player, magic.getCastingSpellId()));
        } else {
            SpellCastHooks.forget(player);
        }
        tickEchoes(player, magic);
        if (MANA_DIRTY.remove(player.getUUID())) PacketDistributor.sendToPlayer(player, new SyncManaPacket(magic));
    }

    @SubscribeEvent
    public static void onEntityJoin(EntityJoinLevelEvent event) {
        if (event.loadedFromDisk() || event.getLevel().isClientSide
                || event.getEntity() instanceof ItemEntity || event.getEntity() instanceof ExperienceOrb) return;
        SpellCastHooks.attach(event.getEntity(), SpellCastHooks.currentSnapshot());
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        cancelEchoes(event.getEntity(), "logout");
        SpellCastHooks.forget(event.getEntity());
        MANA_DIRTY.remove(event.getEntity().getUUID());
    }

    @SubscribeEvent
    public static void onClone(PlayerEvent.Clone event) {
        cancelEchoes(event.getOriginal(), "clone");
        SpellCastHooks.forget(event.getOriginal());
        MANA_DIRTY.remove(event.getOriginal().getUUID());
    }

    @SubscribeEvent
    public static void onChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        cancelEchoes(event.getEntity(), "dimension_changed");
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        PENDING_ECHOES.clear();
        MANA_DIRTY.clear();
        FAILED_ECHOES.clear();
        ECHOING.remove();
        ECHO_CONTEXT.remove();
        SpellCastHooks.reset();
    }

    public static void applyEffect(LivingEntity receiver, String effectId, int duration, int amplifier) {
        if (effectId == null || duration <= 0) return;
        ResourceLocation id = ResourceLocation.tryParse(effectId);
        if (id == null) return;
        MobEffect effect = BuiltInRegistries.MOB_EFFECT.get(id);
        if (effect != null) receiver.addEffect(new MobEffectInstance(effect, duration, Math.max(0, amplifier), false, false, true));
    }

    public static SpellEffects resolveEffectsFor(LivingEntity caster, String spellId) {
        var snapshot = SpellCastHooks.currentSnapshot();
        return caster != null && snapshot != null && snapshot.owner().equals(caster.getUUID())
                && snapshot.spellId().equals(spellId) ? snapshot.effects() : SpellEffects.NONE;
    }
}
