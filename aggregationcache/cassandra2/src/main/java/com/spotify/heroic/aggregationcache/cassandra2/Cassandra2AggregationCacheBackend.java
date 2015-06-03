package com.spotify.heroic.aggregationcache.cassandra2;

import java.util.List;

import javax.inject.Inject;

import lombok.RequiredArgsConstructor;
import lombok.Synchronized;
import lombok.ToString;

import com.netflix.astyanax.Serializer;
import com.netflix.astyanax.model.ColumnFamily;
import com.netflix.astyanax.serializers.IntegerSerializer;
import com.netflix.astyanax.serializers.StringSerializer;
import com.spotify.heroic.aggregationcache.AggregationCacheBackend;
import com.spotify.heroic.aggregationcache.CacheOperationException;
import com.spotify.heroic.aggregationcache.model.CacheBackendGetResult;
import com.spotify.heroic.aggregationcache.model.CacheBackendKey;
import com.spotify.heroic.aggregationcache.model.CacheBackendPutResult;
import com.spotify.heroic.concurrrency.ReadWriteThreadPools;
import com.spotify.heroic.model.CacheKey;
import com.spotify.heroic.model.DataPoint;
import com.spotify.heroic.model.DateRange;
import com.spotify.heroic.statistics.AggregationCacheBackendReporter;

import eu.toolchain.async.AsyncFramework;
import eu.toolchain.async.AsyncFuture;
import eu.toolchain.async.Managed;
import eu.toolchain.async.ManagedAction;

@RequiredArgsConstructor
@ToString(exclude = { "context" })
public class Cassandra2AggregationCacheBackend implements AggregationCacheBackend {
    public static final int WIDTH = 1200;

    @Inject
    private ReadWriteThreadPools pool;

    @Inject
    private Serializer<CacheKey> cacheKeySerializer;

    @Inject
    private AggregationCacheBackendReporter reporter;

    @Inject
    private AsyncFramework async;

    @Inject
    private Managed<Context> context;

    private final ColumnFamily<Integer, String> CQL3_CF = ColumnFamily.newColumnFamily("Cql3CF",
            IntegerSerializer.get(), StringSerializer.get());

    @Override
    public AsyncFuture<CacheBackendGetResult> get(final CacheBackendKey key, final DateRange range)
            throws CacheOperationException {
        return context.doto(new ManagedAction<Context, CacheBackendGetResult>() {
            @Override
            public AsyncFuture<CacheBackendGetResult> action(Context ctx) throws Exception {
                return async.call(new CacheGetResolver(cacheKeySerializer, ctx, CQL3_CF, key, range), pool.read());
            }
        });
    }

    @Override
    public AsyncFuture<CacheBackendPutResult> put(final CacheBackendKey key, final List<DataPoint> datapoints)
            throws CacheOperationException {
        return context.doto(new ManagedAction<Context, CacheBackendPutResult>() {
            @Override
            public AsyncFuture<CacheBackendPutResult> action(Context ctx) throws Exception {
                return async.call(new CachePutResolver(cacheKeySerializer, ctx, CQL3_CF, key, datapoints), pool.write());
            }
        });
    }

    @Override
    @Synchronized
    public AsyncFuture<Void> start() throws Exception {
        return context.start();
    }

    @Override
    @Synchronized
    public AsyncFuture<Void> stop() throws Exception {
        return context.stop();
    }

    @Override
    public boolean isReady() {
        return context.isReady();
    }
}
