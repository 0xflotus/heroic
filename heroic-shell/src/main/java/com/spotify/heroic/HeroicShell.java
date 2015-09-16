/* Copyright (c) 2015 Spotify AB.
 * 
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. See the NOTICE
 * file distributed with this work for additional information regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with the
 * License. You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License. */

package com.spotify.heroic;

import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.lang.Thread.UncaughtExceptionHandler;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.Executors;

import lombok.RequiredArgsConstructor;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;

import org.apache.commons.lang3.StringUtils;
import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Option;

import com.google.common.collect.ImmutableList;
import com.spotify.heroic.HeroicCore.Builder;
import com.spotify.heroic.elasticsearch.ManagedConnectionFactory;
import com.spotify.heroic.elasticsearch.TransportClientSetup;
import com.spotify.heroic.elasticsearch.index.SingleIndexMapping;
import com.spotify.heroic.metadata.MetadataManagerModule;
import com.spotify.heroic.metadata.MetadataModule;
import com.spotify.heroic.metadata.elasticsearch.ElasticsearchMetadataModule;
import com.spotify.heroic.shell.AbstractShellTaskParams;
import com.spotify.heroic.shell.CommandDefinition;
import com.spotify.heroic.shell.CoreInterface;
import com.spotify.heroic.shell.CoreShellInterface;
import com.spotify.heroic.shell.ShellTask;
import com.spotify.heroic.shell.TaskElasticsearchParameters;
import com.spotify.heroic.shell.TaskParameters;
import com.spotify.heroic.shell.Tasks;
import com.spotify.heroic.suggest.SuggestManagerModule;
import com.spotify.heroic.suggest.SuggestModule;
import com.spotify.heroic.suggest.elasticsearch.ElasticsearchSuggestModule;

import eu.toolchain.async.AsyncFramework;
import eu.toolchain.async.TinyAsync;

@Slf4j
public class HeroicShell {
    public static final Path[] DEFAULT_CONFIGS = new Path[] { Paths.get("heroic.yml"),
            Paths.get("/etc/heroic/heroic.yml") };

