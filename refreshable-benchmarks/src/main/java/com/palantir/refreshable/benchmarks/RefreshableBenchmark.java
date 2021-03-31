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

package com.palantir.refreshable.benchmarks;

import com.palantir.refreshable.Refreshable;
import com.palantir.refreshable.SettableRefreshable;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@Warmup(iterations = 4, time = 3)
@Measurement(iterations = 4, time = 3)
@Threads(4)
@Fork(value = 1)
@SuppressWarnings("checkstyle:DesignForExtension")
public class RefreshableBenchmark {

    private final SettableRefreshable<String> refreshable = Refreshable.create("initial");

    @Benchmark
    @OutputTimeUnit(TimeUnit.MICROSECONDS)
    public Refreshable<Integer> map() {
        return refreshable.map(String::length);
    }

    @Benchmark
    @OutputTimeUnit(TimeUnit.MILLISECONDS)
    public Refreshable<Integer> slowMap() {
        return refreshable.map(s -> {
            try {
                Thread.sleep(1);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            return s.length();
        });
    }

    public static void main(String[] _args) throws Exception {
        Options opt = new OptionsBuilder()
                .include(RefreshableBenchmark.class.getSimpleName())
                .build();
        new Runner(opt).run();
    }
}
