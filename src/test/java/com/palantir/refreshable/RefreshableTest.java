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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.palantir.logsafe.exceptions.SafeIllegalArgumentException;
import java.lang.ref.WeakReference;
import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.immutables.value.Value;
import org.jmock.lib.concurrent.DeterministicScheduler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@SuppressWarnings("UnusedVariable")
@ExtendWith(MockitoExtension.class)
public final class RefreshableTest {

    private static final Config CONFIG = Config.of("prop");
    private static final Config UPDATED_CONFIG = Config.of("newProp");

    @Mock
    Callable<Config> producer;

    @Mock
    Consumer<Config> consumer;

    @Mock
    Consumer<Config> derivedConsumer;

    @Mock
    Consumer<Throwable> exceptionHandler;

    DeterministicScheduler scheduler;
    Refreshable<Config> refreshable;

    @BeforeEach
    public void before() throws Exception {
        scheduler = new DeterministicScheduler();
        updateConfig(CONFIG);
        refreshable = Refreshables.create(producer, exceptionHandler, scheduler, Duration.ofMinutes(1));
    }

    @Test
    public void testMostRecent_noUpdates() {
        assertThat(refreshable.current()).isEqualTo(CONFIG);
    }

    @Test
    public void testMostRecent_withUpdates() throws Exception {
        updateConfig(UPDATED_CONFIG);
        assertThat(refreshable.current()).isEqualTo(UPDATED_CONFIG);
    }

    @Test
    public void testMap_noUpdates() {
        Refreshable<String> mappedConfig = refreshable.map(Config::property);
        assertThat(mappedConfig.current()).isEqualTo("prop");
    }

    @Test
    public void testMap_withUpdates() throws Exception {
        Refreshable<String> mappedConfig = refreshable.map(Config::property);
        updateConfig(UPDATED_CONFIG);
        assertThat(mappedConfig.current()).isEqualTo("newProp");
    }

    @Test
    public void testMap_noEventsWhenDerivedValueUnchanged() throws Exception {
        Refreshable<Config> mappedConfig = refreshable.map(c -> CONFIG);
        refreshable.subscribe(consumer);
        mappedConfig.subscribe(derivedConsumer);
        // Both of them should get the initial value
        verify(consumer).accept(CONFIG);
        verify(derivedConsumer).accept(CONFIG);
        verifyNoMoreInteractions(consumer, derivedConsumer);

        updateConfig(UPDATED_CONFIG);
        // Only 'consumer' should get the update, since the value changed
        verify(consumer).accept(UPDATED_CONFIG);
        verifyNoMoreInteractions(consumer, derivedConsumer);
    }

    @Test
    public void testSubscribe() throws Exception {
        refreshable.subscribe(consumer);
        verify(consumer).accept(CONFIG);

        updateConfig(UPDATED_CONFIG);
        verify(consumer).accept(UPDATED_CONFIG);

        updateConfig(CONFIG);
        verify(consumer, times(2)).accept(CONFIG);
        verifyNoMoreInteractions(consumer);
    }

    @Test
    public void testSubscribe_notDistinct() throws Exception {
        refreshable.subscribe(consumer);
        verify(consumer).accept(CONFIG);

        updateConfig(UPDATED_CONFIG);
        updateConfig(UPDATED_CONFIG);
        verify(consumer).accept(UPDATED_CONFIG);
        verifyNoMoreInteractions(consumer);
    }

    @Test
    public void testSubscribe_latestOnly() throws Exception {
        updateConfig(UPDATED_CONFIG);
        refreshable.subscribe(consumer);

        verify(consumer).accept(UPDATED_CONFIG);
        verifyNoMoreInteractions(consumer);
    }

    @Test
    public void testRefreshable_doesNotBreakOnException() throws Exception {
        IllegalArgumentException exception = new IllegalArgumentException();
        when(producer.call()).thenThrow(exception).thenReturn(UPDATED_CONFIG);

        scheduler.tick(1, TimeUnit.MINUTES);

        verify(exceptionHandler).accept(exception);
        assertThat(refreshable.current()).isEqualTo(CONFIG);

        scheduler.tick(1, TimeUnit.MINUTES);

        verifyNoMoreInteractions(exceptionHandler);
        assertThat(refreshable.current()).isEqualTo(UPDATED_CONFIG);
    }

    @Test
    public void testRefreshable_diesIfFirstCallThrows() throws Exception {
        when(producer.call()).thenThrow(IllegalArgumentException.class);

        assertThatExceptionOfType(RuntimeException.class)
                .isThrownBy(() -> Refreshables.create(producer, exceptionHandler, scheduler, Duration.ofMinutes(1)));
    }

    @Test
    public void testRefreshable_doesNotThrowIfRefreshIntervalInMillis() {
        assertThatCode(() -> Refreshables.create(() -> null, throwable -> {}, scheduler, Duration.ofMillis(1)))
                .doesNotThrowAnyException();
    }

    @Test
    public void testRefreshable_throwsMeaningfulExceptionIfCreatedWithDurationOfZeroOrNegative() {
        assertThatThrownBy(() -> Refreshables.create(() -> null, throwable -> {}, scheduler, Duration.ofMillis(0)))
                .isInstanceOf(SafeIllegalArgumentException.class)
                .hasMessage("Cannot create Refreshable with 0 or negative refresh interval");

        assertThatThrownBy(() -> Refreshables.create(() -> null, throwable -> {}, scheduler, Duration.ofMillis(-10)))
                .isInstanceOf(SafeIllegalArgumentException.class)
                .hasMessage("Cannot create Refreshable with 0 or negative refresh interval");
    }

