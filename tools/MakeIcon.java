import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/** Renders the view icon (same drawing as icons/xterm.svg). Usage: java tools/MakeIcon.java <xterm|xterm-running|xterm-done> <size> <file.png> */
public class MakeIcon {
	public static void main(String[] args) throws Exception {
		int size = Integer.parseInt(args[1]);
		BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = image.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
		g.scale(size / 16.0, size / 16.0);
		g.setPaint(new GradientPaint(0, 1, new Color(0x8b5cf6), 16, 15, new Color(0x22d3ee)));
		g.fill(new RoundRectangle2D.Double(0.5, 1.5, 15, 13, 6, 6));
		g.setPaint(new Color(0x16161e));
		g.fill(new RoundRectangle2D.Double(1.5, 2.5, 13, 11, 4.4, 4.4));
		Path2D chevron = new Path2D.Double();
		chevron.moveTo(4, 5.2);
		chevron.lineTo(7.2, 8);
		chevron.lineTo(4, 10.8);
		g.setPaint(new Color(0x22d3ee));
		g.setStroke(new BasicStroke(1.7f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		g.draw(chevron);
		g.setPaint(new Color(0xff9e64));
		g.fill(new RoundRectangle2D.Double(8.4, 10, 3.8, 1.6, 1, 1));
		if (args[0].equals("xterm-running") || args[0].equals("xterm-done")) {
			// Status badge in the lower right corner, with a dark ring to detach it from the icon.
			g.setPaint(new Color(0x16161e));
			g.fill(new Ellipse2D.Double(7.6, 7.6, 8.4, 8.4));
			g.setPaint(new Color(args[0].equals("xterm-done") ? 0x4ade80 : 0xff9e64));
			g.fill(new Ellipse2D.Double(8.8, 8.8, 6, 6));
		}
		g.dispose();
		ImageIO.write(image, "png", new File(args[2]));
	}
}
