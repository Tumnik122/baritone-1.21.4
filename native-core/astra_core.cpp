#define ASTRA_CORE_EXPORTS
#define NOMINMAX
#define WIN32_LEAN_AND_MEAN

#include "astra_core.h"

#include <windows.h>
#include <GL/gl.h>

#include <algorithm>
#include <array>
#include <cmath>
#include <cstddef>
#include <cstdio>
#include <cstring>
#include <mutex>
#include <vector>

// Automatyczne linkowanie bibliotek systemowych w MSVC (działa nawet przy prostym build-native.bat)
#if defined(_MSC_VER)
    #pragma comment(lib, "opengl32.lib")
    #pragma comment(lib, "gdi32.lib")
    #pragma comment(lib, "user32.lib")
#endif

namespace {

// ============================================================================
// 1. DEFINICJE OPENGL 3.3 CORE (BRAK ZALEŻNOŚCI OD GLEW/GLAD)
// ============================================================================
using GLchar     = char;
using GLsizeiptr = ptrdiff_t;
using GLintptr   = ptrdiff_t;

#ifndef GL_ARRAY_BUFFER
constexpr GLenum GL_ARRAY_BUFFER              = 0x8892;
#endif
#ifndef GL_DYNAMIC_DRAW
constexpr GLenum GL_DYNAMIC_DRAW              = 0x88E8;
#endif
#ifndef GL_FRAGMENT_SHADER
constexpr GLenum GL_FRAGMENT_SHADER           = 0x8B30;
#endif
#ifndef GL_VERTEX_SHADER
constexpr GLenum GL_VERTEX_SHADER             = 0x8B31;
#endif
#ifndef GL_COMPILE_STATUS
constexpr GLenum GL_COMPILE_STATUS            = 0x8B81;
#endif
#ifndef GL_LINK_STATUS
constexpr GLenum GL_LINK_STATUS               = 0x8B82;
#endif
#ifndef GL_CURRENT_PROGRAM
constexpr GLenum GL_CURRENT_PROGRAM           = 0x8B8D;
#endif
#ifndef GL_VERTEX_ARRAY_BINDING
constexpr GLenum GL_VERTEX_ARRAY_BINDING      = 0x85B5;
#endif
#ifndef GL_ARRAY_BUFFER_BINDING
constexpr GLenum GL_ARRAY_BUFFER_BINDING      = 0x8894;
#endif
#ifndef GL_ACTIVE_TEXTURE
constexpr GLenum GL_ACTIVE_TEXTURE            = 0x84E0;
#endif
#ifndef GL_TEXTURE0
constexpr GLenum GL_TEXTURE0                  = 0x84C0;
#endif
#ifndef GL_CLAMP_TO_EDGE
constexpr GLenum GL_CLAMP_TO_EDGE             = 0x812F;
#endif
#ifndef GL_R8
constexpr GLenum GL_R8                        = 0x8229;
#endif
#ifndef GL_RED
constexpr GLenum GL_RED                       = 0x1903;
#endif
#ifndef GL_BLEND_SRC_RGB
constexpr GLenum GL_BLEND_SRC_RGB             = 0x80C9;
#endif
#ifndef GL_BLEND_DST_RGB
constexpr GLenum GL_BLEND_DST_RGB             = 0x80C8;
#endif
#ifndef GL_BLEND_SRC_ALPHA
constexpr GLenum GL_BLEND_SRC_ALPHA           = 0x80CB;
#endif
#ifndef GL_BLEND_DST_ALPHA
constexpr GLenum GL_BLEND_DST_ALPHA           = 0x80CA;
#endif

using PFNGLCREATESHADERPROC           = GLuint (APIENTRY*)(GLenum);
using PFNGLSHADERSOURCEPROC           = void   (APIENTRY*)(GLuint, GLsizei, const GLchar* const*, const GLint*);
using PFNGLCOMPILESHADERPROC          = void   (APIENTRY*)(GLuint);
using PFNGLGETSHADERIVPROC            = void   (APIENTRY*)(GLuint, GLenum, GLint*);
using PFNGLDELETESHADERPROC           = void   (APIENTRY*)(GLuint);
using PFNGLCREATEPROGRAMPROC          = GLuint (APIENTRY*)(void);
using PFNGLATTACHSHADERPROC           = void   (APIENTRY*)(GLuint, GLuint);
using PFNGLLINKPROGRAMPROC            = void   (APIENTRY*)(GLuint);
using PFNGLGETPROGRAMIVPROC           = void   (APIENTRY*)(GLuint, GLenum, GLint*);
using PFNGLUSEPROGRAMPROC             = void   (APIENTRY*)(GLuint);
using PFNGLDELETEPROGRAMPROC          = void   (APIENTRY*)(GLuint);
using PFNGLGETUNIFORMLOCATIONPROC     = GLint  (APIENTRY*)(GLuint, const GLchar*);
using PFNGLUNIFORM1IPROC              = void   (APIENTRY*)(GLint, GLint);
using PFNGLUNIFORM1FPROC              = void   (APIENTRY*)(GLint, GLfloat);
using PFNGLUNIFORM2FPROC              = void   (APIENTRY*)(GLint, GLfloat, GLfloat);
using PFNGLUNIFORM4FPROC              = void   (APIENTRY*)(GLint, GLfloat, GLfloat, GLfloat, GLfloat);
using PFNGLUNIFORMMATRIX4FVPROC       = void   (APIENTRY*)(GLint, GLsizei, GLboolean, const GLfloat*);
using PFNGLGENVERTEXARRAYSPROC        = void   (APIENTRY*)(GLsizei, GLuint*);
using PFNGLBINDVERTEXARRAYPROC        = void   (APIENTRY*)(GLuint);
using PFNGLDELETEVERTEXARRAYSPROC     = void   (APIENTRY*)(GLsizei, const GLuint*);
using PFNGLGENBUFFERSPROC             = void   (APIENTRY*)(GLsizei, GLuint*);
using PFNGLBINDBUFFERPROC             = void   (APIENTRY*)(GLenum, GLuint);
using PFNGLBUFFERDATAPROC             = void   (APIENTRY*)(GLenum, GLsizeiptr, const void*, GLenum);
using PFNGLBUFFERSUBDATAPROC          = void   (APIENTRY*)(GLenum, GLintptr, GLsizeiptr, const void*);
using PFNGLDELETEBUFFERSPROC          = void   (APIENTRY*)(GLsizei, const GLuint*);
using PFNGLENABLEVERTEXATTRIBARRAYPROC= void   (APIENTRY*)(GLuint);
using PFNGLVERTEXATTRIBPOINTERPROC    = void   (APIENTRY*)(GLuint, GLint, GLenum, GLboolean, GLsizei, const void*);
using PFNGLACTIVETEXTUREPROC          = void   (APIENTRY*)(GLenum);
using PFNGLBLENDFUNCSEPARATEPROC      = void   (APIENTRY*)(GLenum, GLenum, GLenum, GLenum);

struct GlFunctions {
    PFNGLCREATESHADERPROC            glCreateShader            = nullptr;
    PFNGLSHADERSOURCEPROC            glShaderSource            = nullptr;
    PFNGLCOMPILESHADERPROC           glCompileShader           = nullptr;
    PFNGLGETSHADERIVPROC             glGetShaderiv             = nullptr;
    PFNGLDELETESHADERPROC            glDeleteShader            = nullptr;
    PFNGLCREATEPROGRAMPROC           glCreateProgram           = nullptr;
    PFNGLATTACHSHADERPROC            glAttachShader            = nullptr;
    PFNGLLINKPROGRAMPROC             glLinkProgram             = nullptr;
    PFNGLGETPROGRAMIVPROC            glGetProgramiv            = nullptr;
    PFNGLUSEPROGRAMPROC              glUseProgram              = nullptr;
    PFNGLDELETEPROGRAMPROC           glDeleteProgram           = nullptr;
    PFNGLGETUNIFORMLOCATIONPROC      glGetUniformLocation      = nullptr;
    PFNGLUNIFORM1IPROC               glUniform1i               = nullptr;
    PFNGLUNIFORM1FPROC               glUniform1f               = nullptr;
    PFNGLUNIFORM2FPROC               glUniform2f               = nullptr;
    PFNGLUNIFORM4FPROC               glUniform4f               = nullptr;
    PFNGLUNIFORMMATRIX4FVPROC        glUniformMatrix4fv        = nullptr;
    PFNGLGENVERTEXARRAYSPROC         glGenVertexArrays         = nullptr;
    PFNGLBINDVERTEXARRAYPROC         glBindVertexArray         = nullptr;
    PFNGLDELETEVERTEXARRAYSPROC      glDeleteVertexArrays      = nullptr;
    PFNGLGENBUFFERSPROC              glGenBuffers              = nullptr;
    PFNGLBINDBUFFERPROC              glBindBuffer              = nullptr;
    PFNGLBUFFERDATAPROC              glBufferData              = nullptr;
    PFNGLBUFFERSUBDATAPROC           glBufferSubData           = nullptr;
    PFNGLDELETEBUFFERSPROC           glDeleteBuffers           = nullptr;
    PFNGLENABLEVERTEXATTRIBARRAYPROC glEnableVertexAttribArray = nullptr;
    PFNGLVERTEXATTRIBPOINTERPROC     glVertexAttribPointer     = nullptr;
    PFNGLACTIVETEXTUREPROC           glActiveTexture           = nullptr;
    PFNGLBLENDFUNCSEPARATEPROC       glBlendFuncSeparate       = nullptr;

    template <typename T>
    static bool loadProc(HMODULE oglMod, T& outFn, const char* name) {
        PROC p = wglGetProcAddress(name);
        if (!p || p == reinterpret_cast<PROC>(0x1) || p == reinterpret_cast<PROC>(0x2) ||
            p == reinterpret_cast<PROC>(0x3) || p == reinterpret_cast<PROC>(-1)) {
            p = GetProcAddress(oglMod, name);
        }
        outFn = reinterpret_cast<T>(p);
        return outFn != nullptr;
    }

    bool loadAll() {
        HMODULE oglMod = GetModuleHandleW(L"opengl32.dll");
        if (!oglMod) {
            oglMod = LoadLibraryW(L"opengl32.dll");
        }
        if (!oglMod) return false;

        return loadProc(oglMod, glCreateShader,            "glCreateShader")
            && loadProc(oglMod, glShaderSource,            "glShaderSource")
            && loadProc(oglMod, glCompileShader,           "glCompileShader")
            && loadProc(oglMod, glGetShaderiv,             "glGetShaderiv")
            && loadProc(oglMod, glDeleteShader,            "glDeleteShader")
            && loadProc(oglMod, glCreateProgram,           "glCreateProgram")
            && loadProc(oglMod, glAttachShader,            "glAttachShader")
            && loadProc(oglMod, glLinkProgram,             "glLinkProgram")
            && loadProc(oglMod, glGetProgramiv,            "glGetProgramiv")
            && loadProc(oglMod, glUseProgram,              "glUseProgram")
            && loadProc(oglMod, glDeleteProgram,           "glDeleteProgram")
            && loadProc(oglMod, glGetUniformLocation,      "glGetUniformLocation")
            && loadProc(oglMod, glUniform1i,               "glUniform1i")
            && loadProc(oglMod, glUniform1f,               "glUniform1f")
            && loadProc(oglMod, glUniform2f,               "glUniform2f")
            && loadProc(oglMod, glUniform4f,               "glUniform4f")
            && loadProc(oglMod, glUniformMatrix4fv,        "glUniformMatrix4fv")
            && loadProc(oglMod, glGenVertexArrays,         "glGenVertexArrays")
            && loadProc(oglMod, glBindVertexArray,         "glBindVertexArray")
            && loadProc(oglMod, glDeleteVertexArrays,      "glDeleteVertexArrays")
            && loadProc(oglMod, glGenBuffers,              "glGenBuffers")
            && loadProc(oglMod, glBindBuffer,              "glBindBuffer")
            && loadProc(oglMod, glBufferData,              "glBufferData")
            && loadProc(oglMod, glBufferSubData,           "glBufferSubData")
            && loadProc(oglMod, glDeleteBuffers,           "glDeleteBuffers")
            && loadProc(oglMod, glEnableVertexAttribArray, "glEnableVertexAttribArray")
            && loadProc(oglMod, glVertexAttribPointer,     "glVertexAttribPointer")
            && loadProc(oglMod, glActiveTexture,           "glActiveTexture")
            && loadProc(oglMod, glBlendFuncSeparate,       "glBlendFuncSeparate");
    }
};

// ============================================================================
// 2. SHADERY GLSL 3.30 CORE (UBER-SDF: OBSIDIAN GLASS, GLOW, GRADIENT, GLYPHS)
// ============================================================================
constexpr const char* VERTEX_SHADER_SRC = R"GLSL(
#version 330 core
layout (location = 0) in vec2 aPos;
layout (location = 1) in vec2 aLocalPos;
layout (location = 2) in vec2 aUV;

uniform mat4 uProj;

out vec2 vLocalPos;
out vec2 vUV;

void main() {
    gl_Position = uProj * vec4(aPos, 0.0, 1.0);
    vLocalPos = aLocalPos;
    vUV = aUV;
}
)GLSL";

constexpr const char* FRAGMENT_SHADER_SRC = R"GLSL(
#version 330 core
in vec2 vLocalPos;
in vec2 vUV;

uniform int   uMode;        // 0 = SDF Box+Border, 1 = Soft Drop Shadow/Glow, 2 = Gradient Line, 3 = Font Glyph
uniform vec2  uSize;        // Szerokość i wysokość elementu w pikselach logicznych
uniform float uRadius;      // Promień zaokrąglenia narożników
uniform float uBorderWidth; // Grubość ramki
uniform float uSpread;      // Rozmycie cienia / poświaty
uniform vec4  uFillColor;   // Kolor główny RGBA
uniform vec4  uFillColor2;  // Kolor dolny gradientu RGBA
uniform vec4  uBorderColor; // Kolor obramowania RGBA
uniform sampler2D uFontTex;

out vec4 FragColor;

float sdRoundBox(vec2 p, vec2 b, float r) {
    vec2 q = abs(p) - b + r;
    return min(max(q.x, q.y), 0.0) + length(max(q, 0.0)) - r;
}

void main() {
    if (uMode == 0) {
        // Zaokrąglony prostokąt SDF z gradientowym wypełnieniem, górnym połyskiem szkła i ramką
        vec2 halfSize = uSize * 0.5;
        vec2 p = vLocalPos - halfSize;
        float r = min(uRadius, min(halfSize.x, halfSize.y));
        float dist = sdRoundBox(p, halfSize, r);

        // Antyaliasing krawędzi 1.0 px
        float outerAlpha = 1.0 - smoothstep(-0.75, 0.75, dist);
        if (outerAlpha <= 0.001) discard;

        float tY = clamp(vLocalPos.y / max(uSize.y, 1.0), 0.0, 1.0);
        vec4 baseFill = mix(uFillColor, uFillColor2, tY);

        // Subtelny szklany refleks na górnej krawędzi (Obsidian & Lilac Glass)
        float topSheen = exp(-vLocalPos.y * 0.28) * 0.045;
        baseFill.rgb += vec3(0.77, 0.71, 0.99) * topSheen;

        // Maska ramki wewnętrznej
        float borderMask = 0.0;
        if (uBorderWidth > 0.0) {
            float innerDist = dist + uBorderWidth;
            borderMask = smoothstep(-0.75, 0.75, innerDist);
        }

        vec4 color = mix(baseFill, uBorderColor, borderMask * uBorderColor.a);
        color.a = mix(baseFill.a, max(baseFill.a, uBorderColor.a), borderMask) * outerAlpha;
        FragColor = color;
    }
    else if (uMode == 1) {
        // Miękki cień (Drop Shadow) lub neonowa poświata (Bloom) wokół kształtu SDF
        vec2 pad = vec2(uSpread);
        vec2 innerSize = max(uSize - pad * 2.0, vec2(1.0));
        vec2 halfSize = innerSize * 0.5;
        vec2 p = (vLocalPos - pad) - halfSize;
        float r = min(uRadius, min(halfSize.x, halfSize.y));
        float dist = sdRoundBox(p, halfSize, r);

        float norm = clamp(dist / max(uSpread, 0.001), 0.0, 1.0);
        float falloff = pow(1.0 - norm, 2.35);
        if (falloff <= 0.001) discard;

        FragColor = vec4(uFillColor.rgb, uFillColor.a * falloff);
    }
    else if (uMode == 2) {
        // Cienka linia z płynnym zanikaniem gradientowym na końcach
        float edgeFade = sin(clamp(vUV.x, 0.0, 1.0) * 3.14159265);
        edgeFade = pow(max(edgeFade, 0.0), 0.75);
        FragColor = vec4(uFillColor.rgb, uFillColor.a * edgeFade);
    }
    else {
        // Wygładzony glif czcionki z atlasu GDI TrueType
        float alpha = texture(uFontTex, vUV).r;
        if (alpha <= 0.01) discard;
        FragColor = vec4(uFillColor.rgb, uFillColor.a * alpha);
    }
}
)GLSL";

// ============================================================================
// 3. PALETA KOLORÓW GRIM CLIENT (OBSIDIAN & LILAC GLASS) I STRUKTURY DANYCH
// ============================================================================
struct Color4 {
    float r, g, b, a;

    static constexpr Color4 fromHex(uint32_t argb) {
        return Color4{
            static_cast<float>((argb >> 16) & 0xFF) / 255.0f,
            static_cast<float>((argb >> 8)  & 0xFF) / 255.0f,
            static_cast<float>( argb        & 0xFF) / 255.0f,
            static_cast<float>((argb >> 24) & 0xFF) / 255.0f
        };
    }

    static constexpr Color4 lerp(const Color4& c1, const Color4& c2, float t) {
        const float k = std::clamp(t, 0.0f, 1.0f);
        return Color4{
            c1.r + (c2.r - c1.r) * k,
            c1.g + (c2.g - c1.g) * k,
            c1.b + (c2.b - c1.b) * k,
            c1.a + (c2.a - c1.a) * k
        };
    }

    constexpr Color4 withAlpha(float newAlpha) const {
        return Color4{ r, g, b, newAlpha };
    }
};

// Główne tokeny stylistyczne (Grim Client / Obsidian & Lilac Glass)
constexpr Color4 COL_BG_TOP          = Color4::fromHex(0xDB0D0B18); // #0D0B18 (~86% alpha)
constexpr Color4 COL_BG_BOT          = Color4::fromHex(0xD4080710); // Głęboki obsydian u dołu (~83% alpha)
constexpr Color4 COL_HEADER_TOP      = Color4::fromHex(0xEE141124); // Jaśniejszy szklany nagłówek
constexpr Color4 COL_HEADER_BOT      = Color4::fromHex(0xE60F0D1C);
constexpr Color4 COL_BORDER_LILAC    = Color4::fromHex(0x3AC4B5FD); // Pastelowa lawenda #C4B5FD (niska alfa)
constexpr Color4 COL_ACCENT_LILAC    = Color4::fromHex(0xFFC4B5FD); // Neonowa pastelowa lawenda
constexpr Color4 COL_TEXT_WHITE      = Color4::fromHex(0xFFF5F3FF); // Czysta biel aktywnych modułów
constexpr Color4 COL_TEXT_DIM        = Color4::fromHex(0xFF8C89A3); // Przygaszony szaro-fioletowy
constexpr Color4 COL_ACTIVE_GREEN    = Color4::fromHex(0xFF00FF88); // Neonowa zieleń #00FF88
constexpr Color4 COL_INACTIVE_LED    = Color4::fromHex(0xFF4A485E); // Wygaszona szara dioda
constexpr Color4 COL_SHADOW          = Color4::fromHex(0x88030208); // Miękki cień pod oknem
constexpr Color4 COL_BADGE_BG        = Color4::fromHex(0x2EFFFFFF); // Tło badge'a klawisza [R]
constexpr Color4 COL_BADGE_ACTIVE_BG = Color4::fromHex(0x2A00FF88); // Subtelna zielona poświata badge'a
constexpr Color4 COL_BADGE_BORDER    = Color4::fromHex(0x30C4B5FD);
constexpr Color4 COL_BADGE_ACT_BDR   = Color4::fromHex(0x7500FF88);
constexpr Color4 COL_ROW_ACTIVE_BG   = Color4::fromHex(0x1400FF88); // Delikatne podświetlenie aktywnego wiersza

// Metryki glifów w atlasie czcionek TrueType
struct GlyphMetric {
    float u0 = 0.0f, v0 = 0.0f, u1 = 0.0f, v1 = 0.0f;
    float width = 0.0f;
    float height = 0.0f;
    float advance = 0.0f;
};

struct FontAtlas {
    GLuint textureId = 0;
    float lineHeight = 11.0f;
    float currentScale = 0.0f;
    std::array<GlyphMetric, 128> glyphs{};

