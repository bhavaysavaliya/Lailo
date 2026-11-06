package com.lailo.application.features.auth.infrastructure.captcha;

import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.geom.CubicCurve2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Base64;
import java.util.random.RandomGenerator;

/** Renders distorted text to a PNG data URI (rotated glyphs, noise curves, speckle). */
@Component
public class CaptchaImageRenderer {

    private static final int WIDTH = 180;
    private static final int HEIGHT = 56;
    private static final String[] FONTS = {Font.SANS_SERIF, Font.SERIF, Font.MONOSPACED};

    static {
        System.setProperty("java.awt.headless", "true");
    }

    public String renderDataUri(String text, RandomGenerator rnd) {
        BufferedImage img = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(new Color(246, 247, 249));
            g.fillRect(0, 0, WIDTH, HEIGHT);

            for (int i = 0; i < 120; i++) { // speckle
                g.setColor(new Color(150 + rnd.nextInt(90), 150 + rnd.nextInt(90), 150 + rnd.nextInt(90)));
                g.fillOval(rnd.nextInt(WIDTH), rnd.nextInt(HEIGHT), 2, 2);
            }

            int x = 14;
            int step = (WIDTH - 28) / text.length();
            for (char ch : text.toCharArray()) {
                g.setFont(new Font(FONTS[rnd.nextInt(FONTS.length)], Font.BOLD, 28 + rnd.nextInt(8)));
                g.setColor(new Color(20 + rnd.nextInt(80), 30 + rnd.nextInt(80), 60 + rnd.nextInt(100)));
                AffineTransform saved = g.getTransform();
                int y = 36 + rnd.nextInt(10) - 5;
                g.rotate(Math.toRadians(rnd.nextInt(50) - 25), x + 8, y - 10);
                g.drawString(String.valueOf(ch), x, y);
                g.setTransform(saved);
                x += step;
            }

            g.setStroke(new BasicStroke(1.6f));
            for (int i = 0; i < 4; i++) { // interference curves through the glyphs
                g.setColor(new Color(60 + rnd.nextInt(120), 60 + rnd.nextInt(120), 60 + rnd.nextInt(120)));
                g.draw(new CubicCurve2D.Float(0, rnd.nextInt(HEIGHT), WIDTH / 3f, rnd.nextInt(HEIGHT),
                        2 * WIDTH / 3f, rnd.nextInt(HEIGHT), WIDTH, rnd.nextInt(HEIGHT)));
            }
        } finally {
            g.dispose();
        }
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ImageIO.write(img, "png", out);
            return "data:image/png;base64," + Base64.getEncoder().encodeToString(out.toByteArray());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
