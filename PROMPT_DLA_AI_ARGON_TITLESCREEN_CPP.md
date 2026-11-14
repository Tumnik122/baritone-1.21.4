# 🚀 GRAND MASTER PROMPT DLA AI: ARGON 4 (GEMINI WEB / HIGH REASONING)
# IMPLEMENTACJA C++20 + OPENGL 3.3 CORE SHADERÓW DLA APPLE LIQUID GLASS TITLESCREEN
# Środowisko: Minecraft 1.21.4 (Fabric) / Java 21 Panama FFM / MSVC C++20 / native-core

---

### INSTRUKCJA DLA UŻYTKOWNIKA (dj):
> Skopiuj całą poniższą treść (od `=== POCZĄTEK PROMPTU DLA ARGONA 4 ===` do `=== KONIEC PROMPTU DLA ARGONA 4 ===`) i wklej do okna czatu z **Argonem 4 (Gemini Advanced/Wysoka)** lub uruchom w Brave z portem 9222.
> Prompt zawiera kompletną specyfikację techniczną, układ pamięci C++, shadery GLSL SDF z fizyczną refrakcją Liquid Glass, bezpieczną integrację z Java 21 Panama FFM oraz ochronę stanu OpenGL Blaze3D.

---

```markdown
=== POCZĄTEK PROMPTU DLA ARGONA 4 ===

Jesteś Głównym Inżynierem Grafiki OpenGL 3.3 Core, Architektem Natywnego Silnika C++20 (`native-core`) oraz Twórcą Systemów GUI dla klienta Astra Client / Baritone (Minecraft Fabric 1.21.4, Java 21 Mojmap).

### CEL ZADANIA:
Przenieść renderowanie elementów GUI ekranu głównego (`ModernAestheticTitleScreen`) z wolnego, rastrowego rysowania 2D na **sprzętowo akcelerowany, natywny potok C++20 z shaderami GLSL SDF (Signed Distance Fields) i materiałem Apple Liquid Glass (iOS Lockscreen / macOS Glassmorphism)** w `native-core/astra_core.cpp` i `native-core/astra_core.h`, wywoływany przez Java 21 Foreign Function & Memory API (Panama FFM).

---

## 1. ARCHITEKTURA I ŚRODOWISKO PROJEKTU

1. **Język i kompilator:** C++20 (MSVC x64), linkowane dynamicznie jako `astra_core.dll` w katalogu `native-core/`.
2. **Mostek Java ↔ C++:** Java 21 Panama FFM (`java.lang.foreign.Linker`, `SymbolLookup`, `MemorySegment`, `FunctionDescriptor`). Brak narzutu JNI, 100% Zero-Allocation w pętli renderowania klatki.
3. **Kontekst graficzny:** Aktualny kontekst OpenGL 3.3 Core tworzony przez GLFW / Minecrafta. Wszystkie wskaźniki funkcji (`glCreateShader`, `glUseProgram`, `glBindVertexArray`, etc.) są pobierane dynamicznie przez `wglGetProcAddress` (jak w istniejącym `astra_core.cpp`).
4. **Ochrona stanu Blaze3D (`ScopedGlStateGuard`):** Każde wywołanie funkcji renderującej MUSI izolować i przywracać:
   - `GL_CURRENT_PROGRAM`, `GL_VERTEX_ARRAY_BINDING`, `GL_ARRAY_BUFFER_BINDING`
   - `GL_TEXTURE_BINDING_2D`, `GL_ACTIVE_TEXTURE`
   - `GL_BLEND`, `GL_BLEND_SRC_RGB`, `GL_BLEND_DST_RGB`, `GL_BLEND_SRC_ALPHA`, `GL_BLEND_DST_ALPHA`
   - `GL_DEPTH_TEST`, `GL_CULL_FACE`, `GL_SCISSOR_TEST`, `glViewport`, `glScissor`
   - Wywołanie `BufferUploader.reset()` i `invalidate()` po stronie Javy, aby zsynchronizować cache Blaze3D.

---

## 2. DOKŁADNA SPECYFIKACJA WIZUALNA (APPLE LIQUID GLASS / iOS LOCKSCREEN)

Zaprojektuj i zaimplementuj shader fragmentów GLSL (`LIQUID_GLASS_TITLESCREEN_FS`) oraz geometrię wierzchołków dla następujących komponentów (na wzór lockscreena iOS):

### A. MATERIAŁ „LIQUID GLASS” (FIZYCZNE SZKŁO KROPLOWE):
- **Pole odległości SDF (`sdRoundedBox4`):** Matematycznie idealne krzywizny rogów z antyaliasingiem subpikselowym (`smoothstep(-0.75, 0.75, d)`).
- **Normalne powierzchni i soczewkowanie (Dome Curvature):**
  - Wektor normalny wyliczany z gradientu SDF wzdłuż krawędzi karty: $\vec{N} = \text{normalize}(\nabla d \cdot \text{curve}^{1.8}, 1.0)$.
  - Efekt soczewkowy zaginający tło przy obrzeżach karty (refrakcja optyczna: $\vec{uv} + \vec{N}_{xy} \cdot \text{offset}$).
- **Rozmycie matowe (Frosted Gaussian/Poisson Blur):** 12-punktowy zoptymalizowany kernel próbkujący teksturę tła z subtelną dyspersją chromatyczną (oddzielne offsety dla kanałów R, G, B symulujące pryzmatyczne załamanie światła na szkle).
- **Światło kierunkowe i połysk krawędzi (Specular Top Rim):**
  - Górny refleks świetlny: 1-pikselowa półprzezroczysta linia na górnym menisku szkła (`0x4DFFFFFF`).
  - Kątowe odbicie światła (`specular = pow(dot(N, L), 12.0)`).
- **Głęboki cień kroplowy (Hermite Soft Drop Shadow):** Płynny zanikający cień pod kartami bez widocznych schodków (`exp(-max(d, 0.0) * 0.2)`).

### B. KOMPONENTY EKRANU:
1. **Pigułki trybów Singleplayer & Multiplayer:**
   - Dwa zaokrąglone przyciski obok siebie ($W \approx 135\text{px}, H \approx 32\text{px}$).
   - Wektorowe ikony SDF: pojedynczy użytkownik (Singleplayer) oraz grupa dwóch postaci (Multiplayer).
   - Animacja najazdu: uniesienie o $-3\text{px}$, powiększenie o 2%, rozjaśnienie menisku szkła i lawendowo-błękitny subtelny glow (`0x5538BDF8`).
2. **Karta „Continue” (Szybkie Dołączenie do Ostatniego Serwera):**
   - Karta o wymiarach $W \approx 280\text{px}, H \approx 48\text{px}$.
   - Złoty monogram z zaokrąglonymi rogami (`[ CP ]` dla clearmc.pl) z gradientem bursztynowo-złotym.
   - Napisy: mały subtelny napis "Continue • 5 days ago" oraz wyraźny biały "clearmc.pl".
   - Okrągły przycisk Play (`[ ▶ ]`) po prawej stronie z dynamicznym rozbłyskiem przy hoverze.
3. **Odtwarzacz Muzyki w stylu iOS (Now Playing Widget):**
   - Karta $W \approx 280\text{px}, H \approx 72\text{px}$.
   - Miniaturka okładki albumu (zaokrąglony kwadrat z gradientem zmierzchu i symbolem płyty).
   - Tytuł utworu ("The First War") oraz wykonawca ("Unstable SMP").
   - Interaktywny suwak postępu z zaokrąglonym białym wypełnieniem i główką (thumb) powiększającą się przy najechaniu.
   - Wektorowe kontrolki SDF: Poprzedni utwór (`|◀`), Play/Pause (`▶` / `⏸`), Następny utwór (`▶|`).
4. **Dolny Pływający Dock:**
   - Kapsuła o kształcie pigułki (promień $R = H/2$) wyśrodkowana na dole ekranu.
   - 4 wektorowe ikony SDF:
     - ⚙ Ustawienia (suwaki / zębatka)
     - ✏ Personalizacja (ołówek)
     - 👤 Baritone / Profil
     - ✕ Wyjście z gry (akcent czerwony przy hoverze)
   - Pływające dymki z podpowiedziami (Tooltips) unoszące się płynnie nad aktywną ikoną.
5. **Górny Pasek Profilu (Top-Right):**
   - Pigułka ze statusem: głowa gracza, nick, pulsująca szmaragdowa dioda "• Online" oraz menu "•••".

---

## 3. WYMAGANIA IMPLEMENTACYJNE W KODZIE C++ (`astra_core.cpp` i `astra_core.h`)

1. **Definicje struktur C++ i API eksportowanego (`extern "C"`):**
```cpp
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
#pragma pack(pop)

