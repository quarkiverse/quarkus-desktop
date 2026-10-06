package io.quarkiverse.desktop.showcase.swt.pages.a11y;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.stream.Collectors;

import jakarta.inject.Singleton;

import org.eclipse.swt.SWT;
import org.eclipse.swt.accessibility.ACC;
import org.eclipse.swt.accessibility.Accessible;
import org.eclipse.swt.accessibility.AccessibleActionAdapter;
import org.eclipse.swt.accessibility.AccessibleActionEvent;
import org.eclipse.swt.accessibility.AccessibleAdapter;
import org.eclipse.swt.accessibility.AccessibleControlAdapter;
import org.eclipse.swt.accessibility.AccessibleControlEvent;
import org.eclipse.swt.accessibility.AccessibleEvent;
import org.eclipse.swt.accessibility.AccessibleListener;
import org.eclipse.swt.accessibility.AccessibleValueAdapter;
import org.eclipse.swt.accessibility.AccessibleValueEvent;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.RowLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Text;

import io.quarkiverse.desktop.showcase.core.Check;
import io.quarkiverse.desktop.showcase.swt.core.ChecksTable;
import io.quarkiverse.desktop.showcase.swt.core.SwtCategories;
import io.quarkiverse.desktop.showcase.swt.core.SwtChecks;
import io.quarkiverse.desktop.showcase.swt.core.SwtKit;
import io.quarkiverse.desktop.showcase.swt.core.SwtPage;
import io.quarkiverse.desktop.showcase.swt.core.SwtSnapshots;
import io.quarkiverse.desktop.showcase.swt.core.UiStages;

/**
 * The accessibility API of SWT : the {@link Accessible} objects of native controls (a label, a text field, an image
 * button) and of custom controls painted on a {@link Canvas} (a progress gauge, a switch, a list of colors with three
 * children), the names, descriptions, help and keyboard shortcuts of {@code AccessibleListener}, the roles, states,
 * values, children, locations, hit tests and selection of {@code AccessibleControlListener}, the value of
 * {@code AccessibleValueListener} and the action of {@code AccessibleActionListener}, the relations
 * {@code LABEL_FOR} and {@code LABELLED_BY} ({@code Accessible.addRelation}), a lightweight child
 * ({@code new Accessible(parent)}) and the accessibility events. The checks query the listeners directly, as the
 * platform does for an assistive technology : no screen reader is needed, and their values are the same everywhere.
 * <p>
 * Native code paths. Windows : {@code Control.getAccessible()} creates the proxy of the standard accessible object of
 * the window ({@code CreateStdAccessibleObject} of oleacc) and SWT's {@code IAccessible} and {@code IAccessible2}
 * {@code COMObject} (then, when they are asked for, {@code IEnumVARIANT}, {@code IServiceProvider},
 * {@code IAccessibleValue}, {@code IAccessibleAction} and, for a relation, {@code IAccessibleRelation}) : tables of
 * native function pointers whose methods the operating system calls back through JNI callbacks (the static
 * {@code COMObject.callback<N>} methods, bound when the first object using a slot of a table is created) when an
 * assistive technology asks the window for its object ({@code WM_GETOBJECT}, {@code LresultFromObject}) ; the events
 * are {@code NotifyWinEvent} calls when an assistive technology runs. GTK : ATK. SWT registers GTypes for the
 * accessible objects of its widgets, whose ATK interfaces ({@code AtkObject}, {@code AtkComponent}, {@code AtkAction},
 * {@code AtkValue}, {@code AtkSelection}...) are implemented by static Java methods of {@code AccessibleObject} that
 * ATK and the AT-SPI bridge call back through JNI. Cocoa : {@code NSAccessibility} attributes of the views.
 * <p>
 * Why it matters for a native executable : nothing calls these callbacks until a screen reader runs, so a callback
 * missing from the JNI configuration of a native executable fails for the users who need it, never in a test without
 * an assistive technology ; creating the {@code COMObject}s (their callbacks are bound when they are created) and the
 * ATK types is what this page can exercise without one. The custom controls are painted with a {@code GC}, and the
 * checks never depend on the position of the window : every run renders the same pixels.
 */
@Singleton
public class SwtAccessibilityPage implements SwtPage {

