package com.legacyaudit.model.config;

import com.legacyaudit.model.json.PyJson;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiFunction;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;

/** Reads {@code audit.yaml} (and other YAML files of a project) the way the Python tool did. */
public final class ConfigLoader {

    /**
     * Layers applied to the YAML data on every {@link #load}, in order (the hosted site's admin
     * defaults and limits; {@code add_config_layer} in the Python tool). Each gets the data and
     * the absolute config path and returns the data to use.
     */
    private static final List<BiFunction<Object, Path, Object>> LAYERS = new CopyOnWriteArrayList<>();

    private ConfigLoader() {}

    /** Apply {@code layer} in every later {@link #load}; the returned action removes it. */
    public static Runnable addLayer(BiFunction<Object, Path, Object> layer) {
        LAYERS.add(layer);
        return () -> LAYERS.remove(layer);
    }

    /** Plain YAML 1.1 data (maps, lists, text, numbers, booleans), as PyYAML's safe_load gives. */
    public static Object readYaml(Path path) {
        String text;
        try {
            text = Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ConfigException("Cannot read " + path + ": " + e.getMessage());
        }
        return parseYaml(text, path.toString());
    }

    public static Object parseYaml(String text, String source) {
        try {
            return new Yaml(new SafeConstructor(new LoaderOptions())).load(text);
        } catch (YAMLException e) {
            throw new ConfigException(source + " is not valid YAML: " + e.getMessage());
        }
    }

    public static LoadedConfig load(Path path) {
        Path abs = path.toAbsolutePath().normalize();
        if (!Files.isRegularFile(abs)) {
            throw new ConfigException("Cannot read config " + path + ": No such file or directory");
        }
        Object data = readYaml(abs);
        for (BiFunction<Object, Path, Object> layer : LAYERS) {
            data = layer.apply(data, abs);
        }
        AuditConfig config = ConfigReader.parse(data, path.toString());
        String canonical = PyJson.compact(config.dump());
        return new LoadedConfig(config, abs, abs.getParent(), sha256(canonical).substring(0, 16));
    }

    static String sha256(String text) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
