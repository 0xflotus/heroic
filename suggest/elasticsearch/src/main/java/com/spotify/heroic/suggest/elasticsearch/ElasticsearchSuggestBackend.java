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

package com.spotify.heroic.suggest.elasticsearch;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;

import lombok.RequiredArgsConstructor;
import lombok.ToString;

import org.apache.commons.lang3.time.StopWatch;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.ListenableActionFuture;
import org.elasticsearch.action.bulk.BulkProcessor;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.action.index.IndexRequest.OpType;
import org.elasticsearch.action.search.SearchRequestBuilder;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.action.search.SearchType;
import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentFactory;
import org.elasticsearch.index.query.BoolQueryBuilder;
import org.elasticsearch.index.query.FilterBuilder;
import org.elasticsearch.index.query.QueryBuilder;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.SearchHits;
import org.elasticsearch.search.aggregations.AggregationBuilders;
import org.elasticsearch.search.aggregations.bucket.terms.StringTerms;
import org.elasticsearch.search.aggregations.bucket.terms.Terms;
import org.elasticsearch.search.aggregations.bucket.terms.Terms.Bucket;
import org.elasticsearch.search.aggregations.bucket.terms.Terms.Order;
import org.elasticsearch.search.aggregations.bucket.terms.TermsBuilder;
import org.elasticsearch.search.aggregations.metrics.cardinality.Cardinality;
import org.elasticsearch.search.aggregations.metrics.cardinality.CardinalityBuilder;
import org.elasticsearch.search.aggregations.metrics.max.MaxBuilder;
import org.elasticsearch.search.aggregations.metrics.tophits.TopHits;
import org.elasticsearch.search.aggregations.metrics.tophits.TopHitsBuilder;

import com.google.common.collect.ImmutableList;
import com.google.inject.Inject;
import com.spotify.heroic.elasticsearch.Connection;
import com.spotify.heroic.elasticsearch.ElasticsearchUtils;
import com.spotify.heroic.elasticsearch.RateLimitExceededException;
import com.spotify.heroic.elasticsearch.RateLimitedCache;
import com.spotify.heroic.elasticsearch.index.NoIndexSelectedException;
import com.spotify.heroic.filter.Filter;
import com.spotify.heroic.injection.LifeCycle;
import com.spotify.heroic.metric.model.WriteResult;
import com.spotify.heroic.model.DateRange;
import com.spotify.heroic.model.RangeFilter;
import com.spotify.heroic.model.Series;
import com.spotify.heroic.statistics.LocalMetadataBackendReporter;
import com.spotify.heroic.suggest.SuggestBackend;
import com.spotify.heroic.suggest.model.KeySuggest;
import com.spotify.heroic.suggest.model.MatchOptions;
import com.spotify.heroic.suggest.model.TagKeyCount;
import com.spotify.heroic.suggest.model.TagSuggest;
import com.spotify.heroic.suggest.model.TagSuggest.Suggestion;
import com.spotify.heroic.suggest.model.TagValueSuggest;
import com.spotify.heroic.suggest.model.TagValuesSuggest;
import com.spotify.heroic.utils.Grouped;

import eu.toolchain.async.AsyncFramework;
import eu.toolchain.async.AsyncFuture;
import eu.toolchain.async.Borrowed;
import eu.toolchain.async.Managed;
import eu.toolchain.async.ManagedAction;
import eu.toolchain.async.ResolvableFuture;
import eu.toolchain.async.Transform;

@RequiredArgsConstructor
@ToString(of = { "connection" })
public class ElasticsearchSuggestBackend implements SuggestBackend, LifeCycle, Grouped {
    private static final StandardAnalyzer analyzer = new StandardAnalyzer();
    public static final TimeValue TIMEOUT = TimeValue.timeValueMillis(10000);

    @Inject
    private AsyncFramework async;

    @Inject
    private Managed<Connection> connection;

    @Inject
    private LocalMetadataBackendReporter reporter;

