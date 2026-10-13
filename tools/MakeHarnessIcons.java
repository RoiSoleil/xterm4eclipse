import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.font.GlyphVector;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/**
 * Renders the icons of the coding agents, shown in the menus and in the tabs of their terminals:
 * drawings of their own in the colors of each agent, not their logos.
 * Usage: java tools/MakeHarnessIcons.java <output directory> [preview.png]
 */
public class MakeHarnessIcons {

	private static final String[] NAMES = {"claude", "codex", "opencode", "reasonix", "gemini", "copilot", "cursor",
			"qwen", "kilo", "crush", "amp", "goose", "aider"};

	public static void main(String[] args) throws Exception {
		File directory = new File(args[0]);
		directory.mkdirs();
		for (String name : NAMES) {
			ImageIO.write(render(name, 16), "png", new File(directory, name + ".png"));
			ImageIO.write(render(name, 32), "png", new File(directory, name + "@2x.png"));
		}
		if (args.length > 1) {
			BufferedImage sheet = new BufferedImage(NAMES.length * 144, 192, BufferedImage.TYPE_INT_ARGB);
			Graphics2D g = sheet.createGraphics();
			g.setPaint(new Color(0x3c3f41));
			g.fillRect(0, 0, sheet.getWidth() / 2, sheet.getHeight());
			g.setPaint(new Color(0xf4f4f4));
			g.fillRect(sheet.getWidth() / 2, 0, sheet.getWidth() / 2, sheet.getHeight());
			for (int i = 0; i < NAMES.length; i++) {
				g.drawImage(render(NAMES[i], 128), i * 144 + 8, 8, null);
				g.drawImage(render(NAMES[i], 16), i * 144 + 40, 152, null);
				g.drawImage(render(NAMES[i], 32), i * 144 + 72, 144, null);
			}
			g.dispose();
			ImageIO.write(sheet, "png", new File(args[1]));
		}
	}

	private static BufferedImage render(String name, int size) {
		BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = image.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
		g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
		g.scale(size / 16.0, size / 16.0);
		switch (name) {
		case "claude" -> claude(g);
		case "codex" -> codex(g);
		case "opencode" -> openCode(g);
		case "reasonix" -> label(g, 0x4d6bfe, 0xffffff, "R", 11);
		case "gemini" -> gemini(g);
		case "copilot" -> copilot(g);
		case "cursor" -> cursor(g);
		case "qwen" -> label(g, 0x615ced, 0xffffff, "Q", 11);
		case "kilo" -> label(g, 0xf8f675, 0x1a1a1a, "K", 11);
		case "crush" -> crush(g);
		case "amp" -> amp(g);
		case "goose" -> label(g, 0x1f2328, 0xffffff, "g", 12);
		default -> label(g, 0x14b014, 0x0b1f0b, "ai", 8.5);
		}
		g.dispose();
		return image;
	}

	private static void box(Graphics2D g, int color) {
		g.setPaint(new Color(color));
		g.fill(new RoundRectangle2D.Double(0.5, 1, 15, 14, 4, 4));
	}

	private static void label(Graphics2D g, int background, int foreground, String text, double fontSize) {
		box(g, background);
		Font font = new Font("DejaVu Sans", Font.BOLD, 1).deriveFont((float) fontSize);
		GlyphVector glyphs = font.createGlyphVector(g.getFontRenderContext(), text);
		Rectangle2D bounds = glyphs.getVisualBounds();
		g.setPaint(new Color(foreground));
		g.fill(glyphs.getOutline((float) (8 - bounds.getCenterX()), (float) (8 - bounds.getCenterY())));
	}

