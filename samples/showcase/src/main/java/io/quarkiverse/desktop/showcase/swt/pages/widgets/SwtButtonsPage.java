package io.quarkiverse.desktop.showcase.swt.pages.widgets;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import jakarta.inject.Singleton;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.PaletteData;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Link;

import io.quarkiverse.desktop.showcase.core.Check;
import io.quarkiverse.desktop.showcase.swt.core.ChecksTable;
import io.quarkiverse.desktop.showcase.swt.core.SwtCategories;
import io.quarkiverse.desktop.showcase.swt.core.SwtChecks;
import io.quarkiverse.desktop.showcase.swt.core.SwtKit;
import io.quarkiverse.desktop.showcase.swt.core.SwtMode;
import io.quarkiverse.desktop.showcase.swt.core.SwtPage;
import io.quarkiverse.desktop.showcase.swt.core.SwtSnapshots;
import io.quarkiverse.desktop.showcase.swt.core.UiStages;

/**
 * The button family and the static controls of SWT : {@link Button} in its five types ({@code PUSH}, {@code CHECK},
 * {@code RADIO}, {@code TOGGLE}, {@code ARROW} in the four directions) and with {@code FLAT}, in every state (selected,
 * grayed, disabled), with a custom color and with an image ; {@link Label} with text, a generated image, horizontal and
 * vertical separators, a wrapped text and the three alignments ; {@link Link} with several links ; {@link Group} with
 * and without a title.
 * <p>
 * Native code paths. Windows : every control is a native window that SWT subclasses, so that its messages go through
 * the JNI callback of {@code Display.windowProc} : {@code BUTTON} windows ({@code BS_PUSHBUTTON}, {@code BS_CHECKBOX}
 * and {@code BS_3STATE} for the grayed check box, {@code BS_RADIOBUTTON}, {@code BS_PUSHLIKE} for the toggles,
 * {@code BS_FLAT}, an image list for the image with an alpha channel : {@code BCM_SETIMAGELIST} and its
 * {@code BUTTON_IMAGELIST}), the arrows drawn by SWT ({@code BS_OWNERDRAW} : {@code WM_DRAWITEM} and its
 * {@code DRAWITEMSTRUCT}, the scroll bar arrow of the theme), the colored push button custom drawn ({@code WM_NOTIFY}
 * {@code NM_CUSTOMDRAW} and its {@code NMCUSTOMDRAW}, a GC on the device context of the notification), the separators
 * and the image label owner drawn {@code STATIC} controls, {@code Link} a {@code SysLink} control
 * ({@code LM_SETITEM}, {@code LM_GETIDEALSIZE} with a {@code SIZE}, {@code NM_CUSTOMDRAW} for the link color),
 * {@code Group} a {@code BS_GROUPBOX} button. GTK : {@code GtkButton}, {@code GtkCheckButton},
 * {@code GtkRadioButton} and {@code GtkToggleButton} with their {@code clicked} signal, {@code GtkLabel},
 * {@code GtkSeparator} and {@code GtkImage}, {@code GtkFrame}, and a {@code Link} that SWT paints itself (a
 * {@code TextLayout} drawn in the {@code draw} signal). Cocoa : {@code NSButton} and its bezel styles,
 * {@code NSTextField}, {@code NSBox}, {@code NSTextView} for {@code Link}.
 * <p>
 * Why it matters for a native executable : every one of these callbacks, and every struct class SWT copies from native
 * memory ({@code DRAWITEMSTRUCT}, {@code NMCUSTOMDRAW}, {@code BUTTON_IMAGELIST}, {@code LITEM}, {@code SIZE}...) is
 * reached through JNI, with field ids looked up by name : a class, a field or a method missing from the native image is
 * an error at the first paint or the first notification, not at build time. The images are computed (no file, no
 * clock) : the page renders the same pixels in every run.
 */
@Singleton
public class SwtButtonsPage implements SwtPage {

    private static final int ACCENT = 0x1976D2;
    private static final int TINT = 0xE3F2FD;
    private static final int LINK_COLOR = 0xAD1457;
    private static final int LABEL_WIDTH = 180;
    private static final int CHECK_NAME_WIDTH = 460;
    private static final int ICON_SIZE = 16;
    private static final int PICTURE_SIZE = 40;
    private static final String WRAPPED = "SWT.WRAP : a label wraps its text at the width its layout gives it,"
            + " here 180 points.";
    private static final String LINK = "See the <a href=\"https://www.eclipse.org/swt/\">SWT home page</a>,\nthe"
            + " <a href=\"widgets\">widget gallery</a> or <a>the snippets</a>\n(a link without href).";
    private static final String[] DIRECTIONS = { "UP", "DOWN", "LEFT", "RIGHT" };
    private static final int[] DIRECTION_BITS = { SWT.UP, SWT.DOWN, SWT.LEFT, SWT.RIGHT };

