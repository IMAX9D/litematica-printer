package me.aleksilassila.litematica.printer.integration;

import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.malilib.config.options.ConfigOptionList;
import fi.dy.masa.malilib.util.IntBoundingBox;
import fi.dy.masa.malilib.util.LayerMode;
import fi.dy.masa.malilib.util.LayerRange;
import me.aleksilassila.litematica.printer.enums.SelectionType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;

/**
 * Narrows a legacy player-range envelope to Litematica's active render range.
 *
 * <p>This is an enumeration optimization only. The native per-position render
 * range check remains in place as the correctness boundary. Unsupported API
 * versions, non-render-layer selection modes and every non-single layer mode
 * therefore retain the native envelope and behavior.</p>
 */
public final class LegacyRenderLayerScanBounds {
    private static final long INACTIVE_SIGNATURE = 0L;

    private LegacyRenderLayerScanBounds() {
    }

    /**
     * Returns a compact change signature for invalidating an in-progress box
     * when the user changes axis, layer mode or layer limits.
     */
    public static long signature(ConfigOptionList selectionType) {
        if (!usesRenderLayer(selectionType)) return INACTIVE_SIGNATURE;

        try {
            LayerRange range = DataManager.getRenderLayerRange();
            if (range == null || range.getLayerMode() != LayerMode.SINGLE_LAYER
                    || range.getAxis() == null) {
                return INACTIVE_SIGNATURE;
            }

            long value = 0x6A09E667F3BCC909L;
            value = mix(value, range.getAxis().ordinal());
            value = mix(value, range.getLayerSingle());
            return value != INACTIVE_SIGNATURE ? value : 1L;
        } catch (RuntimeException | LinkageError ignored) {
            // Fail open to build.365's original full range. The native
            // per-position selection check still enforces correctness.
            return INACTIVE_SIGNATURE;
        }
    }

    /**
     * Resolves constructor bounds, intersecting them with a SINGLE_LAYER range
     * when that is the selected Printer boundary.
     */
    public static Bounds resolve(ConfigOptionList selectionType,
            int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        Bounds original = new Bounds(minX, minY, minZ, maxX, maxY, maxZ);
        if (!usesRenderLayer(selectionType)) {
            return original;
        }

        try {
            LayerRange range = DataManager.getRenderLayerRange();
            if (range == null || range.getLayerMode() != LayerMode.SINGLE_LAYER
                    || range.getAxis() == null) {
                return original;
            }

            IntBoundingBox clamped = range.getClampedArea(
                    minX, minY, minZ, maxX, maxY, maxZ);
            if (clamped == null) {
                // A layer outside the reachable envelope has no safe derived
                // coordinate. Preserve the native box; its unchanged reach
                // and selection checks reject every position without risking
                // inverted world-height bounds.
                return original;
            }
            ClientLevel level = Minecraft.getInstance().level;
            if (level != null && (clamped.maxY < level.getMinBuildHeight()
                    || clamped.minY >= level.getMaxBuildHeight())) {
                return original;
            }
            return new Bounds(clamped.minX, clamped.minY, clamped.minZ,
                    clamped.maxX, clamped.maxY, clamped.maxZ);
        } catch (RuntimeException | LinkageError ignored) {
            return original;
        }
    }

    /** Immutable six-coordinate constructor argument snapshot. */
    public record Bounds(int minX, int minY, int minZ,
            int maxX, int maxY, int maxZ) {
    }

    private static boolean usesRenderLayer(ConfigOptionList selectionType) {
        return selectionType != null
                && selectionType.getOptionListValue()
                        == SelectionType.LITEMATICA_RENDER_LAYER;
    }

    private static long mix(long value, int component) {
        return (value ^ Integer.toUnsignedLong(component)) * 0x100000001B3L;
    }
}
