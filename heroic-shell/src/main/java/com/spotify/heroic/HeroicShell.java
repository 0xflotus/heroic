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

package com.spotify.heroic;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import jline.console.ConsoleReader;
import jline.console.UserInterruptException;
import jline.console.completer.StringsCompleter;
import jline.console.history.FileHistory;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;

import org.apache.commons.lang3.StringUtils;
import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Option;

import com.google.inject.Inject;
import com.spotify.heroic.cluster.ClusterManager;
import com.spotify.heroic.consumer.Consumer;
import com.spotify.heroic.metadata.MetadataBackend;
import com.spotify.heroic.metadata.MetadataManager;
import com.spotify.heroic.metric.MetricBackend;
import com.spotify.heroic.metric.MetricManager;
import com.spotify.heroic.shell.CoreBridge;
import com.spotify.heroic.shell.CoreBridge.State;
import com.spotify.heroic.shell.QuoteParser;
import com.spotify.heroic.shell.ShellTask;
import com.spotify.heroic.shell.ShellTaskUsage;
import com.spotify.heroic.shell.task.ConfigGet;
import com.spotify.heroic.shell.task.Fetch;
import com.spotify.heroic.shell.task.Keys;
import com.spotify.heroic.shell.task.ListBackends;
import com.spotify.heroic.shell.task.MetadataCount;
import com.spotify.heroic.shell.task.MetadataDelete;
import com.spotify.heroic.shell.task.MetadataEntries;
import com.spotify.heroic.shell.task.MetadataFetch;
import com.spotify.heroic.shell.task.MetadataLoad;
import com.spotify.heroic.shell.task.MetadataMigrate;
import com.spotify.heroic.shell.task.MetadataMigrateSuggestions;
import com.spotify.heroic.shell.task.MetadataTags;
import com.spotify.heroic.shell.task.SuggestKey;
import com.spotify.heroic.shell.task.SuggestPerformance;
import com.spotify.heroic.shell.task.SuggestTag;
import com.spotify.heroic.shell.task.SuggestTagKeyCount;
import com.spotify.heroic.shell.task.SuggestTagValue;
import com.spotify.heroic.shell.task.SuggestTagValues;
import com.spotify.heroic.shell.task.WritePerformance;
import com.spotify.heroic.utils.GroupMember;

import eu.toolchain.async.AsyncFramework;
import eu.toolchain.async.AsyncFuture;
import eu.toolchain.async.Managed;
import eu.toolchain.async.ManagedSetup;
import eu.toolchain.async.TinyAsync;

@Slf4j
public class HeroicShell {
    @ToString
    public static class Parameters {
        @Option(name = "-c", aliases = { "--config" }, usage = "Path to configuration", metaVar = "<config>")
        private String config;

        @Option(name = "--server", usage = "Start shell as server (enables listen port)")
        private boolean server = false;

        @Option(name = "-h", aliases = { "--help" }, help = true, usage = "Display help")
        private boolean help;
    }

    private static final Map<String, Class<? extends ShellTask>> available = new HashMap<>();

    static {
        available.put("get", ConfigGet.class);
        available.put("keys", Keys.class);
        available.put("backends", ListBackends.class);
        available.put("fetch", Fetch.class);
        available.put("write-performance", WritePerformance.class);
        available.put("metadata-delete", MetadataDelete.class);
        available.put("metadata-fetch", MetadataFetch.class);
        available.put("metadata-tags", MetadataTags.class);
        available.put("metadata-count", MetadataCount.class);
        available.put("metadata-entries", MetadataEntries.class);
        available.put("metadata-migrate", MetadataMigrate.class);
        available.put("metadata-migrate-suggestions", MetadataMigrateSuggestions.class);
        available.put("metadata-load", MetadataLoad.class);
        available.put("suggest-tag", SuggestTag.class);
        available.put("suggest-key", SuggestKey.class);
        available.put("suggest-tag-value", SuggestTagValue.class);
        available.put("suggest-tag-values", SuggestTagValues.class);
        available.put("suggest-tag-key-count", SuggestTagKeyCount.class);
        available.put("suggest-performance", SuggestPerformance.class);
    }