    // per build state (one content at a time)
    private Widgets w;
    private ChecksTable checksTable;

    /** The controls of one build, read by the checks. */
    private static final class Widgets {
        Button push;
        Button disabledPush;
        Button colored;
        Button imagePush;
        Button leftPush;
        Button toggleOff;
        Button toggleOn;
        Button toggleDisabled;
        Button unchecked;
        Button checked;
        Button grayed;
        Button disabledCheck;
        Button disabledChecked;
        Button disabledGrayed;
        Button small;
        Button medium;
        Button large;
        Button disabledRadio;
        Button disabledRadioSelected;
        final Button[] arrows = new Button[4];
        final Button[] flatArrows = new Button[4];
        final Button[] disabledArrows = new Button[4];
        Button flatPush;
        Button flatToggle;
        Button flatCheck;
        Button flatRadio;
        Label text;
        Label picture;
        Label left;
        Label center;
        Label right;
        Label wrap;
        Label horizontal;
        Label shadowIn;
        Label shadowNone;
        Label vertical;
        Link link;
        Link coloredLink;
        Link disabledLink;
        Group titled;
        Group untitled;
        ImageData icon;
        ImageData pictureData;
    }

    @Override
    public String id() {
        return "swt-buttons";
    }

    @Override
    public String title() {
        return "Buttons and labels";
    }

    @Override
    public String category() {
        return SwtCategories.WIDGETS;
    }

    @Override
    public int order() {
        return 10;
    }

    @Override
    public Control build(Composite parent) {
        w = new Widgets();
        w.icon = icon(ICON_SIZE);
        w.pictureData = picture(PICTURE_SIZE);
        Composite page = SwtKit.page(parent, 12);
        SwtKit.text(page, "The native buttons of SWT in their five types (PUSH, CHECK, RADIO, TOGGLE, ARROW) and FLAT,"
                + " in every state, with labels, separators, links and groups. On Windows, each one is a native window"
                + " subclassed by SWT : its messages, owner draw and custom draw notifications come back to Java"
                + " through JNI callbacks. The checks read the states back from the native controls.",
                SwtKit.TEXT_WIDTH);

        Composite row1 = SwtKit.row(page, 12);
        pushButtons(row1);
        toggleButtons(row1);
        checkBoxes(row1);
        radioButtons(row1);
        arrowButtons(row1);

        Composite row2 = SwtKit.row(page, 12);
        flatButtons(row2);
        labels(row2);
        separators(row2);
        Composite column = SwtKit.column(row2, 12);
        links(column);
        groups(column);

        checksTable = ChecksTable.table(page, "Button, Label, Link and Group checks",
                List.of(Check.info("state", "pending")), CHECK_NAME_WIDTH, ChecksTable.WIDTH);
        return page;
    }

    // ---------------------------------------------------------------------------------------------------- buttons

    private void pushButtons(Composite parent) {
        Group group = group(parent, "SWT.PUSH", 1);
        w.push = button(group, SWT.PUSH, "Push");
        w.disabledPush = button(group, SWT.PUSH, "Disabled");
        w.disabledPush.setEnabled(false);
        // a background or foreground color : SWT draws the button itself (NM_CUSTOMDRAW on Windows)
        w.colored = button(group, SWT.PUSH, "Custom colors");
        w.colored.setBackground(SwtKit.color(ACCENT));
        w.colored.setForeground(SwtKit.color(SwtKit.WHITE));
        w.imagePush = button(group, SWT.PUSH, "Image + text");
        Image image = new Image(group.getDisplay(), w.icon);
        w.imagePush.setImage(image);
        w.imagePush.addListener(SWT.Dispose, event -> image.dispose());
        w.leftPush = button(group, SWT.PUSH | SWT.LEFT, "SWT.LEFT");
    }