    bool build(float guiScale) {
        const float safeScale = std::clamp(guiScale, 1.0f, 4.0f);
        if (textureId != 0 && std::abs(currentScale - safeScale) < 0.05f) {
            return true;
        }

        const int physicalFontPx = static_cast<int>(std::lround(12.5f * safeScale));
        const int ATLAS_W = static_cast<int>(512 * std::ceil(safeScale));
        const int ATLAS_H = static_cast<int>(128 * std::ceil(safeScale));

        HDC memDC = CreateCompatibleDC(nullptr);
        if (!memDC) return false;

        BITMAPINFO bmi{};
        bmi.bmiHeader.biSize        = sizeof(BITMAPINFOHEADER);
        bmi.bmiHeader.biWidth       = ATLAS_W;
        bmi.bmiHeader.biHeight      = -ATLAS_H; // Top-down DIB (zgodny z UV top-left)
        bmi.bmiHeader.biPlanes      = 1;
        bmi.bmiHeader.biBitCount    = 32;
        bmi.bmiHeader.biCompression = BI_RGB;

        void* bits = nullptr;
        HBITMAP dib = CreateDIBSection(memDC, &bmi, DIB_RGB_COLORS, &bits, nullptr, 0);
        if (!dib || !bits) {
            DeleteDC(memDC);
            return false;
        }

        HGDIOBJ oldBmp = SelectObject(memDC, dib);

        HFONT font = CreateFontW(
            -physicalFontPx, 0, 0, 0,
            FW_SEMIBOLD, FALSE, FALSE, FALSE,
            DEFAULT_CHARSET, OUT_TT_PRECIS, CLIP_DEFAULT_PRECIS,
            ANTIALIASED_QUALITY, DEFAULT_PITCH | FF_SWISS,
            L"Segoe UI"
        );
        HGDIOBJ oldFont = SelectObject(memDC, font);

        SetBkMode(memDC, TRANSPARENT);
        SetTextColor(memDC, RGB(255, 255, 255));
        std::memset(bits, 0, static_cast<size_t>(ATLAS_W) * ATLAS_H * 4);

        TEXTMETRICW tm{};
        GetTextMetricsW(memDC, &tm);
        lineHeight = static_cast<float>(tm.tmHeight) / safeScale;

        int penX = 2;
        int penY = 2;
        int rowMaxH = 0;

        for (int ch = 32; ch < 127; ++ch) {
            wchar_t wch = static_cast<wchar_t>(ch);
            SIZE sz{};
            GetTextExtentPoint32W(memDC, &wch, 1, &sz);

            const int glyphW = std::max<int>(1, sz.cx);
            const int glyphH = std::max<int>(1, sz.cy);

            if (penX + glyphW + 3 >= ATLAS_W) {
                penX = 2;
                penY += rowMaxH + 3;
                rowMaxH = 0;
            }
            if (penY + glyphH + 2 >= ATLAS_H) {
                break;
            }

            TextOutW(memDC, penX, penY, &wch, 1);

            GlyphMetric& gm = glyphs[static_cast<size_t>(ch)];
            gm.u0      = static_cast<float>(penX) / static_cast<float>(ATLAS_W);
            gm.v0      = static_cast<float>(penY) / static_cast<float>(ATLAS_H);
            gm.u1      = static_cast<float>(penX + glyphW) / static_cast<float>(ATLAS_W);
            gm.v1      = static_cast<float>(penY + glyphH) / static_cast<float>(ATLAS_H);
            gm.width   = static_cast<float>(glyphW) / safeScale;
            gm.height  = static_cast<float>(glyphH) / safeScale;
            gm.advance = static_cast<float>(glyphW) / safeScale;

            penX += glyphW + 3;
            rowMaxH = std::max(rowMaxH, glyphH);
        }

        GdiFlush();

        // Konwersja kanału luminancji z DIB do jednokanałowej tekstury GL_R8
        std::vector<uint8_t> alphaBuf(static_cast<size_t>(ATLAS_W) * ATLAS_H, 0);
        const uint32_t* srcPixels = static_cast<const uint32_t*>(bits);
        for (size_t i = 0; i < alphaBuf.size(); ++i) {
            const uint32_t px = srcPixels[i];
            const uint32_t r = (px >> 16) & 0xFF;
            const uint32_t g = (px >> 8)  & 0xFF;
            const uint32_t b =  px        & 0xFF;
            alphaBuf[i] = static_cast<uint8_t>((r + g + b) / 3);
        }

        GLint prevUnpack = 4;
        glGetIntegerv(GL_UNPACK_ALIGNMENT, &prevUnpack);
        glPixelStorei(GL_UNPACK_ALIGNMENT, 1);

        if (textureId == 0) {
            glGenTextures(1, &textureId);
        }
        glBindTexture(GL_TEXTURE_2D, textureId);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glTexImage2D(
            GL_TEXTURE_2D, 0, GL_R8,
            ATLAS_W, ATLAS_H, 0,
            GL_RED, GL_UNSIGNED_BYTE, alphaBuf.data()
        );

        glPixelStorei(GL_UNPACK_ALIGNMENT, prevUnpack);

        SelectObject(memDC, oldFont);
        SelectObject(memDC, oldBmp);
        DeleteObject(font);
        DeleteObject(dib);
        DeleteDC(memDC);

        currentScale = safeScale;
        return textureId != 0;
    }

    float measureText(const char* text) const {
        if (!text) return 0.0f;
        float w = 0.0f;
        for (const char* p = text; *p != '\0'; ++p) {
            const unsigned char c = static_cast<unsigned char>(*p);
            const size_t idx = (c >= 32 && c < 127) ? c : static_cast<size_t>('?');
            w += glyphs[idx].advance;
        }
        return w;
    }
};

struct KeybindEntryState {
    int32_t id = 0;
    int32_t mode = ASTRA_MODE_TOGGLE;
    bool active = false;
    bool pendingRemoval = false;
    char name[48]{};
    char keyLabel[20]{}; // Sformatowane np. "[R]"
    float animActive = 0.0f;
    float animAppear = 0.0f;
};

struct Vertex {
    float x, y;
    float lx, ly;
    float u, v;
};

struct EngineState {
    std::mutex mtx;
    GlFunctions gl{};
    bool initialized = false;
    bool glReady = false;

    GLuint program = 0;
    GLuint vao = 0;
    GLuint vbo = 0;
    FontAtlas font{};

    // Lokalizacje uniformów
    GLint uProj        = -1;
    GLint uMode        = -1;
    GLint uSize        = -1;
    GLint uRadius      = -1;
    GLint uBorderWidth = -1;
    GLint uSpread      = -1;
    GLint uFillColor   = -1;
    GLint uFillColor2  = -1;
    GLint uBorderColor = -1;
    GLint uFontTex     = -1;

    // Stan modułu Keybinds HUD
    bool visible = true;
    bool onlyShowActive = false;
    float targetX = 20.0f;
    float targetY = 120.0f;
    float animX = 20.0f;
    float animY = 120.0f;
    float animWidth = 156.0f;
    float animHeight = 24.0f;
    bool firstFrame = true;

    std::vector<KeybindEntryState> entries;
};

EngineState g_state;

// ============================================================================
// 4. POMOCNICZE FUNKCJE KOMPILACJI SHADERÓW I RYSOWANIA PRYMITYWÓW SDF
// ============================================================================
GLuint compileShader(const GlFunctions& gl, GLenum type, const char* src) {
    GLuint shader = gl.glCreateShader(type);
    gl.glShaderSource(shader, 1, &src, nullptr);
    gl.glCompileShader(shader);
    GLint ok = 0;
    gl.glGetShaderiv(shader, GL_COMPILE_STATUS, &ok);
    if (!ok) {
        gl.glDeleteShader(shader);
        return 0;
    }
    return shader;
}

bool initOpenGLResources(EngineState& st) {
    if (st.glReady) return true;
    if (!st.gl.loadAll()) return false;

    GLuint vs = compileShader(st.gl, GL_VERTEX_SHADER, VERTEX_SHADER_SRC);
    GLuint fs = compileShader(st.gl, GL_FRAGMENT_SHADER, FRAGMENT_SHADER_SRC);
    if (!vs || !fs) {
        if (vs) st.gl.glDeleteShader(vs);
        if (fs) st.gl.glDeleteShader(fs);
        return false;
    }

    st.program = st.gl.glCreateProgram();
    st.gl.glAttachShader(st.program, vs);
    st.gl.glAttachShader(st.program, fs);
    st.gl.glLinkProgram(st.program);
    st.gl.glDeleteShader(vs);
    st.gl.glDeleteShader(fs);

    GLint linked = 0;
    st.gl.glGetProgramiv(st.program, GL_LINK_STATUS, &linked);
    if (!linked) {
        st.gl.glDeleteProgram(st.program);
        st.program = 0;
        return false;
    }

    st.uProj        = st.gl.glGetUniformLocation(st.program, "uProj");
    st.uMode        = st.gl.glGetUniformLocation(st.program, "uMode");
    st.uSize        = st.gl.glGetUniformLocation(st.program, "uSize");
    st.uRadius      = st.gl.glGetUniformLocation(st.program, "uRadius");
    st.uBorderWidth = st.gl.glGetUniformLocation(st.program, "uBorderWidth");
    st.uSpread      = st.gl.glGetUniformLocation(st.program, "uSpread");
    st.uFillColor   = st.gl.glGetUniformLocation(st.program, "uFillColor");
    st.uFillColor2  = st.gl.glGetUniformLocation(st.program, "uFillColor2");
    st.uBorderColor = st.gl.glGetUniformLocation(st.program, "uBorderColor");
    st.uFontTex     = st.gl.glGetUniformLocation(st.program, "uFontTex");

    st.gl.glGenVertexArrays(1, &st.vao);
    st.gl.glGenBuffers(1, &st.vbo);

    st.gl.glBindVertexArray(st.vao);
    st.gl.glBindBuffer(GL_ARRAY_BUFFER, st.vbo);
    st.gl.glBufferData(GL_ARRAY_BUFFER, sizeof(Vertex) * 6, nullptr, GL_DYNAMIC_DRAW);

    st.gl.glEnableVertexAttribArray(0);
    st.gl.glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, sizeof(Vertex), reinterpret_cast<void*>(offsetof(Vertex, x)));
    st.gl.glEnableVertexAttribArray(1);
    st.gl.glVertexAttribPointer(1, 2, GL_FLOAT, GL_FALSE, sizeof(Vertex), reinterpret_cast<void*>(offsetof(Vertex, lx)));
    st.gl.glEnableVertexAttribArray(2);
    st.gl.glVertexAttribPointer(2, 2, GL_FLOAT, GL_FALSE, sizeof(Vertex), reinterpret_cast<void*>(offsetof(Vertex, u)));

    if (!st.font.build(1.0f)) {
        return false;
    }

    st.glReady = true;
    return true;
}

void emitQuad(const EngineState& st, float x, float y, float w, float h,
              float u0 = 0.0f, float v0 = 0.0f, float u1 = 1.0f, float v1 = 1.0f) {
    const Vertex verts[6] = {
        { x,     y,     0.0f, 0.0f, u0, v0 },
        { x,     y + h, 0.0f, h,    u0, v1 },
        { x + w, y + h, w,    h,    u1, v1 },
        { x,     y,     0.0f, 0.0f, u0, v0 },
        { x + w, y + h, w,    h,    u1, v1 },
        { x + w, y,     w,    0.0f, u1, v0 }
    };
    st.gl.glBufferSubData(GL_ARRAY_BUFFER, 0, sizeof(verts), verts);
    glDrawArrays(GL_TRIANGLES, 0, 6);
}

void drawSdfBox(const EngineState& st, float x, float y, float w, float h, float radius,
                const Color4& topCol, const Color4& botCol,
                float borderW = 0.0f, const Color4& borderCol = Color4{0, 0, 0, 0}) {
    if (w <= 0.1f || h <= 0.1f) return;
    st.gl.glUniform1i(st.uMode, 0);
    st.gl.glUniform2f(st.uSize, w, h);
    st.gl.glUniform1f(st.uRadius, radius);
    st.gl.glUniform1f(st.uBorderWidth, borderW);
    st.gl.glUniform4f(st.uFillColor, topCol.r, topCol.g, topCol.b, topCol.a);
    st.gl.glUniform4f(st.uFillColor2, botCol.r, botCol.g, botCol.b, botCol.a);
    st.gl.glUniform4f(st.uBorderColor, borderCol.r, borderCol.g, borderCol.b, borderCol.a);
    emitQuad(st, x, y, w, h);
}

void drawSdfShadowOrGlow(const EngineState& st, float x, float y, float w, float h,
                         float radius, float spread, const Color4& col) {
    if (col.a <= 0.002f) return;
    const float sx = x - spread;
    const float sy = y - spread;
    const float sw = w + spread * 2.0f;
    const float sh = h + spread * 2.0f;
    st.gl.glUniform1i(st.uMode, 1);
    st.gl.glUniform2f(st.uSize, sw, sh);
    st.gl.glUniform1f(st.uRadius, radius);
    st.gl.glUniform1f(st.uSpread, spread);
    st.gl.glUniform4f(st.uFillColor, col.r, col.g, col.b, col.a);
    emitQuad(st, sx, sy, sw, sh);
}

void drawGradientDivider(const EngineState& st, float x, float y, float w, float h, const Color4& col) {
    st.gl.glUniform1i(st.uMode, 2);
    st.gl.glUniform2f(st.uSize, w, h);
    st.gl.glUniform4f(st.uFillColor, col.r, col.g, col.b, col.a);
    emitQuad(st, x, y, w, h);
}

void drawText(const EngineState& st, const char* text, float x, float y, const Color4& col) {
    if (!text || *text == '\0' || col.a <= 0.005f) return;
    st.gl.glUniform1i(st.uMode, 3);
    st.gl.glUniform4f(st.uFillColor, col.r, col.g, col.b, col.a);

    float cursorX = x;
    for (const char* p = text; *p != '\0'; ++p) {
        const unsigned char c = static_cast<unsigned char>(*p);
        const size_t idx = (c >= 32 && c < 127) ? c : static_cast<size_t>('?');
        const GlyphMetric& gm = st.font.glyphs[idx];
        if (gm.width > 0.0f && gm.height > 0.0f) {
            emitQuad(st, cursorX, y, gm.width, gm.height, gm.u0, gm.v0, gm.u1, gm.v1);
        }
        cursorX += gm.advance;
    }
}

// Wektorowa ikona klawiatury [⌨] rysowana prymitywami SDF
void drawKeyboardIconSdf(const EngineState& st, float x, float y, const Color4& col) {
    const Color4 keyCap = col.withAlpha(col.a * 0.85f);
    // Ramka obudowy klawiatury (12x8 px)
    drawSdfBox(st, x, y, 12.0f, 8.0f, 1.8f, Color4{0, 0, 0, 0}, Color4{0, 0, 0, 0}, 1.0f, col);
    // Górny rząd klawiszy
    drawSdfBox(st, x + 2.0f, y + 2.0f, 1.5f, 1.5f, 0.4f, keyCap, keyCap);
    drawSdfBox(st, x + 4.3f, y + 2.0f, 1.5f, 1.5f, 0.4f, keyCap, keyCap);
    drawSdfBox(st, x + 6.6f, y + 2.0f, 1.5f, 1.5f, 0.4f, keyCap, keyCap);
    drawSdfBox(st, x + 8.9f, y + 2.0f, 1.3f, 1.5f, 0.4f, keyCap, keyCap);
    // Dolny rząd + spacja
    drawSdfBox(st, x + 2.0f, y + 4.5f, 1.6f, 1.5f, 0.4f, keyCap, keyCap);
    drawSdfBox(st, x + 4.2f, y + 4.5f, 3.8f, 1.5f, 0.4f, col, col);
    drawSdfBox(st, x + 8.6f, y + 4.5f, 1.6f, 1.5f, 0.4f, keyCap, keyCap);
}

void formatKeyBadge(const char* rawKey, char (&out)[20]) {
    if (!rawKey || rawKey[0] == '\0') {
        std::snprintf(out, sizeof(out), "NONE");
        return;
    }
    // Czysta etykieta klawisza bez podwójnych nawiasów [ ], ponieważ renderujemy zaokrąglony keycap 3D
    const size_t len = std::strlen(rawKey);
    if (len >= 2 && rawKey[0] == '[' && rawKey[len - 1] == ']') {
        std::snprintf(out, sizeof(out), "%.*s", static_cast<int>(len - 2), rawKey + 1);
    } else {
        std::snprintf(out, sizeof(out), "%.19s", rawKey);
    }
}

void seedDefaultModules(EngineState& st) {
    if (!st.entries.empty()) return;

    auto addDefault = [&](int32_t id, const char* name, const char* key, int32_t mode, bool act) {
        KeybindEntryState e{};
        e.id = id;
        e.mode = mode;
        e.active = act;
        e.animActive = act ? 1.0f : 0.0f;
        e.animAppear = 1.0f;
        std::snprintf(e.name, sizeof(e.name), "%s", name);
        formatKeyBadge(key, e.keyLabel);
        st.entries.push_back(e);
    };

    addDefault(1, "GrimAC Bypass", "R",      ASTRA_MODE_TOGGLE, true);
    addDefault(2, "Anarchia Mode", "V",      ASTRA_MODE_TOGGLE, true);
    addDefault(3, "FastMine",      "G",      ASTRA_MODE_HOLD,   false);
    addDefault(4, "KillAura",      "M4",     ASTRA_MODE_TOGGLE, false);
}

// Bezpieczny RAII guard stanu OpenGL (chroni potok renderowania Minecrafta 1.21.4)
struct ScopedGlStateGuard {
    const GlFunctions& gl;
    GLint prevProgram = 0;
    GLint prevVao = 0;
    GLint prevArrayBuf = 0;
    GLint prevActiveTex = 0;
    GLint prevTex2D = 0;
    GLint prevBlendSrcRgb = 0;
    GLint prevBlendDstRgb = 0;
    GLint prevBlendSrcAlpha = 0;
    GLint prevBlendDstAlpha = 0;
    GLboolean prevBlend = GL_FALSE;
    GLboolean prevDepth = GL_FALSE;
    GLboolean prevCull = GL_FALSE;
    GLboolean prevScissor = GL_FALSE;
    GLint prevScissorBox[4]{};

    explicit ScopedGlStateGuard(const GlFunctions& fn) : gl(fn) {
        glGetIntegerv(GL_CURRENT_PROGRAM, &prevProgram);
        glGetIntegerv(GL_VERTEX_ARRAY_BINDING, &prevVao);
        glGetIntegerv(GL_ARRAY_BUFFER_BINDING, &prevArrayBuf);
        glGetIntegerv(GL_ACTIVE_TEXTURE, &prevActiveTex);
        gl.glActiveTexture(GL_TEXTURE0);
        glGetIntegerv(GL_TEXTURE_BINDING_2D, &prevTex2D);

        prevBlend   = glIsEnabled(GL_BLEND);
        prevDepth   = glIsEnabled(GL_DEPTH_TEST);
        prevCull    = glIsEnabled(GL_CULL_FACE);
        prevScissor = glIsEnabled(GL_SCISSOR_TEST);
        glGetIntegerv(GL_SCISSOR_BOX, prevScissorBox);

        glGetIntegerv(GL_BLEND_SRC_RGB, &prevBlendSrcRgb);
        glGetIntegerv(GL_BLEND_DST_RGB, &prevBlendDstRgb);
        glGetIntegerv(GL_BLEND_SRC_ALPHA, &prevBlendSrcAlpha);
        glGetIntegerv(GL_BLEND_DST_ALPHA, &prevBlendDstAlpha);
    }

    ~ScopedGlStateGuard() {
        gl.glUseProgram(static_cast<GLuint>(prevProgram));
        gl.glBindVertexArray(static_cast<GLuint>(prevVao));
        gl.glBindBuffer(GL_ARRAY_BUFFER, static_cast<GLuint>(prevArrayBuf));
        gl.glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, static_cast<GLuint>(prevTex2D));
        gl.glActiveTexture(static_cast<GLenum>(prevActiveTex));

        gl.glBlendFuncSeparate(prevBlendSrcRgb, prevBlendDstRgb, prevBlendSrcAlpha, prevBlendDstAlpha);
        if (prevBlend) glEnable(GL_BLEND); else glDisable(GL_BLEND);
        if (prevDepth) glEnable(GL_DEPTH_TEST); else glDisable(GL_DEPTH_TEST);
        if (prevCull)  glEnable(GL_CULL_FACE);  else glDisable(GL_CULL_FACE);

        glScissor(prevScissorBox[0], prevScissorBox[1], prevScissorBox[2], prevScissorBox[3]);
        if (prevScissor) glEnable(GL_SCISSOR_TEST); else glDisable(GL_SCISSOR_TEST);
    }
};

} // namespace

