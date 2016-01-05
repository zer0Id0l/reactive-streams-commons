package reactivestreams.commons.publisher;

import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import reactivestreams.commons.subscription.EmptySubscription;
import reactivestreams.commons.support.ReactiveState;

/**
 * Represents an never publisher which only calls onSubscribe.
 * <p>
 * This Publisher is effectively stateless and only a single instance exists.
 * Use the {@link #instance()} method to obtain a properly type-parametrized view of it.
 */
public final class PublisherNever implements Publisher<Object>,
                                             ReactiveState.Factory,
                                             ReactiveState.ActiveUpstream {

    private static final Publisher<Object> INSTANCE = new PublisherNever();

    private PublisherNever() {
        // deliberately no op
    }

    @Override
    public boolean isStarted() {
        return true;
    }

    @Override
    public boolean isTerminated() {
        return false;
    }

    @Override
    public void subscribe(Subscriber<? super Object> s) {
        s.onSubscribe(EmptySubscription.INSTANCE);
    }

    /**
     * Returns a properly parametrized instance of this never Publisher.
     *
     * @return a properly parametrized instance of this never Publisher
     */
    @SuppressWarnings("unchecked")
    public static <T> Publisher<T> instance() {
        return (Publisher<T>) INSTANCE;
    }
}