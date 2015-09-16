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
import com.google.common.base.Optional;
import com.spotify.heroic.aggregation.AggregationContext;
import com.spotify.heroic.aggregation.AggregationQuery;
import com.spotify.heroic.aggregation.SimpleSamplingQuery;
import com.spotify.heroic.common.Sampling;

import lombok.Data;

@Data
public class QuantileAggregationQuery implements AggregationQuery {
    public static final double DEFAULT_QUANTILE = 0.5;
    public static final double DEFAULT_ERROR = 0.01;

    private final Optional<Sampling> sampling;
    private final double q;
    private final double error;

    @JsonCreator
    public QuantileAggregationQuery(@JsonProperty("sampling") SimpleSamplingQuery sampling, @JsonProperty("q") Double q,
            @JsonProperty("error") Double error) {
        this.sampling = Optional.fromNullable(sampling).transform(SimpleSamplingQuery::build);
        this.q = Optional.fromNullable(q).or(DEFAULT_QUANTILE);
        this.error = Optional.fromNullable(error).or(DEFAULT_ERROR);
    }

    @Override
    public QuantileAggregation build(AggregationContext context) {
        return new QuantileAggregation(sampling.or(context.getSampling()).or(SimpleSamplingQuery.DEFAULT_SUPPLIER), q,
                error);
    }
}