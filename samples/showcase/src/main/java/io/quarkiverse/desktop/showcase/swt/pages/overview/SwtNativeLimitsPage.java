package io.quarkiverse.desktop.showcase.swt.pages.overview;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;

import jakarta.inject.Singleton;

import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.RowLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Scale;
import org.eclipse.swt.widgets.Text;

import io.quarkiverse.desktop.showcase.core.Check;
import io.quarkiverse.desktop.showcase.swt.core.ChecksTable;
import io.quarkiverse.desktop.showcase.swt.core.SwtCategories;
import io.quarkiverse.desktop.showcase.swt.core.SwtChecks;
import io.quarkiverse.desktop.showcase.swt.core.SwtKit;
import io.quarkiverse.desktop.showcase.swt.core.SwtMode;
import io.quarkiverse.desktop.showcase.swt.core.SwtPage;
import io.quarkiverse.desktop.swt.UiThread;

/**
 * Where a native executable legitimately differs from the JVM for SWT (a {@link #runtimeDependent()} page : its
 * differences are EXPECTED) : no {@code java} launcher (no {@code sun.java.launcher}), no JDK home, no class path, and
 * where SWT loads its native library from : {@code swt.library.path} (the directory of the executable, when
 * quarkus-desktop-swt copies the libraries next to it), {@code java.library.path}, or {@code ~/.swt/lib/<os>/<arch>},
 * where SWT extracts them from its jar (JVM) or from the resources of the executable.
 * <p>
 * The "Must hold in both runtimes" table contains real checks : a failure there is a missing native configuration. The
 * native controls show the theme of the platform : on Windows the visual styles of comctl32 v6, which SWT activates
 * itself (a manifest context), with the JVM and in a native executable.
 */
@Singleton
public class SwtNativeLimitsPage implements SwtPage {

    private static final int HALF_WIDTH = 494;

    @Override
    public String id() {
        return "swt-native-limits";
    }

    @Override
    public String title() {
        return "Native limits";
    }

    @Override
    public String category() {
        return SwtCategories.OVERVIEW;
    }

    @Override
    public int order() {
        // after swt-environment (default order 100)
        return 110;
    }

    @Override
    public boolean runtimeDependent() {
        return true;
    }

    @Override
    public Control build(Composite parent) {
        Composite page = SwtKit.page(parent, 14);
        SwtKit.heading(page, "Native limits");
        SwtKit.text(page, "What legitimately differs between the JVM (the java launcher, a JDK home and the SWT jar)"
                + " and a GraalVM native executable. The left table is expected to differ; the right table must hold in"
                + " both runtimes (a failure there is missing native configuration). The snapshot tools pin"
                + " -Dswt.autoScale=100 unless --hidpi.", SwtKit.TEXT_WIDTH);
        Composite row = SwtKit.row(page, 12);
        ChecksTable.table(row, "Expected differences", expectedDifferences(), 200, HALF_WIDTH);
        Composite right = SwtKit.column(row, 14);
        ChecksTable.table(right, "Must hold in both runtimes", mustHold(), 240, HALF_WIDTH);
        nativeControls(right);
        return page;
    }

    // ------------------------------------------------------------------------------------------ expected differences

    private static List<Check> expectedDifferences() {
        List<Check> checks = new ArrayList<>();
        checks.add(Check.info("runtime", SwtMode.runtime()));
        checks.add(Check.info("java.vm.name", property("java.vm.name")));
        checks.add(Check.info("java.vendor.version", property("java.vendor.version")));
        checks.add(Check.info("executable", executable()));
        checks.add(Check.info("sun.java.launcher", property("sun.java.launcher")));
        checks.add(Check.info("java.home", javaHomeKind()));
        checks.add(Check.info("java.class.path", classPathKind()));
        checks.add(Check.info("java.library.path", entries(System.getProperty("java.library.path"))));
        checks.add(Check.info("swt.library.path", swtLibraryPathKind()));
        checks.add(SwtChecks.info("SWT library loaded from (the first location of SWT's search order that has it)",
                SwtNativeLimitsPage::libraryLocation));
        checks.add(SwtChecks.info("SWT libraries of this version in ~/.swt/lib/<os>/<arch>",
                () -> String.join(" ", libraries(swtLibraryDirectory()))));
        return checks;
    }

    private static String property(String name) {
        String value = System.getProperty(name);
        return value == null ? "unset" : value;
    }

    /**
     * The file name of the executable (never its path).
     */
    private static String executable() {
        return ProcessHandle.current().info().command()
                .map(command -> Path.of(command).getFileName().toString().toLowerCase(Locale.ROOT))
                .orElse("unknown");
    }

    /**
     * The directory of the executable.
     */
    private static Optional<Path> executableDirectory() {
        return ProcessHandle.current().info().command().map(command -> Path.of(command).toAbsolutePath().getParent());
    }

    /**
     * What {@code java.home} designates (never the path) : a JDK runtime image, another directory, or nothing.
     */
    private static String javaHomeKind() {
        String home = System.getProperty("java.home");
        if (home == null || home.isEmpty()) {
            return "unset";
        }
        Path path = Path.of(home);
        if (Files.isRegularFile(path.resolve("lib").resolve("modules"))) {
            return "JDK runtime image (lib/modules)";
        }
        return Files.isDirectory(path) ? "another directory" : "missing directory";
    }

