package io.quarkiverse.desktop.swt;

import java.util.Objects;
import java.util.function.Function;

import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Instance;

import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Widget;

import io.quarkus.arc.Arc;
import io.quarkus.arc.ArcContainer;

/**
 * Beans that create widgets : a {@code @Dependent} bean (the controller of a dialog, of a document window) is destroyed
 * with the widget it created.
 * <p>
 * SWT widgets are not beans : a widget needs its parent when it is created, and SWT forbids subclassing most widget
 * classes. A bean creates its widgets in a method instead. A {@code @Dependent} bean got from an {@code Instance} stays
 * referenced by the {@code Instance} until it is destroyed : a dialog opened many times would leak.
 * {@link #create(Instance, Function)} destroys the bean when its widget is disposed : its {@code @PreDestroy} methods
 * run (stop its timers, remove its global listeners), and its own dependent beans are destroyed.
 *
 * <pre>
 * &#64;Dependent
 * public class InvoiceDialog {
 *
 *     &#64;Inject
 *     InvoiceService service;
 *
 *     Shell open(Shell parent, Invoice invoice) {
 *         Shell shell = new Shell(parent, SWT.DIALOG_TRIM | SWT.APPLICATION_MODAL);
 *         // the widgets of the invoice...
 *         shell.pack();
 *         shell.open();
 *         return shell;
 *     }
 * }
 *
 * &#64;Inject
 * Instance&lt;InvoiceDialog&gt; dialogs;
 *
 * void edit(Invoice invoice) { // on the user interface thread
 *     WidgetBeans.create(dialogs, dialog -&gt; dialog.open(shell, invoice));
 * }
 * </pre>
 */
public final class WidgetBeans {

    private WidgetBeans() {
    }

    /**
     * Gets a bean on the user interface thread and creates its widget. A {@code @Dependent} bean is destroyed once the
     * widget is disposed (closing a shell disposes it), and not when the application already stopped, or at once when the
     * widget cannot be created. A bean of another scope is used as is, and never destroyed by this method.
     *
     * @param instance the {@code Instance} of the bean
     * @param factory creates the widget of the bean, on the user interface thread
     * @return the widget
     * @throws IllegalStateException when not called on the user interface thread (the widget would be created on another
     *         thread)
     */
    public static <B, W extends Widget> W create(Instance<B> instance, Function<? super B, W> factory) {
        Objects.requireNonNull(factory);
        if (!UiThread.isUiThread()) {
            throw new IllegalStateException("Widget beans are created on the user interface thread, not in the thread "
                    + Thread.currentThread().getName()
                    + " : use a SwtStartupEvent observer, a @RunOnUiThread method or UiThread.run");
        }
        ArcContainer container = Arc.container();
        Instance.Handle<B> handle = instance.getHandle();
        boolean dependent = container != null && handle.getBean().getScope() == Dependent.class;
        W widget;
        try {
            widget = factory.apply(handle.get());
        } catch (RuntimeException | Error e) {
            if (dependent) {
                handle.destroy();
            }
            throw e;
        }
        if (dependent) {
            if (widget == null || widget.isDisposed()) {
                handle.destroy();
            } else {
                widget.addListener(SWT.Dispose, event -> {
                    // The widgets disposed when the application stops are disposed before the container destroys the
                    // beans : the bean is destroyed then
                    if (container.isRunning()) {
                        handle.destroy();
                    }
                });
            }
        }
        return widget;
    }
}
