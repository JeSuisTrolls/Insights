package dev.frankheijden.insights.api.listeners;

import dev.frankheijden.insights.api.InsightsPlugin;
import dev.frankheijden.insights.api.addons.Region;
import dev.frankheijden.insights.api.concurrent.ScanOptions;
import dev.frankheijden.insights.api.concurrent.storage.ChunkStorage;
import dev.frankheijden.insights.api.concurrent.storage.AddonStorage;
import dev.frankheijden.insights.api.concurrent.storage.DistributionStorage;
import dev.frankheijden.insights.api.concurrent.storage.Storage;
import dev.frankheijden.insights.api.concurrent.storage.WorldStorage;
import dev.frankheijden.insights.api.config.LimitEnvironment;
import dev.frankheijden.insights.api.config.Messages;
import dev.frankheijden.insights.api.config.limits.Limit;
import dev.frankheijden.insights.api.config.limits.LimitInfo;
import dev.frankheijden.insights.api.objects.InsightsBase;
import dev.frankheijden.insights.api.objects.chunk.ChunkPart;
import dev.frankheijden.insights.api.objects.wrappers.ScanObject;
import dev.frankheijden.insights.api.tasks.ScanTask;
import dev.frankheijden.insights.api.utils.ChunkUtils;
import dev.frankheijden.insights.api.utils.StringUtils;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Level;

public abstract class InsightsListener extends InsightsBase implements Listener {

    protected InsightsListener(InsightsPlugin plugin) {
        super(plugin);
    }

    protected void handleModification(Block block, int amount) {
        handleModification(block.getLocation(), block.getType(), amount);
    }

    protected void handleModification(BlockState state, int amount) {
        handleModification(state.getLocation(), state.getType(), amount);
    }

    protected void handleModification(Location location, Material material, int amount) {
        if (amount < 0) {
            handleModification(location, material, Material.AIR, -amount);
        } else {
            handleModification(location, Material.AIR, material, amount);
        }
    }

    protected void handleModification(Location location, Consumer<Storage> storageConsumer) {
        UUID worldUid = location.getWorld().getUID();
        long chunkKey = ChunkUtils.getKey(location);
        plugin.getWorldStorage().getWorld(worldUid).get(chunkKey).ifPresent(storageConsumer);
        plugin.getAddonManager().getRegion(location)
                .flatMap(region -> plugin.getAddonStorage().get(region.getKey()))
                .ifPresent(storageConsumer);
    }

    protected void handleModification(Location location, Material from, Material to, int amount) {
        handleModification(location, storage -> {
            storage.modify(ScanObject.of(from), -amount);
            storage.modify(ScanObject.of(to), amount);
        });
    }

    protected void handleModification(Location location, EntityType entity, int amount) {
        handleModification(location, storage -> storage.modify(ScanObject.of(entity), amount));
    }

    protected boolean handleAddition(Player player, Location location, ScanObject<?> item, int delta) {
        return handleAddition(player, location, item, delta, true);
    }

