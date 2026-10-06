package io.quarkiverse.desktop.showcase.swt.core;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.FontData;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.graphics.RGBA;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Widget;

import io.quarkiverse.desktop.showcase.core.Check;

/**
 * Helpers to run checks, attach them to a control of the page content and format deterministic values : the SWT
 * counterpart of {@code core/Checks}.
 * <p>
 * The checks are attached to a control with {@link Widget#setData(String, Object)} under {@link #KEY} (by
 * {@link #attach} or by {@link ChecksTable}), and collected from the control tree of the page content once the page is
 * ready ({@link #collect}) : they end up in report.json. Call the methods taking a widget on the user interface thread.
 */
public final class SwtChecks {

    /**
     * The key of the checks of a control ({@link Widget#getData(String)}) : an unmodifiable {@code List<Check>}.
     */
    public static final String KEY = "io.quarkiverse.desktop.showcase.checks";

    private SwtChecks() {
    }

    /**
     * Runs {@code action} : passed with the returned value, or failed with the exception.
     */
    public static Check run(String name, Callable<?> action) {
        try {
            return Check.pass(name, action.call());
        } catch (Throwable t) {
            return Check.fail(name, describe(t));
        }
    }

    /**
     * Runs {@code action} : passed if the returned value equals {@code expected} (compared as strings when the types
     * differ, e.g. an Integer and a String).
     */
    public static Check expect(String name, Object expected, Callable<?> action) {
        try {
            Object actual = action.call();
            boolean ok = expected == null ? actual == null
                    : expected.equals(actual) || (actual != null && expected.toString().equals(actual.toString()));
            return Check.of(name, ok, ok ? actual : "expected " + expected + " but got " + actual);
        } catch (Throwable t) {
            return Check.fail(name, describe(t));
        }
    }

    /**
     * Runs {@code action} : informational check with the returned value, or failed with the exception.
     */
    public static Check info(String name, Callable<?> action) {
        try {
            return Check.info(name, action.call());
        } catch (Throwable t) {
            return Check.fail(name, describe(t));
        }
    }

    /**
     * {@code check} as is on {@code os}, and informational on the other operating systems (for expectations verified
     * on one operating system only).
     */
    public static Check onlyOn(SwtMode.Os os, Check check) {
        return SwtMode.os() == os || !Boolean.FALSE.equals(check.ok()) ? check
                : Check.info(check.name(), check.value());
    }

    /**
     * {@code check} as is on {@code os} and on {@code other}, and informational on the third operating system.
     */
    public static Check onlyOn(SwtMode.Os os, SwtMode.Os other, Check check) {
        return SwtMode.os() == other ? check : onlyOn(os, check);
    }

    /**
     * {@code class: message <- cause class: message...} (5 causes at most).
     */
    public static String describe(Throwable t) {
        StringBuilder sb = new StringBuilder(t.getClass().getName());
        if (t.getMessage() != null) {
            sb.append(": ").append(t.getMessage());
        }
        Throwable cause = t.getCause();
        int depth = 0;
        while (cause != null && cause != t && depth++ < 5) {
            sb.append(" <- ").append(cause.getClass().getName());
            if (cause.getMessage() != null) {
                sb.append(": ").append(cause.getMessage());
            }
            t = cause;
            cause = cause.getCause();
        }
        return sb.toString();
    }

    // -------------------------------------------------------------------------------------------- attach and collect

    /**
     * Attaches {@code checks} to {@code widget} (replacing the checks attached before).
     */
    public static <W extends Widget> W attach(W widget, List<Check> checks) {
        widget.setData(KEY, List.copyOf(checks));
        return widget;
    }

    /**
     * The checks attached to {@code widget}.
     */
    public static List<Check> of(Widget widget) {
        return widget.getData(KEY) instanceof List<?> list ? list.stream().map(Check.class::cast).toList() : List.of();
    }

    /**
     * Collects the checks attached to {@code root} and to all its descendants (depth first, in the order of the
     * children).
     */
    public static List<Check> collect(Control root) {
        List<Check> all = new ArrayList<>();
        collect(root, all);
        return all;
    }

    private static void collect(Control control, List<Check> all) {
        if (control.isDisposed()) {
            return;
        }
        all.addAll(of(control));
        if (control instanceof Composite composite) {
            for (Control child : composite.getChildren()) {
                collect(child, all);
            }
        }
    }

    // ------------------------------------------------------------------------------------ deterministic formatting

    /**
     * {@code value} with {@code decimals} decimals, {@code .} as decimal separator, and no negative zero.
     */
    public static String num(double value, int decimals) {
        String s = String.format(Locale.ROOT, "%." + decimals + "f", value);
        return s.matches("-0(\\.0*)?") ? s.substring(1) : s;
    }

    /**
     * {@code value} with 3 decimals.
     */
    public static String num(double value) {
        return num(value, 3);
    }

    /**
     * {@code x,y wxh}.
     */
    public static String rect(Rectangle r) {
        return r.x + "," + r.y + " " + r.width + "x" + r.height;
    }

    /**
     * {@code x,y}.
     */
    public static String point(Point p) {
        return p.x + "," + p.y;
    }

    /**
     * {@code wxh}.
     */
    public static String size(Point p) {
        return p.x + "x" + p.y;
    }

    /**
     * {@code #RRGGBB}.
     */
    public static String rgb(RGB rgb) {
        return String.format(Locale.ROOT, "#%02X%02X%02X", rgb.red, rgb.green, rgb.blue);
    }

    /**
     * {@code #AARRGGBB}.
     */
    public static String rgba(RGBA rgba) {
        return argb(rgba.alpha << 24 | rgba.rgb.red << 16 | rgba.rgb.green << 8 | rgba.rgb.blue);
    }

    /**
     * {@code #AARRGGBB}.
     */
    public static String argb(int argb) {
        return String.format(Locale.ROOT, "#%08X", argb);
    }

    /**
     * {@code name height style} of a font : {@code Segoe UI 9 bold italic}, the height in points (rounded).
     */
    public static String font(FontData data) {
        StringBuilder sb = new StringBuilder(data.getName()).append(' ').append(data.getHeight());
        int style = data.getStyle();
        if (style == 0) {
            sb.append(" normal");
        }
        if ((style & SWT.BOLD) != 0) {
            sb.append(" bold");
        }
        if ((style & SWT.ITALIC) != 0) {
            sb.append(" italic");
        }
        return sb.toString();
    }

    /**
     * SHA-256 of {@code bytes}, first 16 hex digits (enough to detect a difference).
     */
    public static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String sha256(String text) {
        return sha256(text.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * SHA-256 of the ARGB pixels of {@code data} (first 16 hex digits) : the same digest as {@code core/Checks} for the
     * same pixels, whatever the depth and the palette of the image.
     */
    public static String sha256(ImageData data) {
        int[] argb = SwtSnapshots.argb(data);
        ByteBuffer bytes = ByteBuffer.allocate(argb.length * 4 + 8);
        bytes.putInt(data.width).putInt(data.height);
        bytes.asIntBuffer().put(argb);
        return sha256(bytes.array());
    }
}
