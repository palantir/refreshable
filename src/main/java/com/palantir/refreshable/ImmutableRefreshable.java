/*
 * (c) Copyright 2020 Palantir Technologies Inc. All rights reserved.
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

import java.util.function.Consumer;
import java.util.function.Function;

/** An immutable implementation of {@link Refreshable} used by {@link Refreshable#only(Object)}. */
final class ImmutableRefreshable<T> implements Refreshable<T> {

    private final T value;

    ImmutableRefreshable(T value) {
        this.value = value;
    }

    @Override
    public T current() {
        return value;
    }

    @Override
    public Disposable subscribe(Consumer<? super T> consumer) {
        consumer.accept(value);
        return ImmutableRefreshableDisposable.INSTANCE;
    }

    @Override
    public <R> Refreshable<R> map(Function<? super T, R> function) {
        return new ImmutableRefreshable<>(function.apply(value));
    }

    @Override
    public String toString() {
        return "ImmutableRefreshable{" + value + '}';
    }

    private enum ImmutableRefreshableDisposable implements Disposable {
        INSTANCE;

        @Override
        public void dispose() {
            // nothing to do. ImmutableRefreshable.subscribe invokes the consumer exactly once.
        }

        @Override
        public String toString() {
            return "ImmutableRefreshableDisposable{}";
        }
    }
}
