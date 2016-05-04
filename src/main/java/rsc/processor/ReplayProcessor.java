package rsc.processor;

import java.util.Arrays;
import java.util.Iterator;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicLongFieldUpdater;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

import org.reactivestreams.Processor;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import rsc.documentation.BackpressureMode;
import rsc.documentation.BackpressureSupport;
import rsc.flow.Fuseable;
import rsc.documentation.FusionMode;
import rsc.documentation.FusionSupport;
import rsc.flow.MultiProducer;
import rsc.flow.Producer;
import rsc.flow.Receiver;
import rsc.publisher.Px;
import rsc.state.Backpressurable;
import rsc.state.Cancellable;
import rsc.state.Completable;
import rsc.state.Requestable;
import rsc.util.BackpressureHelper;
import rsc.util.SubscriptionHelper;
import rsc.util.UnsignalledExceptions;

/**
 * Replays all or the last N items to Subscribers.
 * 
 * @param <T> the value type
 */
@BackpressureSupport(input = BackpressureMode.UNBOUNDED, output = BackpressureMode.BOUNDED)
@FusionSupport(input = { FusionMode.NONE }, output = { FusionMode.ASYNC })
public final class ReplayProcessor<T> 
extends Px<T> implements Processor<T, T>, Fuseable, MultiProducer, Backpressurable,
                         Completable, Receiver {

    final Buffer<T> buffer;

    Subscription subscription;
    
    volatile ReplaySubscription<T>[] subscribers;
    @SuppressWarnings("rawtypes")
    static final AtomicReferenceFieldUpdater<ReplayProcessor, ReplaySubscription[]> SUBSCRIBERS =
            AtomicReferenceFieldUpdater.newUpdater(ReplayProcessor.class, ReplaySubscription[].class, "subscribers");
    
    @SuppressWarnings("rawtypes")
    static final ReplaySubscription[] EMPTY = new ReplaySubscription[0];
    @SuppressWarnings("rawtypes")
    static final ReplaySubscription[] TERMINATED = new ReplaySubscription[0];
    
    /**
     * Constructs a ReplayProcessor with bounded or unbounded
     * buffering.
     * @param bufferSize if unbounded, this number represents the link size of the shared buffer,
     *                   if bounded, this is the maximum number of retained items
     * @param unbounded should the replay buffer be unbounded
     */
    public ReplayProcessor(int bufferSize, boolean unbounded) {
        if (unbounded) {
            this.buffer = new UnboundedBuffer<>(bufferSize);
        } else {
            this.buffer = new BoundedBuffer<>(bufferSize);
        }
        SUBSCRIBERS.lazySet(this, EMPTY);
    }
    
    @Override
    public void subscribe(Subscriber<? super T> s) {
        
        ReplaySubscription<T> rp = new ReplaySubscription<>(s, this);
        s.onSubscribe(rp);
        
        if (add(rp)) {
            if (rp.cancelled) {
                remove(rp);
            }
        } else {
            buffer.drain(rp);
        }
    }

    @Override
    public Iterator<?> downstreams() {
        return Arrays.asList(subscribers).iterator();
    }

    @Override
    public long downstreamCount() {
        return subscribers.length;
    }

    @Override
    public long getCapacity() {
        return buffer.capacity();
    }

    @Override
    public boolean isTerminated() {
        return buffer.isDone();
    }

    @Override
    public boolean isStarted() {
        return subscription != null;
    }

    @Override
    public Object upstream() {
        return subscription;
    }

    boolean add(ReplaySubscription<T> rp) {
        for (;;) {
            ReplaySubscription<T>[] a = subscribers;
            if (a == TERMINATED) {
                return false;
            }
            int n = a.length;
            
            @SuppressWarnings("unchecked")
            ReplaySubscription<T>[] b = new ReplaySubscription[n + 1];
            System.arraycopy(a, 0, b, 0, n);
            b[n] = rp;
            if (SUBSCRIBERS.compareAndSet(this, a, b)) {
                return true;
            }
        }
    }
    
    @SuppressWarnings("unchecked")
    void remove(ReplaySubscription<T> rp) {
        outer:
        for (;;) {
            ReplaySubscription<T>[] a = subscribers;
            if (a == TERMINATED || a == EMPTY) {
                return;
            }
            int n = a.length;
            
            for (int i = 0; i < n; i++) {
                if (a[i] == rp) {
                    ReplaySubscription<T>[] b;
                    
                    if (n == 1) {
                        b = EMPTY;
                    } else {
                        b = new ReplaySubscription[n - 1];
                        System.arraycopy(a, 0, b, 0, i);
                        System.arraycopy(a, i + 1, b, i, n - i - 1);
                    }
                    
                    if (SUBSCRIBERS.compareAndSet(this, a, b)) {
                        return;
                    }
                    
                    continue outer;
                }
            }
            
            break;
        }
    }

    @Override
    public void onSubscribe(Subscription s) {
        if (buffer.isDone()) {
            s.cancel();
        } else {
            if(!SubscriptionHelper.validate(subscription, s)) {
                s.cancel();
                return;
            }
            subscription = s;
            s.request(Long.MAX_VALUE);
        }
    }

    @Override
    public void onNext(T t) {
        Buffer<T> b = buffer;
        if (b.isDone()) {
            UnsignalledExceptions.onNextDropped(t);
        } else {
            b.onNext(t);
            for (ReplaySubscription<T> rp : subscribers) {
                b.drain(rp);
            }
        }
    }

    @Override
    public void onError(Throwable t) {
        Buffer<T> b = buffer;
        if (b.isDone()) {
            UnsignalledExceptions.onErrorDropped(t);
        } else {
            b.onError(t);
            
            @SuppressWarnings("unchecked")
            ReplaySubscription<T>[] a = SUBSCRIBERS.getAndSet(this, TERMINATED);
            
            for (ReplaySubscription<T> rp : a) {
                b.drain(rp);
            }
        }
    }

    @Override
    public void onComplete() {
        Buffer<T> b = buffer;
        if (!b.isDone()) {
            b.onComplete();
            
            @SuppressWarnings("unchecked")
            ReplaySubscription<T>[] a = SUBSCRIBERS.getAndSet(this, TERMINATED);
            
            for (ReplaySubscription<T> rp : a) {
                b.drain(rp);
            }
        }
    }

    interface Buffer<T> {
        
        void onNext(T value);
        
        void onError(Throwable ex);
        
        void onComplete();
        
        void drain(ReplaySubscription<T> rp);
        
        boolean isDone();
        
        T poll(ReplaySubscription<T> rp);
        
        void clear(ReplaySubscription<T> rp);
        
        boolean isEmpty(ReplaySubscription<T> rp);
        
        int size(ReplaySubscription<T> rp);

        int capacity();
    }
    
    static final class UnboundedBuffer<T> implements Buffer<T> {

        final int batchSize;
        
        volatile int size;
        
        final Object[] head;
        
        Object[] tail;
        
        int tailIndex;
        
        volatile boolean done;
        Throwable error;
        
        public UnboundedBuffer(int batchSize) {
            this.batchSize = batchSize;
            Object[] n = new Object[batchSize + 1];
            this.tail = n;
            this.head = n;
        }

        @Override
        public int capacity() {
            return batchSize;
        }

        @Override
        public void onNext(T value) {
            int i = tailIndex;
            Object[] a = tail;
            if (i == a.length - 1) {
                Object[] b = new Object[a.length];
                b[0] = value;
                tailIndex = 1;
                a[i] = b;
                tail = b;
            } else {
                a[i] = value;
                tailIndex = i + 1;
            }
            size++;
        }

        @Override
        public void onError(Throwable ex) {
            error = ex;
            done = true;
        }

        @Override
        public void onComplete() {
            done = true;
        }

        void drainNormal(ReplaySubscription<T> rp) {
            int missed = 1;
            
            final Subscriber<? super T> a = rp.actual;
            final int n = batchSize;
            
            for (;;) {
                
                long r = rp.requested;
                long e = 0L;
                
                Object[] node = (Object[])rp.node;
                if (node == null) {
                    node = head;
                }
                int tailIndex = rp.tailIndex;
                int index = rp.index;
                
                while (e != r) {
                    if (rp.cancelled) {
                        rp.node = null;
                        return;
                    }
                    
                    boolean d = done;
                    boolean empty = index == size;

                    if (d && empty) {
                        rp.node = null;
                        Throwable ex = error;
                        if (ex != null) {
                            a.onError(ex);
                        } else {
                            a.onComplete();
                        }
                        return;
                    }
                    
                    if (empty) {
                        break;
                    }
                    
                    if (tailIndex == n) {
                        node = (Object[])node[tailIndex];
                        tailIndex = 0;
                    }
                    
                    @SuppressWarnings("unchecked")
                    T v = (T)node[tailIndex];
                    
                    a.onNext(v);
                    
                    e++;
                    tailIndex++;
                    index++;
                }
                
                if (e == r) {
                    if (rp.cancelled) {
                        rp.node = null;
                        return;
                    }
                    
                    boolean d = done;
                    boolean empty = index == size;

                    if (d && empty) {
                        rp.node = null;
                        Throwable ex = error;
                        if (ex != null) {
                            a.onError(ex);
                        } else {
                            a.onComplete();
                        }
                        return;
                    }
                }
                
                if (e != 0L) {
                    if (r != Long.MAX_VALUE) {
                        rp.produced(e);
                    }
                }
                
                rp.index = index;
                rp.tailIndex = tailIndex;
                rp.node = node;
                
                missed = rp.leave(missed);
                if (missed == 0) {
                    break;
                }
            }
        }
        
        void drainFused(ReplaySubscription<T> rp) {
            int missed = 1;
            
            final Subscriber<? super T> a = rp.actual;
            
            for (;;) {
                
                if (rp.cancelled) {
                    rp.node = null;
                    return;
                }
                
                a.onNext(null);
                
                if (done) {
                    Throwable ex = error;
                    if (ex != null) {
                        a.onError(ex);
                    } else {
                        a.onComplete();
                    }
                    return;
                }
                
                missed = rp.leave(missed);
                if (missed == 0) {
                    break;
                }
            }
        }
        
        @Override
        public void drain(ReplaySubscription<T> rp) {
            if (!rp.enter()) {
                return;
            }
            
            if (rp.fusionMode == NONE) {
                drainNormal(rp);
            } else {
                drainFused(rp);
            }
        }

        @Override
        public boolean isDone() {
            return done;
        }

        @Override
        public T poll(ReplaySubscription<T> rp) {
            int index = rp.index;
            if (index == size) {
                return null;
            }
            Object[] node = (Object[])rp.node;
            if (node == null) {
                node = head;
                rp.node = node;
            }
            int tailIndex = rp.tailIndex;
            if (tailIndex == batchSize) {
                node = (Object[])node[tailIndex];
                tailIndex = 0;
            }
            @SuppressWarnings("unchecked")
            T v = (T)node[tailIndex];
            rp.index = index + 1;
            rp.tailIndex = tailIndex + 1;
            return v;
        }

        @Override
        public void clear(ReplaySubscription<T> rp) {
            rp.node = null;
        }

        @Override
        public boolean isEmpty(ReplaySubscription<T> rp) {
            return rp.index == size;
        }

        @Override
        public int size(ReplaySubscription<T> rp) {
            return size - rp.index;
        }
        
    }
    
    static final class BoundedBuffer<T> implements Buffer<T> {

        final int limit;
        
        volatile Node<T> head;
        
        Node<T> tail;

        int size;

        volatile boolean done;
        Throwable error;
        
        public BoundedBuffer(int limit) {
            this.limit = limit;
            Node<T> n = new Node<>(null);
            this.tail = n;
            this.head = n;
        }

        @Override
        public int capacity() {
            return limit;
        }

        @Override
        public void onNext(T value) {
            Node<T> n = new Node<>(value);
            tail.set(n);
            tail = n;
            int s = size;
            if (s == limit) {
                head = head.get();
            } else {
                size = s + 1;
            }
        }

        @Override
        public void onError(Throwable ex) {
            error = ex;
            done = true;
        }

        @Override
        public void onComplete() {
            done = true;
        }

        void drainNormal(ReplaySubscription<T> rp) {
            final Subscriber<? super T> a = rp.actual;
            
            int missed = 1;
            
            for (;;) {
                
                long r = rp.requested;
                long e = 0L;
                
                @SuppressWarnings("unchecked")
                Node<T> node = (Node<T>)rp.node;
                if (node == null) {
                    node = head;
                }
                
                while (e != r) {
                    if (rp.cancelled) {
                        rp.node = null;
                        return;
                    }
                    
                    boolean d = done;
                    Node<T> next = node.get();
                    boolean empty = next == null;
                    
                    if (d && empty) {
                        rp.node = null;
                        Throwable ex = error;
                        if (ex != null) {
                            a.onError(ex);
                        } else {
                            a.onComplete();
                        }
                        return;
                    }
                    
                    if (empty) {
                        break;
                    }
                    
                    a.onNext(next.value);
                    
                    e++;
                    node = next;
                }
                
                if (e == r) {
                    if (rp.cancelled) {
                        rp.node = null;
                        return;
                    }
                    
                    boolean d = done;
                    boolean empty = node.get() == null;
                    
                    if (d && empty) {
                        rp.node = null;
                        Throwable ex = error;
                        if (ex != null) {
                            a.onError(ex);
                        } else {
                            a.onComplete();
                        }
                        return;
                    }
                }
                
                if (e != 0L) {
                    if (r != Long.MAX_VALUE) {
                        rp.produced(e);
                    }
                }
                
                rp.node = node;
                
                missed = rp.leave(missed);
                if (missed == 0) {
                    break;
                }
            }
        }
        
        void drainFused(ReplaySubscription<T> rp) {
            int missed = 1;
            
            final Subscriber<? super T> a = rp.actual;
            
            for (;;) {
                
                if (rp.cancelled) {
                    rp.node = null;
                    return;
                }
                
                a.onNext(null);
                
                if (done) {
                    Throwable ex = error;
                    if (ex != null) {
                        a.onError(ex);
                    } else {
                        a.onComplete();
                    }
                    return;
                }
                
                missed = rp.leave(missed);
                if (missed == 0) {
                    break;
                }
            }
        }
        
        @Override
        public void drain(ReplaySubscription<T> rp) {
            if (!rp.enter()) {
                return;
            }
            
            if (rp.fusionMode == NONE) {
                drainNormal(rp);
            } else {
                drainFused(rp);
            }
        }

        @Override
        public boolean isDone() {
            return done;
        }
        
        static final class Node<T> extends AtomicReference<Node<T>> {
            /** */
            private static final long serialVersionUID = 3713592843205853725L;
            
            final T value;
            
            public Node(T value) {
                this.value = value;
            }
        }

        @Override
        public T poll(ReplaySubscription<T> rp) {
            @SuppressWarnings("unchecked")
            Node<T> node = (Node<T>)rp.node;
            if (node == null) {
                node = head;
                rp.node = node;
            }
            
            Node<T> next = node.get();
            if (next == null) {
                return null;
            }
            rp.node = next;
            
            return next.value;
        }

        @Override
        public void clear(ReplaySubscription<T> rp) {
            rp.node = null;
        }

        @Override
        public boolean isEmpty(ReplaySubscription<T> rp) {
            @SuppressWarnings("unchecked")
            Node<T> node = (Node<T>)rp.node;
            if (node == null) {
                node = head;
                rp.node = node;
            }
            return node.get() == null;
        }

        @Override
        public int size(ReplaySubscription<T> rp) {
            @SuppressWarnings("unchecked")
            Node<T> node = (Node<T>)rp.node;
            if (node == null) {
                node = head;
            }
            int count = 0;
            
            Node<T> next;
            while ((next = node.get()) != null && count != Integer.MAX_VALUE) {
                count++;
                node = next;
            }
            
            return count;
        }
    }
    
    static final class ReplaySubscription<T> implements QueueSubscription<T>, Producer,
                                                        Cancellable, Receiver,
                                                        Requestable {
        final Subscriber<? super T> actual;
        
        final ReplayProcessor<T> parent;
        
        final Buffer<T> buffer;

        int index;
        
        int tailIndex;
        
        Object node;
        
        volatile int wip;
        @SuppressWarnings("rawtypes")
        static final AtomicIntegerFieldUpdater<ReplaySubscription> WIP =
                AtomicIntegerFieldUpdater.newUpdater(ReplaySubscription.class, "wip");

        volatile long requested;
        @SuppressWarnings("rawtypes")
        static final AtomicLongFieldUpdater<ReplaySubscription> REQUESTED =
                AtomicLongFieldUpdater.newUpdater(ReplaySubscription.class, "requested");
        
        volatile boolean cancelled;
        
        int fusionMode;

        public ReplaySubscription(Subscriber<? super T> actual, ReplayProcessor<T> parent) {
            this.actual = actual;
            this.parent = parent;
            this.buffer = parent.buffer;
        }

        @Override
        public long requestedFromDownstream() {
            return requested;
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }

        @Override
        public Object downstream() {
            return actual;
        }

        @Override
        public Object upstream() {
            return parent;
        }

        @Override
        public int requestFusion(int requestedMode) {
            if ((requestedMode & ASYNC) != 0) {
                fusionMode = ASYNC;
                return ASYNC;
            }
            return NONE;
        }
        
        @Override
        public T poll() {
            return buffer.poll(this);
        }
        
        @Override
        public void clear() {
            buffer.clear(this);
        }
        
        @Override
        public boolean isEmpty() {
            return buffer.isEmpty(this);
        }
        
        @Override
        public int size() {
            return buffer.size(this);
        }
        
        @Override
        public void request(long n) {
            if (SubscriptionHelper.validate(n)) {
                if (fusionMode == NONE) {
                    BackpressureHelper.getAndAddCap(REQUESTED, this, n);
                }
                buffer.drain(this);
            }
        }
        
        @Override
        public void cancel() {
            if (!cancelled) {
                cancelled = true;
                
                parent.remove(this);
                
                if (enter()) {
                    node = null;
                }
            }
        }
        
        boolean enter() {
            return WIP.getAndIncrement(this) == 0;
        }
        
        int leave(int missed) {
            return WIP.addAndGet(this, -missed);
        }
        
        void produced(long n) {
            REQUESTED.addAndGet(this, -n);
        }
    }
}
