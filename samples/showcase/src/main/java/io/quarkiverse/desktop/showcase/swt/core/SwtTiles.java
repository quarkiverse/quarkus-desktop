package io.quarkiverse.desktop.showcase.swt.core;

import java.util.List;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.widgets.Display;

/**
 * Captioned tiles painted offscreen into one image, in a grid : for each tile a background, a border, the drawing area
 * (its own image : the painter draws from (0, 0), clipped to the area, with anti-aliasing on) and a centered caption.
 * The SWT counterpart of {@code core/Grid} : pages show the image ({@link SwtKit#image}) and probe its pixels
 * ({@link #probe}) ; the capture does not depend on the screen.
 * <p>
 * Colors are {@code int} constants and every SWT object is created when the tiles are painted.
 */
public final class SwtTiles {

    public static final int BACKGROUND = 0xF7F9FC;
    public static final int BORDER = 0xB0BEC5;
    public static final int INK = 0x263238;

    /** Height of the caption area at the bottom of a tile. */
    public static final int CAPTION_HEIGHT = 24;
    /** Margin around the drawing area of a tile. */
    public static final int INSET = 8;

    /**
     * Paints the drawing area of a tile : {@code width x height} points, the origin at the top left corner of the area.
     */
    @FunctionalInterface
    public interface Painter {
        void paint(GC gc, int width, int height);
    }

    /**
     * A tile : its caption (lines separated by {@code \n}) and the painter of its drawing area.
     */
    public record Tile(String caption, Painter painter) {
    }

    private final int columns;
    private final int tileWidth;
    private final int tileHeight;
    private final List<Tile> tiles;

    public SwtTiles(int columns, int tileWidth, int tileHeight, List<Tile> tiles) {
        this.columns = columns;
        this.tileWidth = tileWidth;
        this.tileHeight = tileHeight;
        this.tiles = List.copyOf(tiles);
    }

    public List<Tile> tiles() {
        return tiles;
    }

    public int areaWidth() {
        return tileWidth - 2 * INSET;
    }

    public int areaHeight() {
        return tileHeight - INSET - CAPTION_HEIGHT;
    }

    public int width() {
        return columns * tileWidth;
    }

    public int height() {
        return (tiles.size() + columns - 1) / columns * tileHeight;
    }

    /**
     * Index of the tile with {@code caption}.
     */
    public int index(String caption) {
        for (int i = 0; i < tiles.size(); i++) {
            if (tiles.get(i).caption().equals(caption)) {
                return i;
            }
        }
        throw new IllegalArgumentException("No tile " + caption);
    }

    /**
     * Top left corner of the drawing area of tile {@code index}, in the image of the tiles.
     */
    public Point origin(int index) {
        return new Point((index % columns) * tileWidth + INSET, (index / columns) * tileHeight + INSET);
    }

    /**
     * {@code #AARRGGBB} of the pixel at ({@code x}, {@code y}) of the drawing area of the tile with {@code caption}
     * (an image painted at 100 %).
     */
    public String probe(ImageData image, String caption, int x, int y) {
        Point o = origin(index(caption));
        return SwtChecks.argb(SwtSnapshots.pixel(image, o.x + x, o.y + y));
    }

    /**
     * Paints the tiles (on the user interface thread).
     */
    public ImageData paint() {
        Display display = UiStages.display();
        return SwtSnapshots.offscreen(width(), height(), gc -> {
            for (int i = 0; i < tiles.size(); i++) {
                paintTile(display, gc, (i % columns) * tileWidth, (i / columns) * tileHeight, tiles.get(i));
            }
        });
    }

    private void paintTile(Display display, GC gc, int x, int y, Tile tile) {
        gc.setBackground(SwtKit.color(BACKGROUND));
        gc.fillRectangle(x, y, tileWidth, tileHeight);
        gc.setForeground(SwtKit.color(BORDER));
        gc.drawRectangle(x + 2, y + 2, tileWidth - 5, tileHeight - 5);
        Image area = new Image(display, areaWidth(), areaHeight());
        try {
            GC agc = new GC(area);
            try {
                agc.setBackground(SwtKit.color(BACKGROUND));
                agc.fillRectangle(0, 0, areaWidth(), areaHeight());
                agc.setAntialias(SWT.ON);
                agc.setTextAntialias(SWT.ON);
                agc.setForeground(SwtKit.color(INK));
                agc.setBackground(SwtKit.color(INK));
                agc.setFont(SwtKit.font(SWT.NORMAL, 8));
                tile.painter().paint(agc, areaWidth(), areaHeight());
            } finally {
                agc.dispose();
            }
            gc.drawImage(area, x + INSET, y + INSET);
        } finally {
            area.dispose();
        }
        gc.setTextAntialias(SWT.ON);
        gc.setForeground(SwtKit.color(INK));
        gc.setFont(SwtKit.font(SWT.NORMAL, 8));
        String[] lines = tile.caption().split("\n");
        int lineHeight = gc.getFontMetrics().getHeight();
        int top = y + tileHeight - 4 - lines.length * lineHeight;
        for (String line : lines) {
            Point extent = gc.stringExtent(line);
            gc.drawString(line, x + (tileWidth - extent.x) / 2, top, true);
            top += lineHeight;
        }
    }
}