    protected boolean handleAddition(
            Player player,
            Location location,
            ScanObject<?> item,
            int delta,
            boolean included
    ) {
        Optional<Region> regionOptional = plugin.getAddonManager().getRegionAware(location, player, item);
        var chunk = location.getChunk();
        var world = location.getWorld();
        UUID worldUid = world.getUID();
        long chunkKey = ChunkUtils.getKey(chunk);

        boolean queued;
        String area;
        LimitEnvironment env;
        if (regionOptional.isPresent()) {
            var region = regionOptional.get();
            queued = plugin.getAddonScanTracker().isQueued(region.getKey());
            area = plugin.getAddonManager().getAddon(region.getAddon()).getAreaName();
            env = new LimitEnvironment(player, world.getName(), region.getAddon());
            plugin.getLogger().info("[DEBUG][handleAddition] player=" + player.getName()
                    + " item=" + item + " delta=" + delta
                    + " area=region key=" + region.getKey() + " queued=" + queued);
        } else {
            queued = plugin.getWorldChunkScanTracker().isQueued(worldUid, chunkKey);
            area = "chunk";
            env = new LimitEnvironment(player, world.getName());
            plugin.getLogger().info("[DEBUG][handleAddition] player=" + player.getName()
                    + " item=" + item + " delta=" + delta
                    + " area=chunk chunkKey=" + chunkKey + " queued=" + queued);
        }

        if (queued) {
            plugin.getLogger().info("[DEBUG][handleAddition] BLOCKED - scan in progress for area=" + area);
            if (plugin.getSettings().canReceiveAreaScanNotifications(player)) {
                plugin.getMessages().getMessage(Messages.Key.AREA_SCAN_QUEUED).addTemplates(
                        Messages.tagOf("area", area)
                ).sendTo(player);
            }
            return true;
        }

        Optional<Limit> limitOptional = plugin.getLimits().getFirstLimit(item, env);
        if (limitOptional.isEmpty()) {
            plugin.getLogger().info("[DEBUG][handleAddition] no limit found for item=" + item + ", allowing");
            return false;
        }
        var limit = limitOptional.get();
        var limitInfo = limit.getLimit(item);
        plugin.getLogger().info("[DEBUG][handleAddition] limit found: name=" + limitInfo.getName()
                + " max=" + limitInfo.getLimit());

        if (regionOptional.isEmpty() && limit.getSettings().isDisallowedPlacementOutsideRegion()) {
            plugin.getLogger().info("[DEBUG][handleAddition] BLOCKED - disallowed placement outside region");
            plugin.getMessages().getMessage(Messages.Key.LIMIT_DISALLOWED_PLACEMENT).addTemplates(TagResolver.resolver(
                    Messages.tagOf("name", limitInfo.getName()),
                    Messages.tagOf("area", area)
            )).sendTo(player);
            return true;
        }

        Consumer<Storage> storageConsumer = storage -> {
            if (included && regionOptional.isEmpty()) {
                storage.modify(item, -delta);
            }
            if (plugin.getSettings().canReceiveAreaScanNotifications(player)) {
                plugin.getMessages().getMessage(Messages.Key.AREA_SCAN_COMPLETED).sendTo(player);
            }
        };

        Optional<Storage> storageOptional;
        if (regionOptional.isPresent()) {
            storageOptional = handleAddonAddition(player, regionOptional.get(), storageConsumer);
        } else {
            storageOptional = handleChunkAddition(player, chunk, storageConsumer);
        }

        if (storageOptional.isEmpty()) {
            plugin.getLogger().info("[DEBUG][handleAddition] BLOCKED - storage empty, scan triggered");
            return true;
        }

        var storage = storageOptional.get();
        long count = storage.count(limit, item);
        plugin.getLogger().info("[DEBUG][handleAddition] count=" + count + " delta=" + delta
                + " limit=" + limitInfo.getLimit() + " -> " + (count + delta > limitInfo.getLimit() ? "BLOCKED" : "ALLOWED"));

        if (count + delta > limitInfo.getLimit()) {
            plugin.getMessages().getMessage(Messages.Key.LIMIT_REACHED).addTemplates(
                    Messages.tagOf("limit", StringUtils.pretty(limitInfo.getLimit())),
                    Messages.tagOf("name", limitInfo.getName()),
                    Messages.tagOf("area", area)
            ).sendTo(player);
            return true;
        }
        return false;
    }

    protected void evaluateAddition(Player player, Location location, ScanObject<?> item, int delta) {
        Optional<Region> regionOptional = plugin.getAddonManager().getRegionAware(location, player, item);
        World world = location.getWorld();
        long chunkKey = ChunkUtils.getKey(location);

        LimitEnvironment env;
        Optional<Storage> storageOptional;
        if (regionOptional.isPresent()) {
            Region region = regionOptional.get();
            env = new LimitEnvironment(player, world.getName(), region.getAddon());
            storageOptional = plugin.getAddonStorage().get(region.getKey());
        } else {
            env = new LimitEnvironment(player, world.getName());
            storageOptional = plugin.getWorldStorage().getWorld(world.getUID()).get(chunkKey);
        }

        if (storageOptional.isEmpty()) return;
        Storage storage = storageOptional.get();

        Optional<Limit> limitOptional = plugin.getLimits().getFirstLimit(item, env);
        if (limitOptional.isEmpty()) return;

        Limit limit = limitOptional.get();
        LimitInfo limitInfo = limit.getLimit(item);
        long count = storage.count(limit, item);

        if (player.hasPermission("insights.notifications")) {
            float progress = (float) (count + delta) / limitInfo.getLimit();
            plugin.getNotifications().getCachedProgress(player.getUniqueId(), Messages.Key.LIMIT_NOTIFICATION)
                    .progress(progress)
                    .add(player)
                    .create()
                    .addTemplates(
                            Messages.tagOf("name", limitInfo.getName()),
                            Messages.tagOf("count", StringUtils.pretty(count + delta)),
                            Messages.tagOf("limit", StringUtils.pretty(limitInfo.getLimit()))
                    )
                    .send();
        }
    }

    private Optional<Storage> handleChunkAddition(
            Player player,
            Chunk chunk,
            Consumer<Storage> storageConsumer
    ) {
        UUID worldUid = chunk.getWorld().getUID();
        long chunkKey = ChunkUtils.getKey(chunk);

        WorldStorage worldStorage = plugin.getWorldStorage();
        ChunkStorage chunkStorage = worldStorage.getWorld(worldUid);
        Optional<Storage> storageOptional = chunkStorage.get(chunkKey);
        plugin.getLogger().info("[DEBUG][handleChunkAddition] chunkKey=" + chunkKey
                + " world=" + chunk.getWorld().getName() + " storagePresent=" + storageOptional.isPresent());

        if (storageOptional.isEmpty()) {
            if (plugin.getSettings().canReceiveAreaScanNotifications(player)) {
                plugin.getMessages().getMessage(Messages.Key.AREA_SCAN_STARTED).addTemplates(
                        Messages.tagOf("area", "chunk")
                ).sendTo(player);
            }

            plugin.getChunkContainerExecutor().submit(chunk)
                    .thenAccept(storage -> {
                        plugin.getLogger().info("[DEBUG][handleChunkAddition] scan COMPLETE chunkKey=" + chunkKey);
                        storageConsumer.accept(storage);
                    })
                    .exceptionally(th -> {
                        plugin.getLogger().log(Level.SEVERE, th, th::getMessage);
                        return null;
                    });
        }
        return storageOptional;
    }

