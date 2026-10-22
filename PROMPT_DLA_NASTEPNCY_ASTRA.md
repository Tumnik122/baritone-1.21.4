# MASTER BRIEFING: PROMPT DLA NASTĘPCY AI (ASTRA CLIENT & BARITONE 1.21.4)

> **Cześć Następco!**  
> Przejmujesz ten projekt po mnie. Abyś od pierwszej sekundy nie popełnił żadnego błędu, nie zepsuł visuali, nie dostał bana na GrimAC i nie wypychał kodu w złą gałąź na GitHubie — oto kompletne kompendium wiedzy o projekcie, architekturze, technologii i zasadach, które musisz bezwzględnie stosować.

---

## 1. Kontekst Projektu i Środowisko
- **Projekt:** **Astra Client / Baritone Revolution 1.21.4** (Prywatny, zaawansowany klient z autopilotem, schematic studiem i systemem visuali).
- **Minecraft:** `1.21.4` (Official Mojang Mappings / Mojmap).
- **Środowisko:** Java 21, Fabric Loom (Unimined 1.2.9), Blaze3D, JOML, OpenGL 3.3 Core Profile.
- **Sprzęt docelowy użytkownika:** NVIDIA GeForce RTX 4060 (8GB VRAM), Intel Core i5-12400F, 32GB RAM, ekran 1080p 144Hz–240Hz.
- **Antycheat docelowy:** **GrimAC** (najnowsze wersje z surową symulacją ticków 50ms, `PredictionEngine`, detekcją odchyleń GCD, raycastingu bloków i reachu).

---

## 2. ZASADA NUMER 1: Współpraca z modelem ARGON 4
Posiadasz dostęp do zewnętrznego super-modelu **ARGON 4 (Gemini Web z myśleniem: Wysoka dokładność)** poprzez serwer MCP `gemini-web-bridge` (`call_mcp_tool` -> `ask_gemini_web`).
- **Kiedy wywoływać:**
  1. Zawsze, gdy użytkownik wspomni: *"Argon"*, *"zapytaj Argona"*, *"niech Argon to zrobi"*.
  2. Zawsze przy trudnych zadaniach: matematyka 3D, krzywe parametryczne, fizyka cząsteczek, trigometria kątowa, omijanie detekcji GrimAC, złożony refaktoring czy błędy kompilacji Gradle.
- **Zasada:** Nigdy nie zgaduj skomplikowanych algorytmów na własną rękę — skonsultuj je z Argonem 4!

---

## 3. Repozytoria Git i Synchronizacja z GitHubem
Użytkownik posiada repozytorium na GitHubie, a lokalny katalog roboczy to:
`c:\Users\Administrator\Desktop\Gry i Mody\baritone-1.21.4\baritone-1.21.4`
- **Główne repozytorium podglądane w przeglądarce użytkownika:**  
  `https://github.com/Tumnik122/mc-laucher123.git`  
  Aktywna gałąź w przeglądarce: **`baritone-1.21.4-full-optimization-7949e`** oraz **`main`**.
- **Repozytorium zapasowe:**  
  `https://github.com/Tumnik122/baritone-1.21.4.git` (gałąź `main`).
- **ZASADA PUSHA:** Gdy użytkownik mówi *"zaktualizuj githuba"*, ZAWSZE wypychaj na obie gałęzie `mc-laucher123` oraz `origin`:
  ```powershell
  git push origin main
  git push mclaucher main:baritone-1.21.4-full-optimization-7949e
  git push mclaucher main:main
  ```
  Jeśli tego nie zrobisz, użytkownik odświeży stronę na GitHubie i powie, że nic nie zaktualizowałeś!

---

## 4. Zasady Renderowania i Visuali God-Tier (Zero Lag, Zero Glitch)

### A. Ochrona Stanu OpenGL (`AstraGlStateGuard`)
- Nigdy nie modyfikuj stanów OpenGL w pętli renderowania bez przywrócenia ich do stanu wyjściowego!
- Każdy blok renderowania 3D MUSI być opakowany w:
  ```java
  try (AstraGlStateGuard guard = new AstraGlStateGuard(false, false, true)) {
      // Twój kod renderujący...
  }
  ```
  `AstraGlStateGuard` automatycznie zabezpiecza `GL_DEPTH_TEST`, `GL_DEPTH_FUNC`, `depthMask`, `GL_CULL_FACE` oraz resetuje shadery Blaze3D. Bez tego zepsujesz ekrany GUI, czat lub wywołasz czarny ekran w grze.

