package com.spotify.heroic.metadata.elasticsearch;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import javax.inject.Named;
import javax.inject.Singleton;

import lombok.RequiredArgsConstructor;

import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentFactory;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Optional;
import com.google.inject.Inject;
import com.google.inject.Key;
import com.google.inject.Module;
import com.google.inject.PrivateModule;
import com.google.inject.Provides;
import com.spotify.heroic.concurrrency.ReadWriteThreadPools;
import com.spotify.heroic.elasticsearch.Connection;
import com.spotify.heroic.elasticsearch.ElasticsearchUtils;
import com.spotify.heroic.elasticsearch.ManagedConnectionFactory;
import com.spotify.heroic.metadata.MetadataBackend;
import com.spotify.heroic.metadata.MetadataModule;
import com.spotify.heroic.statistics.LocalMetadataBackendReporter;
import com.spotify.heroic.statistics.LocalMetadataManagerReporter;
import com.spotify.heroic.utils.GroupedUtils;

import eu.toolchain.async.AsyncFramework;
import eu.toolchain.async.Managed;

@RequiredArgsConstructor
public final class ElasticsearchMetadataModule implements MetadataModule {
    public static final String DEFAULT_GROUP = "elasticsearch";
    public static final String TEMPLATE_NAME = "heroic-metadata";

    private final String id;
    private final Set<String> groups;
    private final ManagedConnectionFactory connection;
    private final ReadWriteThreadPools.Config pools;

    @JsonCreator
    public ElasticsearchMetadataModule(@JsonProperty("id") String id, @JsonProperty("group") String group,
            @JsonProperty("groups") Set<String> groups,
            @JsonProperty("connection") ManagedConnectionFactory connection,
            @JsonProperty("pools") ReadWriteThreadPools.Config pools) throws Exception {
        this.id = id;
        this.groups = GroupedUtils.groups(group, groups, DEFAULT_GROUP);
        this.connection = Optional.fromNullable(connection).or(ManagedConnectionFactory.provideDefault());
        this.pools = Optional.fromNullable(pools).or(ReadWriteThreadPools.Config.provideDefault());
    }

    private static Map<String, XContentBuilder> mappings() throws IOException {
        final Map<String, XContentBuilder> mappings = new HashMap<>();
        mappings.put("metadata", buildMetadataMapping());
        return mappings;
    }

    private static XContentBuilder buildMetadataMapping() throws IOException {
        final XContentBuilder b = XContentFactory.jsonBuilder();

        // @formatter:off
        b.startObject();
          b.startObject(ElasticsearchUtils.TYPE_METADATA);
            b.startObject("properties");
              b.startObject(ElasticsearchUtils.SERIES_KEY);
                b.field("type", "string");
                b.startObject("fields");
                  b.startObject("raw");
                    b.field("type", "string");
                    b.field("index", "not_analyzed");
                    b.field("doc_values", true);
                  b.endObject();
                b.endObject();
              b.endObject();

              b.startObject(ElasticsearchUtils.SERIES_TAGS);
                b.field("type", "nested");
                b.startObject("properties");
                  b.startObject(ElasticsearchUtils.TAGS_KEY);
                    b.field("type", "string");
                    b.startObject("fields");
                      b.startObject("raw");
                        b.field("type", "string");
                        b.field("index", "not_analyzed");
                        b.field("doc_values", true);
                      b.endObject();
                    b.endObject();
                  b.endObject();

                  b.startObject(ElasticsearchUtils.TAGS_VALUE);
                    b.field("type", "string");
                    b.startObject("fields");
                      b.startObject("raw");
                        b.field("type", "string");
                        b.field("index", "not_analyzed");
                        b.field("doc_values", true);
                      b.endObject();
                    b.endObject();
                  b.endObject();
                b.endObject();
              b.endObject();
            b.endObject();
          b.endObject();
        b.endObject();
        // @formatter:on

        return b;
    }

    @Override
    public Module module(final Key<MetadataBackend> key, final String id) {
        return new PrivateModule() {
            @Provides
            @Singleton
            public LocalMetadataBackendReporter reporter(LocalMetadataManagerReporter reporter) {
                return reporter.newMetadataBackend(id);
            }

            @Provides
            @Singleton
            @Named("groups")
            public Set<String> groups() {
                return groups;
            }

            @Provides
            @Singleton
            public ReadWriteThreadPools pools(AsyncFramework async, LocalMetadataBackendReporter reporter) {
                return pools.construct(async, reporter.newThreadPool());
            }

            @Provides
            @Inject
            public Managed<Connection> connection(ManagedConnectionFactory builder) throws IOException {
                return builder.construct(TEMPLATE_NAME, mappings());
            }

            @Override
            protected void configure() {
                bind(ManagedConnectionFactory.class).toInstance(connection);
                bind(key).to(ElasticsearchMetadataBackend.class);
                expose(key);
            }
        };
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public String buildId(int i) {
        return String.format("elasticsearch#%d", i);
    }
}