    static final String[] COLOR_NAMES = { "Red", "Green", "Blue" };
    private static final int[] COLOR_VALUES = { 0xE53935, 0x43A047, 0x1E88E5 };
    private static final int PANEL = 0xF3F4F6;
    private static final int GAUGE_WIDTH = 160;
    private static final int GAUGE_HEIGHT = 24;
    private static final int SWITCH_WIDTH = 52;
    private static final int SWITCH_HEIGHT = 26;
    private static final int SWATCH = 28;
    private static final int SWATCH_GAP = 8;
    private static final int LIST_WIDTH = 3 * SWATCH + 2 * SWATCH_GAP;

    // per build state (one content at a time)
    private Gallery gallery;

    /** The controls of one build, their listeners and the state of the custom controls. */
    private static final class Gallery {
        Composite panel;
        Label label;
        Text text;
        Button zoom;
        Image zoomIcon;
        Canvas gauge;
        Canvas toggle;
        Canvas list;
        AccessibleListener textNames;
        AccessibleListener zoomNames;
        AccessibleListener gaugeNames;
        AccessibleListener toggleNames;
        AccessibleListener listNames;
        AccessibleControlAdapter gaugeControl;
        AccessibleControlAdapter toggleControl;
        AccessibleControlAdapter listControl;
        AccessibleValueAdapter gaugeValue;
        AccessibleActionAdapter toggleAction;
        int value = 70;
        boolean on = true;
        int selected = 1;
        Check relations;
        ChecksTable listeners;
        ChecksTable objects;
    }

    @Override
    public String id() {
        return "swt-accessibility";
    }

    @Override
    public String title() {
        return "Accessibility";
    }

    @Override
    public String category() {
        return SwtCategories.A11Y;
    }

    @Override
    public int order() {
        return 10;
    }

    @Override
    public Control build(Composite parent) {
        Gallery g = new Gallery();
        gallery = g;
        Composite page = SwtKit.page(parent, 14);
        SwtKit.text(page, "Native controls and custom controls painted on a Canvas, with their accessible objects:"
                + " names, descriptions, roles, states, values, children, an action and label relations. The checks"
                + " query the accessibility listeners the way the platform does for a screen reader, which is not"
                + " needed here.", SwtKit.TEXT_WIDTH);
        gallery(page, g);
        listeners(g);
        g.relations = SwtChecks.run("addRelation(LABEL_FOR, text), addRelation(LABELLED_BY, label)", () -> {
            relations(g);
            return "added";
        });
        g.objects = ChecksTable.table(page, "Accessible objects and relations", List.of(Check.info("state",
                "pending")), 330, ChecksTable.WIDTH);
        g.listeners = ChecksTable.table(page, "Querying the listeners (what a screen reader reads)",
                List.of(Check.info("state", "pending")), 330, ChecksTable.WIDTH);
        return page;
    }

    @Override
    public CompletionStage<?> ready(Control content) {
        Gallery g = gallery;
        // laid out : the locations of the custom controls are known
        return UiStages.rounds(2).thenAccept(v -> {
            if (g.listeners.isDisposed()) {
                return;
            }
            g.objects.setChecks(objectChecks(g));
            g.listeners.setChecks(listenerChecks(g));
        });
    }

    @Override
    public void dispose(Control content) {
        gallery = null;
    }

    // ----------------------------------------------------------------------------------------------------- gallery

    private static void gallery(Composite page, Gallery g) {
        g.panel = new Composite(page, SWT.NONE);
        RowLayout layout = new RowLayout(SWT.HORIZONTAL);
        layout.marginWidth = 14;
        layout.marginHeight = 12;
        layout.spacing = 24;
        layout.center = true;
        g.panel.setLayout(layout);
        g.panel.setBackground(SwtKit.color(PANEL));
        SwtKit.size(g.panel, SwtKit.TEXT_WIDTH, SWT.DEFAULT);

        Composite field = item(g.panel, "native Label and Text (relations)");
        Composite fieldRow = SwtKit.row(field, 8);
        ((RowLayout) fieldRow.getLayout()).center = true;
        g.label = new Label(fieldRow, SWT.NONE);
        g.label.setText("Name:");
        g.text = new Text(fieldRow, SWT.BORDER | SWT.SINGLE);
        g.text.setText("Ada Lovelace");
        SwtKit.size(g.text, 140, SWT.DEFAULT);

        Composite zoom = item(g.panel, "image Button");
        g.zoomIcon = new Image(page.getDisplay(), SwtSnapshots.offscreen(16, 16, SwtAccessibilityPage::paintZoomIcon));
        g.zoom = new Button(zoom, SWT.PUSH);
        g.zoom.setImage(g.zoomIcon);
        g.zoom.addListener(SWT.Dispose, event -> g.zoomIcon.dispose());

        Composite gauge = item(g.panel, "progress gauge (Canvas)");
        g.gauge = SwtKit.canvas(gauge, GAUGE_WIDTH, GAUGE_HEIGHT, gc -> paintGauge(gc, g.value));

        Composite toggle = item(g.panel, "switch (Canvas)");
        g.toggle = SwtKit.canvas(toggle, SWITCH_WIDTH, SWITCH_HEIGHT, gc -> paintSwitch(gc, g.on));

        Composite list = item(g.panel, "list of 3 colors (Canvas)");
        g.list = SwtKit.canvas(list, LIST_WIDTH, SWATCH, gc -> paintList(gc, g.selected));
        // the controls first, their captions under them : moved above the captions
        for (Composite item : new Composite[] { field, zoom, gauge, toggle, list }) {
            item.getChildren()[1].moveAbove(item.getChildren()[0]);
        }
    }

