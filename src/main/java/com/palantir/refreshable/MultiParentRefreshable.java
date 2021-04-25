/*
 * (c) Copyright 2021 Palantir Technologies Inc. All rights reserved.
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

import com.google.common.collect.ImmutableList;
import java.lang.ref.WeakReference;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import javax.annotation.Nullable;

/**
 * To add to {@linkplain Refreshable} semantics: when {@linkplain Refreshable#map(Function)}
 * is used to create a derived refreshable, we define {@code parent} and {@code child} as the
 * preexisting and derived refreshables, respectively. The {@code root} refers to the original
 * refreshable returned from {@linkplain Refreshable#create(Object)}, and the root and all its
 * children/descendants form a refreshable {@code tree}.
 *
 * In order for refreshables to allow garbage-collection of unused descendants, each parent only
 * weakly references its children. But, in order to ensure that a descendant's
 * {@linkplain Refreshable#subscribe(Consumer) side-effect subscribers} continue to receive
 * updates, the root keeps a strong reference to every descendant refreshable with a side-effect
 * subscriber in the tree.
 *
 * In addition, children strongly reference their own parents. This ensures that a second-order
 * child in active use (strongly-referenced by code) will keep its own parent alive and receive
 * updates.
 *
 * To summarize these semantics: a descendant refreshable is eligible for GC (i.e. not
 * strongly-referenced by root) iff it and its descendants have no side-effect subscribers
 * registered.
 *
 * However, since descendant refreshables are only created using
 * {@linkplain Refreshable#map(Function)}, the interface provides no mechanism for creating
 * refreshables based on input from multiple parents.
 *
 * This class bridges that functionality while preserving similar GC semantics: the
 * returned refreshable is eligible for GC iff it and its descendants have no side-effect
 * subscribers.
 */
public final class MultiParentRefreshable {

    private MultiParentRefreshable() {}

    /**
     * Creates a refreshable that's derived from two or more 'parent' refreshables
     * (see {@link Refreshable#map(Function)} for a refreshable derived from a single parent).
     * It is similar to
     * <pre>{@code
     *   Refreshable<T> parentA = Refreshable.create(...);
     *   Refreshable<T> parentB = Refreshable.create(...);
     *   SettableRefreshable<T> child = Refreshable.create(...);
     *   parentA.subscribe(t -> child.update(computeNewValue(t, parentB.get())));
     *   parentB.subscribe(t -> child.update(computeNewValue(parentA.get(), t)));
     * }</pre>
     * except that it allows for garbage collection of 'child'.
     * <p>
     * In particular, {@link Refreshable#subscribe(Consumer)} stores a strong reference to the
     * listener callback. In the above code, the listener holds a strong reference to 'child',
     * preventing its garbage collection even if there are no other references to 'child'.
     * {@link Refreshable#map(Function)}, by contrast, only retains a weak reference to the
     * returned refreshable. We do the equivalent here for multiple parents.
     */
    public static <R> Refreshable<R> createFromMultiple(
            Iterable<? extends Refreshable<?>> parents, Supplier<R> valueFactory) {
        SettableRefreshable<R> refreshable = Refreshable.create(valueFactory.get());
        MultiParentSubscriber<R> subscriber = MultiParentSubscriber.subscribe(parents, refreshable, valueFactory);
        return trackSideEffectSubscribers(subscriber, refreshable);
    }

    /**
     * {@linkplain Refreshable#subscribe(Consumer)} Side-effect subscription} tracking prevents
     * garbage collection of the subscribed refreshable (to ensure reception of future updates).
     * But that tracking only extends as far as the root of a refreshable tree (see
     * {@code RootSubscriberTracker} in {@linkplain com.palantir.refreshable.DefaultRefreshable}).
     * Here, that root is the return value of {@linkplain #createFromMultiple(Iterable, Supplier)}
     * which is only weakly referenced; it cannot not protect the subtree from garbage collection.
     *
     * Instead, for each side-effect subscription to a descendant refreshable, add a strong
     * reference from the {@linkplain MultiParentSubscriber} to that refreshable.
     */
    private static <R> Refreshable<R> trackSideEffectSubscribers(
            MultiParentSubscriber<?> subscriber, Refreshable<R> delegate) {
        return new Refreshable<R>() {
            @Override
            public R current() {
                return delegate.current();
            }

            @Override
            public R get() {
                return delegate.get();
            }

            @Override
            public Disposable subscribe(Consumer<? super R> consumer) {
                Disposable ref = subscriber.addStrongReference(this);
                Disposable subscription = delegate.subscribe(consumer);
                return () -> {
                    subscription.dispose();
                    ref.dispose();
                };
            }

            @Override
            public <R1> Refreshable<R1> map(Function<? super R, R1> function) {
                return trackSideEffectSubscribers(subscriber, delegate.map(function));
            }
        };
    }

    private static final class MultiParentSubscriber<R> implements Consumer<Object> {
        private final WeakReference<SettableRefreshable<R>> refreshableRef;
        private final Supplier<R> valueFactory;

        @Nullable
        private volatile List<Disposable> subscriptions;

        private final Set<Object> strongRefs = ConcurrentHashMap.newKeySet();

        private MultiParentSubscriber(SettableRefreshable<R> refreshable, Supplier<R> valueFactory) {
            this.refreshableRef = new WeakReference<>(refreshable);
            this.valueFactory = valueFactory;
        }

        private static <R> MultiParentSubscriber<R> subscribe(
                Iterable<? extends Refreshable<?>> parents,
                SettableRefreshable<R> refreshable,
                Supplier<R> valueFactory) {
            MultiParentSubscriber<R> subscriber = new MultiParentSubscriber<>(refreshable, valueFactory);
            ImmutableList.Builder<Disposable> subscriptionsBuilder = ImmutableList.builder();
            for (Refreshable<?> parent : parents) {
                subscriptionsBuilder.add(parent.subscribe(subscriber));
            }
            subscriber.subscriptions = subscriptionsBuilder.build();
            return subscriber;
        }

        private Disposable addStrongReference(Object obj) {
            Object ref = new Object[] {obj};
            strongRefs.add(ref);
            return () -> strongRefs.remove(ref);
        }

        @Override
        public void accept(Object ignored) {
            SettableRefreshable<R> refreshable = refreshableRef.get();
            if (refreshable == null) {
                if (subscriptions != null) {
                    subscriptions.forEach(Disposable::dispose);
                }
            } else {
                refreshable.update(valueFactory.get());
            }
        }
    }
}
