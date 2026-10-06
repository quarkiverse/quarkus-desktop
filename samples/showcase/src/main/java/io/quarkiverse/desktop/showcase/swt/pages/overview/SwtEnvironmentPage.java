package io.quarkiverse.desktop.showcase.swt.pages.overview;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import jakarta.inject.Singleton;

import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Monitor;

import io.quarkiverse.desktop.showcase.core.Check;
import io.quarkiverse.desktop.showcase.swt.core.ChecksTable;
import io.quarkiverse.desktop.showcase.swt.core.SwtCategories;
import io.quarkiverse.desktop.showcase.swt.core.SwtChecks;
import io.quarkiverse.desktop.showcase.swt.core.SwtEnvironment;
import io.quarkiverse.desktop.showcase.swt.core.SwtKit;
import io.quarkiverse.desktop.showcase.swt.core.SwtPage;
import io.quarkiverse.desktop.swt.UiThread;

/**
 * The environment of the SWT run (the same values as the top of report.json, without those that legitimately differ
 * between the JVM and a native executable), the user interface thread of quarkus-desktop-swt, the display and its
 * monitors, the fonts and the system settings that SWT reports.
 * <p>
 * Every value is computed when the page is built : the {@code Display} answers at once.
 */
@Singleton
public class SwtEnvironmentPage implements SwtPage {

    private static final int HALF_WIDTH = 494;

    @Override
    public String id() {
        return "swt-environment";
    }

    @Override
    public String title() {
        return "Environment";
    }

    @Override
    public String category() {
        return SwtCategories.OVERVIEW;
    }

    @Override
    public Control build(Composite parent) {
        Display display = parent.getDisplay();
        Composite page = SwtKit.page(parent, 14);
        SwtKit.heading(page, "Quarkus Desktop Showcase");
        SwtKit.text(page, "Every page exercises an SWT feature area, with the native widgets of the platform. In"
                + " snapshot mode, pages are rendered to images and compared between a JVM run and a native run,"
                + " together with their checks.", SwtKit.TEXT_WIDTH);
        ChecksTable.table(page, "Runtime environment", runtime());
        Composite row = SwtKit.row(page, 12);
        ChecksTable.table(row, "Display and monitors", display(display), 250, HALF_WIDTH);
        ChecksTable.table(row, "Fonts and system settings", system(display), 250, HALF_WIDTH);
        return page;
    }

    private static List<Check> runtime() {
        List<Check> checks = new ArrayList<>();
        // quarkus-desktop-swt runs the user interface on the main thread of the application (no @QuarkusMain), the
        // first thread of the process : the thread SWT requires on macOS
        checks.add(SwtChecks.expect("user interface thread (the thread of the Display)", "main",
                () -> Thread.currentThread().getName()));
        checks.add(SwtChecks.expect("UiThread.isUiThread()", true, UiThread::isUiThread));
        checks.add(SwtChecks.expect("Display.getCurrent() is UiThread.display()", true,
                () -> Display.getCurrent() == UiThread.display()));
        checks.add(SwtChecks.expect("SWT platform of the operating system", expectedPlatform(), SWT::getPlatform));
        checks.add(SwtChecks.expect("Display.getAppName() (quarkus.application.name)", "quarkus-desktop-showcase",
                Display::getAppName));
        Map<String, Object> env = SwtEnvironment.describe();
        env.forEach((key, value) -> {
            if (!SwtEnvironment.INFO_KEYS.contains(key)) {
                checks.add(Check.info(key, value));
            }
        });
        return checks;
    }

