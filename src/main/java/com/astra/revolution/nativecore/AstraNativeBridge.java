package com.astra.revolution.nativecore;

import com.astra.revolution.gui.KeybindsHudRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.invoke.MethodHandle;
import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * ASTRA NATIVE BRIDGE (Java 21 Panama FFM Architecture)
 * 
 * Bezpieczny most pomiedzy silnikiem klienta Astra a 64-bitowym rdzeniem C++ (astra-core.dll).
 * Zgodny z Java 21 Foreign Function & Memory (FFM) API (JEP 442 / JEP 454).
 *
 * Architektura 100% Fail-Safe:
 * - Wypakowuje biblioteke astra-core.dll z zasobow do katalogu gry (.minecraft/astra_native/).
 * - Dynamicznie wiaze uchwyty downcall (Linker, SymbolLookup, FunctionDescriptor).
 * - W przypadku braku DLL, bledu architektury lub braku uprawnien native-access:
 *   most ustawia flage isNativeAvailable = false, wypisuje jedno ostrzezenie i NIE powoduje crashu gry.
 */
public final class AstraNativeBridge {

    public record KeybindEntry(
            String name,
            int key,
            boolean active,
            String mode
    ) {}

    private static volatile boolean isNativeAvailable = false;
    private static volatile int nativeVersion = 0;
    private static volatile boolean hasLoggedWarning = false;

    // Uchwyty MethodHandle do funkcji eksportowanych z astra-core.dll
    private static MethodHandle mhInitCore = null;
    private static MethodHandle mhGetVersion = null;
    private static MethodHandle mhRenderKeybinds = null;

    // Odzwierciedlenie obiektow i metod FFM w pamieci
    private static Object globalArena = null;
    private static Class<?> arenaClass = null;
    private static Class<?> allocClass = null;
    private static Method allocateUtf8Method = null;

    // Rejestr biezacego stanu keybindów dla mostka natywnego
    private static final List<KeybindEntry> KEYBINDS_REGISTRY = new CopyOnWriteArrayList<>();

    // Bufor pomocniczy JSON dla minimalizacji alokacji
    private static final StringBuilder JSON_SCRATCH = new StringBuilder(512);

    static {
        initBridge();
    }

    private AstraNativeBridge() {}