    private Optional<Storage> handleAddonAddition(
            Player player,
            Region region,
            Consumer<Storage> storageConsumer
    ) {
        String key = region.getKey();

        AddonStorage addonStorage = plugin.getAddonStorage();
        Optional<Storage> storageOptional = addonStorage.get(key);
        plugin.getLogger().info("[DEBUG][handleAddonAddition] key=" + key
                + " storagePresent=" + storageOptional.isPresent());
        if (storageOptional.isEmpty()) {
            if (plugin.getSettings().canReceiveAreaScanNotifications(player)) {
                plugin.getMessages().getMessage(Messages.Key.AREA_SCAN_STARTED).addTemplates(
                        Messages.tagOf("area", plugin.getAddonManager().getAddon(region.getAddon()).getAreaName())
                ).sendTo(player);
            }

            scanRegion(player, region, storageConsumer);
            return Optional.empty();
        }
        return storageOptional;
    }

    protected void handleRemoval(Player player, Location location, ScanObject<?> item, int delta) {
        handleRemoval(player, location, item, delta, true);
    }

    protected void handleRemoval(Player player, Location location, ScanObject<?> item, int delta, boolean included) {
        Optional<Region> regionOptional = plugin.getAddonManager().getRegionAware(location, player, item);
        Chunk chunk = location.getChunk();
        World world = location.getWorld();
        UUID worldUid = world.getUID();
        long chunkKey = ChunkUtils.getKey(chunk);
        UUID uuid = player.getUniqueId();

        boolean queued;
        LimitEnvironment env;
        Optional<Storage> storageOptional;
        if (regionOptional.isPresent()) {
            Region region = regionOptional.get();
            queued = plugin.getAddonScanTracker().isQueued(region.getKey());
            env = new LimitEnvironment(player, world.getName(), region.getAddon());
            storageOptional = plugin.getAddonStorage().get(region.getKey());
        } else {
            queued = plugin.getWorldChunkScanTracker().isQueued(worldUid, chunkKey);
            env = new LimitEnvironment(player, world.getName());
            storageOptional = plugin.getWorldStorage().getWorld(worldUid).get(chunkKey);
        }

        storageOptional.ifPresent(storage -> storage.modify(item, -delta));

        if (player.hasPermission("insights.notifications")) {
            if (queued) return;

            Optional<Limit> limitOptional = plugin.getLimits().getFirstLimit(item, env);
            if (limitOptional.isEmpty()) return;
            Limit limit = limitOptional.get();
            LimitInfo limitInfo = limit.getLimit(item);

            Consumer<Storage> notification = storage -> {
                long count = storage.count(limit, item);
                float progress = (float) count / limitInfo.getLimit();
                plugin.getNotifications().getCachedProgress(uuid, Messages.Key.LIMIT_NOTIFICATION)
                        .progress(progress)
                        .add(player)
                        .create()
                        .addTemplates(
                                Messages.tagOf("name", limitInfo.getName()),
                                Messages.tagOf("count", StringUtils.pretty(count)),
                                Messages.tagOf("limit", StringUtils.pretty(limitInfo.getLimit()))
                        )
                        .send();
            };

            if (storageOptional.isPresent()) {
                notification.accept(storageOptional.get());
                return;
            }

            Consumer<Storage> storageConsumer = storage -> {
                if (included && regionOptional.isEmpty()) storage.modify(item, -delta);
                notification.accept(storage);
            };

            if (regionOptional.isPresent()) {
                scanRegion(player, regionOptional.get(), storageConsumer);
            } else {
                plugin.getChunkContainerExecutor().submit(chunk)
                        .thenAccept(storageConsumer)
                        .exceptionally(th -> {
                            plugin.getLogger().log(Level.SEVERE, th, th::getMessage);
                            return null;
                        });
            }
        }
    }

    private void scanRegion(Player player, Region region, Consumer<Storage> storageConsumer) {
        plugin.getLogger().info("[DEBUG][scanRegion] START key=" + region.getKey()
                + " chunkParts=" + region.toChunkParts().size());
        plugin.getAddonScanTracker().add(region.getKey());
        List<ChunkPart> chunkParts = region.toChunkParts();
        ScanTask.scan(
                plugin,
                player,
                chunkParts,
                chunkParts.size(),
                ScanOptions.scanOnly(),
                player.hasPermission("insights.notifications"),
                DistributionStorage::new,
                (storage, loc, acc) -> storage.mergeRight(acc),
                storage -> {
                    plugin.getLogger().info("[DEBUG][scanRegion] COMPLETE key=" + region.getKey());
                    plugin.getAddonScanTracker().remove(region.getKey());
                    plugin.getAddonStorage().put(region.getKey(), storage);
                    storageConsumer.accept(storage);
                }
        );
    }
}
