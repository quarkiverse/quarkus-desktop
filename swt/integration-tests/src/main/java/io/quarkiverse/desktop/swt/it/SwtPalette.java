package io.quarkiverse.desktop.swt.it;

import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.RGB;

/**
 * SWT values in static fields : the static initializer uses SWT classes, which are initialized at run time in a native
 * executable, so this class must be initialized at run time too (the extension detects it).
 */
public final class SwtPalette {

    public static final RGB ACCENT = new RGB(0, 150, 201);

    public static final Point MINIMUM_SIZE = new Point(320, 200);

    private SwtPalette() {
    }
}