    /**
     * Bezpieczna inicjalizacja mostka natywnego C++ (Fail-Safe)
     */
    public static synchronized void initBridge() {
        if (isNativeAvailable) return;

        try {
            // Weryfikacja systemu operacyjnego (aktualnie 64-bit Windows)
            String os = System.getProperty("os.name", "").toLowerCase();
            String arch = System.getProperty("os.arch", "").toLowerCase();
            if (!os.contains("win") || (!arch.contains("64") && !arch.contains("amd64"))) {
                logWarning("Natywny rdzen C++ wspierany jest wylacznie na architekturze Windows x64.");
                return;
            }

            // 1. Zlokalizuj lub wypakuj astra-core.dll z zasobow moda
            File dllFile = resolveOrExtractDll();
            if (dllFile == null || !dllFile.exists()) {
                logWarning("Brak biblioteki astra-core.dll w zasobach moda ani w katalogu lokalnym.");
                return;
            }

            // 2. Dynamiczne dowiazanie FFM Panama (Java 21)
            Class<?> linkerClass = Class.forName("java.lang.foreign.Linker");
            arenaClass = Class.forName("java.lang.foreign.Arena");
            Class<?> symbolLookupClass = Class.forName("java.lang.foreign.SymbolLookup");
            Class<?> functionDescriptorClass = Class.forName("java.lang.foreign.FunctionDescriptor");
            Class<?> valueLayoutClass = Class.forName("java.lang.foreign.ValueLayout");
            Class<?> memoryLayoutClass = Class.forName("java.lang.foreign.MemoryLayout");
            Class<?> memorySegmentClass = Class.forName("java.lang.foreign.MemorySegment");
            allocClass = Class.forName("java.lang.foreign.SegmentAllocator");
            Class<?> optionArrayClass = Class.forName("[Ljava.lang.foreign.Linker$Option;");

            Object linker = linkerClass.getMethod("nativeLinker").invoke(null);
            globalArena = arenaClass.getMethod("global").invoke(null);

            Method libLookupMethod = symbolLookupClass.getMethod("libraryLookup", Path.class, arenaClass);
            Object lookup = libLookupMethod.invoke(null, dllFile.toPath(), globalArena);

            Method findMethod = symbolLookupClass.getMethod("find", String.class);
            Optional<?> optInit = (Optional<?>) findMethod.invoke(lookup, "initNativeCore");
            Optional<?> optVer = (Optional<?>) findMethod.invoke(lookup, "getNativeVersion");
            Optional<?> optRender = (Optional<?>) findMethod.invoke(lookup, "renderKeybindsOverlay");

            if (optInit.isEmpty() || optVer.isEmpty() || optRender.isEmpty()) {
                logWarning("Biblioteka astra-core.dll nie eksportuje wymaganych symboli (init/version/render).");
                return;
            }

            Object boolLayout = valueLayoutClass.getField("JAVA_BOOLEAN").get(null);
            Object intLayout = valueLayoutClass.getField("JAVA_INT").get(null);
            Object floatLayout = valueLayoutClass.getField("JAVA_FLOAT").get(null);
            Object addrLayout = valueLayoutClass.getField("ADDRESS").get(null);

            Method ofMethod = functionDescriptorClass.getMethod("of", memoryLayoutClass, Class.forName("[Ljava.lang.foreign.MemoryLayout;"));
            Method ofVoidMethod = functionDescriptorClass.getMethod("ofVoid", Class.forName("[Ljava.lang.foreign.MemoryLayout;"));

            Object[] emptyLayouts = (Object[]) Array.newInstance(memoryLayoutClass, 0);
            Object descInit = ofMethod.invoke(null, boolLayout, emptyLayouts);
            Object descVer = ofMethod.invoke(null, intLayout, emptyLayouts);

            Object[] renderLayouts = (Object[]) Array.newInstance(memoryLayoutClass, 3);
            renderLayouts[0] = floatLayout;
            renderLayouts[1] = floatLayout;
            renderLayouts[2] = addrLayout;
            Object descRender = ofVoidMethod.invoke(null, (Object) renderLayouts);

            Method downcallMethod = linkerClass.getMethod("downcallHandle", memorySegmentClass, functionDescriptorClass, optionArrayClass);
            Object emptyOptions = Array.newInstance(Class.forName("java.lang.foreign.Linker$Option"), 0);

            mhInitCore = (MethodHandle) downcallMethod.invoke(linker, optInit.get(), descInit, emptyOptions);
            mhGetVersion = (MethodHandle) downcallMethod.invoke(linker, optVer.get(), descVer, emptyOptions);
            mhRenderKeybinds = (MethodHandle) downcallMethod.invoke(linker, optRender.get(), descRender, emptyOptions);

            // Metoda bufora znakowego
            try {
                allocateUtf8Method = allocClass.getMethod("allocateUtf8String", String.class);
            } catch (NoSuchMethodException e) {
                // Java 22+ fallback
                allocateUtf8Method = allocClass.getMethod("allocateFrom", String.class);
            }

            // Inicjalizacja rdzenia natywnego
            boolean inited = (boolean) mhInitCore.invoke();
            nativeVersion = (int) mhGetVersion.invoke();

            if (inited) {
                isNativeAvailable = true;
                System.out.println("[AstraNativeBridge] Pomyslnie zainicjalizowano rdzen C++ (v" + nativeVersion + ") przez Java 21 FFM.");
            } else {
                logWarning("Funkcja initNativeCore() zwrocila false.");
            }

        } catch (Throwable t) {
            logWarning("Inicjalizacja FFM Panama zakonczona wyjatkiem: " + t.getMessage());
            isNativeAvailable = false;
        }
    }

    /**
     * Zwraca plik DLL rozpakowany z zasobow moda do folderu gry (.minecraft/astra_native/).
     */
    private static File resolveOrExtractDll() {
        try {
            // Sprawdz najpierw katalog roboczy projektowy (dev environment)
            File devDll = new File("native-core/astra-core.dll").getAbsoluteFile();
            if (devDll.exists() && devDll.length() > 0) {
                return devDll;
            }

            File devDll2 = new File("src/main/resources/assets/astra/native/astra-core.dll").getAbsoluteFile();
            if (devDll2.exists() && devDll2.length() > 0) {
                return devDll2;
            }

            // Katalog docelowy w srodowisku gracza (.minecraft/astra_native/)
            File gameDir = Minecraft.getInstance() != null && Minecraft.getInstance().gameDirectory != null
                    ? Minecraft.getInstance().gameDirectory
                    : new File(".");

            File nativeDir = new File(gameDir, "astra_native");
            if (!nativeDir.exists()) {
                nativeDir.mkdirs();
            }

            File targetDll = new File(nativeDir, "astra-core.dll");

            // Wypakowanie z jar resources: /assets/astra/native/astra-core.dll
            InputStream in = AstraNativeBridge.class.getResourceAsStream("/assets/astra/native/astra-core.dll");
            if (in != null) {
                try (in) {
                    Files.copy(in, targetDll.toPath(), StandardCopyOption.REPLACE_EXISTING);
                }
                return targetDll;
            }

            // Fallback: jesli plik juz tam byl z poprzedniej sesji
            if (targetDll.exists() && targetDll.length() > 0) {
                return targetDll;
            }

        } catch (Throwable t) {
            logWarning("Blad podczas wypakowywania astra-core.dll: " + t.getMessage());
        }
        return null;
    }

