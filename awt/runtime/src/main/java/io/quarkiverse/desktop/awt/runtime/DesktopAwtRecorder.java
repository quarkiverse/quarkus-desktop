package io.quarkiverse.desktop.awt.runtime;

import java.awt.AWTEvent;
import java.awt.EventQueue;
import java.awt.SystemTray;
import java.awt.Toolkit;
import java.awt.TrayIcon;
import java.awt.Window;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.EmptyStackException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.jboss.logging.Logger;

import io.quarkus.runtime.ApplicationLifecycleManager;
import io.quarkus.runtime.ShutdownContext;
import io.quarkus.runtime.annotations.Recorder;

/**
 * Run time support of the Desktop AWT extension.
 */
@Recorder
public class DesktopAwtRecorder {

    private static final Logger LOGGER = Logger.getLogger(DesktopAwtRecorder.class);

    /**
     * Directory of the files that the extension embeds in native executables.
     */
    public static final String RESOURCES = "META-INF/quarkus-desktop-awt/";

    /**
     * The logical font configuration of the JDK used for the native build (Windows).
     */
    public static final String FONT_CONFIGURATION = RESOURCES + "fontconfig.properties";

    /**
     * The PostScript font names of the JDK used for the native build ({@code lib/psfontj2d.properties}), used to print
     * text with PostScript fonts.
     */
    public static final String POSTSCRIPT_FONTS = RESOURCES + "psfontj2d.properties";

    /**
     * The Metal shader library of the JDK used for the native build ({@code lib/shaders.metallib}, macOS), used by the
     * Metal rendering pipeline of Java2D.
     */
    public static final String METAL_SHADERS = RESOURCES + "shaders.metallib";

    /**
     * The directory that {@code io.quarkus:quarkus-awt} uses as {@code java.home} in native executables, relative to
     * {@code java.io.tmpdir}.
     */
    static final String RUNTIME_HOME = "quarkus-awt-tmp-fonts";

    /**
     * Prepares the {@code java.home} directory of a native executable, which has no JDK : some JDK desktop classes read
     * files in it, and some fail when {@code java.home} is not set (for instance the Java Sound audio system and the
     * font configuration).
     * <p>
     * {@code io.quarkus:quarkus-awt} sets {@code java.home} to a temporary directory when fonts are initialized. This
     * sets it to the same directory at startup (unless it is set), so that it is set whatever AWT feature the
     * application uses first. It also writes the PostScript font names there, and makes the logical fonts use the font
     * configuration of the JDK when one is embedded.
     *
     * @param fontConfiguration the name of the directory (in {@code java.io.tmpdir}) to extract the embedded font
     *        configuration to, or {@code null} when none is embedded
     * @param metalShaders whether the Metal shaders of the JDK are embedded (macOS)
     */
    public void initRuntimeHome(String fontConfiguration, boolean metalShaders) {
        try {
            Path tmp = Path.of(System.getProperty("java.io.tmpdir"));
            Path home = tmp.resolve(RUNTIME_HOME);
            String javaHome = System.getProperty("java.home");
            if (javaHome == null || javaHome.isBlank()) {
                System.setProperty("java.home", home.toString());
            }
            Files.createDirectories(home.resolve("lib"));
            Files.createDirectories(home.resolve("conf").resolve("fonts"));
            extract(POSTSCRIPT_FONTS, home.resolve("lib").resolve("psfontj2d.properties"));
            if (fontConfiguration != null && System.getProperty("sun.awt.fontconfig") == null) {
                Path file = tmp.resolve(fontConfiguration).resolve("fontconfig.properties");
                if (extract(FONT_CONFIGURATION, file)) {
                    // Read by sun.awt.FontConfiguration instead of the configuration files of java.home
                    System.setProperty("sun.awt.fontconfig", file.toString());
                }
            }
            if (metalShaders) {
                // macOS : the shaders must match the libraries next to the executable, the directory is shared by every
                // native executable (another one may have written the shaders of another JDK)
                extractIfChanged(METAL_SHADERS, home.resolve("lib").resolve("shaders.metallib"));
            }
        } catch (IOException | RuntimeException e) {
            LOGGER.warnf(e, "Unable to prepare the java.home directory of the native executable");
        }
    }

    /**
     * Extracts a resource to a file, unless the file exists.
     *
     * @return whether the file exists
     */
    private static boolean extract(String resource, Path file) throws IOException {
        if (Files.isRegularFile(file)) {
            return true;
        }
        try (InputStream in = DesktopAwtRecorder.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                LOGGER.debugf("Resource %s not found", resource);
                return false;
            }
            Files.createDirectories(file.getParent());
            // Other processes may extract the same file at the same time : write a private copy, then move it
            Path copy = Files.createTempFile(file.getParent(), file.getFileName().toString(), ".tmp");
            try {
                Files.copy(in, copy, StandardCopyOption.REPLACE_EXISTING);
                Files.move(copy, file, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                if (!Files.isRegularFile(file)) {
                    throw e;
                }
            } finally {
                Files.deleteIfExists(copy);
            }
        }
        return true;
    }

    /**
     * Extracts a resource to a file, unless the file has the same content.
     *
     * @return whether the resource exists
     */
    static boolean extractIfChanged(String resource, Path file) throws IOException {
        byte[] data;
        try (InputStream in = DesktopAwtRecorder.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                return false;
            }
            data = in.readAllBytes();
        }
        if (Files.isRegularFile(file) && Files.size(file) == data.length && Arrays.equals(Files.readAllBytes(file), data)) {
            return true;
        }
        Files.createDirectories(file.getParent());
        // Other processes may extract the same file at the same time : write a private copy, then move it
        Path copy = Files.createTempFile(file.getParent(), file.getFileName().toString(), ".tmp");
        try {
            Files.write(copy, data);
            Files.move(copy, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(copy);
        }
        return true;
    }

