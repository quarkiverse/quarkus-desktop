package io.quarkiverse.desktop.showcase.swt.pages.widgets;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import jakarta.inject.Singleton;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.DateTime;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.ProgressBar;
import org.eclipse.swt.widgets.Scale;
import org.eclipse.swt.widgets.Slider;
import org.eclipse.swt.widgets.Spinner;
import org.eclipse.swt.widgets.Text;

import io.quarkiverse.desktop.showcase.core.Check;
import io.quarkiverse.desktop.showcase.swt.core.ChecksTable;
import io.quarkiverse.desktop.showcase.swt.core.SwtCategories;
import io.quarkiverse.desktop.showcase.swt.core.SwtChecks;
import io.quarkiverse.desktop.showcase.swt.core.SwtKit;
import io.quarkiverse.desktop.showcase.swt.core.SwtMode;
import io.quarkiverse.desktop.showcase.swt.core.SwtPage;
import io.quarkiverse.desktop.showcase.swt.core.UiStages;

/**
 * The input controls of SWT : {@link Text} (single line, multi line, password, read only, search with its icons and
 * message, wrapped), {@link Spinner} (digits, range, increments), {@link Combo} ({@code DROP_DOWN}, {@code READ_ONLY},
 * {@code SIMPLE}), {@link DateTime} ({@code DATE} in its three lengths, {@code TIME}, {@code CALENDAR}), {@link Scale},
 * {@link Slider} and the determinate {@link ProgressBar} in its states (normal, paused, error) and vertical. Every
 * value is fixed, and the date is in the past (29 February 2024) : the month calendar never shows the current day.
 * <p>
 * Native code paths. Windows : {@code EDIT} controls ({@code ES_PASSWORD}, {@code ES_READONLY}, {@code ES_MULTILINE},
 * {@code EM_SETCUEBANNER} for the message, owner drawn icons of the search field : {@code WM_DRAWITEM}), the
 * {@code msctls_updown32} buddy of the spinner ({@code UDN_DELTAPOS} in {@code WM_NOTIFY}, an {@code NMUPDOWN}), the
 * {@code COMBOBOX} control and its edit and list children ({@code COMBOBOXINFO}), {@code SysDateTimePick32} and
 * {@code SysMonthCal32} ({@code SYSTEMTIME} structs : {@code DTM_SETSYSTEMTIME}, {@code MCM_SETCURSEL},
 * {@code MCM_GETMINREQRECT}), {@code msctls_trackbar32} for the scale ({@code TBM_*}), a {@code SCROLLBAR} control for
 * the slider ({@code SCROLLINFO}), {@code msctls_progress32} ({@code PBM_SETPOS}, {@code PBM_SETSTATE}) ; all of them
 * subclassed by SWT, their messages coming back through the JNI callback of {@code Display.windowProc}. GTK :
 * {@code GtkEntry} (with its icons and placeholder) and {@code GtkTextView}, {@code GtkSpinButton},
 * {@code GtkComboBoxText}, the date and time fields and {@code GtkCalendar}, {@code GtkScale}, {@code GtkScrollbar},
 * {@code GtkProgressBar}, with their {@code changed}, {@code value-changed} and {@code day-selected} signals. Cocoa :
 * {@code NSTextField}, {@code NSSecureTextField}, {@code NSSearchField}, {@code NSTextView}, {@code NSStepper},
 * {@code NSComboBox}, {@code NSPopUpButton}, {@code NSDatePicker}, {@code NSSlider}, {@code NSScroller},
 * {@code NSProgressIndicator}.
 * <p>
 * Why it matters for a native executable : the values travel through structs that SWT fills and reads with JNI (field
 * ids looked up by name) and through notifications that call back into Java : a struct class or a callback missing
 * from the native image shows up as an error at the first notification or the first paint.
 * <p>
 * Deterministic : no text field has the focus (no caret), no progress bar is indeterminate (its animation would be
 * captured at a random frame), and the bar in the normal state is complete : on Windows, the theme moves a highlight
 * along the filled part of a normal bar below its maximum, every few seconds (the paused and error bars, and a complete
 * normal bar, are still). The page waits until the bars finished animating their fill.
 */
@Singleton
public class SwtInputsPage implements SwtPage {

