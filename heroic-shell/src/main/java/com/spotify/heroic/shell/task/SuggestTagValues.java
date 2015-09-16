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

package com.spotify.heroic.shell.task;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

import lombok.Getter;
import lombok.ToString;

import org.kohsuke.args4j.Argument;
import org.kohsuke.args4j.Option;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Inject;
import com.google.inject.name.Named;
import com.spotify.heroic.HeroicShell;
import com.spotify.heroic.filter.FilterFactory;
import com.spotify.heroic.grammar.QueryParser;
import com.spotify.heroic.model.RangeFilter;
import com.spotify.heroic.shell.AbstractShellTask;
import com.spotify.heroic.shell.ShellTaskParams;
import com.spotify.heroic.shell.ShellTaskUsage;
import com.spotify.heroic.suggest.SuggestManager;
import com.spotify.heroic.suggest.model.TagValuesSuggest;

import eu.toolchain.async.AsyncFuture;
import eu.toolchain.async.Transform;

@ShellTaskUsage("Get a list of value suggestions for a given key")
public class SuggestTagValues extends AbstractShellTask {
    public static void main(String argv[]) throws Exception {
        HeroicShell.standalone(argv, SuggestTagValues.class);
    }

    @Inject
    private SuggestManager suggest;

    @Inject
    private FilterFactory filters;

    @Inject
    private QueryParser parser;

    @Inject
    @Named("application/json")
    private ObjectMapper mapper;

    @Override
    public ShellTaskParams params() {
        return new Parameters();
    }

    @Override
    public AsyncFuture<Void> run(final PrintWriter out, ShellTaskParams base) throws Exception {
        final Parameters params = (Parameters) base;

        final RangeFilter filter = Tasks.setupRangeFilter(filters, parser, params);

        return suggest.useGroup(params.group).tagValuesSuggest(filter, params.exclude, params.groupLimit)
                .transform(new Transform<TagValuesSuggest, Void>() {
                    @Override
                    public Void transform(TagValuesSuggest result) throws Exception {
                        int i = 0;

                        for (final TagValuesSuggest.Suggestion suggestion : result.getSuggestions()) {
                            out.println(String.format("%s: %s", i++, suggestion));
                        }

                        return null;
                    }
                });
    }

    @ToString
    private static class Parameters extends Tasks.QueryParamsBase {
        @Option(name = "-g", aliases = { "--group" }, usage = "Backend group to use", metaVar = "<group>")
        private String group;

        @Option(name = "-e", aliases = { "--exclude" }, usage = "Exclude the given tags")
        private List<String> exclude = new ArrayList<>();

        @Option(name = "--group-limit", usage = "Maximum cardinality to pull")
        private int groupLimit = 100;

        @Option(name = "--limit", aliases = { "--limit" }, usage = "Limit the number of printed entries")
        @Getter
        private int limit = 10;

        @Argument
        @Getter
        private List<String> query = new ArrayList<String>();
    }
}