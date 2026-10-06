package io.quarkiverse.desktop.swt.it;

import static io.quarkiverse.desktop.swt.it.SwtChecks.require;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.Bullet;
import org.eclipse.swt.custom.CBanner;
import org.eclipse.swt.custom.CCombo;
import org.eclipse.swt.custom.CLabel;
import org.eclipse.swt.custom.CTabFolder;
import org.eclipse.swt.custom.CTabItem;
import org.eclipse.swt.custom.ST;
import org.eclipse.swt.custom.SashForm;
import org.eclipse.swt.custom.ScrolledComposite;
import org.eclipse.swt.custom.StackLayout;
import org.eclipse.swt.custom.StyleRange;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.custom.ViewForm;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.FontData;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.GlyphMetrics;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.layout.FormAttachment;
import org.eclipse.swt.layout.FormData;
import org.eclipse.swt.layout.FormLayout;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.layout.RowData;
import org.eclipse.swt.layout.RowLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.CoolBar;
import org.eclipse.swt.widgets.CoolItem;
import org.eclipse.swt.widgets.DateTime;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.ExpandBar;
import org.eclipse.swt.widgets.ExpandItem;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Link;
import org.eclipse.swt.widgets.ProgressBar;
import org.eclipse.swt.widgets.Sash;
import org.eclipse.swt.widgets.Scale;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Slider;
import org.eclipse.swt.widgets.Spinner;
import org.eclipse.swt.widgets.TabFolder;
import org.eclipse.swt.widgets.TabItem;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.Text;
import org.eclipse.swt.widgets.ToolBar;
import org.eclipse.swt.widgets.ToolItem;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.swt.widgets.TreeColumn;
import org.eclipse.swt.widgets.TreeItem;

/**
 * Checks of the widgets : the basic widgets, the containers, the custom widgets of {@code org.eclipse.swt.custom}, the
 * tables and trees, and the layouts. The widgets are shown in a shell, then rendered into {@code <check>-<mode>.png}
 * once still ({@link SwtChecks#show(Shell, Control, Control)}).
 * <p>
 * The renderings are deterministic : fixed values (dates, selections, scroll positions), no animation left running,
 * the focus on a canvas that does not paint it, and no control under the mouse pointer.
 */
final class SwtWidgetChecks {

    private static final RGB ACCENT_LIGHT = new RGB(204, 234, 244);

    private final SwtChecks checks;
    private final Display display;

    SwtWidgetChecks(SwtChecks checks) {
        this.checks = checks;
        this.display = checks.display();
    }

    // ------------------------------------------------------------------------------------------------------ widgets