// ============================================================================
// 5. IMPLEMENTACJA EKSPORTOWANEGO API C (PANAMA FFM ABI)
// ============================================================================
extern "C" {

ASTRA_API bool astra_init() {
    std::lock_guard<std::mutex> lock(g_state.mtx);
    if (!g_state.initialized) {
        g_state.entries.reserve(24);
        seedDefaultModules(g_state);
        g_state.initialized = true;
    }
    return true;
}

ASTRA_API void astra_shutdown() {
    std::lock_guard<std::mutex> lock(g_state.mtx);
    if (g_state.glReady) {
        if (g_state.font.textureId) {
            glDeleteTextures(1, &g_state.font.textureId);
            g_state.font.textureId = 0;
        }
        if (g_state.vbo) {
            g_state.gl.glDeleteBuffers(1, &g_state.vbo);
            g_state.vbo = 0;
        }
        if (g_state.vao) {
            g_state.gl.glDeleteVertexArrays(1, &g_state.vao);
            g_state.vao = 0;
        }
        if (g_state.program) {
            g_state.gl.glDeleteProgram(g_state.program);
            g_state.program = 0;
        }
        g_state.glReady = false;
    }
    g_state.entries.clear();
    g_state.initialized = false;
}

ASTRA_API void astra_keybinds_clear() {
    std::lock_guard<std::mutex> lock(g_state.mtx);
    for (auto& e : g_state.entries) {
        e.pendingRemoval = true;
    }
}

ASTRA_API void astra_keybinds_upsert(
    int32_t id,
    const char* nameUtf8,
    const char* keyLabelUtf8,
    int32_t mode,
    uint8_t active
) {
    std::lock_guard<std::mutex> lock(g_state.mtx);
    if (!g_state.initialized) {
        g_state.entries.reserve(24);
        g_state.initialized = true;
    }

    for (auto& e : g_state.entries) {
        if (e.id == id) {
            e.mode = mode;
            e.active = (active != 0);
            e.pendingRemoval = false;
            if (nameUtf8) {
                std::snprintf(e.name, sizeof(e.name), "%s", nameUtf8);
            }
            if (keyLabelUtf8) {
                formatKeyBadge(keyLabelUtf8, e.keyLabel);
            }
            return;
        }
    }

    KeybindEntryState created{};
    created.id = id;
    created.mode = mode;
    created.active = (active != 0);
    created.animActive = created.active ? 1.0f : 0.0f;
    created.animAppear = 0.0f; // Płynne rozwinięcie nowego wiersza
    std::snprintf(created.name, sizeof(created.name), "%s", nameUtf8 ? nameUtf8 : "Module");
    formatKeyBadge(keyLabelUtf8, created.keyLabel);
    g_state.entries.push_back(created);
}

ASTRA_API void astra_keybinds_remove(int32_t id) {
    std::lock_guard<std::mutex> lock(g_state.mtx);
    for (auto& e : g_state.entries) {
        if (e.id == id) {
            e.pendingRemoval = true;
            break;
        }
    }
}

ASTRA_API void astra_keybinds_sync_batch(
    const AstraKeybindNativeEntry* entries,
    int32_t count
) {
    std::lock_guard<std::mutex> lock(g_state.mtx);
    if (!g_state.initialized) {
        g_state.entries.reserve(24);
        g_state.initialized = true;
    }

    // Oznaczamy wszystkie istniejące jako kandydatów do płynnego zwinięcia
    for (auto& existing : g_state.entries) {
        existing.pendingRemoval = true;
    }

    if (!entries || count <= 0) return;

    for (int32_t i = 0; i < count; ++i) {
        const AstraKeybindNativeEntry& src = entries[i];
        bool found = false;
        for (auto& dst : g_state.entries) {
            if (dst.id == src.id) {
                dst.mode = src.mode;
                dst.active = (src.active != 0);
                dst.pendingRemoval = false;
                std::snprintf(dst.name, sizeof(dst.name), "%s", src.name);
                formatKeyBadge(src.keyLabel, dst.keyLabel);
                found = true;
                break;
            }
        }
        if (!found) {
            KeybindEntryState created{};
            created.id = src.id;
            created.mode = src.mode;
            created.active = (src.active != 0);
            created.animActive = created.active ? 1.0f : 0.0f;
            created.animAppear = 0.0f;
            std::snprintf(created.name, sizeof(created.name), "%s", src.name);
            formatKeyBadge(src.keyLabel, created.keyLabel);
            g_state.entries.push_back(created);
        }
    }
}

ASTRA_API void astra_keybinds_set_config(
    uint8_t visible,
    uint8_t onlyShowActive,
    float targetX,
    float targetY
) {
    std::lock_guard<std::mutex> lock(g_state.mtx);
    g_state.visible = (visible != 0);
    g_state.onlyShowActive = (onlyShowActive != 0);
    g_state.targetX = targetX;
    g_state.targetY = targetY;
}

ASTRA_API void astra_keybinds_get_bounds(
    float* outX,
    float* outY,
    float* outWidth,
    float* outHeight
) {
    std::lock_guard<std::mutex> lock(g_state.mtx);
    if (outX)      *outX      = g_state.animX;
    if (outY)      *outY      = g_state.animY;
    if (outWidth)  *outWidth  = g_state.animWidth;
    if (outHeight) *outHeight = g_state.animHeight;
}

ASTRA_API void astra_keybinds_render(
    int32_t guiScaledWidth,
    int32_t guiScaledHeight,
    float guiScale,
    float deltaTime
) {
    std::lock_guard<std::mutex> lock(g_state.mtx);
    if (!g_state.visible || guiScaledWidth <= 0 || guiScaledHeight <= 0) {
        return;
    }

    if (!g_state.initialized) {
        g_state.entries.reserve(24);
        seedDefaultModules(g_state);
        g_state.initialized = true;
    }

    // Leniwa inicjalizacja zasobów OpenGL w aktywnym kontekście wątku renderującego MC
    if (!g_state.glReady && !initOpenGLResources(g_state)) {
        return;
    }

    g_state.font.build(guiScale);

    const float dt = std::clamp(deltaTime, 0.0005f, 0.1f);
    const float smoothFast = 1.0f - std::exp(-18.0f * dt);
    const float smoothLayout = 1.0f - std::exp(-15.0f * dt);

    // Stałe wymiarowe stylu Grim Client
    constexpr float MIN_WIDTH      = 166.0f;
    constexpr float HEADER_HEIGHT  = 21.0f;
    constexpr float ROW_HEIGHT     = 17.0f;
    constexpr float PAD_X          = 9.0f;
    constexpr float BODY_PAD_TOP   = 4.0f;
    constexpr float BODY_PAD_BOT   = 4.0f;
    constexpr float CARD_RADIUS    = 6.0f;
    constexpr float BADGE_RADIUS   = 3.0f;

    int activeCount = 0;
    float desiredWidth = MIN_WIDTH;
    float animatedBodyRowsHeight = 0.0f;

    const float modeColW = std::max(g_state.font.measureText("HOLD"), g_state.font.measureText("TGL")) + 6.0f;
    float maxKeycapW = 18.0f;

    for (const auto& e : g_state.entries) {
        if (!e.pendingRemoval && (!g_state.onlyShowActive || e.active)) {
            maxKeycapW = std::max(maxKeycapW, g_state.font.measureText(e.keyLabel) + 8.0f);
        }
    }

    // 1. Aktualizacja animacji per-wiersz i obliczanie docelowej szerokości/wysokości okna
    for (size_t i = 0; i < g_state.entries.size(); ) {
        KeybindEntryState& e = g_state.entries[i];

        if (e.active && !e.pendingRemoval) {
            ++activeCount;
        }

        const float targetAct = (e.active && !e.pendingRemoval) ? 1.0f : 0.0f;
        e.animActive += (targetAct - e.animActive) * smoothFast;
        if (e.animActive < 0.003f) e.animActive = 0.0f;
        else if (e.animActive > 0.997f) e.animActive = 1.0f;

        const bool shouldShow = !e.pendingRemoval && (!g_state.onlyShowActive || e.active);
        const float targetVis = shouldShow ? 1.0f : 0.0f;
        e.animAppear += (targetVis - e.animAppear) * smoothLayout;

        if (e.pendingRemoval && e.animAppear < 0.01f) {
            g_state.entries.erase(g_state.entries.begin() + static_cast<ptrdiff_t>(i));
            continue;
        }

        if (e.animAppear > 0.01f) {
            const float nameW  = g_state.font.measureText(e.name);
            // [PadX] + [LED 10px] + [Nazwa] + [Odstęp 14px] + [KeycapCol] + [Odstęp 5px] + [ModeCol] + [PadX]
            const float rowReqW = PAD_X + 10.0f + nameW + 14.0f + maxKeycapW + 5.0f + modeColW + PAD_X;
            desiredWidth = std::max(desiredWidth, rowReqW);
            animatedBodyRowsHeight += ROW_HEIGHT * e.animAppear;
        }

        ++i;
    }

    // Jeśli w trybie onlyShowActive nie ma żadnych aktywnych modułów i okno całkowicie się zwinęło
    if (g_state.onlyShowActive && animatedBodyRowsHeight < 0.25f && activeCount == 0) {
        g_state.animHeight = 0.0f;
        return;
    }

    const float desiredHeight = HEADER_HEIGHT + 1.0f + BODY_PAD_TOP + animatedBodyRowsHeight + BODY_PAD_BOT;

    // Przycinanie pozycji do krawędzi ekranu
    const float maxX = std::max(4.0f, static_cast<float>(guiScaledWidth) - desiredWidth - 4.0f);
    const float maxY = std::max(4.0f, static_cast<float>(guiScaledHeight) - desiredHeight - 4.0f);
    const float clampedX = std::clamp(g_state.targetX, 4.0f, maxX);
    const float clampedY = std::clamp(g_state.targetY, 4.0f, maxY);

    if (g_state.firstFrame) {
        g_state.animX = clampedX;
        g_state.animY = clampedY;
        g_state.animWidth = desiredWidth;
        g_state.animHeight = desiredHeight;
        g_state.firstFrame = false;
    } else {
        g_state.animX      += (clampedX      - g_state.animX)      * smoothFast;
        g_state.animY      += (clampedY      - g_state.animY)      * smoothFast;
        g_state.animWidth  += (desiredWidth  - g_state.animWidth)  * smoothLayout;
        g_state.animHeight += (desiredHeight - g_state.animHeight) * smoothLayout;
    }

    const float x = g_state.animX;
    const float y = g_state.animY;
    const float w = g_state.animWidth;
    const float h = g_state.animHeight;

    // 2. Zapis stanu OpenGL Minecrafta i konfiguracja potoku 2D SDF
    ScopedGlStateGuard glGuard(g_state.gl);

    glEnable(GL_BLEND);
    g_state.gl.glBlendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
    glDisable(GL_DEPTH_TEST);
    glDisable(GL_CULL_FACE);
    glDisable(GL_SCISSOR_TEST);

    g_state.gl.glUseProgram(g_state.program);
    g_state.gl.glBindVertexArray(g_state.vao);
    g_state.gl.glBindBuffer(GL_ARRAY_BUFFER, g_state.vbo);
    g_state.gl.glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, g_state.font.textureId);
    g_state.gl.glUniform1i(g_state.uFontTex, 0);

    // Macierz ortograficzna (0,0 w lewym górnym rogu)
    const float L = 0.0f;
    const float R = static_cast<float>(guiScaledWidth);
    const float T = 0.0f;
    const float B = static_cast<float>(guiScaledHeight);
    const float ortho[16] = {
        2.0f / (R - L), 0.0f,            0.0f, 0.0f,
        0.0f,           2.0f / (T - B),  0.0f, 0.0f,
        0.0f,           0.0f,           -1.0f, 0.0f,
       -(R + L) / (R - L), -(T + B) / (T - B), 0.0f, 1.0f
    };
    g_state.gl.glUniformMatrix4fv(g_state.uProj, 1, GL_FALSE, ortho);

    // 3. Miękki cień pod oknem (Drop Shadow) + główny kontener Obsidian & Lilac Glass
    drawSdfShadowOrGlow(g_state, x, y + 2.0f, w, h, CARD_RADIUS, 11.0f, COL_SHADOW);
    drawSdfBox(g_state, x, y, w, h, CARD_RADIUS, COL_BG_TOP, COL_BG_BOT, 1.0f, COL_BORDER_LILAC);

    // 4. Szklany nagłówek
    drawSdfBox(g_state, x + 1.0f, y + 1.0f, w - 2.0f, HEADER_HEIGHT - 1.0f, CARD_RADIUS - 1.0f, COL_HEADER_TOP, COL_HEADER_BOT);

    // Neonowy pionowy akcent z lewej strony nagłówka
    drawSdfBox(g_state, x + 2.0f, y + 5.0f, 2.0f, HEADER_HEIGHT - 10.0f, 1.0f, COL_ACCENT_LILAC, COL_ACCENT_LILAC);

    // Ikona klawiatury [⌨] w kolorze neonowej lawendy
    const float iconY = y + (HEADER_HEIGHT - 8.0f) * 0.5f;
    drawKeyboardIconSdf(g_state, x + PAD_X + 1.0f, iconY, COL_ACCENT_LILAC);

    // Tytuł "Keybinds"
    const float headerTextY = y + (HEADER_HEIGHT - g_state.font.lineHeight) * 0.5f - 0.5f;
    drawText(g_state, "Keybinds", x + PAD_X + 16.0f, headerTextY, COL_ACCENT_LILAC);

    // Licznik aktywnych klawiszy w nawiasie np. "[2]"
    char counterBuf[16];
    std::snprintf(counterBuf, sizeof(counterBuf), "[%d]", activeCount);
    const float counterW = g_state.font.measureText(counterBuf);
    const Color4 counterCol = (activeCount > 0) ? COL_ACTIVE_GREEN : COL_TEXT_DIM;
    drawText(g_state, counterBuf, x + w - PAD_X - counterW, headerTextY, counterCol);

    // 5. Subtelna linia gradientowa oddzielająca nagłówek od listy
    const float dividerY = y + HEADER_HEIGHT;
    drawGradientDivider(g_state, x + 5.0f, dividerY, w - 10.0f, 1.0f, COL_ACCENT_LILAC.withAlpha(0.42f));

    // 6. Przycinanie Nożycami (Scissor) na obszar listy podczas płynnego rozwijania okna
    const float bodyTopY = dividerY + 1.0f;
    const float bodyClipH = std::max(0.0f, h - HEADER_HEIGHT - 1.0f);
    if (bodyClipH <= 0.5f) {
        return;
    }

    const float scale = (guiScale > 0.0f) ? guiScale : 1.0f;
    const int fbHeight = static_cast<int>(std::round(static_cast<float>(guiScaledHeight) * scale));
    const int scX = static_cast<int>(std::floor(x * scale));
    const int scY = fbHeight - static_cast<int>(std::ceil((bodyTopY + bodyClipH) * scale));
    const int scW = std::max(1, static_cast<int>(std::ceil(w * scale)));
    const int scH = std::max(1, static_cast<int>(std::ceil(bodyClipH * scale)));

    glEnable(GL_SCISSOR_TEST);
    glScissor(scX, scY, scW, scH);

    // 7. Renderowanie wierszy modułów
    float cursorY = bodyTopY + BODY_PAD_TOP;

    for (const KeybindEntryState& e : g_state.entries) {
        if (e.animAppear <= 0.01f) continue;

        const float vis = e.animAppear;
        const float act = e.animActive;
        const float effectiveRowH = ROW_HEIGHT * vis;
        const float slideOffsetX = (1.0f - vis) * -6.0f;

        // Subtelne podświetlenie tła aktywnego wiersza
        if (act > 0.01f) {
            const Color4 rowBg = COL_ROW_ACTIVE_BG.withAlpha(COL_ROW_ACTIVE_BG.a * act * vis);
            drawSdfBox(g_state, x + 3.0f, cursorY + 0.5f, w - 6.0f, effectiveRowH - 1.0f, 3.5f, rowBg, rowBg);
        }

        const float rowCenterY = cursorY + effectiveRowH * 0.5f;

        // Okrągła dioda LED (neonowa zieleń #00FF88 + miękki bloom gdy aktywna)
        const float ledSize = 4.6f;
        const float ledX = x + PAD_X + slideOffsetX;
        const float ledY = rowCenterY - ledSize * 0.5f;

        if (act > 0.03f) {
            const Color4 bloomCol = COL_ACTIVE_GREEN.withAlpha(0.38f * act * vis);
            drawSdfShadowOrGlow(g_state, ledX, ledY, ledSize, ledSize, ledSize * 0.5f, 3.5f, bloomCol);
        }

        Color4 ledCol = Color4::lerp(COL_INACTIVE_LED, COL_ACTIVE_GREEN, act);
        ledCol.a *= vis;
        drawSdfBox(g_state, ledX, ledY, ledSize, ledSize, ledSize * 0.5f, ledCol, ledCol);

        // Nazwa modułu (czysta biel dla aktywnego, przygaszony odcień dla nieaktywnego)
        Color4 nameCol = Color4::lerp(COL_TEXT_DIM, COL_TEXT_WHITE, act);
        nameCol.a *= vis;
        const float textY = rowCenterY - g_state.font.lineHeight * 0.5f - 0.5f;
        drawText(g_state, e.name, ledX + 8.5f, textY, nameCol);

        // Stałe pozycje X prawych kolumn (idealne wyrównanie pionowe bez poszarpania!)
        const float modeColX = x + w - PAD_X - modeColW;
        const float keycapColX = modeColX - 5.0f - maxKeycapW;

        // Keycap 3D klawisza np. R, V, N, G, M4
        const float badgeH = 13.0f;
        const float badgeY = rowCenterY - badgeH * 0.5f;

        Color4 badgeBg  = Color4::lerp(COL_BADGE_BG, COL_BADGE_ACTIVE_BG, act);
        Color4 badgeBdr = Color4::lerp(COL_BADGE_BORDER, COL_BADGE_ACT_BDR, act);
        badgeBg.a  *= vis;
        badgeBdr.a *= vis;

        // Dolny rant 3D klawisza (efekt mechanicznego switcha)
        Color4 lipCol = Color4::lerp(Color4::fromHex(0xFF0C0B14), Color4::fromHex(0xFF091713), act);
        lipCol.a *= vis;
        drawSdfBox(g_state, keycapColX, badgeY + 1.0f, maxKeycapW, badgeH, BADGE_RADIUS, lipCol, lipCol);
        drawSdfBox(g_state, keycapColX, badgeY, maxKeycapW, badgeH - 1.0f, BADGE_RADIUS, badgeBg, badgeBg, 0.85f, badgeBdr);

        const float keyTextW = g_state.font.measureText(e.keyLabel);
        const float keyTextX = keycapColX + (maxKeycapW - keyTextW) * 0.5f;
        Color4 keyTxtCol = Color4::lerp(COL_TEXT_WHITE, COL_ACTIVE_GREEN, act * 0.85f);
        keyTxtCol.a *= vis;
        drawText(g_state, e.keyLabel, keyTextX, textY, keyTxtCol);

        // Pigułka trybu (TGL / HOLD w stałej kolumnie)
        const float modePillH = 13.0f;
        const float modePillY = rowCenterY - modePillH * 0.5f;
        const Color4 modePillBg = Color4::fromHex(0x5506050B).withAlpha(0.33f * vis);
        const Color4 modePillBdr = Color4::fromHex(0x22C4B5FD).withAlpha(0.13f * vis);
        drawSdfBox(g_state, modeColX, modePillY, modeColW, modePillH, 2.5f, modePillBg, modePillBg, 0.5f, modePillBdr);

        const char* modeStr = (e.mode == ASTRA_MODE_HOLD) ? "HOLD" : "TGL";
        const float modeW = g_state.font.measureText(modeStr);
        const float modeTextX = modeColX + (modeColW - modeW) * 0.5f;
        Color4 modeCol = Color4::lerp(COL_TEXT_DIM, COL_ACCENT_LILAC, act * 0.7f);
        modeCol.a *= vis;
        drawText(g_state, modeStr, modeTextX, textY, modeCol);

        cursorY += effectiveRowH;
    }
}

} // extern "C"



// ============================================================================
// 2. MODUŁ APPLE LIQUID GLASS TITLESCREEN (SDF / UBO / OPENGL 3.3 CORE)
// ============================================================================
namespace astra_title {
// MINIMALISTYCZNE DEFINICJE TYPÓW I STAŁYCH OPENGL 3.3 CORE (BEZ ZEWN. NAGŁÓWKÓW)
// ============================================================================

using GLchar = char;
using GLsizeiptr = ptrdiff_t;
using GLintptr = ptrdiff_t;

#ifndef GL_FRAGMENT_SHADER
#define GL_FRAGMENT_SHADER                0x8B30
#define GL_VERTEX_SHADER                  0x8B31
#define GL_COMPILE_STATUS                 0x8B81
#define GL_LINK_STATUS                    0x8B82
#define GL_CURRENT_PROGRAM                0x8B8D
#define GL_ARRAY_BUFFER                   0x8892
#define GL_ARRAY_BUFFER_BINDING           0x8894
#define GL_STATIC_DRAW                    0x88E4
#define GL_DYNAMIC_DRAW                   0x88E8
#define GL_VERTEX_ARRAY_BINDING           0x85B5
#define GL_UNIFORM_BUFFER                 0x8A11
#define GL_UNIFORM_BUFFER_BINDING         0x8A28
#define GL_INVALID_INDEX                  0xFFFFFFFFu
#define GL_TEXTURE0                       0x84C0
#define GL_ACTIVE_TEXTURE                 0x84E0
#define GL_CLAMP_TO_EDGE                  0x812F
#define GL_BLEND_DST_RGB                  0x80C8
#define GL_BLEND_SRC_RGB                  0x80C9
#define GL_BLEND_DST_ALPHA                0x80CA
#define GL_BLEND_SRC_ALPHA                0x80CB
#define GL_SRC_ALPHA                      0x0302
#define GL_ONE_MINUS_SRC_ALPHA            0x0303
#define GL_ONE                            1
#endif

// Wskaźniki funkcji OpenGL 3.3 Core
using PFNGLCREATESHADERPROC            = GLuint(APIENTRY*)(GLenum type);
using PFNGLSHADERSOURCEPROC            = void(APIENTRY*)(GLuint shader, GLsizei count, const GLchar* const* string, const GLint* length);
using PFNGLCOMPILESHADERPROC           = void(APIENTRY*)(GLuint shader);
using PFNGLGETSHADERIVPROC             = void(APIENTRY*)(GLuint shader, GLenum pname, GLint* params);
using PFNGLGETSHADERINFOLOGPROC        = void(APIENTRY*)(GLuint shader, GLsizei bufSize, GLsizei* length, GLchar* infoLog);
using PFNGLDELETESHADERPROC            = void(APIENTRY*)(GLuint shader);
using PFNGLCREATEPROGRAMPROC           = GLuint(APIENTRY*)();
using PFNGLATTACHSHADERPROC            = void(APIENTRY*)(GLuint program, GLuint shader);
using PFNGLLINKPROGRAMPROC             = void(APIENTRY*)(GLuint program);
using PFNGLGETPROGRAMIVPROC            = void(APIENTRY*)(GLuint program, GLenum pname, GLint* params);
using PFNGLGETPROGRAMINFOLOGPROC       = void(APIENTRY*)(GLuint program, GLsizei bufSize, GLsizei* length, GLchar* infoLog);
using PFNGLUSEPROGRAMPROC              = void(APIENTRY*)(GLuint program);
using PFNGLDELETEPROGRAMPROC           = void(APIENTRY*)(GLuint program);
using PFNGLGETUNIFORMLOCATIONPROC      = GLint(APIENTRY*)(GLuint program, const GLchar* name);
using PFNGLUNIFORM1IPROC               = void(APIENTRY*)(GLint location, GLint v0);
using PFNGLUNIFORM4FVPROC              = void(APIENTRY*)(GLint location, GLsizei count, const GLfloat* value);
using PFNGLGETUNIFORMBLOCKINDEXPROC    = GLuint(APIENTRY*)(GLuint program, const GLchar* uniformBlockName);
using PFNGLUNIFORMBLOCKBINDINGPROC     = void(APIENTRY*)(GLuint program, GLuint uniformBlockIndex, GLuint uniformBlockBinding);
using PFNGLGENVERTEXARRAYSPROC         = void(APIENTRY*)(GLsizei n, GLuint* arrays);
using PFNGLDELETEVERTEXARRAYSPROC      = void(APIENTRY*)(GLsizei n, const GLuint* arrays);
using PFNGLBINDVERTEXARRAYPROC         = void(APIENTRY*)(GLuint array);
using PFNGLGENBUFFERSPROC              = void(APIENTRY*)(GLsizei n, GLuint* buffers);
using PFNGLDELETEBUFFERSPROC           = void(APIENTRY*)(GLsizei n, const GLuint* buffers);
using PFNGLBINDBUFFERPROC              = void(APIENTRY*)(GLenum target, GLuint buffer);
using PFNGLBINDBUFFERBASEPROC          = void(APIENTRY*)(GLenum target, GLuint index, GLuint buffer);
using PFNGLBUFFERDATAPROC              = void(APIENTRY*)(GLenum target, GLsizeiptr size, const void* data, GLenum usage);
using PFNGLBUFFERSUBDATAPROC           = void(APIENTRY*)(GLenum target, GLintptr offset, GLsizeiptr size, const void* data);
using PFNGLENABLEVERTEXATTRIBARRAYPROC = void(APIENTRY*)(GLuint index);
using PFNGLVERTEXATTRIBPOINTERPROC     = void(APIENTRY*)(GLuint index, GLint size, GLenum type, GLboolean normalized, GLsizei stride, const void* pointer);
using PFNGLACTIVETEXTUREPROC           = void(APIENTRY*)(GLenum texture);
using PFNGLBLENDFUNCSEPARATEPROC       = void(APIENTRY*)(GLenum sfactorRGB, GLenum dfactorRGB, GLenum sfactorAlpha, GLenum dfactorAlpha);
using PFNGLGETINTEGERI_VPROC           = void(APIENTRY*)(GLenum target, GLuint index, GLint* data);

namespace {

struct GlDispatchTable {
    PFNGLCREATESHADERPROC            CreateShader            = nullptr;
    PFNGLSHADERSOURCEPROC            ShaderSource            = nullptr;
    PFNGLCOMPILESHADERPROC           CompileShader           = nullptr;
    PFNGLGETSHADERIVPROC             GetShaderiv             = nullptr;
    PFNGLGETSHADERINFOLOGPROC        GetShaderInfoLog        = nullptr;
    PFNGLDELETESHADERPROC            DeleteShader            = nullptr;
    PFNGLCREATEPROGRAMPROC           CreateProgram           = nullptr;
    PFNGLATTACHSHADERPROC            AttachShader            = nullptr;
    PFNGLLINKPROGRAMPROC             LinkProgram             = nullptr;
    PFNGLGETPROGRAMIVPROC            GetProgramiv            = nullptr;
    PFNGLGETPROGRAMINFOLOGPROC       GetProgramInfoLog       = nullptr;
    PFNGLUSEPROGRAMPROC              UseProgram              = nullptr;
    PFNGLDELETEPROGRAMPROC           DeleteProgram           = nullptr;
    PFNGLGETUNIFORMLOCATIONPROC      GetUniformLocation      = nullptr;
    PFNGLUNIFORM1IPROC               Uniform1i               = nullptr;
    PFNGLUNIFORM4FVPROC              Uniform4fv              = nullptr;
    PFNGLGETUNIFORMBLOCKINDEXPROC    GetUniformBlockIndex    = nullptr;
    PFNGLUNIFORMBLOCKBINDINGPROC     UniformBlockBinding     = nullptr;
    PFNGLGENVERTEXARRAYSPROC         GenVertexArrays         = nullptr;
    PFNGLDELETEVERTEXARRAYSPROC      DeleteVertexArrays      = nullptr;
    PFNGLBINDVERTEXARRAYPROC         BindVertexArray         = nullptr;
    PFNGLGENBUFFERSPROC              GenBuffers              = nullptr;
    PFNGLDELETEBUFFERSPROC           DeleteBuffers           = nullptr;
    PFNGLBINDBUFFERPROC              BindBuffer              = nullptr;
    PFNGLBINDBUFFERBASEPROC          BindBufferBase          = nullptr;
    PFNGLBUFFERDATAPROC              BufferData              = nullptr;
    PFNGLBUFFERSUBDATAPROC           BufferSubData           = nullptr;
    PFNGLENABLEVERTEXATTRIBARRAYPROC EnableVertexAttribArray = nullptr;
    PFNGLVERTEXATTRIBPOINTERPROC     VertexAttribPointer     = nullptr;
    PFNGLACTIVETEXTUREPROC           ActiveTexture           = nullptr;
    PFNGLBLENDFUNCSEPARATEPROC       BlendFuncSeparate       = nullptr;
    PFNGLGETINTEGERI_VPROC           GetIntegeri_v           = nullptr;

