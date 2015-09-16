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

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.collect.ImmutableSet;
import com.spotify.heroic.aggregation.BucketAggregation;
import com.spotify.heroic.common.Sampling;
import com.spotify.heroic.metric.Metric;
import com.spotify.heroic.metric.MetricType;
import com.spotify.heroic.metric.Point;

import lombok.EqualsAndHashCode;
import lombok.ToString;

@ToString(callSuper = true)
@EqualsAndHashCode(callSuper = true, of = { "NAME" })
public class StdDevAggregation extends BucketAggregation<StripedStdDevBucket> {
    public static final String NAME = "stddev";

    public StdDevAggregation(Sampling sampling) {
        super(sampling, ImmutableSet.of(MetricType.POINT, MetricType.SPREAD), MetricType.POINT);
    }

    @JsonCreator
    public static StdDevAggregation create(@JsonProperty("sampling") Sampling sampling) {
        return new StdDevAggregation(sampling);
    }

    @Override
    protected StripedStdDevBucket buildBucket(long timestamp) {
        return new StripedStdDevBucket(timestamp);
    }

    @Override
    protected Metric build(StripedStdDevBucket bucket) {
        final double value = bucket.value();

        if (Double.isNaN(value)) {
            return Metric.invalid();
        }

        return new Point(bucket.timestamp(), value);
    }
}