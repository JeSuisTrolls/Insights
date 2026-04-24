package dev.frankheijden.insights.placeholders;

import dev.frankheijden.insights.api.InsightsPlugin;
import dev.frankheijden.insights.api.addons.Region;
import dev.frankheijden.insights.api.concurrent.ScanOptions;
import dev.frankheijden.insights.api.concurrent.storage.Storage;
import dev.frankheijden.insights.api.config.LimitEnvironment;
import dev.frankheijden.insights.api.config.limits.Limit;
import dev.frankheijden.insights.api.objects.chunk.ChunkPart;
import dev.frankheijden.insights.api.objects.wrappers.ScanObject;
import dev.frankheijden.insights.api.tasks.ScanTask;
import dev.frankheijden.insights.api.utils.ChunkUtils;
import dev.frankheijden.insights.api.utils.StringUtils;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class InsightsPlaceholderExpansion extends PlaceholderExpansion {

    private final InsightsPlugin plugin;
    private final Set<String> scannedRegions = Collections.newSetFromMap(new ConcurrentHashMap<>());

    public InsightsPlaceholderExpansion(InsightsPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getIdentifier() {
        return "insights";
    }

    @Override
    public String getAuthor() {
        return String.join(", ", plugin.getDescription().getAuthors());
    }

    @Override
    public String getVersion() {
        return plugin.getDescription().getVersion();
    }

    /**
     * Clears the scanned regions cache, forcing a rescan on next placeholder request.
     */
    public void clearScannedRegions() {
        scannedRegions.clear();
    }

    @Override
    public String onPlaceholderRequest(Player player, String identifier) {
        if (identifier == null) return "";
        String[] args = identifier.split("_");
        switch (args[0].toLowerCase(Locale.ENGLISH)) {
            case "limits":
                if (args.length < 3) break;

                String itemString = StringUtils.join(args, "_", 2).toUpperCase(Locale.ENGLISH);
                final ScanObject<?> item;
                try {
                    item = ScanObject.parse(itemString);
                } catch (IllegalArgumentException ex) {
                    return "";
                }

                Location location = player.getLocation();
                World world = location.getWorld();
                UUID worldUid = world.getUID();
                LimitEnvironment env = new LimitEnvironment(player, world.getName());
                Optional<Limit> limitOptional = plugin.getLimits().getFirstLimit(item, env);
                if (!limitOptional.isPresent()) break;

                Limit limit = limitOptional.get();
                switch (args[1].toLowerCase(Locale.ENGLISH)) {
                    case "name": return limit.getLimit(item).getName();
                    case "max": return String.valueOf(limit.getLimit(item).getLimit());
                    case "count":
                        Optional<Region> regionOptional = plugin.getAddonManager().getRegion(location);
                        Optional<Storage> storageOptional;
                        if (regionOptional.isPresent()) {
                            Region region = regionOptional.get();
                            String key = region.getKey();
                            storageOptional = plugin.getAddonStorage().get(key);

                            boolean needsScan = !scannedRegions.contains(key)
                                    && !plugin.getAddonScanTracker().isQueued(key);

                            if (needsScan) {
                                scannedRegions.add(key);
                                plugin.getAddonScanTracker().add(key);
                                List<ChunkPart> chunkParts = region.toChunkParts();
                                ScanTask.scan(plugin, chunkParts, chunkParts.size(), ScanOptions.scanOnly(), info -> {}, storage -> {
                                    plugin.getAddonScanTracker().remove(key);
                                    plugin.getAddonStorage().put(key, storage);
                                });
                                return "";
                            }
                        } else {
                            long chunkKey = ChunkUtils.getKey(location);
                            storageOptional = plugin.getWorldStorage().getWorld(worldUid).get(chunkKey);
                            if (storageOptional.isEmpty()) {
                                plugin.getChunkContainerExecutor().submit(location.getChunk());
                            }
                        }
                        return storageOptional.map(storage -> String.valueOf(storage.count(limit, item))).orElse("");
                    case "count-chunk":
                        long chunkKeyOnly = ChunkUtils.getKey(location);
                        return plugin.getWorldStorage().getWorld(worldUid).get(chunkKeyOnly)
                                .map(storage -> String.valueOf(storage.count(limit, item)))
                                .orElse("");
                    default: break;
                }
                break;
            default: break;
        }
        return "";
    }
}