    bool loaded = false;
    bool supportsUbo = false;
};

GlDispatchTable g_gl{};

void* loadGlSymbol(HMODULE opengl32Module, const char* name) {
    void* p = reinterpret_cast<void*>(wglGetProcAddress(name));
    if (p == nullptr ||
        p == reinterpret_cast<void*>(0x1) ||
        p == reinterpret_cast<void*>(0x2) ||
        p == reinterpret_cast<void*>(0x3) ||
        p == reinterpret_cast<void*>(-1)) {
        if (opengl32Module != nullptr) {
            p = reinterpret_cast<void*>(GetProcAddress(opengl32Module, name));
        }
    }
    return p;
}

template <typename FnT>
bool bindGlProc(HMODULE opengl32Module, FnT& outFn, const char* name) {
    outFn = reinterpret_cast<FnT>(loadGlSymbol(opengl32Module, name));
    return outFn != nullptr;
}

bool ensureGlFunctionsLoaded() {
    if (g_gl.loaded) {
        return true;
    }

    HMODULE hGl32 = GetModuleHandleA("opengl32.dll");
    if (hGl32 == nullptr) {
        hGl32 = LoadLibraryA("opengl32.dll");
    }

    bool ok = true;
    ok &= bindGlProc(hGl32, g_gl.CreateShader,            "glCreateShader");
    ok &= bindGlProc(hGl32, g_gl.ShaderSource,            "glShaderSource");
    ok &= bindGlProc(hGl32, g_gl.CompileShader,           "glCompileShader");
    ok &= bindGlProc(hGl32, g_gl.GetShaderiv,             "glGetShaderiv");
    ok &= bindGlProc(hGl32, g_gl.GetShaderInfoLog,        "glGetShaderInfoLog");
    ok &= bindGlProc(hGl32, g_gl.DeleteShader,            "glDeleteShader");
    ok &= bindGlProc(hGl32, g_gl.CreateProgram,           "glCreateProgram");
    ok &= bindGlProc(hGl32, g_gl.AttachShader,            "glAttachShader");
    ok &= bindGlProc(hGl32, g_gl.LinkProgram,             "glLinkProgram");
    ok &= bindGlProc(hGl32, g_gl.GetProgramiv,            "glGetProgramiv");
    ok &= bindGlProc(hGl32, g_gl.GetProgramInfoLog,       "glGetProgramInfoLog");
    ok &= bindGlProc(hGl32, g_gl.UseProgram,              "glUseProgram");
    ok &= bindGlProc(hGl32, g_gl.DeleteProgram,           "glDeleteProgram");
    ok &= bindGlProc(hGl32, g_gl.GetUniformLocation,      "glGetUniformLocation");
    ok &= bindGlProc(hGl32, g_gl.Uniform1i,               "glUniform1i");
    ok &= bindGlProc(hGl32, g_gl.Uniform4fv,              "glUniform4fv");
    ok &= bindGlProc(hGl32, g_gl.GenVertexArrays,         "glGenVertexArrays");
    ok &= bindGlProc(hGl32, g_gl.DeleteVertexArrays,      "glDeleteVertexArrays");
    ok &= bindGlProc(hGl32, g_gl.BindVertexArray,         "glBindVertexArray");
    ok &= bindGlProc(hGl32, g_gl.GenBuffers,              "glGenBuffers");
    ok &= bindGlProc(hGl32, g_gl.DeleteBuffers,           "glDeleteBuffers");
    ok &= bindGlProc(hGl32, g_gl.BindBuffer,              "glBindBuffer");
    ok &= bindGlProc(hGl32, g_gl.BufferData,              "glBufferData");
    ok &= bindGlProc(hGl32, g_gl.BufferSubData,           "glBufferSubData");
    ok &= bindGlProc(hGl32, g_gl.EnableVertexAttribArray, "glEnableVertexAttribArray");
    ok &= bindGlProc(hGl32, g_gl.VertexAttribPointer,     "glVertexAttribPointer");
    ok &= bindGlProc(hGl32, g_gl.ActiveTexture,           "glActiveTexture");
    ok &= bindGlProc(hGl32, g_gl.BlendFuncSeparate,       "glBlendFuncSeparate");

    // Opcjonalne rozszerzenia UBO (GL 3.1+ Core) — posiadamy bezpieczny fallback uniform vec4[]
    bool uboOk = true;
    uboOk &= bindGlProc(hGl32, g_gl.GetUniformBlockIndex, "glGetUniformBlockIndex");
    uboOk &= bindGlProc(hGl32, g_gl.UniformBlockBinding,  "glUniformBlockBinding");
    uboOk &= bindGlProc(hGl32, g_gl.BindBufferBase,       "glBindBufferBase");
    uboOk &= bindGlProc(hGl32, g_gl.GetIntegeri_v,        "glGetIntegeri_v");

    g_gl.supportsUbo = ok && uboOk;
    g_gl.loaded = ok;
    return ok;
}

// ============================================================================
// OCHRONA STANU BLAZE3D / MINECRAFT OPENGL (ZERO-ALLOC RAII GUARD)
// ============================================================================

class ScopedTitleGlStateGuard {
public:
    ScopedTitleGlStateGuard() noexcept {
        glGetIntegerv(GL_CURRENT_PROGRAM, &prevProgram_);
        glGetIntegerv(GL_VERTEX_ARRAY_BINDING, &prevVao_);
        glGetIntegerv(GL_ARRAY_BUFFER_BINDING, &prevArrayBuffer_);
        glGetIntegerv(GL_UNIFORM_BUFFER_BINDING, &prevUniformBuffer_);

        if (g_gl.supportsUbo && g_gl.GetIntegeri_v != nullptr) {
            g_gl.GetIntegeri_v(GL_UNIFORM_BUFFER_BINDING, 0, &prevUniformBufferSlot0_);
        }

        glGetIntegerv(GL_ACTIVE_TEXTURE, &prevActiveTexture_);
        g_gl.ActiveTexture(GL_TEXTURE0);
        glGetIntegerv(GL_TEXTURE_BINDING_2D, &prevTexture2DUnit0_);
        g_gl.ActiveTexture(static_cast<GLenum>(prevActiveTexture_));
        glGetIntegerv(GL_TEXTURE_BINDING_2D, &prevTexture2DActive_);

        prevBlend_       = glIsEnabled(GL_BLEND);
        prevDepthTest_   = glIsEnabled(GL_DEPTH_TEST);
        prevCullFace_    = glIsEnabled(GL_CULL_FACE);
        prevScissorTest_ = glIsEnabled(GL_SCISSOR_TEST);

        glGetIntegerv(GL_BLEND_SRC_RGB, &prevBlendSrcRgb_);
        glGetIntegerv(GL_BLEND_DST_RGB, &prevBlendDstRgb_);
        glGetIntegerv(GL_BLEND_SRC_ALPHA, &prevBlendSrcAlpha_);
        glGetIntegerv(GL_BLEND_DST_ALPHA, &prevBlendDstAlpha_);

        glGetIntegerv(GL_VIEWPORT, prevViewport_);
        glGetIntegerv(GL_SCISSOR_BOX, prevScissorBox_);
    }

    ~ScopedTitleGlStateGuard() noexcept {
        glViewport(prevViewport_[0], prevViewport_[1], prevViewport_[2], prevViewport_[3]);
        glScissor(prevScissorBox_[0], prevScissorBox_[1], prevScissorBox_[2], prevScissorBox_[3]);

        if (prevScissorTest_) glEnable(GL_SCISSOR_TEST); else glDisable(GL_SCISSOR_TEST);
        if (prevCullFace_)    glEnable(GL_CULL_FACE);    else glDisable(GL_CULL_FACE);
        if (prevDepthTest_)   glEnable(GL_DEPTH_TEST);   else glDisable(GL_DEPTH_TEST);

        g_gl.BlendFuncSeparate(
            static_cast<GLenum>(prevBlendSrcRgb_),
            static_cast<GLenum>(prevBlendDstRgb_),
            static_cast<GLenum>(prevBlendSrcAlpha_),
            static_cast<GLenum>(prevBlendDstAlpha_)
        );
        if (prevBlend_) glEnable(GL_BLEND); else glDisable(GL_BLEND);

        g_gl.ActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, static_cast<GLuint>(prevTexture2DUnit0_));
        g_gl.ActiveTexture(static_cast<GLenum>(prevActiveTexture_));
        glBindTexture(GL_TEXTURE_2D, static_cast<GLuint>(prevTexture2DActive_));

        if (g_gl.supportsUbo && g_gl.BindBufferBase != nullptr) {
            g_gl.BindBufferBase(GL_UNIFORM_BUFFER, 0, static_cast<GLuint>(prevUniformBufferSlot0_));
        }
        g_gl.BindBuffer(GL_UNIFORM_BUFFER, static_cast<GLuint>(prevUniformBuffer_));
        g_gl.BindBuffer(GL_ARRAY_BUFFER, static_cast<GLuint>(prevArrayBuffer_));
        g_gl.BindVertexArray(static_cast<GLuint>(prevVao_));
        g_gl.UseProgram(static_cast<GLuint>(prevProgram_));
    }

    ScopedTitleGlStateGuard(const ScopedTitleGlStateGuard&) = delete;
    ScopedTitleGlStateGuard& operator=(const ScopedTitleGlStateGuard&) = delete;

private:
    GLint prevProgram_ = 0;
    GLint prevVao_ = 0;
    GLint prevArrayBuffer_ = 0;
    GLint prevUniformBuffer_ = 0;
    GLint prevUniformBufferSlot0_ = 0;
    GLint prevActiveTexture_ = GL_TEXTURE0;
    GLint prevTexture2DUnit0_ = 0;
    GLint prevTexture2DActive_ = 0;

    GLboolean prevBlend_ = GL_FALSE;
    GLboolean prevDepthTest_ = GL_FALSE;
    GLboolean prevCullFace_ = GL_FALSE;
    GLboolean prevScissorTest_ = GL_FALSE;

    GLint prevBlendSrcRgb_ = GL_SRC_ALPHA;
    GLint prevBlendDstRgb_ = GL_ONE_MINUS_SRC_ALPHA;
    GLint prevBlendSrcAlpha_ = GL_ONE;
    GLint prevBlendDstAlpha_ = GL_ONE_MINUS_SRC_ALPHA;

    GLint prevViewport_[4]{};
    GLint prevScissorBox_[4]{};
};

// ============================================================================
// KODY ŹRÓDŁOWE SHADERÓW GLSL 3.30 CORE (APPLE LIQUID GLASS + SDF VECTOR UI)
// ============================================================================

constexpr const char* FULLSCREEN_QUAD_VS = R"GLSL(
#version 330 core
layout(location = 0) in vec2 aPos;
void main() {
    gl_Position = vec4(aPos, 0.0, 1.0);
}
)GLSL";

// Wersja UBO (główny szybki potok)
constexpr const char* LIQUID_GLASS_TITLESCREEN_UBO_FS_1 = R"GLSL(
#version 330 core
out vec4 fragColor;

uniform sampler2D uBgTexture;

layout(std140) uniform TitleScreenBlock {
    vec4 uScreen;       // x: guiW, y: guiH, z: fbW, w: fbH
    vec4 uTimeMouse;    // x: totalTime, y: deltaTime, z: mouseX, w: mouseY
    vec4 uFlags;        // x: hasBgTexture (0/1), y: musicProgress, z: musicPlaying, w: currentTrack
    vec4 uStatus;       // x: hoveredDockIndex, y: profileMenuOpen, z: dotsHover, w: dropdownAnim

    vec4 uSpRect;       // x, y, w, h
    vec4 uMpRect;       // x, y, w, h
    vec4 uContinueRect; // x, y, w, h
    vec4 uMusicRect;    // x, y, w, h
    vec4 uProfileRect;  // x, y, w, h
    vec4 uDropdownRect; // x, y, w, h
    vec4 uDockRect;     // x, y, w, h
    vec4 uTooltipRect;  // x, y, w, h

    vec4 uHoverA;       // x: spHover, y: mpHover, z: continueHover, w: continuePlayHover
    vec4 uHoverB;       // x: sliderHover, y: mediaPrevHover, z: mediaPlayHover, w: mediaNextHover
    vec4 uHoverC;       // x: profileHover, y: statusDotX, z: dotsCenterX, w: unused
    vec4 uDockHover;    // x, y, z, w
    vec4 uDockIconX;    // x0, x1, x2, x3
};

// ----------------------------------------------------------------------------
// 1. POLA ODLEGŁOŚCI SDF (SIGNED DISTANCE FIELDS)
// ----------------------------------------------------------------------------

// 4-narożnikowy SDF zaokrąglonego prostokąta (r = vec4(TR, BR, TL, BL) w układzie GUI Y-w-dół)
float sdRoundedBox4(vec2 p, vec2 b, vec4 r) {
    vec2 rx = (p.x > 0.0) ? r.xy : r.zw;
    float rad = (p.y > 0.0) ? rx.y : rx.x;
    vec2 q = abs(p) - b + rad;
    return min(max(q.x, q.y), 0.0) + length(max(q, 0.0)) - rad;
}

float sdSegment(vec2 p, vec2 a, vec2 b) {
    vec2 pa = p - a, ba = b - a;
    float h = clamp(dot(pa, ba) / max(dot(ba, ba), 1e-6), 0.0, 1.0);
    return length(pa - ba * h);
}

float sdTriangle(vec2 p, vec2 p0, vec2 p1, vec2 p2) {
    vec2 e0 = p1 - p0, e1 = p2 - p1, e2 = p0 - p2;
    vec2 v0 = p - p0, v1 = p - p1, v2 = p - p2;
    vec2 pq0 = v0 - e0 * clamp(dot(v0, e0) / dot(e0, e0), 0.0, 1.0);
    vec2 pq1 = v1 - e1 * clamp(dot(v1, e1) / dot(e1, e1), 0.0, 1.0);
    vec2 pq2 = v2 - e2 * clamp(dot(v2, e2) / dot(e2, e2), 0.0, 1.0);
    float s = sign(e0.x * e2.y - e0.y * e2.x);
    vec2 d = min(min(vec2(dot(pq0, pq0), s * (v0.x * e0.y - v0.y * e0.x)),
                     vec2(dot(pq1, pq1), s * (v1.x * e1.y - v1.y * e1.x))),
                     vec2(dot(pq2, pq2), s * (v2.x * e2.y - v2.y * e2.x)));
    return -sqrt(d.x) * sign(d.y);
}

// Wektorowe ikony SDF
float sdSingleUser(vec2 p) {
    float head = length(p - vec2(0.0, -2.5)) - 2.15;
    float body = sdRoundedBox4(p - vec2(0.0, 2.4), vec2(3.7, 2.0), vec4(2.0, 1.2, 2.0, 1.2));
    return min(head, body);
}

float sdMultiUser(vec2 p) {
    float backHead = length(p - vec2(3.1, -2.1)) - 1.8;
    float backBody = sdRoundedBox4(p - vec2(3.1, 2.2), vec2(3.0, 1.7), vec4(1.7, 1.0, 1.7, 1.0));
    float front = sdSingleUser(p - vec2(-1.6, 0.0));
    return min(front, min(backHead, backBody));
}

float sdGearIcon(vec2 p) {
    float r = length(p);
    float ang = atan(p.y, p.x);
    float teeth = 0.75 * cos(ang * 8.0);
    float outerRing = r - (4.6 + teeth);
    float innerHole = 2.05 - r;
    return max(outerRing, innerHole);
}

float sdPencilIcon(vec2 p) {
    // Obrót o -45 stopni dla ukośnego ołówka
    const float c = 0.70710678;
    vec2 q = vec2(p.x * c + p.y * c, -p.x * c + p.y * c);
    float shaft = sdRoundedBox4(q - vec2(0.0, -0.7), vec2(1.45, 3.8), vec4(0.6));
    float tip = sdTriangle(q, vec2(-1.45, 3.1), vec2(1.45, 3.1), vec2(0.0, 5.7));
    return min(shaft, tip);
}

vec2 guiToTexUV(vec2 guiPos) {
    return vec2(guiPos.x / uScreen.x, 1.0 - (guiPos.y / uScreen.y));
}

// ----------------------------------------------------------------------------
// 2. 12-PUNKTOWY FROSTED BLUR + DYSPERSJA CHROMATYCZNA + FALLBACK
// ----------------------------------------------------------------------------

vec3 sampleFallbackSky(vec2 uv) {
    vec3 top = vec3(0.035, 0.065, 0.135);
    vec3 bot = vec3(0.075, 0.125, 0.225);
    return mix(bot, top, clamp(uv.y, 0.0, 1.0));
}

vec3 sampleFrostedGlassBg(vec2 baseUV, vec2 refractOffset, float blurRadiusPx, float dispersion) {
    return sampleFallbackSky(baseUV + refractOffset);
}

// ----------------------------------------------------------------------------
// 3. MATERIAŁ "APPLE LIQUID GLASS" (DOME CURVATURE + SPECULAR + HERMITE SHADOW)
// ----------------------------------------------------------------------------

vec4 evalLiquidGlassCard(vec2 guiPos, vec4 rect, vec4 radii, float hover, vec3 accentGlow, float accentStrength) {
    vec2 center = rect.xy + rect.zw * 0.5;
    vec2 halfExtents = rect.zw * 0.5;
    vec2 p = guiPos - center;

    vec4 maxR = vec4(min(min(halfExtents.x, halfExtents.y), radii.x),
                     min(min(halfExtents.x, halfExtents.y), radii.y),
                     min(min(halfExtents.x, halfExtents.y), radii.z),
                     min(min(halfExtents.x, halfExtents.y), radii.w));

    float d = sdRoundedBox4(p, halfExtents, maxR);

    // Głęboki miękki cień Hermite'a bez schodków
    float shadowDist = max(d, 0.0);
    if (shadowDist > 20.0) {
        return vec4(0.0);
    }

    float tShadow = clamp(1.0 - shadowDist / 20.0, 0.0, 1.0);
    float hermiteShadow = tShadow * tShadow * (3.0 - 2.0 * tShadow);
    float shadowAlpha = hermiteShadow * exp(-shadowDist * 0.20) * (0.32 + 0.12 * hover);

    // Gradient SDF do obliczenia normalnej soczewkowej (Dome Curvature)
    const vec2 eps = vec2(0.75, 0.0);
    vec2 grad = normalize(vec2(
        sdRoundedBox4(p + eps.xy, halfExtents, maxR) - sdRoundedBox4(p - eps.xy, halfExtents, maxR),
        sdRoundedBox4(p + eps.yx, halfExtents, maxR) - sdRoundedBox4(p - eps.yx, halfExtents, maxR)
    ) + vec2(1e-5));

    // Wypukły menisk kropli wody (Surface Tension Water Bevel 18.0px)
    float bevelWidth = 18.0;
    float edgeDist = clamp(-d / bevelWidth, 0.0, 1.0);

    // Wypukły profil sferyczny kropli wody (Surface Tension Arc)
    float waterDome = sqrt(max(0.001, 1.0 - (1.0 - edgeDist) * (1.0 - edgeDist)));
    float domeSlope = (1.0 - edgeDist) / max(0.06, waterDome);

    // Wypukła soczewkowa normalna kropli zagięta silnie na obrzeżach
    float normalStrength = 1.75 + 0.40 * hover;
    vec3 N = normalize(vec3(grad * domeSlope * normalStrength, 1.0));

    // Refrakcja optyczna zaginająca tło przy brzegach (soczewka wody)
    vec2 baseUV = guiToTexUV(guiPos);
    vec2 refractOffset = N.xy * (0.026 + 0.012 * hover) * waterDome;
    float dispersion = 0.50 * (1.0 - edgeDist); // tęczowa aberracja na krawędzi kropli
    float blurRadius = 8.5 * waterDome + 3.5 * hover;

    vec3 glassBg = sampleFrostedGlassBg(baseUV, refractOffset, blurRadius, dispersion);

    // Krystaliczna woda z subtelnym błękitnym odcieniem
    vec3 waterTint = mix(vec3(0.92, 0.97, 1.0), vec3(1.0), 0.70);
    float tintMix = 0.05 + 0.04 * hover;
    vec3 col = mix(glassBg, waterTint, tintMix);

    // Subtelny błękitny glow przy najechaniu
    col += accentGlow * (accentStrength * hover * (0.30 + 0.70 * (1.0 - edgeDist)));

    // Podwójny refleks specular kropli wody:
    // 1. Główny jasny błysk światła na szczycie wypukłości
    vec3 L1 = normalize(vec3(-0.42, -0.85, 0.35));
    float specDome = pow(max(dot(N, L1), 0.0), 16.0) * (1.0 - edgeDist);
    col += vec3(1.0) * specDome * (0.75 + 0.35 * hover);

    // 2. Wewnętrzny refleks kaustyczny (drugie odbicie wewnątrz soczewki)
    vec3 L2 = normalize(vec3(0.35, -0.90, 0.25));
    float specCaustic = pow(max(dot(N, L2), 0.0), 32.0) * waterDome;
    col += vec3(0.85, 0.95, 1.0) * specCaustic * 0.40;

    // Jasna wypukła krawędź menisku kropli (Water Bead Rim Highlight)
    float beadRim = 1.0 - smoothstep(0.0, 1.40, abs(d + 0.75));
    float topMeniscus = clamp(0.75 - 0.50 * grad.y, 0.25, 1.0);
    float rimAlpha = beadRim * (0.45 + 0.30 * hover) * topMeniscus;
    col = mix(col, vec3(1.0), rimAlpha);

    // Subpikselowy antyaliasing konturu karty
    float bodyMask = 1.0 - smoothstep(-0.75, 0.75, d);
    float glassAlpha = mix(0.55, 0.72, hover);

    vec3 outRgb = mix(vec3(0.01, 0.02, 0.05), col, bodyMask);
    float outAlpha = max(shadowAlpha * (1.0 - bodyMask), bodyMask * glassAlpha);
    outAlpha = clamp(outAlpha + rimAlpha * 0.35 + specDome * 0.40, 0.0, 1.0);
    return vec4(outRgb, outAlpha);
}

