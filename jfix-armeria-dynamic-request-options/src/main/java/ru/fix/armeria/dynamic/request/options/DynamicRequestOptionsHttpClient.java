package ru.fix.armeria.dynamic.request.options;

import com.linecorp.armeria.client.Client;
import com.linecorp.armeria.client.HttpClient;
import com.linecorp.armeria.client.SimpleDecoratingClient;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.util.AbstractUnwrappable;
import com.linecorp.armeria.common.util.Unwrappable;
import ru.fix.dynamic.property.api.DynamicProperty;

/**
 * Http related implementation of {@link DynamicRequestOptionsClient}.
 * <p/>
 * Motivation why it is on java and not on kotlin:
 * <ul>
 *     <li>
 *         {@link HttpClient} implements {@link Unwrappable} with default implementation of {@link Unwrappable#as(Class)}
 *     </li>
 *     <li>
 *         {@link AbstractUnwrappable}, extended by {@link DynamicRequestOptionsClient} through
 *         {@link SimpleDecoratingClient}, also declares <b>final</b> implementation of {@link Unwrappable#as(Class)}
 *     </li>
 *     <li>
 *         When trying to extend {@link DynamicRequestOptionsClient} on kotlin, kotlin, according to
 *         <a href="https://kotlinlang.org/docs/reference/interfaces.html#resolving-overriding-conflicts">this</a>,
 *         requires manual choice which implementation to choose.
 *     </li>
 *     <li>
 *         BUT since {@link AbstractUnwrappable} declares final implementation of {@link Unwrappable#as(Class)},
 *         kotlin cannot override this method.
 *     </li>
 *     <li>
 *         according to <a href="https://docs.oracle.com/javase/tutorial/java/IandI/override.html">this</a> article:
 *         <blockquote>Instance methods are preferred over interface default methods.</blockquote>
 *     </li>
 *     <li>Thus, java class doesn't fail to compile, since {@link AbstractUnwrappable}'s implementation is chosen</li>
 * </ul>
 */
final class DynamicRequestOptionsHttpClient
        extends DynamicRequestOptionsClient<HttpRequest, HttpResponse>
        implements HttpClient {

    DynamicRequestOptionsHttpClient(
            Client<HttpRequest, HttpResponse> delegate,
            DynamicProperty<Long> readTimeoutProperty,
            DynamicProperty<Long> writeTimeoutProperty
    ) {
        super(delegate, readTimeoutProperty, writeTimeoutProperty);
    }
}