    /**
     * A column holding a control (created next) above its caption.
     */
    private static Composite item(Composite panel, String caption) {
        Composite column = SwtKit.column(panel, 6);
        SwtKit.caption(column, caption);
        return column;
    }

    private static void paintZoomIcon(GC gc) {
        gc.setAntialias(SWT.ON);
        gc.setForeground(SwtKit.color(0x263238));
        gc.setLineWidth(2);
        gc.drawOval(1, 1, 10, 10);
        gc.drawLine(10, 10, 14, 14);
        gc.setLineWidth(1);
        gc.drawLine(4, 6, 8, 6);
        gc.drawLine(6, 4, 6, 8);
    }

    private static void paintGauge(GC gc, int value) {
        gc.setBackground(SwtKit.color(0xCFD8DC));
        gc.fillRectangle(0, 0, GAUGE_WIDTH, GAUGE_HEIGHT);
        gc.setBackground(SwtKit.color(0x43A047));
        gc.fillRectangle(0, 0, GAUGE_WIDTH * value / 100, GAUGE_HEIGHT);
        gc.setForeground(SwtKit.color(0x263238));
        gc.drawRectangle(0, 0, GAUGE_WIDTH - 1, GAUGE_HEIGHT - 1);
        gc.setForeground(SwtKit.color(0xFFFFFF));
        gc.setFont(SwtKit.font(SWT.BOLD, 8));
        String text = value + " %";
        Point extent = gc.stringExtent(text);
        gc.drawString(text, (GAUGE_WIDTH * value / 100 - extent.x) / 2, (GAUGE_HEIGHT - extent.y) / 2, true);
    }

    private static void paintSwitch(GC gc, boolean on) {
        gc.setAntialias(SWT.ON);
        gc.setBackground(SwtKit.color(PANEL));
        gc.fillRectangle(0, 0, SWITCH_WIDTH, SWITCH_HEIGHT);
        gc.setBackground(SwtKit.color(on ? 0x43A047 : 0x9E9E9E));
        gc.fillRoundRectangle(0, 0, SWITCH_WIDTH, SWITCH_HEIGHT, SWITCH_HEIGHT, SWITCH_HEIGHT);
        gc.setBackground(SwtKit.color(0xFFFFFF));
        int knob = SWITCH_HEIGHT - 6;
        gc.fillOval(on ? SWITCH_WIDTH - knob - 3 : 3, 3, knob, knob);
    }

    private static void paintList(GC gc, int selected) {
        gc.setBackground(SwtKit.color(PANEL));
        gc.fillRectangle(0, 0, LIST_WIDTH, SWATCH);
        for (int i = 0; i < COLOR_VALUES.length; i++) {
            Rectangle r = swatch(i);
            gc.setBackground(SwtKit.color(COLOR_VALUES[i]));
            gc.fillRectangle(r);
            if (i == selected) {
                gc.setForeground(SwtKit.color(0x263238));
                gc.setLineWidth(3);
                gc.drawRectangle(r.x + 1, r.y + 1, r.width - 3, r.height - 3);
                gc.setLineWidth(1);
            }
        }
    }

    private static Rectangle swatch(int index) {
        return new Rectangle(index * (SWATCH + SWATCH_GAP), 0, SWATCH, SWATCH);
    }

    // --------------------------------------------------------------------------------------------------- listeners