void blendOver(inout vec4 dst, vec4 src) {
    if (src.a <= 0.001) return;
    float outA = src.a + dst.a * (1.0 - src.a);
    if (outA > 0.001) {
        dst.rgb = (src.rgb * src.a + dst.rgb * dst.a * (1.0 - src.a)) / outA;
    } else {
        dst.rgb = vec3(0.0);
    }
    dst.a = outA;
}

// ----------------------------------------------------------------------------
// 4. GŁÓWNY POTOK RENDEROWANIA EKRANU TYTUŁOWEGO)GLSL";
constexpr const char* LIQUID_GLASS_TITLESCREEN_UBO_FS_2 = R"GLSL(
// ----------------------------------------------------------------------------

void main() {
    vec2 screenUV = gl_FragCoord.xy / max(uScreen.zw, vec2(1.0));
    vec2 guiPos = vec2(screenUV.x * uScreen.x, (1.0 - screenUV.y) * uScreen.y);

    // Przezroczysty punkt wyjscia - karty blenda sie bezposrednio na tlo wyrenderowane przez Jave
    vec4 outCol = vec4(0.0);

    const vec3 SKY_BLUE_GLOW = vec3(0.220, 0.741, 0.973); // #38BDF8

    // === A. PIGUŁKI TRYBÓW SINGLEPLAYER & MULTIPLAYER ===
    blendOver(outCol, evalLiquidGlassCard(guiPos, uSpRect, vec4(uSpRect.w * 0.5), uHoverA.x, SKY_BLUE_GLOW, 0.34));
    blendOver(outCol, evalLiquidGlassCard(guiPos, uMpRect, vec4(uMpRect.w * 0.5), uHoverA.y, SKY_BLUE_GLOW, 0.34));

    vec2 spIconCenter = vec2(uSpRect.x + uSpRect.z * 0.5 - 35.0, uSpRect.y + uSpRect.w * 0.5);
    float dSpIcon = sdSingleUser(guiPos - spIconCenter);
    float spIconAlpha = (1.0 - smoothstep(-0.65, 0.65, dSpIcon)) * mix(0.82, 1.0, uHoverA.x);
    blendOver(outCol, vec4(1.0, 1.0, 1.0, spIconAlpha));

    vec2 mpIconCenter = vec2(uMpRect.x + uMpRect.z * 0.5 - 34.0, uMpRect.y + uMpRect.w * 0.5);
    float dMpIcon = sdMultiUser(guiPos - mpIconCenter);
    float mpIconAlpha = (1.0 - smoothstep(-0.65, 0.65, dMpIcon)) * mix(0.82, 1.0, uHoverA.y);
    blendOver(outCol, vec4(1.0, 1.0, 1.0, mpIconAlpha));

    // === B. KARTA "CONTINUE" (ANARCHIA.GG / SERWER QUICK JOIN) ===
    blendOver(outCol, evalLiquidGlassCard(guiPos, uContinueRect, vec4(15.0), uHoverA.z * 0.85, vec3(0.98, 0.75, 0.14), 0.20));

    // Okrągły przycisk Play [ ▶ ] po prawej stronie karty Continue
    vec2 playBtnCenter = vec2(uContinueRect.x + uContinueRect.z - 23.0, uContinueRect.y + uContinueRect.w * 0.5);
    float playHover = max(uHoverA.z * 0.45, uHoverA.w);
    float dPlayCircle = length(guiPos - playBtnCenter) - 13.5;
    if (dPlayCircle < 12.0) {
        float flare = exp(-max(dPlayCircle, 0.0) * 0.25) * 0.32 * uHoverA.w;
        blendOver(outCol, vec4(1.0, 0.95, 0.82, flare));
        float fillA = mix(0.14, 0.32, playHover);
        float rimA = 1.0 - smoothstep(0.0, 1.1, abs(dPlayCircle + 0.5));
        float cMask = 1.0 - smoothstep(-0.7, 0.7, dPlayCircle);
        blendOver(outCol, vec4(1.0, 1.0, 1.0, cMask * fillA + rimA * 0.30));
    }
    float triShift = 1.1 * uHoverA.w;
    float dPlayTri = sdTriangle(guiPos - playBtnCenter,
        vec2(-2.9 + triShift, -4.5),
        vec2(-2.9 + triShift,  4.5),
        vec2( 4.9 + triShift,  0.0));
    blendOver(outCol, vec4(1.0, 1.0, 1.0, (1.0 - smoothstep(-0.6, 0.6, dPlayTri)) * 0.96));

    // === C. ODTWARZACZ MUZYKI iOS (NOW PLAYING WIDGET) ===
    if (uMusicRect.z > 10.0 && uMusicRect.w > 10.0) {
        blendOver(outCol, evalLiquidGlassCard(guiPos, uMusicRect, vec4(18.0), 0.14, SKY_BLUE_GLOW, 0.12));

        // Miniaturka okładki albumu z gradientem zmierzchu i symbolem płyty winylowej
        float covSize = uMusicRect.w - 22.0;
        vec2 covPos = vec2(uMusicRect.x + 11.0, uMusicRect.y + 11.0);
        vec2 covCenter = covPos + covSize * 0.5;
        float dCov = sdRoundedBox4(guiPos - covCenter, vec2(covSize * 0.5), vec4(10.0));
        if (dCov < 1.0) {
            vec2 cuv = (guiPos - covPos) / max(covSize, 1.0);
            int trk = int(uFlags.w + 0.5);
            vec3 twilightTop = (trk == 0) ? vec3(0.18, 0.11, 0.38) : ((trk == 1) ? vec3(0.08, 0.21, 0.42) : vec3(0.24, 0.12, 0.34));
            vec3 twilightBot = vec3(0.04, 0.06, 0.15);
            vec3 art = mix(twilightTop, twilightBot, cuv.y);

            // Symbol płyty winylowej i księżycowego refleksu w okładce
            float discR = length(cuv - vec2(0.5, 0.5));
            float vinylRing = smoothstep(0.39, 0.37, discR) * smoothstep(0.11, 0.13, discR);
            float groove = 0.5 + 0.5 * sin(discR * 95.0);
            art = mix(art, vec3(0.06, 0.08, 0.16) + groove * 0.04, vinylRing * 0.85);
            float labelCol = smoothstep(0.13, 0.11, discR);
            art = mix(art, vec3(0.96, 0.65, 0.14), labelCol);
            float spindle = smoothstep(0.035, 0.025, discR);
            art = mix(art, vec3(0.04, 0.06, 0.15), spindle);

            float covRim = 1.0 - smoothstep(0.0, 1.1, abs(dCov + 0.5));
            art = mix(art, vec3(1.0), covRim * 0.28);
            float covMask = 1.0 - smoothstep(-0.7, 0.7, dCov);
            blendOver(outCol, vec4(art, covMask));
        }

        // Interaktywny pasek postępu (Timeline Slider)
        float sHover = uHoverB.x;
        float sliderX = covPos.x + covSize + 11.0;
        float sliderW = (uMusicRect.x + uMusicRect.z - 13.0) - sliderX;
        float sliderY = covPos.y + covSize - 10.0;
        float sliderH = 3.0 + 1.8 * sHover;
        vec2 sCenter = vec2(sliderX + sliderW * 0.5, sliderY);

        float dRail = sdRoundedBox4(guiPos - sCenter, vec2(sliderW * 0.5, sliderH * 0.5), vec4(sliderH * 0.5));
        blendOver(outCol, vec4(1.0, 1.0, 1.0, (1.0 - smoothstep(-0.6, 0.6, dRail)) * 0.20));

        float prog = clamp(uFlags.y, 0.0, 1.0);
        float fillW = max(sliderH, sliderW * prog);
        vec2 fCenter = vec2(sliderX + fillW * 0.5, sliderY);
        float dFill = sdRoundedBox4(guiPos - fCenter, vec2(fillW * 0.5, sliderH * 0.5), vec4(sliderH * 0.5));
        blendOver(outCol, vec4(1.0, 1.0, 1.0, (1.0 - smoothstep(-0.6, 0.6, dFill)) * 0.95));

        // Główka suwaka (Thumb) powiększająca się płynnie przy najechaniu
        float thumbR = mix(2.2, 4.6, sHover);
        vec2 thumbPos = vec2(sliderX + sliderW * prog, sliderY);
        float dThumb = length(guiPos - thumbPos) - thumbR;
        blendOver(outCol, vec4(1.0, 1.0, 1.0, (1.0 - smoothstep(-0.6, 0.6, dThumb)) * mix(0.65, 1.0, sHover)));

        // Wektorowe kontrolki SDF: [ |◀ ]   [ ▶ / ⏸ ]   [ ▶| ]
        float ctrlCenterX = sliderX + sliderW * 0.5;
        float ctrlCenterY = uMusicRect.y + uMusicRect.w - 13.0;

        // Poprzedni utwór (|◀)
        vec2 prevP = guiPos - vec2(ctrlCenterX - 32.0, ctrlCenterY);
        blendOver(outCol, vec4(1.0, 1.0, 1.0, (1.0 - smoothstep(-0.6, 0.6, length(prevP) - 10.5)) * (0.15 * uHoverB.y)));
        float dPrevBar = sdRoundedBox4(prevP - vec2(-3.6, 0.0), vec2(0.75, 3.8), vec4(0.5));
        float dPrevTri = sdTriangle(prevP, vec2(4.0, -3.8), vec2(-2.0, 0.0), vec2(4.0, 3.8));
        blendOver(outCol, vec4(1.0, 1.0, 1.0, (1.0 - smoothstep(-0.6, 0.6, min(dPrevBar, dPrevTri))) * mix(0.76, 1.0, uHoverB.y)));

        // Play / Pause (▶ / ⏸)
        vec2 ppP = guiPos - vec2(ctrlCenterX, ctrlCenterY);
        blendOver(outCol, vec4(1.0, 1.0, 1.0, (1.0 - smoothstep(-0.6, 0.6, length(ppP) - 12.0)) * (0.20 * uHoverB.z)));
        float dPpIcon;
        if (uFlags.z > 0.5) {
            float barL = sdRoundedBox4(ppP - vec2(-2.3, 0.0), vec2(1.15, 4.2), vec4(0.8));
            float barR = sdRoundedBox4(ppP - vec2( 2.3, 0.0), vec2(1.15, 4.2), vec4(0.8));
            dPpIcon = min(barL, barR);
        } else {
            dPpIcon = sdTriangle(ppP, vec2(-2.9, -4.6), vec2(-2.9, 4.6), vec2(4.9, 0.0));
        }
        blendOver(outCol, vec4(1.0, 1.0, 1.0, (1.0 - smoothstep(-0.6, 0.6, dPpIcon)) * mix(0.88, 1.0, uHoverB.z)));

        // Następny utwór (▶|)
        vec2 nextP = guiPos - vec2(ctrlCenterX + 32.0, ctrlCenterY);
        blendOver(outCol, vec4(1.0, 1.0, 1.0, (1.0 - smoothstep(-0.6, 0.6, length(nextP) - 10.5)) * (0.15 * uHoverB.w)));
        float dNextBar = sdRoundedBox4(nextP - vec2(3.6, 0.0), vec2(0.75, 3.8), vec4(0.5));
        float dNextTri = sdTriangle(nextP, vec2(-4.0, -3.8), vec2(-4.0, 3.8), vec2(2.0, 0.0));
        blendOver(outCol, vec4(1.0, 1.0, 1.0, (1.0 - smoothstep(-0.6, 0.6, min(dNextBar, dNextTri))) * mix(0.76, 1.0, uHoverB.w)));
    }

    // === D. GÓRNY PASEK PROFILU (TOP-RIGHT) ===
    blendOver(outCol, evalLiquidGlassCard(guiPos, uProfileRect, vec4(uProfileRect.w * 0.5), uHoverC.x * 0.75, SKY_BLUE_GLOW, 0.18));

    float profMidY = uProfileRect.y + uProfileRect.w * 0.5;
    vec2 statusDotPos = vec2(uHoverC.y, profMidY);
    float dStatusDot = length(guiPos - statusDotPos);
    float pulse = 0.65 + 0.35 * sin(uTimeMouse.x * 3.2);
    blendOver(outCol, vec4(0.13, 0.77, 0.37, exp(-dStatusDot * 0.45) * 0.52 * pulse));
    blendOver(outCol, vec4(0.20, 0.88, 0.46, 1.0 - smoothstep(2.1, 3.0, dStatusDot)));

    // Pionowy separator i menu "•••"
    vec2 dotsCenter = vec2(uHoverC.z, profMidY);
    float dDiv = sdRoundedBox4(guiPos - vec2(dotsCenter.x - 12.0, profMidY), vec2(0.5, 7.5), vec4(0.5));
    blendOver(outCol, vec4(1.0, 1.0, 1.0, (1.0 - smoothstep(-0.5, 0.5, dDiv)) * 0.18));

    blendOver(outCol, vec4(1.0, 1.0, 1.0, (1.0 - smoothstep(-0.6, 0.6, length(guiPos - dotsCenter) - 10.5)) * (0.16 * uStatus.z)));
    float dDots = min(
        length(guiPos - (dotsCenter + vec2(-4.2, 0.0))) - 1.25,
        min(length(guiPos - dotsCenter) - 1.25,
            length(guiPos - (dotsCenter + vec2(4.2, 0.0))) - 1.25)
    );
    blendOver(outCol, vec4(1.0, 1.0, 1.0, (1.0 - smoothstep(-0.5, 0.5, dDots)) * mix(0.78, 1.0, uStatus.z)));

    if (uStatus.w > 0.02) {
        blendOver(outCol, evalLiquidGlassCard(guiPos, uDropdownRect, vec4(13.0), 0.45, SKY_BLUE_GLOW, 0.18));
    }

    // === E. DOLNY PŁYWAJĄCY DOCK (KAPSUŁA R = H/2) ===
    blendOver(outCol, evalLiquidGlassCard(guiPos, uDockRect, vec4(uDockRect.w * 0.5), 0.12, SKY_BLUE_GLOW, 0.14));

    float dockMidY = uDockRect.y + uDockRect.w * 0.5;
    float iconCenters[4] = float[4](uDockIconX.x, uDockIconX.y, uDockIconX.z, uDockIconX.w);
    float iconHovers[4]  = float[4](uDockHover.x, uDockHover.y, uDockHover.z, uDockHover.w);

    for (int i = 0; i < 4; i++) {
        float h = iconHovers[i];
        vec2 ic = vec2(iconCenters[i], dockMidY - 1.5 * h);
        vec2 ip = guiPos - ic;

        if (h > 0.01) {
            vec3 glowCol = (i == 3) ? vec3(0.97, 0.44, 0.44) : SKY_BLUE_GLOW;
            blendOver(outCol, vec4(glowCol, (1.0 - smoothstep(-0.7, 0.7, length(ip) - 14.5)) * (0.18 * h)));
            float underGlow = exp(-length(guiPos - vec2(iconCenters[i], dockMidY + 7.5)) * 0.15) * 0.35 * h;
            blendOver(outCol, vec4(glowCol, underGlow));
        }

        float dIcon = 100.0;
        if (i == 0) {
            dIcon = sdGearIcon(ip);
        } else if (i == 1) {
            dIcon = sdPencilIcon(ip);
        } else if (i == 2) {
            dIcon = sdSingleUser(ip);
        } else {
            float s1 = sdSegment(ip, vec2(-4.0, -4.0), vec2(4.0, 4.0)) - 0.95;
            float s2 = sdSegment(ip, vec2( 4.0, -4.0), vec2(-4.0, 4.0)) - 0.95;
            dIcon = min(s1, s2);
        }

        vec3 iconTint = (i == 3) ? mix(vec3(1.0), vec3(0.98, 0.45, 0.45), h) : vec3(1.0);
        float iconAlpha = (1.0 - smoothstep(-0.6, 0.6, dIcon)) * mix(0.78, 1.0, h);
        blendOver(outCol, vec4(iconTint, iconAlpha));
    }

    // Dymek z podpowiedzią (Tooltip) nad aktywną ikoną w docku
    int hoveredIdx = int(uStatus.x + (uStatus.x >= 0.0 ? 0.5 : -0.5));
    if (hoveredIdx >= 0 && hoveredIdx < 4) {
        float tipHover = iconHovers[hoveredIdx];
        if (tipHover > 0.08) {
            blendOver(outCol, evalLiquidGlassCard(guiPos, uTooltipRect, vec4(9.0), 0.35, SKY_BLUE_GLOW, 0.18));
        }
    }

    fragColor = outCol;
}
)GLSL";


// Wersja Fallback bez UBO (uniform vec4 uData[17]) dla starszych sterowników OpenGL 3.3
constexpr const char* LIQUID_GLASS_TITLESCREEN_FALLBACK_FS = R"GLSL(
#version 330 core
out vec4 fragColor;

uniform sampler2D uBgTexture;
uniform vec4 uData[17];

float sdRoundedBox4(vec2 p, vec2 b, vec4 r) {
    vec2 rx = (p.x > 0.0) ? r.xy : r.zw;
    float rad = (p.y > 0.0) ? rx.y : rx.x;
    vec2 q = abs(p) - b + rad;
    return min(max(q.x, q.y), 0.0) + length(max(q, 0.0)) - rad;
}

vec4 evalFallbackCard(vec2 guiPos, vec4 rect, float radius, float hover) {
    vec2 center = rect.xy + rect.zw * 0.5;
    vec2 halfExtents = rect.zw * 0.5;
    float d = sdRoundedBox4(guiPos - center, halfExtents, vec4(min(radius, min(halfExtents.x, halfExtents.y))));
    float shadow = exp(-max(d, 0.0) * 0.20) * 0.30 * step(d, 18.0);
    float body = 1.0 - smoothstep(-0.75, 0.75, d);
    float rim = (1.0 - smoothstep(0.0, 1.15, abs(d + 0.5))) * (0.28 + 0.22 * hover);
    vec3 col = mix(vec3(0.06, 0.10, 0.18), vec3(0.90, 0.95, 1.0), 0.10 + 0.08 * hover + rim);
    return vec4(mix(vec3(0.01, 0.02, 0.05), col, body), max(shadow * (1.0 - body), body));
}

void main() {
    vec4 scr = uData[0];
    vec2 screenUV = gl_FragCoord.xy / max(scr.zw, vec2(1.0));
    vec2 guiPos = vec2(screenUV.x * scr.x, (1.0 - screenUV.y) * scr.y);

    vec4 outCol = vec4(0.0);

    vec4 c0 = evalFallbackCard(guiPos, uData[4], uData[4].w * 0.5, uData[12].x);
    outCol.rgb = mix(outCol.rgb, c0.rgb, c0.a);
    vec4 c1 = evalFallbackCard(guiPos, uData[5], uData[5].w * 0.5, uData[12].y);
    outCol.rgb = mix(outCol.rgb, c1.rgb, c1.a);
    vec4 c2 = evalFallbackCard(guiPos, uData[6], 15.0, uData[12].z);
    outCol.rgb = mix(outCol.rgb, c2.rgb, c2.a);
    vec4 c3 = evalFallbackCard(guiPos, uData[7], 18.0, 0.14);
    outCol.rgb = mix(outCol.rgb, c3.rgb, c3.a);
    vec4 c4 = evalFallbackCard(guiPos, uData[8], uData[8].w * 0.5, uData[14].x);
    outCol.rgb = mix(outCol.rgb, c4.rgb, c4.a);
    vec4 c5 = evalFallbackCard(guiPos, uData[10], uData[10].w * 0.5, 0.12);
    outCol.rgb = mix(outCol.rgb, c5.rgb, c5.a);

    fragColor = outCol;
}
)GLSL";

// ============================================================================
// STAN SILNIKA NATYWNEGO (ZERO-ALLOCATION RUNTIME)
// ============================================================================

struct NativeTitleRendererState {
    bool initialized = false;
    bool usingUboPath = false;

    GLuint programId = 0;
    GLuint vaoId = 0;
    GLuint vboId = 0;
    GLuint uboId = 0;

    GLint uBgTextureLoc = -1;
    GLint uFallbackDataLoc = -1;

    bool hasLayoutOverride = false;
    AstraTitleScreenLayoutPOD layoutOverride{};