    private static String classPathKind() {
        String classPath = System.getProperty("java.class.path");
        if (classPath == null || classPath.isEmpty()) {
            return classPath == null ? "unset" : "empty";
        }
        return entries(classPath);
    }

    private static String entries(String path) {
        if (path == null) {
            return "unset";
        }
        return path.isEmpty() ? "empty" : path.split(File.pathSeparator).length + " entries";
    }

    private static String swtLibraryPathKind() {
        String path = System.getProperty("swt.library.path");
        if (path == null) {
            return "unset";
        }
        Path directory = Path.of(path).toAbsolutePath();
        return executableDirectory().filter(directory::equals).isPresent() ? "the directory of the executable"
                : "another directory";
    }

    /**
     * Where SWT finds its main library ({@code swt-<platform>-<version>}), in its search order (see
     * {@code org.eclipse.swt.internal.Library.loadLibrary}) : {@code swt.library.path}, {@code java.library.path}, then
     * {@code ~/.swt/lib/<os>/<arch>}.
     */
    private static String libraryLocation() {
        String path = System.getProperty("swt.library.path");
        if (path != null && !libraries(Path.of(path)).isEmpty()) {
            return "swt.library.path";
        }
        String javaLibraryPath = System.getProperty("java.library.path");
        if (javaLibraryPath != null) {
            for (String entry : javaLibraryPath.split(File.pathSeparator)) {
                if (!entry.isEmpty() && !libraries(Path.of(entry)).isEmpty()) {
                    return "java.library.path";
                }
            }
        }
        return libraries(swtLibraryDirectory()).isEmpty() ? "unknown" : "~/.swt/lib/<os>/<arch>";
    }

    /**
     * {@code ~/.swt/lib/<os>/<arch>}, where SWT extracts its libraries.
     */
    private static Path swtLibraryDirectory() {
        String os = System.getProperty("os.name", "");
        String arch = System.getProperty("os.arch", "");
        return Path.of(System.getProperty("user.home"), ".swt", "lib",
                os.equals("Linux") ? "linux" : os.equals("Mac OS X") ? "macosx" : os.startsWith("Win") ? "win32" : os,
                arch.equals("amd64") ? "x86_64" : arch);
    }

    /**
     * The native libraries of this version of SWT in {@code directory} ({@code swt-win32-4971r15.dll},
     * {@code libswt-pi3-gtk-4971r15.so}...), sorted.
     */
    private static List<String> libraries(Path directory) {
        String version = SWT.getVersion() + "r";
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(directory)) {
            return files.map(file -> file.getFileName().toString())
                    .filter(name -> name.contains("swt") && name.contains("-" + version)).sorted().toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    // --------------------------------------------------------------------------------------------------- must hold

    private static List<Check> mustHold() {
        List<Check> checks = new ArrayList<>();
        checks.add(SwtChecks.expect("user interface thread", "main", () -> Thread.currentThread().getName()));
        checks.add(SwtChecks.expect("Display.getDefault() is the Display of the application", true,
                () -> Display.getDefault() == UiThread.display()));
        checks.add(SwtChecks.run("SWT.getVersion()", SWT::getVersion));
        checks.add(SwtChecks.expect("Locale.getDefault()", "en-US", () -> Locale.getDefault().toLanguageTag()));
        checks.add(SwtChecks.expect("Display.getAppName()", "quarkus-desktop-showcase", Display::getAppName));
        // a system property of the run, passed alike to the JVM and to the native executable
        checks.add(Check.info("swt.autoScale (the tools pin 100 unless --hidpi)", property("swt.autoScale")));
        return checks;
    }

    // --------------------------------------------------------------------------------------------- native controls

    /**
     * Native controls : themed by the platform (the visual styles of comctl32 v6 on Windows, which SWT activates
     * itself).
     */
    private static void nativeControls(Composite parent) {
        Composite column = SwtKit.column(parent, 6);
        SwtKit.title(column, "Native controls (the theme of the platform)");
        SwtKit.caption(column, "Windows : the visual styles of comctl32 v6, which SWT activates itself");
        Composite panel = new Composite(column, SWT.NONE);
        RowLayout layout = new RowLayout(SWT.HORIZONTAL);
        layout.spacing = 8;
        layout.marginWidth = 8;
        layout.marginHeight = 8;
        layout.center = true;
        panel.setLayout(layout);
        panel.setBackground(SwtKit.color(0xF3F4F6));
        SwtKit.size(panel, HALF_WIDTH, SWT.DEFAULT);
        Button push = new Button(panel, SWT.PUSH);
        push.setText("Button");
        Button check = new Button(panel, SWT.CHECK);
        check.setText("Check");
        check.setSelection(true);
        Button radio = new Button(panel, SWT.RADIO);
        radio.setText("Radio");
        radio.setSelection(true);
        Combo combo = new Combo(panel, SWT.READ_ONLY);
        combo.setItems("Combo");
        combo.select(0);
        Text text = new Text(panel, SWT.BORDER);
        text.setText("Text");
        Scale scale = new Scale(panel, SWT.HORIZONTAL);
        scale.setMaximum(100);
        scale.setSelection(40);
        SwtKit.size(scale, 120, SWT.DEFAULT);
    }
}
