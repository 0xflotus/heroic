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
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.concurrent.TimeUnit;

import lombok.ToString;

import org.kohsuke.args4j.Option;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Inject;
import com.google.inject.name.Named;
import com.spotify.heroic.HeroicShell;
import com.spotify.heroic.metric.MetricBackendGroup;
import com.spotify.heroic.metric.MetricManager;
import com.spotify.heroic.metric.model.FetchData;
import com.spotify.heroic.metric.model.TimeDataGroup;
import com.spotify.heroic.model.DateRange;
import com.spotify.heroic.model.MetricType;
import com.spotify.heroic.model.Series;
import com.spotify.heroic.model.TimeData;
import com.spotify.heroic.shell.AbstractShellTask;
import com.spotify.heroic.shell.AbstractShellTaskParams;
import com.spotify.heroic.shell.ShellTaskParams;
import com.spotify.heroic.shell.ShellTaskUsage;

import eu.toolchain.async.AsyncFuture;
import eu.toolchain.async.Transform;

@ShellTaskUsage("Fetch a range of data points")
public class Fetch extends AbstractShellTask {
    public static void main(String argv[]) throws Exception {
        HeroicShell.standalone(argv, Fetch.class);
    }

    @Inject
    private MetricManager metrics;

    @Inject
    @Named("application/json")
    private ObjectMapper mapper;

    @Override
    public ShellTaskParams params() {
        return new Parameters();
    }

    @Override
    public AsyncFuture<Void> run(final PrintWriter out, final ShellTaskParams base) throws Exception {
        final Parameters params = (Parameters) base;
        final long now = System.currentTimeMillis();

        final Series series = mapper.readValue(params.series, Series.class);
        final long start = params.start == null ? now : Tasks.parseInstant(params.start, now);
        final long end = params.end == null ? defaultEnd(start) : Tasks.parseInstant(params.end, now);

        final DateRange range = new DateRange(start, end);
        final int limit = Math.max(1, params.limit);

        final DateFormat flip = new SimpleDateFormat("yyyy-MM-dd HH:mm");
        final DateFormat point = new SimpleDateFormat("HH:mm:ss.SSS");

        final MetricBackendGroup readGroup = metrics.useGroup(params.group);

        return readGroup.fetch(MetricType.POINTS, series, range).transform(new Transform<FetchData, Void>() {
            @Override
            public Void transform(FetchData result) throws Exception {
                outer:
                for (final TimeDataGroup g : result.getGroups()) {
                    int i = 0;

                    Calendar current = null;
                    Calendar last = null;

                    for (final TimeData d : g.getData()) {
                        current = Calendar.getInstance();
                        current.setTime(new Date(d.getTimestamp()));

                        if (flipped(last, current)) {
                            out.println(flip.format(current.getTime()));
                        }

                        out.println(String.format("  %s: %s", point.format(new Date(d.getTimestamp())), d));

                        if (i++ >= limit)
                            break outer;

                        last = current;
                    }

                }

                return null;
            }
        });
    }

    private boolean flipped(Calendar last, Calendar current) {
        if (last == null)
            return true;

        if (last.get(Calendar.YEAR) != current.get(Calendar.YEAR))
            return true;

        if (last.get(Calendar.MONTH) != current.get(Calendar.MONTH))
            return true;

        if (last.get(Calendar.DAY_OF_MONTH) != current.get(Calendar.DAY_OF_MONTH))
            return true;

        if (last.get(Calendar.HOUR_OF_DAY) != current.get(Calendar.HOUR_OF_DAY))
            return true;

        return false;
    }

    private long defaultEnd(long start) {
        return start + TimeUnit.MILLISECONDS.convert(1, TimeUnit.HOURS);
    }

    @ToString
    private static class Parameters extends AbstractShellTaskParams {
        @Option(name = "--series", required = true, usage = "Series to fetch", metaVar = "<json>")
        private String series;

        @Option(name = "--start", usage = "Start date", metaVar = "<datetime>")
        private String start;

        @Option(name = "--end", usage = "End date", metaVar = "<datetime>")
        private String end;

        @Option(name = "--limit", usage = "Maximum number of datapoints to fetch", metaVar = "<int>")
        private int limit = 1000;

        @Option(name = "-g", aliases = { "--group" }, usage = "Backend group to use", metaVar = "<group>")
        private String group = null;
    }
}