    private void toggleButtons(Composite parent) {
        Group group = group(parent, "SWT.TOGGLE", 1);
        w.toggleOff = button(group, SWT.TOGGLE, "Toggle");
        w.toggleOn = button(group, SWT.TOGGLE, "Toggle selected");
        w.toggleOn.setSelection(true);
        w.toggleDisabled = button(group, SWT.TOGGLE, "Disabled selected");
        w.toggleDisabled.setSelection(true);
        w.toggleDisabled.setEnabled(false);
    }

    private void checkBoxes(Composite parent) {
        Group group = group(parent, "SWT.CHECK", 1);
        w.unchecked = button(group, SWT.CHECK, "Unchecked");
        w.checked = button(group, SWT.CHECK, "Checked");
        w.checked.setSelection(true);
        // grayed and selected : the indeterminate state (BST_INDETERMINATE on Windows)
        w.grayed = button(group, SWT.CHECK, "Grayed");
        w.grayed.setSelection(true);
        w.grayed.setGrayed(true);
        w.disabledCheck = button(group, SWT.CHECK, "Disabled");
        w.disabledCheck.setEnabled(false);
        w.disabledChecked = button(group, SWT.CHECK, "Disabled checked");
        w.disabledChecked.setSelection(true);
        w.disabledChecked.setEnabled(false);
        w.disabledGrayed = button(group, SWT.CHECK, "Disabled grayed");
        w.disabledGrayed.setSelection(true);
        w.disabledGrayed.setGrayed(true);
        w.disabledGrayed.setEnabled(false);
    }

    private void radioButtons(Composite parent) {
        Group group = group(parent, "SWT.RADIO", 1);
        w.small = button(group, SWT.RADIO, "Small");
        w.medium = button(group, SWT.RADIO, "Medium");
        w.medium.setSelection(true);
        w.large = button(group, SWT.RADIO, "Large");
        // another parent : another radio group
        Composite other = SwtKit.column(group, 6);
        w.disabledRadio = button(other, SWT.RADIO, "Disabled");
        w.disabledRadio.setEnabled(false);
        w.disabledRadioSelected = button(other, SWT.RADIO, "Disabled selected");
        w.disabledRadioSelected.setSelection(true);
        w.disabledRadioSelected.setEnabled(false);
    }

