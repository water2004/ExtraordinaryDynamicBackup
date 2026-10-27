package org.edtp.edb.config;

import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** One server-side permission policy, including discovery and region management. */
public final class BackupConfig {
    private static volatile int permissionLevel = 3;
    private BackupConfig() {}

    public static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve("extraordinarydynamicbackup.json");
    }

    public static int permissionLevel() { return permissionLevel; }

    public static void reload() throws IOException {
        Path path = path();
        if (!Files.exists(path)) {
            Files.createDirectories(path.getParent());
            Files.writeString(path, "{\n  \"permissionLevel\": 3\n}\n", java.nio.file.StandardOpenOption.CREATE_NEW);
        }
        // Parse fully before publishing: an invalid reload must not lower permissions.
        permissionLevel = readPermissionLevel(path);
    }

    public static int readPermissionLevel(Path path) throws IOException {
        if (Files.size(path) > 16_384) throw new IOException("EDB config exceeds 16 KiB");
        try {
            var json = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
            if (!json.has("permissionLevel")) return 3;
            var value = json.getAsJsonPrimitive("permissionLevel");
            if (!value.isNumber()) throw new IllegalArgumentException();
            int level = value.getAsBigDecimal().intValueExact();
            if (level < 0 || level > 4) throw new IllegalArgumentException();
            return level;
        } catch (RuntimeException e) {
            throw new IOException("Invalid EDB config: permissionLevel must be an integer from 0 to 4", e);
        }
    }

    public static boolean allowed(CommandSourceStack source) {
        return switch (permissionLevel) {
            case 0 -> true;
            case 1 -> Commands.hasPermission(Commands.LEVEL_MODERATORS).test(source);
            case 2 -> Commands.hasPermission(Commands.LEVEL_GAMEMASTERS).test(source);
            case 3 -> Commands.hasPermission(Commands.LEVEL_ADMINS).test(source);
            case 4 -> Commands.hasPermission(Commands.LEVEL_OWNERS).test(source);
            default -> false;
        };
    }
}
