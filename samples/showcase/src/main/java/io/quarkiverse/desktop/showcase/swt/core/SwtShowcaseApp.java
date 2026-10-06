package io.quarkiverse.desktop.showcase.swt.core;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import io.quarkiverse.desktop.swt.SwtStartupEvent;
import io.quarkus.runtime.Quarkus;

/**
 * Page registry and main window of the SWT variant : sorts the pages, applies the page filters, opens the main window
 * and starts the snapshot run in snapshot mode. The SWT counterpart of {@code ShowcaseApp}.
 * <p>
 * The application has no {@code @QuarkusMain} : quarkus-desktop-swt runs the user interface on the main thread, creates
 * the {@code Display} there, fires {@link SwtStartupEvent} on it, then runs the event loop. The application stops with
 * {@code Quarkus.asyncExit()} : closing the main window (its last visible shell,
 * {@code quarkus.desktop.swt.exit-on-last-shell-closed}), the Quit menu item, or the end of a snapshot run.
 */
@Singleton
public class SwtShowcaseApp {

    private static final Logger LOG = Logger.getLogger(SwtShowcaseApp.class);

    @Inject
    @Any
    Instance<SwtPage> pageBeans;

    @Inject
    SwtMainWindow window;

    @Inject
    SwtSnapshotRunner snapshots;

    /** Page ids, an entry ending with {@code -} or {@code *} being an id prefix. */
    @ConfigProperty(name = "showcase.pages")
    Optional<List<String>> pageFilter;

    /** Category names or keys (see {@link SwtCategories#KEYS}). */
    @ConfigProperty(name = "showcase.categories")
    Optional<List<String>> categoryFilter;

    /**
     * Opens the main window (on the user interface thread).
     */
    void start(@Observes SwtStartupEvent event) {
        try {
            List<SwtPage> pages = pages();
            LOG.infof("%d pages, swt main window", pages.size());
            window.open(event.display(), pages);
            if (snapshots.enabled()) {
                // every page is built by the snapshot run (errors and uncaught exceptions are attributed to it)
                snapshots.run(window, pages);
            } else if (!pages.isEmpty()) {
                window.select(pages.getFirst());
            }
        } catch (Throwable t) {
            LOG.error("The showcase failed to start", t);
            snapshots.startFailed(t);
            Quarkus.asyncExit(1);
        }
    }

    /**
     * The pages, by category ({@link SwtCategories#ORDER}), order and id, filtered by {@code showcase.pages} and
     * {@code showcase.categories}.
     */
    List<SwtPage> pages() {
        List<SwtPage> pages = pageBeans.stream()
                .sorted(Comparator.comparingInt((SwtPage p) -> SwtCategories.rank(p.category()))
                        .thenComparingInt(SwtPage::order)
                        .thenComparing(SwtPage::id))
                .toList();
        Set<String> ids = new HashSet<>();
        pages.stream().filter(p -> !ids.add(p.id())).forEach(p -> LOG.errorf("Duplicate page id %s", p.id()));
        pages.stream().filter(p -> !SwtCategories.ORDER.contains(p.category()))
                .forEach(p -> LOG.errorf("Unknown category %s of page %s", p.category(), p.id()));
        pages.stream().filter(p -> !p.id().startsWith("swt-") || p.id().contains("--"))
                .forEach(p -> LOG.errorf("Invalid page id %s : swt- then lower case words and single dashes", p.id()));
        return pages.stream()
                .filter(page -> pageFilter.map(filters -> filters.stream().anyMatch(f -> matches(f.trim(), page.id())))
                        .orElse(true))
                .filter(page -> categoryFilter
                        .map(filters -> filters.stream().anyMatch(f -> SwtCategories.matches(f, page.category())))
                        .orElse(true))
                .toList();
    }

    private static boolean matches(String filter, String id) {
        if (filter.endsWith("*")) {
            return id.startsWith(filter.substring(0, filter.length() - 1));
        }
        return filter.endsWith("-") ? id.startsWith(filter) : id.equals(filter);
    }
}
