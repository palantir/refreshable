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
import com.google.common.base.Suppliers;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Represents a {@code T} that may be updated over time.
 *
 * <p>Internally, it differentiates between 'mapping' (to produce derived Refreshables) and 'subscribing' (for the
 * purposes of side-effects), to ensure that chains of unused derived Refreshables can be garbage collected, but any
 * undisposed side-effect subscribers keep all their ancestors alive.
 */
final class DefaultRefreshable<T> implements SettableRefreshable<T> {

    private final EagerRefreshable<T> delegate;

    DefaultRefreshable(T current) {
        this.delegate = new EagerRefreshable<T>(current);
    }

    @Override
    public T current() {
        return delegate.current();
    }

    @Override
    public Disposable subscribe(Consumer<? super T> consumer) {
        return delegate.subscribe(consumer);
    }

    @Override
    public <R> Refreshable<R> map(Function<? super T, R> function) {
        return new LazyRefreshable<>(Suppliers.ofInstance(delegate), delegate, function);
    }

    @Override
    public void update(T value) {
        delegate.update(value);
    }

    @VisibleForTesting
    int subscribers() {
        return delegate.subscribers();
    }
}