    @Test
    public void subscribers_can_throw_without_blocking_other_subscribers() {
        SettableRefreshable<Integer> foo = Refreshable.create(1);

        Consumer<Integer> throwingSubscriber = unused -> {
            throw new RuntimeException("This subscriber throws exceptions - don't want to block others");
        };
        Consumer<Integer> regularSubscriber = mock(Consumer.class);
        foo.subscribe(throwingSubscriber);
        foo.subscribe(regularSubscriber);

        foo.update(2);
        foo.update(3);

        verify(regularSubscriber).accept(2);
        verify(regularSubscriber).accept(3);
    }

    @Test
    public void testRefreshable_mappingsGetUpdatedFirst() {
        DefaultRefreshable<Integer> instance = new DefaultRefreshable<>(1);
        Refreshable<Integer> refreshablePlusOne = instance.map(i -> i + 1);
        instance.subscribe(i -> assertThat(refreshablePlusOne.get()).isEqualTo(i + 1));
        instance.update(5);
        assertThat(refreshablePlusOne.get()).isEqualTo(6);
    }

    @Test
    public void testRefreshable_subscribersUpdatedInOrder() {
        DefaultRefreshable<Integer> instance = new DefaultRefreshable<>(1);
        AtomicInteger counter = new AtomicInteger();
        instance.subscribe(i -> assertThat(counter.incrementAndGet()).isOne());
        instance.subscribe(i -> assertThat(counter.incrementAndGet()).isEqualTo(2));
        assertThat(counter.compareAndSet(2, 0)).isTrue();
        instance.update(2);
        assertThat(counter.get()).isEqualTo(2);
    }

    @Test
    @SuppressWarnings({"UnusedVariable", "StrictUnusedVariable"})
    public void map_on_grandchild_still_works_if_intermediaries_are_no_longer_externally_referenced() {
        DefaultRefreshable<Integer> root = new DefaultRefreshable<>(5);

        DefaultRefreshable<Integer> child = (DefaultRefreshable<Integer>) root.map(number -> number * 2);
        Refreshable<Integer> grandChild = child.map(number -> number * -1);
        assertThat(root.subscribers()).isOne();

        child = null;
        System.gc();

        assertThat(root.subscribers()).isOne();
        root.update(2);
        assertThat(grandChild.current()).isEqualTo(-4);

        grandChild = null;
        System.gc();

        assertThat(root.subscribers()).isOne();
        root.update(9);
        assertThat(root.subscribers()).isZero();
    }

    @Test
    @SuppressWarnings({"UnusedVariable", "StrictUnusedVariable"})
    public void subscribe_on_child_still_works_if_there_are_no_references_to_the_child() {
        DefaultRefreshable<Integer> root = new DefaultRefreshable<>(5);
        Refreshable<Integer> child = root.map(number -> number * 2);

        AtomicInteger testSubscriber = new AtomicInteger(0);
        Consumer<Integer> thowawayLambda = newValue -> testSubscriber.set(newValue);
        child.subscribe(thowawayLambda);
        assertThat(root.subscribers()).isOne();

        assertThat(testSubscriber.get()).isEqualTo(10);
        child = null;
        thowawayLambda = null;
        System.gc();

        assertThat(root.subscribers()).isOne();
        root.update(6);
        assertThat(testSubscriber.get()).isEqualTo(12);
        assertThat(root.subscribers()).isOne();
    }

    /** If the root refreshable was garbage collected, its subscribers are also garbage collected. */
    @Test
    @SuppressWarnings("StrictUnusedVariable")
    public void gcd_root_does_not_maintain_subscribers() {
        DefaultRefreshable<Integer> root = new DefaultRefreshable<>(5);

        // NOTE: can't use a lambda in this test, as they don't necessarily get garbage collected even when unreferenced
        Consumer<Integer> subscriber = new NoOpConsumer();
        root.subscribe(subscriber);
        WeakReference<?> subscriberWeakRef = new WeakReference<>(subscriber);

        subscriber = null;
        System.gc();
        assertThat(subscriberWeakRef.get()).isNotNull();

        root = null;
        System.gc();
        assertThat(subscriberWeakRef.get()).isNull();
    }

    @Test
    @SuppressWarnings("StrictUnusedVariable")
    public void garbage_collected_root_disposes_transitive_subscriber_references() {
        DefaultRefreshable<Integer> root = new DefaultRefreshable<>(5);
        Refreshable<Integer> child = root.map(number -> number * 2);

        NoOpConsumer subscriber = new NoOpConsumer();
        child.subscribe(subscriber);

        System.gc();

        assertThat(root.subscribers()).isOne();

        WeakReference<NoOpConsumer> subscriberWeakRef = new WeakReference<>(subscriber);
        child = null;
        subscriber = null;
        root = null;
        System.gc();

        assertThat(subscriberWeakRef.get()).isNull();
    }

    @Test
    public void map_results_are_not_leaked_when_updates_dont_occur() {
        DefaultRefreshable<Integer> root = new DefaultRefreshable<>(5);
        int iterations = 100;
        for (int i = 0; i < iterations; i++) {
            // simulate continued intermittent map invocations
            root.map(number -> number);
            System.gc();
        }
        assertThat(root.subscribers()).isLessThan(iterations);
    }

    private void updateConfig(Config conf) throws Exception {
        when(producer.call()).thenReturn(conf);
        scheduler.tick(1, TimeUnit.MINUTES);
    }

    @Value.Immutable
    interface Config {
        String property();

        static Config of(String property) {
            return ImmutableConfig.builder().property(property).build();
        }
    }

    private static final class NoOpConsumer implements Consumer<Integer> {
        @Override
        public void accept(Integer _value) {}
    }
}