    /**
     * prevent unnecessary writes if entry is already in cache. Integer is the hashCode of the series.
     */
    @Inject
    private RateLimitedCache<Pair<String, Series>, Boolean> writeCache;

    private final Set<String> groups;

    // different locations for the series used in filtering.
    private final ElasticsearchUtils.FilterContext SERIES_CTX = ElasticsearchUtils.context();
    private final ElasticsearchUtils.FilterContext TAG_CTX = ElasticsearchUtils.context(ElasticsearchUtils.TAG_SERIES);

    private final String[] KEY_SUGGEST_SOURCES = new String[] { ElasticsearchUtils.SERIES_KEY_RAW };

    private static final String[] TAG_SUGGEST_SOURCES = new String[] { ElasticsearchUtils.TAG_KEY,
            ElasticsearchUtils.TAG_VALUE };


    @Override
    public AsyncFuture<Void> start() throws Exception {
        return connection.start();
    }

    @Override
    public AsyncFuture<Void> stop() throws Exception {
        return connection.stop();
    }

    @Override
    public Set<String> getGroups() {
        return groups;
    }

    @Override
    public boolean isReady() {
        return connection.isReady();
    }

    private <R> AsyncFuture<R> doto(ManagedAction<Connection, R> action) {
        return connection.doto(action);
    }

    @Override
    public AsyncFuture<TagValuesSuggest> tagValuesSuggest(final RangeFilter filter, final List<String> exclude,
            final int groupLimit) {
        return doto(new ManagedAction<Connection, TagValuesSuggest>() {
            @Override
            public AsyncFuture<TagValuesSuggest> action(final Connection c) throws Exception {
                final FilterBuilder f = TAG_CTX.filter(filter.getFilter());

                final BoolQueryBuilder root = QueryBuilders.boolQuery();
                root.must(QueryBuilders.filteredQuery(QueryBuilders.matchAllQuery(), f));

                if (!exclude.isEmpty()) {
                    for (final String e : exclude) {
                        root.mustNot(QueryBuilders.matchQuery(ElasticsearchUtils.TAG_KEY_RAW, e));
                    }
                }

                final SearchRequestBuilder request;

                try {
                    request = c.search(filter.getRange(), ElasticsearchUtils.TYPE_TAG).setSearchType(SearchType.COUNT)
                            .setQuery(root);
                } catch (NoIndexSelectedException e) {
                    return async.failed(e);
                }

                {
                    final TermsBuilder terms = AggregationBuilders.terms("keys").field(ElasticsearchUtils.TAG_KEY_RAW)
                            .size(filter.getLimit() + 1);
                    request.addAggregation(terms);
                    // make value bucket one entry larger than necessary to figure out when limiting is applied.
                    final TermsBuilder cardinality = AggregationBuilders.terms("values")
                            .field(ElasticsearchUtils.TAG_VALUE_RAW).size(groupLimit + 1);
                    terms.subAggregation(cardinality);
                }

                return bind(request.execute(), new Transform<SearchResponse, TagValuesSuggest>() {
                    @Override
                    public TagValuesSuggest transform(SearchResponse response) throws Exception {
                        final List<TagValuesSuggest.Suggestion> suggestions = new ArrayList<>();

                        final Terms terms = (Terms) response.getAggregations().get("keys");

                        final List<Bucket> suggestionBuckets = terms.getBuckets();

                        for (final Terms.Bucket bucket : suggestionBuckets.subList(0,
                                Math.min(suggestionBuckets.size(), filter.getLimit()))) {
                            final Terms valueTerms = bucket.getAggregations().get("values");

                            final List<Bucket> valueBuckets = valueTerms.getBuckets();

                            final SortedSet<String> result = new TreeSet<>();

                            for (final Terms.Bucket valueBucket : valueBuckets) {
                                result.add(valueBucket.getKey());
                            }

                            final boolean limited = valueBuckets.size() > groupLimit;

                            final ImmutableList<String> values = ImmutableList.copyOf(result).subList(0,
                                    Math.min(groupLimit, result.size()));

                            suggestions.add(new TagValuesSuggest.Suggestion(bucket.getKey(), values, limited));
                        }

                        return new TagValuesSuggest(new ArrayList<>(suggestions), suggestionBuckets.size() > filter
                                .getLimit());
                    }
                });
            }
        });
    }