### B. Bezwzględny Zakaz Alokacji Pamięci w Pętli Renderowania (Zero-Alloc)
- RTX 4060 generuje ponad 200 FPS. Jeśli w każdej klatce zrobisz `new Matrix4f()`, `new Vector3f()` albo `new Color()`, garbage collector zniszczy płynność mikro-zacięciami (GC stalls).
- **Zasada:** Zawsze używaj prealokowanych buforów statycznych (`static final Vector3f TMP_A = new Vector3f();`, `prevProj.set(...)`, reusable arrays).
- **Zakaz `GL11.glGetInteger()`:** Nigdy nie odpytuj GPU synchronicznie w klatce — wywołuje to GPU pipeline stall!

### C. Standardy Estetyki Visuali (Jak tworzyć efekty "WOW"):
1. **Multi-Shell Pseudo-Kawase Bloom:** Zamiast drogich buforów FBO, twórz 3 koncentryczne powłoki geometryczne w trybie `ADDITIVE_BLEND` (`SRC_ALPHA`, `ONE`) — daje efekt neonowego blasku z zerowym narzutem FPS.
2. **Krzywe Parametryczne:** Dla wstęg i laserów (`Motion Trails`) stosuj Centripetal Catmull-Rom Spline ($\alpha=0.5$) z sub-steppingiem 4 kroków na segment.
3. **Zaokrąglanie Ścieżek Beziera:** Ścieżki Baritone w `PathRenderer` nie mogą mieć ostrych kątów 90° — zakręty są zaokrąglane kwadratową krzywą Beziera $Q(u)$ z animowanymi szewronami prędkości (`> > >`).
4. **Fizyczna Dyspersja Cauchy'ego w Hand Shaderze:** Rozszczepienie pryzmatyczne światła na dłoni (odrębne potęgi Fresnela dla składowych RGB).
5. **Cząsteczki 3D Curl Noise:** Bezzbieżne pole prędkości ($\nabla \cdot \vec{v} = 0$) z kinetycznym polem odpychania o promieniu 2.2m przed biegnącym graczem.
6. **2D Screen Space:** Dynamiczne linie prędkości (*Kinetic Speed Warp Lines*) pojawiające się na obrzeżach ekranu przy sprincie oraz czerwono-fioletowa winieta przy otrzymaniu obrażeń (`player.hurtTime > 0`).

---

## 5. Płynność Ruchu i Omijanie Antycheatów (GrimAC Anti-Stall)

Gdy bot Baritone 1.21.4 czasami się zacina na krawędziach bloków lub zakrętach:
- **Przyczyna:** Smooth Aim interpoluje kąt kamery, a Baritone wciska samo `W` — gracz idzie po łuku (Arc Drift) i zahacza o róg bloku AABB.
- **Rozwiązanie wdrożone w `AstraMovementFluidityEngine.java`:**
  1. **8-Kierunkowy Kwantyzator (Silent Move Fix):** Co tick wybiera kombinację klawiszy `W/S/A/D`, która kieruje ruch dokładnie wzdłuż ścieżki świata, nawet gdy kamera płynnie się obraca.
  2. **Corner Feeler:** Sonda dwóch narożników AABB wykrywa kolizję i aplikuje 38° mikro-strafe omijający narożnik przez 150ms.
  3. **Early Ascend Assist:** Wymusza skok przy podejściu pod blok +1Y zanim pęd sprintu wyzeruje się o ścianę.
  4. Moduł jest wpięty w `PathExecutor.onTick()`.

---

## 6. Procedura Kompilacji i Budowania
W systemie Windows w terminalu PowerShell ZAWSZE używaj prefixu `.\`:
1. Szybka weryfikacja kompilacji:
   ```powershell
   .\gradlew.bat compileJava --offline
   ```
2. Pełne budowanie, obfuskacja ProGuard i determinizacja jarów:
   ```powershell
   .\gradlew.bat :fabric:build --offline -x test
   ```
3. Wygenerowane jary znajdują się w:
   `fabric/build/libs/baritone-standalone-fabric-1.13.1.jar` oraz `baritone-fabric-1.13.1.jar`.

---

## 7. Podsumowanie dla Ciebie
- Szanuj pracę wykonaną w architekturze: kod jest modularny, ultraczysty i przetestowany.
- Użytkownik ceni jakość visuali, płynność działania bota oraz natychmiastowe aktualizacje na GitHubie.
- Gdy użytkownik prosi o kolejną funkcję lub visual — zrób to najlepiej na świecie, skonsultuj z Argonem 4, zbuduj jary i zaktualizuj GitHuba! Powodzenia! ⚡
