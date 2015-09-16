/*
 * Copyright (c) 2015 Spotify AB.
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package com.spotify.heroic.model;

import java.util.Comparator;

import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode
public class DataPoint implements TimeData {
    private final long timestamp;
    private final double value;

    @Override
    public int compareTo(TimeData o) {
        return Long.compare(timestamp, o.getTimestamp());
    }

    @Override
    public int hash() {
        return 0;
    }

    @Override
    public boolean valid() {
        return !Double.isNaN(value);
    }

    static final Comparator<TimeData> comparator = new Comparator<TimeData>() {
        @Override
        public int compare(TimeData a, TimeData b) {
            return Long.compare(a.getTimestamp(), b.getTimestamp());
        }
    };

    public static Comparator<TimeData> comparator() {
        return comparator;
    }
}