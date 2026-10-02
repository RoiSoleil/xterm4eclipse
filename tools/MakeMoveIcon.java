import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/**
 * Renders the icons of the actions that move a terminal: a window whose upper part (the editor area)
 * or lower part (the views) is highlighted, with an arrow towards it.
 * Usage: java tools/MakeMoveIcon.java <move-to-editor|move-to-view> <size> <file.png>
 */
public class MakeMoveIcon {
	public static void main(String[] args) throws Exception {
		boolean toEditor = args[0].equals("move-to-editor");
		int size = Integer.parseInt(args[1]);
		BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = image.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
		g.scale(size / 16.0, size / 16.0);
		Color frame = new Color(0x6b7280);
		Color accent = new Color(0x22a5d8);
		// The highlighted part of the window: where the terminal goes.
		g.setPaint(new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 90));
		g.fill(toEditor ? new Rectangle2D.Double(1.5, 1.5, 13, 7) : new Rectangle2D.Double(1.5, 9.5, 13, 5));
		g.setPaint(frame);
		g.setStroke(new BasicStroke(1f));
		g.draw(new Rectangle2D.Double(1.5, 1.5, 13, 13));
		g.draw(new Rectangle2D.Double(1.5, 9, 13, 0.01));
		// The arrow.
		g.setPaint(accent);
		g.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		Path2D arrow = new Path2D.Double();
		if (toEditor) {
			arrow.moveTo(8, 12.5);
			arrow.lineTo(8, 4);
			arrow.moveTo(5.2, 6.6);
			arrow.lineTo(8, 3.8);
			arrow.lineTo(10.8, 6.6);
		} else {
			arrow.moveTo(8, 3.5);
			arrow.lineTo(8, 12);
			arrow.moveTo(5.2, 9.4);
			arrow.lineTo(8, 12.2);
			arrow.lineTo(10.8, 9.4);
		}
		g.draw(arrow);
		g.dispose();
		ImageIO.write(image, "png", new File(args[2]));
	}
}
