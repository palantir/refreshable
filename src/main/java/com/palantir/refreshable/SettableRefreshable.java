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

/**
 * A {@link Refreshable} value which can be updated by calling the {@link #update} method. It is expected that you
 * only have one root SettableRefreshable, and all other refreshables are derived from this using
 * {@link Refreshable#map} calls.
 */
public interface SettableRefreshable<T> extends Refreshable<T> {

    /**
     * Replaces the value stored in this refreshable with a new value, possibly notifying subscribers. Note that
     * subscribers may run on the same thread. Successive calls to {@link Refreshable#get()} will return this value.
     */
    void update(T value);
}