    private static final int FIELD_WIDTH = 150;
    private static final int AREA_WIDTH = 180;
    private static final int AREA_HEIGHT = 84;
    private static final int COMBO_WIDTH = 130;
    private static final int SPINNER_WIDTH = 100;
    private static final int BAR_LENGTH = 150;
    private static final int CHECK_NAME_WIDTH = 460;
    /** 29 February 2024, 10:30:00 : a leap day, in the past. */
    private static final int YEAR = 2024;
    private static final int MONTH = 1;
    private static final int DAY = 29;
    private static final int HOURS = 10;
    private static final int MINUTES = 30;
    private static final int SECONDS = 0;
    private static final String MULTI = "Multi line text\nwith H_SCROLL and V_SCROLL,\nand a line longer than the"
            + " visible width of the control\nline 4\nline 5\nline 6";
    private static final String WRAP = "SWT.WRAP : a multi line text wraps its long lines at the width of the"
            + " control, here 180 points, between the words.";
    private static final String[] COLORS = { "Red", "Green", "Blue", "Cyan", "Magenta" };
    private static final String[] SIZES = { "Small", "Medium", "Large" };
    private static final String[] PLANETS = { "Mercury", "Venus", "Earth", "Mars", "Jupiter", "Saturn" };

    // per build state (one content at a time)
    private Widgets w;
    private ChecksTable checksTable;

    /** The controls of one build, read by the checks. */
    private static final class Widgets {
        Text single;
        Text message;
        Text password;
        Text readOnly;
        Text search;
        Text searchMessage;
        Text disabled;
        Text right;
        Text multi;
        Text wrap;
        Spinner decimal;
        Spinner integer;
        Spinner readOnlySpinner;
        Spinner disabledSpinner;
        Combo dropDown;
        Combo readOnlyCombo;
        Combo simple;
        Combo disabledCombo;
        DateTime shortDate;
        DateTime mediumDate;
        DateTime longDate;
        DateTime shortTime;
        DateTime mediumTime;
        DateTime calendar;
        Scale scale;
        Scale verticalScale;
        Slider slider;
        Slider verticalSlider;
        Slider disabledSlider;
        ProgressBar normal;
        ProgressBar paused;
        ProgressBar error;
        ProgressBar vertical;
        Group progressGroup;
    }

    @Override
    public String id() {
        return "swt-inputs";
    }

    @Override
    public String title() {
        return "Text and value inputs";
    }

    @Override
    public String category() {
        return SwtCategories.WIDGETS;
    }

    @Override
    public int order() {
        return 20;
    }

    @Override
    public Control build(Composite parent) {
        w = new Widgets();
        Composite page = SwtKit.page(parent, 12);
        SwtKit.text(page, "The native input controls of SWT with fixed values : text fields, spinners, combo boxes,"
                + " date and time pickers, scales, sliders and progress bars. No text field has the focus : no caret is"
                + " captured. The checks read the values, ranges, items and selections back from the native controls.",
                SwtKit.TEXT_WIDTH);

        Composite row1 = SwtKit.row(page, 12);
        texts(row1);
        areas(row1);
        combos(row1);
        progressBars(row1);

        Composite row2 = SwtKit.row(page, 12);
        dates(row2);
        spinners(row2);
        scales(row2);

        checksTable = ChecksTable.table(page, "Text, Spinner, Combo, DateTime, Scale, Slider and ProgressBar checks",
                List.of(Check.info("state", "pending")), CHECK_NAME_WIDTH, ChecksTable.WIDTH);
        return page;
    }

    // ------------------------------------------------------------------------------------------------------- text

    private void texts(Composite parent) {
        Group group = group(parent, "Text", 2);
        w.single = text(group, "SINGLE", SWT.SINGLE | SWT.BORDER, "Single line text");
        w.message = text(group, "message", SWT.SINGLE | SWT.BORDER, "");
        w.message.setMessage("Shown while empty");
        w.password = text(group, "PASSWORD", SWT.SINGLE | SWT.BORDER | SWT.PASSWORD, "secret");
        w.readOnly = text(group, "READ_ONLY", SWT.SINGLE | SWT.BORDER | SWT.READ_ONLY, "Read only text");
        w.search = text(group, "SEARCH", SWT.SEARCH | SWT.ICON_SEARCH | SWT.ICON_CANCEL, "quarkus desktop");
        w.searchMessage = text(group, "SEARCH", SWT.SEARCH | SWT.ICON_SEARCH | SWT.ICON_CANCEL, "");
        w.searchMessage.setMessage("Search");
        w.disabled = text(group, "disabled", SWT.SINGLE | SWT.BORDER, "Disabled text");
        w.disabled.setEnabled(false);
        w.right = text(group, "RIGHT", SWT.SINGLE | SWT.BORDER | SWT.RIGHT, "1234.50");
    }

    private static Text text(Composite parent, String caption, int style, String value) {
        new Label(parent, SWT.NONE).setText(caption);
        Text text = new Text(parent, style);
        text.setText(value);
        // as wide as the widest field (the search fields add their icons to the width hint)
        GridData data = new GridData(SWT.FILL, SWT.CENTER, false, false);
        data.widthHint = FIELD_WIDTH;
        text.setLayoutData(data);
        return text;
    }