    public static void main(String[] args) throws IOException {
        final ExecutorService executor = Executors.newSingleThreadScheduledExecutor();

        final AsyncFramework async = TinyAsync.builder().executor(executor).build();
        final Parameters params = new Parameters();

        final CmdLineParser parser = new CmdLineParser(params);

        try {
            parser.parseArgument(args);
        } catch (CmdLineException e) {
            log.error("Argument error", e);
            System.exit(1);
            return;
        }

        if (params.help) {
            parser.printUsage(System.out);
            System.exit(0);
            return;
        }

        final HeroicCore.Builder builder = CoreBridge.setupBuilder(params.server, params.config);

        final Managed<CoreBridge.State> state = setupState(async, builder);

        final CoreBridge runner = new CoreBridge(state);

        log.info("Starting Heroic...");

        try {
            runner.start();
        } catch (Exception e) {
            log.error("Failed to start core", e);
            System.exit(1);
            return;
        }

        log.info("Wiring tasks...");

        final Map<String, ShellTask> tasks;

        try {
            tasks = buildTasks(runner);
        } catch (Exception e) {
            log.error("Failed to build tasks", e);
            return;
        }

        final HeroicShell shell = new HeroicShell(runner, tasks);

        try {
            shell.run();
        } catch (Exception e) {
            log.error("Exception caught while running shell", e);
        }

        try {
            runner.stop(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.error("Failed to stop runner in a timely fashion", e);
        }

        System.exit(0);
    }

    private static Map<String, ShellTask> buildTasks(CoreBridge runner) throws Exception {
        final Map<String, ShellTask> tasks = new HashMap<>();

        for (final Entry<String, Class<? extends ShellTask>> e : HeroicShell.available.entrySet()) {
            tasks.put(e.getKey(), runner.setup(e.getValue()));
        }

        return tasks;
    }

    private final CoreBridge runner;
    private final Map<String, ShellTask> tasks;

    // mutable state, a.k.a. settings
    int currentTimeout = 10;

    public HeroicShell(CoreBridge runner, Map<String, ShellTask> tasks) {
        this.runner = runner;
        this.tasks = tasks;
    }

    private void run() throws IOException {
        try (final FileInputStream input = new FileInputStream(FileDescriptor.in)) {
            final ConsoleReader reader = new ConsoleReader("heroicsh", input, System.out, null);

            final String home = System.getProperty("user.home");

            final FileHistory history;

            if (home != null) {
                history = new FileHistory(new File(home, ".heroicsh-history"));
                reader.setHistory(history);
            } else {
                history = null;
            }

            final ArrayList<String> commands = new ArrayList<>(available.keySet());

            commands.add("exit");
            commands.add("clear");
            commands.add("help");
            commands.add("timeout");

            reader.addCompleter(new StringsCompleter(commands));
            reader.setHandleUserInterrupt(true);

            try {
                doReaderLoop(reader);
            } catch (Exception e) {
                log.error("Error in reader loop", e);
            }

            if (history != null)
                history.flush();

            reader.shutdown();
        }
    }

    private void doReaderLoop(final ConsoleReader reader)
            throws Exception {
        final PrintWriter out = new PrintWriter(reader.getOutput());

        while (true) {
            reader.setPrompt(String.format("heroic(%s)> ", runner.status()));

            final String line;

            try {
                line = reader.readLine();
            } catch (UserInterruptException e) {
                out.println("Interrupted");
                break;
            }

            if (line == null)
                break;

            final String[] parts = QuoteParser.parse(line);

            if ("exit".equals(parts[0]))
                break;

            if ("clear".equals(parts[0])) {
                reader.clearScreen();
                continue;
            }

            if ("help".equals(parts[0])) {
                printHelp(tasks, out);
                continue;
            }

            if ("timeout".equals(parts[0])) {
                internalTimeoutTask(parts, out);
                continue;
            }

            final long start = System.nanoTime();
            runTask(reader, parts, out);
            final long diff = System.nanoTime() - start;

            out.println(String.format("time: %s", formatTime(diff)));
        }

        out.println();
        out.println("Exiting...");
    }

    private void internalTimeoutTask(String[] parts, PrintWriter out) {
        if (parts.length < 2) {
            if (currentTimeout == 0) {
                out.println("timeout disabled");
            } else {
                out.println(String.format("timeout = %d", currentTimeout));
            }

            return;
        }

        final int timeout;

        try {
            timeout = Integer.parseInt(parts[1]);
        } catch (Exception e) {
            out.println(String.format("not a valid integer value: %s", parts[1]));
            return;
        }

        if (timeout <= 0) {
            out.println("Timeout disabled");
            currentTimeout = 0;
        } else {
            out.println(String.format("Timeout updated to %d seconds", timeout));
            currentTimeout = timeout;
        }
    }

    private String formatTime(long diff) {
        if (diff < 1000) {
            return String.format("%d ns", diff);
        }

        if (diff < 1000000) {
            final double v = ((double) diff) / 1000;
            return String.format("%.3f us", v);
        }

        if (diff < 1000000000) {
            final double v = ((double) diff) / 1000000;
            return String.format("%.3f ms", v);
        }

        final double v = ((double) diff) / 1000000000;
        return String.format("%.3f s", v);
    }

    private void printHelp(final Map<String, ShellTask> tasks, final PrintWriter out) {
        out.println("Known Commands:");
        out.println("help - Display this help");
        out.println("exit - Exit the shell");
        out.println("clear - Clear the shell");
        out.println("timeout - Manipulate and get timeout settings");

        for (final Map.Entry<String, ShellTask> e : tasks.entrySet()) {
            final ShellTaskUsage usage = e.getValue().getClass().getAnnotation(ShellTaskUsage.class);

            final String help;

            if (usage == null) {
                help = "";
            } else {
                help = usage.value();
            }

            out.println(String.format("%s - %s", e.getKey(), help));
        }
    }

    private void runTask(final ConsoleReader reader, String[] parts, final PrintWriter out) throws Exception,
            IOException {
        // TODO: improve this with proper quote parsing and such.
        final String taskName = parts[0];
        final String[] commandArgs = args(parts);

        final ShellTask task = tasks.get(taskName);

        if (task == null) {
            out.println("No such task '" + taskName + "'");
            return;
        }

        final AsyncFuture<Void> t;

        try {
            t = runner.run(task, commandArgs, out);
        } catch (Exception e) {
            out.println("Command failed");
            e.printStackTrace(out);
            return;
        }

        if (t == null)
            return;

        try {
            waitForTermination(t);
        } catch (TimeoutException e) {
            out.println(String.format("Command timed out (current timeout = %ds)", currentTimeout));
            t.cancel(true);
        } catch (Exception e) {
            out.println("Command failed");
            e.printStackTrace(out);
            return;
        }

        out.flush();
        return;
    }

    private Void waitForTermination(final AsyncFuture<Void> t) throws InterruptedException, ExecutionException,
            TimeoutException {
        if (currentTimeout > 0)
            return t.get(currentTimeout, TimeUnit.SECONDS);

        return t.get();
    }

    private String[] args(String[] parts) {
        if (parts.length <= 1)
            return new String[0];

        final String[] args = new String[parts.length - 1];

        for (int i = 1; i < parts.length; i++)
            args[i - 1] = parts[i];

        return args;
    }

    private static Managed<CoreBridge.State> setupState(final AsyncFramework async, final HeroicCore.Builder builder) {
        return async.managed(new ManagedSetup<CoreBridge.State>() {
            @Override
            public AsyncFuture<State> construct() {
                return async.call(new Callable<CoreBridge.State>() {
                    @Override
                    public State call() throws Exception {
                        final HeroicCore core = builder.build();
                        core.start();

                        final Callable<String> status = core.inject(new Callable<String>() {
                            @Inject
                            private Set<Consumer> consumers;

                            @Inject
                            private MetricManager metric;

                            @Inject
                            private MetadataManager metadata;

                            @Inject
                            private ClusterManager cluster;

                            @Override
                            public String call() throws Exception {
                                final List<String> statuses = new ArrayList<>();

                                if (!clusterStatus())
                                    statuses.add("bad cluster");

                                if (!consumerStatus())
                                    statuses.add("bad consumer");

                                if (!metadataStatus())
                                    statuses.add("bad metadata");

                                if (!metricStatus())
                                    statuses.add("bad metric");

                                if (statuses.isEmpty())
                                    return "ok";

                                return StringUtils.join(statuses, ", ");
                            }

                            private boolean clusterStatus() {
                                final ClusterManager.Statistics s = cluster.getStatistics();

                                if (s == null)
                                    return false;

                                return s.getOfflineNodes() == 0 || s.getOnlineNodes() > 0;
                            }

                            private boolean consumerStatus() {
                                for (final Consumer c : consumers) {
                                    if (!c.isReady()) {
                                        return false;
                                    }
                                }

                                return true;
                            }

                            private boolean metadataStatus() {
                                for (final GroupMember<MetadataBackend> backend : metadata.getBackends()) {
                                    if (!backend.getMember().isReady())
                                        return false;
                                }

                                return true;
                            }

                            private boolean metricStatus() {
                                for (final GroupMember<MetricBackend> backend : metric.getBackends()) {
                                    if (!backend.getMember().isReady())
                                        return false;
                                }

                                return true;
                            }
                        });

                        return new State(core, status);
                    }
                });
            }

            @Override
            public AsyncFuture<Void> destruct(final State state) {
                return async.call(new Callable<Void>() {
                    @Override
                    public Void call() throws Exception {
                        state.getCore().shutdown();
                        return null;
                    }

                });
            }
        });
    }
}