    // Statyczny wyrównany bufor std140 (17 * vec4 = 68 floatów = 272 bajty)
    alignas(16) float uboData[68]{};
};

NativeTitleRendererState g_engine{};

GLuint compileShader(GLenum type, const char* source) {
    GLuint shader = g_gl.CreateShader(type);
    if (shader == 0) {
        return 0;
    }
    g_gl.ShaderSource(shader, 1, &source, nullptr);
    g_gl.CompileShader(shader);

    GLint status = GL_FALSE;
    g_gl.GetShaderiv(shader, GL_COMPILE_STATUS, &status);
    if (status == GL_FALSE) {
        char logBuf[1024]{};
        g_gl.GetShaderInfoLog(shader, sizeof(logBuf), nullptr, logBuf);
        OutputDebugStringA("[AstraNativeCore] GLSL compile error: ");
        OutputDebugStringA(logBuf);
        OutputDebugStringA("\n");
        g_gl.DeleteShader(shader);
        return 0;
    }
    return shader;
}

GLuint linkProgram(const char* vsSrc, const char* fsSrc) {
    GLuint vs = compileShader(GL_VERTEX_SHADER, vsSrc);
    if (vs == 0) return 0;

    GLuint fs = compileShader(GL_FRAGMENT_SHADER, fsSrc);
    if (fs == 0) {
        g_gl.DeleteShader(vs);
        return 0;
    }

    GLuint prog = g_gl.CreateProgram();
    g_gl.AttachShader(prog, vs);
    g_gl.AttachShader(prog, fs);
    g_gl.LinkProgram(prog);

    g_gl.DeleteShader(vs);
    g_gl.DeleteShader(fs);

    GLint linked = GL_FALSE;
    g_gl.GetProgramiv(prog, GL_LINK_STATUS, &linked);
    if (linked == GL_FALSE) {
        char logBuf[1024]{};
        g_gl.GetProgramInfoLog(prog, sizeof(logBuf), nullptr, logBuf);
        OutputDebugStringA("[AstraNativeCore] GLSL link error: ");
        OutputDebugStringA(logBuf);
        OutputDebugStringA("\n");
        g_gl.DeleteProgram(prog);
        return 0;
    }
    return prog;
}

inline void writeVec4(float* dst, int vecIndex, float x, float y, float z, float w) noexcept {
    const int base = vecIndex * 4;
    dst[base + 0] = x;
    dst[base + 1] = y;
    dst[base + 2] = z;
    dst[base + 3] = w;
}

void populateUniformData(const AstraTitleScreenStatePOD& s, uint32_t bgTextureId, const GLint viewport[4]) noexcept {
    const float guiW = std::max(1.0f, s.screenWidth);
    const float guiH = std::max(1.0f, s.screenHeight);
    const float fbW  = static_cast<float>(std::max(1, viewport[2]));
    const float fbH  = static_cast<float>(std::max(1, viewport[3]));

    // [0] uScreen
    writeVec4(g_engine.uboData, 0, guiW, guiH, fbW, fbH);
    // [1] uTimeMouse
    writeVec4(g_engine.uboData, 1, s.totalTime, s.deltaTime, s.mouseX, s.mouseY);
    // [2] uFlags
    writeVec4(g_engine.uboData, 2,
              bgTextureId != 0 ? 1.0f : 0.0f,
              std::clamp(s.musicProgress, 0.0f, 1.0f),
              s.musicPlaying != 0 ? 1.0f : 0.0f,
              static_cast<float>(s.currentTrack));

    if (g_engine.hasLayoutOverride) {
        const auto& L = g_engine.layoutOverride;
        writeVec4(g_engine.uboData, 3, static_cast<float>(s.hoveredDockIndex), s.profileMenuOpen != 0 ? 1.0f : 0.0f, L.dotsHover, L.dropdownAnim);
        writeVec4(g_engine.uboData, 4, L.spRect[0], L.spRect[1], L.spRect[2], L.spRect[3]);
        writeVec4(g_engine.uboData, 5, L.mpRect[0], L.mpRect[1], L.mpRect[2], L.mpRect[3]);
        writeVec4(g_engine.uboData, 6, L.continueRect[0], L.continueRect[1], L.continueRect[2], L.continueRect[3]);
        writeVec4(g_engine.uboData, 7, L.musicRect[0], L.musicRect[1], L.musicRect[2], L.musicRect[3]);
        writeVec4(g_engine.uboData, 8, L.profileRect[0], L.profileRect[1], L.profileRect[2], L.profileRect[3]);
        writeVec4(g_engine.uboData, 9, L.dropdownRect[0], L.dropdownRect[1], L.dropdownRect[2], L.dropdownRect[3]);
        writeVec4(g_engine.uboData, 10, L.dockRect[0], L.dockRect[1], L.dockRect[2], L.dockRect[3]);
        writeVec4(g_engine.uboData, 11, L.tooltipRect[0], L.tooltipRect[1], L.tooltipRect[2], L.tooltipRect[3]);
        writeVec4(g_engine.uboData, 12, s.spHover, s.mpHover, s.continueHover, s.continuePlayHover);
        writeVec4(g_engine.uboData, 13, s.sliderHover, s.mediaPrevHover, s.mediaPlayHover, s.mediaNextHover);
        writeVec4(g_engine.uboData, 14, s.profileHover, L.statusDotX, L.dotsCenterX, 0.0f);
        writeVec4(g_engine.uboData, 15, s.dockHover[0], s.dockHover[1], s.dockHover[2], s.dockHover[3]);
        writeVec4(g_engine.uboData, 16, L.dockIconX[0], L.dockIconX[1], L.dockIconX[2], L.dockIconX[3]);
        return;
    }

    // Automatyczny, responsywny układ zgodny ze specyfikacją Apple iOS Lockscreen
    const float centerX = guiW * 0.5f;
    const bool compact  = guiH < 350.0f;

    const float cardW      = std::min(280.0f, guiW - 36.0f);
    const float pillGap    = 10.0f;
    const float pillW      = (cardW - pillGap) * 0.5f; // ~135px
    const float pillH      = compact ? 29.0f : 32.0f;
    const float continueH  = compact ? 44.0f : 48.0f;
    const float musicH     = compact ? 66.0f : 72.0f;
    const float stackGap   = compact ? 9.0f : 12.0f;
    const float clockBlock = compact ? 42.0f : 50.0f;

    const float totalHeroH = clockBlock + stackGap + pillH + stackGap + continueH + stackGap + musicH;
    const float startY     = 42.0f + std::max(0.0f, ((guiH - 106.0f) - totalHeroH) * 0.46f);

    // Pigułki Singleplayer & Multiplayer (uniesienie -3px i powiększenie o 2% na hover)
    const float basePillY = startY + clockBlock + stackGap;
    const float baseSpX   = centerX - cardW * 0.5f;
    const float baseMpX   = baseSpX + pillW + pillGap;

    const float spScale = 1.0f + 0.02f * s.spHover;
    const float spW     = pillW * spScale;
    const float spH     = pillH * spScale;
    const float spX     = baseSpX - (spW - pillW) * 0.5f;
    const float spY     = basePillY - 3.0f * s.spHover - (spH - pillH) * 0.5f;

    const float mpScale = 1.0f + 0.02f * s.mpHover;
    const float mpW     = pillW * mpScale;
    const float mpH     = pillH * mpScale;
    const float mpX     = baseMpX - (mpW - pillW) * 0.5f;
    const float mpY     = basePillY - 3.0f * s.mpHover - (mpH - pillH) * 0.5f;

    // Karta Continue (280x48)
    const float contY = basePillY + pillH + stackGap - 1.5f * s.continueHover;

    // Karta Music (280x72)
    const float musicY = (basePillY + pillH + stackGap) + continueH + stackGap;

    // Górny pasek profilu (Top-Right)
    const float profW = 148.0f;
    const float profH = 30.0f;
    const float profX = guiW - 14.0f - profW;
    const float profY = 14.0f;

    const float dropW = 156.0f;
    const float dropH = 72.0f;
    const float dropX = guiW - 14.0f - dropW;
    const float dropY = profY + profH + 6.0f;

    // Dolny pływający dock (R = H/2)
    const float dockW = 180.0f;
    const float dockH = 38.0f;
    const float dockX = centerX - dockW * 0.5f;
    const float dockY = guiH - 22.0f - dockH;

    const float spacing = dockW / 4.0f;
    const float ix0 = dockX + spacing * 0.5f;
    const float ix1 = dockX + spacing * 1.5f;
    const float ix2 = dockX + spacing * 2.5f;
    const float ix3 = dockX + spacing * 3.5f;

    float tipX = -200.0f, tipY = -200.0f, tipW = 64.0f, tipH = 18.0f;
    if (s.hoveredDockIndex >= 0 && s.hoveredDockIndex < 4) {
        const float activeIconX = dockX + spacing * (static_cast<float>(s.hoveredDockIndex) + 0.5f);
        const float hVal = s.dockHover[s.hoveredDockIndex];
        tipW = 72.0f;
        tipX = activeIconX - tipW * 0.5f;
        tipY = dockY - tipH - 6.0f + (1.0f - hVal) * 4.0f;
    }

    writeVec4(g_engine.uboData, 3, static_cast<float>(s.hoveredDockIndex), s.profileMenuOpen != 0 ? 1.0f : 0.0f, s.profileHover * 0.5f, s.profileMenuOpen != 0 ? 1.0f : 0.0f);
    writeVec4(g_engine.uboData, 4, spX, spY, spW, spH);
    writeVec4(g_engine.uboData, 5, mpX, mpY, mpW, mpH);
    writeVec4(g_engine.uboData, 6, baseSpX, contY, cardW, continueH);
    writeVec4(g_engine.uboData, 7, 0.0f, 0.0f, 0.0f, 0.0f);
    writeVec4(g_engine.uboData, 8, profX, profY, profW, profH);
    writeVec4(g_engine.uboData, 9, dropX, dropY, dropW, dropH);
    writeVec4(g_engine.uboData, 10, dockX, dockY, dockW, dockH);
    writeVec4(g_engine.uboData, 11, tipX, tipY, tipW, tipH);
    writeVec4(g_engine.uboData, 12, s.spHover, s.mpHover, s.continueHover, s.continuePlayHover);
    writeVec4(g_engine.uboData, 13, s.sliderHover, s.mediaPrevHover, s.mediaPlayHover, s.mediaNextHover);
    writeVec4(g_engine.uboData, 14, s.profileHover, profX + profW - 64.0f, profX + profW - 14.0f, 0.0f);
    writeVec4(g_engine.uboData, 15, s.dockHover[0], s.dockHover[1], s.dockHover[2], s.dockHover[3]);
    writeVec4(g_engine.uboData, 16, ix0, ix1, ix2, ix3);
}

} // namespace

// ============================================================================
// EKSPORTOWANE API C ABI (DLL EXPORTS)
// ============================================================================


} // namespace astra_title

extern "C" {
using namespace astra_title;

ASTRA_API bool astra_titlescreen_init() {
    if (g_engine.initialized) {
        return true;
    }
    if (!ensureGlFunctionsLoaded()) {
        return false;
    }

    ScopedTitleGlStateGuard guard;

    // 1. Kompilacja głównego programu z UBO lub bezpiecznego fallbacku
    if (g_gl.supportsUbo) {
        std::string uboFs = std::string(LIQUID_GLASS_TITLESCREEN_UBO_FS_1) + LIQUID_GLASS_TITLESCREEN_UBO_FS_2;
    g_engine.programId = linkProgram(FULLSCREEN_QUAD_VS, uboFs.c_str());
        if (g_engine.programId != 0) {
            const GLuint blockIdx = g_gl.GetUniformBlockIndex(g_engine.programId, "TitleScreenBlock");
            if (blockIdx != GL_INVALID_INDEX) {
                g_gl.UniformBlockBinding(g_engine.programId, blockIdx, 0);
                g_engine.usingUboPath = true;
            } else {
                g_gl.DeleteProgram(g_engine.programId);
                g_engine.programId = 0;
            }
        }
    }

    if (g_engine.programId == 0) {
        g_engine.usingUboPath = false;
        g_engine.programId = linkProgram(FULLSCREEN_QUAD_VS, LIQUID_GLASS_TITLESCREEN_FALLBACK_FS);
        if (g_engine.programId == 0) {
            return false;
        }
        g_engine.uFallbackDataLoc = g_gl.GetUniformLocation(g_engine.programId, "uData");
    }

    g_engine.uBgTextureLoc = g_gl.GetUniformLocation(g_engine.programId, "uBgTexture");

    // 2. Prealokacja statycznego VAO / VBO dla pełnoekranowego quada NDC (-1..1)
    static constexpr float kQuadVertices[12] = {
        -1.0f, -1.0f,
         1.0f, -1.0f,
         1.0f,  1.0f,
        -1.0f, -1.0f,
         1.0f,  1.0f,
        -1.0f,  1.0f
    };

    g_gl.GenVertexArrays(1, &g_engine.vaoId);
    g_gl.GenBuffers(1, &g_engine.vboId);

    g_gl.BindVertexArray(g_engine.vaoId);
    g_gl.BindBuffer(GL_ARRAY_BUFFER, g_engine.vboId);
    g_gl.BufferData(GL_ARRAY_BUFFER, sizeof(kQuadVertices), kQuadVertices, GL_STATIC_DRAW);
    g_gl.EnableVertexAttribArray(0);
    g_gl.VertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, 2 * sizeof(float), nullptr);

    // 3. Prealokacja Uniform Buffer Object (272 bajty)
    if (g_engine.usingUboPath) {
        g_gl.GenBuffers(1, &g_engine.uboId);
        g_gl.BindBuffer(GL_UNIFORM_BUFFER, g_engine.uboId);
        g_gl.BufferData(GL_UNIFORM_BUFFER, sizeof(g_engine.uboData), nullptr, GL_DYNAMIC_DRAW);
    }

    g_engine.initialized = true;
    return true;
}

ASTRA_API void astra_titlescreen_shutdown() {
    if (!g_engine.initialized) {
        return;
    }

    ScopedTitleGlStateGuard guard;

    if (g_engine.uboId != 0) {
        g_gl.DeleteBuffers(1, &g_engine.uboId);
        g_engine.uboId = 0;
    }
    if (g_engine.vboId != 0) {
        g_gl.DeleteBuffers(1, &g_engine.vboId);
        g_engine.vboId = 0;
    }
    if (g_engine.vaoId != 0) {
        g_gl.DeleteVertexArrays(1, &g_engine.vaoId);
        g_engine.vaoId = 0;
    }
    if (g_engine.programId != 0) {
        g_gl.DeleteProgram(g_engine.programId);
        g_engine.programId = 0;
    }

    g_engine.hasLayoutOverride = false;
    g_engine.initialized = false;
}

ASTRA_API void astra_titlescreen_set_layout(const AstraTitleScreenLayoutPOD* layout) {
    if (layout == nullptr) {
        g_engine.hasLayoutOverride = false;
        return;
    }
    std::memcpy(&g_engine.layoutOverride, layout, sizeof(AstraTitleScreenLayoutPOD));
    g_engine.hasLayoutOverride = true;
}

ASTRA_API void astra_titlescreen_render(const AstraTitleScreenStatePOD* state, uint32_t bgTextureId) {
    if (state == nullptr) {
        return;
    }
    if (!g_engine.initialized && !astra_titlescreen_init()) {
        return;
    }

    ScopedTitleGlStateGuard guard;

    GLint viewport[4]{};
    glGetIntegerv(GL_VIEWPORT, viewport);

    // Przygotowanie czystego stanu potoku dla pełnoekranowego przebiegu SDF
    glDisable(GL_DEPTH_TEST);
    glDisable(GL_CULL_FACE);
    glDisable(GL_SCISSOR_TEST);
    glEnable(GL_BLEND);
    g_gl.BlendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ONE_MINUS_SRC_ALPHA);

    g_gl.UseProgram(g_engine.programId);

    g_gl.ActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, static_cast<GLuint>(bgTextureId));
    if (g_engine.uBgTextureLoc >= 0) {
        g_gl.Uniform1i(g_engine.uBgTextureLoc, 0);
    }

    populateUniformData(*state, bgTextureId, viewport);

    if (g_engine.usingUboPath) {
        g_gl.BindBuffer(GL_UNIFORM_BUFFER, g_engine.uboId);
        g_gl.BufferSubData(GL_UNIFORM_BUFFER, 0, sizeof(g_engine.uboData), g_engine.uboData);
        g_gl.BindBufferBase(GL_UNIFORM_BUFFER, 0, g_engine.uboId);
    } else if (g_engine.uFallbackDataLoc >= 0) {
        g_gl.Uniform4fv(g_engine.uFallbackDataLoc, 17, g_engine.uboData);
    }

    g_gl.BindVertexArray(g_engine.vaoId);
    glDrawArrays(GL_TRIANGLES, 0, 6);
}


#include <jni.h>

