import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/**
 * Renders the badges drawn over the icon of the shell in the tab of a terminal: a command running
 * (orange) or finished (green), with a dark ring to detach them from the icon.
 * Usage: java tools/MakeOverlayIcon.java <running|done> <size> <file.png> (size 8, or 16 for @2x)
 */
public class MakeOverlayIcon {
	public static void main(String[] args) throws Exception {
		int size = Integer.parseInt(args[1]);
		BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = image.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.scale(size / 8.0, size / 8.0);
		g.setPaint(new Color(0x16161e));
		g.fill(new Ellipse2D.Double(0, 0, 8, 8));
		g.setPaint(new Color(args[0].equals("done") ? 0x4ade80 : 0xff9e64));
		g.fill(new Ellipse2D.Double(1.2, 1.2, 5.6, 5.6));
		g.dispose();
		ImageIO.write(image, "png", new File(args[2]));
	}
}
