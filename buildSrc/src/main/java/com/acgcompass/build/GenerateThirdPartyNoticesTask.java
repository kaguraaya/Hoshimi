package com.acgcompass.build;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.TaskAction;

public abstract class GenerateThirdPartyNoticesTask extends DefaultTask {
    @Input
    public abstract ListProperty<String> getRuntimeCoordinates();

    @Input
    public abstract ListProperty<String> getDevelopmentTools();

    @OutputFile
    public abstract RegularFileProperty getOutputFile();

    @TaskAction
    public void generate() {
        List<Module> modules = getRuntimeCoordinates().get().stream()
                .distinct()
                .sorted()
                .map(Module::parse)
                .toList();

        List<Module> unknown = modules.stream()
                .filter(module -> runtimeLicenseFamily(module.group()) == null)
                .toList();
        if (!unknown.isEmpty()) {
            throw new GradleException("Missing license metadata for: " + String.join(
                    ", ",
                    unknown.stream().map(Module::coordinate).toList()));
        }

        Map<RuntimeLicenseFamily, List<Module>> families = new TreeMap<>(
                Comparator.comparing(RuntimeLicenseFamily::name));
        for (Module module : modules) {
            families.computeIfAbsent(runtimeLicenseFamily(module.group()), ignored -> new ArrayList<>())
                    .add(module);
        }

        StringBuilder runtimeText = new StringBuilder();
        for (Map.Entry<RuntimeLicenseFamily, List<Module>> entry : families.entrySet()) {
            if (!runtimeText.isEmpty()) {
                runtimeText.append("\n\n");
            }
            RuntimeLicenseFamily family = entry.getKey();
            runtimeText.append(family.name()).append('\n')
                    .append("许可证：").append(family.license()).append('\n')
                    .append("项目地址：").append(family.projectUrl()).append('\n')
                    .append("模块：")
                    .append(String.join(", ", entry.getValue().stream().map(Module::coordinate).toList()));
        }

        String contents = "Hoshimi\n许可证：MIT License\n项目地址：https://github.com/kaguraaya/Hoshimi\n\n"
                + "发布包运行依赖（直接与传递模块）\n\n"
                + runtimeText
                + "\n\n"
                + String.join("\n", getDevelopmentTools().get());
        File output = getOutputFile().get().getAsFile();
        File parent = output.getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs()) {
            throw new GradleException("Cannot create license output directory: " + parent);
        }
        try {
            Files.writeString(output.toPath(), contents, StandardCharsets.UTF_8);
        } catch (IOException error) {
            throw new GradleException("Cannot write third-party license asset", error);
        }
    }

    private static RuntimeLicenseFamily runtimeLicenseFamily(String group) {
        if (group.startsWith("androidx.compose")) {
            return apache("Jetpack Compose", "https://developer.android.com/compose");
        }
        if (group.startsWith("androidx.room")) {
            return apache("AndroidX Room", "https://developer.android.com/jetpack/androidx/releases/room");
        }
        if (group.startsWith("androidx.work")) {
            return apache("AndroidX WorkManager", "https://developer.android.com/jetpack/androidx/releases/work");
        }
        if (group.startsWith("androidx.lifecycle")) {
            return apache("AndroidX Lifecycle", "https://developer.android.com/jetpack/androidx/releases/lifecycle");
        }
        if (group.startsWith("androidx.navigation")) {
            return apache("AndroidX Navigation", "https://developer.android.com/jetpack/androidx/releases/navigation");
        }
        if (group.startsWith("androidx.datastore")) {
            return apache("AndroidX DataStore", "https://developer.android.com/jetpack/androidx/releases/datastore");
        }
        if (group.startsWith("androidx.security")) {
            return apache("AndroidX Security", "https://developer.android.com/jetpack/androidx/releases/security");
        }
        if (group.startsWith("androidx.hilt")) {
            return apache("AndroidX Hilt", "https://developer.android.com/jetpack/androidx/releases/hilt");
        }
        if (group.startsWith("androidx.")) {
            return apache("AndroidX Libraries", "https://developer.android.com/jetpack/androidx");
        }
        return switch (group) {
            case "org.jetbrains.kotlin" -> apache("Kotlin Runtime", "https://kotlinlang.org/");
            case "org.jetbrains.kotlinx" -> apache(
                    "KotlinX Libraries", "https://github.com/Kotlin/kotlinx.coroutines");
            case "org.jetbrains" -> apache(
                    "JetBrains Annotations", "https://github.com/JetBrains/java-annotations");
            case "com.google.dagger" -> apache("Dagger and Hilt", "https://dagger.dev/hilt/");
            case "com.google.accompanist" -> apache(
                    "Accompanist", "https://github.com/google/accompanist");
            case "com.google.crypto.tink" -> apache(
                    "Google Tink", "https://github.com/tink-crypto/tink-java");
            case "com.google.code.gson" -> apache("Gson", "https://github.com/google/gson");
            case "com.google.guava" -> apache(
                    "Guava ListenableFuture", "https://github.com/google/guava");
            case "com.google.code.findbugs" -> new RuntimeLicenseFamily(
                    "JSR-305 Annotations", "BSD 3-Clause License", "https://github.com/findbugsproject/findbugs");
            case "com.squareup.retrofit2" -> apache(
                    "Retrofit", "https://github.com/square/retrofit");
            case "com.squareup.okhttp3" -> apache("OkHttp", "https://square.github.io/okhttp/");
            case "com.squareup.okio" -> apache("Okio", "https://square.github.io/okio/");
            case "io.coil-kt" -> apache("Coil", "https://coil-kt.github.io/coil/");
            case "javax.inject" -> apache("javax.inject", "https://github.com/javax-inject/javax-inject");
            case "jakarta.inject" -> new RuntimeLicenseFamily(
                    "Jakarta Dependency Injection",
                    "Eclipse Public License 2.0",
                    "https://jakarta.ee/specifications/dependency-injection/");
            default -> null;
        };
    }

    private static RuntimeLicenseFamily apache(String name, String projectUrl) {
        return new RuntimeLicenseFamily(name, "Apache License 2.0", projectUrl);
    }

    private record RuntimeLicenseFamily(String name, String license, String projectUrl) {}

    private record Module(String group, String name, String version) {
        static Module parse(String coordinate) {
            String[] parts = coordinate.split(":", 3);
            if (parts.length != 3) {
                throw new GradleException("Invalid dependency coordinate: " + coordinate);
            }
            return new Module(parts[0], parts[1], parts[2]);
        }

        String coordinate() {
            return group + ':' + name + ':' + version;
        }
    }
}
