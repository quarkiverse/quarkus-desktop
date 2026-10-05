package io.quarkiverse.desktop.swt.it;

import static io.quarkiverse.desktop.swt.it.SwtChecks.require;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.swt.SWT;
import org.eclipse.swt.SWTException;
import org.eclipse.swt.accessibility.ACC;
import org.eclipse.swt.accessibility.Accessible;
import org.eclipse.swt.accessibility.AccessibleAdapter;
import org.eclipse.swt.accessibility.AccessibleControlAdapter;
import org.eclipse.swt.accessibility.AccessibleControlEvent;
import org.eclipse.swt.accessibility.AccessibleEvent;
import org.eclipse.swt.dnd.ByteArrayTransfer;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.DND;
import org.eclipse.swt.dnd.DragSource;
import org.eclipse.swt.dnd.DragSourceAdapter;
import org.eclipse.swt.dnd.DragSourceEvent;
import org.eclipse.swt.dnd.DropTarget;
import org.eclipse.swt.dnd.DropTargetAdapter;
import org.eclipse.swt.dnd.DropTargetEvent;
import org.eclipse.swt.dnd.FileTransfer;
import org.eclipse.swt.dnd.HTMLTransfer;
import org.eclipse.swt.dnd.ImageTransfer;
import org.eclipse.swt.dnd.RTFTransfer;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.dnd.TransferData;
import org.eclipse.swt.dnd.URLTransfer;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.printing.Printer;
import org.eclipse.swt.printing.PrinterData;
import org.eclipse.swt.program.Program;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Monitor;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.TaskBar;
import org.eclipse.swt.widgets.Text;
import org.eclipse.swt.widgets.Tray;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.swt.widgets.TreeItem;
import org.eclipse.swt.widgets.Widget;

/**
 * Checks of the native services of SWT : data transfer (clipboard, drag and drop), the desktop (programs, tray, task
 * bar, monitors, application name), printing, accessibility, and the threads (asynchronous and synchronous runnables,
 * timers, wake up).
 * <p>
 * They change nothing on the machine : the clipboard is only read, no print job is created, no program is launched, no
 * tray icon is added. What depends on the machine (its printers, programs, clipboard...) is only reported.
 */
final class SwtServiceChecks {

    private final SwtChecks checks;
    private final Display display;

    SwtServiceChecks(SwtChecks checks) {
        this.checks = checks;
        this.display = checks.display();
    }

    // ------------------------------------------------------------------------------------------------ data transfer

    /**
     * A transfer of the application : a type registered by name.
     */
    static final class ItemTransfer extends ByteArrayTransfer {

        static final String NAME = "quarkus-desktop-swt-it-item";
        static final int ID = registerType(NAME);
        static final ItemTransfer INSTANCE = new ItemTransfer();

        @Override
        protected String[] getTypeNames() {
            return new String[] { NAME };
        }

        @Override
        protected int[] getTypeIds() {
            return new int[] { ID };
        }

        @Override
        protected void javaToNative(Object object, TransferData transferData) {
            super.javaToNative(((String) object).getBytes(StandardCharsets.UTF_8), transferData);
        }

        @Override
        protected Object nativeToJava(TransferData transferData) {
            byte[] bytes = (byte[]) super.nativeToJava(transferData);
            return bytes == null ? null : new String(bytes, StandardCharsets.UTF_8);
        }
    }

    /**
     * The clipboard (only read), the transfer types, and drag sources and drop targets created on controls, then
     * disposed.
     */
    Object dataTransfer() throws Exception {
        Transfer[] transfers = { TextTransfer.getInstance(), RTFTransfer.getInstance(), HTMLTransfer.getInstance(),
                FileTransfer.getInstance(), ImageTransfer.getInstance(), URLTransfer.getInstance(),
                ItemTransfer.INSTANCE };
        String[] names = { "text", "rtf", "html", "file", "image", "url", "item" };
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < transfers.length; i++) {
            TransferData[] types = transfers[i].getSupportedTypes();
            require(types.length > 0, names[i] + " has no type");
            for (TransferData type : types) {
                require(transfers[i].isSupportedType(type), names[i] + " does not support its type");
            }
            text.append(names[i]).append(" types=").append(types.length).append('\n');
        }
        require(ItemTransfer.ID > 0, "registered type " + ItemTransfer.ID);
        checks.writeText("data-transfer", text.toString());