    /**
     * How long the shutdown of an application in dev and test modes waits for the event dispatch thread to dispose the
     * windows.
     */
    static final long DISPOSE_TIMEOUT_SECONDS = 5;

    /**
     * Disposes the windows of the application when it stops, in dev and test modes : the AWT toolkit, its threads and its
     * windows outlive a restart of the application in the same JVM. Removes the tray icons too, whose listeners would
     * call a stopped application.
     */
    public void disposeWindowsOnShutdown(ShutdownContext shutdownContext) {
        shutdownContext.addShutdownTask(DesktopAwtRecorder::disposeWindows);
    }

    /**
     * Disposes the windows on the event dispatch thread, waiting for it at most {@link #DISPOSE_TIMEOUT_SECONDS}.
     * <p>
     * {@code Window.dispose()} called on another thread waits for the event dispatch thread
     * ({@code EventQueue.invokeAndWait}) : it would wait for ever when the event dispatch thread is blocked, for instance
     * in {@code System.exit} after a {@code JFrame.EXIT_ON_CLOSE} (it waits for the shutdown hooks, which stop the
     * application).
     */
    static void disposeWindows() {
        // Do not start the AWT toolkit to dispose windows that cannot exist ; the JVM exits anyway
        if (!isToolkitStarted() || ApplicationLifecycleManager.isVmShuttingDown()) {
            return;
        }
        if (EventQueue.isDispatchThread()) {
            disposeWindowsAndTrayIcons();
            return;
        }
        CountDownLatch disposed = new CountDownLatch(1);
        EventQueue.invokeLater(() -> {
            try {
                disposeWindowsAndTrayIcons();
            } finally {
                disposed.countDown();
            }
        });
        try {
            if (!disposed.await(DISPOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                LOGGER.warnf("The windows of the application are not disposed yet : the event dispatch thread is busy"
                        + " (blocked for %d s). They are disposed once it is free.", DISPOSE_TIMEOUT_SECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void disposeWindowsAndTrayIcons() {
        for (Window window : Window.getWindows()) {
            if (window.isDisplayable()) {
                LOGGER.debugf("Disposing %s", window);
                window.dispose();
            }
        }
        try {
            if (SystemTray.isSupported()) {
                SystemTray tray = SystemTray.getSystemTray();
                for (TrayIcon icon : tray.getTrayIcons()) {
                    LOGGER.debugf("Removing the tray icon %s", icon);
                    tray.remove(icon);
                }
            }
        } catch (RuntimeException | Error e) {
            LOGGER.debugf(e, "Unable to remove the tray icons");
        }
    }

    /**
     * Whether the AWT toolkit started : it starts threads named "AWT-...".
     */
    static boolean isToolkitStarted() {
        return Thread.getAllStackTraces().keySet().stream().anyMatch(thread -> thread.getName().startsWith("AWT-"));
    }

    /**
     * Dispatches the AWT events with the class loader of the application (dev and test modes) until it stops.
     * <p>
     * The event dispatch thread gets the context class loader of the application that started AWT first, and keeps it
     * for the life of the JVM : after a live reload in dev mode, or in a test running another application, Swing would
     * load the classes it finds by name on the event dispatch thread (look and feels, editor kits, Synth objects...)
     * with the class loader of a stopped application.
     */
    public void useApplicationClassLoaderOnEventDispatchThread(ShutdownContext shutdownContext) {
        ApplicationEventQueue queue = new ApplicationEventQueue(Thread.currentThread().getContextClassLoader());
        try {
            Toolkit.getDefaultToolkit().getSystemEventQueue().push(queue);
        } catch (RuntimeException | Error e) {
            LOGGER.debugf(e, "Unable to install the application event queue");
            return;
        }
        shutdownContext.addShutdownTask(queue::remove);
    }

    /**
     * Dispatches the events with the class loader of an application.
     * <p>
     * {@code EventQueue.pop()} removes the top queue of the chain, whatever queue it is called on : when another queue
     * was pushed after this one (by the application, or by another application of the JVM that still runs, as in
     * continuous testing), popping would remove that queue. This queue then only drops its class loader, and is removed
     * by the removal of a later queue of the extension once it is back on top.
     */
    static final class ApplicationEventQueue extends EventQueue {

        private volatile ClassLoader classLoader;

        ApplicationEventQueue(ClassLoader classLoader) {
            this.classLoader = classLoader;
        }

        @Override
        protected void dispatchEvent(AWTEvent event) {
            ClassLoader loader = classLoader;
            Thread thread = Thread.currentThread();
            if (loader != null && thread.getContextClassLoader() != loader) {
                thread.setContextClassLoader(loader);
            }
            super.dispatchEvent(event);
        }

        /**
         * Stops dispatching events with the class loader of the application (the pending events go to the previous
         * event queue) : removes this queue when it is the top one, then the queues of stopped applications below it.
         */
        void remove() {
            classLoader = null;
            EventQueue top = Toolkit.getDefaultToolkit().getSystemEventQueue();
            while (top instanceof ApplicationEventQueue queue && queue.classLoader == null) {
                try {
                    queue.pop();
                } catch (EmptyStackException e) {
                    // already removed
                    return;
                }
                EventQueue next = Toolkit.getDefaultToolkit().getSystemEventQueue();
                if (next == top) {
                    return;
                }
                top = next;
            }
        }

        boolean isRemoved() {
            return classLoader == null;
        }
    }
}
