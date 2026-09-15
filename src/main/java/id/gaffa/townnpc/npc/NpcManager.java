package id.gaffa.townnpc.npc;

import com.mojang.authlib.GameProfile;
import id.gaffa.townnpc.Settings;
import id.gaffa.townnpc.nms.PacketBridge;
import id.gaffa.townnpc.path.Waypoint;
import id.gaffa.townnpc.skin.SkinData;
import net.minecraft.network.protocol.Packet;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

public final class NpcManager {
    private static final long READY_DELAY_MILLIS = 1000L;
    private static final int IDLE_TICKS_BEFORE_SLEEP = 100;
    private static final long AUTOSAVE_INTERVAL_TICKS = 100L;
    private static final long MAX_RELATIVE_DELTA = Short.MAX_VALUE;

    private final Plugin plugin;
    private final File file;
    private final Map<String, Npc> npcs = new ConcurrentSkipListMap<>();
    private final Map<UUID, Long> readyAt = new ConcurrentHashMap<>();
    private final AtomicInteger generation = new AtomicInteger();
    private volatile Settings settings;
    private volatile Mover mover;
    private volatile boolean dirty;
    private long saveSequence;
    private long writtenSequence;

    public NpcManager(Plugin plugin, Settings settings) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "npcs.yml");
        applySettings(settings);
    }

    public void applySettings(Settings settings) {
        this.settings = settings;
        this.mover = new Mover(settings);
    }

    public Settings settings() {
        return settings;
    }

    public Collection<Npc> all() {
        return Collections.unmodifiableCollection(npcs.values());
    }

    public Npc get(String name) {
        return name == null ? null : npcs.get(name.toLowerCase(Locale.ROOT));
    }

    public int count() {
        return npcs.size();
    }

    public Npc create(String name, Location location) {
        Npc npc = new Npc(name, UUID.randomUUID(), location.getWorld().getName(), settings.defaultSpeed());
        npc.waypoints().add(Waypoint.at(location));
        npc.resetTo(0);
        npcs.put(name.toLowerCase(Locale.ROOT), npc);
        markDirty();
        start(npc);
        return npc;
    }

    public void remove(Npc npc) {
        synchronized (npc) {
            npc.removed = true;
            despawnForAll(npc);
        }
        npcs.remove(npc.name().toLowerCase(Locale.ROOT), npc);
        markDirty();
    }

    public void edit(Npc npc, Runnable change) {
        synchronized (npc) {
            change.run();
        }
        markDirty();
    }

    public void refresh(Npc npc) {
        synchronized (npc) {
            List<Player> viewers = new ArrayList<>(npc.viewers);
            despawnForAll(npc);
            for (Player viewer : viewers) {
                spawnFor(npc, viewer);
            }
        }
    }

    public void restart(Npc npc) {
        synchronized (npc) {
            npc.resetTo(0);
            List<Player> viewers = new ArrayList<>(npc.viewers);
            despawnForAll(npc);
            for (Player viewer : viewers) {
                spawnFor(npc, viewer);
            }
        }
        markDirty();
    }

    public void markDirty() {
        dirty = true;
    }

    public void startAutosave() {
        Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, task -> {
            if (dirty) {
                save(true);
            }
        }, AUTOSAVE_INTERVAL_TICKS, AUTOSAVE_INTERVAL_TICKS);
    }

    private void start(Npc npc) {
        npc.generation = generation.get();
        npc.idleTicks = 0;
        scheduleRegion(npc, 1);
    }

    private boolean alive(Npc npc) {
        return plugin.isEnabled() && !npc.removed && npc.generation == generation.get();
    }

    private void scheduleRegion(Npc npc, long delay) {
        World world = Bukkit.getWorld(npc.worldName());
        if (world == null) {
            scheduleGlobal(npc, AUTOSAVE_INTERVAL_TICKS);
            return;
        }
        int chunkX;
        int chunkZ;
        synchronized (npc) {
            chunkX = ((int) Math.floor(npc.x)) >> 4;
            chunkZ = ((int) Math.floor(npc.z)) >> 4;
        }
        Bukkit.getRegionScheduler().runDelayed(plugin, world, chunkX, chunkZ, task -> regionTick(npc), delay);
    }

    private void scheduleGlobal(Npc npc, long delay) {
        Bukkit.getGlobalRegionScheduler().runDelayed(plugin, task -> globalPoll(npc), delay);
    }

    private void regionTick(Npc npc) {
        if (!alive(npc)) {
            return;
        }
        boolean idle;
        try {
            idle = tickNpc(npc);
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.WARNING, "Error ticking NPC " + npc.name(), e);
            idle = true;
        }
        if (idle && !settings.simulateWithoutViewers() && ++npc.idleTicks >= IDLE_TICKS_BEFORE_SLEEP) {
            npc.idleTicks = 0;
            scheduleGlobal(npc, settings.viewerCheckInterval());
        } else {
            if (!idle) {
                npc.idleTicks = 0;
            }
            scheduleRegion(npc, 1);
        }
    }

    private void globalPoll(Npc npc) {
        if (!alive(npc)) {
            return;
        }
        World world = Bukkit.getWorld(npc.worldName());
        boolean hasViewers = false;
        synchronized (npc) {
            if (world == null) {
                despawnForAll(npc);
            } else {
                updateViewers(npc, world);
                hasViewers = !npc.viewers.isEmpty();
            }
        }
        if (hasViewers) {
            scheduleRegion(npc, 1);
        } else {
            scheduleGlobal(npc, settings.viewerCheckInterval());
        }
    }

    private boolean tickNpc(Npc npc) {
        synchronized (npc) {
            World world = Bukkit.getWorld(npc.worldName());
            if (world == null) {
                despawnForAll(npc);
                return true;
            }
            npc.tick++;
            if (npc.tick % settings.viewerCheckInterval() == 0) {
                updateViewers(npc, world);
            }
            if (npc.viewers.isEmpty() && !settings.simulateWithoutViewers()) {
                return true;
            }
            if (!npc.paused()) {
                mover.tick(npc, world);
            }
            if (!npc.lookAtPlayers() || !settings.lookEnabled()) {
                npc.headYaw = npc.yaw;
            } else if ((npc.tick & 1) == 0) {
                updateLook(npc);
            }
            if (!npc.viewers.isEmpty()) {
                sendMovement(npc);
            }
            return npc.viewers.isEmpty();
        }
    }

    private void updateViewers(Npc npc, World world) {
        double maxDistSq = settings.viewDistanceSquared();
        Iterator<Player> it = npc.viewers.iterator();
        while (it.hasNext()) {
            Player viewer = it.next();
            if (!viewer.isOnline() || viewer.getWorld() != world
                    || distanceSquared(npc, viewer) > maxDistSq || !PacketBridge.isConnected(viewer)) {
                it.remove();
                if (viewer.isOnline()) {
                    PacketBridge.send(viewer, despawnPackets(npc));
                }
            }
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getWorld() != world || npc.viewers.contains(player) || !isReady(player)) {
                continue;
            }
            if (distanceSquared(npc, player) <= maxDistSq) {
                spawnFor(npc, player);
            }
        }
    }

    private void updateLook(Npc npc) {
        Player best = null;
        double bestDistSq = settings.lookRadiusSquared();
        double facingX = -Math.sin(Math.toRadians(npc.yaw));
        double facingZ = Math.cos(Math.toRadians(npc.yaw));
        double cosHalfFov = Math.cos(Math.toRadians(settings.lookFov() / 2.0));
        for (Player viewer : npc.viewers) {
            Location loc = viewer.getLocation();
            double dx = loc.getX() - npc.x;
            double dz = loc.getZ() - npc.z;
            double dy = loc.getY() - npc.y;
            double distSq = dx * dx + dy * dy + dz * dz;
            if (distSq > bestDistSq || distSq < 1.0e-6) {
                continue;
            }
            double horizontal = Math.sqrt(dx * dx + dz * dz);
            if (horizontal > 1.0e-6 && (dx * facingX + dz * facingZ) / horizontal < cosHalfFov) {
                continue;
            }
            best = viewer;
            bestDistSq = distSq;
        }
        npc.lookTarget = best;
        if (best == null) {
            npc.headYaw = npc.yaw;
            npc.pitch = npc.waitTicks > 0 ? npc.waitPitch : 0f;
            return;
        }
        Location eye = best.getEyeLocation();
        double dx = eye.getX() - npc.x;
        double dy = eye.getY() - (npc.y + (npc.sneakingShown ? 1.27 : 1.62));
        double dz = eye.getZ() - npc.z;
        npc.headYaw = Mover.yawTowards(dx, dz);
        npc.pitch = Mover.pitchTowards(dx, dy, dz);
    }

    private void sendMovement(Npc npc) {
        boolean sneaking = npc.sneakSegment || npc.lowCeiling;
        List<Packet<?>> packets = npc.packetBuffer;
        packets.clear();

        if (sneaking != npc.sneakingShown) {
            npc.sneakingShown = sneaking;
            packets.add(PacketBridge.entityData(npc.entityId(), sneaking));
        }

        long fx = Math.round(npc.x * 4096.0);
        long fy = Math.round(npc.y * 4096.0);
        long fz = Math.round(npc.z * 4096.0);
        long dx = fx - npc.sentX;
        long dy = fy - npc.sentY;
        long dz = fz - npc.sentZ;
        byte yaw = PacketBridge.angle(npc.yaw);
        byte pitch = PacketBridge.angle(npc.pitch);
        boolean moved = dx != 0 || dy != 0 || dz != 0;
        boolean rotated = yaw != npc.sentYaw || pitch != npc.sentPitch;

        if (Math.abs(dx) > MAX_RELATIVE_DELTA || Math.abs(dy) > MAX_RELATIVE_DELTA || Math.abs(dz) > MAX_RELATIVE_DELTA) {
            packets.add(PacketBridge.teleport(npc.entityId(), npc.x, npc.y, npc.z, npc.yaw, npc.pitch, npc.onGround));
            npc.sentX = fx;
            npc.sentY = fy;
            npc.sentZ = fz;
            npc.sentYaw = yaw;
            npc.sentPitch = pitch;
        } else if (moved) {
            packets.add(PacketBridge.moveRelative(npc.entityId(), (short) dx, (short) dy, (short) dz,
                    npc.yaw, npc.pitch, npc.onGround));
            npc.sentX += dx;
            npc.sentY += dy;
            npc.sentZ += dz;
            npc.sentYaw = yaw;
            npc.sentPitch = pitch;
        } else if (rotated) {
            packets.add(PacketBridge.rotate(npc.entityId(), npc.yaw, npc.pitch, npc.onGround));
            npc.sentYaw = yaw;
            npc.sentPitch = pitch;
        }

        byte headYaw = PacketBridge.angle(npc.headYaw);
        if (headYaw != npc.sentHeadYaw) {
            npc.sentHeadYaw = headYaw;
            packets.add(PacketBridge.headRotation(npc.entityId(), npc.headYaw));
        }

        if (packets.isEmpty()) {
            return;
        }
        for (Player viewer : npc.viewers) {
            PacketBridge.send(viewer, packets);
        }
    }

    private void spawnFor(Npc npc, Player player) {
        if (npc.viewers.isEmpty()) {
            npc.sentX = Math.round(npc.x * 4096.0);
            npc.sentY = Math.round(npc.y * 4096.0);
            npc.sentZ = Math.round(npc.z * 4096.0);
            npc.sentYaw = PacketBridge.angle(npc.yaw);
            npc.sentPitch = PacketBridge.angle(npc.pitch);
            npc.sentHeadYaw = PacketBridge.angle(npc.headYaw);
        }
        SkinData skin = npc.skin();
        GameProfile profile = PacketBridge.profile(npc.uuid(), npc.name(),
                skin == null ? null : skin.value(), skin == null ? null : skin.signature());
        List<Packet<?>> packets = new ArrayList<>(4);
        packets.add(PacketBridge.playerInfoAdd(profile));
        packets.add(PacketBridge.addPlayer(npc.entityId(), npc.uuid(),
                npc.sentX / 4096.0, npc.sentY / 4096.0, npc.sentZ / 4096.0, npc.yaw, npc.pitch, npc.headYaw));
        packets.add(PacketBridge.entityData(npc.entityId(), npc.sneakingShown));
        packets.add(PacketBridge.headRotation(npc.entityId(), npc.headYaw));
        PacketBridge.send(player, packets);
        npc.viewers.add(player);
    }

    private List<Packet<?>> despawnPackets(Npc npc) {
        return List.of(PacketBridge.removeEntity(npc.entityId()), PacketBridge.playerInfoRemove(npc.uuid()));
    }

    private void despawnForAll(Npc npc) {
        if (npc.viewers.isEmpty()) {
            return;
        }
        List<Packet<?>> packets = despawnPackets(npc);
        for (Player viewer : npc.viewers) {
            if (viewer.isOnline()) {
                PacketBridge.send(viewer, packets);
            }
        }
        npc.viewers.clear();
    }

    public void despawnAll() {
        for (Npc npc : npcs.values()) {
            synchronized (npc) {
                despawnForAll(npc);
            }
        }
    }

    public void onJoin(Player player) {
        readyAt.put(player.getUniqueId(), System.currentTimeMillis() + READY_DELAY_MILLIS);
    }

    public void onClientReset(Player player) {
        List<Packet<?>> infoRemovals = new ArrayList<>();
        for (Npc npc : npcs.values()) {
            synchronized (npc) {
                if (npc.viewers.remove(player)) {
                    infoRemovals.add(PacketBridge.playerInfoRemove(npc.uuid()));
                }
            }
        }
        if (!infoRemovals.isEmpty() && player.isOnline()) {
            PacketBridge.send(player, infoRemovals);
        }
    }

    public void onQuit(Player player) {
        readyAt.remove(player.getUniqueId());
        for (Npc npc : npcs.values()) {
            synchronized (npc) {
                npc.viewers.remove(player);
                if (npc.lookTarget == player) {
                    npc.lookTarget = null;
                }
            }
        }
    }

    private boolean isReady(Player player) {
        Long ready = readyAt.get(player.getUniqueId());
        return ready != null && System.currentTimeMillis() >= ready && PacketBridge.isConnected(player);
    }

    private static double distanceSquared(Npc npc, Player player) {
        Location loc = player.getLocation();
        double dx = loc.getX() - npc.x;
        double dy = loc.getY() - npc.y;
        double dz = loc.getZ() - npc.z;
        return dx * dx + dy * dy + dz * dz;
    }

    public void load() {
        generation.incrementAndGet();
        despawnAll();
        npcs.clear();
        if (!file.exists()) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = yaml.getConfigurationSection("npcs");
        if (root == null) {
            return;
        }
        for (String key : root.getKeys(false)) {
            ConfigurationSection section = root.getConfigurationSection(key);
            if (section == null) {
                continue;
            }
            try {
                Npc npc = loadNpc(key, section);
                if (npc != null) {
                    npcs.put(npc.name().toLowerCase(Locale.ROOT), npc);
                    start(npc);
                }
            } catch (RuntimeException e) {
                plugin.getLogger().log(Level.WARNING, "Skipping invalid NPC entry '" + key + "'", e);
            }
            if (npcs.size() >= settings.maxNpcs()) {
                plugin.getLogger().warning("limits.max-npcs reached, remaining entries in npcs.yml were ignored");
                break;
            }
        }
        plugin.getLogger().info("Loaded " + npcs.size() + " NPC(s)");
    }

    private Npc loadNpc(String key, ConfigurationSection section) {
        String name = section.getString("name", key);
        if (!Npc.NAME.matcher(name).matches()) {
            plugin.getLogger().warning("Skipping NPC with invalid name '" + name + "'");
            return null;
        }
        UUID uuid;
        try {
            uuid = UUID.fromString(section.getString("uuid", ""));
        } catch (IllegalArgumentException e) {
            uuid = UUID.randomUUID();
        }
        String world = section.getString("world", "world");
        double speed = section.getDouble("speed", settings.defaultSpeed());
        if (!Double.isFinite(speed)) {
            speed = settings.defaultSpeed();
        }
        speed = Math.max(Settings.MIN_SPEED, Math.min(Settings.MAX_SPEED, speed));

        Npc npc = new Npc(name, uuid, world, speed);
        npc.setLookAtPlayers(section.getBoolean("look-at-players", true));
        npc.setPaused(section.getBoolean("paused", false));

        String skinValue = section.getString("skin.value");
        String skinSignature = section.getString("skin.signature");
        if (SkinData.isValid(skinValue, skinSignature)) {
            npc.setSkin(section.getString("skin.source", ""), new SkinData(skinValue, skinSignature));
        }

        for (Map<?, ?> raw : section.getMapList("waypoints")) {
            Waypoint waypoint = Waypoint.deserialize(raw);
            if (waypoint != null) {
                npc.waypoints().add(waypoint);
            }
            if (npc.waypoints().size() >= settings.maxWaypoints()) {
                break;
            }
        }
        if (npc.waypoints().isEmpty()) {
            plugin.getLogger().warning("NPC '" + name + "' has no valid waypoints, skipping");
            return null;
        }
        npc.resetTo(0);
        return npc;
    }

    public void save() {
        save(false);
    }

    public void save(boolean async) {
        dirty = false;
        YamlConfiguration yaml = new YamlConfiguration();
        for (Npc npc : npcs.values()) {
            synchronized (npc) {
                ConfigurationSection section = yaml.createSection("npcs." + npc.name());
                section.set("name", npc.name());
                section.set("uuid", npc.uuid().toString());
                section.set("world", npc.worldName());
                section.set("speed", npc.speed());
                section.set("look-at-players", npc.lookAtPlayers());
                section.set("paused", npc.paused());
                if (npc.skin() != null) {
                    section.set("skin.source", npc.skinSource());
                    section.set("skin.value", npc.skin().value());
                    section.set("skin.signature", npc.skin().signature());
                }
                List<Map<String, Object>> waypoints = new ArrayList<>(npc.waypoints().size());
                for (Waypoint waypoint : npc.waypoints()) {
                    waypoints.add(waypoint.serialize());
                }
                section.set("waypoints", waypoints);
            }
        }
        long sequence;
        synchronized (file) {
            sequence = ++saveSequence;
        }
        if (async && plugin.isEnabled()) {
            Bukkit.getAsyncScheduler().runNow(plugin, task -> write(yaml, sequence));
        } else {
            write(yaml, sequence);
        }
    }

    private void write(YamlConfiguration yaml, long sequence) {
        synchronized (file) {
            if (sequence < writtenSequence) {
                return;
            }
            writtenSequence = sequence;
            try {
                yaml.save(file);
            } catch (IOException e) {
                plugin.getLogger().log(Level.SEVERE, "Could not save " + file.getName(), e);
            }
        }
    }
}
