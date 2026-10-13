import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.font.GlyphVector;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/**
 * Renders the icons shown in front of the shells in the menus.
 * Usage: java tools/MakeShellIcons.java <output directory> [preview.png]
 */
public class MakeShellIcons {

	private static final String[] NAMES = {"shell", "bash", "zsh", "fish", "powershell", "cmd", "gitbash", "linux"};

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
			g.fillRect(0, 0, sheet.getWidth(), sheet.getHeight());
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
		case "bash" -> label(g, 0x2e3436, 0x4ade80, "$_", 9.5);
		case "zsh" -> label(g, 0x2e3436, 0xfbbf24, "%_", 9.5);
		case "fish" -> label(g, 0x0f4c5c, 0xff9e64, "><>", 7);
		case "powershell" -> label(g, 0x2671be, 0xffffff, ">_", 9.5);
		case "cmd" -> label(g, 0x0c0c0c, 0xe5e5e5, "C:\\", 6.6);
		case "gitbash" -> gitBash(g);
		case "linux" -> penguin(g);
		default -> label(g, 0x555b61, 0xffffff, ">_", 9.5);
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
		text(g, foreground, text, fontSize, 8, 8);
	}

	private static void text(Graphics2D g, int color, String text, double fontSize, double cx, double cy) {
		Font font = new Font("DejaVu Sans Mono", Font.BOLD, 1).deriveFont((float) fontSize);
		GlyphVector glyphs = font.createGlyphVector(g.getFontRenderContext(), text);
		Rectangle2D bounds = glyphs.getVisualBounds();
		g.setPaint(new Color(color));
		g.fill(glyphs.getOutline((float) (cx - bounds.getCenterX()), (float) (cy - bounds.getCenterY())));
	}

	/** A branch with two commits on a tilted square, in the orange of version control tools. */
	private static void gitBash(Graphics2D g) {
		Path2D diamond = new Path2D.Double();
		diamond.moveTo(8, 0.6);
		diamond.lineTo(15.4, 8);
		diamond.lineTo(8, 15.4);
		diamond.lineTo(0.6, 8);
		diamond.closePath();
		g.setPaint(new Color(0xf05033));
		g.setStroke(new BasicStroke(1.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		g.fill(diamond);
		g.draw(diamond);
		g.setPaint(Color.WHITE);
		g.setStroke(new BasicStroke(1.3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		Path2D branch = new Path2D.Double();
		branch.moveTo(6.4, 4.6);
		branch.lineTo(6.4, 11.4);
		branch.moveTo(6.4, 8.6);
		branch.curveTo(9.6, 8.6, 10, 7.6, 10, 6.4);
		g.draw(branch);
		for (double[] node : new double[][] {{6.4, 4.6}, {6.4, 11.4}, {10, 6.2}}) {
			g.fill(new Ellipse2D.Double(node[0] - 1.5, node[1] - 1.5, 3, 3));
		}
	}

	/** A penguin, for the Linux distribution behind WSL. */
	private static void penguin(Graphics2D g) {
		g.setPaint(new Color(0x1f2328));
		g.fill(new Ellipse2D.Double(3.2, 3.6, 9.6, 11.4));
		g.fill(new Ellipse2D.Double(4.8, 0.8, 6.4, 6.6));
		g.setPaint(Color.WHITE);
		g.fill(new Ellipse2D.Double(5, 6.6, 6, 7.6));
		g.fill(new Ellipse2D.Double(5.9, 2.6, 1.8, 2.2));
		g.fill(new Ellipse2D.Double(8.3, 2.6, 1.8, 2.2));
		g.setPaint(new Color(0x1f2328));
		g.fill(new Ellipse2D.Double(6.6, 3.3, 0.9, 1.1));
		g.fill(new Ellipse2D.Double(8.5, 3.3, 0.9, 1.1));
		g.setPaint(new Color(0xfbbf24));
		Path2D beak = new Path2D.Double();
		beak.moveTo(6.7, 5);
		beak.lineTo(9.3, 5);
		beak.lineTo(8, 6.7);
		beak.closePath();
		g.fill(beak);
		g.fill(new Ellipse2D.Double(3.4, 13.4, 4, 2));
		g.fill(new Ellipse2D.Double(8.6, 13.4, 4, 2));
	}
}
