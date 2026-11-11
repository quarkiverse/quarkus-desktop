package io.quarkiverse.desktop.packaging.spi;

import java.util.Objects;

import io.quarkus.builder.item.SimpleBuildItem;

/**
 * The name that the application gives itself at run time, which the desktop matches with its shortcuts and entries :
 * the explicit AppUserModelID of its process on Windows, the class of its windows ({@code WM_CLASS}) on Linux. Not the
 * identifier of the packages ({@code quarkus.desktop.packaging.identifier}). The extension of a user interface toolkit
 * that names the application produces it : Desktop SWT, with the name that it gives to {@code Display.setAppName}. Not
 * Desktop AWT : AWT gives the process no AppUserModelID, and names its windows after the main class.
 * <p>
 * The Desktop Packaging extension gives it to the shortcuts of the MSI package and of the setup program
 * ({@code System.AppUserModel.ID}), without which the windows of the application and its shortcut pinned to the taskbar
 * are two buttons, and to the desktop entry of the Linux packages as its default window class ({@code StartupWMClass}).
 */
public final class ApplicationIdBuildItem extends SimpleBuildItem {

    private final String id;

    /**
     * @param id the name, as the application gives it at run time
     */
    public ApplicationIdBuildItem(String id) {
        this.id = Objects.requireNonNull(id, "id");
    }

    /**
     * The name, as the application gives it at run time.
     */
    public String getId() {
        return id;
    }
}
