package baritone.bypass.modern;

import org.joml.Matrix4f;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL30;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.RenderingHints;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Silnik gładkiej czcionki TrueType z 4-krotnym nadpróbkowaniem (Supersampled AWT Font Atlas)
 * i filtrowaniem GL_LINEAR. Całkowicie eliminuje pikselozę Minecrafta.
 */
public final class SmoothFontRenderer {
    // Nadpróbkowanie 4x dla ostrości na ekranach 1080p/1440p/4K
    private static final float OVERSAMPLE = 4.0f;
    private static final int ATLAS_WIDTH = 1024;
    private static final int ATLAS_HEIGHT = 1024;
    private static final int PADDING = 6;

    private final Map<Character, Glyph> glyphs = new HashMap<>();
    private boolean available = false;
    private int textureId = -1;
    private float fontHeightGui = 9.0f;
    private float ascentGui = 7.0f;

    private record Glyph(float u0, float v0, float u1, float v1, float widthGui, float heightGui, float advanceGui) {}

    public boolean isAvailable() {
        return available && textureId != -1;
    }

    public SmoothFontRenderer(float targetGuiSize) {
        try {
            int awtPointSize = Math.round(targetGuiSize * OVERSAMPLE);
            Font font = loadBestSansFont(awtPointSize);

            BufferedImage atlas = new BufferedImage(ATLAS_WIDTH, ATLAS_HEIGHT, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g2d = atlas.createGraphics();

            // Konfiguracja maksymalnej jakości antyaliasingu i metryk ułamkowych
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g2d.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
            g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g2d.setFont(font);
            g2d.setColor(Color.WHITE);

            FontMetrics metrics = g2d.getFontMetrics();
            this.fontHeightGui = metrics.getHeight() / OVERSAMPLE;
            this.ascentGui = metrics.getAscent() / OVERSAMPLE;

        int curX = PADDING;
        int curY = PADDING;
        int rowHeight = 0;

        // Zakres znaków ASCII + polskie znaki diakrytyczne + separatory i symbole
        String charset = buildCharset();
        for (int i = 0; i < charset.length(); i++) {
            char ch = charset.charAt(i);
            Rectangle2D bounds = metrics.getStringBounds(String.valueOf(ch), g2d);

            float exactAdvance = (float) bounds.getWidth();
            int drawW = Math.max(1, (int) Math.ceil(exactAdvance));
            int drawH = metrics.getHeight();

            if (curX + drawW + PADDING >= ATLAS_WIDTH) {
                curX = PADDING;
                curY += rowHeight + PADDING;
                rowHeight = 0;
            }

            g2d.drawString(String.valueOf(ch), curX, curY + metrics.getAscent());

            float u0 = (float) curX / ATLAS_WIDTH;
            float v0 = (float) curY / ATLAS_HEIGHT;
            float u1 = (float) (curX + drawW) / ATLAS_WIDTH;
            float v1 = (float) (curY + drawH) / ATLAS_HEIGHT;

            glyphs.put(ch, new Glyph(
                    u0, v0, u1, v1,
                    (float) drawW / OVERSAMPLE,
                    (float) drawH / OVERSAMPLE,
                    exactAdvance / OVERSAMPLE
            ));

            curX += drawW + PADDING;
            rowHeight = Math.max(rowHeight, drawH);
        }
        g2d.dispose();

            this.textureId = uploadAtlasTexture(atlas);
            this.available = true;
        } catch (Throwable t) {
            System.err.println("[SmoothFontRenderer] Ostrzeżenie: Błąd AWT/Headless fontu: " + t);
            this.available = false;
        }
    }

    public float getStringWidth(String text) {
        if (text == null || text.isEmpty()) {
            return 0.0f;
        }
        float width = 0.0f;
        for (int i = 0; i < text.length(); i++) {
            Glyph g = glyphs.getOrDefault(text.charAt(i), glyphs.get(' '));
            if (g != null) {
                width += g.advanceGui;
            }
        }
        return width;
    }

    public float getFontHeight() {
        return this.fontHeightGui;
    }

    public float getAscent() {
        return this.ascentGui;
    }

    public void drawString(Matrix4f proj, String text, float x, float y, int argbColor) {
        if (text == null || text.isEmpty()) {
            return;
        }
        SdfShaderManager shader = SdfShaderManager.getInstance();
        shader.beginFontBatch(proj, this.textureId);

        float cursorX = x;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            Glyph g = glyphs.getOrDefault(ch, glyphs.get(' '));
            if (g == null) {
                continue;
            }
            shader.pushQuadToBatch(
                    cursorX, y,
                    g.widthGui, g.heightGui,
                    g.u0, g.v0, g.u1, g.v1,
                    argbColor
            );
            cursorX += g.advanceGui;
        }
        shader.flushBatch();
    }

