package id.gaffa.townnpc.skin;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.regex.Pattern;

public final class SkinService {
    private static final Pattern MOJANG_NAME = Pattern.compile("^[A-Za-z0-9_]{1,16}$");
    private static final Pattern HEX_UUID = Pattern.compile("^[0-9a-fA-F]{32}$");
    private static final int MAX_BODY_BYTES = 64 * 1024;
    private static final String PROFILE_URL = "https://api.mojang.com/users/profiles/minecraft/";
    private static final String SESSION_URL = "https://sessionserver.mojang.com/session/minecraft/profile/";

    private final Plugin plugin;
    private final File cacheFile;
    private final HttpClient http;
    private final Duration timeout;
    private final long cacheMillis;
    private final Map<String, CacheEntry> cache = new HashMap<>();
    private final Map<String, CompletableFuture<Optional<SkinData>>> inFlight = new HashMap<>();

    private record CacheEntry(SkinData skin, long fetchedAt) {
    }

    public SkinService(Plugin plugin, int timeoutSeconds, long cacheMillis) {
        this.plugin = plugin;
        this.cacheFile = new File(plugin.getDataFolder(), "skin-cache.yml");
        this.timeout = Duration.ofSeconds(timeoutSeconds);
        this.cacheMillis = cacheMillis;
        this.http = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        loadCache();
    }

    public Optional<SkinData> fromOnlinePlayer(Player player) {
        PlayerProfile profile = player.getPlayerProfile();
        for (ProfileProperty property : profile.getProperties()) {
            if ("textures".equals(property.getName()) && SkinData.isValid(property.getValue(), property.getSignature())) {
                return Optional.of(new SkinData(property.getValue(), property.getSignature()));
            }
        }
        return Optional.empty();
    }

    public void fromMojang(String name, Consumer<Optional<SkinData>> callback) {
        if (name == null || !MOJANG_NAME.matcher(name).matches()) {
            callback.accept(Optional.empty());
            return;
        }
        String key = name.toLowerCase(Locale.ROOT);
        CacheEntry cached = cache.get(key);
        if (cached != null && System.currentTimeMillis() - cached.fetchedAt() < cacheMillis) {
            callback.accept(Optional.of(cached.skin()));
            return;
        }
        CompletableFuture<Optional<SkinData>> future = inFlight.get(key);
        if (future == null) {
            future = CompletableFuture.supplyAsync(() -> fetch(name));
            inFlight.put(key, future);
            CompletableFuture<Optional<SkinData>> started = future;
            future.whenComplete((result, error) -> runSync(() -> {
                inFlight.remove(key, started);
                if (error != null) {
                    plugin.getLogger().log(Level.WARNING, "Skin lookup for '" + name + "' failed", error);
                } else if (result.isPresent()) {
                    cache.put(key, new CacheEntry(result.get(), System.currentTimeMillis()));
                    saveCache();
                }
            }));
        }
        future.whenComplete((result, error) -> runSync(
                () -> callback.accept(error == null && result != null ? result : Optional.empty())));
    }

    private void runSync(Runnable task) {
        if (plugin.isEnabled()) {
            Bukkit.getScheduler().runTask(plugin, task);
        }
    }

    private Optional<SkinData> fetch(String name) {
        try {
            JsonObject profile = getJson(PROFILE_URL + name);
            if (profile == null) {
                return Optional.empty();
            }
            JsonElement id = profile.get("id");
            if (id == null || !id.isJsonPrimitive() || !HEX_UUID.matcher(id.getAsString()).matches()) {
                return Optional.empty();
            }
            JsonObject session = getJson(SESSION_URL + id.getAsString() + "?unsigned=false");
            if (session == null || !session.has("properties") || !session.get("properties").isJsonArray()) {
                return Optional.empty();
            }
            JsonArray properties = session.getAsJsonArray("properties");
            for (JsonElement element : properties) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject property = element.getAsJsonObject();
                if (!"textures".equals(string(property, "name"))) {
                    continue;
                }
                String value = string(property, "value");
                String signature = string(property, "signature");
                if (SkinData.isValid(value, signature)) {
                    return Optional.of(new SkinData(value, signature));
                }
            }
            return Optional.empty();
        } catch (IOException e) {
            plugin.getLogger().warning("Mojang API request for '" + name + "' failed: " + e.getMessage());
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (RuntimeException e) {
            plugin.getLogger().warning("Mojang API returned unexpected data for '" + name + "': " + e.getMessage());
            return Optional.empty();
        }
    }

    private JsonObject getJson(String url) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(timeout)
                .header("Accept", "application/json")
                .header("User-Agent", "TownNPC/" + plugin.getPluginMeta().getVersion())
                .GET()
                .build();
        HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200 || response.body().length > MAX_BODY_BYTES) {
            return null;
        }
        JsonElement parsed = JsonParser.parseString(new String(response.body(), java.nio.charset.StandardCharsets.UTF_8));
        return parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
    }

    private static String string(JsonObject object, String key) {
        JsonElement element = object.get(key);
        return element != null && element.isJsonPrimitive() ? element.getAsString() : null;
    }

    private void loadCache() {
        if (!cacheFile.exists()) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(cacheFile);
        for (String key : yaml.getKeys(false)) {
            String value = yaml.getString(key + ".value");
            String signature = yaml.getString(key + ".signature");
            long fetchedAt = yaml.getLong(key + ".fetched-at", 0L);
            if (MOJANG_NAME.matcher(key).matches() && SkinData.isValid(value, signature)) {
                cache.put(key.toLowerCase(Locale.ROOT), new CacheEntry(new SkinData(value, signature), fetchedAt));
            }
        }
    }

    private void saveCache() {
        YamlConfiguration yaml = new YamlConfiguration();
        long now = System.currentTimeMillis();
        for (Map.Entry<String, CacheEntry> entry : cache.entrySet()) {
            if (now - entry.getValue().fetchedAt() > cacheMillis) {
                continue;
            }
            yaml.set(entry.getKey() + ".value", entry.getValue().skin().value());
            yaml.set(entry.getKey() + ".signature", entry.getValue().skin().signature());
            yaml.set(entry.getKey() + ".fetched-at", entry.getValue().fetchedAt());
        }
        try {
            yaml.save(cacheFile);
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "Could not save skin cache", e);
        }
    }
}
