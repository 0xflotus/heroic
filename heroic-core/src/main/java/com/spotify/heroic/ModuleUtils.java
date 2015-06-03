package com.spotify.heroic;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Constructor;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

public class ModuleUtils {
    public static final String ENTRY_CLASS_NAME = "Entry";

    public static List<HeroicModule> loadModules(List<URL> moduleLocations) throws IOException {
        final ClassLoader loader = ModuleUtils.class.getClassLoader();

        final List<HeroicModule> modules = new ArrayList<>();

        for (final URL input : moduleLocations) {
            final InputStream inputStream = input.openStream();

            try (final BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))) {
                modules.addAll(loadModule(reader, loader));
            }
        }

        return modules;
    }

    private static List<HeroicModule> loadModule(final BufferedReader reader, final ClassLoader loader)
            throws IOException {
        final List<HeroicModule> children = new ArrayList<>();

        while (true) {
            final String line = reader.readLine();

            if (line == null)
                break;

            final String trimmed = line.trim();

            if (trimmed.isEmpty() || trimmed.startsWith("#"))
                continue;

            children.add(loadModule(trimmed, loader));
        }

        return children;
    }

    public static HeroicModule loadModule(String packageName) {
        final ClassLoader loader = ModuleUtils.class.getClassLoader();
        return loadModule(packageName, loader);
    }

    private static HeroicModule loadModule(String packageName, ClassLoader loader) {
        final String className = String.format("%s.%s", packageName, ENTRY_CLASS_NAME);

        final Class<?> clazz;

        try {
            clazz = loader.loadClass(className);
        } catch (final ClassNotFoundException e) {
            throw new RuntimeException("Class '" + className + "' cannot be found for package '" + packageName + "'", e);
        }

        return loadModule(clazz);
    }

    public static HeroicModule loadModule(final Class<?> clazz) {
        if (!(HeroicModule.class.isAssignableFrom(clazz)))
            throw new RuntimeException("Not a ModuleEntryPoint: " + clazz.toString());

        final Constructor<?> constructor;

        try {
            constructor = clazz.getConstructor();
        } catch (final NoSuchMethodException e) {
            throw new RuntimeException("Expected empty constructor: " + clazz.toString(), e);
        } catch (final SecurityException e) {
            throw new RuntimeException("Security exception when getting constructor for: " + clazz.toString(), e);
        }

        try {
            return (HeroicModule) constructor.newInstance();
        } catch (final ReflectiveOperationException e) {
            throw new RuntimeException("Failed to create instance of: " + clazz.toString(), e);
        }
    }
}
