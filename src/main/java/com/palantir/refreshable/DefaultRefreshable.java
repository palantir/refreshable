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
import com.google.errorprone.annotations.concurrent.GuardedBy;
import com.palantir.logsafe.Preconditions;
import com.palantir.logsafe.UnsafeArg;
import java.lang.ref.WeakReference;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Represents a {@code T} that may be updated over time.
 *
 * <p>Internally, it differentiates between 'mapping' (to produce derived Refreshables) and 'subscribing' (for the
 * purposes of side-effects), to ensure that chains of unused derived Refreshables can be garbage collected, but any
 * undisposed side-effect subscribers keep all their ancestors alive.
 */
public final class DefaultRefreshable<T> implements Refreshable<T> {
    private static final Logger log = LoggerFactory.getLogger(DefaultRefreshable.class);
    /**
     * Every ten times refreshable.map is called without an update we must purge SelfRemovingMapSubscriber instances
     * whose children have been reaped.
     */
    private static final int CLEAN_THRESHOLD = 10;

    @GuardedBy("this")
    private final Set<Consumer<? super T>> orderedSubscribers = new LinkedHashSet<>();

    private final RootSubscriberTracker rootSubscriberTracker;
    private volatile T current;
    private final AtomicInteger mapsSinceLastUpdate = new AtomicInteger();

    /**
     * Ensures that in a long chain of mapped refreshables, intermediate ones can't be garbage collected if derived
     * refreshables are still in use. Empty for root refreshables only.
     */
    @SuppressWarnings("unused")
    private final Optional<?> strongParentReference;

    public DefaultRefreshable(T current) {
        this(current, Optional.empty(), new RootSubscriberTracker());
    }

    private DefaultRefreshable(T current, Optional<?> strongParentReference, RootSubscriberTracker tracker) {
        this.current = current;
        this.strongParentReference = strongParentReference;
        this.rootSubscriberTracker = tracker;
    }

    private <R> DefaultRefreshable<R> createChild(R initialChildValue) {
        Optional<?> parentReference = Optional.of(this);
        return new DefaultRefreshable<>(initialChildValue, parentReference, rootSubscriberTracker);
    }

    /** Updates the current value and sends the specified value to all subscribers. */
    public synchronized void update(T value) {
        if (!Objects.equals(current, value)) {
            current = value;

            // iterating over a copy allows SelfRemovingSubscribers to remove themselves without
            // ConcurrentModificationExceptions
            ImmutableList.copyOf(orderedSubscribers).forEach(subscriber -> subscriber.accept(value));
        }
    }

    @Override
    public T current() {
        return current;
    }

    @Override
    public T get() {
        return current;
    }

    @Override
    public synchronized Disposable subscribe(Consumer<? super T> throwingSubscriber) {
        SideEffectSubscriber<? super T> trackedSubscriber =
                rootSubscriberTracker.newSideEffectSubscriber(throwingSubscriber, this);

        Disposable disposable = subscribeToSelf(trackedSubscriber);
        Disposable unsubscribeAndUntrack = () -> {
            disposable.dispose();
            rootSubscriberTracker.deleteReferenceTo(trackedSubscriber);
        };
        return unsubscribeAndUntrack;
    }

    private synchronized Disposable subscribeToSelf(Consumer<? super T> subscriber) {
        orderedSubscribers.add(subscriber);
        subscriber.accept(current);
        return () -> remove(subscriber);
    }

    private synchronized void remove(Consumer<? super T> subscriber) {
        orderedSubscribers.remove(subscriber);
    }

    @Override
    public synchronized <R> Refreshable<R> map(Function<? super T, R> function) {
        if (mapsSinceLastUpdate.incrementAndGet() > CLEAN_THRESHOLD) {
            // iterating over a copy allows SelfRemovingSubscribers to remove themselves without
            // ConcurrentModificationExceptions
            ImmutableList.copyOf(orderedSubscribers).forEach(value -> {
                if (value instanceof SelfRemovingMapSubscriber) {
                    ((SelfRemovingMapSubscriber<?, ?>) value).disposeIfChildHasBeenCollected();
                }
            });
            mapsSinceLastUpdate.set(0);
        }
        R initialChildValue = function.apply(current);
        DefaultRefreshable<R> child = createChild(initialChildValue);

        SelfRemovingMapSubscriber<? super T, R> mapSubscriber = new SelfRemovingMapSubscriber<>(function, child);
        Disposable cleanUp = subscribeToSelf(mapSubscriber);
        mapSubscriber.cleanUpSubscription = cleanUp;

        return child;
    }

    /**
     * Purely for GC purposes - this class holds a reference to its parent refreshable. Instances of this class are
     * themselves tracked by the {@link RootSubscriberTracker}.
     */
    private static class SideEffectSubscriber<T> implements Consumer<T> {
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
                log.error("Failed to update refreshable subscriber", UnsafeArg.of("value", value), e);
            }
        }
    }

    /** Updates the child refreshable, while still allowing that child refreshable to be garbage collected. */
    private static final class SelfRemovingMapSubscriber<T, R> implements Consumer<T> {
        private final WeakReference<DefaultRefreshable<R>> childRef;
        private final Function<T, R> function;

        @Nullable
        private Disposable cleanUpSubscription = null;

        private SelfRemovingMapSubscriber(Function<T, R> function, DefaultRefreshable<R> child) {
            this.childRef = new WeakReference<>(child);
            this.function = function;
        }

        void disposeIfChildHasBeenCollected() {
            DefaultRefreshable<R> child = childRef.get();

            // if the child refreshable has been garbage collected, there's no point in updating it anymore
            // so we 'dispose' of the subscription, which removes it from the parent (and avoids a memory leak).
            // This is safe because callers of this method are already in a synchronized block.
            if (child == null) {
                Preconditions.checkNotNull(cleanUpSubscription, "cleanUpSubscription")
                        .dispose();
            }
        }

        @Override
        public void accept(T value) {
            DefaultRefreshable<R> child = childRef.get();

            // if the child refreshable has been garbage collected, there's no point in updating it anymore
            // so we 'dispose' of the subscription, which removes it from the parent (and avoids a memory leak).
            // This is safe because callers of this method are already in a synchronized block.
            if (child == null) {
                Preconditions.checkNotNull(cleanUpSubscription, "cleanUpSubscription")
                        .dispose();
                return;
            }

            try {
                child.update(function.apply(value));
            } catch (RuntimeException e) {
                log.error("Failed to update refreshable subscriber", UnsafeArg.of("value", value), e);
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
    synchronized int subscribers() {
        return orderedSubscribers.size();
    }
}
