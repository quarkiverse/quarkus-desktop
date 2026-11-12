package io.quarkiverse.desktop.packaging.spi;

import java.util.Objects;
import java.util.Optional;

import io.quarkus.builder.item.MultiBuildItem;
import io.smallrye.common.os.OS;

/**
 * An option of the JVM that the packaged application needs in JVM mode : {@code -XstartOnFirstThread} for an SWT
 * application on macOS, {@code --enable-native-access=ALL-UNNAMED}, an {@code --add-opens} option... The Desktop
 * Packaging extension gives it to the launcher of the application image, before the options of the configuration
 * ({@code quarkus.desktop.packaging.jvm.options}). Native executables ignore it.
 */
public final class PackagedJvmOptionBuildItem extends MultiBuildItem {

    private final String option;

    private final OS os;

    /**
     * @param option the option, for the applications of every operating system
     */
    public PackagedJvmOptionBuildItem(String option) {
        this(option, null);
    }

    /**
     * @param option the option
     * @param os the operating system of the applications that get the option, or {@code null} for all of them
     */
    public PackagedJvmOptionBuildItem(String option, OS os) {
        this.option = Objects.requireNonNull(option, "option");
        this.os = os;
    }

    public String getOption() {
        return option;
    }

    /**
     * The operating system of the applications that get the option : empty for all of them.
     */
    public Optional<OS> getOs() {
        return Optional.ofNullable(os);
    }
}
