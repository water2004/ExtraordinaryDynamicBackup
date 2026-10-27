package org.edtp.edb.region;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.edtp.edb.storage.SnapshotIO;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.TreeMap;

/** Small per-world definitions; all calls belong on the catalog's serial IO executor. */
public final class RegionRepository {
    private final Path file;
    public RegionRepository(Path root) { file = root.resolve("regions.json"); }

    public Map<String, RegionSelection> list() throws IOException {
        var regions = new TreeMap<String, RegionSelection>();
        if (!Files.exists(file)) return regions;
        if (Files.size(file) > 1024 * 1024) throw new IOException("Region definitions exceed 1 MiB");
        try {
            var json = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            if (integer(json, "format") != 1) throw new IOException("Unsupported region definition format");
            var entries = json.getAsJsonObject("regions");
            if (entries.size() > 1024) throw new IOException("At most 1024 named regions");
            for (var entry : entries.entrySet()) {
                SnapshotIO.validateName(entry.getKey());
                var region = entry.getValue().getAsJsonObject();
                String dimension = region.get("dimension").getAsString();
                if (net.minecraft.resources.Identifier.tryParse(dimension) == null) throw new IllegalArgumentException("Invalid dimension");
                regions.put(entry.getKey(), new RegionSelection(dimension, integer(region, "minX"), integer(region, "minZ"),
                        integer(region, "maxX"), integer(region, "maxZ")));
            }
            return regions;
        } catch (RuntimeException e) { throw new IOException("Invalid regions.json; definitions were not modified", e); }
    }

    private static int integer(JsonObject json, String key) {
        var value = json.getAsJsonPrimitive(key);
        if (!value.isNumber()) throw new IllegalArgumentException("Expected integer: " + key);
        return value.getAsBigDecimal().intValueExact();
    }

    public RegionSelection get(String name) throws IOException {
        SnapshotIO.validateName(name);
        var selection = list().get(name);
        if (selection == null) throw new IOException("Unknown region: " + name);
        return selection;
    }

    public void put(String name, RegionSelection selection, boolean replace) throws IOException {
        SnapshotIO.validateName(name);
        var regions = list();
        if (replace != regions.containsKey(name)) throw new IOException(replace ? "Unknown region: " + name : "Region already exists: " + name);
        if (!replace && regions.size() >= 1024) throw new IOException("At most 1024 named regions");
        regions.put(name, selection);
        save(regions);
    }

    public void remove(String name) throws IOException {
        SnapshotIO.validateName(name);
        var regions = list();
        if (regions.remove(name) == null) throw new IOException("Unknown region: " + name);
        save(regions);
    }

    private void save(Map<String, RegionSelection> regions) throws IOException {
        var gson = new GsonBuilder().setPrettyPrinting().create();
        var json = new JsonObject();
        json.addProperty("format", 1);
        json.add("regions", gson.toJsonTree(regions));
        Files.createDirectories(file.getParent());
        Path temporary = Files.createTempFile(file.getParent(), ".regions-", ".tmp");
        try {
            Files.writeString(temporary, gson.toJson(json) + "\n");
            replace(temporary);
        } finally { Files.deleteIfExists(temporary); }
    }

    private void replace(Path temporary) throws IOException {
        // Windows scanners can briefly hold the newly written file. Retry only
        // sharing/access failures, on the catalog worker; never fall back to truncation.
        for (int attempt = 0; ; attempt++) {
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                return;
            } catch (java.nio.file.AccessDeniedException denied) {
                if (attempt == 4) throw denied;
                try { Thread.sleep(50L << attempt); }
                catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    denied.addSuppressed(interrupted);
                    throw denied;
                }
            }
        }
    }
}
