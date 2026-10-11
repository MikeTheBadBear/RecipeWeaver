package dev.recipeweaver.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

/** Immutable raw file contents captured on the ordered I/O worker. Bukkit YAML decoding runs on the caller thread. */
final class DiskSnapshot {
    private static final String[] DIRECTORIES = {"recipes", "stations", "grid-recipes", "discoveries"};
    private static final String[] FILES = {"items.yml", "settings.yml", "tables.yml", "cauldrons.yml"};
    private static final long MAX_BYTES = 64L * 1024 * 1024;
    private static final int MAX_FILES = 10000;
    private final Map<String, String> contents;

    private DiskSnapshot(Map<String, String> contents) {
        this.contents = Collections.unmodifiableMap(new LinkedHashMap<>(contents));
    }

    static DiskSnapshot fromDocuments(Map<String, String> documents) throws IOException {
        Map<String, String> copy = new LinkedHashMap<>();
        long bytes = 0;
        for (var entry : documents.entrySet()) {
            SqlDocumentStore.validatePath(entry.getKey());
            String content = entry.getValue();
            if (content == null) throw new IOException("Missing document content");
            bytes += content.getBytes(StandardCharsets.UTF_8).length;
            if (bytes > MAX_BYTES || copy.size() >= MAX_FILES) {
                throw new IOException("RecipeWeaver data exceeds snapshot limits");
            }
            copy.put(entry.getKey(), content);
        }
        return new DiskSnapshot(copy);
    }

    Map<String, String> documents() { return contents; }

    static DiskSnapshot read(Path root) throws IOException {
        Map<String, String> contents = new LinkedHashMap<>();
        long bytes = 0;
        for (String name : FILES) {
            Path path = root.resolve(name);
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) continue;
            bytes = readFile(root, path, contents, bytes);
        }
        for (String directoryName : DIRECTORIES) {
            Path directory = root.resolve(directoryName);
            if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) continue;
            if (Files.isSymbolicLink(directory) || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException(directoryName + " must be a directory");
            }
            try (var entries = Files.list(directory)) {
                for (Path path : entries.sorted(Comparator.comparing(p -> p.getFileName().toString())).toList()) {
                    bytes = readFile(root, path, contents, bytes);
                }
            }
        }
        return new DiskSnapshot(contents);
    }

    private static long readFile(Path root, Path path, Map<String, String> contents, long bytes)
            throws IOException {
        if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Unexpected RecipeWeaver data entry: " + root.relativize(path));
        }
        long size = Files.size(path);
        if (size > MAX_BYTES - bytes || contents.size() >= MAX_FILES) {
            throw new IOException("RecipeWeaver data exceeds snapshot limits");
        }
        String name = root.relativize(path).toString().replace('\\', '/');
        String text = Files.readString(path, StandardCharsets.UTF_8);
        if (contents.putIfAbsent(name, text) != null) throw new IOException("Duplicate data entry: " + name);
        return bytes + size;
    }

    String file(String name) { return contents.get(name); }

    Map<String, String> directory(String name) {
        String prefix = name + "/";
        Map<String, String> result = new LinkedHashMap<>();
        contents.forEach((path, data) -> {
            if (path.startsWith(prefix)) result.put(path.substring(prefix.length()), data);
        });
        return Collections.unmodifiableMap(result);
    }

    YamlConfiguration yaml(String name) throws IOException {
        String text = file(name);
        if (text == null) return null;
        YamlConfiguration yaml = new YamlConfiguration();
        try { yaml.loadFromString(text); }
        catch (InvalidConfigurationException ex) { throw new IOException("Invalid " + name, ex); }
        return yaml;
    }
}
