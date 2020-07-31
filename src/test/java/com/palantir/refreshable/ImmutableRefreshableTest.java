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

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class ImmutableRefreshableTest {

    @ParameterizedTest
    @EnumSource(RefreshableFactory.class)
    void testValue(RefreshableFactory factory) {
        Refreshable<String> refreshable = factory.of("initial");
        assertThat(refreshable.current()).isEqualTo(refreshable.get()).isEqualTo("initial");
    }

    @ParameterizedTest
    @EnumSource(RefreshableFactory.class)
    void testMap(RefreshableFactory factory) {
        Refreshable<String> refreshable = factory.of("initial").map(value -> value + value);
        assertThat(refreshable.current()).isEqualTo(refreshable.get()).isEqualTo("initialinitial");
    }

    @ParameterizedTest
    @EnumSource(RefreshableFactory.class)
    void testSubscribe(RefreshableFactory factory) {
        Refreshable<String> refreshable = factory.of("initial");
        AtomicReference<String> subscriber = new AtomicReference<>("initial");
        Disposable disposable = refreshable.subscribe(subscriber::set);
        assertThat(subscriber).hasValue("initial");
        disposable.dispose();
    }

    @ParameterizedTest
    @EnumSource(RefreshableFactory.class)
    void testNullValue(RefreshableFactory factory) {
        Refreshable<String> refreshable = factory.of(null);
        assertThat(refreshable.current()).isEqualTo(refreshable.get()).isNull();
        AtomicReference<String> subscriber = new AtomicReference<>("initial");
        Disposable disposable = refreshable.subscribe(subscriber::set);
        assertThat(subscriber).hasValue(null);
        disposable.dispose();

        Refreshable<String> toNonNull = refreshable.map(_ignored -> "non-null");
        assertThat(toNonNull.current()).isEqualTo(toNonNull.get()).isEqualTo("non-null");
    }

    @ParameterizedTest
    @EnumSource(RefreshableFactory.class)
    void testMapToNull(RefreshableFactory factory) {
        Refreshable<String> refreshable = factory.of("initial");

        Refreshable<String> toNull = refreshable.map(_ignored -> null);
        assertThat(toNull.current()).isEqualTo(toNull.get()).isNull();
    }

    enum RefreshableFactory {
        IMMUTABLE() {
            @Override
            <T> Refreshable<T> of(T value) {
                return Refreshable.only(value);
            }
        },
        DEFAULT() {
            @Override
            <T> Refreshable<T> of(T value) {
                return Refreshable.create(value);
            }
        };

        abstract <T> Refreshable<T> of(T value);
    }
}