ASTRA_API bool astra_titlescreen_init();
ASTRA_API void astra_titlescreen_shutdown();
ASTRA_API void astra_titlescreen_render(
    const AstraTitleScreenStatePOD* state,
    uint32_t bgTextureId // Tekstura czystego tła przekazana z Minecrafta
);
```

2. **Potok Shaderów GLSL w C++:**
   - Skompiluj dedykowany program shaderów z wbudowanym kodem źródłowym GLSL (`FULLSCREEN_QUAD_VS` + `LIQUID_GLASS_FS`).
   - Przekaż parametry kart i przycisków przez `Uniform Buffer Object (UBO)` lub jednolite wektory `uniform vec4`.
   - Zapewnij pełny fallback: jeśli sterownik nie obsługuje określonego rozszerzenia, kod bezpiecznie renderuje gładkie gradienty SDF bez crashowania gry.

3. **Czystość kodu i standardy:**
   - Zero alokacji sterty (`new` / `malloc` / `std::vector::push_back`) wewnątrz metody `astra_titlescreen_render`. Wszystkie bufory wierzchołków i macierze muszą być statyczne lub prealokowane.
   - Pełna obsługa Windows x64 (MSVC), zgodność z ISO C++20.
   - Dołącz pełny kod `astra_core.h` i `astra_core.cpp` (lub dedykowaną sekcję do wklejenia) oraz kod klasy Java 21 ładującej bibliotekę przez `Linker.nativeLinker()`.

Napisz kompletną, gotową do skompilowania implementację!
=== KONIEC PROMPTU DLA ARGONA 4 ===
```