    private void areas(Composite parent) {
        Group group = group(parent, "Text (MULTI)", 1);
        new Label(group, SWT.NONE).setText("H_SCROLL | V_SCROLL");
        w.multi = new Text(group, SWT.MULTI | SWT.BORDER | SWT.H_SCROLL | SWT.V_SCROLL);
        w.multi.setText(MULTI);
        SwtKit.size(w.multi, AREA_WIDTH, AREA_HEIGHT);
        new Label(group, SWT.NONE).setText("WRAP | V_SCROLL");
        w.wrap = new Text(group, SWT.MULTI | SWT.WRAP | SWT.BORDER | SWT.V_SCROLL);
        w.wrap.setText(WRAP);
        SwtKit.size(w.wrap, AREA_WIDTH, AREA_HEIGHT);
    }

    // ---------------------------------------------------------------------------------------- spinner and combo

    private void spinners(Composite parent) {
        Group group = group(parent, "Spinner", 1);
        new Label(group, SWT.NONE).setText("2 digits");
        w.decimal = spinner(group, SWT.BORDER);
        // selection, minimum, maximum, digits, increment, page increment
        w.decimal.setValues(1234, -500, 1500, 2, 25, 100);
        new Label(group, SWT.NONE).setText("WRAP");
        w.integer = spinner(group, SWT.BORDER | SWT.WRAP);
        w.integer.setValues(35, 0, 100, 0, 5, 20);
        new Label(group, SWT.NONE).setText("READ_ONLY");
        w.readOnlySpinner = spinner(group, SWT.BORDER | SWT.READ_ONLY);
        w.readOnlySpinner.setValues(7, 1, 12, 0, 1, 3);
        new Label(group, SWT.NONE).setText("disabled");
        w.disabledSpinner = spinner(group, SWT.BORDER);
        w.disabledSpinner.setValues(42, 0, 100, 0, 1, 10);
        w.disabledSpinner.setEnabled(false);
    }

    private static Spinner spinner(Composite parent, int style) {
        Spinner spinner = new Spinner(parent, style);
        SwtKit.size(spinner, SPINNER_WIDTH, SWT.DEFAULT);
        return spinner;
    }

    private void combos(Composite parent) {
        Group group = group(parent, "Combo", 1);
        new Label(group, SWT.NONE).setText("DROP_DOWN");
        w.dropDown = combo(group, SWT.DROP_DOWN, COLORS, 2);
        new Label(group, SWT.NONE).setText("READ_ONLY");
        w.readOnlyCombo = combo(group, SWT.READ_ONLY, SIZES, 1);
        new Label(group, SWT.NONE).setText("SIMPLE");
        w.simple = combo(group, SWT.SIMPLE, PLANETS, 2);
        SwtKit.size(w.simple, COMBO_WIDTH, 96);
        new Label(group, SWT.NONE).setText("disabled");
        w.disabledCombo = combo(group, SWT.READ_ONLY, SIZES, 0);
        w.disabledCombo.setEnabled(false);
    }

    private static Combo combo(Composite parent, int style, String[] items, int selection) {
        Combo combo = new Combo(parent, style);
        combo.setItems(items);
        combo.select(selection);
        SwtKit.size(combo, COMBO_WIDTH, SWT.DEFAULT);
        return combo;
    }

    // ------------------------------------------------------------------------------------------- date and time

    private void dates(Composite parent) {
        Group group = group(parent, "DateTime", 2);
        Composite fields = SwtKit.column(group, 4);
        fields.setLayoutData(new GridData(SWT.LEFT, SWT.TOP, false, false));
        new Label(fields, SWT.NONE).setText("DATE | SHORT");
        w.shortDate = date(fields, SWT.DATE | SWT.SHORT);
        new Label(fields, SWT.NONE).setText("MEDIUM | DROP_DOWN");
        w.mediumDate = date(fields, SWT.DATE | SWT.MEDIUM | SWT.DROP_DOWN);
        new Label(fields, SWT.NONE).setText("TIME | SHORT");
        w.shortTime = time(fields, SWT.TIME | SWT.SHORT);
        new Label(fields, SWT.NONE).setText("TIME | MEDIUM");
        w.mediumTime = time(fields, SWT.TIME | SWT.MEDIUM);
        Composite month = SwtKit.column(group, 4);
        month.setLayoutData(new GridData(SWT.LEFT, SWT.TOP, false, false));
        new Label(month, SWT.NONE).setText("CALENDAR");
        w.calendar = new DateTime(month, SWT.CALENDAR | SWT.BORDER);
        w.calendar.setDate(YEAR, MONTH, DAY);
        new Label(month, SWT.NONE).setText("DATE | LONG");
        w.longDate = date(month, SWT.DATE | SWT.LONG);
    }

    private static DateTime date(Composite parent, int style) {
        DateTime date = new DateTime(parent, style | SWT.BORDER);
        date.setDate(YEAR, MONTH, DAY);
        return date;
    }

