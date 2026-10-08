package com.example.apotheosis_spells.mixin;

import com.example.apotheosis_spells.affix.spell.CastTimeAffix;
import com.example.apotheosis_spells.affix.spell.CdSkipAffix;
import com.example.apotheosis_spells.affix.spell.ChannelEffectAffix;
import com.example.apotheosis_spells.affix.spell.CooldownAffix;
import com.example.apotheosis_spells.affix.spell.EchoAffix;
import com.example.apotheosis_spells.affix.spell.ExecuteAffix;
import com.example.apotheosis_spells.affix.spell.ManaCostAffix;
import com.example.apotheosis_spells.affix.spell.ManaStealAffix;
import com.example.apotheosis_spells.affix.spell.PostCastEffectAffix;
import com.example.apotheosis_spells.affix.spell.SchoolSignatureAffix;
import com.example.apotheosis_spells.affix.spell.SpellDurationAffix;
import com.example.apotheosis_spells.affix.spell.SpellLevelAffix;
import com.example.apotheosis_spells.affix.spell.SpellPowerAffix;
import com.example.apotheosis_spells.affix.spell.SpellRadiusAffix;
import com.example.apotheosis_spells.affix.spell.WardAffix;
import dev.shadowsoffire.apotheosis.adventure.affix.AffixRegistry;
import dev.shadowsoffire.apotheosis.adventure.affix.AffixType;
import dev.shadowsoffire.apotheosis.adventure.loot.LootRarity;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

@Mixin(value = AffixRegistry.class, remap = false)
public class RegMixin {
    @Mixin(value = LootRarity.class, remap = false)
    public static class AncientRules {
        @Inject(method = "getRules", at = @At("RETURN"), cancellable = true)
        private void apotheosis_spells$omitUnavailableAncientRules(
                CallbackInfoReturnable<List<LootRarity.LootRule>> cir) {
            if (!AffixRegistry.INSTANCE.getTypeMap().get(AffixType.ANCIENT).isEmpty()) return;
            var rules = cir.getReturnValue();
            if (rules.stream().anyMatch(rule -> rule.type() == AffixType.ANCIENT && rule.backup() == null)) {
                cir.setReturnValue(rules.stream()
                        .filter(rule -> rule.type() != AffixType.ANCIENT || rule.backup() != null).toList());
            }
        }
    }

    @Inject(method = "registerBuiltinCodecs", at = @At("TAIL"))
    protected void reg(CallbackInfo ci) {
        AffixRegistry reg = (AffixRegistry) (Object) this;
        reg.registerCodec(ResourceLocation.fromNamespaceAndPath("apotheosis_spells", "spell_power"), SpellPowerAffix.C);
        reg.registerCodec(ResourceLocation.fromNamespaceAndPath("apotheosis_spells", "mana_cost"), ManaCostAffix.C);
        reg.registerCodec(ResourceLocation.fromNamespaceAndPath("apotheosis_spells", "cooldown"), CooldownAffix.C);
        reg.registerCodec(ResourceLocation.fromNamespaceAndPath("apotheosis_spells", "cast_time"), CastTimeAffix.C);
        reg.registerCodec(ResourceLocation.fromNamespaceAndPath("apotheosis_spells", "spell_level"), SpellLevelAffix.C);
        reg.registerCodec(ResourceLocation.fromNamespaceAndPath("apotheosis_spells", "spell_radius"), SpellRadiusAffix.C);
        reg.registerCodec(ResourceLocation.fromNamespaceAndPath("apotheosis_spells", "spell_duration"), SpellDurationAffix.C);
        // 事件类特效词条（第一梯队：伤害系）
        reg.registerCodec(ResourceLocation.fromNamespaceAndPath("apotheosis_spells", "mana_steal"), ManaStealAffix.C);
        reg.registerCodec(ResourceLocation.fromNamespaceAndPath("apotheosis_spells", "execute"), ExecuteAffix.C);
        // 事件类特效词条（第二梯队）
        reg.registerCodec(ResourceLocation.fromNamespaceAndPath("apotheosis_spells", "echo"), EchoAffix.C);
        reg.registerCodec(ResourceLocation.fromNamespaceAndPath("apotheosis_spells", "cd_skip"), CdSkipAffix.C);
        // 事件类特效词条（第三梯队：生存/功能）
        reg.registerCodec(ResourceLocation.fromNamespaceAndPath("apotheosis_spells", "ward"), WardAffix.C);
        // 吟唱增益 / 施法增益（通用「施法给任意药水效果」，效果 id/等级/时长由 JSON 配）
        reg.registerCodec(ResourceLocation.fromNamespaceAndPath("apotheosis_spells", "channel_effect"), ChannelEffectAffix.C);
        reg.registerCodec(ResourceLocation.fromNamespaceAndPath("apotheosis_spells", "postcast_effect"), PostCastEffectAffix.C);
        // 学派签名词条（9 学派共用此 codec，靠 JSON 的 school 字段区分）
        reg.registerCodec(ResourceLocation.fromNamespaceAndPath("apotheosis_spells", "school_signature"), SchoolSignatureAffix.C);
    }
}
