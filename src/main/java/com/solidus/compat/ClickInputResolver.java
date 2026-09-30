package com.solidus.compat;

/**
 * ClickInputResolver — stable seam over Minecraft's click-input types.
 *
 * <p>Implementations live in {@code com.solidus.compat.impl_&lt;version&gt;}
 * and are the ONLY code in the entire mod allowed to reference
 * {@code net.minecraft.world.inventory.ContainerInput} (or whatever type a
 * future Minecraft version uses instead). Everything downstream consumes
 * {@link ClickDescriptor.Input}.</p>
 *
 * <p>When a Minecraft update changes the input type: add a new
 * implementation package, flip the selection in {@link Compat} and
 * update the probe in {@link CompatProbes}. That is the whole update
 * checklist for click semantics — see {@code compat/PACKAGE.md}.</p>
 *
 * @since 2.3.0
 */
public interface ClickInputResolver {

    /**
     * @return the Minecraft version family this resolver handles
     *         (e.g. {@code "26.1"}), for diagnostics
     */
    String minecraftFamily();

    /**
     * Normalizes a raw container-input object into stable semantics.
     *
     * <p>The parameter is typed {@code Object} on purpose: the stable
     * interface must compile without the version-specific type on its
     * signature. Implementations cast it to the concrete type of their
     * Minecraft family.</p>
     *
     * @param rawContainerInput the raw input object from the packet
     *                          (may be null for exotic packets)
     * @return the normalized semantics (never null; OTHER when unknown)
     */
    ClickDescriptor.Input resolve(Object rawContainerInput);
}