JNIEXPORT jboolean JNICALL Java_com_astra_revolution_nativebridge_AstraNativeTitleRenderer_nInit(JNIEnv*, jclass) {
    return astra_titlescreen_init() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL Java_com_astra_revolution_nativebridge_AstraNativeTitleRenderer_nShutdown(JNIEnv*, jclass) {
    astra_titlescreen_shutdown();
}

JNIEXPORT void JNICALL Java_com_astra_revolution_nativebridge_AstraNativeTitleRenderer_nSyncResponsiveLayout(
    JNIEnv*, jclass,
    jfloat spX, jfloat spY, jfloat spW, jfloat spH,
    jfloat mpX, jfloat mpY, jfloat mpW, jfloat mpH,
    jfloat contX, jfloat contY, jfloat contW, jfloat contH,
    jfloat musX, jfloat musY, jfloat musW, jfloat musH,
    jfloat profX, jfloat profY, jfloat profW, jfloat profH,
    jfloat dropX, jfloat dropY, jfloat dropW, jfloat dropH,
    jfloat dockX, jfloat dockY, jfloat dockW, jfloat dockH,
    jfloat tipX, jfloat tipY, jfloat tipW, jfloat tipH,
    jfloat dock0X, jfloat dock1X, jfloat dock2X, jfloat dock3X,
    jfloat statusDotX, jfloat dotsCenterX, jfloat dotsHover, jfloat dropAnim
) {
    AstraTitleScreenLayoutPOD layout{};
    layout.spRect[0] = spX; layout.spRect[1] = spY; layout.spRect[2] = spW; layout.spRect[3] = spH;
    layout.mpRect[0] = mpX; layout.mpRect[1] = mpY; layout.mpRect[2] = mpW; layout.mpRect[3] = mpH;
    layout.continueRect[0] = contX; layout.continueRect[1] = contY; layout.continueRect[2] = contW; layout.continueRect[3] = contH;
    layout.musicRect[0] = musX; layout.musicRect[1] = musY; layout.musicRect[2] = musW; layout.musicRect[3] = musH;
    layout.profileRect[0] = profX; layout.profileRect[1] = profY; layout.profileRect[2] = profW; layout.profileRect[3] = profH;
    layout.dropdownRect[0] = dropX; layout.dropdownRect[1] = dropY; layout.dropdownRect[2] = dropW; layout.dropdownRect[3] = dropH;
    layout.dockRect[0] = dockX; layout.dockRect[1] = dockY; layout.dockRect[2] = dockW; layout.dockRect[3] = dockH;
    layout.tooltipRect[0] = tipX; layout.tooltipRect[1] = tipY; layout.tooltipRect[2] = tipW; layout.tooltipRect[3] = tipH;
    layout.dockIconX[0] = dock0X; layout.dockIconX[1] = dock1X; layout.dockIconX[2] = dock2X; layout.dockIconX[3] = dock3X;
    layout.statusDotX = statusDotX;
    layout.dotsCenterX = dotsCenterX;
    layout.dotsHover = dotsHover;
    layout.dropdownAnim = dropAnim;
    astra_titlescreen_set_layout(&layout);
}

JNIEXPORT void JNICALL Java_com_astra_revolution_nativebridge_AstraNativeTitleRenderer_nRenderFrame(
    JNIEnv*, jclass,
    jfloat screenW, jfloat screenH,
    jfloat mouseX, jfloat mouseY,
    jfloat totalTime, jfloat deltaTime,
    jfloat spHover, jfloat mpHover,
    jfloat contHover, jfloat contPlayHover,
    jfloat sliderHover,
    jfloat prevHover, jfloat playHover, jfloat nextHover,
    jfloat profileHover,
    jfloat dock0, jfloat dock1, jfloat dock2, jfloat dock3,
    jfloat musicProgress, jboolean musicPlaying, jint currentTrack,
    jint hoveredDockIndex, jboolean profileMenuOpen,
    jint bgTextureId
) {
    AstraTitleScreenStatePOD state{};
    state.screenWidth = screenW;
    state.screenHeight = screenH;
    state.mouseX = mouseX;
    state.mouseY = mouseY;
    state.totalTime = totalTime;
    state.deltaTime = deltaTime;
    state.spHover = spHover;
    state.mpHover = mpHover;
    state.continueHover = contHover;
    state.continuePlayHover = contPlayHover;
    state.sliderHover = sliderHover;
    state.mediaPrevHover = prevHover;
    state.mediaPlayHover = playHover;
    state.mediaNextHover = nextHover;
    state.profileHover = profileHover;
    state.dockHover[0] = dock0;
    state.dockHover[1] = dock1;
    state.dockHover[2] = dock2;
    state.dockHover[3] = dock3;
    state.musicProgress = musicProgress;
    state.musicPlaying = (musicPlaying == JNI_TRUE) ? 1 : 0;
    state.currentTrack = currentTrack;
    state.hoveredDockIndex = hoveredDockIndex;
    state.profileMenuOpen = (profileMenuOpen == JNI_TRUE) ? 1 : 0;
    astra_titlescreen_render(&state, static_cast<uint32_t>(bgTextureId));
}


// ============================================================================
// 3. DETEKCJA SPOTIFY (WINDOWS USER32 ENUMWINDOWS)
// ============================================================================

struct SpotifyEnumData {
    bool foundPlaying;
    char artist[128];
    char title[128];
};

static BOOL CALLBACK EnumWindowsSpotifyCallback(HWND hwnd, LPARAM lParam) {
    if (!IsWindowVisible(hwnd)) return TRUE;

    char cls[64]{};
    if (GetClassNameA(hwnd, cls, sizeof(cls)) == 0) return TRUE;
    if (strcmp(cls, "Chrome_WidgetWin_0") != 0) return TRUE;

    char titleBuf[256]{};
    int len = GetWindowTextA(hwnd, titleBuf, sizeof(titleBuf));
    if (len > 0) {
        if (strcmp(titleBuf, "Spotify") == 0 ||
            strcmp(titleBuf, "Spotify Free") == 0 ||
            strcmp(titleBuf, "Spotify Premium") == 0) {
            return TRUE;
        }

        const char* sep = strstr(titleBuf, " - ");
        if (sep != nullptr && sep > titleBuf) {
            SpotifyEnumData* data = reinterpret_cast<SpotifyEnumData*>(lParam);
            data->foundPlaying = true;

            size_t artistLen = static_cast<size_t>(sep - titleBuf);
            if (artistLen >= sizeof(data->artist)) artistLen = sizeof(data->artist) - 1;
            memcpy(data->artist, titleBuf, artistLen);
            data->artist[artistLen] = 0;

            const char* titleStart = sep + 3;
            strncpy(data->title, titleStart, sizeof(data->title) - 1);
            data->title[sizeof(data->title) - 1] = 0;
            return FALSE;
        }
    }
    return TRUE;
}

ASTRA_API bool astra_spotify_poll(char* outArtist, int maxArtist, char* outTitle, int maxTitle) {
    SpotifyEnumData data{};
    EnumWindows(EnumWindowsSpotifyCallback, reinterpret_cast<LPARAM>(&data));
    if (data.foundPlaying) {
        if (outArtist && maxArtist > 0) {
            strncpy(outArtist, data.artist, maxArtist - 1);
            outArtist[maxArtist - 1] = 0;
        }
        if (outTitle && maxTitle > 0) {
            strncpy(outTitle, data.title, maxTitle - 1);
            outTitle[maxTitle - 1] = 0;
        }
        return true;
    }
    return false;
}

JNIEXPORT jobjectArray JNICALL Java_com_astra_revolution_nativebridge_AstraNativeTitleRenderer_nPollSpotify(JNIEnv* env, jclass) {
    char artist[128]{};
    char title[128]{};
    if (astra_spotify_poll(artist, sizeof(artist), title, sizeof(title))) {
        jclass strCls = env->FindClass("java/lang/String");
        if (strCls == nullptr) return nullptr;
        jobjectArray arr = env->NewObjectArray(2, strCls, nullptr);
        env->SetObjectArrayElement(arr, 0, env->NewStringUTF(artist));
        env->SetObjectArrayElement(arr, 1, env->NewStringUTF(title));
        return arr;
    }
    return nullptr;
}


// ============================================================================
// 4. ASTRA SPRUCE 2X2 TREE FARM 3D VISUALS (AVX2 + ADDITIVE BLOOM ZERO-GC)
// ============================================================================

#include <immintrin.h>
#include <cmath>
#include <algorithm>

namespace astra_spruce {
    constexpr float PI = 3.14159265358979323846f;
    constexpr float TAU = 6.28318530717958647692f;

    // Kolory bazowe (0x00RRGGBB)
    constexpr uint32_t RGB_LILAC      = 0xC77DFFu; // #C77DFF (Góra pnia)
    constexpr uint32_t RGB_CYBER_CYAN = 0x00F0FFu; // #00F0FF (Dół pnia / Replant)
    constexpr uint32_t RGB_EMERALD    = 0x00FF88u; // #00FF88 (Liść / Gotowa sadzonka)
    constexpr uint32_t RGB_CYBER_GOLD = 0xFFD700u; // #FFD700 (Pień / Bone Meal)

    __forceinline uint32_t packARGB(uint8_t a, uint32_t rgb) noexcept {
        return (static_cast<uint32_t>(a) << 24) | (rgb & 0x00FFFFFFu);
    }

    __forceinline uint32_t lerpColorARGB(uint32_t rgbA, uint32_t rgbB, float t, uint8_t alpha) noexcept {
        t = std::clamp(t, 0.0f, 1.0f);
        uint32_t rA = (rgbA >> 16) & 0xFFu, gA = (rgbA >> 8) & 0xFFu, bA = rgbA & 0xFFu;
        uint32_t rB = (rgbB >> 16) & 0xFFu, gB = (rgbB >> 8) & 0xFFu, bB = rgbB & 0xFFu;
        uint32_t r = static_cast<uint32_t>(rA + (static_cast<float>(rB) - static_cast<float>(rA)) * t);
        uint32_t g = static_cast<uint32_t>(gA + (static_cast<float>(gB) - static_cast<float>(gA)) * t);
        uint32_t b = static_cast<uint32_t>(bA + (static_cast<float>(bB) - static_cast<float>(bA)) * t);
        return (static_cast<uint32_t>(alpha) << 24) | (r << 16) | (g << 8) | b;
    }

    __forceinline void emitQuad(
        AstraVertex* buf, int32_t& idx, int32_t maxVerts,
        float x0, float y0, float z0, uint32_t c0,
        float x1, float y1, float z1, uint32_t c1,
        float x2, float y2, float z2, uint32_t c2,
        float x3, float y3, float z3, uint32_t c3
    ) noexcept {
        if (idx + 4 > maxVerts) return;
        buf[idx + 0] = { x0, y0, z0, c0 };
        buf[idx + 1] = { x1, y1, z1, c1 };
        buf[idx + 2] = { x2, y2, z2, c2 };
        buf[idx + 3] = { x3, y3, z3, c3 };
        idx += 4;
    }

    __forceinline void emitLine(
        AstraVertex* buf, int32_t& idx, int32_t maxVerts,
        float x0, float y0, float z0, uint32_t c0,
        float x1, float y1, float z1, uint32_t c1
    ) noexcept {
        if (idx + 2 > maxVerts) return;
        buf[idx + 0] = { x0, y0, z0, c0 };
        buf[idx + 1] = { x1, y1, z1, c1 };
        idx += 2;
    }

    // Rysuje prostopadłościan z gradientem góra-dół (ściany boczne + podstawa + szczyt)
    void emitGradientBoxQuads(
        AstraVertex* buf, int32_t& idx, int32_t maxVerts,
        float minX, float minY, float minZ,
        float maxX, float maxY, float maxZ,
        uint32_t bottomColor, uint32_t topColor
    ) noexcept {
        // Ściana North (minZ)
        emitQuad(buf, idx, maxVerts,
            minX, minY, minZ, bottomColor,
            minX, maxY, minZ, topColor,
            maxX, maxY, minZ, topColor,
            maxX, minY, minZ, bottomColor);
        // Ściana South (maxZ)
        emitQuad(buf, idx, maxVerts,
            maxX, minY, maxZ, bottomColor,
            maxX, maxY, maxZ, topColor,
            minX, maxY, maxZ, topColor,
            minX, minY, maxZ, bottomColor);
        // Ściana West (minX)
        emitQuad(buf, idx, maxVerts,
            minX, minY, maxZ, bottomColor,
            minX, maxY, maxZ, topColor,
            minX, maxY, minZ, topColor,
            minX, minY, minZ, bottomColor);
        // Ściana East (maxX)
        emitQuad(buf, idx, maxVerts,
            maxX, minY, minZ, bottomColor,
            maxX, maxY, minZ, topColor,
            maxX, maxY, maxZ, topColor,
            maxX, minY, maxZ, bottomColor);
        // Góra (maxY)
        emitQuad(buf, idx, maxVerts,
            minX, maxY, minZ, topColor,
            minX, maxY, maxZ, topColor,
            maxX, maxY, maxZ, topColor,
            maxX, maxY, minZ, topColor);
        // Dół (minY)
        emitQuad(buf, idx, maxVerts,
            minX, minY, minZ, bottomColor,
            maxX, minY, minZ, bottomColor,
            maxX, minY, maxZ, bottomColor,
            minX, minY, maxZ, bottomColor);
    }

    // Rysuje 12 krawędzi prostopadłościanu z gradientem pionowym
    void emitGradientBoxLines(
        AstraVertex* buf, int32_t& idx, int32_t maxVerts,
        float minX, float minY, float minZ,
        float maxX, float maxY, float maxZ,
        uint32_t bottomColor, uint32_t topColor
    ) noexcept {
        // Dolna podstawa
        emitLine(buf, idx, maxVerts, minX, minY, minZ, bottomColor, maxX, minY, minZ, bottomColor);
        emitLine(buf, idx, maxVerts, maxX, minY, minZ, bottomColor, maxX, minY, maxZ, bottomColor);
        emitLine(buf, idx, maxVerts, maxX, minY, maxZ, bottomColor, minX, minY, maxZ, bottomColor);
        emitLine(buf, idx, maxVerts, minX, minY, maxZ, bottomColor, minX, minY, minZ, bottomColor);

        // Górna podstawa
        emitLine(buf, idx, maxVerts, minX, maxY, minZ, topColor, maxX, maxY, minZ, topColor);
        emitLine(buf, idx, maxVerts, maxX, maxY, minZ, topColor, maxX, maxY, maxZ, topColor);
        emitLine(buf, idx, maxVerts, maxX, maxY, maxZ, topColor, minX, maxY, maxZ, topColor);
        emitLine(buf, idx, maxVerts, minX, maxY, maxZ, topColor, minX, maxY, minZ, topColor);

        // 4 kolumny pionowe
        emitLine(buf, idx, maxVerts, minX, minY, minZ, bottomColor, minX, maxY, minZ, topColor);
        emitLine(buf, idx, maxVerts, maxX, minY, minZ, bottomColor, maxX, maxY, minZ, topColor);
        emitLine(buf, idx, maxVerts, maxX, minY, maxZ, bottomColor, maxX, maxY, maxZ, topColor);
        emitLine(buf, idx, maxVerts, minX, minY, maxZ, bottomColor, minX, maxY, maxZ, topColor);
    }
} // namespace astra_spruce

extern "C" {

ASTRA_API void astra_build_spruce_visuals(
    const AstraSpruceInput* in,
    AstraVertex* solidQuadBuf, int32_t maxSolidQuads,
    AstraVertex* bloomQuadBuf, int32_t maxBloomQuads,
    AstraVertex* bloomLineBuf, int32_t maxBloomLines,
    AstraSpruceOutputCounts* outCounts
) {
    using namespace astra_spruce;

    if (in == nullptr || outCounts == nullptr) return;

    int32_t solidQ = 0;
    int32_t bloomQ = 0;
    int32_t bloomL = 0;

    const float t = in->timeSeconds;
    // Harmoniczny puls 0.0 .. 1.0
    const float harmonic = 0.5f + 0.5f * std::sin(t * 3.2f);
    const float fastPulse = 0.5f + 0.5f * std::sin(t * 7.5f);

    // -------------------------------------------------------------------------
    // 1. HOLOGRAFICZNA KOLUMNA PNIA 2X2 (Additive Bloom + Gradient Lilac/Cyan)
    // -------------------------------------------------------------------------
    if (in->hasTrunk) {
        const float minX = static_cast<float>(in->trunkMinX - in->camX);
        const float minZ = static_cast<float>(in->trunkMinZ - in->camZ);
        const float maxX = minX + 2.0f;
        const float maxZ = minZ + 2.0f;

        const float minY = static_cast<float>((in->baseSoilY + 1) - in->camY);
        const float maxY = static_cast<float>((in->topLogY + 1) - in->camY);

        // Bazowe półprzezroczyste ściany kolumny
        uint8_t baseBotAlpha = static_cast<uint8_t>(22.0f + 14.0f * harmonic);
        uint8_t baseTopAlpha = static_cast<uint8_t>(26.0f + 16.0f * harmonic);
        emitGradientBoxQuads(
            solidQuadBuf, solidQ, maxSolidQuads,
            minX - 0.008f, minY, minZ - 0.008f,
            maxX + 0.008f, maxY, maxZ + 0.008f,
            packARGB(baseBotAlpha, RGB_CYBER_CYAN),
            packARGB(baseTopAlpha, RGB_LILAC)
        );

        // 3 powłoki wolumetrycznego Additive Bloom wokół pnia 2x2
        const float shellOffsets[3] = { 0.015f, 0.045f, 0.085f };
        const uint8_t shellAlphas[3] = {
            static_cast<uint8_t>(38.0f + 22.0f * harmonic),
            static_cast<uint8_t>(20.0f + 12.0f * harmonic),
            static_cast<uint8_t>(10.0f + 8.0f * harmonic)
        };

        for (int s = 0; s < 3; ++s) {
            float off = shellOffsets[s] + 0.012f * harmonic;
            uint32_t cBot = packARGB(shellAlphas[s], RGB_CYBER_CYAN);
            uint32_t cTop = packARGB(shellAlphas[s], RGB_LILAC);
            emitGradientBoxQuads(
                bloomQuadBuf, bloomQ, maxBloomQuads,
                minX - off, minY, minZ - off,
                maxX + off, maxY, maxZ + off,
                cBot, cTop
            );
        }

        // Krawędzie główne kolumny + pierścienie skanujące co 4 bloki wysokości
        uint8_t lineAlpha = static_cast<uint8_t>(190.0f + 65.0f * harmonic);
        emitGradientBoxLines(
            bloomLineBuf, bloomL, maxBloomLines,
            minX - 0.012f, minY, minZ - 0.012f,
            maxX + 0.012f, maxY, maxZ + 0.012f,
            packARGB(lineAlpha, RGB_CYBER_CYAN),
            packARGB(lineAlpha, RGB_LILAC)
        );

        // Skanująca fala energetyczna wędrująca od dołu do góry pnia
        float height = std::max(1.0f, maxY - minY);
        float scanPhase = std::fmod(t * 0.65f, 1.0f);
        float scanY = minY + scanPhase * height;
        uint32_t scanCol = lerpColorARGB(RGB_CYBER_CYAN, RGB_LILAC, scanPhase, 235);
        emitGradientBoxLines(
            bloomLineBuf, bloomL, maxBloomLines,
            minX - 0.03f, scanY - 0.04f, minZ - 0.03f,
            maxX + 0.03f, scanY + 0.04f, maxZ + 0.03f,
            scanCol, scanCol
        );

        // ---------------------------------------------------------------------
        // 2. WSKAŹNIK 4 PÓL SADZENIA (REPLANT PODS 2X2)
        // ---------------------------------------------------------------------
        const float podY = minY + 0.025f;
        // Wektoryzacja AVX2 do obliczenia ośmiokątnych pierścieni dla każdego z 4 pól
        alignas(32) static const float cosOct[8] = {
            1.0f, 0.70710678f, 0.0f, -0.70710678f, -1.0f, -0.70710678f, 0.0f, 0.70710678f
        };
        alignas(32) static const float sinOct[8] = {
            0.0f, 0.70710678f, 1.0f, 0.70710678f, 0.0f, -0.70710678f, -1.0f, -0.70710678f
        };
        const __m256 vCos = _mm256_load_ps(cosOct);
        const __m256 vSin = _mm256_load_ps(sinOct);

        for (int p = 0; p < 4; ++p) {
            int dx = p & 1;
            int dz = (p >> 1) & 1;
            float cx = minX + static_cast<float>(dx) + 0.5f;
            float cz = minZ + static_cast<float>(dz) + 0.5f;

            int32_t state = in->podStates[p];
            uint32_t podRgb = (state == 2) ? RGB_CYBER_GOLD : ((state == 1) ? RGB_EMERALD : RGB_CYBER_CYAN);
            float radius = (state == 2) ? (0.34f + 0.08f * fastPulse) : (0.36f + 0.03f * harmonic);

            __m256 vCx = _mm256_set1_ps(cx);
            __m256 vCz = _mm256_set1_ps(cz);
            __m256 vRad = _mm256_set1_ps(radius);
            __m256 vInnerRad = _mm256_set1_ps(radius * 0.68f);

            alignas(32) float pxOuter[8], pzOuter[8], pxInner[8], pzInner[8];
            _mm256_store_ps(pxOuter, _mm256_fmadd_ps(vCos, vRad, vCx));
            _mm256_store_ps(pzOuter, _mm256_fmadd_ps(vSin, vRad, vCz));
            _mm256_store_ps(pxInner, _mm256_fmadd_ps(vCos, vInnerRad, vCx));
            _mm256_store_ps(pzInner, _mm256_fmadd_ps(vSin, vInnerRad, vCz));

            uint32_t ringLineCol = packARGB(220, podRgb);
            uint32_t ringFillOuter = packARGB(75, podRgb);
            uint32_t ringFillInner = packARGB(15, podRgb);

            for (int k = 0; k < 8; ++k) {
                int next = (k + 1) & 7;
                // Obrys ośmiokąta
                emitLine(bloomLineBuf, bloomL, maxBloomLines,
                    pxOuter[k], podY, pzOuter[k], ringLineCol,
                    pxOuter[next], podY, pzOuter[next], ringLineCol);

                // Wstęga bloom pierścienia
                emitQuad(bloomQuadBuf, bloomQ, maxBloomQuads,
                    pxInner[k], podY, pzInner[k], ringFillInner,
                    pxOuter[k], podY, pzOuter[k], ringFillOuter,
                    pxOuter[next], podY, pzOuter[next], ringFillOuter,
                    pxInner[next], podY, pzInner[next], ringFillInner);
            }

            // Jeśli pędzenie mączką kostną (state == 2), dodaj wznoszący się złoty pierścień
            if (state == 2) {
                float lift = std::fmod(t * 2.2f + p * 0.25f, 1.0f);
                float liftY = podY + lift * 0.85f;
                uint8_t liftAlpha = static_cast<uint8_t>((1.0f - lift) * 210.0f);
                uint32_t liftCol = packARGB(liftAlpha, RGB_CYBER_GOLD);
                for (int k = 0; k < 8; ++k) {
                    int next = (k + 1) & 7;
                    emitLine(bloomLineBuf, bloomL, maxBloomLines,
                        pxOuter[k], liftY, pzOuter[k], liftCol,
                        pxOuter[next], liftY, pzOuter[next], liftCol);
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // 3. PRECYZYJNY OBRYS NISZCZONEGO BLOKU (LIŚĆ vs PIEŃ) + LASER AIM BEAM
    // -------------------------------------------------------------------------
    if (in->hasTarget) {
        const float bx0 = static_cast<float>(in->targetX - in->camX);
        const float by0 = static_cast<float>(in->targetY - in->camY);
        const float bz0 = static_cast<float>(in->targetZ - in->camZ);
        const float bx1 = bx0 + 1.0f;
        const float by1 = by0 + 1.0f;
        const float bz1 = bz0 + 1.0f;

        const bool isLog = (in->targetType == 2);
        const uint32_t targetRgb = isLog ? RGB_CYBER_GOLD : RGB_EMERALD;
        const float prog = std::clamp(in->breakProgress, 0.0f, 1.0f);

        // Subtelne przezroczyste wypełnienie ścianek
        uint8_t solidAlpha = isLog
            ? static_cast<uint8_t>(42.0f + 35.0f * prog)
            : static_cast<uint8_t>(30.0f + 22.0f * fastPulse);
        uint32_t fillCol = packARGB(solidAlpha, targetRgb);

        emitGradientBoxQuads(
            solidQuadBuf, solidQ, maxSolidQuads,
            bx0 - 0.004f, by0 - 0.004f, bz0 - 0.004f,
            bx1 + 0.004f, by1 + 0.004f, bz1 + 0.004f,
            fillCol, fillCol
        );

        // Zewnętrzna poświata Additive Bloom
        float pulseExpand = 0.012f + 0.014f * fastPulse;
        uint32_t glowCol = packARGB(static_cast<uint8_t>(55.0f + 35.0f * fastPulse), targetRgb);
        emitGradientBoxQuads(
            bloomQuadBuf, bloomQ, maxBloomQuads,
            bx0 - pulseExpand, by0 - pulseExpand, bz0 - pulseExpand,
            bx1 + pulseExpand, by1 + pulseExpand, bz1 + pulseExpand,
            glowCol, glowCol
        );

        // Główny obrys bloku
        uint32_t wireCol = packARGB(245, targetRgb);
        emitGradientBoxLines(
            bloomLineBuf, bloomL, maxBloomLines,
            bx0 - 0.006f, by0 - 0.006f, bz0 - 0.006f,
            bx1 + 0.006f, by1 + 0.006f, bz1 + 0.006f,
            wireCol, wireCol
        );

        // Dla PNIA (Cyber Gold): animowany rdzeń postępu niszczenia + zamykająca się klatka
        if (isLog) {
            float effectiveProg = (prog > 0.02f) ? prog : (0.15f + 0.85f * std::fmod(t * 2.5f, 1.0f));
            // 1) Rosnący od dołu złoty rdzeń energetyczny
            float fillTopY = by0 + effectiveProg;
            uint32_t progFillBot = packARGB(35, RGB_CYBER_GOLD);
            uint32_t progFillTop = packARGB(115, 0xFFF4A3u);
            emitGradientBoxQuads(
                bloomQuadBuf, bloomQ, maxBloomQuads,
                bx0 + 0.02f, by0 + 0.02f, bz0 + 0.02f,
                bx1 - 0.02f, fillTopY,    bz1 - 0.02f,
                progFillBot, progFillTop
            );

            // 2) Kurcząca się ku środkowi klatka precyzyjna
            float inset = (1.0f - effectiveProg) * 0.35f;
            uint32_t innerWire = packARGB(230, 0xFFFFB0u);
            emitGradientBoxLines(
                bloomLineBuf, bloomL, maxBloomLines,
                bx0 + inset, by0 + inset, bz0 + inset,
                bx1 - inset, by1 - inset, bz1 - inset,
                innerWire, innerWire
            );
        }

        // ---------------------------------------------------------------------
        // 4. PROMIEŃ LASEROWY (LASER AIM BEAM) Z OCZU GRACZA DO CELU
        // ---------------------------------------------------------------------
        const float lx0 = static_cast<float>(in->laserStartX - in->camX);
        const float ly0 = static_cast<float>(in->laserStartY - in->camY);
        const float lz0 = static_cast<float>(in->laserStartZ - in->camZ);

        const float lx1 = bx0 + 0.5f;
        const float ly1 = by0 + 0.5f;
        const float lz1 = bz0 + 0.5f;

        uint32_t laserStartCol = packARGB(180, RGB_CYBER_CYAN);
        uint32_t laserEndCol   = packARGB(255, targetRgb);

        // Rdzeń wiązki lasera
        emitLine(bloomLineBuf, bloomL, maxBloomLines,
            lx0, ly0, lz0, laserStartCol,
            lx1, ly1, lz1, laserEndCol);

        // Wolumetryczna wstęga Bloom wzdłuż wiązki (2 skrzyżowane płaszczyzny bilboardowe)
        float dx = lx1 - lx0;
        float dy = ly1 - ly0;
        float dz = lz1 - lz0;
        float len = std::sqrt(dx * dx + dy * dy + dz * dz);
        if (len > 0.05f) {
            float beamHalfWidth = 0.028f + 0.012f * fastPulse;
            // Wektor prostopadły w płaszczyźnie XZ
            float invXz = 1.0f / std::max(0.001f, std::sqrt(dx * dx + dz * dz));
            float px = -dz * invXz * beamHalfWidth;
            float pz =  dx * invXz * beamHalfWidth;

            uint32_t beamGlowStart = packARGB(45, RGB_CYBER_CYAN);
            uint32_t beamGlowEnd   = packARGB(95, targetRgb);

            // Wstęga pozioma
            emitQuad(bloomQuadBuf, bloomQ, maxBloomQuads,
                lx0 - px, ly0, lz0 - pz, beamGlowStart,
                lx0 + px, ly0, lz0 + pz, beamGlowStart,
                lx1 + px, ly1, lz1 + pz, beamGlowEnd,
                lx1 - px, ly1, lz1 - pz, beamGlowEnd);

            // Wstęga pionowa
            emitQuad(bloomQuadBuf, bloomQ, maxBloomQuads,
                lx0, ly0 - beamHalfWidth, lz0, beamGlowStart,
                lx0, ly0 + beamHalfWidth, lz0, beamGlowStart,
                lx1, ly1 + beamHalfWidth, lz1, beamGlowEnd,
                lx1, ly1 - beamHalfWidth, lz1, beamGlowEnd);
        }
    }

    outCounts->solidQuadVertices = solidQ;
    outCounts->bloomQuadVertices = bloomQ;
    outCounts->bloomLineVertices = bloomL;
}

JNIEXPORT void JNICALL Java_com_astra_revolution_visuals_SpruceTreeVisuals_nBuildSpruceVisuals(
    JNIEnv* env, jclass,
    jobject inputBuf,
    jobject solidQuadBuf, jint maxSolidQuads,
    jobject bloomQuadBuf, jint maxBloomQuads,
    jobject bloomLineBuf, jint maxBloomLines,
    jobject outCountsBuf
) {
    if (!inputBuf || !solidQuadBuf || !bloomQuadBuf || !bloomLineBuf || !outCountsBuf) return;

    const AstraSpruceInput* in = static_cast<const AstraSpruceInput*>(env->GetDirectBufferAddress(inputBuf));
    AstraVertex* solidQ = static_cast<AstraVertex*>(env->GetDirectBufferAddress(solidQuadBuf));
    AstraVertex* bloomQ = static_cast<AstraVertex*>(env->GetDirectBufferAddress(bloomQuadBuf));
    AstraVertex* bloomL = static_cast<AstraVertex*>(env->GetDirectBufferAddress(bloomLineBuf));
    AstraSpruceOutputCounts* outCounts = static_cast<AstraSpruceOutputCounts*>(env->GetDirectBufferAddress(outCountsBuf));

    if (!in || !solidQ || !bloomQ || !bloomL || !outCounts) return;

    astra_build_spruce_visuals(in, solidQ, maxSolidQuads, bloomQ, maxBloomQuads, bloomL, maxBloomLines, outCounts);
}

namespace cyber_esp {
    // Paleta Cyber Holographic ESP (0x00RRGGBB)
    constexpr uint32_t RGB_CYBER_CYAN   = 0x00F0FFu; // #00F0FF (Korona liści / Cyber Brackets)
    constexpr uint32_t RGB_CYAN_CORE    = 0x99FAFFu; // Rozświetlony rdzeń krawędzi liści
    constexpr uint32_t RGB_CYBER_RED    = 0xFF3300u; // #FF3300 (Pień 2x2 / obrys)
    constexpr uint32_t RGB_CYBER_ORANGE = 0xFF5500u; // #FF5500 (Ściany pnia / fala UV)
    constexpr uint32_t RGB_SCAN_HOT     = 0xFFBB33u; // Szczyt fali skanującej UV

    __forceinline uint32_t packCyberARGB(uint8_t a, uint32_t rgb) noexcept {
        return (static_cast<uint32_t>(a) << 24) | (rgb & 0x00FFFFFFu);
    }

    __forceinline uint32_t lerpCyberARGB(uint32_t rgbA, uint32_t rgbB, float t, uint8_t alpha) noexcept {
        t = std::clamp(t, 0.0f, 1.0f);
        const uint32_t rA = (rgbA >> 16) & 0xFFu, gA = (rgbA >> 8) & 0xFFu, bA = rgbA & 0xFFu;
        const uint32_t rB = (rgbB >> 16) & 0xFFu, gB = (rgbB >> 8) & 0xFFu, bB = rgbB & 0xFFu;
        const uint32_t r = static_cast<uint32_t>(rA + (static_cast<float>(rB) - static_cast<float>(rA)) * t);
        const uint32_t g = static_cast<uint32_t>(gA + (static_cast<float>(gB) - static_cast<float>(gA)) * t);
        const uint32_t b = static_cast<uint32_t>(bA + (static_cast<float>(bB) - static_cast<float>(bA)) * t);
        return (static_cast<uint32_t>(alpha) << 24) | (r << 16) | (g << 8) | b;
    }

    __forceinline void emitCyberLine(
        AstraVertex* __restrict buf, int32_t& idx, int32_t maxVerts,
        float x0, float y0, float z0, uint32_t c0,
        float x1, float y1, float z1, uint32_t c1
    ) noexcept {
        if (idx + 2 > maxVerts) return;
        buf[idx + 0] = { x0, y0, z0, c0 };
        buf[idx + 1] = { x1, y1, z1, c1 };
        idx += 2;
    }

    void emitBoxQuadsAVX2(
        AstraVertex* __restrict buf, int32_t& idx, int32_t maxVerts,
        float x0, float y0, float z0,
        float x1, float y1, float z1,
        uint32_t cBot, uint32_t cTop
    ) noexcept {
        if (idx + 24 > maxVerts) return;

        const __m256 vx = _mm256_setr_ps(x0, x1, x1, x0, x0, x1, x1, x0);
        const __m256 vy = _mm256_setr_ps(y0, y0, y0, y0, y1, y1, y1, y1);
        const __m256 vz = _mm256_setr_ps(z0, z0, z1, z1, z0, z0, z1, z1);

        alignas(32) float cx[8], cy[8], cz[8];
        _mm256_store_ps(cx, vx);
        _mm256_store_ps(cy, vy);
        _mm256_store_ps(cz, vz);

        const uint32_t col[8] = { cBot, cBot, cBot, cBot, cTop, cTop, cTop, cTop };

        static constexpr uint8_t faces[6][4] = {
            { 0, 4, 5, 1 }, // North (-Z)
            { 2, 6, 7, 3 }, // South (+Z)
            { 3, 7, 4, 0 }, // West  (-X)
            { 1, 5, 6, 2 }, // East  (+X)
            { 4, 7, 6, 5 }, // Top   (+Y)
            { 3, 0, 1, 2 }  // Bottom(-Y)
        };

        for (int f = 0; f < 6; ++f) {
            const uint8_t i0 = faces[f][0], i1 = faces[f][1], i2 = faces[f][2], i3 = faces[f][3];
            buf[idx + 0] = { cx[i0], cy[i0], cz[i0], col[i0] };
            buf[idx + 1] = { cx[i1], cy[i1], cz[i1], col[i1] };
            buf[idx + 2] = { cx[i2], cy[i2], cz[i2], col[i2] };
            buf[idx + 3] = { cx[i3], cy[i3], cz[i3], col[i3] };
            idx += 4;
        }
    }

    void emitBoxLinesAVX2(
        AstraVertex* __restrict buf, int32_t& idx, int32_t maxVerts,
        float x0, float y0, float z0,
        float x1, float y1, float z1,
        uint32_t cBot, uint32_t cTop
    ) noexcept {
        if (idx + 24 > maxVerts) return;

        const __m256 vx = _mm256_setr_ps(x0, x1, x1, x0, x0, x1, x1, x0);
        const __m256 vy = _mm256_setr_ps(y0, y0, y0, y0, y1, y1, y1, y1);
        const __m256 vz = _mm256_setr_ps(z0, z0, z1, z1, z0, z0, z1, z1);

        alignas(32) float cx[8], cy[8], cz[8];
        _mm256_store_ps(cx, vx);
        _mm256_store_ps(cy, vy);
        _mm256_store_ps(cz, vz);

        const uint32_t col[8] = { cBot, cBot, cBot, cBot, cTop, cTop, cTop, cTop };

        static constexpr uint8_t edges[12][2] = {
            {0, 1}, {1, 2}, {2, 3}, {3, 0},
            {4, 5}, {5, 6}, {6, 7}, {7, 4},
            {0, 4}, {1, 5}, {2, 6}, {3, 7}
        };

        for (int e = 0; e < 12; ++e) {
            const uint8_t a = edges[e][0], b = edges[e][1];
            buf[idx + 0] = { cx[a], cy[a], cz[a], col[a] };
            buf[idx + 1] = { cx[b], cy[b], cz[b], col[b] };
            idx += 2;
        }
    }

    void emitCyberBracketsAVX2(
        AstraVertex* __restrict buf, int32_t& idx, int32_t maxVerts,
        float x0, float y0, float z0,
        float x1, float y1, float z1,
        uint32_t bracketColor
    ) noexcept {
        if (idx + 48 > maxVerts) return;

        const float dx = std::min(0.42f, (x1 - x0) * 0.22f);
        const float dy = std::min(0.42f, (y1 - y0) * 0.25f);
        const float dz = std::min(0.42f, (z1 - z0) * 0.22f);

        const __m256 vx = _mm256_setr_ps(x0, x1, x1, x0, x0, x1, x1, x0);
        const __m256 vy = _mm256_setr_ps(y0, y0, y0, y0, y1, y1, y1, y1);
        const __m256 vz = _mm256_setr_ps(z0, z0, z1, z1, z0, z0, z1, z1);

        const __m256 sgnX = _mm256_setr_ps( 1.f, -1.f, -1.f,  1.f,  1.f, -1.f, -1.f,  1.f);
        const __m256 sgnY = _mm256_setr_ps( 1.f,  1.f,  1.f,  1.f, -1.f, -1.f, -1.f, -1.f);
        const __m256 sgnZ = _mm256_setr_ps( 1.f,  1.f, -1.f, -1.f,  1.f,  1.f, -1.f, -1.f);

        const __m256 vEndX = _mm256_fmadd_ps(sgnX, _mm256_set1_ps(dx), vx);
        const __m256 vEndY = _mm256_fmadd_ps(sgnY, _mm256_set1_ps(dy), vy);
        const __m256 vEndZ = _mm256_fmadd_ps(sgnZ, _mm256_set1_ps(dz), vz);

        alignas(32) float cx[8], cy[8], cz[8], ex[8], ey[8], ez[8];
        _mm256_store_ps(cx, vx);
        _mm256_store_ps(cy, vy);
        _mm256_store_ps(cz, vz);
        _mm256_store_ps(ex, vEndX);
        _mm256_store_ps(ey, vEndY);
        _mm256_store_ps(ez, vEndZ);

        for (int c = 0; c < 8; ++c) {
            emitCyberLine(buf, idx, maxVerts, cx[c], cy[c], cz[c], bracketColor, ex[c], cy[c], cz[c], bracketColor);
            emitCyberLine(buf, idx, maxVerts, cx[c], cy[c], cz[c], bracketColor, cx[c], ey[c], cz[c], bracketColor);
            emitCyberLine(buf, idx, maxVerts, cx[c], cy[c], cz[c], bracketColor, cx[c], cy[c], ez[c], bracketColor);
        }
    }

    void buildCanopyTier(
        float x0, float y0, float z0,
        float x1, float y1, float z1,
        float harmonic,
        AstraVertex* __restrict solidQ, int32_t& solidIdx, int32_t maxSolidQ,
        AstraVertex* __restrict bloomQ, int32_t& bloomQIdx, int32_t maxBloomQ,
        AstraVertex* __restrict bloomL, int32_t& bloomLIdx, int32_t maxBloomL
    ) noexcept {
        const uint8_t wallAlpha = static_cast<uint8_t>(68.0f + 16.0f * harmonic);
        const uint32_t wallCol = packCyberARGB(wallAlpha, RGB_CYBER_CYAN);
        emitBoxQuadsAVX2(
            solidQ, solidIdx, maxSolidQ,
            x0 - 0.005f, y0 - 0.005f, z0 - 0.005f,
            x1 + 0.005f, y1 + 0.005f, z1 + 0.005f,
            wallCol, wallCol
        );

        const float glowPad = 0.028f + 0.014f * harmonic;
        const uint32_t bloomWallCol = packCyberARGB(static_cast<uint8_t>(26.0f + 14.0f * harmonic), RGB_CYBER_CYAN);
        emitBoxQuadsAVX2(
            bloomQ, bloomQIdx, maxBloomQ,
            x0 - glowPad, y0 - glowPad, z0 - glowPad,
            x1 + glowPad, y1 + glowPad, z1 + glowPad,
            bloomWallCol, bloomWallCol
        );

        const uint32_t innerLineCol = packCyberARGB(235, RGB_CYBER_CYAN);
        const uint32_t outerLineCol = packCyberARGB(static_cast<uint8_t>(110.0f + 45.0f * harmonic), RGB_CYAN_CORE);

        emitBoxLinesAVX2(
            bloomL, bloomLIdx, maxBloomL,
            x0 - 0.006f, y0 - 0.006f, z0 - 0.006f,
            x1 + 0.006f, y1 + 0.006f, z1 + 0.006f,
            innerLineCol, innerLineCol
        );

        const float outerOff = 0.032f + 0.012f * harmonic;
        emitBoxLinesAVX2(
            bloomL, bloomLIdx, maxBloomL,
            x0 - outerOff, y0 - outerOff, z0 - outerOff,
            x1 + outerOff, y1 + outerOff, z1 + outerOff,
            outerLineCol, outerLineCol
        );

        const uint32_t bracketCol = packCyberARGB(255, 0xE0FFFFu);
        emitCyberBracketsAVX2(
            bloomL, bloomLIdx, maxBloomL,
            x0 - 0.018f, y0 - 0.018f, z0 - 0.018f,
            x1 + 0.018f, y1 + 0.018f, z1 + 0.018f,
            bracketCol
        );
    }
}

extern "C" JNIEXPORT void JNICALL Java_com_astra_revolution_visuals_SpruceTreeVisuals_nativeBuildCyberEsp(
    JNIEnv* env,
    jclass /*clazz*/,
    jobject inputBuf,
    jobject solidQuadBuf, jint maxSolidQuadVerts,
    jobject bloomQuadBuf, jint maxBloomQuadVerts,
    jobject bloomLineBuf, jint maxBloomLineVerts,
    jobject outCountsBuf
) {
    const auto* in = static_cast<const AstraCyberEspInput*>(env->GetDirectBufferAddress(inputBuf));
    auto* solidQ   = static_cast<AstraVertex*>(env->GetDirectBufferAddress(solidQuadBuf));
    auto* bloomQ   = static_cast<AstraVertex*>(env->GetDirectBufferAddress(bloomQuadBuf));
    auto* bloomL   = static_cast<AstraVertex*>(env->GetDirectBufferAddress(bloomLineBuf));
    auto* counts   = static_cast<AstraCyberEspCounts*>(env->GetDirectBufferAddress(outCountsBuf));

    if (!in || !solidQ || !bloomQ || !bloomL || !counts) return;

    int32_t solidIdx  = 0;
    int32_t bloomQIdx = 0;
    int32_t bloomLIdx = 0;

    const float t = in->timeSeconds;
    const float harmonic = 0.5f + 0.5f * std::sin(t * 3.14159265f);

    // 1. PIEŃ 2X2 (#FF3300 / #FF5500 + Fala skanująca UV)
    if (in->hasTrunk) {
        const float minX = static_cast<float>(static_cast<double>(in->trunkMinX) - in->camX);
        const float minZ = static_cast<float>(static_cast<double>(in->trunkMinZ) - in->camZ);
        const float maxX = minX + 2.0f;
        const float maxZ = minZ + 2.0f;

        const float minY = static_cast<float>(static_cast<double>(in->baseSoilY + 1) - in->camY);
        const float maxY = static_cast<float>(static_cast<double>(in->topLogY + 1) - in->camY);
        const float trunkHeight = std::max(1.0f, maxY - minY);

        const uint8_t trunkWallAlpha = static_cast<uint8_t>(84.0f + 16.0f * harmonic);
        const uint32_t wallBotCol = cyber_esp::packCyberARGB(trunkWallAlpha, cyber_esp::RGB_CYBER_RED);
        const uint32_t wallTopCol = cyber_esp::packCyberARGB(trunkWallAlpha, cyber_esp::RGB_CYBER_ORANGE);

        cyber_esp::emitBoxQuadsAVX2(
            solidQ, solidIdx, maxSolidQuadVerts,
            minX - 0.006f, minY, minZ - 0.006f,
            maxX + 0.006f, maxY, maxZ + 0.006f,
            wallBotCol, wallTopCol
        );

        const float bloomPad = 0.026f + 0.012f * harmonic;
        const uint32_t trunkBloomCol = cyber_esp::packCyberARGB(static_cast<uint8_t>(38.0f + 18.0f * harmonic), cyber_esp::RGB_CYBER_RED);
        cyber_esp::emitBoxQuadsAVX2(
            bloomQ, bloomQIdx, maxBloomQuadVerts,
            minX - bloomPad, minY, minZ - bloomPad,
            maxX + bloomPad, maxY, maxZ + bloomPad,
            trunkBloomCol, trunkBloomCol
        );

        const uint32_t wireCore = cyber_esp::packCyberARGB(245, cyber_esp::RGB_CYBER_RED);
        const uint32_t wireGlow = cyber_esp::packCyberARGB(140, cyber_esp::RGB_CYBER_ORANGE);
        cyber_esp::emitBoxLinesAVX2(
            bloomL, bloomLIdx, maxBloomLineVerts,
            minX - 0.008f, minY, minZ - 0.008f,
            maxX + 0.008f, maxY, maxZ + 0.008f,
            wireCore, wireCore
        );
        cyber_esp::emitBoxLinesAVX2(
            bloomL, bloomLIdx, maxBloomLineVerts,
            minX - 0.030f, minY, minZ - 0.030f,
            maxX + 0.030f, maxY, maxZ + 0.030f,
            wireGlow, wireGlow
        );

        const float scanPhase = std::fmod(t * 0.55f, 1.0f);
        const float scanCenterY = minY + scanPhase * trunkHeight;
        const float bandHalfH = std::min(0.65f, trunkHeight * 0.12f);
        const float bandMinY = std::max(minY, scanCenterY - bandHalfH);
        const float bandMaxY = std::min(maxY, scanCenterY + bandHalfH);

        if (bandMaxY > bandMinY + 0.01f) {
            const uint32_t scanFadeCol = cyber_esp::packCyberARGB(15, cyber_esp::RGB_CYBER_ORANGE);
            const uint32_t scanPeakCol = cyber_esp::packCyberARGB(145, cyber_esp::RGB_SCAN_HOT);
            cyber_esp::emitBoxQuadsAVX2(
                bloomQ, bloomQIdx, maxBloomQuadVerts,
                minX - 0.022f, bandMinY, minZ - 0.022f,
                maxX + 0.022f, bandMaxY, maxZ + 0.022f,
                scanFadeCol, scanPeakCol
            );

            const uint32_t scanRingCol = cyber_esp::lerpCyberARGB(cyber_esp::RGB_CYBER_RED, cyber_esp::RGB_SCAN_HOT, harmonic, 250);
            cyber_esp::emitBoxLinesAVX2(
                bloomL, bloomLIdx, maxBloomLineVerts,
                minX - 0.035f, bandMaxY - 0.02f, minZ - 0.035f,
                maxX + 0.035f, bandMaxY + 0.02f, maxZ + 0.035f,
                scanRingCol, scanRingCol
            );
        }
    }

    // 2. SCHODKOWA KORONA LIŚCI (LIŚCIE / LEAVES)
    if (in->hasLeaves) {
        if (in->tierCount > 0) {
            const int32_t count = std::min(in->tierCount, MAX_CANOPY_TIERS);
            for (int32_t i = 0; i < count; ++i) {
                const SpruceTierBox& box = in->tiers[i];
                const float x0 = static_cast<float>(static_cast<double>(box.minX) - in->camX);
                const float y0 = static_cast<float>(static_cast<double>(box.minY) - in->camY);
                const float z0 = static_cast<float>(static_cast<double>(box.minZ) - in->camZ);
                const float x1 = static_cast<float>(static_cast<double>(box.maxX) - in->camX);
                const float y1 = static_cast<float>(static_cast<double>(box.maxY) - in->camY);
                const float z1 = static_cast<float>(static_cast<double>(box.maxZ) - in->camZ);

                cyber_esp::buildCanopyTier(
                    x0, y0, z0, x1, y1, z1, harmonic,
                    solidQ, solidIdx, maxSolidQuadVerts,
                    bloomQ, bloomQIdx, maxBloomQuadVerts,
                    bloomL, bloomLIdx, maxBloomLineVerts
                );
            }
        } else if (in->hasTrunk) {
            const float tMinX = static_cast<float>(static_cast<double>(in->trunkMinX) - in->camX);
            const float tMinZ = static_cast<float>(static_cast<double>(in->trunkMinZ) - in->camZ);
            const float topY  = static_cast<float>(static_cast<double>(in->topLogY + 1) - in->camY);
            const float soilY = static_cast<float>(static_cast<double>(in->baseSoilY + 1) - in->camY);
            const float canopyStartY = std::max(soilY + 3.0f, topY - 12.0f);
            const float spanY = std::max(4.0f, (topY + 2.0f) - canopyStartY);

            constexpr float radii[4] = { 3.0f, 2.0f, 1.0f, 0.0f };
            for (int tier = 0; tier < 4; ++tier) {
                const float r = radii[tier];
                const float y0 = canopyStartY + (spanY * static_cast<float>(tier) / 4.0f);
                const float y1 = canopyStartY + (spanY * static_cast<float>(tier + 1) / 4.0f);

                cyber_esp::buildCanopyTier(
                    tMinX - r, y0, tMinZ - r,
                    tMinX + 2.0f + r, y1, tMinZ + 2.0f + r,
                    harmonic,
                    solidQ, solidIdx, maxSolidQuadVerts,
                    bloomQ, bloomQIdx, maxBloomQuadVerts,
                    bloomL, bloomLIdx, maxBloomLineVerts
                );
            }
        }
    }

    counts->solidQuadVertices = solidIdx;
    counts->bloomQuadVertices = bloomQIdx;
    counts->bloomLineVertices = bloomLIdx;
}

} // extern "C"

} // extern "C"