    private static Font loadBestSansFont(int awtPointSize) {
        // 1. Próba wczytania dołączonego fontu z zasobów moda
        try (InputStream is = SmoothFontRenderer.class.getResourceAsStream("/assets/capsulehud/fonts/Inter-SemiBold.ttf")) {
            if (is != null) {
                Font custom = Font.createFont(Font.TRUETYPE_FONT, is);
                return custom.deriveFont(Font.PLAIN, (float) awtPointSize);
            }
        } catch (Exception ignored) {
        }

        // 2. Wybór najlepszego nowoczesnego fontu zainstalowanego w systemie (Windows/macOS/Linux)
        List<String> preferred = Arrays.asList(
                "Inter SemiBold", "Inter Medium", "Inter",
                "SF Pro Rounded", "SF Pro Display",
                "Poppins Medium", "Poppins",
                "Segoe UI Variable Display Semibold", "Segoe UI Semibold", "Segoe UI"
        );
        String[] available = GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames();
        for (String candidate : preferred) {
            for (String sysFont : available) {
                if (sysFont.equalsIgnoreCase(candidate)) {
                    return new Font(sysFont, Font.BOLD, awtPointSize);
                }
            }
        }
        return new Font(Font.SANS_SERIF, Font.BOLD, awtPointSize);
    }

    private static String buildCharset() {
        StringBuilder sb = new StringBuilder(512);
        for (int c = 32; c < 127; c++) {
            sb.append((char) c);
        }
        sb.append("ąčęėįšųūžĄČĘĖĮŠŲŪŽąćęłńóśźżĄĆĘŁŃÓŚŹŻ│•·✦◆()/:,.-_+°✓★☆✕✖…—–“”„”§");
        return sb.toString();
    }

    private static int uploadAtlasTexture(BufferedImage img) {
        int width = img.getWidth();
        int height = img.getHeight();
        int[] pixels = new int[width * height];
        img.getRGB(0, 0, width, height, pixels, 0, width);

        ByteBuffer buffer = BufferUtils.createByteBuffer(width * height * 4);
        for (int argb : pixels) {
            int a = (argb >>> 24) & 0xFF;
            int r = (argb >> 16) & 0xFF;
            int g = (argb >> 8) & 0xFF;
            int b = argb & 0xFF;
            int coverage = Math.max(a, Math.max(r, Math.max(g, b)));
            // Czysty biały kolor bazowy (255, 255, 255) z kanałem alpha
            buffer.put((byte) 0xFF);
            buffer.put((byte) 0xFF);
            buffer.put((byte) 0xFF);
            buffer.put((byte) coverage);
        }
        buffer.flip();

        int prevUnpackRowLength = GL11.glGetInteger(GL11.GL_UNPACK_ROW_LENGTH);
        int prevUnpackSkipPixels = GL11.glGetInteger(GL11.GL_UNPACK_SKIP_PIXELS);
        int prevUnpackSkipRows = GL11.glGetInteger(GL11.GL_UNPACK_SKIP_ROWS);
        int prevUnpackAlignment = GL11.glGetInteger(GL11.GL_UNPACK_ALIGNMENT);

        GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, 0);
        GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_PIXELS, 0);
        GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_ROWS, 0);
        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 4);

        int texId = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texId);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);

        GL11.glTexImage2D(
                GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8,
                width, height, 0,
                GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buffer
        );

        GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, prevUnpackRowLength);
        GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_PIXELS, prevUnpackSkipPixels);
        GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_ROWS, prevUnpackSkipRows);
        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, prevUnpackAlignment);

        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
        return texId;
    }
}