    private static DateTime time(Composite parent, int style) {
        DateTime time = new DateTime(parent, style | SWT.BORDER);
        time.setTime(HOURS, MINUTES, SECONDS);
        return time;
    }

    // --------------------------------------------------------------------------------- scale, slider, progress

    private void scales(Composite parent) {
        Group group = group(parent, "Scale and Slider", 2);
        Composite horizontals = SwtKit.column(group, 4);
        new Label(horizontals, SWT.NONE).setText("Scale");
        w.scale = new Scale(horizontals, SWT.HORIZONTAL);
        w.scale.setMaximum(100);
        w.scale.setMinimum(0);
        w.scale.setIncrement(5);
        w.scale.setPageIncrement(20);
        w.scale.setSelection(40);
        SwtKit.size(w.scale, BAR_LENGTH, SWT.DEFAULT);
        new Label(horizontals, SWT.NONE).setText("Slider");
        w.slider = new Slider(horizontals, SWT.HORIZONTAL);
        // selection, minimum, maximum, thumb, increment, page increment
        w.slider.setValues(30, 0, 110, 10, 1, 10);
        SwtKit.size(w.slider, BAR_LENGTH, SWT.DEFAULT);
        new Label(horizontals, SWT.NONE).setText("disabled Slider");
        w.disabledSlider = new Slider(horizontals, SWT.HORIZONTAL);
        w.disabledSlider.setValues(60, 0, 110, 10, 1, 10);
        w.disabledSlider.setEnabled(false);
        SwtKit.size(w.disabledSlider, BAR_LENGTH, SWT.DEFAULT);
        Composite verticals = SwtKit.row(group, 8);
        verticals.setLayoutData(new GridData(SWT.LEFT, SWT.TOP, false, false));
        w.verticalScale = new Scale(verticals, SWT.VERTICAL);
        w.verticalScale.setMaximum(10);
        w.verticalScale.setPageIncrement(2);
        w.verticalScale.setSelection(7);
        SwtKit.size(w.verticalScale, SWT.DEFAULT, 150);
        w.verticalSlider = new Slider(verticals, SWT.VERTICAL);
        w.verticalSlider.setValues(20, 0, 100, 25, 1, 25);
        SwtKit.size(w.verticalSlider, SWT.DEFAULT, 150);
    }

    private void progressBars(Composite parent) {
        Group group = group(parent, "ProgressBar", 2);
        w.progressGroup = group;
        Composite horizontals = SwtKit.column(group, 4);
        // below 100 %, a normal bar is animated on Windows (a highlight moving along the bar)
        new Label(horizontals, SWT.NONE).setText("NORMAL, 100 %");
        w.normal = progressBar(horizontals, SWT.HORIZONTAL, 100, SWT.NORMAL);
        new Label(horizontals, SWT.NONE).setText("PAUSED, 45 %");
        w.paused = progressBar(horizontals, SWT.HORIZONTAL, 45, SWT.PAUSED);
        new Label(horizontals, SWT.NONE).setText("ERROR, 80 %");
        w.error = progressBar(horizontals, SWT.HORIZONTAL, 80, SWT.ERROR);
        w.vertical = progressBar(group, SWT.VERTICAL, 70, SWT.PAUSED);
        w.vertical.setLayoutData(new GridData(SWT.LEFT, SWT.TOP, false, false));
        SwtKit.size(w.vertical, SWT.DEFAULT, 130);
    }

    private static ProgressBar progressBar(Composite parent, int style, int selection, int state) {
        ProgressBar bar = new ProgressBar(parent, style);
        bar.setMinimum(0);
        bar.setMaximum(100);
        bar.setSelection(selection);
        bar.setState(state);
        if ((style & SWT.HORIZONTAL) != 0) {
            SwtKit.size(bar, BAR_LENGTH, SWT.DEFAULT);
        }
        return bar;
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
        layout.horizontalSpacing = 8;
        layout.verticalSpacing = 4;
        group.setLayout(layout);
        return group;
    }

    // ------------------------------------------------------------------------------------------------------ ready

    @Override
    public CompletionStage<?> ready(Control content) {
        Widgets widgets = w;
        ChecksTable table = checksTable;
        return UiStages.rounds(2)
                .thenAccept(v -> table.setChecks(checks(widgets)))
                .thenCompose(v -> UiStages.rounds(2))
                // the progress bars animate their fill up to their position (Windows, about 0.7 s) : until it is drawn
                .thenCompose(v -> UiStages.stable(widgets.progressGroup, 5000));
    }

