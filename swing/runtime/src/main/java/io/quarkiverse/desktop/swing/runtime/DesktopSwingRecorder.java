package io.quarkiverse.desktop.swing.runtime;

import java.awt.AWTEvent;
import java.awt.EventQueue;
import java.awt.GraphicsEnvironment;
import java.awt.Toolkit;
import java.awt.event.AWTEventListener;
import java.awt.event.WindowEvent;
import java.lang.reflect.InvocationTargetException;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javax.swing.JFrame;
import javax.swing.LookAndFeel;
import javax.swing.UIManager;
import javax.swing.WindowConstants;

import org.jboss.logging.Logger;

import io.quarkus.runtime.RuntimeValue;
import io.quarkus.runtime.ShutdownContext;
import io.quarkus.runtime.annotations.Recorder;

/**
 * Run time support of the Desktop Swing extension.
 */
@Recorder
public class DesktopSwingRecorder {

    private static final Logger LOGGER = Logger.getLogger(DesktopSwingRecorder.class);

    private final RuntimeValue<DesktopSwingConfig> config;

    public DesktopSwingRecorder(RuntimeValue<DesktopSwingConfig> config) {
        this.config = config;
    }

    /**
     * Sets the configured look and feel ({@code quarkus.desktop.swing.look-and-feel}), on the event dispatch thread.
     */
    public void setLookAndFeel() {
        Optional<String> lookAndFeel = config.getValue().lookAndFeel().map(String::trim).filter(s -> !s.isEmpty());
        if (lookAndFeel.isEmpty()) {
            return;
        }
        // The application class loader : the event dispatch thread may have the one of a previous application (dev mode)
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        try {
            EventQueue.invokeAndWait(() -> {
                String className = className(lookAndFeel.get());
                try {
                    if (isJdkClass(className)) {
                        // Swing creates the look and feels of the JDK (their packages are not exported)
                        UIManager.setLookAndFeel(className);
                    } else {
                        UIManager.setLookAndFeel((LookAndFeel) Class.forName(className, true, classLoader)
                                .getDeclaredConstructor().newInstance());
                    }
                    LOGGER.debugf("Look and feel set to %s", className);
                } catch (Exception | LinkageError e) {
                    LOGGER.warnf("Unable to set the look and feel %s (quarkus.desktop.swing.look-and-feel=%s) : %s. The"
                            + " look and feel is %s.", className, lookAndFeel.get(), e,
                            UIManager.getLookAndFeel().getName());
                }
            });
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (InvocationTargetException e) {
            LOGGER.warnf(e.getCause(), "Unable to set the look and feel %s", lookAndFeel.get());
        }
    }

    /**
     * Warns about the frames that close with {@code EXIT_ON_CLOSE}, in dev mode : closing one calls {@code System.exit} on
     * the event dispatch thread, which ends dev mode instead of stopping the application.
     */
    public void warnExitOnClose(ShutdownContext shutdownContext) {
        if (GraphicsEnvironment.isHeadless()) {
            return;
        }
        Set<String> warned = ConcurrentHashMap.newKeySet();
        AWTEventListener listener = event -> {
            if (event.getID() == WindowEvent.WINDOW_OPENED && event.getSource() instanceof JFrame frame
                    && frame.getDefaultCloseOperation() == WindowConstants.EXIT_ON_CLOSE
                    && warned.add(frame.getClass().getName())) {
                LOGGER.warnf("%s closes with EXIT_ON_CLOSE : closing it exits the JVM, which ends dev mode. Use"
                        + " DISPOSE_ON_CLOSE : the application stops when its last window closes"
                        + " (quarkus.desktop.awt.exit-on-last-window-closed), or when it calls Quarkus.asyncExit().",
                        frame.getClass().getName());
            }
        };
        Toolkit.getDefaultToolkit().addAWTEventListener(listener, AWTEvent.WINDOW_EVENT_MASK);
        shutdownContext.addShutdownTask(() -> Toolkit.getDefaultToolkit().removeAWTEventListener(listener));
    }

    static boolean isJdkClass(String className) {
        return className.startsWith("javax.swing.") || className.startsWith("com.sun.java.swing.")
                || className.startsWith("com.apple.laf.");
    }

    /**
     * The look and feel class of a configured value : a look and feel name, or a class name.
     */
    static String className(String lookAndFeel) {
        return switch (lookAndFeel.toLowerCase(Locale.ROOT)) {
            case "system" -> UIManager.getSystemLookAndFeelClassName();
            case "cross-platform" -> UIManager.getCrossPlatformLookAndFeelClassName();
            default -> knownClassName(lookAndFeel).orElse(lookAndFeel);
        };
    }

    /**
     * The class of a JDK look and feel name ({@code metal}, {@code nimbus}, {@code motif}, {@code windows},
     * {@code windows-classic}, {@code gtk}), empty for other values (class names).
     */
    public static Optional<String> knownClassName(String lookAndFeel) {
        return Optional.ofNullable(switch (lookAndFeel.toLowerCase(Locale.ROOT)) {
            case "metal" -> "javax.swing.plaf.metal.MetalLookAndFeel";
            case "nimbus" -> "javax.swing.plaf.nimbus.NimbusLookAndFeel";
            case "motif" -> "com.sun.java.swing.plaf.motif.MotifLookAndFeel";
            case "windows" -> "com.sun.java.swing.plaf.windows.WindowsLookAndFeel";
            case "windows-classic" -> "com.sun.java.swing.plaf.windows.WindowsClassicLookAndFeel";
            case "gtk" -> "com.sun.java.swing.plaf.gtk.GTKLookAndFeel";
            default -> null;
        });
    }
}
