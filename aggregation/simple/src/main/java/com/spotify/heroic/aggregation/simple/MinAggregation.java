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

import lombok.EqualsAndHashCode;
import lombok.ToString;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.spotify.heroic.aggregation.BucketAggregation;
import com.spotify.heroic.model.DataPoint;
import com.spotify.heroic.model.MetricType;
import com.spotify.heroic.model.Sampling;

@ToString(callSuper = true)
@EqualsAndHashCode(callSuper = true, of = { "NAME" })
public class MinAggregation extends BucketAggregation<DataPoint, StripedMinBucket> {
    public static final String NAME = "min";

    public MinAggregation(Sampling sampling) {
        super(sampling, DataPoint.class, MetricType.POINTS);
    }

    @JsonCreator
    public static MinAggregation create(@JsonProperty("sampling") Sampling sampling) {
        return new MinAggregation(sampling);
    }

    @Override
    protected StripedMinBucket buildBucket(long timestamp) {
        return new StripedMinBucket(timestamp);
    }

    @Override
    protected DataPoint build(StripedMinBucket bucket) {
        return new DataPoint(bucket.timestamp(), bucket.value());
    }
}