    private static List<Check> checks(Widgets w) {
        List<Check> checks = new ArrayList<>();
        // Text
        checks.add(SwtChecks.expect("Text styles (getStyle) of six fields",
                "SINGLE, SINGLE PASSWORD, SINGLE READ_ONLY, SINGLE RIGHT, MULTI, MULTI WRAP", () -> Stream.of(w.single,
                        w.password, w.readOnly, w.right, w.multi, w.wrap).map(SwtInputsPage::textStyle)
                        .collect(Collectors.joining(", "))));
        checks.add(SwtChecks.expect("Text SEARCH style bits (SEARCH, ICON_*)", "true true true", () -> {
            int style = w.search.getStyle();
            return ((style & SWT.SEARCH) != 0) + " " + ((style & SWT.ICON_SEARCH) != 0) + " "
                    + ((style & SWT.ICON_CANCEL) != 0);
        }));
        checks.add(SwtChecks.expect("Text values (getText) of five fields",
                "Single line text | secret | Read only text | quarkus desktop | 1234.50", () -> Stream.of(w.single,
                        w.password, w.readOnly, w.search, w.right).map(Text::getText).collect(Collectors.joining(
                                " | "))));
        checks.add(SwtChecks.expect("Text messages (setMessage) and empty texts", "Shown while empty '' / Search ''",
                () -> w.message.getMessage() + " '" + w.message.getText() + "' / "
                        + w.searchMessage.getMessage() + " '" + w.searchMessage.getText() + "'"));
        checks.add(SwtChecks.expect("Text getEditable : single, read only, disabled", "true false true",
                () -> w.single.getEditable() + " " + w.readOnly.getEditable() + " " + w.disabled.getEditable()));
        checks.add(SwtChecks.expect("Text isEnabled : single, disabled", "true false",
                () -> w.single.isEnabled() + " " + w.disabled.isEnabled()));
        checks.add(SwtChecks.info("Text PASSWORD echo char (getEchoChar)",
                () -> "U+" + String.format(Locale.ROOT, "%04X", (int) w.password.getEchoChar())));
        checks.add(SwtChecks.expect("Text setSelection(0, 6) / getSelectionText", "Single [0, 6], then ''",
                () -> {
                    w.single.setSelection(0, 6);
                    String selected = w.single.getSelectionText() + " [" + w.single.getSelection().x + ", "
                            + w.single.getSelection().y + "]";
                    w.single.setSelection(0);
                    return selected + ", then '" + w.single.getSelectionText() + "'";
                }));
        checks.add(SwtChecks.expect("Text append and insert, then restored", "Single line text, appended + inserted",
                () -> {
                    String original = w.single.getText();
                    w.single.append(", appended");
                    w.single.setSelection(w.single.getCharCount());
                    w.single.insert(" + inserted");
                    String changed = w.single.getText();
                    w.single.setText(original);
                    w.single.setSelection(0);
                    return changed;
                }));
        checks.add(SwtChecks.expect("Text setTextLimit(20) / getTextLimit", 20, () -> {
            w.right.setTextLimit(20);
            return w.right.getTextLimit();
        }));
        checks.add(SwtChecks.expect("Text MULTI : getLineCount, line delimiter length", "6, " + Text.DELIMITER.length(),
                () -> w.multi.getLineCount() + ", " + w.multi.getLineDelimiter().length()));
        // macOS : Text.DELIMITER is "\r", but the NSTextView keeps the "\n" of setText and getText returns the text as
        // stored (no conversion either way, Text.setText / Text.getText) : the text comes back unchanged, a Cocoa SWT
        // bug against the Javadoc of Text.DELIMITER ("when text is queried ... delimited using this delimiter")
        checks.add(SwtChecks.expect("Text MULTI : getText with Text.DELIMITER", true,
                () -> w.multi.getText().equals(SwtMode.isMac() ? MULTI : MULTI.replace("\n", Text.DELIMITER))));
        checks.add(SwtChecks.expect("Text WRAP : text unchanged, lines wrapped", "true true", () -> {
            String text = w.wrap.getText();
            // getLineCount counts the wrapped lines on Windows (EM_GETLINECOUNT) ; on Cocoa the paragraphs of the
            // NSTextStorage, 1 for this text without a line break : there the wrap shows in computeSize at the width
            // of the control
            boolean wrapped = SwtMode.isMac()
                    ? w.wrap.getLineCount() == 1 && w.wrap.computeSize(AREA_WIDTH, SWT.DEFAULT).y
                            > w.wrap.computeSize(SWT.DEFAULT, SWT.DEFAULT).y
                    : w.wrap.getLineCount() > 1;
            return text.equals(WRAP) + " " + wrapped;
        }));
        // Spinner
        checks.add(SwtChecks.expect("Spinner selection min max digits increment page",
                "1234 -500 1500 2 25 100 / 35 0 100 0 5 20", () -> spinner(w.decimal) + " / " + spinner(w.integer)));
        checks.add(SwtChecks.info("Spinner getText (decimal separator of the locale)",
                () -> w.decimal.getText() + " / " + w.integer.getText()));
        checks.add(SwtChecks.expect("Spinner WRAP, READ_ONLY styles / disabled isEnabled",
                "true true / false", () -> ((w.integer.getStyle() & SWT.WRAP) != 0) + " "
                        + ((w.readOnlySpinner.getStyle() & SWT.READ_ONLY) != 0) + " / "
                        + w.disabledSpinner.isEnabled()));
        // documented : a selection out of range is adjusted to the range
        checks.add(SwtChecks.expect("Spinner setSelection(99999 / -99999) is adjusted", "1500 -500",
                () -> {
                    w.decimal.setSelection(99999);
                    int high = w.decimal.getSelection();
                    w.decimal.setSelection(-99999);
                    int low = w.decimal.getSelection();
                    w.decimal.setSelection(1234);
                    return high + " " + low;
                }));
        // Combo
        checks.add(SwtChecks.expect("Combo items : drop down, read only, simple",
                "Red Green Blue Cyan Magenta / Small Medium Large / Mercury Venus Earth Mars Jupiter Saturn",
                () -> Stream.of(w.dropDown, w.readOnlyCombo, w.simple).map(c -> String.join(" ", c.getItems()))
                        .collect(Collectors.joining(" / "))));
        checks.add(SwtChecks.expect("Combo selections (getSelectionIndex getText)", "2 Blue / 1 Medium / 2 Earth / 0"
                + " Small", () -> Stream.of(w.dropDown, w.readOnlyCombo, w.simple, w.disabledCombo)
                        .map(c -> c.getSelectionIndex() + " " + c.getText()).collect(Collectors.joining(" / "))));
        checks.add(SwtChecks.expect("Combo styles : DROP_DOWN, READ_ONLY, SIMPLE", "true true true",
                () -> ((w.dropDown.getStyle() & SWT.DROP_DOWN) != 0) + " " + ((w.readOnlyCombo.getStyle()
                        & SWT.READ_ONLY) != 0) + " " + ((w.simple.getStyle() & SWT.SIMPLE) != 0)));
        checks.add(SwtChecks.expect("Combo add(Black, 0), indexOf(Cyan), remove(0)",
                "Black 6 4, then 5 3 Blue", () -> {
                    Combo combo = w.dropDown;
                    combo.add("Black", 0);
                    String added = combo.getItem(0) + " " + combo.getItemCount() + " " + combo.indexOf("Cyan");
                    combo.remove(0);
                    combo.select(2);
                    return added + ", then " + combo.getItemCount() + " " + combo.indexOf("Cyan") + " "
                            + combo.getText();
                }));
        // Cocoa : setText leaves item 2 selected in the list of the NSComboBox (getSelectionIndex stays 2), and
        // select returns early for the selected index : the text field keeps Teal (Combo.setText, Combo.select)
        checks.add(SwtChecks.expect("Combo DROP_DOWN setText(Teal), then select(2)",
                SwtMode.pick("Teal, then Teal 2", "Teal, then Blue 2", "Teal, then Blue 2"), () -> {
                    Combo combo = w.dropDown;
                    combo.setText("Teal");
                    String custom = combo.getText();
                    combo.select(2);
                    return custom + ", then " + combo.getText() + " " + combo.getSelectionIndex();
                }));
        // a hint, "not supported on platforms that do not have this concept" : the READ_ONLY combo of Cocoa is an
        // NSPopUpButton whose menu shows every item (setVisibleItemCount ignored, getVisibleItemCount = getItemCount)
        checks.add(SwtChecks.expect("Combo setVisibleItemCount(4) / getVisibleItemCount",
                SwtMode.pick(SIZES.length, 4, 4), () -> {
                    w.readOnlyCombo.setVisibleItemCount(4);
                    return w.readOnlyCombo.getVisibleItemCount();
                }));
        checks.add(SwtChecks.expect("Combo READ_ONLY setText(Huge) is ignored", "Medium 1",
                () -> {
                    w.readOnlyCombo.setText("Huge");
                    return w.readOnlyCombo.getText() + " " + w.readOnlyCombo.getSelectionIndex();
                }));
        // DateTime
        checks.add(SwtChecks.expect("DateTime dates : short, medium, long, calendar",
                "2024-02-29 2024-02-29 2024-02-29 2024-02-29", () -> Stream.of(w.shortDate, w.mediumDate, w.longDate,
                        w.calendar).map(SwtInputsPage::date).collect(Collectors.joining(" "))));
        checks.add(SwtChecks.expect("DateTime times : short, medium", "10:30 10:30:00",
                () -> String.format(Locale.ROOT, "%02d:%02d", w.shortTime.getHours(), w.shortTime.getMinutes()) + " "
                        + time(w.mediumTime)));
        checks.add(SwtChecks.expect("DateTime styles of the six pickers",
                "DATE SHORT, DATE MEDIUM DROP_DOWN, DATE LONG, TIME SHORT, TIME MEDIUM, CALENDAR",
                () -> Stream.of(w.shortDate, w.mediumDate, w.longDate, w.shortTime, w.mediumTime, w.calendar)
                        .map(SwtInputsPage::dateTimeStyle).collect(Collectors.joining(", "))));
        // documented : a day that is not valid for the month and year is ignored
        checks.add(SwtChecks.expect("DateTime setDay(30) in February 2024 is ignored", "2024-02-29", () -> {
            w.calendar.setDay(30);
            return date(w.calendar);
        }));
        checks.add(SwtChecks.onlyOn(SwtMode.Os.WINDOWS, SwtChecks.expect(
                "DateTime setDate(2023, 1, 29) (no leap day) rejected", "2024-02-29", () -> {
                    w.mediumDate.setDate(2023, 1, 29);
                    String result = date(w.mediumDate);
                    w.mediumDate.setDate(YEAR, MONTH, DAY);
                    return result;
                })));
        checks.add(SwtChecks.expect("DateTime setTime(23, 59, 59) round trip", "23:59:59, then 10:30:00",
                () -> {
                    w.mediumTime.setTime(23, 59, 59);
                    String changed = time(w.mediumTime);
                    w.mediumTime.setTime(HOURS, MINUTES, SECONDS);
                    return changed + ", then " + time(w.mediumTime);
                }));
        // Scale and Slider
        checks.add(SwtChecks.expect("Scale min max increment page selection",
                "0 100 5 20 40 / 0 10 1 2 7", () -> scale(w.scale) + " / " + scale(w.verticalScale)));
        checks.add(SwtChecks.expect("Slider min max thumb increment page selection",
                "0 110 10 1 10 30 / 0 100 25 1 25 20 / 0 110 10 1 10 60", () -> slider(w.slider) + " / "
                        + slider(w.verticalSlider) + " / " + slider(w.disabledSlider)));
        checks.add(SwtChecks.expect("orientations : scale, slider, bar, vertical ones",
                "H H H V V V", () -> Stream.of(w.scale, w.slider, w.normal, w.verticalScale, w.verticalSlider,
                        w.vertical).map(c -> (c.getStyle() & SWT.VERTICAL) != 0 ? "V" : (c.getStyle()
                                & SWT.HORIZONTAL) != 0 ? "H" : "?").collect(Collectors.joining(" "))));
        checks.add(SwtChecks.expect("Slider setValues(50, 0, 200, 20, 2, 25) round trip",
                "0 200 20 2 25 50, then 0 110 10 1 10 30", () -> {
                    w.slider.setValues(50, 0, 200, 20, 2, 25);
                    String changed = slider(w.slider);
                    w.slider.setValues(30, 0, 110, 10, 1, 10);
                    return changed + ", then " + slider(w.slider);
                }));
        checks.add(SwtChecks.onlyOn(SwtMode.Os.WINDOWS, SwtChecks.expect(
                "Slider setSelection(200) clamped to max - thumb", 100, () -> {
                    w.slider.setSelection(200);
                    int value = w.slider.getSelection();
                    w.slider.setSelection(30);
                    return value;
                })));
        checks.add(SwtChecks.onlyOn(SwtMode.Os.WINDOWS, SwtChecks.expect(
                "Scale setSelection(150) is clamped to the maximum", 100, () -> {
                    w.scale.setSelection(150);
                    int value = w.scale.getSelection();
                    w.scale.setSelection(40);
                    return value;
                })));
        // ProgressBar
        checks.add(SwtChecks.expect("ProgressBar minimum maximum selection",
                "0 100 100 / 0 100 45 / 0 100 80 / 0 100 70", () -> Stream.of(w.normal, w.paused, w.error, w.vertical)
                        .map(b -> b.getMinimum() + " " + b.getMaximum() + " " + b.getSelection())
                        .collect(Collectors.joining(" / "))));
        // a hint, "not supported on platforms that do not have this concept"
        checks.add(SwtChecks.onlyOn(SwtMode.Os.WINDOWS, SwtChecks.expect(
                "ProgressBar getState of the four bars", "NORMAL PAUSED ERROR PAUSED",
                () -> Stream.of(w.normal, w.paused, w.error, w.vertical).map(b -> state(b.getState()))
                        .collect(Collectors.joining(" ")))));
        // computed sizes : relations that hold on every platform, the values themselves for the comparison
        checks.add(SwtChecks.expect("computeSize : a MULTI text with more lines is taller", true,
                () -> w.multi.computeSize(AREA_WIDTH, SWT.DEFAULT).y
                        > w.single.computeSize(AREA_WIDTH, SWT.DEFAULT).y));
        // SWT.SIMPLE is a hint : Cocoa creates the NSComboBox of DROP_DOWN and limits the height of an editable combo
        // to the height of its text (Combo.computeSize, Combo.setBounds) : the heightHint 96 is ignored there, the
        // SIMPLE combo is exactly as tall as the DROP_DOWN combo
        checks.add(SwtChecks.expect("getSize : SIMPLE combo taller than DROP_DOWN", true,
                () -> SwtMode.isMac() ? w.simple.getSize().y == w.dropDown.getSize().y
                        : w.simple.getSize().y > w.dropDown.getSize().y));
        checks.add(SwtChecks.expect("computeSize : calendar larger than date field", "true true", () -> {
            Point calendar = w.calendar.computeSize(SWT.DEFAULT, SWT.DEFAULT);
            Point date = w.mediumDate.computeSize(SWT.DEFAULT, SWT.DEFAULT);
            return (calendar.x > date.x) + " " + (calendar.y > date.y);
        }));
        checks.add(SwtChecks.info("computeSize : text, spinner, combo x 2",
                () -> join(Stream.of(w.single, w.decimal, w.dropDown, w.readOnlyCombo),
                        c -> SwtChecks.size(c.computeSize(SWT.DEFAULT, SWT.DEFAULT)))));
        checks.add(SwtChecks.info("computeSize : dates x 3, time, calendar",
                () -> join(Stream.of(w.shortDate, w.mediumDate, w.longDate, w.mediumTime, w.calendar),
                        c -> SwtChecks.size(c.computeSize(SWT.DEFAULT, SWT.DEFAULT)))));
        checks.add(SwtChecks.info("computeSize : scale, slider, progress bars x 2",
                () -> join(Stream.of(w.scale, w.slider, w.normal, w.vertical),
                        c -> SwtChecks.size(c.computeSize(SWT.DEFAULT, SWT.DEFAULT)))));
        checks.add(SwtChecks.info("getLineHeight, getTextHeight, getItemHeight",
                () -> w.multi.getLineHeight() + " " + w.dropDown.getTextHeight() + " " + w.dropDown.getItemHeight()));
        return checks;
    }

