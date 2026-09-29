package io.quarkiverse.desktop.awt;

import java.awt.EventQueue;
import java.awt.Window;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Instance;

import io.quarkus.arc.Arc;
import io.quarkus.arc.ArcContainer;

/**
 * Windows as CDI beans : a {@code @Dependent} window (a dialog, a document window) is destroyed with its window.
 * <p>
 * A {@code @Dependent} bean got from an {@code Instance} stays referenced by the {@code Instance} until it is destroyed :
 * a dialog opened many times would leak. {@link #get(Instance)} destroys the bean when its window is closed
 * ({@code WINDOW_CLOSED}, after {@code Window.dispose()}) : its {@code @PreDestroy} methods run (stop its timers, remove
 * its global listeners), and its own dependent beans are destroyed.
 *
 * <pre>
 * &#64;Dependent
 * public class InvoiceDialog extends JDialog {
 *
 *     &#64;Inject
 *     InvoiceService service;
 *
 *     InvoiceDialog() {
 *         setDefaultCloseOperation(DISPOSE_ON_CLOSE);
 *     }
 * }
 *
 * &#64;Inject
 * Instance&lt;InvoiceDialog&gt; dialogs;
 *
 * void edit(Invoice invoice) { // on the event dispatch thread
 *     InvoiceDialog dialog = WindowBeans.get(dialogs);
 *     dialog.edit(invoice);
 *     dialog.setVisible(true);
 * }
 * </pre>
 */
public final class WindowBeans {

    private WindowBeans() {
    }

    /**
     * Gets a window bean on the event dispatch thread. A {@code @Dependent} window is destroyed once it is closed : when
     * it is disposed ({@code DISPOSE_ON_CLOSE}, {@code Window.dispose()}), not when it is only hidden
     * ({@code HIDE_ON_CLOSE}, {@code setVisible(false)}), and not when the application already stopped. A
     * {@code @Singleton} window is returned as is.
     *
     * @param instance the {@code Instance} of the window bean
     * @return the window, not shown yet
     * @throws IllegalStateException when not called on the event dispatch thread (the window would be created on
     *         another thread)
     */
    public static <W extends Window> W get(Instance<W> instance) {
        if (!EventQueue.isDispatchThread()) {
            throw new IllegalStateException("Window beans are created on the event dispatch thread, not in the thread "
                    + Thread.currentThread().getName()
                    + " : use a DesktopStartupEvent observer, a @RunOnEdt method or Edt.run");
        }
        ArcContainer container = Arc.container();
        Instance.Handle<W> handle = instance.getHandle();
        W window = handle.get();
        if (container != null && handle.getBean().getScope() == Dependent.class) {
            window.addWindowListener(new WindowAdapter() {
                @Override
                public void windowClosed(WindowEvent e) {
                    window.removeWindowListener(this);
                    // The windows disposed when the application stops (dev and test modes) are closed later, once
                    // the container destroyed the bean
                    if (container.isRunning()) {
                        handle.destroy();
                    }
                }
            });
        }
        return window;
    }
}
