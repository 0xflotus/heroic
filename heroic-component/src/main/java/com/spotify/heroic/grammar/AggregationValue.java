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

package com.spotify.heroic.grammar;

import java.util.List;
import java.util.Map;

import com.spotify.heroic.aggregation.Aggregation;
import com.spotify.heroic.aggregation.AggregationFactory;

import lombok.Data;

@ValueName("aggregation")
@Data
public class AggregationValue implements Value {
    private final String name;
    private final List<Value> arguments;
    private final Map<String, Value> keywordArguments;

    @Override
    public Value sub(Value other) {
        throw new IllegalArgumentException(String.format("%s: does not support subtraction", this));
    }

    @Override
    public Value add(Value other) {
        throw new IllegalArgumentException(String.format("%s: does not support addition", this));
    }

    public String toString() {
        return "<aggregation:" + name + ":" + arguments + ":" + keywordArguments + ">";
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T> T cast(T to) {
        if (to instanceof AggregationValue) {
            return (T) this;
        }

        throw new ValueCastException(this, to);
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T> T cast(Class<T> to) {
        if (to.isAssignableFrom(AggregationValue.class)) {
            return (T) this;
        }

        throw new ValueTypeCastException(this, to);
    }

    public Aggregation build(final AggregationFactory aggregations) {
        return aggregations.build(name, arguments, keywordArguments);
    }
}