    private static String textStyle(Text text) {
        int style = text.getStyle();
        StringBuilder sb = new StringBuilder();
        sb.append((style & SWT.MULTI) != 0 ? "MULTI" : (style & SWT.SINGLE) != 0 ? "SINGLE" : "NONE");
        sb.append((style & SWT.PASSWORD) != 0 ? " PASSWORD" : "");
        sb.append((style & SWT.READ_ONLY) != 0 ? " READ_ONLY" : "");
        sb.append((style & SWT.WRAP) != 0 ? " WRAP" : "");
        sb.append((style & SWT.RIGHT) != 0 ? " RIGHT" : "");
        return sb.toString();
    }

    private static String spinner(Spinner spinner) {
        return spinner.getSelection() + " " + spinner.getMinimum() + " " + spinner.getMaximum() + " "
                + spinner.getDigits() + " " + spinner.getIncrement() + " " + spinner.getPageIncrement();
    }

    private static String scale(Scale scale) {
        return scale.getMinimum() + " " + scale.getMaximum() + " " + scale.getIncrement() + " "
                + scale.getPageIncrement() + " " + scale.getSelection();
    }

    private static String slider(Slider slider) {
        return slider.getMinimum() + " " + slider.getMaximum() + " " + slider.getThumb() + " "
                + slider.getIncrement() + " " + slider.getPageIncrement() + " " + slider.getSelection();
    }

