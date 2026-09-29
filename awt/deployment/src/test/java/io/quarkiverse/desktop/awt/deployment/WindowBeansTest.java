package io.quarkiverse.desktop.awt.deployment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.awt.Dialog;
import java.awt.EventQueue;
import java.awt.Frame;
import java.awt.GraphicsEnvironment;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkiverse.desktop.awt.WindowBeans;
import io.quarkus.test.QuarkusExtensionTest;

/**
 * {@code WindowBeans} destroys a {@code @Dependent} window bean once its window is disposed, not a {@code @Singleton}
 * one, and refuses to create windows outside the event dispatch thread. The windows need a display.
 */
class WindowBeansTest {

    static final AtomicInteger DIALOGS_DESTROYED = new AtomicInteger();

    static final AtomicInteger FRAMES_DESTROYED = new AtomicInteger();

    @Dependent
    public static class EditDialog extends Dialog {

        public EditDialog() {
            super((Frame) null, "edit");
        }

        @PreDestroy
        void destroy() {
            DIALOGS_DESTROYED.incrementAndGet();
        }
    }

    @Singleton
    public static class MainFrame extends Frame {

        @PreDestroy
        void destroy() {
            FRAMES_DESTROYED.incrementAndGet();
        }
    }

    @RegisterExtension
    static final QuarkusExtensionTest TEST = new QuarkusExtensionTest()
            .withApplicationRoot(root -> root.addClasses(EditDialog.class, MainFrame.class));

    @Inject
    Instance<EditDialog> dialogs;

    @Inject
    Instance<MainFrame> frames;

    @Test
    void onlyOnEventDispatchThread() {
        assertThrows(IllegalStateException.class, () -> WindowBeans.get(dialogs));
    }

    @Test
    void dependentWindowDestroyedWhenDisposed() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "needs a display");
        AtomicReference<EditDialog> first = new AtomicReference<>();
        EventQueue.invokeAndWait(() -> {
            first.set(WindowBeans.get(dialogs));
            first.get().pack();
        });
        EventQueue.invokeAndWait(() -> {
            // hidden : not destroyed
            first.get().setVisible(false);
        });
        flush();
        assertEquals(0, DIALOGS_DESTROYED.get());
        EventQueue.invokeAndWait(() -> first.get().dispose());
        flush();
        assertEquals(1, DIALOGS_DESTROYED.get());
        // disposed again (made displayable again) : destroyed once
        EventQueue.invokeAndWait(() -> {
            first.get().pack();
            first.get().dispose();
        });
        flush();
        assertEquals(1, DIALOGS_DESTROYED.get());

        AtomicReference<MainFrame> frame = new AtomicReference<>();
        EventQueue.invokeAndWait(() -> {
            frame.set(WindowBeans.get(frames));
            assertSame(frame.get(), WindowBeans.get(frames));
            frame.get().pack();
            frame.get().dispose();
        });
        flush();
        assertEquals(0, FRAMES_DESTROYED.get());
    }

    /**
     * Waits for the events posted by the previous ones ({@code WINDOW_CLOSED} is posted by {@code dispose()}).
     */
    private static void flush() throws Exception {
        for (int i = 0; i < 3; i++) {
            EventQueue.invokeAndWait(() -> {
            });
        }
    }
}