    /**
     * The listeners of the accessible objects : what a screen reader asks for.
     */
    private static void listeners(Gallery g) {
        g.textNames = new AccessibleAdapter() {
            @Override
            public void getDescription(AccessibleEvent e) {
                e.result = "The full name of the author";
            }

            @Override
            public void getHelp(AccessibleEvent e) {
                e.result = "First name and last name";
            }
        };
        g.text.getAccessible().addAccessibleListener(g.textNames);

        // an image button has no text : its name comes from the listener
        g.zoomNames = new AccessibleAdapter() {
            @Override
            public void getName(AccessibleEvent e) {
                e.result = "Zoom in";
            }

            @Override
            public void getDescription(AccessibleEvent e) {
                e.result = "Enlarges the preview by 25 %";
            }

            @Override
            public void getKeyboardShortcut(AccessibleEvent e) {
                e.result = "Ctrl++";
            }

            @Override
            public void getHelp(AccessibleEvent e) {
                e.result = "Zooms the preview in";
            }
        };
        g.zoom.getAccessible().addAccessibleListener(g.zoomNames);

        g.gaugeNames = AccessibleListener.getNameAdapter(e -> e.result = "Download progress");
        g.gaugeControl = new AccessibleControlAdapter() {
            @Override
            public void getRole(AccessibleControlEvent e) {
                e.detail = ACC.ROLE_PROGRESSBAR;
            }

            @Override
            public void getState(AccessibleControlEvent e) {
                e.detail = ACC.STATE_READONLY;
            }

            @Override
            public void getValue(AccessibleControlEvent e) {
                e.result = g.value + " %";
            }

            @Override
            public void getChildCount(AccessibleControlEvent e) {
                e.detail = 0;
            }

            @Override
            public void getLocation(AccessibleControlEvent e) {
                location(e, g.gauge, g.gauge.getClientArea());
            }

            @Override
            public void getChildAtPoint(AccessibleControlEvent e) {
                Point point = g.gauge.toControl(e.x, e.y);
                e.childID = g.gauge.getClientArea().contains(point) ? ACC.CHILDID_SELF : ACC.CHILDID_NONE;
            }
        };
        g.gaugeValue = new AccessibleValueAdapter() {
            @Override
            public void getCurrentValue(AccessibleValueEvent e) {
                e.value = g.value;
            }

            @Override
            public void getMinimumValue(AccessibleValueEvent e) {
                e.value = 0;
            }

            @Override
            public void getMaximumValue(AccessibleValueEvent e) {
                e.value = 100;
            }
        };
        Accessible gauge = g.gauge.getAccessible();
        gauge.addAccessibleListener(g.gaugeNames);
        gauge.addAccessibleControlListener(g.gaugeControl);
        gauge.addAccessibleValueListener(g.gaugeValue);

        g.toggleNames = new AccessibleAdapter() {
            @Override
            public void getName(AccessibleEvent e) {
                e.result = "Notifications";
            }

            @Override
            public void getKeyboardShortcut(AccessibleEvent e) {
                e.result = "Space";
            }
        };
        g.toggleControl = new AccessibleControlAdapter() {
            @Override
            public void getRole(AccessibleControlEvent e) {
                e.detail = ACC.ROLE_CHECKBUTTON;
            }

            @Override
            public void getState(AccessibleControlEvent e) {
                e.detail = ACC.STATE_FOCUSABLE | (g.on ? ACC.STATE_CHECKED : 0);
            }

            @Override
            public void getDefaultAction(AccessibleControlEvent e) {
                e.result = g.on ? "Switch off" : "Switch on";
            }
        };
        g.toggleAction = new AccessibleActionAdapter() {
            @Override
            public void getActionCount(AccessibleActionEvent e) {
                e.count = 1;
            }

            @Override
            public void getName(AccessibleActionEvent e) {
                e.result = e.index == 0 ? "toggle" : null;
            }

            @Override
            public void getDescription(AccessibleActionEvent e) {
                e.result = e.index == 0 ? "Switches the notifications on or off" : null;
            }

            @Override
            public void doAction(AccessibleActionEvent e) {
                if (e.index != 0) {
                    return;
                }
                g.on = !g.on;
                g.toggle.redraw();
                // tells an assistive technology, when one runs
                g.toggle.getAccessible().sendEvent(ACC.EVENT_STATE_CHANGED, null);
                e.result = ACC.OK;
            }
        };
        Accessible toggle = g.toggle.getAccessible();
        toggle.addAccessibleListener(g.toggleNames);
        toggle.addAccessibleControlListener(g.toggleControl);
        toggle.addAccessibleActionListener(g.toggleAction);

        g.listNames = new AccessibleAdapter() {
            @Override
            public void getName(AccessibleEvent e) {
                e.result = e.childID == ACC.CHILDID_SELF ? "Colors"
                        : e.childID >= 0 && e.childID < COLOR_NAMES.length ? COLOR_NAMES[e.childID] : null;
            }
        };
        g.listControl = new AccessibleControlAdapter() {
            @Override
            public void getRole(AccessibleControlEvent e) {
                e.detail = e.childID == ACC.CHILDID_SELF ? ACC.ROLE_LIST : ACC.ROLE_LISTITEM;
            }

            @Override
            public void getState(AccessibleControlEvent e) {
                e.detail = e.childID == ACC.CHILDID_SELF ? ACC.STATE_FOCUSABLE
                        : ACC.STATE_SELECTABLE | (e.childID == g.selected ? ACC.STATE_SELECTED : 0);
            }

            @Override
            public void getChildCount(AccessibleControlEvent e) {
                e.detail = COLOR_NAMES.length;
            }

            @Override
            public void getChildren(AccessibleControlEvent e) {
                Object[] children = new Object[COLOR_NAMES.length];
                for (int i = 0; i < children.length; i++) {
                    children[i] = i;
                }
                e.children = children;
            }

            @Override
            public void getChildAtPoint(AccessibleControlEvent e) {
                Point point = g.list.toControl(e.x, e.y);
                e.childID = ACC.CHILDID_NONE;
                if (g.list.getClientArea().contains(point)) {
                    e.childID = ACC.CHILDID_SELF;
                    for (int i = 0; i < COLOR_NAMES.length; i++) {
                        if (swatch(i).contains(point)) {
                            e.childID = i;
                        }
                    }
                }
            }

            @Override
            public void getLocation(AccessibleControlEvent e) {
                location(e, g.list, e.childID == ACC.CHILDID_SELF ? g.list.getClientArea() : swatch(e.childID));
            }

            @Override
            public void getSelection(AccessibleControlEvent e) {
                e.childID = g.selected;
            }

            @Override
            public void getFocus(AccessibleControlEvent e) {
                // the list never has the focus here
                e.childID = ACC.CHILDID_NONE;
            }
        };
        Accessible list = g.list.getAccessible();
        list.addAccessibleListener(g.listNames);
        list.addAccessibleControlListener(g.listControl);
    }