    @Override
    public AsyncFuture<TagValueSuggest> tagValueSuggest(final RangeFilter filter, final String key) {
        return doto(new ManagedAction<Connection, TagValueSuggest>() {
            @Override
            public AsyncFuture<TagValueSuggest> action(final Connection c) throws Exception {
                final BoolQueryBuilder root = QueryBuilders.boolQuery();

                if (key != null && !key.isEmpty()) {
                    root.must(QueryBuilders.termQuery(ElasticsearchUtils.TAG_KEY_RAW, key));
                }

                root.must(QueryBuilders.filteredQuery(QueryBuilders.matchAllQuery(), TAG_CTX.filter(filter.getFilter())));

                final SearchRequestBuilder request;

                try {
                    request = c.search(filter.getRange(), ElasticsearchUtils.TYPE_TAG).setSearchType(SearchType.COUNT)
                            .setQuery(root);
                } catch (NoIndexSelectedException e) {
                    return async.failed(e);
                }

                {
                    final TermsBuilder terms = AggregationBuilders.terms("values")
                            .field(ElasticsearchUtils.TAG_VALUE_RAW).size(filter.getLimit() + 1)
                            .order(Order.term(true));
                    request.addAggregation(terms);
                }

                return bind(request.execute(), new Transform<SearchResponse, TagValueSuggest>() {
                    @Override
                    public TagValueSuggest transform(SearchResponse response) throws Exception {
                        final List<String> suggestions = new ArrayList<>();

                        final Terms terms = (Terms) response.getAggregations().get("values");

                        final List<Bucket> buckets = terms.getBuckets();

                        for (final Terms.Bucket bucket : buckets.subList(0, Math.min(buckets.size(), filter.getLimit())))
                            suggestions.add(bucket.getKey());

                        boolean limited = buckets.size() > filter.getLimit();

                        return new TagValueSuggest(new ArrayList<>(suggestions), limited);
                    }
                });
            }
        });
    }

    @Override
    public AsyncFuture<TagKeyCount> tagKeyCount(final RangeFilter filter) {
        return doto(new ManagedAction<Connection, TagKeyCount>() {
            @Override
            public AsyncFuture<TagKeyCount> action(final Connection c) throws Exception {
                final FilterBuilder f = TAG_CTX.filter(filter.getFilter());

                final BoolQueryBuilder root = QueryBuilders.boolQuery();
                root.must(QueryBuilders.filteredQuery(QueryBuilders.matchAllQuery(), f));

                final SearchRequestBuilder request;

                try {
                    request = c.search(filter.getRange(), ElasticsearchUtils.TYPE_TAG).setSearchType(SearchType.COUNT)
                            .setQuery(root);
                } catch (NoIndexSelectedException e) {
                    return async.failed(e);
                }

                {
                    final TermsBuilder terms = AggregationBuilders.terms("keys").field(ElasticsearchUtils.TAG_KEY_RAW)
                            .size(filter.getLimit());
                    request.addAggregation(terms);
                    final CardinalityBuilder cardinality = AggregationBuilders.cardinality("cardinality").field(
                            ElasticsearchUtils.TAG_VALUE_RAW);
                    terms.subAggregation(cardinality);
                }

                return bind(request.execute(), new Transform<SearchResponse, TagKeyCount>() {
                    @Override
                    public TagKeyCount transform(SearchResponse response) throws Exception {
                        final Set<TagKeyCount.Suggestion> suggestions = new LinkedHashSet<>();

                        final Terms terms = (Terms) response.getAggregations().get("keys");

                        for (final Terms.Bucket bucket : terms.getBuckets()) {
                            final Cardinality cardinality = bucket.getAggregations().get("cardinality");
                            suggestions.add(new TagKeyCount.Suggestion(bucket.getKey(), cardinality.getValue()));
                        }

                        return new TagKeyCount(new ArrayList<>(suggestions), false);
                    }
                });
            }
        });
    }