        Clipboard clipboard = new Clipboard(display);
        int available;
        int textAvailable = 0;
        try {
            available = clipboard.getAvailableTypeNames().length;
            for (TransferData type : clipboard.getAvailableTypes()) {
                textAvailable += TextTransfer.getInstance().isSupportedType(type) ? 1 : 0;
            }
        } finally {
            clipboard.dispose();
        }
        require(clipboard.isDisposed(), "the clipboard is not disposed");

        Shell shell = checks.shell("Data transfer");
        List<Widget> created = new ArrayList<>();
        try {
            Composite content = new Composite(shell, SWT.NONE);
            content.setLayout(new GridLayout(2, true));
            Label source = new Label(content, SWT.BORDER);
            source.setText("Drag source");
            Text target = new Text(content, SWT.BORDER);
            target.setText("Drop target");
            Tree tree = new Tree(content, SWT.BORDER);
            tree.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true, 2, 1));
            for (int i = 0; i < 3; i++) {
                new TreeItem(tree, SWT.NONE).setText("Item " + i);
            }
            Canvas focus = new Canvas(content, SWT.NONE);
            focus.setLayoutData(new GridData(1, 1));
            int operations = DND.DROP_COPY | DND.DROP_MOVE | DND.DROP_LINK;
            DragSourceAdapter dragListener = new DragSourceAdapter() {
                @Override
                public void dragSetData(DragSourceEvent event) {
                    event.data = "dragged";
                }
            };
            DropTargetAdapter dropListener = new DropTargetAdapter() {
                @Override
                public void drop(DropTargetEvent event) {
                    // nothing is dropped by the checks
                }
            };
            for (Control control : new Control[] { source, tree }) {
                DragSource dragSource = new DragSource(control, operations);
                dragSource.setTransfer(transfers);
                dragSource.addDragListener(dragListener);
                created.add(dragSource);
                require(dragSource.getControl() == control && control.getData(DND.DRAG_SOURCE_KEY) == dragSource,
                        "drag source of " + control);
                require(dragSource.getTransfer().length == transfers.length
                        && dragSource.getDragListeners().length == 1, "drag source transfers");
            }
            for (Control control : new Control[] { target, tree }) {
                DropTarget dropTarget = new DropTarget(control, operations | DND.DROP_DEFAULT);
                dropTarget.setTransfer(transfers);
                dropTarget.addDropListener(dropListener);
                created.add(dropTarget);
                require(dropTarget.getControl() == control && control.getData(DND.DROP_TARGET_KEY) == dropTarget,
                        "drop target of " + control);
                require(dropTarget.getTransfer().length == transfers.length
                        && dropTarget.getDropListeners().length == 1, "drop target transfers");
            }
            // the effects of the tree : created by default for trees and tables
            DragSource treeSource = (DragSource) created.get(1);
            DropTarget treeTarget = (DropTarget) created.get(3);
            require(treeSource.getDragSourceEffect() != null && treeTarget.getDropTargetEffect() != null,
                    "default effects of the tree");
            checks.place(shell);
            shell.setVisible(true);
            focus.setFocus();
            checks.settle();
        } finally {
            shell.dispose();
        }
        for (Widget widget : created) {
            require(widget.isDisposed(), "not disposed with its control : " + widget);
        }
        return "transfers=" + transfers.length + " dragSources=2 dropTargets=2 clipboardTypes=" + available
                + " clipboardText=" + (textAvailable > 0);
    }

    // --------------------------------------------------------------------------------------------- desktop services

    /**
     * The programs, the system tray and task bar, the monitors and the application name : reported (they depend on the
     * machine), never changed.
     */
    Object desktopServices() {
        StringBuilder result = new StringBuilder();
        String[] extensions = Program.getExtensions();
        Program text = Program.findProgram(".txt");
        result.append("extensions=").append(extensions.length);
        result.append(" txt=").append(text == null ? "none" : text.getName().replace(' ', '_'));
        if (text != null) {
            var icon = text.getImageData();
            result.append(" txtIcon=").append(icon == null ? "none" : icon.width + "x" + icon.height);
        }
        Tray tray = display.getSystemTray();
        result.append(" tray=").append(tray == null ? "none" : tray.getItemCount() + "items");
        TaskBar taskBar = display.getSystemTaskBar();
        result.append(" taskBar=").append(taskBar == null ? "none" : taskBar.getItemCount() + "items");
        Monitor[] monitors = display.getMonitors();
        require(monitors.length > 0, "no monitor");
        Monitor primary = display.getPrimaryMonitor();
        Rectangle bounds = primary.getBounds();
        Rectangle area = primary.getClientArea();
        require(bounds.width > 0 && bounds.height > 0 && bounds.contains(area.x, area.y), "primary monitor " + bounds);
        result.append(" monitors=").append(monitors.length).append(" primary=").append(bounds.width).append('x')
                .append(bounds.height).append(" area=").append(area.width).append('x').append(area.height);
        result.append(" doubleClick=").append(display.getDoubleClickTime() > 0);
        result.append(" iconSizes=").append(display.getIconSizes().length);
        String name = Display.getAppName();
        if (checks.quarkus()) {
            // quarkus.desktop.swt.application-name, given to SWT before the Display is created
            require(SwtChecks.APPLICATION_NAME.equals(name), "Display.getAppName() " + name);
        }
        result.append(" appName=").append(name == null ? "none" : name.replace(' ', '_'));
        result.append(" appVersion=").append(Display.getAppVersion());
        return result.toString();
    }

    // ----------------------------------------------------------------------------------------------------- printing

    /**
     * The printers : reported, never used (no print job is created).
     */
    Object printing() {
        PrinterData[] printers = Printer.getPrinterList();
        PrinterData defaultPrinter = Printer.getDefaultPrinterData();
        for (PrinterData printer : printers) {
            require(printer.name != null, "a printer without name");
        }
        return "printers=" + printers.length + " default=" + (defaultPrinter == null ? "none" : "present");
    }

    // ------------------------------------------------------------------------------------------------ accessibility

    /**
     * Accessibility : names, roles and relations given to the accessible objects of controls. Their listeners are
     * called when an assistive technology (or the system) queries them : reported, not required.
     */
    Object accessibility() throws Exception {
        Shell shell = checks.shell("Accessibility");
        try {
            Composite content = new Composite(shell, SWT.NONE);
            content.setLayout(new GridLayout(2, false));
            Label label = new Label(content, SWT.NONE);
            label.setText("Name :");
            Text text = new Text(content, SWT.BORDER);
            text.setText("Quarkus");
            Button button = new Button(content, SWT.PUSH);
            button.setText("Apply");
            Canvas custom = new Canvas(content, SWT.BORDER);
            custom.setLayoutData(new GridData(80, 30));
            AtomicInteger queries = new AtomicInteger();
            Accessible accessible = custom.getAccessible();
            require(accessible != null && accessible.getControl() == custom, "Control.getAccessible()");
            accessible.addAccessibleListener(new AccessibleAdapter() {
                @Override
                public void getName(AccessibleEvent event) {
                    queries.incrementAndGet();
                    event.result = "Custom control";
                }

                @Override
                public void getHelp(AccessibleEvent event) {
                    queries.incrementAndGet();
                    event.result = "A canvas painted by the application";
                }
            });
            accessible.addAccessibleControlListener(new AccessibleControlAdapter() {
                @Override
                public void getRole(AccessibleControlEvent event) {
                    queries.incrementAndGet();
                    event.detail = ACC.ROLE_PUSHBUTTON;
                }

                @Override
                public void getState(AccessibleControlEvent event) {
                    queries.incrementAndGet();
                    event.detail = ACC.STATE_FOCUSABLE;
                }

                @Override
                public void getChildCount(AccessibleControlEvent event) {
                    queries.incrementAndGet();
                    event.detail = 0;
                }
            });
            button.getAccessible().addAccessibleListener(new AccessibleAdapter() {
                @Override
                public void getDescription(AccessibleEvent event) {
                    queries.incrementAndGet();
                    event.result = "Applies the name";
                }
            });
            label.getAccessible().addRelation(ACC.RELATION_LABEL_FOR, text.getAccessible());
            text.getAccessible().addRelation(ACC.RELATION_LABELLED_BY, label.getAccessible());
            // a lightweight child of the custom control
            Accessible child = new Accessible(accessible);
            checks.place(shell);
            shell.setVisible(true);
            custom.setFocus();
            checks.settle();
            accessible.setFocus(ACC.CHILDID_SELF);
            accessible.selectionChanged();
            text.getAccessible().textChanged(ACC.TEXT_INSERT, 0, 7);
            text.getAccessible().sendEvent(ACC.EVENT_VALUE_CHANGED, null);
            checks.settle();
            text.getAccessible().removeRelation(ACC.RELATION_LABELLED_BY, label.getAccessible());
            label.getAccessible().removeRelation(ACC.RELATION_LABEL_FOR, text.getAccessible());
            child.dispose();
            return "accessibles=4 relations=2 queried=" + (queries.get() > 0);
        } finally {
            shell.dispose();
        }
    }

    // -------------------------------------------------------------------------------------------------------- async

    /**
     * The threads : {@code asyncExec} from a worker runs in order, {@code syncExec} and {@code syncCall} return to the
     * worker, {@code timerExec} runs after its delay and can be cancelled, {@code wake} wakes the sleeping user
     * interface thread up, and the access from another thread is rejected.
     */
    Object async() throws Exception {
        // not the common pool : its thread factory is loaded by name by Quarkus (exact reachability metadata)
        ExecutorService worker = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "swt-checks-worker");
            thread.setDaemon(true);
            return thread;
        });
        try {
            Thread uiThread = Thread.currentThread();
            require(display.getThread() == uiThread && Display.getCurrent() == display,
                    "not on the user interface thread");

            List<Integer> order = Collections.synchronizedList(new ArrayList<>());
            AtomicBoolean onUiThread = new AtomicBoolean(true);
            worker.submit(() -> {
                for (int i = 0; i < 20; i++) {
                    int index = i;
                    display.asyncExec(() -> {
                        onUiThread.compareAndSet(true, Thread.currentThread() == uiThread);
                        order.add(index);
                    });
                }
            });
            checks.await(() -> order.size() == 20);
            for (int i = 0; i < 20; i++) {
                require(order.get(i) == i, "asyncExec order " + order);
            }
            require(onUiThread.get(), "asyncExec ran outside the user interface thread");

            Future<String> synced = worker.submit(() -> {
                AtomicReference<String> name = new AtomicReference<>();
                display.syncExec(() -> name.set(Thread.currentThread() == uiThread ? "ui" : "other"));
                Integer called = display.syncCall(() -> 6 * 7);
                return name.get() + "-" + called;
            });
            checks.await(synced::isDone);
            require(synced.get().equals("ui-42"), "syncExec/syncCall " + synced.get());

            Future<String> rejected = worker.submit(() -> {
                try {
                    display.timerExec(10, () -> {
                    });
                    return "accepted";
                } catch (SWTException e) {
                    return e.code + " " + e.getMessage();
                }
            });
            checks.await(rejected::isDone);
            require(rejected.get().equals(SWT.ERROR_THREAD_INVALID_ACCESS + " Invalid thread access"),
                    "timerExec from a worker " + rejected.get());

            AtomicBoolean fired = new AtomicBoolean();
            AtomicBoolean cancelledFired = new AtomicBoolean();
            Runnable cancelled = () -> cancelledFired.set(true);
            long start = System.nanoTime();
            long[] elapsed = new long[1];
            display.timerExec(150, () -> {
                elapsed[0] = System.nanoTime() - start;
                fired.set(true);
            });
            display.timerExec(100, cancelled);
            display.timerExec(-1, cancelled);
            checks.await(fired::get);
            require(TimeUnit.NANOSECONDS.toMillis(elapsed[0]) >= 100, "timerExec ran too early");
            require(!cancelledFired.get(), "a cancelled timer ran");

            // wake : the user interface thread sleeps, without timer, until the worker wakes it up
            AtomicBoolean woken = new AtomicBoolean();
            AtomicBoolean timedOut = new AtomicBoolean();
            Runnable timeout = () -> timedOut.set(true);
            display.timerExec(10_000, timeout);
            checks.pump(() -> true);
            worker.submit(() -> {
                try {
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                woken.set(true);
                display.wake();
            });
            while (!woken.get() && !timedOut.get()) {
                if (!display.readAndDispatch()) {
                    display.sleep();
                }
            }
            display.timerExec(-1, timeout);
            require(woken.get() && !timedOut.get(), "Display.wake did not wake the user interface thread up");
            return "asyncExec=" + order.size() + " syncExec=" + synced.get() + " timer=true wake=true";
        } finally {
            worker.shutdownNow();
        }
    }
}
