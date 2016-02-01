package reactivestreams.commons.publisher;

import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicLongFieldUpdater;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import reactivestreams.commons.flow.Fuseable;
import reactivestreams.commons.flow.Fuseable.FusionMode;
import reactivestreams.commons.publisher.PublisherSubscribeOn.ScheduledEmptySubscriptionEager;
import reactivestreams.commons.publisher.PublisherSubscribeOn.ScheduledSubscriptionEagerCancel;
import reactivestreams.commons.util.BackpressureHelper;
import reactivestreams.commons.util.EmptySubscription;
import reactivestreams.commons.util.ExceptionHelper;
import reactivestreams.commons.util.SubscriptionHelper;

/**
 * Emits events on a different thread specified by a scheduler callback.
 *
 * @param <T> the value type
 */
public final class PublisherObserveOn<T> extends PublisherSource<T, T> {

    final Callable<? extends Consumer<Runnable>> schedulerFactory;
    
    final boolean delayError;
    
    final Supplier<? extends Queue<T>> queueSupplier;
    
    final int prefetch;
    
    public PublisherObserveOn(
            Publisher<? extends T> source, 
            Callable<? extends Consumer<Runnable>> schedulerFactory, 
            boolean delayError,
            int prefetch,
            Supplier<? extends Queue<T>> queueSupplier) {
        super(source);
        if (prefetch <= 0) {
            throw new IllegalArgumentException("prefetch > 0 required but it was " + prefetch);
        }
        this.schedulerFactory = Objects.requireNonNull(schedulerFactory, "schedulerFactory");
        this.delayError = delayError;
        this.prefetch = prefetch;
        this.queueSupplier = Objects.requireNonNull(queueSupplier, "queueSupplier");
    }

    @Override
    public void subscribe(Subscriber<? super T> s) {
        
        Consumer<Runnable> scheduler;
        
        try {
            scheduler = schedulerFactory.call();
        } catch (Throwable e) {
            ExceptionHelper.throwIfFatal(e);
            EmptySubscription.error(s, e);
            return;
        }
        
        if (scheduler == null) {
            EmptySubscription.error(s, new NullPointerException("The schedulerFactory returned a null Function"));
            return;
        }
        
        if (source instanceof Supplier) {
            @SuppressWarnings("unchecked")
            Supplier<T> supplier = (Supplier<T>) source;
            
            T v = supplier.get();
            
            if (v == null) {
                ScheduledEmptySubscriptionEager parent = new ScheduledEmptySubscriptionEager(s, scheduler);
                s.onSubscribe(parent);
                scheduler.accept(parent);
            } else {
                s.onSubscribe(new ScheduledSubscriptionEagerCancel<>(s, v, scheduler));
            }
            return;
        }
        
        if (s instanceof Fuseable.ConditionalSubscriber) {
            Fuseable.ConditionalSubscriber<? super T> cs = (Fuseable.ConditionalSubscriber<? super T>) s;
            source.subscribe(new PublisherObserveOnConditionalSubscriber<>(cs, scheduler, delayError, prefetch, queueSupplier));
            return;
        }
        source.subscribe(new PublisherObserveOnSubscriber<>(s, scheduler, delayError, prefetch, queueSupplier));
    }
    