    /**
     * Sets the location of {@code area} of {@code control} in display coordinates, as an assistive technology expects.
     */
    private static void location(AccessibleControlEvent e, Control control, Rectangle area) {
        Point origin = control.toDisplay(area.x, area.y);
        e.x = origin.x;
        e.y = origin.y;
        e.width = area.width;
        e.height = area.height;
    }

    /**
     * The label is the label for the text field, the text field is labelled by the label.
     */
    private static void relations(Gallery g) {
        g.label.getAccessible().addRelation(ACC.RELATION_LABEL_FOR, g.text.getAccessible());
        g.text.getAccessible().addRelation(ACC.RELATION_LABELLED_BY, g.label.getAccessible());
    }

    // ------------------------------------------------------------------------------------------------------ checks

    private static List<Check> objectChecks(Gallery g) {
        List<Check> checks = new ArrayList<>();
        Control[] controls = { g.panel, g.label, g.text, g.zoom, g.gauge, g.toggle, g.list };
        checks.add(SwtChecks.expect("Control.getAccessible() of 7 controls : created once, getControl()",
                "7 of 7", () -> Arrays.stream(controls).filter(c -> {
                    Accessible accessible = c.getAccessible();
                    return accessible != null && accessible == c.getAccessible() && accessible.getControl() == c;
                }).count() + " of 7"));
        checks.add(SwtChecks.expect("new Accessible(list) : a lightweight child, then dispose()",
                "control list, disposed", () -> {
                    Accessible child = new Accessible(g.list.getAccessible());
                    String control = child.getControl() == g.list ? "control list" : "another control";
                    child.dispose();
                    return control + ", disposed";
                }));
        // added when the page was built
        checks.add(g.relations);
        checks.add(SwtChecks.expect("removeRelation(LABEL_FOR), then addRelation again", "removed, added", () -> {
            g.label.getAccessible().removeRelation(ACC.RELATION_LABEL_FOR, g.text.getAccessible());
            g.label.getAccessible().addRelation(ACC.RELATION_LABEL_FOR, g.text.getAccessible());
            return "removed, added";
        }));
        checks.add(SwtChecks.expect("addRelation(LABEL_FOR, null)", "IllegalArgumentException", () -> {
            try {
                g.label.getAccessible().addRelation(ACC.RELATION_LABEL_FOR, null);
                return "no exception";
            } catch (IllegalArgumentException e) {
                return e.getClass().getSimpleName();
            }
        }));
        checks.add(SwtChecks.expect("events : sendEvent(VALUE_CHANGED), selectionChanged()", "sent", () -> {
            g.gauge.getAccessible().sendEvent(ACC.EVENT_VALUE_CHANGED, null);
            g.list.getAccessible().selectionChanged();
            return "sent";
        }));
        checks.add(SwtChecks.expect("remove then add a listener (removeAccessibleListener)", "Zoom in", () -> {
            Accessible zoom = g.zoom.getAccessible();
            zoom.removeAccessibleListener(g.zoomNames);
            zoom.addAccessibleListener(g.zoomNames);
            return name(g.zoomNames, zoom, ACC.CHILDID_SELF);
        }));
        return checks;
    }

