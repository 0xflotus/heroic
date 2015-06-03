package com.spotify.heroic.aggregation;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.inject.Inject;
import javax.inject.Named;

import com.spotify.heroic.HeroicContext;
import com.spotify.heroic.HeroicModule;
import com.spotify.heroic.grammar.AggregationValue;
import com.spotify.heroic.grammar.ListValue;
import com.spotify.heroic.grammar.Value;

import eu.toolchain.serializer.SerialReader;
import eu.toolchain.serializer.SerialWriter;
import eu.toolchain.serializer.Serializer;
import eu.toolchain.serializer.SerializerFramework;

public class Entry implements HeroicModule {
    @Inject
    private HeroicContext ctx;

    @Inject
    private AggregationSerializer aggregation;

    @Inject
    private AggregationFactory factory;

    @Inject
    @Named("common")
    private SerializerFramework s;

    @Override
    public void setup() {
        final Serializer<List<String>> list = s.list(s.string());
        final Serializer<List<Aggregation>> aggregations = s.list(aggregation);

        ctx.aggregation(GroupAggregation.NAME, GroupAggregation.class, GroupAggregationQuery.class,
                new GroupingAggregationSerializer<GroupAggregation>(list, aggregation) {
                    @Override
                    protected GroupAggregation build(List<String> of, Aggregation each) {
                        return new GroupAggregation(of, each);
                    }
                }, new GroupingAggregationBuilder<GroupAggregation>(factory) {
                    @Override
                    protected GroupAggregation build(List<String> over, Aggregation each) {
                        return new GroupAggregation(over, each);
                    }
                });

        ctx.aggregation(CollapseAggregation.NAME, CollapseAggregation.class, CollapseAggregationQuery.class,
                new GroupingAggregationSerializer<CollapseAggregation>(list, aggregation) {
                    @Override
                    protected CollapseAggregation build(List<String> of, Aggregation each) {
                        return new CollapseAggregation(of, each);
                    }
                }, new GroupingAggregationBuilder<CollapseAggregation>(factory) {
                    @Override
                    protected CollapseAggregation build(List<String> over, Aggregation each) {
                        return new CollapseAggregation(over, each);
                    }
                });

        ctx.aggregation(ChainAggregation.NAME, ChainAggregation.class, ChainAggregationQuery.class,
                new Serializer<ChainAggregation>() {
                    @Override
                    public void serialize(SerialWriter buffer, ChainAggregation value) throws IOException {
                        aggregations.serialize(buffer, value.getChain());
                    }

                    @Override
                    public ChainAggregation deserialize(SerialReader buffer) throws IOException {
                        final List<Aggregation> chain = aggregations.deserialize(buffer);
                        return new ChainAggregation(chain);
                    }
                }, new AggregationBuilder<ChainAggregation>() {
                    @Override
                    public ChainAggregation build(List<Value> args, Map<String, Value> keywords) {
                        final List<Aggregation> aggregations = new ArrayList<>();

                        for (final Value v : args) {
                            aggregations.addAll(flatten(v));
                        }

                        return new ChainAggregation(aggregations);
                    }

                    private List<Aggregation> flatten(Value v) {
                        final List<Aggregation> aggregations = new ArrayList<>();

                        if (v instanceof ListValue) {
                            for (final Value item : ((ListValue) v).getList()) {
                                aggregations.addAll(flatten(item));
                            }
                        } else {
                            final AggregationValue a = v.cast(AggregationValue.class);
                            aggregations.add(factory.build(a.getName(), a.getArguments(), a.getKeywordArguments()));
                        }

                        return aggregations;
                    }
                });
    }
}
