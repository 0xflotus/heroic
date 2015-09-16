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

package com.spotify.heroic.metric.memory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import javax.inject.Inject;
import javax.inject.Named;

import lombok.Data;
import lombok.Getter;
import lombok.ToString;

import com.google.common.collect.ImmutableList;
import com.spotify.heroic.common.DateRange;
import com.spotify.heroic.common.LifeCycle;
import com.spotify.heroic.common.Series;
import com.spotify.heroic.metric.AbstractMetricBackend;
import com.spotify.heroic.metric.BackendEntry;
import com.spotify.heroic.metric.BackendKey;
import com.spotify.heroic.metric.FetchData;
import com.spotify.heroic.metric.FetchQuotaWatcher;
import com.spotify.heroic.metric.Metric;
import com.spotify.heroic.metric.MetricBackend;
import com.spotify.heroic.metric.MetricType;
import com.spotify.heroic.metric.MetricTypedGroup;
import com.spotify.heroic.metric.WriteMetric;
import com.spotify.heroic.metric.WriteResult;

import eu.toolchain.async.AsyncFramework;
import eu.toolchain.async.AsyncFuture;

/**
 * MetricBackend for Heroic cassandra datastore.
 */
@ToString
public class MemoryBackend extends AbstractMetricBackend implements LifeCycle {
    private static final List<BackendEntry> EMPTY_ENTRIES = new ArrayList<>();

    private final ConcurrentMap<MemoryKey, NavigableMap<Long, Metric>> storage = new ConcurrentHashMap<>();

    private final Object $create = new Object();

    @Inject
    @Getter
    private AsyncFramework async;

    @Inject
    @Named("groups")
    private Set<String> groups;

    @Override
    public AsyncFuture<Void> start() throws Exception {
        return async.resolved();
    }

    @Override
    public AsyncFuture<Void> stop() throws Exception {
        return async.resolved();
    }

    @Override
    public AsyncFuture<Void> configure() {
        return async.resolved();
    }

    @Override
    public Set<String> getGroups() {
        return groups;
    }

    @Override
    public AsyncFuture<WriteResult> write(WriteMetric write) {
        final long start = System.nanoTime();
        final List<Long> times = new ArrayList<>();
        writeOne(times, write, start);
        return async.resolved(WriteResult.of(times));
    }

    @Override
    public AsyncFuture<WriteResult> write(Collection<WriteMetric> writes) {
        final List<Long> times = new ArrayList<>(writes.size());

        for (final WriteMetric write : writes) {
            final long start = System.nanoTime();
            writeOne(times, write, start);
        }

        return async.resolved(WriteResult.of(times));
    }

    @Override
    public AsyncFuture<FetchData> fetch(MetricType source, Series series, DateRange range,
            FetchQuotaWatcher watcher) {
        final long start = System.nanoTime();
        final MemoryKey key = new MemoryKey(source, series);
        final List<MetricTypedGroup> groups = doFetch(key, range);
        final ImmutableList<Long> times = ImmutableList.of(System.nanoTime() - start);
        return async.resolved(new FetchData(series, times, groups));
    }

    @Override
    public AsyncFuture<List<BackendKey>> keys(BackendKey start, BackendKey end, int limit) {
        return async.resolved((List<BackendKey>) new ArrayList<BackendKey>());
    }

    @Override
    public boolean isReady() {
        return true;
    }

    @Override
    public Iterable<BackendEntry> listEntries() {
        return EMPTY_ENTRIES;
    }

    @Data
    public static final class MemoryKey {
        private final MetricType source;
        private final Series series;
    }

    private void writeOne(final List<Long> times, final WriteMetric write, final long start) {
        for (final MetricTypedGroup g : write.getGroups()) {
            final MemoryKey key = new MemoryKey(g.getType(), write.getSeries());
            final NavigableMap<Long, Metric> tree = getOrCreate(key);

            synchronized (tree) {
                for (final Metric d : g.getData()) {
                    tree.put(d.getTimestamp(), d);
                }
            }
        }

        times.add(System.nanoTime() - start);
    }

    private List<MetricTypedGroup> doFetch(final MemoryKey key, DateRange range) {
        final NavigableMap<Long, Metric> tree = storage.get(key);

        if (tree == null) {
            return ImmutableList.of(new MetricTypedGroup(key.getSource(), ImmutableList.of()));
        }

        synchronized (tree) {
            final Iterable<Metric> data = tree.subMap(range.getStart(), range.getEnd()).values();
            return ImmutableList.of(new MetricTypedGroup(key.getSource(), ImmutableList.copyOf(data)));
        }
    }

    /**
     * Get or create a new navigable map to store time data.
     *
     * @param key The key to create the map under.
     * @return An existing, or a newly created navigable map for the given key.
     */
    private NavigableMap<Long, Metric> getOrCreate(final MemoryKey key) {
        final NavigableMap<Long, Metric> tree = storage.get(key);

        if (tree != null)
            return tree;

        synchronized ($create) {
            final NavigableMap<Long, Metric> checked = storage.get(key);

            if (checked != null)
                return checked;

            final NavigableMap<Long, Metric> created = new TreeMap<>();
            storage.put(key, created);
            return created;
        }
    }
}