package io.quarkiverse.desktop.showcase.swt.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Monitor;

/**
 * The environment of an SWT run : written at the top of report.json and shown by the {@code swt-environment} page. The
 * SWT counterpart of {@code core/Environment}.
 * <p>
 * tools/Compare.java compares every key between two runs (a difference is an {@code ENV DIFF} mismatch : e.g. another
 * zoom, a DPI unaware native executable, another theme), except the informational ones ({@link #INFO_KEYS}, which
 * legitimately differ between a JVM and a native executable). They are a subset of {@code Compare.ENV_INFO_KEYS} and of
 * {@code core/Environment.INFO_KEYS} : a new informational key must be added to both lists.
 * <p>
 * Call it on the user interface thread.
 */
public final class SwtEnvironment {

    /**
     * Keys that are expected to differ between the JVM and a native executable : reported, never a mismatch.
     */
    public static final List<String> INFO_KEYS = List.of("runtime", "javaVendorVersion", "os");

    private SwtEnvironment() {
    }

    public static Map<String, Object> describe() {
        Display display = UiStages.display();
        Map<String, Object> env = new LinkedHashMap<>();
        env.put("runtime", SwtMode.runtime());
        env.put("javaVersion", System.getProperty("java.version"));
        env.put("javaVendorVersion", String.valueOf(System.getProperty("java.vendor.version")));
        env.put("os", System.getProperty("os.name") + " " + System.getProperty("os.version") + " "
                + System.getProperty("os.arch"));
        env.put("locale", Locale.getDefault().toLanguageTag());
        env.put("ui", "swt");
        env.put("swtPlatform", SWT.getPlatform());
        env.put("swtVersion", SWT.getVersion());
        // the tools pin -Dswt.autoScale=100 unless --hidpi : the same in both runs
        env.put("swtAutoScale", property("swt.autoScale"));
        // the zoom SWT draws with (from swt.autoScale and the monitor zoom), the zoom of the snapshots
        env.put("swtDeviceZoom", SwtSnapshots.deviceZoom());
        // the DPI awareness of the process : a DPI unaware native executable gets 96 dpi where the JVM (java.exe
        // declares itself DPI aware) gets the real resolution of the monitor on Windows
        Point dpi = display.getDPI();
        env.put("dpi", dpi.x + "x" + dpi.y);
        // the zoom of the monitor, whatever swt.autoScale (Monitor.getZoom)
        env.put("primaryMonitorZoom", display.getPrimaryMonitor().getZoom());
        env.put("monitors", monitors(display));
        env.put("systemFont", SwtChecks.font(display.getSystemFont().getFontData()[0]));
        env.put("darkTheme", Display.isSystemDarkTheme());
        env.put("highContrast", display.getHighContrast());
        // the thread of the Display : main (SwtLifecycle.run() on the main thread of the application) in both runtimes
        env.put("mainThread", display.getThread().getName());
        // Display.setAppName : quarkus.desktop.swt.application-name, quarkus.application.name by default
        env.put("applicationName", String.valueOf(Display.getAppName()));
        return env;
    }

    /**
     * {@code x,y wxh zoom N} of every monitor, the primary one first.
     */
    static String monitors(Display display) {
        Monitor primary = display.getPrimaryMonitor();
        List<String> monitors = new ArrayList<>();
        monitors.add(monitor(primary));
        for (Monitor monitor : display.getMonitors()) {
            if (!monitor.equals(primary)) {
                monitors.add(monitor(monitor));
            }
        }
        return String.join(" ; ", monitors);
    }

    static String monitor(Monitor monitor) {
        return SwtChecks.rect(monitor.getBounds()) + " zoom " + monitor.getZoom();
    }

    private static String property(String name) {
        String value = System.getProperty(name);
        return value == null ? "unset" : value;
    }
}
