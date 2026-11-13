package io.quarkiverse.desktop.packaging.ui.it;

import java.nio.file.Files;

import jakarta.enterprise.event.Observes;
import jakarta.inject.Singleton;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.FontData;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;

import io.quarkiverse.desktop.swt.OpenRequest;
import io.quarkiverse.desktop.swt.SwtStartupEvent;
import io.quarkus.runtime.ImageMode;

/**
 * The shell of the SWT application (see {@link Events}).
 */
@Singleton
public class PackagedWindow {

    void start(@Observes SwtStartupEvent event) {
        String mode = ImageMode.current().isNativeImage() ? "native" : "jvm";
        Display display = event.display();
        Shell shell = new Shell(display);
        shell.setText("Packaging UI IT");
        shell.setLayout(new FillLayout());
        Label label = new Label(shell, SWT.CENTER);
        label.setText("Packaging UI IT - SWT (" + mode + ")");
        Color background = new Color(0x46, 0x95, 0xEB);
        label.setBackground(background);
        label.setForeground(display.getSystemColor(SWT.COLOR_WHITE));
        FontData data = label.getFont().getFontData()[0];
        Font font = new Font(display, data.getName(), 24, SWT.BOLD);
        label.setFont(font);
        shell.addDisposeListener(e -> font.dispose());
        shell.setSize(640, 320);
        shell.open();
        display.asyncExec(() -> Events.log("shown swt " + mode));
        long end = System.currentTimeMillis() + Events.LIFETIME_MILLIS;
        Runnable poll = new Runnable() {
            @Override
            public void run() {
                if (shell.isDisposed()) {
                    return;
                }
                if (Files.exists(Events.QUIT) || System.currentTimeMillis() > end) {
                    shell.dispose();
                } else {
                    display.timerExec(250, this);
                }
            }
        };
        display.timerExec(250, poll);
    }

    void open(@Observes OpenRequest request) {
        Events.log(Events.open(request.source().name(), request.files(), request.uris()));
    }
}
