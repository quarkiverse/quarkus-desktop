package io.quarkiverse.desktop.packaging.ui.it;

import java.awt.Color;
import java.awt.Font;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.nio.file.Files;

import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.SwingConstants;
import javax.swing.Timer;
import javax.swing.WindowConstants;

import jakarta.enterprise.event.Observes;
import jakarta.inject.Singleton;

import io.quarkiverse.desktop.awt.DesktopStartupEvent;
import io.quarkiverse.desktop.awt.OpenRequest;
import io.quarkus.runtime.ImageMode;

/**
 * The window of the Swing application (see {@link Events}).
 */
@Singleton
public class PackagedWindow {

    void start(@Observes DesktopStartupEvent event) {
        String mode = ImageMode.current().isNativeImage() ? "native" : "jvm";
        JFrame frame = new JFrame("Packaging UI IT");
        frame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        JLabel label = new JLabel("Packaging UI IT - Swing (" + mode + ")", SwingConstants.CENTER);
        label.setOpaque(true);
        label.setBackground(new Color(0x4695EB));
        label.setForeground(Color.WHITE);
        label.setFont(label.getFont().deriveFont(Font.BOLD, 24f));
        frame.add(label);
        frame.setSize(640, 320);
        frame.setLocationRelativeTo(null);
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowOpened(WindowEvent e) {
                Events.log("shown swing " + mode);
            }
        });
        long end = System.currentTimeMillis() + Events.LIFETIME_MILLIS;
        Timer timer = new Timer(250, null);
        timer.addActionListener(e -> {
            if (Files.exists(Events.QUIT) || System.currentTimeMillis() > end) {
                timer.stop();
                frame.dispose();
            }
        });
        timer.start();
        frame.setVisible(true);
    }

    void open(@Observes OpenRequest request) {
        Events.log(Events.open(request.source().name(), request.files(), request.uris()));
    }
}
