package com.spotify.heroic.suggest.model;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.SortedSet;
import java.util.TreeSet;

import lombok.Data;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.spotify.heroic.cluster.ClusterNode;
import com.spotify.heroic.cluster.model.NodeMetadata;
import com.spotify.heroic.cluster.model.NodeRegistryEntry;
import com.spotify.heroic.metric.model.NodeError;
import com.spotify.heroic.metric.model.RequestError;

import eu.toolchain.async.Collector;
import eu.toolchain.async.Transform;

@Data
public class TagValueSuggest {
    public static final List<RequestError> EMPTY_ERRORS = new ArrayList<RequestError>();
    public static final List<String> EMPTY_VALUES = new ArrayList<String>();
    public static final boolean DEFAULT_LIMITED = false;

    public static final TagValueSuggest EMPTY = new TagValueSuggest(EMPTY_ERRORS, EMPTY_VALUES, DEFAULT_LIMITED);

    private final List<RequestError> errors;
    private final List<String> values;
    private final boolean limited;

    @JsonCreator
    public TagValueSuggest(@JsonProperty("errors") List<RequestError> errors,
            @JsonProperty("values") List<String> values, @JsonProperty("limited") Boolean limited) {
        this.errors = Optional.fromNullable(errors).or(EMPTY_ERRORS);
        this.values = values;
        this.limited = limited;
    }

    public TagValueSuggest(List<String> values, boolean limited) {
        this(EMPTY_ERRORS, values, limited);
    }

    public TagValueSuggest merge(TagValueSuggest other, int limit) {
        final List<RequestError> errors = new ArrayList<>(this.errors);
        errors.addAll(other.errors);

        final SortedSet<String> values = new TreeSet<>(this.values);
        values.addAll(other.values);

        final List<String> list = new ArrayList<>(values);

        final boolean limited = this.limited || other.limited || list.size() > limit;
        return new TagValueSuggest(errors, list.subList(0, Math.min(list.size(), limit)), limited);
    }

    public static Collector<TagValueSuggest, TagValueSuggest> reduce(final int limit) {
        return new Collector<TagValueSuggest, TagValueSuggest>() {
            @Override
            public TagValueSuggest collect(Collection<TagValueSuggest> results) throws Exception {
                TagValueSuggest result = EMPTY;

                for (final TagValueSuggest r : results)
                    result = r.merge(result, limit);

                return result;
            }
        };
    }

    public static Transform<Throwable, ? extends TagValueSuggest> nodeError(final NodeRegistryEntry node) {
        return new Transform<Throwable, TagValueSuggest>() {
            @Override
            public TagValueSuggest transform(Throwable e) throws Exception {
                final NodeMetadata m = node.getMetadata();
                final ClusterNode c = node.getClusterNode();
                return new TagValueSuggest(ImmutableList.<RequestError> of(NodeError.fromThrowable(m.getId(),
                        c.toString(), m.getTags(), e)), EMPTY_VALUES, DEFAULT_LIMITED);
            }
        };
    }
}