#pragma once

#include <cstdint>

#if defined(_WIN32) || defined(_WIN64)
    #if defined(ASTRA_CORE_EXPORTS)
        #define ASTRA_API __declspec(dllexport)
    #else
        #define ASTRA_API __declspec(dllimport)
    #endif
#else
    #define ASTRA_API __attribute__((visibility("default")))
#endif

#ifdef __cplusplus
extern "C" {
#endif

// ============================================================================
// 1. MODUŁ KEYBINDS HUD (PANAMA FFM / C ABI)
// ============================================================================

enum AstraKeybindMode : int32_t {
    ASTRA_MODE_TOGGLE = 0,
    ASTRA_MODE_HOLD   = 1
};

#pragma pack(push, 1)
struct AstraKeybindNativeEntry {
    int32_t id;             // Unikalne ID modułu
    int32_t mode;           // AstraKeybindMode (0 = TOGGLE, 1 = HOLD)
    uint8_t active;         // 1 = aktywny, 0 = nieaktywny
    uint8_t _pad[3];        // Wyrównanie do 4 bajtów
    char    name[48];       // Nazwa modułu UTF-8 zakończona '\0' (np. "GrimAC Bypass")
    char    keyLabel[16];   // Nazwa klawisza UTF-8 zakończona '\0' (np. "R", "LSHIFT", "M4")
};
#pragma pack(pop)

ASTRA_API bool astra_init();
ASTRA_API void astra_shutdown();

ASTRA_API void astra_keybinds_clear();
ASTRA_API void astra_keybinds_upsert(
    int32_t id,
    const char* nameUtf8,
    const char* keyLabelUtf8,
    int32_t mode,
    uint8_t active
);
ASTRA_API void astra_keybinds_remove(int32_t id);
ASTRA_API void astra_keybinds_sync_batch(
    const AstraKeybindNativeEntry* entries,
    int32_t count
);

ASTRA_API void astra_keybinds_set_config(
    uint8_t visible,
    uint8_t onlyShowActive,
    float targetX,
    float targetY
);

ASTRA_API void astra_keybinds_get_bounds(
    float* outX,
    float* outY,
    float* outWidth,
    float* outHeight
);

ASTRA_API void astra_keybinds_render(
    int32_t guiScaledWidth,
    int32_t guiScaledHeight,
    float guiScale,
    float deltaTime
);

// ============================================================================
// 2. MODUŁ APPLE LIQUID GLASS TITLESCREEN (SDF / UBO / OPENGL 3.3 CORE)
// ============================================================================

#pragma pack(push, 1)
struct AstraTitleScreenStatePOD {
    float screenWidth;
    float screenHeight;
    float mouseX;
    float mouseY;
    float totalTime;
    float deltaTime;

    // Animacje hover (0.0f - 1.0f)
    float spHover;
    float mpHover;
    float continueHover;
    float continuePlayHover;
    float sliderHover;
    float mediaPrevHover;
    float mediaPlayHover;
    float mediaNextHover;
    float profileHover;
    float dockHover[4];

    // Stan muzyki
    float musicProgress; // 0.0f - 1.0f
    uint8_t musicPlaying;
    int32_t currentTrack;

    // Statusy
    int32_t hoveredDockIndex; // -1 jeśli brak
    uint8_t profileMenuOpen;
};

struct AstraTitleScreenLayoutPOD {
    float spRect[4];        // x, y, w, h
    float mpRect[4];        // x, y, w, h
    float continueRect[4];  // x, y, w, h
    float musicRect[4];     // x, y, w, h
    float profileRect[4];   // x, y, w, h
    float dropdownRect[4];  // x, y, w, h
    float dockRect[4];      // x, y, w, h
    float tooltipRect[4];   // x, y, w, h
    float dockIconX[4];     // środki X czterech ikon w docku
    float statusDotX;       // środek X zielonej kropki "Online"
    float dotsCenterX;      // środek X przycisku "•••"
    float dotsHover;        // animacja hover dla "•••" (0.0f - 1.0f)
    float dropdownAnim;     // animacja otwarcia menu profilu (0.0f - 1.0f)
};

struct AstraVertex {
    float x;
    float y;
    float z;
    uint32_t color; // Format ABGR/ARGB zgodny z Blaze3D VertexConsumer
};

struct AstraSpruceInput {
    // Pozycja kamery (już zinterpolowana)
    double camX, camY, camZ;
    // Punkt startowy lasera (oczy gracza z korektą FOV)
    double laserStartX, laserStartY, laserStartZ;

    // Dane kolumny pnia 2x2
    int32_t hasTrunk;
    int32_t trunkMinX;
    int32_t trunkMinZ;
    int32_t baseSoilY;
    int32_t topLogY;

    // Dane aktualnie niszczonego bloku
    int32_t hasTarget;
    int32_t targetX;
    int32_t targetY;
    int32_t targetZ;
    int32_t targetType; // 0 = brak, 1 = LIŚĆ (Emerald), 2 = PIEŃ (Cyber Gold)
    float breakProgress; // 0.0f .. 1.0f

    // Stany 4 pól sadzenia (2x2: [0]=(0,0), [1]=(1,0), [2]=(0,1), [3]=(1,1))
    // 0 = puste/oczekuje, 1 = gotowe/posadzone (Emerald), 2 = pędzenie mączką (Gold)
    int32_t podStates[4];

    // Czas animacji w sekundach
    float timeSeconds;
};

struct AstraSpruceOutputCounts {
    int32_t solidQuadVertices;
    int32_t bloomQuadVertices;
    int32_t bloomLineVertices;
};

struct SpruceTierBox {
    int32_t minX;
    int32_t minY;
    int32_t minZ;
    int32_t maxX; // Granica górna bloku (np. blockX + 1)
    int32_t maxY;
    int32_t maxZ;
};

constexpr int32_t MAX_CANOPY_TIERS = 16;

struct AstraCyberEspInput {
    // Pozycja kamery w świecie (double eliminuje drgania precyzji na dużych koordynatach)
    double camX;
    double camY;
    double camZ;

    // Dane pnia 2x2
    int32_t hasTrunk;
    int32_t trunkMinX;
    int32_t trunkMinZ;
    int32_t baseSoilY;
    int32_t topLogY;

    // Dane schodkowej korony liści (Stepped Bounding Box)
    int32_t hasLeaves;
    int32_t tierCount; // 0 = automatyczny profil schodkowy 2x2; >0 = jawne segmenty ze skanera
    SpruceTierBox tiers[MAX_CANOPY_TIERS];

    // Czas animacji (sekundy)
    float timeSeconds;
};

struct AstraCyberEspCounts {
    int32_t solidQuadVertices;
    int32_t bloomQuadVertices;
    int32_t bloomLineVertices;
};
#pragma pack(pop)

static_assert(sizeof(AstraTitleScreenStatePOD) == 90, "AstraTitleScreenStatePOD must be exactly 90 bytes (packed)");

ASTRA_API bool astra_titlescreen_init();
ASTRA_API void astra_titlescreen_shutdown();
ASTRA_API void astra_titlescreen_set_layout(const AstraTitleScreenLayoutPOD* layout);
ASTRA_API void astra_titlescreen_render(
    const AstraTitleScreenStatePOD* state,
    uint32_t bgTextureId
);

ASTRA_API void astra_build_spruce_visuals(
    const AstraSpruceInput* input,
    AstraVertex* solidQuadBuf, int32_t maxSolidQuads,
    AstraVertex* bloomQuadBuf, int32_t maxBloomQuads,
    AstraVertex* bloomLineBuf, int32_t maxBloomLines,
    AstraSpruceOutputCounts* outCounts
);

#include <jni.h>

JNIEXPORT void JNICALL Java_com_astra_revolution_visuals_SpruceTreeVisuals_nativeBuildCyberEsp(
    JNIEnv* env,
    jclass clazz,
    jobject inputBuf,
    jobject solidQuadBuf, jint maxSolidQuadVerts,
    jobject bloomQuadBuf, jint maxBloomQuadVerts,
    jobject bloomLineBuf, jint maxBloomLineVerts,
    jobject outCountsBuf
);

#ifdef __cplusplus
}
#endif