    private static List<Check> listenerChecks(Gallery g) {
        List<Check> checks = new ArrayList<>();
        Accessible text = g.text.getAccessible();
        Accessible zoom = g.zoom.getAccessible();
        Accessible gauge = g.gauge.getAccessible();
        Accessible toggle = g.toggle.getAccessible();
        Accessible list = g.list.getAccessible();
        int self = ACC.CHILDID_SELF;

        checks.add(SwtChecks.expect("Text : description ; help", "The full name of the author ; First name and"
                + " last name", () -> description(g.textNames, text, self) + " ; " + help(g.textNames, text, self)));
        checks.add(SwtChecks.expect("image Button : name ; description", "Zoom in ; Enlarges the preview by 25 %",
                () -> name(g.zoomNames, zoom, self) + " ; " + description(g.zoomNames, zoom, self)));
        checks.add(SwtChecks.expect("image Button : keyboard shortcut ; help", "Ctrl++ ; Zooms the preview in",
                () -> shortcut(g.zoomNames, zoom, self) + " ; " + help(g.zoomNames, zoom, self)));

        checks.add(SwtChecks.expect("gauge : name, role, state, value", "Download progress, PROGRESSBAR, READONLY,"
                + " 70 %", () -> name(g.gaugeNames, gauge, self) + ", " + role(g.gaugeControl, gauge, self) + ", "
                        + state(g.gaugeControl, gauge, self) + ", " + value(g.gaugeControl, gauge, self)));
        checks.add(SwtChecks.expect("gauge : AccessibleValueListener (current, minimum, maximum)", "70, 0, 100",
                () -> {
                    AccessibleValueEvent current = new AccessibleValueEvent(gauge);
                    g.gaugeValue.getCurrentValue(current);
                    AccessibleValueEvent minimum = new AccessibleValueEvent(gauge);
                    g.gaugeValue.getMinimumValue(minimum);
                    AccessibleValueEvent maximum = new AccessibleValueEvent(gauge);
                    g.gaugeValue.getMaximumValue(maximum);
                    return current.value + ", " + minimum.value + ", " + maximum.value;
                }));
        checks.add(SwtChecks.expect("gauge : location (relative to the control), child count", "0,0 160x24, 0",
                () -> relativeLocation(g.gaugeControl, gauge, g.gauge, self) + ", "
                        + childCount(g.gaugeControl, gauge)));

        checks.add(SwtChecks.expect("switch : name, shortcut, role, state, default action",
                "Notifications, Space, CHECKBUTTON, CHECKED FOCUSABLE, Switch off",
                () -> name(g.toggleNames, toggle, self) + ", " + shortcut(g.toggleNames, toggle, self) + ", "
                        + role(g.toggleControl, toggle, self) + ", " + state(g.toggleControl, toggle, self) + ", "
                        + defaultAction(g.toggleControl, toggle)));
        checks.add(SwtChecks.expect("switch : AccessibleActionListener (count, name, description)",
                "1, toggle, Switches the notifications on or off", () -> {
                    AccessibleActionEvent count = new AccessibleActionEvent(toggle);
                    g.toggleAction.getActionCount(count);
                    AccessibleActionEvent name = new AccessibleActionEvent(toggle);
                    g.toggleAction.getName(name);
                    AccessibleActionEvent description = new AccessibleActionEvent(toggle);
                    g.toggleAction.getDescription(description);
                    return count.count + ", " + name.result + ", " + description.result;
                }));
        // the action twice : the switch ends as it started (the snapshot shows it on)
        checks.add(SwtChecks.expect("switch : doAction(0) twice : result and state", "OK FOCUSABLE, OK CHECKED"
                + " FOCUSABLE", () -> {
                    String first = doAction(g.toggleAction, toggle) + " " + state(g.toggleControl, toggle, self);
                    String second = doAction(g.toggleAction, toggle) + " " + state(g.toggleControl, toggle, self);
                    return first + ", " + second;
                }));

        checks.add(SwtChecks.expect("list : name, role, state, child count, children", "Colors, LIST, FOCUSABLE, 3,"
                + " 0 1 2", () -> name(g.listNames, list, self) + ", " + role(g.listControl, list, self) + ", "
                        + state(g.listControl, list, self) + ", " + childCount(g.listControl, list) + ", "
                        + children(g.listControl, list)));
        checks.add(SwtChecks.expect("list children : names", "Red Green Blue", () -> String.join(" ",
                name(g.listNames, list, 0), name(g.listNames, list, 1), name(g.listNames, list, 2))));
        checks.add(SwtChecks.expect("list children : roles", "LISTITEM LISTITEM LISTITEM", () -> String.join(" ",
                role(g.listControl, list, 0), role(g.listControl, list, 1), role(g.listControl, list, 2))));
        checks.add(SwtChecks.expect("list children : states", "SELECTABLE ; SELECTED SELECTABLE ; SELECTABLE",
                () -> String.join(" ; ", state(g.listControl, list, 0), state(g.listControl, list, 1),
                        state(g.listControl, list, 2))));
        checks.add(SwtChecks.expect("list children : locations (relative to the control)",
                "0,0 28x28 ; 36,0 28x28 ; 72,0 28x28", () -> String.join(" ; ",
                        relativeLocation(g.listControl, list, g.list, 0),
                        relativeLocation(g.listControl, list, g.list, 1),
                        relativeLocation(g.listControl, list, g.list, 2))));
        checks.add(SwtChecks.expect("list : getChildAtPoint (in each swatch, between two, outside)",
                "0 1 2 SELF NONE", () -> {
                    List<String> hits = new ArrayList<>();
                    for (Point point : List.of(new Point(14, 14), new Point(50, 14), new Point(86, 14),
                            new Point(32, 14), new Point(14, 40))) {
                        hits.add(childId(childAtPoint(g.listControl, list, g.list.toDisplay(point))));
                    }
                    return String.join(" ", hits);
                }));
        checks.add(SwtChecks.expect("list : selection, focus", "1, NONE", () -> {
            AccessibleControlEvent selection = event(list, self);
            g.listControl.getSelection(selection);
            AccessibleControlEvent focus = event(list, self);
            g.listControl.getFocus(focus);
            return childId(selection.childID) + ", " + childId(focus.childID);
        }));
        return checks;
    }

