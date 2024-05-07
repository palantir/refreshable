/*
 * (c) Copyright 2017 Palantir Technologies Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.palantir.refreshable;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.RateLimiter;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.palantir.logsafe.DoNotLog;
import com.palantir.logsafe.SafeArg;
import com.palantir.logsafe.exceptions.SafeRuntimeException;
import com.palantir.logsafe.logger.SafeLogger;
import com.palantir.logsafe.logger.SafeLoggerFactory;
import java.lang.ref.Cleaner;
import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Consumer;
import java.util.function.Function;
import javax.annotation.concurrent.GuardedBy;

/**
 * Represents a {@code T} that may be updated over time.
 *
 * <p>Internally, it differentiates between 'mapping' (to produce derived Refreshables) and 'subscribing' (for the
 * purposes of side-effects), to ensure that chains of unused derived Refreshables can be garbage collected, but any
 * undisposed side-effect subscribers keep all their ancestors alive.
 */
final class DefaultRefreshable<@DoNotLog T> implements SettableRefreshable<T> {
    private static final SafeLogger log = SafeLoggerFactory.get(DefaultRefreshable.class);
    private static final Cleaner REFRESHABLE_CLEANER = Cleaner.create(new ThreadFactoryBuilder()
            .setNameFormat("DefaultRefreshable-Cleaner-%d")
            .setDaemon(true)
            .build());

    private static final int WARN_THRESHOLD = 1000;

    private final RateLimiter warningRateLimiter;

    /** Subscribers are updated in deterministic order based on registration order. This prevents a class
     * of bugs where a listener on a refreshable uses a refreshable mapped from itself, and guarantees the child
     * mappings will be up-to-date before the listener is executed, as long as the input mapping occurred before
     * the subscription. While we strongly recommend against this kind of dependency, it's complicated to detect
     * in large projects with layers of indirection.
     * <p>
     * Consider the following:
     * <pre>{@code
     * SettableRefreshable<Integer> instance = Refreshable.create(1);
     * Refreshable<Integer> refreshablePlusOne = instance.map(i -> i + 1);
     * instance.subscribe(i -> {
     *     // Code invoked here should reliably be able to assume that refreshablePlusOne.current()
     *     // is i + 1. Without enforcing order, that would not be the case and vary by jvm initialization!
     * });
     * }</pre>
     */
    private final Set<Consumer<? super T>> orderedSubscribers = Collections.synchronizedSet(new LinkedHashSet<>());

    private final RootSubscriberTracker rootSubscriberTracker;
    private volatile T current;
    private final Lock writeLock;
    private final Lock readLock;

    /**
     * Ensures that in a long chain of mapped refreshables, intermediate ones can't be garbage collected if derived
     * refreshables are still in use. Empty for root refreshables only.
     */
    @SuppressWarnings("unused")
    private final Optional<?> strongParentReference;

    DefaultRefreshable(T current) {
        this(current, Optional.empty(), new RootSubscriberTracker());
    }

    private DefaultRefreshable(T current, Optional<?> strongParentReference, RootSubscriberTracker tracker) {
        this.current = current;
        this.strongParentReference = strongParentReference;
        this.rootSubscriberTracker = tracker;
        this.warningRateLimiter = RateLimiter.create(10);
        ReadWriteLock lock = new ReentrantReadWriteLock();
        writeLock = lock.writeLock();
        readLock = lock.readLock();
    }

    private <R> DefaultRefreshable<R> createChild(R initialChildValue) {
        Optional<?> parentReference = Optional.of(this);
        return new DefaultRefreshable<>(initialChildValue, parentReference, rootSubscriberTracker);
    }

    /** Updates the current value and sends the specified value to all subscribers. */
    @Override
    public void update(T value) {
        writeLock.lock();
        try {
            if (!Objects.equals(current, value)) {
                current = value;

                // iterating over a copy allows subscriptions to be disposed within an update without causing
                // ConcurrentModificationExceptions.
                ImmutableList.copyOf(orderedSubscribers).forEach(subscriber -> subscriber.accept(value));
            }
        } finally {
            writeLock.unlock();
        }
    }

    @Override
    public T current() {
        return current;
    }

    @Override
    public Disposable subscribe(Consumer<? super T> throwingSubscriber) {
        readLock.lock();
        try {
            SideEffectSubscriber<? super T> trackedSubscriber =
                    rootSubscriberTracker.newSideEffectSubscriber(throwingSubscriber, this);

            Disposable disposable = subscribeToSelf(trackedSubscriber, true);
            return new SubscribeDisposable(disposable, rootSubscriberTracker, trackedSubscriber);
        } finally {
            readLock.unlock();
        }
    }

    private static final class SubscribeDisposable implements Disposable {
        private final Disposable delegate;
        private final RootSubscriberTracker rootSubscriberTracker;
        private final SideEffectSubscriber<?> trackedSubscriber;

        SubscribeDisposable(
                Disposable delegate,
                RootSubscriberTracker rootSubscriberTracker,
                SideEffectSubscriber<?> trackedSubscriber) {
            this.delegate = delegate;
            this.rootSubscriberTracker = rootSubscriberTracker;
            this.trackedSubscriber = trackedSubscriber;
        }

        @Override
        public void dispose() {
            delegate.dispose();
            rootSubscriberTracker.deleteReferenceTo(trackedSubscriber);
        }
    }

    @GuardedBy("readLock")
    private Disposable subscribeToSelf(Consumer<? super T> subscriber, boolean updateSubscriber) {
        preSubscribeLogging();
        orderedSubscribers.add(subscriber);
        if (updateSubscriber) {
            subscriber.accept(current);
        }
        return new DefaultDisposable(orderedSubscribers, subscriber);
    }