    private static void logWarning(String msg) {
        if (!hasLoggedWarning) {
            hasLoggedWarning = true;
            System.out.println("[AstraNativeBridge] [OSTRZEZENIE] " + msg + " (Mostek dziala w trybie Pure Java Fallback).");
        }
    }

    public static boolean isNativeAvailable() {
        return isNativeAvailable;
    }

    public static int getNativeVersion() {
        return nativeVersion;
    }

    // =========================================================================
    // REJESTR DANYCH KEYBINDS DLA MODUŁÓW
    // =========================================================================

    public static void registerKeybind(String name, int key, boolean active, String mode) {
        if (name == null) return;
        KEYBINDS_REGISTRY.removeIf(e -> e.name().equalsIgnoreCase(name));
        KEYBINDS_REGISTRY.add(new KeybindEntry(name, key, active, mode != null ? mode : "TOGGLE"));
    }

    public static void updateKeybindState(String name, boolean active) {
        if (name == null) return;
        for (int i = 0; i < KEYBINDS_REGISTRY.size(); i++) {
            KeybindEntry e = KEYBINDS_REGISTRY.get(i);
            if (e.name().equalsIgnoreCase(name)) {
                KEYBINDS_REGISTRY.set(i, new KeybindEntry(e.name(), e.key(), active, e.mode()));
                return;
            }
        }
    }

    public static List<KeybindEntry> getRegisteredKeybinds() {
        return Collections.unmodifiableList(KEYBINDS_REGISTRY);
    }

    /**
     * Serializuje bieżący stan modułów do lekkiego formatu JSON.
     */
    public static synchronized String buildPayloadJson() {
        JSON_SCRATCH.setLength(0);
        JSON_SCRATCH.append("{\"modules\":[");
        for (int i = 0; i < KEYBINDS_REGISTRY.size(); i++) {
            KeybindEntry e = KEYBINDS_REGISTRY.get(i);
            if (i > 0) JSON_SCRATCH.append(',');
            JSON_SCRATCH.append("{\"name\":\"").append(e.name())
                    .append("\",\"key\":").append(e.key())
                    .append(",\"active\":").append(e.active())
                    .append(",\"mode\":\"").append(e.mode()).append("\"}");
        }
        JSON_SCRATCH.append("]}");
        return JSON_SCRATCH.toString();
    }

    // =========================================================================
    // BEZPIECZNE WYWOŁANIE NAKŁADKI W HUD (FAIL-SAFE)
    // =========================================================================

    /**
     * Punkt wejściowy wywoływany w klatce renderowania HUD.
     * Przekazuje stan do C++ przez FFM Panama (jeśli dostępne),
     * a w razie niedostępności bezpiecznie zachowuje płynne działanie klienta.
     */
    public static void renderKeybindsHud(GuiGraphics g) {
        if (!isNativeAvailable || mhRenderKeybinds == null) {
            return;
        }

        try {
            float x = KeybindsHudRenderer.hudX;
            float y = KeybindsHudRenderer.hudY;
            String json = buildPayloadJson();

            // Alokacja bufora natywnego w confined arena dla zero-leak wywołania C++
            Object confinedArena = arenaClass.getMethod("ofConfined").invoke(null);
            try (AutoCloseable autoClose = (AutoCloseable) confinedArena) {
                Object memSeg = allocateUtf8Method.invoke(confinedArena, json);
                mhRenderKeybinds.invoke(x, y, memSeg);
            }
        } catch (Throwable t) {
            // Ignorujemy błędy klatki — zero wpływu na renderowanie gry
        }
    }
}