    @Override
    public AsyncFuture<TagSuggest> tagSuggest(final RangeFilter filter, final MatchOptions options, final String key,
            final String value) {
        return doto(new ManagedAction<Connection, TagSuggest>() {
            @Override
            public AsyncFuture<TagSuggest> action(final Connection c) throws Exception {
                final QueryBuilder query;

                final BoolQueryBuilder fuzzy = QueryBuilders.boolQuery();

                if (key != null && !key.isEmpty())
                    try {
                        fuzzy.should(match(ElasticsearchUtils.TAG_KEY, key, options));
                    } catch (IOException e) {
                        return async.failed(e);
                    }

                if (value != null && !value.isEmpty())
                    try {
                        fuzzy.should(match(ElasticsearchUtils.TAG_VALUE, value, options));
                    } catch (IOException e) {
                        return async.failed(e);
                    }

                if (filter.getFilter() instanceof Filter.True) {
                    query = fuzzy;
                } else {
                    query = QueryBuilders.filteredQuery(fuzzy, TAG_CTX.filter(filter.getFilter()));
                }

                final SearchRequestBuilder request;

                try {
                    request = c.search(filter.getRange(), ElasticsearchUtils.TYPE_TAG).setSearchType(SearchType.COUNT)
                            .setQuery(query);
                } catch (NoIndexSelectedException e) {
                    return async.failed(e);
                }

                // aggregation
                {
                    final MaxBuilder topHit = AggregationBuilders.max("topHit").script("_score");
                    final TopHitsBuilder hits = AggregationBuilders.topHits("hits").setSize(1)
                            .setFetchSource(TAG_SUGGEST_SOURCES, new String[0]);

                    final TermsBuilder kvs = AggregationBuilders.terms("kvs").field(ElasticsearchUtils.TAG_KV)
                            .size(filter.getLimit()).order(Order.aggregation("topHit", false)).subAggregation(hits)
                            .subAggregation(topHit);

                    request.addAggregation(kvs);
                }

                return bind(request.execute(), new Transform<SearchResponse, TagSuggest>() {
                    @Override
                    public TagSuggest transform(SearchResponse response) throws Exception {
                        final Set<Suggestion> suggestions = new LinkedHashSet<>();

                        final StringTerms kvs = (StringTerms) response.getAggregations().get("kvs");

                        for (final Terms.Bucket bucket : kvs.getBuckets()) {
                            final TopHits topHits = (TopHits) bucket.getAggregations().get("hits");
                            final SearchHits hits = topHits.getHits();
                            final SearchHit hit = hits.getAt(0);
                            final Map<String, Object> doc = hit.getSource();

                            final String key = (String) doc.get(ElasticsearchUtils.TAG_KEY);
                            final String value = (String) doc.get(ElasticsearchUtils.TAG_VALUE);
                            suggestions.add(new Suggestion(hits.getMaxScore(), key, value));
                        }

                        return new TagSuggest(new ArrayList<>(suggestions));
                    }
                });
            }
        });
    }