    static final class PublisherObserveOnSubscriber<T>
    implements Subscriber<T>, Subscription, Runnable {
        
        final Subscriber<? super T> actual;
        
        final Consumer<Runnable> scheduler;
        
        final boolean delayError;
        
        final int prefetch;
        
        final int limit;
        
        final Supplier<? extends Queue<T>> queueSupplier;
        
        Subscription s;
        
        Queue<T> queue;
        
        volatile boolean cancelled;
        
        volatile boolean done;
        
        Throwable error;
        
        volatile int wip;
        @SuppressWarnings("rawtypes")
        static final AtomicIntegerFieldUpdater<PublisherObserveOnSubscriber> WIP =
                AtomicIntegerFieldUpdater.newUpdater(PublisherObserveOnSubscriber.class, "wip");

        volatile long requested;
        @SuppressWarnings("rawtypes")
        static final AtomicLongFieldUpdater<PublisherObserveOnSubscriber> REQUESTED =
                AtomicLongFieldUpdater.newUpdater(PublisherObserveOnSubscriber.class, "requested");

        int sourceMode;
        
        static final int NORMAL = 0;
        static final int SYNC = 1;
        static final int ASYNC = 2;
        
        long produced;
        
        public PublisherObserveOnSubscriber(
                Subscriber<? super T> actual,
                Consumer<Runnable> scheduler,
                boolean delayError,
                int prefetch,
                Supplier<? extends Queue<T>> queueSupplier) {
            this.actual = actual;
            this.scheduler = scheduler;
            this.delayError = delayError;
            this.prefetch = prefetch;
            this.queueSupplier = queueSupplier;
            if (prefetch != Integer.MAX_VALUE) {
                this.limit = prefetch - (prefetch >> 2);
            } else {
                this.limit = Integer.MAX_VALUE;
            }
        }
        
        @Override
        public void onSubscribe(Subscription s) {
            if (SubscriptionHelper.validate(this.s, s)) {
                this.s = s;
                
                if (s instanceof Fuseable.QueueSubscription) {
                    @SuppressWarnings("unchecked")
                    Fuseable.QueueSubscription<T> f = (Fuseable.QueueSubscription<T>) s;
                    
                    FusionMode m = f.requestFusion(FusionMode.ANY);
                    
                    if (m == FusionMode.SYNC) {
                        sourceMode = SYNC;
                        queue = f;
                        done = true;
                        
                        actual.onSubscribe(this);
                        return;
                    } else
                    if (m == FusionMode.ASYNC) {
                        sourceMode = ASYNC;
                        queue = f;
                    } else {
                        try {
                            queue = queueSupplier.get();
                        } catch (Throwable e) {
                            ExceptionHelper.throwIfFatal(e);
                            s.cancel();
                            scheduler.accept(null);
                            
                            EmptySubscription.error(actual, e);
                            return;
                        }
                    }
                } else {
                    try {
                        queue = queueSupplier.get();
                    } catch (Throwable e) {
                        ExceptionHelper.throwIfFatal(e);
                        s.cancel();
                        scheduler.accept(null);
                        
                        EmptySubscription.error(actual, e);
                        return;
                    }
                }
                
                actual.onSubscribe(this);
                
                if (prefetch == Integer.MAX_VALUE) {
                    s.request(Long.MAX_VALUE);
                } else {
                    s.request(prefetch);
                }
            }
        }
        
        @Override
        public void onNext(T t) {
            if (sourceMode == ASYNC) {
                trySchedule();
                return;
            }
            if (!queue.offer(t)) {
                s.cancel();
                
                error = new IllegalStateException("Queue is full?!");
                done = true;
            }
            trySchedule();
        }
        
        @Override
        public void onError(Throwable t) {
            error = t;
            done = true;
            trySchedule();
        }
        
        @Override
        public void onComplete() {
            done = true;
            trySchedule();
        }
        
        @Override
        public void request(long n) {
            if (SubscriptionHelper.validate(n)) {
                BackpressureHelper.addAndGet(REQUESTED, this, n);
                trySchedule();
            }
        }
        
        @Override
        public void cancel() {
            if (cancelled) {
                return;
            }
            
            cancelled = true;
            scheduler.accept(null);
            
            if (WIP.getAndIncrement(this) == 0) {
                s.cancel();
                queue.clear();
            }
        }
        
        void trySchedule() {
            if (WIP.getAndIncrement(this) != 0) {
                return;
            }
            scheduler.accept(this);
        }
        
        void runSync() {
            int missed = 1;
            
            final Subscriber<? super T> a = actual;
            final Queue<T> q = queue;

            long e = produced;

            for (;;) {
                
                long r = requested;
                
                while (e != r) {
                    T v;
                    
                    try {
                        v = q.poll();
                    } catch (Throwable ex) {
                        ExceptionHelper.throwIfFatal(ex);
                        scheduler.accept(null);
                        
                        a.onError(ex);
                        return;
                    }

                    if (cancelled) {
                        scheduler.accept(null);
                        return;
                    }
                    if (v == null) {
                        scheduler.accept(null);
                        a.onComplete();
                        return;
                    }
                    
                    a.onNext(v);
                    
                    e++;
                }
                
                if (e == r) {
                    if (cancelled) {
                        scheduler.accept(null);
                        return;
                    }
                    
                    boolean empty;
                    
                    try {
                        empty = q.isEmpty();
                    } catch (Throwable ex) {
                        ExceptionHelper.throwIfFatal(ex);
                        scheduler.accept(null);
                        
                        a.onError(ex);
                        return;
                    }
                    
                    if (empty) {
                        scheduler.accept(null);
                        a.onComplete();
                        return;
                    }
                }

                int w = wip;
                if (missed == w) {
                    produced = e;
                    missed = WIP.addAndGet(this, -missed);
                    if (missed == 0) {
                        break;
                    }
                } else {
                    missed = w;
                }
            }
        }
        
        void runAsync() {
            int missed = 1;
            
            final Subscriber<? super T> a = actual;
            final Queue<T> q = queue;

            long e = produced;

            for (;;) {
                
                long r = requested;
                
                while (e != r) {
                    boolean d = done;
                    T v = q.poll();
                    boolean empty = v == null;
                    
                    if (checkTerminated(d, empty, a)) {
                        return;
                    }
                    
                    if (empty) {
                        break;
                    }
                    
                    a.onNext(v);
                    
                    e++;
                    if (e == limit) {
                        if (r != Long.MAX_VALUE) {
                            r = REQUESTED.addAndGet(this, -e);
                        }
                        s.request(e);
                        e = 0L;
                    }
                }
                
                if (e == r) {
                    if (checkTerminated(done, q.isEmpty(), a)) {
                        return;
                    }
                }
                
                int w = wip;
                if (missed == w) {
                    produced = e;
                    missed = WIP.addAndGet(this, -missed);
                    if (missed == 0) {
                        break;
                    }
                } else {
                    missed = w;
                }
            }
        }
        
        @Override
        public void run() {
            if (sourceMode == SYNC) {
                runSync();
            } else {
                runAsync();
            }
        }
        
        boolean checkTerminated(boolean d, boolean empty, Subscriber<?> a) {
            if (cancelled) {
                s.cancel();
                scheduler.accept(null);
                queue.clear();
                return true;
            }
            if (d) {
                if (delayError) {
                    if (empty) {
                        scheduler.accept(null);
                        Throwable e = error;
                        if (e != null) {
                            a.onError(e);
                        } else {
                            a.onComplete();
                        }
                        return true;
                    }
                } else {
                    Throwable e = error;
                    if (e != null) {
                        scheduler.accept(null);
                        queue.clear();
                        a.onError(e);
                        return true;
                    } else 
                    if (empty) {
                        scheduler.accept(null);
                        a.onComplete();
                        return true;
                    }
                }
            }
            
            return false;
        }
    }

