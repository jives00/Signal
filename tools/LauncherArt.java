import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/**
 * Renders Signal's Android TV launcher banner and icon from the design tokens (PLAN §3) and the
 * bundled Archivo font. The real logo is still an open decision (PLAN §4.4); until then, rerun
 * this after changing the wordmark or colors:
 *
 *   java tools/LauncherArt.java
 *
 * Writes the release art into androidTvApp/src/main/res and labeled variants into src/debug and
 * src/profile, so the three installs are easy to tell apart on the Shield's home screen.
 */
public class LauncherArt {
    static final Color BG = new Color(0x30, 0x34, 0x3B);
    static final Color TEXT = new Color(0xEC, 0xEE, 0xF1);
    static final Color ACCENT = new Color(0x2F, 0x6F, 0xE8);
    static final Color MUTED = new Color(0xAE, 0xB4, 0xBD);

    public static void main(String[] args) throws Exception {
        Font archivo = Font.createFont(Font.TRUETYPE_FONT, new File("shared/src/commonMain/composeResources/font/archivo_800.ttf"));
        String res = "androidTvApp/src/";
        for (String[] variant : new String[][] {{"main", null}, {"debug", "DEBUG"}, {"profile", "PROFILE"}}) {
            // Banner: 320×180 dp, drawn at xhdpi (2×).
            write(banner(archivo, variant[1]), res + variant[0] + "/res/drawable-xhdpi/banner.png");
            // Icon: 48 dp, drawn at xxxhdpi (4×).
            write(icon(archivo, variant[1]), res + variant[0] + "/res/mipmap-xxxhdpi/ic_launcher.png");
        }
    }

    static BufferedImage banner(Font archivo, String label) {
        int w = 640, h = 360;
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = start(img);
        g.setColor(BG);
        g.fillRect(0, 0, w, h);
        // Wordmark, flush left like everything in the design, centered vertically.
        g.setFont(archivo.deriveFont(124f));
        FontMetrics fm = g.getFontMetrics();
        int x = 56;
        int baseline = (h + fm.getAscent() - fm.getDescent()) / 2;
        g.setColor(TEXT);
        g.drawString("SIGNAL", x, baseline);
        g.setColor(ACCENT);
        g.drawString(".", x + fm.stringWidth("SIGNAL"), baseline);
        // The accent bar along the bottom edge.
        g.fillRect(0, h - 20, w, 20);
        if (label != null) tag(g, archivo, label, 56, 40, 34f);
        g.dispose();
        return img;
    }

    static BufferedImage icon(Font archivo, String label) {
        int s = 192;
        BufferedImage img = new BufferedImage(s, s, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = start(img);
        g.setColor(BG);
        g.fillRect(0, 0, s, s);
        g.setFont(archivo.deriveFont(132f));
        FontMetrics fm = g.getFontMetrics();
        int width = fm.stringWidth("S.");
        int x = (s - width) / 2;
        int baseline = (s + fm.getAscent() - fm.getDescent()) / 2;
        g.setColor(TEXT);
        g.drawString("S", x, baseline);
        g.setColor(ACCENT);
        g.drawString(".", x + fm.stringWidth("S"), baseline);
        if (label != null) {
            g.setColor(ACCENT);
            g.fillRect(0, s - 36, s, 36);
            g.setColor(TEXT);
            g.setFont(archivo.deriveFont(24f));
            FontMetrics lf = g.getFontMetrics();
            g.drawString(label, (s - lf.stringWidth(label)) / 2, s - 10);
        }
        g.dispose();
        return img;
    }

    static void tag(Graphics2D g, Font archivo, String label, int x, int y, float size) {
        g.setFont(archivo.deriveFont(size));
        FontMetrics fm = g.getFontMetrics();
        int pad = 12;
        g.setColor(ACCENT);
        g.fillRect(x, y, fm.stringWidth(label) + pad * 2, fm.getHeight());
        g.setColor(TEXT);
        g.drawString(label, x + pad, y + fm.getAscent());
    }

    static Graphics2D start(BufferedImage img) {
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        return g;
    }

    static void write(BufferedImage img, String path) throws Exception {
        File f = new File(path);
        f.getParentFile().mkdirs();
        ImageIO.write(img, "png", f);
        System.out.println("wrote " + path);
    }
}
