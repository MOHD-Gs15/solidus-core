package com.solidus.compat;

import com.solidus.SolidusMod;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * SolidusMixinPlugin — the apply-visibility channel for Solidus mixins.
 *
 * <p><b>Why (audit W-3):</b> the compatibility probes in
 * {@link CompatProbes} verify the Minecraft <i>shapes</i> we compiled
 * against. But "the shapes exist" and "our mixin actually transformed the
 * class" are different facts — a conflicting transformer from another mod
 * could in principle interfere even when the target looks intact.</p>
 *
 * <p>Mixin calls {@link #postApply} for every successfully applied
 * mixin:target pair. This plugin records the {@code ContainerClickMixin}
 * application into a flag, and the first real player connection triggers
 * a cross-check (see
 * {@link Compat#confirmClickMixinAfterFirstConnection()}): if the probes
 * passed but the apply event never fired, the GUI layer is downgraded at
 * runtime with a loud error — the same graceful-degradation path as a
 * failed probe.</p>
 *
 * <p>All other plugin hooks are intentionally permissive: this plugin
 * observes, it never vetoes.</p>
 *
 * @since 2.3.0
 */
public class SolidusMixinPlugin implements IMixinConfigPlugin {

    /** Fully-qualified name of the mixin this plugin tracks. */
    static final String CONTAINER_CLICK_MIXIN = "com.solidus.mixin.ContainerClickMixin";

    /** Set by postApply when ContainerClickMixin transformed its target. */
    private static volatile boolean containerClickMixinApplied = false;

    /**
     * @return true when Mixin reported a successful apply of
     *         ContainerClickMixin to its target class
     */
    public static boolean isContainerClickMixinApplied() {
        return containerClickMixinApplied;
    }

    // -- IMixinConfigPlugin (observation only) ----------------

    @Override
    public void onLoad(String mixinConfig) {
        SolidusMod.LOGGER.debug("Solidus mixin config loaded: {}", mixinConfig);
    }

    @Override
    public String getRefMapperConfig() {
        return null; // unobfuscated Minecraft: no refmap needed
    }

    @Override
    public List<String> getMixins() {
        return null; // all mixins are declared statically in the JSON config
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
        // no-op: observe, never veto
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        return true; // observe, never veto
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass,
                         String mixinClassName, IMixinInfo mixinInfo) {
        // no-op
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass,
                          String mixinClassName, IMixinInfo mixinInfo) {
        if (CONTAINER_CLICK_MIXIN.equals(mixinClassName)) {
            containerClickMixinApplied = true;
            SolidusMod.LOGGER.debug(
                "Compat: ContainerClickMixin applied to {}.", targetClassName);
        }
    }
}
