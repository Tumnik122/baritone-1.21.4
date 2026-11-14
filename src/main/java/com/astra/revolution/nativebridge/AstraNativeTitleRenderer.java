package com.astra.revolution.nativebridge;

import com.mojang.blaze3d.vertex.BufferUploader;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Zero-Allocation, Fail-Safe Native Bridge for Apple Liquid Glass TitleScreen.
 * Powered by C++20 / OpenGL 3.3 Core (astra-core.dll).
 *
 * Direct JNI invocation guarantees 100% JVM compatibility without requiring --enable-preview.
 */
public final class AstraNativeTitleRenderer {

    private static final AstraNativeTitleRenderer INSTANCE = new AstraNativeTitleRenderer();

    private boolean libraryLoaded = false;
    private boolean glInitialized = false;

    // JNI Native Declarations
    private static native boolean nInit();
    private static native void nShutdown();
    private static native void nSyncResponsiveLayout(
            float spX, float spY, float spW, float spH,
            float mpX, float mpY, float mpW, float mpH,
            float contX, float contY, float contW, float contH,
            float musX, float musY, float musW, float musH,
            float profX, float profY, float profW, float profH,
            float dropX, float dropY, float dropW, float dropH,
            float dockX, float dockY, float dockW, float dockH,
            float tipX, float tipY, float tipW, float tipH,
            float dock0X, float dock1X, float dock2X, float dock3X,
            float statusDotX, float dotsCenterX, float dotsHover, float dropAnim
    );
    private static native String[] nPollSpotify();

    private static native void nRenderFrame(
            float screenW, float screenH,
            float mouseX, float mouseY,
            float totalTime, float deltaTime,
            float spHover, float mpHover,
            float contHover, float contPlayHover,
            float sliderHover,
            float prevHover, float playHover, float nextHover,
            float profileHover,
            float dock0, float dock1, float dock2, float dock3,
            float musicProgress, boolean musicPlaying, int currentTrack,
            int hoveredDockIndex, boolean profileMenuOpen,
            int bgTextureId
    );

    private AstraNativeTitleRenderer() {
        loadNativeLibrary();
    }

    public static AstraNativeTitleRenderer getInstance() {
        return INSTANCE;
    }

    private void loadNativeLibrary() {
        try {
            Path dllPath = resolveDllPath();
            if (dllPath != null && Files.exists(dllPath)) {
                System.load(dllPath.toAbsolutePath().toString());
                this.libraryLoaded = true;
                System.out.println("[AstraNativeTitleRenderer] Successfully loaded native library: " + dllPath);
            } else {
                System.err.println("[AstraNativeTitleRenderer] Could not locate or extract astra-core.dll.");
                this.libraryLoaded = false;
            }
        } catch (Throwable t) {
            this.libraryLoaded = false;
            System.err.println("[AstraNativeTitleRenderer] Failed to load native library: " + t.getMessage());
        }
    }

    private static Path resolveDllPath() {
        Path[] candidates = new Path[]{
                Path.of("native-core", "astra-core.dll"),
                Path.of("native-core", "astra_core.dll"),
                Path.of("..", "native-core", "astra-core.dll"),
                Path.of("..", "native-core", "astra_core.dll"),
                Path.of("astra-core.dll"),
                Path.of("astra_core.dll")
        };
        for (Path p : candidates) {
            try {
                Path normalized = p.toAbsolutePath().normalize();
                if (Files.isRegularFile(normalized)) {
                    return normalized;
                }
            } catch (Throwable ignored) {}
        }
        // Fallback: extract from jar resources when packaged
        try {
            Path tempDir = Path.of(System.getProperty("java.io.tmpdir", "."), "astra_native");
            Files.createDirectories(tempDir);
            Path targetDll = tempDir.resolve("astra-core.dll");
            try (InputStream is = AstraNativeTitleRenderer.class.getResourceAsStream("/assets/astra/native/astra-core.dll")) {
                if (is != null) {
                    Files.copy(is, targetDll, StandardCopyOption.REPLACE_EXISTING);
                    return targetDll.toAbsolutePath();
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    public boolean ensureInitialized() {
        if (!this.libraryLoaded) {
            return false;
        }
        if (this.glInitialized) {
            return true;
        }
        try {
            this.glInitialized = nInit();
            if (this.glInitialized) {
                BufferUploader.reset();
            }
            return this.glInitialized;
        } catch (Throwable t) {
            t.printStackTrace();
            return false;
        }
    }

    public void syncResponsiveLayout(
            float spX, float spY, float spW, float spH,
            float mpX, float mpY, float mpW, float mpH,
            float contX, float contY, float contW, float contH,
            float musX, float musY, float musW, float musH,
            float profX, float profY, float profW, float profH,
            float dropX, float dropY, float dropW, float dropH,
            float dockX, float dockY, float dockW, float dockH,
            float tipX, float tipY, float tipW, float tipH,
            float dock0X, float dock1X, float dock2X, float dock3X,
            float statusDotX, float dotsCenterX, float dotsHover, float dropAnim
    ) {
        if (!ensureInitialized()) return;
        try {
            nSyncResponsiveLayout(
                    spX, spY, spW, spH,
                    mpX, mpY, mpW, mpH,
                    contX, contY, contW, contH,
                    musX, musY, musW, musH,
                    profX, profY, profW, profH,
                    dropX, dropY, dropW, dropH,
                    dockX, dockY, dockW, dockH,
                    tipX, tipY, tipW, tipH,
                    dock0X, dock1X, dock2X, dock3X,
                    statusDotX, dotsCenterX, dotsHover, dropAnim
            );
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    public String[] pollSpotifyTrack() {
        if (!this.libraryLoaded) return null;
        try {
            return nPollSpotify();
        } catch (Throwable t) {
            return null;
        }
    }

    public void renderFrame(
            float screenW, float screenH,
            float mouseX, float mouseY,
            float totalTime, float deltaTime,
            float spHover, float mpHover,
            float contHover, float contPlayHover,
            float sliderHover,
            float prevHover, float playHover, float nextHover,
            float profileHover,
            float dock0, float dock1, float dock2, float dock3,
            float musicProgress, boolean musicPlaying, int currentTrack,
            int hoveredDockIndex, boolean profileMenuOpen,
            int bgTextureId
    ) {
        if (!ensureInitialized()) return;
        try {
            nRenderFrame(
                    screenW, screenH,
                    mouseX, mouseY,
                    totalTime, deltaTime,
                    spHover, mpHover,
                    contHover, contPlayHover,
                    sliderHover,
                    prevHover, playHover, nextHover,
                    profileHover,
                    dock0, dock1, dock2, dock3,
                    musicProgress, musicPlaying, currentTrack,
                    hoveredDockIndex, profileMenuOpen,
                    bgTextureId
            );
            BufferUploader.reset();
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    public void shutdown() {
        if (!this.glInitialized) return;
        try {
            nShutdown();
            BufferUploader.reset();
        } catch (Throwable t) {
            t.printStackTrace();
        } finally {
            this.glInitialized = false;
        }
    }
}
