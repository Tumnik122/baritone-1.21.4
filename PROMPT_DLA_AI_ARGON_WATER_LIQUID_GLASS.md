# PROMPT DLA AI ARGON 4: Wypukły Menisk Wodny (Fluid Liquid Glass), Detekcja Spotify & Dynamiczny Auto-Hide

Skopiuj i wklej poniższy prompt do czatu z modelem **Argon 4** (karta w przeglądarce Brave / `gemini-web-bridge`):

---

```markdown
Jesteś elitarnym inżynierem grafiki komputerowej (OpenGL 3.3+ Core / C++20 / GLSL) oraz architektem JVM (Java 21 / Fabric 1.21.4 / Blaze3D).

Pracujemy nad projektem ekranu głównego (ModernAestheticTitleScreen) dla klienta Astra Client 1.21.4. Użytkownik ocenił dotychczasowy styl Liquid Glass na 8/10, wskazując następujące wymagania i ulepszenia fizyczne:
1. EFEKT PRAWDZIWEJ WODY (CONVEX WATER DROPLET MENISCUS):
   - Dotychczasowy bevel był zbyt płaski (szklana faza).
   - Użytkownik oczekuje efektu wypukłej kropli wody rozlanej na ekranie – ramki kart muszą mieć wyraźny, soczewkowy profil menisku opartego na napięciu powierzchniowym cieczy (Laplace-Young surface tension).
   - Światło musi załamywać się na brzegach jak w grubej soczewce wody (mocne zagięcie tła pod krawędzią), a na wierzchołkach menisku muszą pojawiać się dwa refleksy specular: główny księżycowy błysk na szczycie oraz wewnętrzne odbicie kaustyczne (caustic back-bounce) przy wewnętrznej wardze kropli.
   - Dyspersja chromatyczna (rozszczepienie tęczowe / aberracja Cauchy'ego) musi być skoncentrowana dokładnie w pasie największej krzywizny menisku.

2. DYNAMICZNA DETEKCJA SPOTIFY (WINDOWS WIN32) & AUTO-HIDE:
   - Zaimplementuj natywną detekcję odtwarzacza Spotify na Windows bez zewnętrznych bibliotek i bez alokacji pamięci na stercie w pętli renderowania.
   - Detekcja przez Win32 API: `EnumWindows` i filtrowanie po klasie okna `Chrome_WidgetWin_0` oraz procesie `Spotify.exe`.
   - Analiza tytułu okna: stany bezczynności ("Spotify", "Spotify Free", "Spotify Premium") oznaczają brak muzyki (`spotifyActive = false`). Gdy tytuł ma postać "Artysta - Tytuł", wyodrębnij artystę i tytuł utworu.
   - ZASADA ABSOLUTNA: Gdy użytkownik NIE słucha muzyki na Spotify, widget odtwarzacza MUSI CAŁKOWICIE ZNIKNĄĆ Z EKRANU (Auto-Hide). Prostokąt `uMusicRect` w shaderze oraz w układzie Javy ma zapadać się do 0 (`musicH = 0`, `musicW = 0`), a shader GLSL ma bezwarunkowo pomijać renderowanie odtwarzacza muzycznego. Gdy muzyka gra, karta płynnie się pojawia i wyświetla prawdziwe metadane ze Spotify.

3. INTEGRACJA SERWERA ANARCHIA.GG:
   - Zmień domyślny kafelek "Continue" z `clearmc.pl` na oficjalny serwer `anarchia.gg`.
   - Pobierz i wyświetl prawdziwą ikonę serwera Anarchia.gg w odznace kafelka z fallbackiem wektorowym.
   - Kliknięcie w przycisk natychmiast łączy z adresem `anarchia.gg`.

4. PERFEKCYJNE TŁO BEZ SZWÓW I PLAM:
   - Zrekonstruuj teksturę tła zamku i jeziora (`title_bg.png`) tak, aby usunąć wszelkie kwadratowe plamy po inpaintingu i szwy gradientu na niebie i wodzie (zapewnij gładkie przejście smoothstep w przestrzeni kolorów sRGB).

Przeanalizuj poniższe wzory matematyczne i zoptymalizuj potok GLSL:

A. Równanie Profilu Menisku Wodnego (Convex Water Dome):
Dla odległości $d$ od krawędzi zaokrąglonego prostokąta (SDF $d \le 0$) o szerokości menisku $W_{\text{bevel}} = 18.0\text{px}$:
$$t = \text{clamp}\left(-\frac{d}{W_{\text{bevel}}}, 0.0, 1.0\right)$$
Zamiast funkcji potęgowej $t^\gamma$, zastosuj sferyczny łuk menisku cieczy:
$$\text{dome}(t) = \sqrt{\max\left(0.001, 1.0 - (1.0 - t)^2\right)}$$
$$\text{slope}(t) = \frac{1.0 - t}{\max(0.06, \text{dome}(t))}$$
Wypadkowy wektor normalny powierzchni soczewki cieczy:
$$\vec{N} = \text{normalize}\left(\begin{bmatrix} \nabla \text{SDF}(p) \cdot \text{slope}(t) \cdot S_{\text{norm}} \\ 1.0 \end{bmatrix}\right)$$

B. Dyspersja Chromatyczna i Załamanie (Snell-Cauchy Refraction):
$$\Delta \vec{uv} = \vec{N}_{xy} \cdot K_{\text{refr}} \cdot \text{dome}(t)$$
$$\vec{uv}_R = \vec{uv} + \Delta \vec{uv} \cdot (1.0 + \delta)$$
$$\vec{uv}_G = \vec{uv} + \Delta \vec{uv}$$
$$\vec{uv}_B = \vec{uv} + \Delta \vec{uv} \cdot (1.0 - \delta)$$
Gdzie $\delta = 0.50 \cdot (1.0 - t)$ koncentruje rozszczepienie pryzmatyczne wyłącznie na obwodzie menisku.

C. Podwójny Refleks Specular:
1. Główny błysk światła (wierzchołek menisku):
   $$S_1 = \left(\max(\vec{N} \cdot \vec{L}_1, 0.0)\right)^{16} \cdot (1.0 - t)$$
2. Odbicie kaustyczne (wewnętrzna krawędź):
   $$S_2 = \left(\max(\vec{N} \cdot \vec{L}_2, 0.0)\right)^6 \cdot \sin(\pi \cdot t) \cdot 0.35$$

Napisz kompletną implementację lub wskaż ewentualne poprawki matematyczne dla `evalLiquidGlassCard` w `native-core/astra_core.cpp` oraz integracji z `ModernAestheticTitleScreen.java`.
```

---