    // ---------------------------------------------------------------------------------------- querying the listeners

    private static AccessibleEvent named(Accessible accessible, int childId) {
        AccessibleEvent e = new AccessibleEvent(accessible);
        e.childID = childId;
        return e;
    }

    static String name(AccessibleListener listener, Accessible accessible, int childId) {
        AccessibleEvent e = named(accessible, childId);
        listener.getName(e);
        return e.result;
    }

    static String description(AccessibleListener listener, Accessible accessible, int childId) {
        AccessibleEvent e = named(accessible, childId);
        listener.getDescription(e);
        return e.result;
    }

    static String help(AccessibleListener listener, Accessible accessible, int childId) {
        AccessibleEvent e = named(accessible, childId);
        listener.getHelp(e);
        return e.result;
    }

    static String shortcut(AccessibleListener listener, Accessible accessible, int childId) {
        AccessibleEvent e = named(accessible, childId);
        listener.getKeyboardShortcut(e);
        return e.result;
    }

    private static AccessibleControlEvent event(Accessible accessible, int childId) {
        AccessibleControlEvent e = new AccessibleControlEvent(accessible);
        e.childID = childId;
        return e;
    }

    static String role(AccessibleControlAdapter listener, Accessible accessible, int childId) {
        AccessibleControlEvent e = event(accessible, childId);
        listener.getRole(e);
        return roleName(e.detail);
    }

