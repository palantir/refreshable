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

import static org.assertj.core.api.Assertions.assertThat;

import com.google.common.collect.ImmutableList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

public class MultiParentRefreshableTest {
    private static final int LARGE_ARRAY_SIZE = 1 << 20; // 1 MiB
    // Allocate more arrays than can fit in RAM at once, causing OOM if GC is not possible
    private static final long NUM_ARRAYS_TO_ALLOCATE_PER_PASS =
            Runtime.getRuntime().maxMemory() / LARGE_ARRAY_SIZE + 1;

    @Test
    public void testMultiParentRefreshableUpdates() {
        SettableRefreshable<Integer> left = Refreshable.create(1);
        SettableRefreshable<Integer> right = Refreshable.create(2);
        Refreshable<Integer> sum = sum(left, right);
        assertThat(sum.current()).isEqualTo(3);

        left.update(3);
        assertThat(sum.current()).isEqualTo(5);

        right.update(4);
        assertThat(sum.current()).isEqualTo(7);
    }

    @Test
    public void testMultiParentRefreshableSubscription() {
        SettableRefreshable<Integer> left = Refreshable.create(1);
        SettableRefreshable<Integer> right = Refreshable.create(2);
        AtomicInteger subscribedSum = new AtomicInteger();
        sum(left, right).subscribe(subscribedSum::set);
        assertThat(subscribedSum.get()).isEqualTo(3);

        left.update(3);
        assertThat(subscribedSum.get()).isEqualTo(5);

        right.update(4);
        assertThat(subscribedSum.get()).isEqualTo(7);
    }

    @Test
    public void testUnreferencedMultiParentRefreshableIsGcEligible() {
        SettableRefreshable<Integer> left = Refreshable.create(1);
        SettableRefreshable<Integer> right = Refreshable.create(2);
        // Create enough refreshables to exceed heap
        for (int i = 0; i < NUM_ARRAYS_TO_ALLOCATE_PER_PASS; i++) {
            largeDummyRefreshable(left, right);
        }
    }

    @Test
    public void testUnsubscribedMultiParentRefreshableIsGcEligible() {
        SettableRefreshable<Integer> left = Refreshable.create(1);
        SettableRefreshable<Integer> right = Refreshable.create(2);
        for (int i = 0; i < NUM_ARRAYS_TO_ALLOCATE_PER_PASS; i++) {
            Disposable subscription = largeDummyRefreshable(left, right).subscribe(_ignored -> {});
            subscription.dispose();
        }
    }

    @Test
    public void testMappedMultiParentRefreshableIsGcEligible() {
        SettableRefreshable<Integer> left = Refreshable.create(1);
        SettableRefreshable<Integer> right = Refreshable.create(2);
        for (int i = 0; i < NUM_ARRAYS_TO_ALLOCATE_PER_PASS; i++) {
            // Note: This ensures that both parent and child refreshables are collection-eligible
            largeDummyRefreshable(left, right).map(Function.identity());
        }
    }

    @Test
    public void testActiveSubscribersAndRefreshablesAreNotGcEligible() {
        SettableRefreshable<Integer> left = Refreshable.create(1);
        SettableRefreshable<Integer> right = Refreshable.create(2);

        // Note: Redundant refreshables are deliberately created below.
        // Example:
        //  Refreshable<Integer> sum = sum(left, right);
        //  Refreshable<Integer> indirectSum = sum(left, right).map(Function.identity());
        // If this was instead written as:
        //  Refreshable<Integer> sum = sum(left, right);
        //  Refreshable<Integer> indirectSum = sum.map(Function.identity());
        // then indirectSum's parent refreshable would not be exposed to GC
        // (due to the presence of sum on the stack).
        Refreshable<Integer> sum = sum(left, right);

        AtomicInteger subscriberSum = new AtomicInteger();
        sum(left, right).subscribe(subscriberSum::set);

        Refreshable<Integer> indirectSum = sum(left, right).map(Function.identity());

        AtomicInteger indirectSubscriberSum = new AtomicInteger();
        sum(left, right).map(Function.identity()).subscribe(indirectSubscriberSum::set);

        // Trigger several rounds of GC
        for (int i = 0; i < 3 * NUM_ARRAYS_TO_ALLOCATE_PER_PASS; i++) {
            largeDummyRefreshable(left, right);
        }

        left.update(3);
        right.update(4);

        assertThat(sum.current())
                .describedAs("Direct MultiParentRefreshable receives correct value")
                .isEqualTo(7);
        assertThat(subscriberSum.get())
                .describedAs("Subscriber-only MultiParentRefreshable was not garbage-collected")
                .isEqualTo(7);
        assertThat(indirectSum.current())
                .describedAs("Intermediate MultiParentRefreshable was not garbage-collected")
                .isEqualTo(7);
        assertThat(indirectSubscriberSum.get())
                .describedAs("Subscriber-only intermediate refreshables were not garbage-collected")
                .isEqualTo(7);
    }

    private static Refreshable<Integer> sum(Refreshable<Integer> left, Refreshable<Integer> right) {
        return MultiParentRefreshable.createFromMultiple(ImmutableList.of(left, right), () -> left.get() + right.get());
    }

    private static Refreshable<byte[]> largeDummyRefreshable(Refreshable<Integer> left, Refreshable<Integer> right) {
        return MultiParentRefreshable.createFromMultiple(
                ImmutableList.of(left, right), () -> new byte[LARGE_ARRAY_SIZE]);
    }
}
