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

package com.spotify.heroic.aggregation;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.jsontype.NamedType;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.spotify.heroic.grammar.Value;

import eu.toolchain.serializer.SerialReader;
import eu.toolchain.serializer.SerialWriter;
import eu.toolchain.serializer.Serializer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Serializes aggregation configurations.
 *
 * Each aggregation configuration is packed into a Composite which has the type of the aggregation as a prefixed short.
 *
 * @author udoprog
 */
@RequiredArgsConstructor
@Slf4j
public class CoreAggregationRegistry implements AggregationSerializer, AggregationFactory {
    private final Serializer<String> string;

    private final Map<Class<? extends Aggregation>, String> queryTypes = new HashMap<>();
    private final Map<Class<? extends AggregationInstance>, String> types = new HashMap<>();
    private final Map<String, Serializer<? extends AggregationInstance>> serializers = new HashMap<>();
    private final Map<String, AggregationDSL> builders = new HashMap<>();

    @Override
    public <T extends Aggregation> void registerQuery(String id, Class<T> queryType) {
        if (queryTypes.put(queryType, id) != null) {
            throw new IllegalArgumentException("An aggregaiton query with the id '" + id + "' is already registered.");
        }
    }

    @Override
    public <T extends AggregationInstance> void register(String id, Class<T> clazz, Serializer<T> serializer,
            AggregationDSL builder) {
        if (types.put(clazz, id) != null) {
            throw new IllegalArgumentException("A type with the id '" + id + "' is already registered.");
        }

        serializers.put(id, serializer);
        builders.put(id, builder);
    }

    @Override
    public void serialize(SerialWriter buffer, AggregationInstance value) throws IOException {
        final String id = types.get(value.getClass());

        if (id == null)
            throw new RuntimeException("Type is not a serializable aggregate: " + value.getClass());

        string.serialize(buffer, id);

        final SerialWriter.Scope scope = buffer.scope();

        @SuppressWarnings("unchecked")
        final Serializer<AggregationInstance> serializer = (Serializer<AggregationInstance>) serializers.get(id);

        serializer.serialize(scope, value);
    }

    @Override
    public AggregationInstance deserialize(SerialReader buffer) throws IOException {
        final String id = string.deserialize(buffer);

        @SuppressWarnings("unchecked")
        final Serializer<AggregationInstance> serializer = (Serializer<AggregationInstance>) serializers.get(id);

        if (serializer == null) {
            buffer.skip();
            log.warn("Unknown aggregation type: " + id);
            return null;
        }

        return serializer.deserialize(buffer);
    }

    @Override
    public Aggregation build(String name, List<Value> args, Map<String, Value> keywords) {
        final AggregationDSL builder = builders.get(name);

        if (builder == null) {
            throw new IllegalArgumentException(String.format("no aggregation named %s", name));
        }

        try {
            return builder.build(args, keywords);
        } catch (Exception e) {
            throw new IllegalArgumentException("failed to build aggregation", e);
        }
    }

    public void configure(SimpleModule module) {
        for (final Map.Entry<Class<? extends AggregationInstance>, String> e : types.entrySet())
            module.registerSubtypes(new NamedType(e.getKey(), e.getValue()));

        for (final Map.Entry<Class<? extends Aggregation>, String> e : queryTypes.entrySet())
            module.registerSubtypes(new NamedType(e.getKey(), e.getValue()));
    }
}