    private static String dateTimeStyle(DateTime dateTime) {
        int style = dateTime.getStyle();
        StringBuilder sb = new StringBuilder();
        sb.append((style & SWT.DATE) != 0 ? "DATE" : (style & SWT.TIME) != 0 ? "TIME" : (style & SWT.CALENDAR) != 0
                ? "CALENDAR" : "NONE");
        if ((style & SWT.CALENDAR) == 0) {
            sb.append((style & SWT.SHORT) != 0 ? " SHORT" : (style & SWT.LONG) != 0 ? " LONG" : (style & SWT.MEDIUM)
                    != 0 ? " MEDIUM" : "");
        }
        sb.append((style & SWT.DROP_DOWN) != 0 ? " DROP_DOWN" : "");
        return sb.toString();
    }

    private static String date(DateTime date) {
        return String.format(Locale.ROOT, "%04d-%02d-%02d", date.getYear(), date.getMonth() + 1, date.getDay());
    }

    private static String time(DateTime time) {
        return String.format(Locale.ROOT, "%02d:%02d:%02d", time.getHours(), time.getMinutes(), time.getSeconds());
    }

    private static String state(int state) {
        return switch (state) {
            case SWT.NORMAL -> "NORMAL";
            case SWT.PAUSED -> "PAUSED";
            case SWT.ERROR -> "ERROR";
            default -> String.valueOf(state);
        };
    }

    private static <C extends Control> String join(Stream<C> controls, Function<C, String> value) {
        return controls.map(value).collect(Collectors.joining(" "));
    }

    @Override
    public void dispose(Control content) {
        w = null;
        checksTable = null;
    }
}
