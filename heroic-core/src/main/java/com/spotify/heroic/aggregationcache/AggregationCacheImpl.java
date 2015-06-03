package com.spotify.heroic.aggregationcache;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.inject.Inject;

import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;

import com.spotify.heroic.aggregation.Aggregation;
import com.spotify.heroic.aggregationcache.model.CacheBackendGetResult;
import com.spotify.heroic.aggregationcache.model.CacheBackendKey;
import com.spotify.heroic.aggregationcache.model.CacheBackendPutResult;
import com.spotify.heroic.aggregationcache.model.CachePutResult;
import com.spotify.heroic.aggregationcache.model.CacheQueryResult;
import com.spotify.heroic.filter.Filter;
import com.spotify.heroic.model.DataPoint;
import com.spotify.heroic.model.DateRange;
import com.spotify.heroic.statistics.AggregationCacheReporter;

import eu.toolchain.async.AsyncFuture;
import eu.toolchain.async.Transform;

@NoArgsConstructor
public class AggregationCacheImpl implements AggregationCache {
    @Inject
    private AggregationCacheBackend backend;

    @Inject
    private AggregationCacheReporter reporter;

    @RequiredArgsConstructor
    private static final class BackendCacheGetHandle implements Transform<CacheBackendGetResult, CacheQueryResult> {
        private final AggregationCacheReporter reporter;
        private final DateRange range;

        @Override
        public CacheQueryResult transform(CacheBackendGetResult result) throws Exception {
            final CacheBackendKey key = result.getKey();

            final long width = key.getAggregation().sampling().getSize();

            final List<DateRange> misses = new ArrayList<DateRange>();

            final List<DataPoint> cached = result.getDatapoints();

            if (width == 0 || cached.isEmpty()) {
                misses.add(range);
                reporter.reportGetMiss(misses.size());
                return new CacheQueryResult(key, range, cached, misses);
            }

            final long end = range.getEnd();

            long current = range.getStart();

            for (final DataPoint d : cached) {
                if (current + width != d.getTimestamp() && current < d.getTimestamp())
                    misses.add(range.modify(current, d.getTimestamp()));

                current = d.getTimestamp();
            }

            if (current < end)
                misses.add(range.modify(current, end));

            reporter.reportGetMiss(misses.size());
            return new CacheQueryResult(key, range, cached, misses);
        }
    }

    @RequiredArgsConstructor
    private final class BackendCachePutHandle implements Transform<CacheBackendPutResult, CachePutResult> {
        @Override
        public CachePutResult transform(CacheBackendPutResult result) throws Exception {
            return new CachePutResult();
        }
    }

    @Override
    public boolean isConfigured() {
        return backend != null;
    }

    @Override
    public AsyncFuture<CacheQueryResult> get(Filter filter, Map<String, String> group, final Aggregation aggregation,
            DateRange range) throws CacheOperationException {
        if (!isConfigured())
            throw new CacheOperationException("Cache backend is not configured");

        final CacheBackendKey key = new CacheBackendKey(filter, group, aggregation);

        return backend.get(key, range).transform(new BackendCacheGetHandle(reporter, range));
    }

    @Override
    public AsyncFuture<CachePutResult> put(Filter filter, Map<String, String> group, Aggregation aggregation,
            List<DataPoint> datapoints) throws CacheOperationException {
        final CacheBackendKey key = new CacheBackendKey(filter, group, aggregation);

        if (!isConfigured())
            throw new CacheOperationException("Cache backend is not configured");

        return backend.put(key, datapoints).transform(new BackendCachePutHandle()).onAny(reporter.reportPut());
    }
}