    private void arrowButtons(Composite parent) {
        Group group = group(parent, "SWT.ARROW", 4);
        arrows(group, SWT.NONE, true, w.arrows);
        arrows(group, SWT.FLAT, true, w.flatArrows);
        arrows(group, SWT.NONE, false, w.disabledArrows);
        Label caption = SwtKit.caption(group, "rows : normal,\nFLAT, disabled");
        caption.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false, 4, 1));
    }

    private static void arrows(Composite parent, int style, boolean enabled, Button[] arrows) {
        for (int i = 0; i < DIRECTION_BITS.length; i++) {
            Button arrow = new Button(parent, SWT.ARROW | DIRECTION_BITS[i] | style);
            arrow.setEnabled(enabled);
            SwtKit.size(arrow, 24, 24);
            arrows[i] = arrow;
        }
    }

    private void flatButtons(Composite parent) {
        Group group = group(parent, "SWT.FLAT", 1);
        w.flatPush = button(group, SWT.PUSH | SWT.FLAT, "Flat push");
        w.flatToggle = button(group, SWT.TOGGLE | SWT.FLAT, "Flat toggle");
        w.flatToggle.setSelection(true);
        w.flatCheck = button(group, SWT.CHECK | SWT.FLAT, "Flat check");
        w.flatCheck.setSelection(true);
        w.flatRadio = button(group, SWT.RADIO | SWT.FLAT, "Flat radio");
        w.flatRadio.setSelection(true);
    }

    private static Button button(Composite parent, int style, String text) {
        Button button = new Button(parent, style);
        button.setText(text);
        if ((style & (SWT.PUSH | SWT.TOGGLE)) != 0) {
            // as wide as the widest button of the group
            button.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, false, false));
        }
        return button;
    }

    // ----------------------------------------------------------------------------------------------------- labels

    private void labels(Composite parent) {
        Group group = group(parent, "Label", 2);
        w.text = new Label(group, SWT.NONE);
        w.text.setText("A text label");
        w.picture = SwtKit.image(group, w.pictureData);
        w.picture.setLayoutData(new GridData(SWT.RIGHT, SWT.CENTER, false, false, 1, 2));
        SwtKit.caption(group, "a GC drawing");
        w.left = aligned(group, SWT.LEFT, "SWT.LEFT");
        w.center = aligned(group, SWT.CENTER, "SWT.CENTER");
        w.right = aligned(group, SWT.RIGHT, "SWT.RIGHT");
        w.wrap = new Label(group, SWT.WRAP);
        w.wrap.setText(WRAPPED);
        GridData wrapData = new GridData(SWT.LEFT, SWT.TOP, false, false, 2, 1);
        wrapData.widthHint = LABEL_WIDTH;
        w.wrap.setLayoutData(wrapData);
    }

    private static Label aligned(Composite parent, int alignment, String text) {
        Label label = new Label(parent, alignment);
        label.setText(text);
        label.setBackground(SwtKit.color(TINT));
        GridData data = new GridData(SWT.LEFT, SWT.CENTER, false, false, 2, 1);
        data.widthHint = LABEL_WIDTH;
        label.setLayoutData(data);
        return label;
    }

    private void separators(Composite parent) {
        Group group = group(parent, "SWT.SEPARATOR", 2);
        Composite horizontals = SwtKit.column(group, 10);
        SwtKit.caption(horizontals, "HORIZONTAL");
        w.horizontal = separator(horizontals, SWT.HORIZONTAL, 120);
        SwtKit.caption(horizontals, "SHADOW_IN");
        w.shadowIn = separator(horizontals, SWT.HORIZONTAL | SWT.SHADOW_IN, 120);
        SwtKit.caption(horizontals, "SHADOW_NONE");
        w.shadowNone = separator(horizontals, SWT.HORIZONTAL | SWT.SHADOW_NONE, 120);
        Composite verticals = SwtKit.column(group, 4);
        SwtKit.caption(verticals, "VERTICAL");
        w.vertical = separator(verticals, SWT.VERTICAL, 92);
        ((GridData) w.vertical.getLayoutData()).horizontalAlignment = SWT.CENTER;
    }

    private static Label separator(Composite parent, int style, int length) {
        Label separator = new Label(parent, SWT.SEPARATOR | style);
        boolean horizontal = (style & SWT.HORIZONTAL) != 0;
        SwtKit.size(separator, horizontal ? length : SWT.DEFAULT, horizontal ? SWT.DEFAULT : length);
        return separator;
    }

    // ------------------------------------------------------------------------------------------- links and groups

    private void links(Composite parent) {
        Group group = group(parent, "Link", 1);
        w.link = new Link(group, SWT.NONE);
        w.link.setText(LINK);
        // never opened : no listener, no program launched
        w.coloredLink = new Link(group, SWT.NONE);
        w.coloredLink.setText("A <a>link</a> with setLinkForeground");
        w.coloredLink.setLinkForeground(SwtKit.color(LINK_COLOR));
        w.disabledLink = new Link(group, SWT.NONE);
        w.disabledLink.setText("A disabled <a>link</a>");
        w.disabledLink.setEnabled(false);
    }

    private void groups(Composite parent) {
        w.titled = group(parent, "Group with a title", 1);
        new Label(w.titled, SWT.NONE).setText("A Composite with a native frame");
        w.untitled = new Group(w.titled, SWT.SHADOW_ETCHED_IN);
        GridLayout layout = new GridLayout(1, false);
        layout.marginWidth = 8;
        layout.marginHeight = 4;
        w.untitled.setLayout(layout);
        new Label(w.untitled, SWT.NONE).setText("A Group without a title");
    }

    /**
     * A group with a title, laying its children out in {@code columns} columns.
     */
    private static Group group(Composite parent, String title, int columns) {
        Group group = new Group(parent, SWT.NONE);
        group.setText(title);
        GridLayout layout = new GridLayout(columns, false);
        layout.marginWidth = 8;
        layout.marginHeight = 6;
        layout.horizontalSpacing = 6;
        layout.verticalSpacing = 6;
        group.setLayout(layout);
        return group;
    }

    // ----------------------------------------------------------------------------------------------------- images

    /**
     * A {@code size x size} disc with an alpha channel and a soft edge, computed without a GC (4 x 4 samples per pixel,
     * integer arithmetic) : the same bytes on every platform and in every run.
     */
    private static ImageData icon(int size) {
        ImageData data = new ImageData(size, size, 24, new PaletteData(0xFF0000, 0xFF00, 0xFF));
        data.alphaData = new byte[size * size];
        // coordinates in eighths of a pixel : the samples at 1, 3, 5, 7 of every pixel
        int center = size * 4;
        int outer = size * 4 - 4;
        int inner = size * 4 / 3;
        for (int y = 0; y < size; y++) {
            // a vertical gradient from 0x42A5F5 to 0x1565C0
            int red = 0x42 + (0x15 - 0x42) * y / (size - 1);
            int green = 0xA5 + (0x65 - 0xA5) * y / (size - 1);
            int blue = 0xF5 + (0xC0 - 0xF5) * y / (size - 1);
            for (int x = 0; x < size; x++) {
                int disc = 0;
                int dot = 0;
                for (int sy = 1; sy < 8; sy += 2) {
                    for (int sx = 1; sx < 8; sx += 2) {
                        int dx = x * 8 + sx - center;
                        int dy = y * 8 + sy - center;
                        int d2 = dx * dx + dy * dy;
                        disc += d2 <= outer * outer ? 1 : 0;
                        dot += d2 <= inner * inner ? 1 : 0;
                    }
                }
                // the white dot blended over the gradient, the disc coverage as alpha
                int r = (red * (16 - dot) + 0xFF * dot) / 16;
                int g = (green * (16 - dot) + 0xFF * dot) / 16;
                int b = (blue * (16 - dot) + 0xFF * dot) / 16;
                data.setPixel(x, y, r << 16 | g << 8 | b);
                data.alphaData[y * size + x] = (byte) (disc * 255 / 16);
            }
        }
        return data;
    }

    /**
     * A {@code size x size} picture painted offscreen with a GC : a gradient, a disc and a triangle.
     */
    private static ImageData picture(int size) {
        return SwtSnapshots.offscreen(size, size, gc -> {
            gc.setAntialias(SWT.ON);
            gc.setForeground(SwtKit.color(0xFFCA28));
            gc.setBackground(SwtKit.color(0xEF6C00));
            gc.fillGradientRectangle(0, 0, size, size, true);
            gc.setBackground(SwtKit.color(SwtKit.WHITE));
            gc.fillOval(size / 8, size / 8, size / 2, size / 2);
            gc.setBackground(SwtKit.color(0x263238));
            gc.fillPolygon(new int[] { size / 2, size * 5 / 8, size - 4, size - 4, size / 4, size - 4 });
            gc.setForeground(SwtKit.color(0x263238));
            gc.drawRectangle(0, 0, size - 1, size - 1);
        });
    }

    // ------------------------------------------------------------------------------------------------------ ready

    @Override
    public CompletionStage<?> ready(Control content) {
        Widgets widgets = w;
        ChecksTable table = checksTable;
        return UiStages.rounds(2)
                .thenAccept(v -> table.setChecks(checks(widgets)))
                .thenCompose(v -> UiStages.rounds(2));
    }

    private static List<Check> checks(Widgets w) {
        List<Check> checks = new ArrayList<>();
        // Button : types and styles
        checks.add(SwtChecks.expect("Button types (getStyle)", "PUSH CHECK RADIO TOGGLE ARROW",
                () -> join(Stream.of(w.push, w.checked, w.medium, w.toggleOn, w.arrows[0]), SwtButtonsPage::type)));
        checks.add(SwtChecks.expect("SWT.FLAT kept (push, toggle, check, radio, arrow)", "true true true true true",
                () -> join(Stream.of(w.flatPush, w.flatToggle, w.flatCheck, w.flatRadio, w.flatArrows[0]),
                        b -> String.valueOf((b.getStyle() & SWT.FLAT) != 0))));
        checks.add(SwtChecks.expect("ARROW directions (getAlignment)", "UP DOWN LEFT RIGHT",
                () -> join(Arrays.stream(w.arrows), b -> direction(b.getAlignment()))));
        checks.add(SwtChecks.expect("ARROW setAlignment(SWT.LEFT) round trip", "LEFT, then UP", () -> {
            Button arrow = w.disabledArrows[0];
            arrow.setAlignment(SWT.LEFT);
            String changed = direction(arrow.getAlignment());
            arrow.setAlignment(SWT.UP);
            return changed + ", then " + direction(arrow.getAlignment());
        }));
        checks.add(SwtChecks.expect("PUSH alignment (getAlignment) : default, SWT.LEFT", "CENTER LEFT",
                () -> alignment(w.push.getAlignment()) + " " + alignment(w.leftPush.getAlignment())));
        // Button : states
        checks.add(SwtChecks.expect("CHECK getSelection of the six check boxes",
                "false true true false true true", () -> join(Stream.of(w.unchecked, w.checked, w.grayed,
                        w.disabledCheck, w.disabledChecked, w.disabledGrayed), b -> String.valueOf(b.getSelection()))));
        checks.add(SwtChecks.expect("CHECK getGrayed of the six check boxes",
                "false false true false false true", () -> join(Stream.of(w.unchecked, w.checked, w.grayed,
                        w.disabledCheck, w.disabledChecked, w.disabledGrayed), b -> String.valueOf(b.getGrayed()))));
        checks.add(SwtChecks.expect("CHECK setGrayed true / false : selection grayed",
                "true true, then true false", () -> {
                    Button box = w.checked;
                    box.setGrayed(true);
                    String grayed = box.getSelection() + " " + box.getGrayed();
                    box.setGrayed(false);
                    return grayed + ", then " + box.getSelection() + " " + box.getGrayed();
                }));
        checks.add(SwtChecks.expect("CHECK setSelection(true / false) round trip", "true, then false", () -> {
            Button box = w.unchecked;
            box.setSelection(true);
            String selected = String.valueOf(box.getSelection());
            box.setSelection(false);
            return selected + ", then " + box.getSelection();
        }));
        checks.add(SwtChecks.expect("TOGGLE getSelection of the three toggles", "false true true",
                () -> join(Stream.of(w.toggleOff, w.toggleOn, w.toggleDisabled),
                        b -> String.valueOf(b.getSelection()))));
        checks.add(SwtChecks.expect("RADIO getSelection of the two radio groups", "false true false / false true",
                () -> join(Stream.of(w.small, w.medium, w.large), b -> String.valueOf(b.getSelection())) + " / "
                        + w.disabledRadio.getSelection() + " " + w.disabledRadioSelected.getSelection()));
        // the native radio buttons are not automatic (BS_RADIOBUTTON) : SWT groups them on a click only
        checks.add(SwtChecks.onlyOn(SwtMode.Os.WINDOWS, SwtChecks.expect(
                "RADIO setSelection(Large) keeps Medium selected", "false true true", () -> {
                    w.large.setSelection(true);
                    String states = join(Stream.of(w.small, w.medium, w.large),
                            b -> String.valueOf(b.getSelection()));
                    w.large.setSelection(false);
                    return states;
                })));
        checks.add(SwtChecks.expect("FLAT selection : toggle, check, radio", "true true true",
                () -> join(Stream.of(w.flatToggle, w.flatCheck, w.flatRadio), b -> String.valueOf(b.getSelection()))));
        checks.add(SwtChecks.expect("isEnabled : disabled push, toggle, check, radio, arrow, link",
                "false false false false false false", () -> join(Stream.of(w.disabledPush, w.toggleDisabled,
                        w.disabledCheck, w.disabledRadio, w.disabledArrows[0], w.disabledLink),
                        c -> String.valueOf(c.isEnabled()))));
        checks.add(SwtChecks.expect("PUSH with image : text, image bounds", "Image + text, 0,0 16x16",
                () -> w.imagePush.getText() + ", " + SwtChecks.rect(w.imagePush.getImage().getBounds())));
        checks.add(SwtChecks.expect("PUSH custom colors (custom drawn)", "#1976D2 on #FFFFFF",
                () -> SwtChecks.rgb(w.colored.getBackground().getRGB()) + " on "
                        + SwtChecks.rgb(w.colored.getForeground().getRGB())));
        checks.add(SwtChecks.expect("PUSH image read back : alpha, opaque pixels", "identical, identical", () -> {
            int[] expected = SwtSnapshots.argb(w.icon);
            int[] actual = SwtSnapshots.argb(w.imagePush.getImage().getImageData());
            boolean alpha = expected.length == actual.length;
            boolean opaque = alpha;
            for (int i = 0; alpha && i < expected.length; i++) {
                alpha = expected[i] >>> 24 == actual[i] >>> 24;
                opaque = opaque && (expected[i] >>> 24 != 0xFF || expected[i] == actual[i]);
            }
            return (alpha ? "identical" : "different") + ", " + (opaque ? "identical" : "different");
        }));
        // Label
        checks.add(SwtChecks.expect("Label text, alignments (getAlignment)", "A text label, LEFT CENTER RIGHT",
                () -> w.text.getText() + ", " + join(Stream.of(w.left, w.center, w.right),
                        l -> alignment(l.getAlignment()))));
        checks.add(SwtChecks.expect("Label image read back : bounds, pixels", "0,0 40x40, identical", () -> {
            Image image = w.picture.getImage();
            boolean same = Arrays.equals(SwtSnapshots.argb(w.pictureData),
                    SwtSnapshots.argb(image.getImageData()));
            return SwtChecks.rect(image.getBounds()) + ", " + (same ? "identical" : "different");
        }));
        checks.add(SwtChecks.info("Label image : SHA-256 of the GC drawing",
                () -> SwtChecks.sha256(w.pictureData)));
        // Label.checkStyle : SHADOW_OUT is the default shadow of a separator
        checks.add(SwtChecks.expect("SEPARATOR styles (SHADOW_OUT by default)",
                "HORIZONTAL SHADOW_OUT, HORIZONTAL SHADOW_IN, HORIZONTAL SHADOW_NONE, VERTICAL SHADOW_OUT",
                () -> Stream.of(w.horizontal, w.shadowIn, w.shadowNone, w.vertical)
                        .map(SwtButtonsPage::separatorStyle).collect(Collectors.joining(", "))));
        checks.add(SwtChecks.expect("SEPARATOR : SWT.SEPARATOR bit, no text, no image", "true '' null",
                () -> Stream.of(w.horizontal, w.shadowIn, w.shadowNone, w.vertical)
                        .allMatch(l -> (l.getStyle() & SWT.SEPARATOR) != 0) + " '" + w.horizontal.getText() + "' "
                        + w.horizontal.getImage()));
        checks.add(SwtChecks.expect("SEPARATOR sizes : horizontal wide, vertical tall", "true true", () -> {
            Point h = w.horizontal.getSize();
            Point v = w.vertical.getSize();
            return (h.x > h.y) + " " + (v.y > v.x);
        }));
        checks.add(wrapCheck(w));
        // Link
        checks.add(SwtChecks.expect("Link getText returns the markup as set", true,
                () -> w.link.getText().equals(LINK)));
        checks.add(SwtChecks.expect("Link setLinkForeground / getLinkForeground", "#AD1457",
                () -> SwtChecks.rgb(w.coloredLink.getLinkForeground().getRGB())));
        checks.add(SwtChecks.expect("Link on three lines is taller than a link on one", true,
                () -> w.link.computeSize(SWT.DEFAULT, SWT.DEFAULT).y
                        > w.coloredLink.computeSize(SWT.DEFAULT, SWT.DEFAULT).y));
        // Group
        checks.add(SwtChecks.expect("Group texts : titled, untitled", "Group with a title, ''",
                () -> w.titled.getText() + ", '" + w.untitled.getText() + "'"));
        // GTK : getClientArea() is at 0,0, Group.getClientAreaInPixels forces x and y to 0 (SWT bug 453827,
        // Group.java:135-153 of SWT GTK 3.132.0) : the client widget is placed by the trim of computeTrim (its
        // allocation in the frame, Group.computeTrimInPixels, Group.java:120-132), and below the title its top trim is
        // larger than the one of the untitled Group
        checks.add(SwtChecks.expect("Group client area inside its bounds, below its title", true, () -> {
            Rectangle area = w.titled.getClientArea();
            Point size = w.titled.getSize();
            if (SwtMode.isLinux()) {
                Rectangle trim = w.titled.computeTrim(0, 0, 0, 0);
                return area.x == 0 && area.y == 0 && trim.x <= 0 && area.width - trim.x <= size.x
                        && area.height - trim.y <= size.y && trim.y < w.untitled.computeTrim(0, 0, 0, 0).y;
            }
            return area.x >= 0 && area.y > 0 && area.x + area.width <= size.x && area.y + area.height <= size.y;
        }));
        // computed sizes : relations that hold on every platform, the values themselves for the comparison
        checks.add(SwtChecks.expect("computeSize : check box, checked = unchecked", true,
                () -> sameSize(w.unchecked, "Unchecked", w.checked)));
        checks.add(SwtChecks.expect("computeSize : the four arrows are alike", true,
                () -> Arrays.stream(w.arrows).map(b -> b.computeSize(SWT.DEFAULT, SWT.DEFAULT)).distinct()
                        .count() == 1));
        checks.add(SwtChecks.expect("computeSize : a push button grows with its text", true,
                () -> w.disabledPush.computeSize(SWT.DEFAULT, SWT.DEFAULT).x
                        > w.push.computeSize(SWT.DEFAULT, SWT.DEFAULT).x));
        checks.add(SwtChecks.expect("computeSize : an image widens a push button", true, () -> {
            Point withImage = w.imagePush.computeSize(SWT.DEFAULT, SWT.DEFAULT);
            Image image = w.imagePush.getImage();
            w.imagePush.setImage(null);
            Point without = w.imagePush.computeSize(SWT.DEFAULT, SWT.DEFAULT);
            w.imagePush.setImage(image);
            return withImage.x > without.x;
        }));
        checks.add(SwtChecks.info("computeSize : push, check, radio, toggle, arrow, flat",
                () -> join(Stream.of(w.push, w.checked, w.medium, w.toggleOff, w.arrows[0], w.flatPush),
                        c -> SwtChecks.size(c.computeSize(SWT.DEFAULT, SWT.DEFAULT)))));
        checks.add(SwtChecks.info("computeSize : label, link ; Group computeTrim(100 x 50)",
                () -> SwtChecks.size(w.text.computeSize(SWT.DEFAULT, SWT.DEFAULT)) + " "
                        + SwtChecks.size(w.link.computeSize(SWT.DEFAULT, SWT.DEFAULT)) + " "
                        + SwtChecks.rect(w.untitled.computeTrim(0, 0, 100, 50))));
        return checks;
    }

    /**
     * The wrapped label : as wide as its width hint, and on several lines.
     */
    private static Check wrapCheck(Widgets w) {
        try {
            Point wrapped = w.wrap.computeSize(LABEL_WIDTH, SWT.DEFAULT);
            int line = w.text.computeSize(SWT.DEFAULT, SWT.DEFAULT).y;
            boolean ok = wrapped.x == LABEL_WIDTH && wrapped.y >= 2 * line;
            return Check.of("WRAP label : computeSize(180, SWT.DEFAULT)", ok,
                    ok ? "180 wide, " + (wrapped.y >= 3 * line ? "3 lines or more" : "2 lines")
                            : SwtChecks.size(wrapped) + " (one line : " + line + ")");
        } catch (Throwable t) {
            return Check.fail("WRAP label : computeSize(180, SWT.DEFAULT)", SwtChecks.describe(t));
        }
    }

    private static boolean sameSize(Button reference, String text, Button other) {
        // the same text : the selection must not change the size
        String saved = other.getText();
        other.setText(text);
        boolean same = reference.computeSize(SWT.DEFAULT, SWT.DEFAULT).equals(other.computeSize(SWT.DEFAULT,
                SWT.DEFAULT));
        other.setText(saved);
        return same;
    }

    private static <C extends Control> String join(Stream<C> controls, Function<C, String> value) {
        return controls.map(value).collect(Collectors.joining(" "));
    }

    private static String type(Button button) {
        int style = button.getStyle();
        return (style & SWT.PUSH) != 0 ? "PUSH" : (style & SWT.CHECK) != 0 ? "CHECK" : (style & SWT.RADIO) != 0
                ? "RADIO" : (style & SWT.TOGGLE) != 0 ? "TOGGLE" : (style & SWT.ARROW) != 0 ? "ARROW" : "NONE";
    }

    private static String direction(int alignment) {
        for (int i = 0; i < DIRECTION_BITS.length; i++) {
            if (alignment == DIRECTION_BITS[i]) {
                return DIRECTIONS[i];
            }
        }
        return String.valueOf(alignment);
    }

    private static String alignment(int alignment) {
        return switch (alignment) {
            case SWT.LEFT -> "LEFT";
            case SWT.CENTER -> "CENTER";
            case SWT.RIGHT -> "RIGHT";
            default -> String.valueOf(alignment);
        };
    }

    private static String separatorStyle(Label label) {
        int style = label.getStyle();
        StringBuilder sb = new StringBuilder();
        sb.append((style & SWT.HORIZONTAL) != 0 ? "HORIZONTAL" : "");
        sb.append((style & SWT.VERTICAL) != 0 ? "VERTICAL" : "");
        sb.append((style & SWT.SHADOW_IN) != 0 ? " SHADOW_IN" : "");
        sb.append((style & SWT.SHADOW_OUT) != 0 ? " SHADOW_OUT" : "");
        sb.append((style & SWT.SHADOW_NONE) != 0 ? " SHADOW_NONE" : "");
        return sb.toString();
    }

    @Override
    public void dispose(Control content) {
        // the images are disposed with their controls
        w = null;
        checksTable = null;
    }
}
