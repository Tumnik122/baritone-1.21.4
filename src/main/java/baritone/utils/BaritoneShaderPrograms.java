/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package baritone.utils;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import net.minecraft.client.renderer.CoreShaders;
import net.minecraft.client.renderer.ShaderDefines;
import net.minecraft.client.renderer.ShaderProgram;
import net.minecraft.resources.ResourceLocation;

/**
 * Core shader programs dedicated to Baritone overlays. They are registered during
 * {@link CoreShaders} initialization so Minecraft compiles and reloads them with
 * its regular shader lifecycle.
 */
public final class BaritoneShaderPrograms {

    public static final ShaderProgram PATH_GLOW = new ShaderProgram(
            ResourceLocation.fromNamespaceAndPath("baritone", "core/baritone_path_glow"),
            DefaultVertexFormat.POSITION_COLOR_NORMAL,
            ShaderDefines.EMPTY
    );

    public static final ShaderProgram FILL_GLOW = new ShaderProgram(
            ResourceLocation.fromNamespaceAndPath("baritone", "core/baritone_fill_glow"),
            DefaultVertexFormat.POSITION_COLOR,
            ShaderDefines.EMPTY
    );

    private static boolean registered;

    private BaritoneShaderPrograms() {
    }

    public static void register() {
        if (registered) {
            return;
        }
        CoreShaders.getProgramsToPreload().add(PATH_GLOW);
        CoreShaders.getProgramsToPreload().add(FILL_GLOW);
        registered = true;
    }
}