    @Override
    public AsyncFuture<KeySuggest> keySuggest(final RangeFilter filter, final MatchOptions options, final String key) {
        return doto(new ManagedAction<Connection, KeySuggest>() {
            @Override
            public AsyncFuture<KeySuggest> action(final Connection c) throws Exception {
                final QueryBuilder query;

                final BoolQueryBuilder fuzzy = QueryBuilders.boolQuery();

                if (key != null && !key.isEmpty())
                    try {
                        fuzzy.should(match(ElasticsearchUtils.SERIES_KEY, key, options));
                    } catch (IOException e) {
                        return async.failed(e);
                    }

                if (filter instanceof Filter.True) {
                    query = fuzzy;
                } else {
                    query = QueryBuilders.filteredQuery(fuzzy, SERIES_CTX.filter(filter.getFilter()));
                }

                final SearchRequestBuilder request;

                try {
                    request = c.search(filter.getRange(), ElasticsearchUtils.TYPE_SERIES)
                            .setSearchType(SearchType.COUNT).setQuery(query);
                } catch (NoIndexSelectedException e) {
                    return async.failed(e);
                }

                // aggregation
                {
                    final MaxBuilder topHit = AggregationBuilders.max("top_hit").script("_score");
                    final TopHitsBuilder hits = AggregationBuilders.topHits("hits").setSize(1)
                            .setFetchSource(KEY_SUGGEST_SOURCES, new String[0]);

                    final TermsBuilder keys = AggregationBuilders.terms("keys")
                            .field(ElasticsearchUtils.SERIES_KEY_RAW).size(filter.getLimit())
                            .order(Order.aggregation("top_hit", false)).subAggregation(hits).subAggregation(topHit);

                    request.addAggregation(keys);
                }

                return bind(request.execute(), new Transform<SearchResponse, KeySuggest>() {
                    @Override
                    public KeySuggest transform(SearchResponse response) throws Exception {
                        final Set<KeySuggest.Suggestion> suggestions = new LinkedHashSet<>();

                        final StringTerms keys = (StringTerms) response.getAggregations().get("keys");

                        for (final Terms.Bucket bucket : keys.getBuckets()) {
                            final TopHits topHits = (TopHits) bucket.getAggregations().get("hits");
                            final SearchHits hits = topHits.getHits();
                            suggestions.add(new KeySuggest.Suggestion(hits.getMaxScore(), bucket.getKey()));
                        }

                        return new KeySuggest(new ArrayList<>(suggestions));
                    }
                });
            }
        });
    }

    @Override
    public void writeDirect(Series series, DateRange range) throws Exception {
        try (final Borrowed<Connection> b = connection.borrow()) {
            final Connection c = b.get();

            final String[] indices = c.writeIndices(range);

            final String seriesId = Integer.toHexString(series.hashCode());

            final XContentBuilder xSeries = XContentFactory.jsonBuilder();
            final BytesReference rawSeries;

            xSeries.startObject();
            ElasticsearchUtils.buildMetadataDoc(xSeries, series);
            xSeries.endObject();

            // for nested entry in suggestion.
            final XContentBuilder xSeriesRaw = XContentFactory.jsonBuilder();
            xSeriesRaw.startObject();
              xSeriesRaw.field("id", seriesId);
              ElasticsearchUtils.buildMetadataDoc(xSeriesRaw, series);
            xSeriesRaw.endObject();

            rawSeries = xSeriesRaw.bytes();
            // @formatter:on

            final BulkProcessor bulk = c.bulk();

            final List<Long> times = new ArrayList<>(indices.length);

            for (final String index : indices) {
                bulk.add(new IndexRequest(index, ElasticsearchUtils.TYPE_SERIES, seriesId).source(xSeries)
                        .opType(OpType.CREATE));

                for (final Map.Entry<String, String> e : series.getTags().entrySet()) {
                    final String suggestId = seriesId + ":" + Integer.toHexString(e.hashCode());
                    final XContentBuilder suggest = XContentFactory.jsonBuilder();

                    suggest.startObject();
                    ElasticsearchUtils.buildTagDoc(suggest, rawSeries, e);
                    suggest.endObject();

                    bulk.add(new IndexRequest(index, ElasticsearchUtils.TYPE_TAG, suggestId).source(suggest)
                            .opType(OpType.CREATE));
                }
            }
        }
    }