    static final class PublisherObserveOnConditionalSubscriber<T>
    implements Subscriber<T>, Subscription, Runnable {
        
        final Fuseable.ConditionalSubscriber<? super T> actual;
        
        final Consumer<Runnable> scheduler;
        
        final boolean delayError;
        
        final int prefetch;
        
        final int limit;

        final Supplier<? extends Queue<T>> queueSupplier;
        
        Subscription s;
        
        Queue<T> queue;
        
        volatile boolean cancelled;
        
        volatile boolean done;
        
        Throwable error;
        
        volatile int wip;
        @SuppressWarnings("rawtypes")
        static final AtomicIntegerFieldUpdater<PublisherObserveOnConditionalSubscriber> WIP =
                AtomicIntegerFieldUpdater.newUpdater(PublisherObserveOnConditionalSubscriber.class, "wip");

        volatile long requested;
        @SuppressWarnings("rawtypes")
        static final AtomicLongFieldUpdater<PublisherObserveOnConditionalSubscriber> REQUESTED =
                AtomicLongFieldUpdater.newUpdater(PublisherObserveOnConditionalSubscriber.class, "requested");

        int sourceMode;
        
        static final int NORMAL = 0;
        static final int SYNC = 1;
        static final int ASYNC = 2;

        long produced;
        
        long consumed;
        
        public PublisherObserveOnConditionalSubscriber(
                Fuseable.ConditionalSubscriber<? super T> actual,
                Consumer<Runnable> scheduler,
                boolean delayError,
                int prefetch,
                Supplier<? extends Queue<T>> queueSupplier) {
            this.actual = actual;
            this.scheduler = scheduler;
            this.delayError = delayError;
            this.prefetch = prefetch;
            this.queueSupplier = queueSupplier;
            if (prefetch != Integer.MAX_VALUE) {
                this.limit = prefetch - (prefetch >> 2);
            } else {
                this.limit = Integer.MAX_VALUE;
            }
        }
        
        @Override
        public void onSubscribe(Subscription s) {
            if (SubscriptionHelper.validate(this.s, s)) {
                this.s = s;
                
                if (s instanceof Fuseable.QueueSubscription) {
                    @SuppressWarnings("unchecked")
                    Fuseable.QueueSubscription<T> f = (Fuseable.QueueSubscription<T>) s;
                    
                    FusionMode m = f.requestFusion(FusionMode.ANY);
                    
                    if (m == FusionMode.SYNC) {
                        sourceMode = SYNC;
                        queue = f;
                        done = true;
                        
                        actual.onSubscribe(this);
                        return;
                    } else
                    if (m == FusionMode.ASYNC) {
                        sourceMode = ASYNC;
                        queue = f;
                    } else {
                        try {
                            queue = queueSupplier.get();
                        } catch (Throwable e) {
                            ExceptionHelper.throwIfFatal(e);
                            s.cancel();
                            scheduler.accept(null);
                            
                            EmptySubscription.error(actual, e);
                            return;
                        }
                    }
                } else {
                    try {
                        queue = queueSupplier.get();
                    } catch (Throwable e) {
                        ExceptionHelper.throwIfFatal(e);
                        s.cancel();
                        scheduler.accept(null);
                        
                        EmptySubscription.error(actual, e);
                        return;
                    }
                }
                
                actual.onSubscribe(this);
                
                if (prefetch == Integer.MAX_VALUE) {
                    s.request(Long.MAX_VALUE);
                } else {
                    s.request(prefetch);
                }
            }
        }
        
        @Override
        public void onNext(T t) {
            if (sourceMode == ASYNC) {
                trySchedule();
                return;
            }
            if (!queue.offer(t)) {
                s.cancel();
                
                error = new IllegalStateException("Queue is full?!");
                done = true;
            }
            trySchedule();
        }
        
        @Override
        public void onError(Throwable t) {
            error = t;
            done = true;
            trySchedule();
        }
        
        @Override
        public void onComplete() {
            done = true;
            trySchedule();
        }
        
        @Override
        public void request(long n) {
            if (SubscriptionHelper.validate(n)) {
                BackpressureHelper.addAndGet(REQUESTED, this, n);
                trySchedule();
            }
        }
        
        @Override
        public void cancel() {
            if (cancelled) {
                return;
            }
            
            cancelled = true;
            scheduler.accept(null);
            
            if (WIP.getAndIncrement(this) == 0) {
                s.cancel();
                queue.clear();
            }
        }
        
        void trySchedule() {
            if (WIP.getAndIncrement(this) != 0) {
                return;
            }
            
            scheduler.accept(this);
        }
        
        void runSync() {
            int missed = 1;
            
            final Fuseable.ConditionalSubscriber<? super T> a = actual;
            final Queue<T> q = queue;

            long e = produced;

            for (;;) {
                
                long r = requested;
                
                while (e != r) {
                    T v;
                    try {
                        v = q.poll();
                    } catch (Throwable ex) {
                        ExceptionHelper.throwIfFatal(ex);
                        scheduler.accept(null);
                        
                        a.onError(ex);
                        return;
                    }

                    if (cancelled) {
                        scheduler.accept(null);
                        return;
                    }
                    if (v == null) {
                        scheduler.accept(null);
                        a.onComplete();
                        return;
                    }
                    
                    if (a.tryOnNext(v)) {
                        e++;
                    }
                }
                
                if (e == r) {
                    if (cancelled) {
                        scheduler.accept(null);
                        return;
                    }
                    
                    boolean empty;
                    
                    try {
                        empty = q.isEmpty();
                    } catch (Throwable ex) {
                        ExceptionHelper.throwIfFatal(ex);
                        scheduler.accept(null);
                        
                        a.onError(ex);
                        return;
                    }
                    
                    if (empty) {
                        scheduler.accept(null);
                        a.onComplete();
                        return;
                    }
                }

                int w = wip;
                if (missed == w) {
                    produced = e;
                    missed = WIP.addAndGet(this, -missed);
                    if (missed == 0) {
                        break;
                    }
                } else {
                    missed = w;
                }
            }
        }
        
        void runAsync() {
            int missed = 1;
            
            final Fuseable.ConditionalSubscriber<? super T> a = actual;
            final Queue<T> q = queue;
            
            long emitted = produced;
            long polled = consumed;
            
            for (;;) {
                
                long r = requested;
                
                while (emitted != r) {
                    boolean d = done;
                    T v = q.poll();
                    boolean empty = v == null;
                    
                    if (checkTerminated(d, empty, a)) {
                        return;
                    }
                    
                    if (empty) {
                        break;
                    }

                    if (a.tryOnNext(v)) {
                        emitted++;
                    }
                    
                    polled++;
                    
                    if (polled == limit) {
                        s.request(polled);
                        polled = 0L;
                    }
                }
                
                if (emitted == r) {
                    if (checkTerminated(done, q.isEmpty(), a)) {
                        return;
                    }
                }
                
                int w = wip;
                if (missed == w) {
                    produced = emitted;
                    consumed = polled;
                    missed = WIP.addAndGet(this, -missed);
                    if (missed == 0) {
                        break;
                    }
                } else {
                    missed = w;
                }
            }

        }
        
        @Override
        public void run() {
            if (sourceMode == SYNC) {
                runSync();
            } else {
                runAsync();
            }
        }
        
        boolean checkTerminated(boolean d, boolean empty, Subscriber<?> a) {
            if (cancelled) {
                s.cancel();
                queue.clear();
                scheduler.accept(null);
                return true;
            }
            if (d) {
                if (delayError) {
                    if (empty) {
                        scheduler.accept(null);
                        Throwable e = error;
                        if (e != null) {
                            a.onError(e);
                        } else {
                            a.onComplete();
                        }
                        return true;
                    }
                } else {
                    Throwable e = error;
                    if (e != null) {
                        scheduler.accept(null);
                        queue.clear();
                        a.onError(e);
                        return true;
                    } else 
                    if (empty) {
                        scheduler.accept(null);
                        a.onComplete();
                        return true;
                    }
                }
            }
            
            return false;
        }
    }
}