    private static List<Check> display(Display display) {
        List<Check> checks = new ArrayList<>();
        checks.add(SwtChecks.info("Display.getBounds()", () -> SwtChecks.rect(display.getBounds())));
        checks.add(SwtChecks.info("Display.getClientArea()", () -> SwtChecks.rect(display.getClientArea())));
        checks.add(SwtChecks.info("Display.getDPI()", () -> SwtChecks.size(display.getDPI())));
        checks.add(SwtChecks.info("Display.getDepth()", display::getDepth));
        checks.add(SwtChecks.info("Display.getIconDepth()", display::getIconDepth));
        checks.add(SwtChecks.info("Display.getIconSizes()", () -> Arrays.stream(display.getIconSizes())
                .map(SwtChecks::size).collect(Collectors.joining(" "))));
        Monitor primary = display.getPrimaryMonitor();
        Monitor[] monitors = display.getMonitors();
        checks.add(Check.info("monitors", monitors.length));
        for (int i = 0; i < monitors.length; i++) {
            Monitor monitor = monitors[i];
            String prefix = "monitor " + i + (monitor.equals(primary) ? " (primary)" : "");
            checks.add(Check.info(prefix + " bounds", SwtChecks.rect(monitor.getBounds())));
            checks.add(Check.info(prefix + " client area", SwtChecks.rect(monitor.getClientArea())));
            checks.add(Check.info(prefix + " zoom", monitor.getZoom() + " %"));
        }
        checks.add(SwtChecks.info("Display.getSystemTray()",
                () -> display.getSystemTray() == null ? "none" : "present"));
        checks.add(SwtChecks.info("Display.getSystemTaskBar()",
                () -> display.getSystemTaskBar() == null ? "none" : "present"));
        checks.add(SwtChecks.info("Display.getSystemMenu() (macOS)",
                () -> display.getSystemMenu() == null ? "none" : display.getSystemMenu().getItemCount() + " items"));
        checks.add(SwtChecks.info("Display.getTouchEnabled()", display::getTouchEnabled));
        return checks;
    }

    private static List<Check> system(Display display) {
        List<Check> checks = new ArrayList<>();
        checks.add(SwtChecks.info("SWT.getPlatform()", SWT::getPlatform));
        checks.add(SwtChecks.info("SWT.getVersion()", SWT::getVersion));
        checks.add(SwtChecks.info("Display.getAppName()", Display::getAppName));
        checks.add(SwtChecks.info("Display.getSystemFont()",
                () -> SwtChecks.font(display.getSystemFont().getFontData()[0])));
        checks.add(SwtChecks.info("scalable fonts (Display.getFontList)",
                () -> display.getFontList(null, true).length));
        checks.add(SwtChecks.info("bitmap fonts (Display.getFontList)", () -> display.getFontList(null, false).length));
        checks.add(SwtChecks.info("Display.isSystemDarkTheme()", Display::isSystemDarkTheme));
        checks.add(SwtChecks.info("Display.getHighContrast()", display::getHighContrast));
        checks.add(SwtChecks.info("Display.getDoubleClickTime()", () -> display.getDoubleClickTime() + " ms"));
        checks.add(SwtChecks.info("Display.getDismissalAlignment()",
                () -> display.getDismissalAlignment() == SWT.LEFT ? "LEFT" : "RIGHT"));
        // the colors of the theme : the same in both runs
        String[] names = { "WIDGET_BACKGROUND", "WIDGET_FOREGROUND", "LIST_SELECTION", "INFO_BACKGROUND",
                "LINK_FOREGROUND" };
        int[] colors = { SWT.COLOR_WIDGET_BACKGROUND, SWT.COLOR_WIDGET_FOREGROUND, SWT.COLOR_LIST_SELECTION,
                SWT.COLOR_INFO_BACKGROUND, SWT.COLOR_LINK_FOREGROUND };
        for (int i = 0; i < names.length; i++) {
            int color = colors[i];
            checks.add(SwtChecks.info("SWT.COLOR_" + names[i],
                    () -> SwtChecks.rgb(display.getSystemColor(color).getRGB())));
        }
        return checks;
    }

    /**
     * The SWT platform of the operating system ({@code os.name}) : the SWT jar that Maven chose for the build.
     */
    private static String expectedPlatform() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return os.startsWith("windows") ? "win32" : os.startsWith("mac") ? "cocoa" : "gtk";
    }
}