    @Override
    public AsyncFuture<WriteResult> write(final Series series, final DateRange range) {
        try (final Borrowed<Connection> b = connection.borrow()) {
            if (!b.isValid())
                return async.cancelled();

            final Connection c = b.get();

            final String[] indices;

            try {
                indices = c.writeIndices(range);
            } catch (NoIndexSelectedException e) {
                return async.failed(e);
            }

            final String seriesId = Integer.toHexString(series.hashCode());

            final XContentBuilder xSeries;
            final BytesReference rawSeries;

            try {
                // convert to bytes, to avoid having to rebuild it for every write.
                // @formatter:off
                xSeries = XContentFactory.jsonBuilder();
                xSeries.startObject();
                ElasticsearchUtils.buildMetadataDoc(xSeries, series);
                xSeries.endObject();

                // for nested entry in suggestion.
                final XContentBuilder xSeriesRaw = XContentFactory.jsonBuilder();
                xSeriesRaw.startObject();
                  xSeriesRaw.field("id", seriesId);
                  ElasticsearchUtils.buildMetadataDoc(xSeriesRaw, series);
                xSeriesRaw.endObject();

                rawSeries = xSeriesRaw.bytes();
                // @formatter:on
            } catch (IOException e) {
                return async.failed(e);
            }

            final BulkProcessor bulk = c.bulk();

            final List<Long> times = new ArrayList<>(indices.length);

            for (final String index : indices) {
                final StopWatch watch = new StopWatch();

                watch.start();

                final Pair<String, Series> key = Pair.of(index, series);

                final Callable<Boolean> loader = new Callable<Boolean>() {
                    @Override
                    public Boolean call() throws Exception {
                        bulk.add(new IndexRequest(index, ElasticsearchUtils.TYPE_SERIES, seriesId).source(xSeries)
                                .opType(OpType.CREATE));

                        for (final Map.Entry<String, String> e : series.getTags().entrySet()) {
                            final String suggestId = seriesId + ":" + Integer.toHexString(e.hashCode());
                            final XContentBuilder suggest = XContentFactory.jsonBuilder();

                            suggest.startObject();
                            ElasticsearchUtils.buildTagDoc(suggest, rawSeries, e);
                            suggest.endObject();

                            bulk.add(new IndexRequest(index, ElasticsearchUtils.TYPE_TAG, suggestId).source(suggest)
                                    .opType(OpType.CREATE));
                        }

                        return true;
                    }
                };

                try {
                    writeCache.get(key, loader);
                } catch (ExecutionException e) {
                    return async.failed(e);
                } catch (RateLimitExceededException e) {
                    reporter.reportWriteDroppedByRateLimit();
                }

                watch.stop();
                times.add(watch.getNanoTime());
            }

            return async.resolved(WriteResult.of(times));
        }
    }

    private <S, T> AsyncFuture<T> bind(final ListenableActionFuture<S> actionFuture, final Transform<S, T> transform) {
        final ResolvableFuture<T> future = async.future();

        actionFuture.addListener(new ActionListener<S>() {
            @Override
            public void onResponse(S response) {
                final T result;

                try {
                    result = transform.transform(response);
                } catch (Exception e) {
                    future.fail(e);
                    return;
                }

                future.resolve(result);
            }

            @Override
            public void onFailure(Throwable e) {
                future.fail(e);
            }
        });

        return future;
    }

    private QueryBuilder match(String field, String value, MatchOptions options) throws IOException {
        final BoolQueryBuilder bool = QueryBuilders.boolQuery();

        // exact match
        bool.should(QueryBuilders.termQuery(field, value));

        final List<String> terms;

        try {
            terms = ElasticsearchUtils.tokenize(analyzer, field, value);
        } catch (IOException e) {
            throw new IOException("failed to tokenize query", e);
        }

        for (final String term : terms) {
            // prefix on raw to match with non-term prefixes.
            bool.should(QueryBuilders.prefixQuery(String.format("%s.raw", field), term));
            // prefix on terms, to match on the prefix of any term.
            bool.should(QueryBuilders.prefixQuery(field, term));
            // prefix on exact term matches.
            bool.should(QueryBuilders.termQuery(field, term));
        }

        // optionall match fuzzy
        if (options.isFuzzy())
            bool.should(QueryBuilders.fuzzyQuery(field, value).prefixLength(options.getFuzzyPrefixLength())
                    .maxExpansions(options.getFuzzyMaxExpansions()));

        return bool;
    }
}