    /**
     * The basic widgets : buttons of every kind, labels, links, texts, combos, lists, ranges, dates and a canvas.
     */
    Object widgets() throws Exception {
        try (SwtChecks.Resources resources = new SwtChecks.Resources()) {
            Image image = resources.add(new Image(display, SwtChecks.icon(SwtPalette.ACCENT, 0)));
            Shell shell = checks.shell("Widgets");
            try {
                Composite content = new Composite(shell, SWT.NONE);
                content.setLayout(new GridLayout(3, false));

                Group buttons = new Group(content, SWT.NONE);
                buttons.setText("Buttons");
                buttons.setLayout(new GridLayout(2, false));
                buttons.setLayoutData(new GridData(SWT.FILL, SWT.FILL, false, false));
                button(buttons, SWT.PUSH, "Push", false);
                button(buttons, SWT.PUSH | SWT.FLAT, "Flat", false);
                button(buttons, SWT.CHECK, "Check", true);
                button(buttons, SWT.CHECK, "Unchecked", false);
                button(buttons, SWT.CHECK, "Grayed", true).setGrayed(true);
                button(buttons, SWT.TOGGLE, "Toggle", true);
                button(buttons, SWT.RADIO, "Radio 1", true);
                button(buttons, SWT.RADIO, "Radio 2", false);
                button(buttons, SWT.PUSH, "Image", false).setImage(image);
                button(buttons, SWT.PUSH, "Disabled", false).setEnabled(false);
                Composite arrows = new Composite(buttons, SWT.NONE);
                arrows.setLayout(new RowLayout(SWT.HORIZONTAL));
                arrows.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, false, false, 2, 1));
                for (int direction : new int[] { SWT.UP, SWT.DOWN, SWT.LEFT, SWT.RIGHT }) {
                    new Button(arrows, SWT.ARROW | direction);
                }

                Composite texts = column(content);
                new Label(texts, SWT.NONE).setText("Label");
                Label separator = new Label(texts, SWT.SEPARATOR | SWT.HORIZONTAL);
                separator.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
                Link link = new Link(texts, SWT.NONE);
                link.setText("A <a href=\"https://quarkus.io\">link</a> in a text");
                text(texts, SWT.SINGLE | SWT.BORDER, "Single line");
                Text multi = text(texts, SWT.MULTI | SWT.BORDER | SWT.WRAP | SWT.V_SCROLL,
                        "First line\nSecond line\nThird line");
                ((GridData) multi.getLayoutData()).heightHint = 48;
                text(texts, SWT.SINGLE | SWT.BORDER | SWT.PASSWORD, "secret");
                text(texts, SWT.SINGLE | SWT.BORDER | SWT.READ_ONLY, "Read only");
                text(texts, SWT.SINGLE | SWT.BORDER, "").setMessage("Message");

                Composite choices = column(content);
                String[] items = { "Alpha", "Beta", "Gamma", "Delta", "Epsilon", "Zeta" };
                Combo dropDown = new Combo(choices, SWT.DROP_DOWN);
                dropDown.setItems(items);
                dropDown.select(1);
                Combo readOnly = new Combo(choices, SWT.READ_ONLY);
                readOnly.setItems(items);
                readOnly.select(0);
                Combo simple = new Combo(choices, SWT.SIMPLE);
                simple.setItems(items);
                simple.select(2);
                simple.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, false, 1, 1));
                ((GridData) simple.getLayoutData()).heightHint = 64;
                org.eclipse.swt.widgets.List list = new org.eclipse.swt.widgets.List(choices,
                        SWT.BORDER | SWT.SINGLE | SWT.V_SCROLL);
                list.setItems(items);
                list.select(2);
                GridData listData = new GridData(SWT.FILL, SWT.FILL, true, false);
                listData.heightHint = 64;
                list.setLayoutData(listData);
                require(dropDown.getText().equals("Beta"), "Combo.getText " + dropDown.getText());
                require(list.getSelectionIndex() == 2, "List.getSelectionIndex " + list.getSelectionIndex());

                Composite ranges = column(content);
                Spinner spinner = new Spinner(ranges, SWT.BORDER);
                spinner.setValues(42, 0, 100, 0, 1, 10);
                Spinner decimals = new Spinner(ranges, SWT.BORDER);
                decimals.setValues(1234, 0, 10_000, 2, 1, 100);
                Scale scale = new Scale(ranges, SWT.HORIZONTAL);
                scale.setMaximum(100);
                scale.setPageIncrement(10);
                scale.setSelection(30);
                Slider slider = new Slider(ranges, SWT.HORIZONTAL);
                slider.setValues(50, 0, 110, 10, 1, 10);
                // determinate bars : the glow of the bar in the normal state of Windows is animated
                ProgressBar paused = new ProgressBar(ranges, SWT.HORIZONTAL | SWT.SMOOTH);
                paused.setMaximum(100);
                paused.setSelection(60);
                paused.setState(SWT.PAUSED);
                ProgressBar error = new ProgressBar(ranges, SWT.HORIZONTAL);
                error.setMaximum(100);
                error.setSelection(30);
                error.setState(SWT.ERROR);
                for (Control control : ranges.getChildren()) {
                    control.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
                }
                require(spinner.getSelection() == 42 && decimals.getDigits() == 2, "Spinner " + spinner.getSelection());
                require(slider.getThumb() == 10 && scale.getSelection() == 30, "Slider/Scale");

                Composite dates = column(content);
                DateTime date = new DateTime(dates, SWT.DATE | SWT.MEDIUM | SWT.BORDER);
                date.setDate(2024, 1, 29);
                DateTime time = new DateTime(dates, SWT.TIME | SWT.MEDIUM | SWT.BORDER);
                time.setTime(13, 45, 30);
                DateTime dropDownDate = new DateTime(dates, SWT.DATE | SWT.SHORT | SWT.DROP_DOWN | SWT.BORDER);
                dropDownDate.setDate(2024, 1, 29);
                DateTime calendar = new DateTime(content, SWT.CALENDAR | SWT.BORDER);
                calendar.setDate(2024, 1, 29);
                require(calendar.getYear() == 2024 && calendar.getMonth() == 1 && calendar.getDay() == 29,
                        "calendar " + calendar.getYear() + "-" + calendar.getMonth() + "-" + calendar.getDay());
                require(time.getHours() == 13 && time.getMinutes() == 45 && time.getSeconds() == 30,
                        "time " + time.getHours() + ":" + time.getMinutes() + ":" + time.getSeconds());

                int[] painted = new int[1];
                Canvas canvas = new Canvas(content, SWT.BORDER);
                GridData canvasData = new GridData(SWT.FILL, SWT.FILL, false, false);
                canvasData.widthHint = 180;
                canvasData.heightHint = 120;
                canvas.setLayoutData(canvasData);
                canvas.addPaintListener(event -> {
                    painted[0]++;
                    paintCanvas(event.gc, canvas.getClientArea());
                });

                checks.place(shell);
                ImageData rendered = checks.show(shell, canvas, content);
                checks.writeImage("widgets", rendered);
                require(painted[0] > 0, "the canvas was not painted");
                return "size=" + rendered.width + "x" + rendered.height + " controls=" + count(content) + " painted="
                        + (painted[0] > 0);
            } finally {
                shell.dispose();
            }
        }
    }

    private static Button button(Composite parent, int style, String text, boolean selected) {
        Button button = new Button(parent, style);
        button.setText(text);
        button.setSelection(selected);
        return button;
    }

    private static Text text(Composite parent, int style, String value) {
        Text text = new Text(parent, style);
        text.setText(value);
        GridData data = new GridData(SWT.FILL, SWT.CENTER, true, false);
        data.widthHint = 150;
        text.setLayoutData(data);
        return text;
    }

    private static Composite column(Composite parent) {
        Composite column = new Composite(parent, SWT.NONE);
        GridLayout layout = new GridLayout(1, false);
        layout.marginWidth = 0;
        column.setLayout(layout);
        column.setLayoutData(new GridData(SWT.FILL, SWT.BEGINNING, false, false));
        return column;
    }

    private void paintCanvas(GC gc, Rectangle area) {
        gc.setBackground(display.getSystemColor(SWT.COLOR_WHITE));
        gc.fillRectangle(area);
        gc.setBackground(new Color(SwtPalette.ACCENT));
        gc.fillOval(10, 30, 70, 50);
        gc.setBackground(new Color(240, 160, 40));
        gc.fillPolygon(new int[] { 100, 80, 140, 20, 170, 80 });
        gc.setForeground(display.getSystemColor(SWT.COLOR_DARK_BLUE));
        gc.setLineWidth(3);
        gc.drawRectangle(5, 5, area.width - 11, area.height - 11);
        gc.setLineWidth(1);
        gc.setForeground(display.getSystemColor(SWT.COLOR_BLACK));
        gc.drawString("Canvas", 12, 10, true);
        gc.drawLine(10, 100, 170, 90);
    }

    private static int count(Composite parent) {
        int count = 0;
        for (Control child : parent.getChildren()) {
            count++;
            if (child instanceof Composite composite) {
                count += count(composite);
            }
        }
        return count;
    }

    // --------------------------------------------------------------------------------------------------- containers

    /**
     * The containers : tool bars, cool bars, tab folders, expand bars and sashes.
     */
    Object containers() throws Exception {
        try (SwtChecks.Resources resources = new SwtChecks.Resources()) {
            Image[] images = images(resources);
            Shell shell = checks.shell("Containers");
            try {
                Composite content = new Composite(shell, SWT.NONE);
                content.setLayout(new GridLayout(2, false));

                ToolBar toolBar = new ToolBar(content, SWT.FLAT | SWT.RIGHT);
                toolBar.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));
                toolItem(toolBar, SWT.PUSH, "New", images[0]);
                toolItem(toolBar, SWT.CHECK, "Bold", images[1]).setSelection(true);
                new ToolItem(toolBar, SWT.SEPARATOR);
                toolItem(toolBar, SWT.RADIO, "Left", null).setSelection(true);
                toolItem(toolBar, SWT.RADIO, "Right", null);
                new ToolItem(toolBar, SWT.SEPARATOR);
                toolItem(toolBar, SWT.DROP_DOWN, "Menu", images[2]);
                toolItem(toolBar, SWT.PUSH, "Off", images[0]).setEnabled(false);
                ToolItem holder = new ToolItem(toolBar, SWT.SEPARATOR);
                Combo combo = new Combo(toolBar, SWT.READ_ONLY);
                combo.setItems("Small", "Medium", "Large");
                combo.select(1);
                combo.pack();
                holder.setWidth(combo.getSize().x);
                holder.setControl(combo);

                CoolBar coolBar = new CoolBar(content, SWT.FLAT);
                coolBar.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));
                for (int i = 0; i < 2; i++) {
                    CoolItem item = new CoolItem(coolBar, SWT.NONE);
                    ToolBar tools = new ToolBar(coolBar, SWT.FLAT);
                    for (int j = 0; j < 3; j++) {
                        toolItem(tools, SWT.PUSH, null, images[(i + j) % images.length]);
                    }
                    tools.pack();
                    Point size = tools.getSize();
                    Point itemSize = item.computeSize(size.x, size.y);
                    // sized first : the control takes the size of the item
                    item.setPreferredSize(itemSize);
                    item.setSize(itemSize);
                    item.setControl(tools);
                }

                TabFolder tabs = new TabFolder(content, SWT.TOP);
                tabs.setLayoutData(fixed(240, 110));
                for (int i = 0; i < 3; i++) {
                    TabItem item = new TabItem(tabs, SWT.NONE);
                    item.setText("Tab " + (i + 1));
                    item.setImage(images[i]);
                    Label page = new Label(tabs, SWT.WRAP);
                    page.setText("The page of the tab " + (i + 1));
                    item.setControl(page);
                }
                tabs.setSelection(1);
                TabFolder bottomTabs = new TabFolder(content, SWT.BOTTOM);
                bottomTabs.setLayoutData(fixed(200, 110));
                for (int i = 0; i < 2; i++) {
                    TabItem item = new TabItem(bottomTabs, SWT.NONE);
                    item.setText("Bottom " + (i + 1));
                    Button page = new Button(bottomTabs, SWT.CHECK);
                    page.setText("A check box");
                    item.setControl(page);
                }

                ExpandBar expandBar = new ExpandBar(content, SWT.V_SCROLL);
                expandBar.setLayoutData(fixed(240, 190));
                // sized before its items : GTK warns when they are laid out in an empty bar
                expandBar.setSize(240, 190);
                expandBar.setSpacing(4);
                Composite expanded = new Composite(expandBar, SWT.NONE);
                expanded.setLayout(new GridLayout(2, false));
                new Label(expanded, SWT.NONE).setText("Name");
                new Text(expanded, SWT.BORDER).setText("Value");
                button(expanded, SWT.CHECK, "Enabled", true);
                ExpandItem first = new ExpandItem(expandBar, SWT.NONE);
                first.setText("Expanded");
                first.setImage(images[0]);
                first.setHeight(expanded.computeSize(SWT.DEFAULT, SWT.DEFAULT).y);
                first.setControl(expanded);
                first.setExpanded(true);
                Label collapsedLabel = new Label(expandBar, SWT.NONE);
                collapsedLabel.setText("Collapsed content");
                ExpandItem second = new ExpandItem(expandBar, SWT.NONE);
                second.setText("Collapsed");
                second.setImage(images[1]);
                second.setHeight(collapsedLabel.computeSize(SWT.DEFAULT, SWT.DEFAULT).y);
                second.setControl(collapsedLabel);

                Composite sashes = new Composite(content, SWT.BORDER);
                sashes.setLayoutData(fixed(200, 190));
                sashes.setLayout(new FormLayout());
                Label left = new Label(sashes, SWT.CENTER);
                left.setText("Left");
                Sash vertical = new Sash(sashes, SWT.VERTICAL);
                Label right = new Label(sashes, SWT.CENTER);
                right.setText("Right");
                Sash horizontal = new Sash(sashes, SWT.HORIZONTAL);
                Label bottom = new Label(sashes, SWT.CENTER);
                bottom.setText("Bottom");
                left.setLayoutData(form(new FormAttachment(0), new FormAttachment(0), new FormAttachment(vertical),
                        new FormAttachment(horizontal)));
                vertical.setLayoutData(form(new FormAttachment(40), new FormAttachment(0), null,
                        new FormAttachment(horizontal)));
                right.setLayoutData(form(new FormAttachment(vertical), new FormAttachment(0), new FormAttachment(100),
                        new FormAttachment(horizontal)));
                horizontal.setLayoutData(form(new FormAttachment(0), new FormAttachment(60), new FormAttachment(100),
                        null));
                bottom.setLayoutData(form(new FormAttachment(0), new FormAttachment(horizontal),
                        new FormAttachment(100), new FormAttachment(100)));
                vertical.setBackground(display.getSystemColor(SWT.COLOR_WIDGET_NORMAL_SHADOW));
                horizontal.setBackground(display.getSystemColor(SWT.COLOR_WIDGET_NORMAL_SHADOW));

                Canvas focus = new Canvas(content, SWT.NONE);
                focus.setLayoutData(fixed(1, 1));

                checks.place(shell);
                ImageData rendered = checks.show(shell, focus, content);
                checks.writeImage("containers", rendered);
                require(tabs.getSelectionIndex() == 1, "TabFolder selection " + tabs.getSelectionIndex());
                require(first.getExpanded() && !second.getExpanded(), "ExpandItem states");
                require(toolBar.getItemCount() == 9, "tool items " + toolBar.getItemCount());
                require(coolBar.getItemCount() == 2, "cool items " + coolBar.getItemCount());
                require(vertical.getBounds().x > left.getBounds().x, "sash " + vertical.getBounds());
                return "size=" + rendered.width + "x" + rendered.height + " tools=" + toolBar.getItemCount()
                        + " coolItems=" + coolBar.getItemCount() + " tabs=" + tabs.getItemCount() + " expandItems="
                        + expandBar.getItemCount();
            } finally {
                shell.dispose();
            }
        }
    }

    private static ToolItem toolItem(ToolBar toolBar, int style, String text, Image image) {
        ToolItem item = new ToolItem(toolBar, style);
        if (text != null) {
            item.setText(text);
        }
        if (image != null) {
            item.setImage(image);
        }
        item.setToolTipText(text);
        return item;
    }

    private static GridData fixed(int width, int height) {
        GridData data = new GridData(SWT.FILL, SWT.FILL, false, false);
        data.widthHint = width;
        data.heightHint = height;
        return data;
    }

    private static FormData form(FormAttachment left, FormAttachment top, FormAttachment right, FormAttachment bottom) {
        FormData data = new FormData();
        data.left = left;
        data.top = top;
        data.right = right;
        data.bottom = bottom;
        return data;
    }

    private Image[] images(SwtChecks.Resources resources) {
        RGB[] colors = { SwtPalette.ACCENT, new RGB(220, 60, 50), new RGB(60, 170, 80) };
        Image[] images = new Image[colors.length];
        for (int i = 0; i < colors.length; i++) {
            images[i] = resources.add(new Image(display, SwtChecks.icon(colors[i], i)));
        }
        return images;
    }

    // ----------------------------------------------------------------------------------------------- custom widgets

    /**
     * The custom widgets of {@code org.eclipse.swt.custom} : styled text, tab folder, labels, combo, sash form,
     * scrolled composite, view form and banner.
     */
    Object customWidgets() throws Exception {
        try (SwtChecks.Resources resources = new SwtChecks.Resources()) {
            Image[] images = images(resources);
            Font baseFont = display.getSystemFont();
            FontData boldData = baseFont.getFontData()[0];
            Font bold = resources.add(new Font(display, boldData.getName(), boldData.getHeight(), SWT.BOLD));
            Shell shell = checks.shell("Custom widgets");
            try {
                Composite content = new Composite(shell, SWT.NONE);
                content.setLayout(new GridLayout(2, false));

                StyledText styled = styledText(content);
                styled.setLayoutData(fixed(300, 170));

                CTabFolder folder = new CTabFolder(content, SWT.BORDER | SWT.CLOSE);
                folder.setLayoutData(fixed(280, 170));
                folder.setSimple(false);
                folder.setUnselectedImageVisible(true);
                folder.setMinimizeVisible(true);
                folder.setMaximizeVisible(true);
                folder.setSelectionBackground(new Color[] { new Color(ACCENT_LIGHT), new Color(255, 255, 255) },
                        new int[] { 100 }, true);
                for (int i = 0; i < 3; i++) {
                    CTabItem item = new CTabItem(folder, SWT.NONE);
                    item.setText("Item " + (i + 1));
                    item.setImage(images[i]);
                    Label page = new Label(folder, SWT.WRAP);
                    page.setText("The page of the item " + (i + 1));
                    item.setControl(page);
                }
                folder.setSelection(1);

                CLabel clabel = new CLabel(content, SWT.SHADOW_IN);
                clabel.setText("A CLabel with an image");
                clabel.setImage(images[0]);
                clabel.setBackground(new Color[] { new Color(255, 255, 255), new Color(ACCENT_LIGHT) },
                        new int[] { 100 }, false);
                clabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, false, false));

                CCombo ccombo = new CCombo(content, SWT.BORDER | SWT.READ_ONLY);
                ccombo.setItems(new String[] { "Red", "Green", "Blue" });
                ccombo.select(1);
                ccombo.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, false, false));

                SashForm sashForm = new SashForm(content, SWT.HORIZONTAL | SWT.BORDER);
                sashForm.setLayoutData(fixed(300, 80));
                org.eclipse.swt.widgets.List sashList = new org.eclipse.swt.widgets.List(sashForm, SWT.NONE);
                sashList.setItems("One", "Two", "Three");
                Label sashLabel = new Label(sashForm, SWT.WRAP);
                sashLabel.setText("The second part of the sash form");
                sashForm.setSashWidth(4);
                sashForm.setWeights(30, 70);

                ScrolledComposite scrolled = new ScrolledComposite(content, SWT.H_SCROLL | SWT.V_SCROLL | SWT.BORDER);
                scrolled.setLayoutData(fixed(280, 80));
                Composite scrolledContent = new Composite(scrolled, SWT.NONE);
                scrolledContent.setLayout(new GridLayout(4, true));
                for (int i = 0; i < 16; i++) {
                    new Label(scrolledContent, SWT.NONE).setText("Scrolled cell " + (i + 1));
                }
                // larger than the scrolled composite whatever the fonts and the scale : the origin set below is in the
                // range of its scroll bars (the height of the labels alone may exceed its height by a few pixels only)
                Point preferred = scrolledContent.computeSize(SWT.DEFAULT, SWT.DEFAULT);
                scrolledContent.setSize(Math.max(preferred.x, 480), Math.max(preferred.y, 160));
                scrolled.setContent(scrolledContent);

                ViewForm viewForm = new ViewForm(content, SWT.BORDER | SWT.FLAT);
                viewForm.setLayoutData(fixed(300, 90));
                CLabel title = new CLabel(viewForm, SWT.NONE);
                title.setText("View form");
                title.setImage(images[1]);
                viewForm.setTopLeft(title);
                ToolBar viewTools = new ToolBar(viewForm, SWT.FLAT);
                toolItem(viewTools, SWT.PUSH, null, images[2]);
                viewForm.setTopRight(viewTools);
                Label body = new Label(viewForm, SWT.WRAP);
                body.setText("The content of the view form");
                viewForm.setContent(body);

                CBanner banner = new CBanner(content, SWT.NONE);
                banner.setLayoutData(fixed(280, 60));
                Label bannerLeft = new Label(banner, SWT.NONE);
                bannerLeft.setText("Banner left");
                bannerLeft.setFont(bold);
                banner.setLeft(bannerLeft);
                Label bannerRight = new Label(banner, SWT.NONE);
                bannerRight.setText("Right");
                banner.setRight(bannerRight);
                banner.setRightWidth(90);
                Label bannerBottom = new Label(banner, SWT.NONE);
                bannerBottom.setText("Bottom of the banner");
                banner.setBottom(bannerBottom);

                Canvas focus = new Canvas(content, SWT.NONE);
                focus.setLayoutData(fixed(1, 1));

                checks.place(shell);
                shell.layout(true, true);
                scrolled.setOrigin(20, 15);
                ImageData rendered = checks.show(shell, focus, content);
                checks.writeImage("custom-widgets", rendered);
                require(folder.getSelectionIndex() == 1, "CTabFolder selection " + folder.getSelectionIndex());
                require(ccombo.getText().equals("Green"), "CCombo " + ccombo.getText());
                require(styled.getLineBullet(2) != null && styled.getLineBullet(0) == null, "bullets");
                require(scrolled.getOrigin().equals(new Point(20, 15)), "origin " + scrolled.getOrigin());
                int[] weights = sashForm.getWeights();
                return "size=" + rendered.width + "x" + rendered.height + " styles=" + styled.getStyleRanges().length
                        + " lines=" + styled.getLineCount() + " weights=" + weights.length + " items="
                        + folder.getItemCount();
            } finally {
                shell.dispose();
            }
        }
    }

    private StyledText styledText(Composite parent) {
        StyledText styled = new StyledText(parent, SWT.BORDER | SWT.MULTI | SWT.WRAP);
        String text = String.join("\n", "Bold italic underline strikeout", "Foreground and background colors",
                "A bullet line", "Another bullet line", "A line with a background", "Double squiggle link border",
                "A right aligned line");
        styled.setText(text);
        styled.setMargins(4, 4, 4, 4);
        List<StyleRange> ranges = new ArrayList<>();
        ranges.add(new StyleRange(text.indexOf("Bold"), 4, null, null, SWT.BOLD));
        ranges.add(new StyleRange(text.indexOf("italic"), 6, null, null, SWT.ITALIC));
        StyleRange underline = new StyleRange(text.indexOf("underline"), 9, null, null);
        underline.underline = true;
        ranges.add(underline);
        StyleRange strikeout = new StyleRange(text.indexOf("strikeout"), 9, null, null);
        strikeout.strikeout = true;
        ranges.add(strikeout);
        ranges.add(new StyleRange(text.indexOf("Foreground"), 10, display.getSystemColor(SWT.COLOR_RED), null));
        ranges.add(new StyleRange(text.indexOf("background colors"), 10, null,
                display.getSystemColor(SWT.COLOR_YELLOW)));
        StyleRange doubleUnderline = new StyleRange(text.indexOf("Double"), 6, null, null);
        doubleUnderline.underline = true;
        doubleUnderline.underlineStyle = SWT.UNDERLINE_DOUBLE;
        ranges.add(doubleUnderline);
        StyleRange squiggle = new StyleRange(text.indexOf("squiggle"), 8, null, null);
        squiggle.underline = true;
        squiggle.underlineStyle = SWT.UNDERLINE_SQUIGGLE;
        squiggle.underlineColor = display.getSystemColor(SWT.COLOR_RED);
        ranges.add(squiggle);
        StyleRange link = new StyleRange(text.indexOf("link"), 4, null, null);
        link.underline = true;
        link.underlineStyle = SWT.UNDERLINE_LINK;
        ranges.add(link);
        StyleRange border = new StyleRange(text.indexOf("border"), 6, null, null);
        border.borderStyle = SWT.BORDER_SOLID;
        border.borderColor = display.getSystemColor(SWT.COLOR_DARK_GREEN);
        ranges.add(border);
        for (StyleRange range : ranges) {
            styled.setStyleRange(range);
        }
        StyleRange bulletStyle = new StyleRange();
        bulletStyle.metrics = new GlyphMetrics(0, 0, 24);
        bulletStyle.foreground = new Color(SwtPalette.ACCENT);
        styled.setLineBullet(2, 2, new Bullet(ST.BULLET_DOT, bulletStyle));
        styled.setLineBackground(4, 1, new Color(ACCENT_LIGHT));
        styled.setLineAlignment(6, 1, SWT.RIGHT);
        return styled;
    }

    // ---------------------------------------------------------------------------------------------- tables and trees

    /**
     * A table with columns, check boxes, images, a sort column and a selection, a virtual table, and a tree with
     * columns, expanded and checked items.
     */
    Object tableAndTree() throws Exception {
        try (SwtChecks.Resources resources = new SwtChecks.Resources()) {
            Image[] images = images(resources);
            FontData boldData = display.getSystemFont().getFontData()[0];
            Font bold = resources.add(new Font(display, boldData.getName(), boldData.getHeight(), SWT.BOLD));
            Shell shell = checks.shell("Tables and trees");
            try {
                Composite content = new Composite(shell, SWT.NONE);
                content.setLayout(new GridLayout(2, false));

                Table table = new Table(content, SWT.BORDER | SWT.CHECK | SWT.FULL_SELECTION | SWT.MULTI);
                table.setLayoutData(fixed(320, 200));
                // sized before its columns and items : GTK warns when they are laid out in an empty table
                table.setSize(320, 200);
                table.setHeaderVisible(true);
                table.setLinesVisible(true);
                String[] titles = { "Name", "Size", "Kind" };
                int[] widths = { 140, 60, 80 };
                TableColumn[] columns = new TableColumn[titles.length];
                for (int i = 0; i < titles.length; i++) {
                    columns[i] = new TableColumn(table, i == 1 ? SWT.RIGHT : SWT.LEFT);
                    columns[i].setText(titles[i]);
                    columns[i].setWidth(widths[i]);
                }
                String[] kinds = { "Folder", "Document", "Image" };
                for (int i = 0; i < 6; i++) {
                    TableItem item = new TableItem(table, SWT.NONE);
                    item.setText(new String[] { "Row " + (i + 1), String.valueOf((i + 1) * 128), kinds[i % 3] });
                    item.setImage(0, images[i % 3]);
                    item.setChecked(i % 2 == 0);
                }
                table.getItem(4).setGrayed(true);
                table.getItem(2).setForeground(display.getSystemColor(SWT.COLOR_DARK_RED));
                table.getItem(5).setBackground(new Color(ACCENT_LIGHT));
                table.getItem(5).setFont(bold);
                table.setSortColumn(columns[1]);
                table.setSortDirection(SWT.UP);
                table.setSelection(new int[] { 1, 3 });

                Table virtual = new Table(content, SWT.VIRTUAL | SWT.BORDER | SWT.FULL_SELECTION);
                virtual.setLayoutData(fixed(150, 200));
                int[] setData = new int[1];
                virtual.addListener(SWT.SetData, event -> {
                    setData[0]++;
                    ((TableItem) event.item).setText("Virtual " + event.index);
                });
                virtual.setItemCount(10_000);

                Tree tree = new Tree(content, SWT.BORDER | SWT.CHECK | SWT.FULL_SELECTION);
                tree.setLayoutData(new GridData(SWT.FILL, SWT.FILL, false, false, 2, 1));
                ((GridData) tree.getLayoutData()).heightHint = 340;
                tree.setHeaderVisible(true);
                tree.setLinesVisible(true);
                TreeColumn[] treeColumns = new TreeColumn[titles.length];
                for (int i = 0; i < titles.length; i++) {
                    treeColumns[i] = new TreeColumn(tree, i == 1 ? SWT.RIGHT : SWT.LEFT);
                    treeColumns[i].setText(titles[i]);
                    treeColumns[i].setWidth(widths[i] + (i == 0 ? 60 : 0));
                }
                TreeItem[] roots = new TreeItem[3];
                for (int r = 0; r < roots.length; r++) {
                    roots[r] = new TreeItem(tree, SWT.NONE);
                    roots[r].setText(new String[] { "Root " + (r + 1), "", kinds[0] });
                    roots[r].setImage(images[r]);
                    for (int c = 0; c < 3; c++) {
                        TreeItem child = new TreeItem(roots[r], SWT.NONE);
                        child.setText(new String[] { "Child " + (r + 1) + "." + (c + 1), String.valueOf(64 * (c + 1)),
                                kinds[1 + c % 2] });
                        child.setImage(images[(r + c + 1) % 3]);
                        child.setChecked((r + c) % 2 == 0);
                        if (c == 1) {
                            new TreeItem(child, SWT.NONE).setText(new String[] { "Leaf", "1", kinds[2] });
                        }
                    }
                }
                roots[0].setChecked(true);
                roots[0].setGrayed(true);
                roots[0].setExpanded(true);
                roots[0].getItem(1).setExpanded(true);
                roots[2].setExpanded(true);
                tree.setSortColumn(treeColumns[0]);
                tree.setSortDirection(SWT.DOWN);
                tree.setSelection(roots[0].getItem(2));

                Canvas focus = new Canvas(content, SWT.NONE);
                focus.setLayoutData(fixed(1, 1));

                checks.place(shell);
                // the selections scroll the items into view
                table.setTopIndex(0);
                tree.setTopItem(roots[0]);
                ImageData rendered = checks.show(shell, focus, content);
                checks.writeImage("table-tree", rendered);

                require(table.getItemCount() == 6 && table.getColumnCount() == 3, "table items");
                require(table.getSelectionCount() == 2 && table.isSelected(3), "table selection");
                require(table.getSortColumn() == columns[1] && table.getSortDirection() == SWT.UP, "sort");
                int checked = 0;
                for (TableItem item : table.getItems()) {
                    checked += item.getChecked() ? 1 : 0;
                }
                require(checked == 3, "checked rows " + checked);
                int requested = setData[0];
                require(requested > 0 && requested < 10_000, "SetData requests " + requested);
                require(virtual.getItemCount() == 10_000, "virtual items " + virtual.getItemCount());
                require(virtual.getItem(5_000).getText().equals("Virtual 5000"), virtual.getItem(5_000).getText());
                require(tree.getItemCount() == 3 && roots[0].getItemCount() == 3, "tree items");
                int[] treeCounts = new int[3];
                countTree(tree.getItems(), treeCounts);
                require(treeCounts[0] == 15, "tree items " + treeCounts[0]);
                require(roots[0].getExpanded() && !roots[1].getExpanded(), "expanded items");
                require(tree.getSelectionCount() == 1 && tree.getSelection()[0] == roots[0].getItem(2),
                        "tree selection");
                return "size=" + rendered.width + "x" + rendered.height + " rows=" + table.getItemCount() + " checked="
                        + checked + " virtual=" + virtual.getItemCount() + " setData=" + requested + " treeItems="
                        + treeCounts[0] + " treeChecked=" + treeCounts[1] + " expanded=" + treeCounts[2];
            } finally {
                shell.dispose();
            }
        }
    }

    /**
     * Counts the items, the checked items and the expanded items.
     */
    private static void countTree(TreeItem[] items, int[] counts) {
        for (TreeItem item : items) {
            counts[0]++;
            counts[1] += item.getChecked() ? 1 : 0;
            counts[2] += item.getExpanded() ? 1 : 0;
            countTree(item.getItems(), counts);
        }
    }

    // ------------------------------------------------------------------------------------------------------ layouts

    /**
     * The layouts : the bounds of the children of composites of fixed sizes, written to {@code layouts-<mode>.txt}.
     */
    Object layouts() throws Exception {
        Shell shell = checks.shell("Layouts");
        // the composites of the layouts are placed by the checks
        shell.setLayout(null);
        try {
            StringBuilder text = new StringBuilder();

            Composite fill = new Composite(shell, SWT.NONE);
            FillLayout fillLayout = new FillLayout(SWT.HORIZONTAL);
            fillLayout.marginWidth = 5;
            fillLayout.marginHeight = 4;
            fillLayout.spacing = 4;
            fill.setLayout(fillLayout);
            Control[] filled = boxes(fill, 3);
            layout(fill, 300, 40);
            describe(text, "fill", fill);
            for (int i = 0; i < filled.length; i++) {
                require(filled[i].getBounds().equals(new Rectangle(5 + 98 * i, 4, 94, 32)),
                        "FillLayout child " + i + " " + filled[i].getBounds());
            }

            Composite row = new Composite(shell, SWT.NONE);
            RowLayout rowLayout = new RowLayout(SWT.HORIZONTAL);
            rowLayout.wrap = true;
            rowLayout.pack = true;
            row.setLayout(rowLayout);
            Control[] rowBoxes = boxes(row, 5);
            for (int i = 0; i < rowBoxes.length; i++) {
                rowBoxes[i].setLayoutData(new RowData(60, 20 + 5 * i));
            }
            layout(row, 200, 120);
            describe(text, "row-wrap", row);
            require(rowBoxes[1].getBounds().x == rowBoxes[0].getBounds().x + 60 + rowLayout.spacing,
                    "RowLayout spacing " + rowBoxes[1].getBounds());
            require(rowBoxes[3].getBounds().x == rowLayout.marginLeft
                    && rowBoxes[3].getBounds().y > rowBoxes[0].getBounds().y,
                    "RowLayout wrap " + rowBoxes[3].getBounds());

            Composite justified = new Composite(shell, SWT.NONE);
            RowLayout justifiedLayout = new RowLayout(SWT.HORIZONTAL);
            justifiedLayout.justify = true;
            justifiedLayout.pack = false;
            justifiedLayout.center = true;
            justified.setLayout(justifiedLayout);
            Control[] justifiedBoxes = boxes(justified, 3);
            for (int i = 0; i < justifiedBoxes.length; i++) {
                justifiedBoxes[i].setLayoutData(new RowData(30 + 10 * i, 10 + 10 * i));
            }
            layout(justified, 300, 50);
            describe(text, "row-justify", justified);
            Rectangle lastJustified = justifiedBoxes[2].getBounds();
            require(lastJustified.x + lastJustified.width > 250, "RowLayout justify " + lastJustified);
            require(justifiedBoxes[0].getBounds().width == justifiedBoxes[2].getBounds().width,
                    "RowLayout pack=false " + justifiedBoxes[0].getBounds());

            Composite vertical = new Composite(shell, SWT.NONE);
            RowLayout verticalLayout = new RowLayout(SWT.VERTICAL);
            verticalLayout.fill = true;
            vertical.setLayout(verticalLayout);
            Control[] verticalBoxes = boxes(vertical, 3);
            for (int i = 0; i < verticalBoxes.length; i++) {
                verticalBoxes[i].setLayoutData(new RowData(20 + 20 * i, 15));
            }
            layout(vertical, 120, 80);
            describe(text, "row-vertical", vertical);
            require(verticalBoxes[0].getBounds().width == 60, "RowLayout fill " + verticalBoxes[0].getBounds());

            Composite grid = new Composite(shell, SWT.NONE);
            GridLayout gridLayout = new GridLayout(3, false);
            gridLayout.marginWidth = 5;
            gridLayout.marginHeight = 5;
            gridLayout.horizontalSpacing = 6;
            gridLayout.verticalSpacing = 4;
            grid.setLayout(gridLayout);
            Control[] cells = boxes(grid, 8);
            cells[0].setLayoutData(gridData(SWT.FILL, SWT.FILL, false, false, 1, 1, 50, 20));
            cells[1].setLayoutData(gridData(SWT.FILL, SWT.FILL, true, false, 1, 1, 40, 20));
            cells[2].setLayoutData(gridData(SWT.END, SWT.CENTER, false, false, 1, 1, 30, 10));
            cells[3].setLayoutData(gridData(SWT.FILL, SWT.FILL, false, false, 2, 1, 20, 20));
            cells[4].setLayoutData(gridData(SWT.CENTER, SWT.FILL, false, true, 1, 2, 20, 30));
            cells[5].setLayoutData(gridData(SWT.BEGINNING, SWT.BEGINNING, false, false, 1, 1, 25, 25));
            cells[6].setLayoutData(gridData(SWT.FILL, SWT.FILL, false, false, 1, 1, 15, 15));
            GridData excluded = gridData(SWT.FILL, SWT.FILL, false, false, 1, 1, 10, 10);
            excluded.exclude = true;
            cells[7].setLayoutData(excluded);
            cells[7].setBounds(1, 2, 3, 4);
            layout(grid, 260, 150);
            describe(text, "grid", grid);
            Point gridSize = grid.computeSize(SWT.DEFAULT, SWT.DEFAULT);
            text.append("grid preferred ").append(gridSize.x).append('x').append(gridSize.y).append('\n');
            Rectangle a = cells[0].getBounds();
            Rectangle b = cells[1].getBounds();
            Rectangle c = cells[2].getBounds();
            require(b.width == 260 - 10 - 50 - 30 - 12, "GridLayout grab " + b);
            require(c.x + c.width == 255 && c.height == 10, "GridLayout end alignment " + c);
            require(cells[3].getBounds().width == a.width + 6 + b.width, "GridLayout span " + cells[3].getBounds());
            require(cells[7].getBounds().equals(new Rectangle(1, 2, 3, 4)), "GridData.exclude " + cells[7].getBounds());

            Composite form = new Composite(shell, SWT.NONE);
            FormLayout formLayout = new FormLayout();
            formLayout.marginWidth = 4;
            formLayout.marginHeight = 4;
            form.setLayout(formLayout);
            Control[] parts = boxes(form, 4);
            FormData first = new FormData(80, 30);
            first.left = new FormAttachment(0, 10);
            first.top = new FormAttachment(0, 10);
            parts[0].setLayoutData(first);
            FormData second = new FormData();
            second.left = new FormAttachment(parts[0], 5);
            second.top = new FormAttachment(parts[0], 0, SWT.TOP);
            second.right = new FormAttachment(100, -10);
            second.height = 30;
            parts[1].setLayoutData(second);
            FormData third = new FormData();
            third.left = new FormAttachment(25, 0);
            third.right = new FormAttachment(75, 0);
            third.top = new FormAttachment(50, 0);
            third.bottom = new FormAttachment(100, -5);
            parts[2].setLayoutData(third);
            FormData fourth = new FormData(40, 20);
            fourth.left = new FormAttachment(parts[0], 0, SWT.LEFT);
            fourth.top = new FormAttachment(parts[0], 5);
            parts[3].setLayoutData(fourth);
            layout(form, 300, 160);
            describe(text, "form", form);
            Rectangle formFirst = parts[0].getBounds();
            require(parts[1].getBounds().x == formFirst.x + formFirst.width + 5, "FormLayout attachment "
                    + parts[1].getBounds());
            require(parts[1].getBounds().y == formFirst.y, "FormLayout alignment " + parts[1].getBounds());
            require(parts[2].getBounds().y == 4 + (160 - 8) * 50 / 100,
                    "FormLayout percentage " + parts[2].getBounds());
            require(parts[3].getBounds().x == formFirst.x
                    && parts[3].getBounds().y == formFirst.y + formFirst.height + 5,
                    "FormLayout " + parts[3].getBounds());

            Composite stack = new Composite(shell, SWT.NONE);
            StackLayout stackLayout = new StackLayout();
            stackLayout.marginWidth = 3;
            stackLayout.marginHeight = 3;
            stack.setLayout(stackLayout);
            Control[] stacked = boxes(stack, 3);
            stackLayout.topControl = stacked[1];
            layout(stack, 120, 60);
            describe(text, "stack", stack);
            require(stacked[1].getVisible() && !stacked[0].getVisible() && !stacked[2].getVisible(),
                    "StackLayout visibility");
            require(stacked[1].getBounds().equals(new Rectangle(3, 3, 114, 54)),
                    "StackLayout " + stacked[1].getBounds());

            // widgets sized by their content : the fonts of the platform
            Composite widgets = new Composite(shell, SWT.NONE);
            widgets.setLayout(new GridLayout(2, false));
            new Label(widgets, SWT.NONE).setText("Name :");
            new Text(widgets, SWT.BORDER).setText("Quarkus");
            Button push = new Button(widgets, SWT.PUSH);
            push.setText("Push");
            Button check = new Button(widgets, SWT.CHECK);
            check.setText("Check");
            Point widgetsSize = widgets.computeSize(SWT.DEFAULT, SWT.DEFAULT);
            layout(widgets, widgetsSize.x, widgetsSize.y);
            describe(text, "widgets", widgets);
            text.append("widgets preferred ").append(widgetsSize.x).append('x').append(widgetsSize.y).append('\n');

            checks.writeText("layouts", text.toString());
            return "layouts=7 grid=" + gridSize.x + "x" + gridSize.y + " widgets=" + widgetsSize.x + "x"
                    + widgetsSize.y;
        } finally {
            shell.dispose();
        }
    }

    /**
     * Children of a fixed size (their layout data) : canvases without border.
     */
    private static Control[] boxes(Composite parent, int count) {
        Control[] boxes = new Control[count];
        for (int i = 0; i < count; i++) {
            boxes[i] = new Canvas(parent, SWT.NONE);
        }
        return boxes;
    }

    private static GridData gridData(int horizontalAlignment, int verticalAlignment, boolean grabHorizontal,
            boolean grabVertical, int horizontalSpan, int verticalSpan, int widthHint, int heightHint) {
        GridData data = new GridData(horizontalAlignment, verticalAlignment, grabHorizontal, grabVertical,
                horizontalSpan, verticalSpan);
        data.widthHint = widthHint;
        data.heightHint = heightHint;
        return data;
    }

    private static void layout(Composite composite, int width, int height) {
        composite.setBounds(0, 0, width, height);
        composite.layout(true, true);
    }

    private static void describe(StringBuilder text, String name, Composite composite) {
        Control[] children = composite.getChildren();
        for (int i = 0; i < children.length; i++) {
            Rectangle bounds = children[i].getBounds();
            text.append(name).append(' ').append(i).append(' ').append(bounds.x).append(',').append(bounds.y)
                    .append(',').append(bounds.width).append(',').append(bounds.height)
                    .append(children[i].getVisible() ? "" : " hidden").append('\n');
        }
    }
}