    static String state(AccessibleControlAdapter listener, Accessible accessible, int childId) {
        AccessibleControlEvent e = event(accessible, childId);
        listener.getState(e);
        return stateNames(e.detail);
    }

    static String value(AccessibleControlAdapter listener, Accessible accessible, int childId) {
        AccessibleControlEvent e = event(accessible, childId);
        listener.getValue(e);
        return e.result;
    }

    static String defaultAction(AccessibleControlAdapter listener, Accessible accessible) {
        AccessibleControlEvent e = event(accessible, ACC.CHILDID_SELF);
        listener.getDefaultAction(e);
        return e.result;
    }

    static int childCount(AccessibleControlAdapter listener, Accessible accessible) {
        AccessibleControlEvent e = event(accessible, ACC.CHILDID_SELF);
        listener.getChildCount(e);
        return e.detail;
    }

    static String children(AccessibleControlAdapter listener, Accessible accessible) {
        AccessibleControlEvent e = event(accessible, ACC.CHILDID_SELF);
        listener.getChildren(e);
        return e.children == null ? "none"
                : Arrays.stream(e.children).map(String::valueOf).collect(Collectors.joining(" "));
    }

    static int childAtPoint(AccessibleControlAdapter listener, Accessible accessible, Point display) {
        AccessibleControlEvent e = event(accessible, ACC.CHILDID_NONE);
        e.x = display.x;
        e.y = display.y;
        listener.getChildAtPoint(e);
        return e.childID;
    }

    /**
     * The location the listener gives, relative to {@code control} : the checks never depend on where the window is.
     */
    static String relativeLocation(AccessibleControlAdapter listener, Accessible accessible, Control control,
            int childId) {
        AccessibleControlEvent e = event(accessible, childId);
        listener.getLocation(e);
        Point origin = control.toDisplay(0, 0);
        return SwtChecks.rect(new Rectangle(e.x - origin.x, e.y - origin.y, e.width, e.height));
    }

    static String doAction(AccessibleActionAdapter listener, Accessible accessible) {
        AccessibleActionEvent e = new AccessibleActionEvent(accessible);
        e.index = 0;
        listener.doAction(e);
        return e.result;
    }

    static String childId(int id) {
        return switch (id) {
            case ACC.CHILDID_SELF -> "SELF";
            case ACC.CHILDID_NONE -> "NONE";
            case ACC.CHILDID_MULTIPLE -> "MULTIPLE";
            default -> String.valueOf(id);
        };
    }

    static String roleName(int role) {
        return switch (role) {
            case ACC.ROLE_PROGRESSBAR -> "PROGRESSBAR";
            case ACC.ROLE_CHECKBUTTON -> "CHECKBUTTON";
            case ACC.ROLE_PUSHBUTTON -> "PUSHBUTTON";
            case ACC.ROLE_LIST -> "LIST";
            case ACC.ROLE_LISTITEM -> "LISTITEM";
            case ACC.ROLE_LABEL -> "LABEL";
            case ACC.ROLE_TEXT -> "TEXT";
            case ACC.ROLE_CLIENT_AREA -> "CLIENT_AREA";
            default -> "0x" + Integer.toHexString(role);
        };
    }

    /** The state bits of {@link ACC} and their names, in the order of the checks. */
    private static final int[] STATES = { ACC.STATE_SELECTED, ACC.STATE_CHECKED, ACC.STATE_READONLY,
            ACC.STATE_FOCUSED, ACC.STATE_FOCUSABLE, ACC.STATE_SELECTABLE, ACC.STATE_PRESSED, ACC.STATE_EXPANDED,
            ACC.STATE_COLLAPSED, ACC.STATE_BUSY, ACC.STATE_INVISIBLE, ACC.STATE_DISABLED };
    private static final String[] STATE_NAMES = { "SELECTED", "CHECKED", "READONLY", "FOCUSED", "FOCUSABLE",
            "SELECTABLE", "PRESSED", "EXPANDED", "COLLAPSED", "BUSY", "INVISIBLE", "DISABLED" };

    static String stateNames(int state) {
        if (state == ACC.STATE_NORMAL) {
            return "NORMAL";
        }
        List<String> names = new ArrayList<>();
        int rest = state;
        for (int i = 0; i < STATES.length; i++) {
            if ((state & STATES[i]) != 0) {
                names.add(STATE_NAMES[i]);
                rest &= ~STATES[i];
            }
        }
        if (rest != 0) {
            names.add("0x" + Integer.toHexString(rest));
        }
        return String.join(" ", names);
    }
}
