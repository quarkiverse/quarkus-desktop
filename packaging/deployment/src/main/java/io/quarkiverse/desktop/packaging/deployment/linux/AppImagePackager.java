package io.quarkiverse.desktop.packaging.deployment.linux;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.quarkiverse.desktop.packaging.deployment.AppInfo;
import io.quarkiverse.desktop.packaging.deployment.ApplicationImage;
import io.quarkiverse.desktop.packaging.deployment.FileTrees;
import io.quarkiverse.desktop.packaging.deployment.Packager;
import io.quarkiverse.desktop.packaging.deployment.PackagingContext;
import io.quarkiverse.desktop.packaging.deployment.PackagingException;
import io.quarkiverse.desktop.packaging.deployment.Tools;

/**
 * An AppImage, built with {@code appimagetool} from an application directory ({@code AppDir}) : the application image,
 * an {@code AppRun} script that starts its launcher, its desktop entry and its icon. One executable file that runs on
 * most distributions, without installation : it has the application image, not the libraries of the system that the
 * application uses.
 */
public final class AppImagePackager implements Packager {

    /**
     * The size of the section of the update information in the runtime of an AppImage ({@code .upd_info}), in bytes.
     */
    private static final int UPDATE_INFORMATION_SIZE = 1024;

    /**
     * Checks {@code appimagetool}, the runtime file of the configuration, and the update information with
     * {@code zsyncmake}, which writes the {@code .zsync} file of the AppImage.
     */
    @Override
    public void check(PackagingContext context) {
        appimagetool(context);
        runtimeFile(context);
        if (updateInformation(context.config().linux().appimage().updateInformation()).isPresent()) {
            zsyncmake(context);
        }
    }

    @Override
    public Path build(PackagingContext context, ApplicationImage image) throws IOException {
        String arch = context.arch().gnu();
        Path appimagetool = appimagetool(context);
        Optional<Path> runtimeFile = runtimeFile(context);
        Optional<String> updateInformation = updateInformation(context.config().linux().appimage().updateInformation());
        Optional<Path> zsyncmake = updateInformation.map(information -> zsyncmake(context));
        AppInfo app = context.app();
        String name = app.packageName();
        Path work = context.workDirectory("appimage");
        Path appDir = work.resolve(app.fileName() + ".AppDir");
        FileTrees.copy(image.root(), appDir.resolve("usr/lib").resolve(name));
        // the entry point of the AppImage
        Path appRun = FileTrees.write(appDir.resolve("AppRun"), appRun(name, image.launcher()));
        FileTrees.executable(appRun);
        Files.createDirectories(appDir.resolve("usr/bin"));
        Files.createSymbolicLink(appDir.resolve("usr/bin").resolve(name), Path.of("../lib/" + name + "/" + image.launcher()));
        // the desktop entry and the icon of the AppImage are at its root
        String desktopEntry = LinuxDesktop.desktopEntry(app, context.config().linux(), name, name);
        FileTrees.write(appDir.resolve(name + ".desktop"), desktopEntry);
        FileTrees.write(appDir.resolve("usr/share/applications").resolve(name + ".desktop"), desktopEntry);
        context.icons().writePng(appDir.resolve(name + ".png"), 256);
        Files.createSymbolicLink(appDir.resolve(".DirIcon"), Path.of(name + ".png"));
        LinuxDesktop.icons(context.icons(), appDir.resolve("usr/share/icons/hicolor"), name, 256);
        // the MIME types of the file types, which the tools that integrate AppImages (AppImageLauncher) install
        LinuxDesktop.writeMimeTypes(app, appDir.resolve("usr/share"), name, 256);
        FileTrees.normalizePermissions(appDir);

        Path appImage = context.output(app.fileName() + "-" + app.version() + "-" + arch + ".AppImage");
        Path tmp = Files.createDirectories(work.resolve("tmp"));
        Map<String, String> environment = new HashMap<>(environment(arch, app.version(), tmp));
        // appimagetool runs the zsyncmake of the PATH : the one that was found first
        zsyncmake.ifPresent(tool -> environment.put("PATH", path(tool, System.getenv("PATH"))));
        Tools.exec(work, environment, command(appimagetool, runtimeFile, updateInformation, appDir, appImage));
        FileTrees.executable(appImage);
        if (updateInformation.isPresent()) {
            // zsyncmake writes the .zsync file in the working directory of appimagetool
            String zsync = appImage.getFileName() + ".zsync";
            if (!Files.isRegularFile(work.resolve(zsync))) {
                throw new PackagingException("appimagetool did not write " + zsync + ", the file that the AppImage"
                        + " updaters download (quarkus.desktop.packaging.linux.appimage.update-information) : check that"
                        + " zsyncmake runs (zsync package)");
            }
            Files.move(work.resolve(zsync), appImage.resolveSibling(zsync), StandardCopyOption.REPLACE_EXISTING);
        }
        return appImage;
    }

    /**
     * The {@code zsyncmake} of the machine, which {@code appimagetool} runs to write the {@code .zsync} file of an
     * AppImage with update information.
     */
    private static Path zsyncmake(PackagingContext context) {
        return context.tools().require("zsyncmake", "install zsync (apt install zsync, dnf install zsync)");
    }

