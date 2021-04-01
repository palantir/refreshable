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

import com.palantir.logsafe.Preconditions;
import com.palantir.logsafe.UnsafeArg;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A lazy implementation of refreshable which avoids eager updates until {@link #subscribe(Consumer)} is invoked.
 * It's common that subscribe is never called, so there's no reason to track the result of each map if it's only ever
 * used for updates.
 * This comes at a cost: Unlike the eager refreshable, this object may block callers of {@link Refreshable#current()}
 * to compute updated values on the caller thread, while {@link EagerRefreshable} handles all computation on the
 * thread which calls {@link SettableRefreshable#update(Object)}.
 */
final class LazyRefreshable<T, U> implements Refreshable<T> {

    private static final Logger log = LoggerFactory.getLogger(LazyRefreshable.class);

    @Nullable
    private Supplier<Refreshable<U>> nonLazyParent;

    @Nullable
    private Refreshable<U> maybeLazyParent;

    @Nullable
    private Function<? super U, T> function;

    @Nullable
    private volatile Refreshable<T> fullDelegate;

    @Nullable
    private volatile CachedResult<U, T> cached;

    LazyRefreshable(
            Supplier<Refreshable<U>> nonLazyParent, Refreshable<U> maybeLazyParent, Function<? super U, T> function) {
        this.nonLazyParent = nonLazyParent;
        this.maybeLazyParent = maybeLazyParent;
        this.function = function;
    }

    @Override
    public T current() {
        Refreshable<T> snapshot = fullDelegate;
        if (snapshot != null) {
            return snapshot.current();
        }
        CachedResult<U, T> cachedResultSnapshot = cached;

        // maybeLazyParent may be nulled while a current() call is in flight.
        Refreshable<U> maybeLazyParentSnapshot = maybeLazyParent;
        if (maybeLazyParentSnapshot == null) {
            return Preconditions.checkNotNull(
                            fullDelegate, "maybeLazyParent is and fullDelegate are both null at the same time.")
                    .current();
        } else if (cachedResultSnapshot != null) {
            U currentParentValue = maybeLazyParentSnapshot.current();
            if (cachedResultSnapshot.parentValue == currentParentValue) {
                return cachedResultSnapshot.functionResult;
            }
        }

        synchronized (this) {
            // revalidate before doing expensive computations
            snapshot = fullDelegate;
            if (snapshot != null) {
                return snapshot.current();
            }
            // recheck the delegate to avoid recomputing the same result
            cachedResultSnapshot = cached;
            U currentParentValue = maybeLazyParentSnapshot.current();
            if (cachedResultSnapshot != null) {
                if (cachedResultSnapshot.parentValue == currentParentValue) {
                    return cachedResultSnapshot.functionResult;
                }
            }
            try {
                T result = Preconditions.checkNotNull(
                                function,
                                "Lazy refreshable function cannot be null within current() computation mutex block")
                        .apply(currentParentValue);
                cached = new CachedResult<>(currentParentValue, result);
                return result;
            } catch (RuntimeException e) {
                // Use the previous value if we're unable to compute a new one
                if (cachedResultSnapshot != null) {
                    // only log in the non-throwing branch
                    log.error(
                            "Failed to update refreshable subscriber with value {}",
                            UnsafeArg.of("value", currentParentValue),
                            e);
                    // Update the cached value mapping the new parent value to the previous result to avoid
                    // attempting to recompute on each access.
                    cached = new CachedResult<>(currentParentValue, cachedResultSnapshot.functionResult);
                    return cachedResultSnapshot.functionResult;
                }
                throw e;
            }
        }
    }

    @Override
    public Disposable subscribe(Consumer<? super T> consumer) {
        // Subscribe always requires a eager refreshable
        return getOrInitEagerDelegate().subscribe(consumer);
    }

    @Override
    public <R> Refreshable<R> map(Function<? super T, R> mapFunction) {
        // Note: this class must not return a map from the full delegate directly because that would be more expensive
        // with no guarantee that subscribe will ever be called on it.
        return new LazyRefreshable<>(this::getOrInitEagerDelegate, this, mapFunction);
    }

    private Refreshable<T> getOrInitEagerDelegate() {
        Refreshable<T> snapshot = fullDelegate;
        if (snapshot == null) {
            synchronized (this) {
                // Must retry loading the full delegate instance under a lock
                snapshot = fullDelegate;
                if (snapshot != null) {
                    return snapshot;
                }

                snapshot = Preconditions.checkNotNull(
                                nonLazyParent, "nonLazyParent cannot be null while initializing the eager delegate")
                        .get()
                        .map(Preconditions.checkNotNull(
                                function,
                                "Lazy refreshable function cannot be null while initializing the eager delegate"));
                setEagerDelegate(snapshot);
            }
        }
        return snapshot;
    }

    @GuardedBy("this")
    private void setEagerDelegate(Refreshable<T> value) {
        fullDelegate = value;
        // Free references that are no longer necessary -- at this point the LazyRefreshable
        // is a thin shim to the eager delegate.
        maybeLazyParent = null;
        nonLazyParent = null;
        function = null;
        cached = null;
    }

    private static final class CachedResult<T, R> {
        private final T parentValue;
        private final R functionResult;

        CachedResult(T parentValue, R functionResult) {
            this.parentValue = parentValue;
            this.functionResult = functionResult;
        }
    }
}