	private static BasicStroke stroke(double width) {
		return new BasicStroke((float) width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
	}

	/** A white starburst on the terracotta of Claude. */
	private static void claude(Graphics2D g) {
		box(g, 0xd97757);
		g.setPaint(Color.WHITE);
		g.setStroke(stroke(1.6));
		for (int i = 0; i < 8; i++) {
			double angle = Math.PI * i / 4 + Math.PI / 8;
			double length = i % 2 == 0 ? 5.2 : 4.2;
			g.draw(new Line2D.Double(8 + Math.cos(angle) * 1.2, 8 + Math.sin(angle) * 1.2,
					8 + Math.cos(angle) * length, 8 + Math.sin(angle) * length));
		}
	}

	/** A prompt in a cloud-white box: the terminal of Codex. */
	private static void codex(Graphics2D g) {
		box(g, 0xf5f5f5);
		g.setPaint(new Color(0x111111));
		g.setStroke(stroke(1.7));
		Path2D chevron = new Path2D.Double();
		chevron.moveTo(4, 5);
		chevron.lineTo(7.2, 8);
		chevron.lineTo(4, 11);
		g.draw(chevron);
		g.draw(new Line2D.Double(8.6, 11, 12, 11));
	}

	/** A square frame, open as a code block. */
	private static void openCode(Graphics2D g) {
		box(g, 0x211e1e);
		g.setPaint(new Color(0xfab283));
		g.setStroke(new BasicStroke(1.8f, BasicStroke.CAP_SQUARE, BasicStroke.JOIN_MITER));
		g.draw(new Rectangle2D.Double(4.6, 4.4, 6.8, 7.2));
		g.setPaint(new Color(0x7d7575));
		g.fill(new Rectangle2D.Double(6.4, 7.6, 3.2, 3.1));
	}

	/** A four-pointed sparkle from blue to violet. */
	private static void gemini(Graphics2D g) {
		Path2D star = new Path2D.Double();
		star.moveTo(8, 0.8);
		star.curveTo(8.4, 5, 11, 7.6, 15.2, 8);
		star.curveTo(11, 8.4, 8.4, 11, 8, 15.2);
		star.curveTo(7.6, 11, 5, 8.4, 0.8, 8);
		star.curveTo(5, 7.6, 7.6, 5, 8, 0.8);
		star.closePath();
		g.setPaint(new GradientPaint(2, 13, new Color(0x4285f4), 14, 3, new Color(0x9b72cb)));
		g.fill(star);
	}

	/** The goggles of a pilot. */
	private static void copilot(Graphics2D g) {
		box(g, 0x24292f);
		g.setPaint(Color.WHITE);
		g.setStroke(stroke(1.4));
		g.draw(new RoundRectangle2D.Double(2.8, 5.2, 4.6, 5.6, 3, 3));
		g.draw(new RoundRectangle2D.Double(8.6, 5.2, 4.6, 5.6, 3, 3));
		g.draw(new Line2D.Double(7.4, 7.2, 8.6, 7.2));
	}

	/** The pointer of a mouse. */
	private static void cursor(Graphics2D g) {
		box(g, 0x0f0f0f);
		Path2D arrow = new Path2D.Double();
		arrow.moveTo(4.4, 3.4);
		arrow.lineTo(12, 7.2);
		arrow.lineTo(8.6, 8.4);
		arrow.lineTo(7.2, 12);
		arrow.closePath();
		g.setPaint(Color.WHITE);
		g.fill(arrow);
	}

	/** A heart, in the pink of Charm. */
	private static void crush(Graphics2D g) {
		box(g, 0x6b50ff);
		Path2D heart = new Path2D.Double();
		heart.moveTo(8, 12.4);
		heart.curveTo(2, 8.4, 3.2, 3.8, 6, 3.8);
		heart.curveTo(7.2, 3.8, 7.8, 4.6, 8, 5.4);
		heart.curveTo(8.2, 4.6, 8.8, 3.8, 10, 3.8);
		heart.curveTo(12.8, 3.8, 14, 8.4, 8, 12.4);
		heart.closePath();
		g.setPaint(new Color(0xff60ff));
		g.fill(heart);
	}

	/** A flash of power. */
	private static void amp(Graphics2D g) {
		box(g, 0xf34e3f);
		Path2D bolt = new Path2D.Double();
		bolt.moveTo(9.4, 2.6);
		bolt.lineTo(4.6, 8.8);
		bolt.lineTo(7.6, 8.8);
		bolt.lineTo(6.6, 13.4);
		bolt.lineTo(11.4, 7.2);
		bolt.lineTo(8.4, 7.2);
		bolt.closePath();
		g.setPaint(Color.WHITE);
		g.fill(bolt);
	}
}
