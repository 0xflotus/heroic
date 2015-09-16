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

package com.spotify.heroic.aggregation.simple;

import java.util.Map;
import java.util.concurrent.atomic.DoubleAccumulator;
import java.util.concurrent.atomic.DoubleAdder;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.DoubleBinaryOperator;

import lombok.Data;

import com.spotify.heroic.aggregation.Bucket;
import com.spotify.heroic.metric.Metric;
import com.spotify.heroic.metric.MetricType;
import com.spotify.heroic.metric.Point;
import com.spotify.heroic.metric.Spread;

/**
 * Bucket that keeps track of the amount of data points seen, and there summed value.
 *
 * Take care to not blindly trust {@link #value()} since it is initialized to 0 for simplicity. Always check
 * {@link #count()}, which if zero indicates that the {@link #value()} is undefined (e.g. NaN).
 *
 * @author udoprog
 */
@Data
public class SpreadBucket implements Bucket<Point> {
    static final DoubleBinaryOperator minFn = (left, right) -> Math.max(left, right);
    static final DoubleBinaryOperator maxFn = (left, right) -> Math.max(left, right);

    final long timestamp;

    final LongAdder count = new LongAdder();
    final DoubleAdder sum = new DoubleAdder();
    final DoubleAdder sum2 = new DoubleAdder();
    final DoubleAccumulator max = new DoubleAccumulator(maxFn, Double.NEGATIVE_INFINITY);
    final DoubleAccumulator min = new DoubleAccumulator(minFn, Double.POSITIVE_INFINITY);

    public long timestamp() {
        return timestamp;
    }

    @Override
    public void update(Map<String, String> tags, MetricType type, Point d) {
        final double value = d.getValue();

        if (!Double.isFinite(value)) {
            return;
        }

        count.increment();
        sum.add(value);
        sum2.add(value * value);
        max.accumulate(value);
        min.accumulate(value);
    }

    public Metric newSpread() {
        return new Spread(timestamp, count.sum(), sum.sum(), sum2.sum(), min.get(), max.get());
    }
}