    /**
     * A {@code PATH} whose first directory is the one of a tool : the tools that run it by its name find that one.
     *
     * @param path the {@code PATH} of the build, or {@code null}
     */
    static String path(Path tool, String path) {
        String directory = tool.getParent().toString();
        return path == null || path.isEmpty() ? directory : directory + File.pathSeparator + path;
    }

    /**
     * The update information of the configuration ({@code quarkus.desktop.packaging.linux.appimage.update-information}),
     * checked : {@code zsync|<URL>}, {@code gh-releases-zsync|<owner>|<repository>|<release>|<file name>} or
     * {@code pling-v1-zsync|<product>|<file name>}, without empty fields, in the 1024 bytes of its section.
     *
     * @return the information, empty when it is not set or blank
     * @throws PackagingException for information in another format
     */
    static Optional<String> updateInformation(Optional<String> configured) {
        Optional<String> information = configured.map(String::strip).filter(value -> !value.isEmpty());
        if (information.isEmpty()) {
            return information;
        }
        String value = information.get();
        String[] fields = value.split("\\|", -1);
        int expected = switch (fields[0]) {
            case "zsync" -> 2;
            case "gh-releases-zsync" -> 5;
            case "pling-v1-zsync" -> 3;
            default -> -1;
        };
        boolean valid = fields.length == expected
                && value.getBytes(StandardCharsets.UTF_8).length < UPDATE_INFORMATION_SIZE;
        for (String field : fields) {
            valid &= !field.isBlank();
        }
        if (!valid) {
            throw new PackagingException("quarkus.desktop.packaging.linux.appimage.update-information=" + value + " : the"
                    + " update information is zsync|<URL of the .zsync file>,"
                    + " gh-releases-zsync|<owner>|<repository>|<release, such as latest>|<file name of the .zsync file,"
                    + " with * for the version> or pling-v1-zsync|<product>|<file name>, in less than "
                    + UPDATE_INFORMATION_SIZE + " bytes");
        }
        return information;
    }

    /**
     * The {@code appimagetool} of the machine : {@code appimagetool}, or the AppImage of the architecture of the
     * application ({@code appimagetool-x86_64.AppImage}).
     */
    private static Path appimagetool(PackagingContext context) {
        return context.tools().find("appimagetool")
                .or(() -> context.tools().find("appimagetool-" + context.arch().gnu() + ".AppImage"))
                .orElseThrow(() -> new PackagingException("appimagetool not found : download appimagetool-"
                        + context.arch().gnu() + ".AppImage from https://github.com/AppImage/appimagetool/releases, make"
                        + " it executable and put it on the PATH as appimagetool (or set"
                        + " quarkus.desktop.packaging.tools.appimagetool to its path)"));
    }

    /**
     * The runtime of the AppImage of the configuration ({@code quarkus.desktop.packaging.linux.appimage.runtime-file}),
     * if any.
     *
     * @throws PackagingException when the file does not exist
     */
    private static Optional<Path> runtimeFile(PackagingContext context) {
        Optional<Path> runtimeFile = context.config().linux().appimage().runtimeFile()
                .map(context.projectDirectory()::resolve).map(Path::toAbsolutePath);
        if (runtimeFile.isPresent() && !Files.isRegularFile(runtimeFile.get())) {
            throw new PackagingException("quarkus.desktop.packaging.linux.appimage.runtime-file : " + runtimeFile.get()
                    + " not found");
        }
        return runtimeFile;
    }

    /**
     * The environment of {@code appimagetool} : the architecture and the version of the AppImage (rather than a
     * {@code VERSION} variable of the build), and its extraction where FUSE is missing (containers), as it is an
     * AppImage itself. It extracts itself in a temporary directory of the build : in {@code /tmp}, two builds would
     * share the directory of the same {@code appimagetool}, which the first one to end deletes.
     *
     * @param tmp the temporary directory of {@code appimagetool}
     */
    static Map<String, String> environment(String arch, String version, Path tmp) {
        return Map.of("ARCH", arch, "VERSION", version, "APPIMAGE_EXTRACT_AND_RUN", "1", "TMPDIR", tmp.toString());
    }

    /**
     * The command of {@code appimagetool}.
     *
     * @param runtimeFile the runtime of the AppImage, or empty for {@code appimagetool} to download the latest one
     * @param updateInformation the update information to embed, if any : {@code appimagetool} then runs
     *        {@code zsyncmake}, which writes the {@code .zsync} file
     */
    static List<String> command(Path appimagetool, Optional<Path> runtimeFile, Optional<String> updateInformation,
            Path appDir, Path appImage) {
        List<String> command = new ArrayList<>(List.of(appimagetool.toString(), "--no-appstream"));
        runtimeFile.ifPresent(runtime -> command.addAll(List.of("--runtime-file", runtime.toString())));
        updateInformation.ifPresent(information -> command.addAll(List.of("--updateinformation", information)));
        command.add(appDir.toString());
        command.add(appImage.toString());
        return command;
    }

    /**
     * The entry point of the AppImage : starts the launcher of the application image, wherever the AppImage is
     * mounted.
     */
    static String appRun(String name, String launcher) {
        return "#!/bin/sh\n"
                + "HERE=\"$(dirname \"$(readlink -f \"$0\")\")\"\n"
                + "exec \"$HERE/usr/lib/" + name + "/" + launcher + "\" \"$@\"\n";
    }
}
