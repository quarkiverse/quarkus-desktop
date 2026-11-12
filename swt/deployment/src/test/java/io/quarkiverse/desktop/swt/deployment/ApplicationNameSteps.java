package io.quarkiverse.desktop.swt.deployment;

import java.util.function.Consumer;

import io.quarkiverse.desktop.packaging.spi.ApplicationIdBuildItem;
import io.quarkiverse.desktop.packaging.spi.PackagedApplicationNameBuildItem;
import io.quarkus.builder.BuildChainBuilder;
import io.quarkus.builder.BuildContext;
import io.quarkus.builder.BuildStep;
import io.quarkus.builder.ConsumeFlag;
import io.quarkus.builder.ConsumeFlags;
import io.quarkus.deployment.builditem.FeatureBuildItem;

/**
 * The build steps of the tests of the name of the application, without the Desktop Packaging extension : the name of
 * the packages, as that extension gives it, and the record of the application id that the build gives the packages. The
 * build and the test methods use different class loaders : the application id is passed in a system property. The tests
 * add this class to their application : their test methods only see the classes of the application.
 */
final class ApplicationNameSteps {

    /**
     * The recorded application id when the build gives the packages none.
     */
    static final String NONE = "<none>";

    /**
     * The system property of the recorded application id.
     */
    private static final String APPLICATION_ID = ApplicationNameSteps.class.getName() + ".application-id";

    private ApplicationNameSteps() {
    }

    /**
     * Produces the name of the packages ({@code PackagedApplicationNameBuildItem}), as the Desktop Packaging extension
     * does with {@code quarkus.desktop.packaging.name}.
     */
    static Consumer<BuildChainBuilder> packagedName(String name) {
        return chain -> chain.addBuildStep(new BuildStep() {
            @Override
            public void execute(BuildContext context) {
                context.produce(new PackagedApplicationNameBuildItem(name));
            }
        }).produces(PackagedApplicationNameBuildItem.class).build();
    }

    /**
     * Records the application id that the build gives the packages ({@code ApplicationIdBuildItem}), {@link #NONE} when
     * it gives none.
     */
    static Consumer<BuildChainBuilder> recordApplicationId() {
        return chain -> chain.addBuildStep(new BuildStep() {
            @Override
            public void execute(BuildContext context) {
                ApplicationIdBuildItem id = context.consume(ApplicationIdBuildItem.class);
                System.setProperty(APPLICATION_ID, id != null ? id.getId() : NONE);
                context.produce(new FeatureBuildItem("application-id-recorder"));
            }
        }).consumes(ApplicationIdBuildItem.class, ConsumeFlags.of(ConsumeFlag.OPTIONAL))
                .produces(FeatureBuildItem.class).build();
    }

    /**
     * The application id recorded by the build ({@link #recordApplicationId}), null when it did not record it.
     */
    static String applicationId() {
        return System.getProperty(APPLICATION_ID);
    }
}