    public static void main(String[] args) throws IOException {
        Thread.setDefaultUncaughtExceptionHandler(new UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread t, Throwable e) {
                try {
                    log.error("Uncaught exception in thread {}, exiting...", t, e);
                } finally {
                    System.exit(1);
                }
            }
        });

        final Parameters params = new Parameters();
        final CmdLineParser parser = new CmdLineParser(params);
        final ParsedArguments parsed = ParsedArguments.parse(args);

        try {
            parser.parseArgument(parsed.primary);
        } catch (CmdLineException e) {
            log.error("Argument error", e);
            System.exit(1);
            return;
        }

        if (params.help()) {
            parser.printUsage(System.err);
            HeroicModules.printProfileUsage(new PrintWriter(System.err), "-P");
            System.exit(0);
            return;
        }

        final AsyncFramework async = TinyAsync.builder().executor(Executors.newSingleThreadExecutor()).build();

        if (parsed.child.isEmpty()) {
            final CoreInterface bridge;

            try {
                bridge = setupCoreBridge(params, async);
            } catch (Exception e) {
                log.error("Failed to setup core bridge", e);
                System.exit(1);
                return;
            }

            interactive(params, bridge);
            System.exit(0);
            return;
        }

        final HeroicCore.Builder builder = setupBuilder(params.server, params.config(), params.profile());

        try {
            standalone(parsed.child, builder);
        } catch (Exception e) {
            log.error("Failed to run standalone task", e);
        }

        System.exit(0);
    }

    private static CoreInterface setupCoreBridge(Parameters params, AsyncFramework async) throws Exception {
        if (params.connect != null) {
            return setupRemoteCoreBridge(params.connect, async);
        }

        return setupLocalCoreBridge(params, async);
    }

    private static CoreInterface setupRemoteCoreBridge(String connect, AsyncFramework async) throws Exception {
        return RemoteHeroicCoreBridge.fromConnectString(connect, async);
    }

    private static CoreInterface setupLocalCoreBridge(Parameters params, AsyncFramework async) throws Exception {
        final HeroicCore.Builder builder = setupBuilder(params.server, params.config(), params.profile());

        final HeroicCore core = builder.build();

        log.info("Starting local Heroic...");

        core.start();

        return new LocalHeroicCoreBridge(core, async);
    }

    static void interactive(Parameters params, CoreInterface core) {
        log.info("Setting up interactive shell...");

        try {
            runInteractiveShell(core);
        } catch (Exception e) {
            log.error("Error when running shell", e);
        }

        log.info("Closing core bridge...");

        try {
            core.shutdown();
        } catch (Exception e) {
            log.error("Failed to close core bridge", e);
        }
    }

    static void runInteractiveShell(final CoreInterface core)
            throws Exception {
        final List<CommandDefinition> commands = new ArrayList<>(core.getCommands());

        commands.add(new CommandDefinition("clear", ImmutableList.of(), "Clear the current shell"));
        commands.add(new CommandDefinition("timeout", ImmutableList.of(), "Get or set the current task timeout"));
        commands.add(new CommandDefinition("exit", ImmutableList.of(), "Exit the shell"));

        try (final FileInputStream input = new FileInputStream(FileDescriptor.in)) {
            final HeroicInteractiveShell interactive = HeroicInteractiveShell.buildInstance(commands, input);
            final CoreShellInterface c = core.setup(interactive);

            try {
                interactive.run(c);
            } finally {
                interactive.shutdown();
            }
        }
    }

    static void standalone(List<String> arguments, Builder builder) throws Exception {
        final String taskName = arguments.iterator().next();
        final List<String> rest = arguments.subList(1, arguments.size());

        log.info("Running standalone task {}", taskName);

        final Class<ShellTask> taskType = resolveShellTask(taskName);
        final ShellTask task = Tasks.newInstance(taskType);
        final TaskParameters params = task.params();

        final CmdLineParser parser = new CmdLineParser(params);

        try {
            parser.parseArgument(rest);
        } catch (CmdLineException e) {
            log.error("Error parsing arguments", e);
            System.exit(1);
            return;
        }

        if (params.help()) {
            parser.printUsage(System.err);
            HeroicModules.printProfileUsage(System.err, "-P");
            System.exit(0);
            return;
        }

        if (params instanceof TaskElasticsearchParameters) {
            final TaskElasticsearchParameters elasticsearch = (TaskElasticsearchParameters) params;

            if (elasticsearch.getSeeds() != null) {
                log.info("Setting up standalone elasticsearch configuration");
                standaloneElasticsearchConfig(builder, elasticsearch);
            }
        }

        final HeroicCore core = builder.build();

        log.info("Starting Heroic...");
        core.start();

        try {
            core.inject(task);

            final PrintWriter o = standaloneOutput(params, System.out);

            try {
                task.run(o, params).get();
            } catch (Exception e) {
                log.error("Failed to run task", e);
            } finally {
                o.flush();
            }
        } finally {
            core.shutdown();
        }
    }

    @SuppressWarnings("unchecked")
    static Class<ShellTask> resolveShellTask(final String taskName) throws ClassNotFoundException, Exception {
        final Class<?> taskType = Class.forName(taskName);

        if (!(ShellTask.class.isAssignableFrom(taskType))) {
            throw new Exception(String.format("Not an instance of ShellTask (%s)", taskName));
        }

        return (Class<ShellTask>) taskType;
    }

    static void standaloneElasticsearchConfig(HeroicCore.Builder builder, TaskElasticsearchParameters params) {
        final List<String> seeds = Arrays.asList(StringUtils.split(params.getSeeds(), ','));

        final String clusterName = params.getClusterName();
        final String backendType = params.getBackendType();

        builder.profile(new HeroicProfile() {

            @Override
            public HeroicConfig build() throws Exception {
                // @formatter:off

                final TransportClientSetup clientSetup = TransportClientSetup.builder()
                    .clusterName(clusterName)
                    .seeds(seeds)
                .build();

                return HeroicConfig.builder()
                        .metadata(
                            MetadataManagerModule.builder()
                            .backends(
                                ImmutableList.<MetadataModule>of(
                                    ElasticsearchMetadataModule.builder()
                                    .connection(setupConnection(clientSetup, "metadata"))
                                    .writesPerSecond(0d)
                                    .build()
                                )
                            ).build()
                        )
                        .suggest(
                            SuggestManagerModule.builder()
                            .backends(
                                ImmutableList.<SuggestModule>of(
                                    ElasticsearchSuggestModule.builder()
                                    .connection(setupConnection(clientSetup, "suggest"))
                                    .writesPerSecond(0d)
                                    .backendType(backendType)
                                    .build()
                                )
                            )
                            .build()
                        )

                .build();
                // @formatter:on
            }

            private ManagedConnectionFactory setupConnection(TransportClientSetup clientSetup, final String index) {
                // @formatter:off
                return ManagedConnectionFactory.builder()
                    .clientSetup(clientSetup)
                    .index(SingleIndexMapping.builder().index(index).build())
                    .build();
                // @formatter:on
            }

            @Override
            public String description() {
                return "load metadata from a file";
            }
        });
    }

    static PrintWriter standaloneOutput(final TaskParameters params, final PrintStream original) throws IOException {
        if (params.output() != null && !"-".equals(params.output())) {
            return new PrintWriter(Files.newOutputStream(Paths.get(params.output())));
        }

        return new PrintWriter(original);
    }

    static Path parseConfigPath(String config) {
        final Path path = doParseConfigPath(config);

        if (!Files.isRegularFile(path))
            throw new IllegalStateException("No such file: " + path.toAbsolutePath());

        return path;
    }

    static Path doParseConfigPath(String config) {
        if (config == null) {
            for (final Path p : DEFAULT_CONFIGS) {
                if (Files.isRegularFile(p))
                    return p;
            }

            throw new IllegalStateException("No default configuration available, checked "
                    + formatDefaults(DEFAULT_CONFIGS));
        }

        return Paths.get(config);
    }

    static String formatDefaults(Path[] defaultConfigs) {
        final List<Path> alternatives = new ArrayList<>(defaultConfigs.length);

        for (final Path path : defaultConfigs)
            alternatives.add(path.toAbsolutePath());

        return StringUtils.join(alternatives, ", ");
    }

    static HeroicCore.Builder setupBuilder(boolean server, String config, String profile) {
        HeroicCore.Builder builder = HeroicCore.builder().server(server).modules(HeroicModules.ALL_MODULES)
                .oneshot(true);

        if (config != null) {
            builder.configPath(parseConfigPath(config));
        }

        if (profile != null) {
            final HeroicProfile p = HeroicModules.PROFILES.get(profile);

            if (p == null)
                throw new IllegalArgumentException(String.format("not a valid profile: %s", profile));

            builder.profile(p);
        }

        return builder;
    }

    @ToString
    public static class Parameters extends AbstractShellTaskParams {
        @Option(name = "--server", usage = "Start shell as server (enables listen port)")
        private boolean server = false;

        @Option(name = "--connect", usage = "Connect to a remote heroic server")
        private String connect = null;
    }

    @RequiredArgsConstructor
    static class ParsedArguments {
        final List<String> primary;
        final List<String> child;

        public static ParsedArguments parse(String[] args) {
            final List<String> primary = new ArrayList<>();
            final List<String> child = new ArrayList<>();

            final Iterator<String> iterator = Arrays.stream(args).iterator();

            while (iterator.hasNext()) {
                final String arg = iterator.next();

                if ("--".equals(arg)) {
                    break;
                }

                primary.add(arg);
            }

            while (iterator.hasNext()) {
                child.add(iterator.next());
            }

            return new ParsedArguments(primary, child);
        }
    }
}
