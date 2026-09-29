package io.quarkiverse.desktop.awt;

import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import jakarta.interceptor.InterceptorBinding;

/**
 * Runs the annotated methods of a bean on the event dispatch thread, whatever thread calls them.
 * <ul>
 * <li>A {@code void} method runs now when called on the event dispatch thread, later ({@code EventQueue.invokeLater})
 * otherwise, and its call returns at once ; an exception thrown later goes to the uncaught exception handler of the event
 * dispatch thread.</li>
 * <li>A method returning a {@code CompletionStage} (or a {@code CompletableFuture}) returns a stage completed with the
 * result, or the exception, of the method, once it ran on the event dispatch thread.</li>
 * </ul>
 * Other return types are a build error, as are beans other than {@code @ApplicationScoped}, {@code @Singleton} and
 * {@code @Dependent} ones, and a class binding on a component class (annotate methods, or a presenter bean). The
 * interceptor is the outermost one : the other interceptors of the method ({@code @ActivateRequestContext},
 * {@code @Transactional}, fault tolerance, caches, tracing) run on the event dispatch thread.
 *
 * <pre>
 * &#64;ApplicationScoped
 * public class StatusPresenter {
 *
 *     &#64;Inject
 *     Instance&lt;StatusBar&gt; bar;
 *
 *     &#64;RunOnEdt
 *     void show(String text) {
 *         bar.get().setText(text);
 *     }
 * }
 * </pre>
 */
@InterceptorBinding
@Inherited
@Retention(RetentionPolicy.RUNTIME)
@Target({ ElementType.TYPE, ElementType.METHOD })
public @interface RunOnEdt {
}
