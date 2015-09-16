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
import java.util.List;

import lombok.ToString;

import org.kohsuke.args4j.Option;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Inject;
import com.google.inject.name.Named;
import com.spotify.heroic.HeroicShell;
import com.spotify.heroic.common.Grouped;
import com.spotify.heroic.metadata.MetadataManager;
import com.spotify.heroic.metric.MetricManager;
import com.spotify.heroic.shell.AbstractShellTask;
import com.spotify.heroic.shell.AbstractShellTaskParams;
import com.spotify.heroic.shell.ShellTaskParams;
import com.spotify.heroic.shell.ShellTaskUsage;
import com.spotify.heroic.suggest.SuggestManager;

import eu.toolchain.async.AsyncFramework;
import eu.toolchain.async.AsyncFuture;

@ShellTaskUsage("List available backend groups")
public class ListBackends extends AbstractShellTask {
    public static void main(String argv[]) throws Exception {
        HeroicShell.standalone(argv, ListBackends.class);
    }

    @Inject
    private MetricManager metrics;

    @Inject
    private MetadataManager metadata;

    @Inject
    private SuggestManager suggest;

    @Inject
    @Named("application/json")
    private ObjectMapper mapper;

    @Inject
    private AsyncFramework async;

    @Override
    public ShellTaskParams params() {
        return new Parameters();
    }

    @Override
    public AsyncFuture<Void> run(PrintWriter out, ShellTaskParams base) throws Exception {
        final Parameters params = (Parameters) base;

        printBackends(out, "metric", metrics.use(params.group));
        printBackends(out, "metadata", metadata.use(params.group));
        printBackends(out, "suggest", suggest.use(params.group));

        return async.resolved(null);
    }

    private void printBackends(PrintWriter out, String title, List<? extends Grouped> group) {
        if (group.isEmpty()) {
            out.println(String.format("%s: (empty)", title));
            return;
        }

        out.println(String.format("%s:", title));

        for (final Grouped grouped : group) {
            out.println(String.format("  %s %s", grouped.getGroups(), grouped));
        }
    }

    @ToString
    private static class Parameters extends AbstractShellTaskParams {
        @Option(name = "-g", aliases = { "--group" }, usage = "Backend group to use", metaVar = "<group>")
        private String group;
    }
}