    /**
     * A {@link Disposable} object shouldn't prevent a {@link Refreshable} from being garbage collected if the
     * refreshable is no longer referenced. In that case it's impossible that the consumer could be called with an
     * update because the root refreshable has been garbage collected.
     * <pre>{@code
     * SettableRefreshable root = Refreshable.create(initial);
     * Disposable subscription = root.subscribe(System.out::println);
     * root = null;
     * // root should be garbage collected, nothing can update the SettableRefreshable value.
     * }</pre>
     */
    private static final class DefaultDisposable implements Disposable {
        // The subscribers set holds a reference to the subscriber, if either reference is
        // collected, the other isn't meaningful to track either.
        private final WeakReference<Set<? extends Consumer<?>>> subscribersRef;
        private final WeakReference<Consumer<?>> subscriberRef;

        DefaultDisposable(Set<? extends Consumer<?>> subscribers, Consumer<?> subscriber) {
            this.subscribersRef = new WeakReference<>(subscribers);
            this.subscriberRef = new WeakReference<>(subscriber);
        }

        @Override
        public void dispose() {
            Set<? extends Consumer<?>> subscribers = subscribersRef.get();
            Consumer<?> subscriber = subscriberRef.get();
            subscribersRef.clear();
            subscriberRef.clear();
            if (subscribers != null && subscriber != null) {
                subscribers.remove(subscriber);
            }
        }
    }

    @Override
    public <R> Refreshable<R> map(Function<? super T, R> function) {
        readLock.lock();
        try {
            R initialChildValue = function.apply(current);
            DefaultRefreshable<R> child = createChild(initialChildValue);

            MapSubscriber<? super T, R> mapSubscriber = new MapSubscriber<>(function, child);
            // Do not update the subscriber here because we've just computed the value while
            // holding readLock above to ensure bad functions throw on 'map' invocation.
            Disposable cleanUp = subscribeToSelf(mapSubscriber, false);
            REFRESHABLE_CLEANER.register(child, cleanUp::dispose);
            return child;
        } finally {
            readLock.unlock();
        }
    }

    private void preSubscribeLogging() {
        if (log.isWarnEnabled()) {
            int subscribers = orderedSubscribers.size() + 1;
            if (subscribers > WARN_THRESHOLD && warningRateLimiter.tryAcquire()) {
                log.warn(
                        "Refreshable {} has an excessive number of subscribers: {} and is likely leaking memory. "
                                + "The current warning threshold is {}.",
                        SafeArg.of("refreshableIdentifier", System.identityHashCode(this)),
                        SafeArg.of("numSubscribers", subscribers),
                        SafeArg.of("warningThreshold", WARN_THRESHOLD),
                        new SafeRuntimeException("location"));
            } else if (log.isDebugEnabled()) {
                log.debug(
                        "Added a subscription to refreshable {} resulting in {} subscriptions",
                        SafeArg.of("refreshableIdentifier", System.identityHashCode(this)),
                        SafeArg.of("numSubscribers", subscribers));
            }
        }
    }

    /**
     * Purely for GC purposes - this class holds a reference to its parent refreshable. Instances of this class are
     * themselves tracked by the {@link RootSubscriberTracker}.
     */
    private static class SideEffectSubscriber<@DoNotLog T> implements Consumer<T> {
        private final Consumer<T> unsafeSubscriber;

        @SuppressWarnings("unused")
        private final Refreshable<?> strongParentReference;

        SideEffectSubscriber(Consumer<T> unsafeSubscriber, Refreshable<?> strongParentReference) {
            this.unsafeSubscriber = unsafeSubscriber;
            this.strongParentReference = strongParentReference;
        }

        @Override
        public void accept(T value) {
            try {
                unsafeSubscriber.accept(value);
            } catch (RuntimeException e) {
                log.error("Failed to update refreshable subscriber", e);
            }
        }
    }

    /** Updates the child refreshable, while still allowing that child refreshable to be garbage collected. */
    private static final class MapSubscriber<@DoNotLog T, @DoNotLog R> implements Consumer<T> {
        private final WeakReference<DefaultRefreshable<R>> childRef;
        private final Function<T, R> function;

        private MapSubscriber(Function<T, R> function, DefaultRefreshable<R> child) {
            this.childRef = new WeakReference<>(child);
            this.function = function;
        }

        @Override
        public void accept(T value) {
            DefaultRefreshable<R> child = childRef.get();
            if (child != null) {
                try {
                    child.update(function.apply(value));
                } catch (RuntimeException e) {
                    log.error("Failed to update refreshable subscriber", e);
                }
            }
        }
    }

    /**
     * Stores references to all {@link SideEffectSubscriber} instances, so that they won't be garbage collected until
     * the whole refreshable tree is collected. Otherwise, derived Refreshables may be GC'd because their only inbound
     * references could be WeakReferences.
     */
    private static final class RootSubscriberTracker {
        private final Set<SideEffectSubscriber<?>> liveSubscribers = ConcurrentHashMap.newKeySet();

        <T> SideEffectSubscriber<? super T> newSideEffectSubscriber(
                Consumer<? super T> unsafeSubscriber, DefaultRefreshable<T> parent) {
            SideEffectSubscriber<? super T> freshSubscriber = new SideEffectSubscriber<>(unsafeSubscriber, parent);
            liveSubscribers.add(freshSubscriber);
            return freshSubscriber;
        }

        void deleteReferenceTo(SideEffectSubscriber<?> subscriber) {
            liveSubscribers.remove(subscriber);
        }
    }

    @VisibleForTesting
    int subscribers() {
        return orderedSubscribers.size();
    }
}
