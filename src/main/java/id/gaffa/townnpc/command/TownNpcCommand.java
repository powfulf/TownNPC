package id.gaffa.townnpc.command;

import id.gaffa.townnpc.Settings;
import id.gaffa.townnpc.TownNpcPlugin;
import id.gaffa.townnpc.npc.Npc;
import id.gaffa.townnpc.npc.NpcManager;
import id.gaffa.townnpc.path.Waypoint;
import id.gaffa.townnpc.skin.SkinData;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

public final class TownNpcCommand implements TabExecutor {
    private static final List<String> ROOT = List.of("create", "remove", "list", "info", "tp", "skin",
            "speed", "look", "path", "pause", "resume", "reload");
    private static final List<String> PATH_SUB = List.of("add", "insert", "set", "move", "remove", "list", "clear", "show");
    private static final int SHOW_SECONDS = 15;
    private static final int SHOW_MAX_POINTS = 2000;

    private final TownNpcPlugin plugin;
    private final NpcManager npcs;

    public TownNpcCommand(TownNpcPlugin plugin, NpcManager npcs) {
        this.plugin = plugin;
        this.npcs = npcs;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            help(sender, label);
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "create" -> create(sender, args);
            case "remove" -> remove(sender, args);
            case "list" -> list(sender);
            case "info" -> info(sender, args);
            case "tp" -> teleport(sender, args);
            case "skin" -> skin(sender, args);
            case "speed" -> speed(sender, args);
            case "look" -> look(sender, args);
            case "path" -> path(sender, label, args);
            case "pause" -> setPaused(sender, args, true);
            case "resume" -> setPaused(sender, args, false);
            case "reload" -> reload(sender);
            default -> help(sender, label);
        }
        return true;
    }

    private void help(CommandSender sender, String label) {
        String c = "/" + label + " ";
        info(sender, "TownNPC commands:");
        info(sender, c + "create <name> [world x y z]  - create an NPC at your position");
        info(sender, c + "remove <name>");
        info(sender, c + "list | info <name> | tp <name>");
        info(sender, c + "skin <name> mojang <account> | player <online player> | clear");
        info(sender, c + "speed <name> <blocks per second>");
        info(sender, c + "look <name> on|off  - turn head toward nearby players");
        info(sender, c + "pause <name> | resume <name>");
        info(sender, c + "path <name> add [jump] [sneak] [wait <seconds>] [at x y z [yaw]]");
        info(sender, c + "path <name> insert <index> [jump] [sneak] [wait <seconds>] [at x y z [yaw]]");
        info(sender, c + "path <name> set <index> jump|sneak <true|false> | wait <seconds>");
        info(sender, c + "path <name> move <index> [at x y z]  - move a waypoint to your position");
        info(sender, c + "path <name> remove <index> | list | clear | show");
        info(sender, c + "reload");
    }

    private void create(CommandSender sender, String[] args) {
        if (!requireArgs(sender, args, 2, "create <name> [world x y z]")) {
            return;
        }
        Location location = resolveLocation(sender, null, args, 2, "create <name> [world x y z]");
        if (location == null) {
            return;
        }
        String name = args[1];
        if (!Npc.NAME.matcher(name).matches()) {
            error(sender, "Name must be 1-16 characters: letters, digits or underscore.");
            return;
        }
        if (npcs.get(name) != null) {
            error(sender, "An NPC named " + name + " already exists.");
            return;
        }
        if (npcs.count() >= npcs.settings().maxNpcs()) {
            error(sender, "NPC limit reached (" + npcs.settings().maxNpcs() + ").");
            return;
        }
        npcs.create(name, location);
        success(sender, "Created NPC " + name + ". Add waypoints with /tnpc path " + name + " add.");
    }

    private void remove(CommandSender sender, String[] args) {
        Npc npc = requireNpc(sender, args, "remove <name>");
        if (npc == null) {
            return;
        }
        npcs.remove(npc);
        success(sender, "Removed NPC " + npc.name() + ".");
    }

    private void list(CommandSender sender) {
        if (npcs.count() == 0) {
            info(sender, "No NPCs defined.");
            return;
        }
        info(sender, "NPCs (" + npcs.count() + "):");
        for (Npc npc : npcs.all()) {
            info(sender, "- " + npc.name() + "  world=" + npc.worldName() + "  waypoints=" + npc.waypoints().size()
                    + "  viewers=" + npc.viewerCount() + (npc.paused() ? "  [paused]" : ""));
        }
    }

    private void info(CommandSender sender, String[] args) {
        Npc npc = requireNpc(sender, args, "info <name>");
        if (npc == null) {
            return;
        }
        info(sender, "NPC " + npc.name());
        info(sender, "  world: " + npc.worldName() + "  position: " + fmt(npc.x()) + ", " + fmt(npc.y()) + ", " + fmt(npc.z()));
        info(sender, "  speed: " + npc.speed() + " b/s   look-at-players: " + npc.lookAtPlayers() + "   paused: " + npc.paused());
        info(sender, "  skin: " + (npc.skin() == null ? "none" : npc.skinSource()));
        info(sender, "  waypoints: " + npc.waypoints().size() + "   next: #" + npc.nextWaypoint()
                + "   viewers: " + npc.viewerCount());
        info(sender, "  state: " + (npc.airborne() ? "airborne " : "") + (npc.sneaking() ? "sneaking " : "")
                + (npc.waitTicks() > 0 ? "waiting " + npc.waitTicks() + "t" : "") + (npc.paused() ? "paused" : ""));
    }

    private void teleport(CommandSender sender, String[] args) {
        Player player = requirePlayer(sender);
        Npc npc = requireNpc(sender, args, "tp <name>");
        if (player == null || npc == null) {
            return;
        }
        World world = Bukkit.getWorld(npc.worldName());
        if (world == null) {
            error(sender, "World " + npc.worldName() + " is not loaded.");
            return;
        }
        player.teleportAsync(new Location(world, npc.x(), npc.y(), npc.z(), npc.yaw(), npc.pitch()));
        success(sender, "Teleported to " + npc.name() + ".");
    }

    private void skin(CommandSender sender, String[] args) {
        Npc npc = requireNpc(sender, args, "skin <name> mojang <account> | player <online player> | clear");
        if (npc == null || !requireArgs(sender, args, 3, "skin <name> mojang <account> | player <online player> | clear")) {
            return;
        }
        switch (args[2].toLowerCase(Locale.ROOT)) {
            case "clear" -> {
                npc.setSkin("", null);
                npcs.refresh(npc);
                npcs.markDirty();
                success(sender, "Skin cleared for " + npc.name() + ".");
            }
            case "player" -> {
                if (!requireArgs(sender, args, 4, "skin <name> player <online player>")) {
                    return;
                }
                Player target = Bukkit.getPlayerExact(args[3]);
                if (target == null) {
                    error(sender, "Player " + args[3] + " is not online.");
                    return;
                }
                Optional<SkinData> local = plugin.skins().fromOnlinePlayer(target);
                if (local.isPresent()) {
                    applySkin(sender, npc, "player:" + target.getName(), local.get());
                    return;
                }
                info(sender, target.getName() + " has no skin data on this server, asking Mojang...");
                fetchMojang(sender, npc, target.getName());
            }
            case "mojang" -> {
                if (!requireArgs(sender, args, 4, "skin <name> mojang <account>")) {
                    return;
                }
                info(sender, "Looking up " + args[3] + " on Mojang...");
                fetchMojang(sender, npc, args[3]);
            }
            default -> error(sender, "Usage: skin <name> mojang <account> | player <online player> | clear");
        }
    }

    private void fetchMojang(CommandSender sender, Npc npc, String account) {
        if (!Npc.NAME.matcher(account).matches()) {
            error(sender, "'" + account + "' is not a valid Minecraft account name.");
            return;
        }
        String npcName = npc.name();
        plugin.skins().fromMojang(account, result -> {
            Npc current = npcs.get(npcName);
            if (current == null) {
                return;
            }
            if (result.isEmpty()) {
                error(sender, "No skin found for '" + account + "' (unknown account or Mojang unreachable).");
                return;
            }
            applySkin(sender, current, "mojang:" + account, result.get());
        });
    }

    private void applySkin(CommandSender sender, Npc npc, String source, SkinData skin) {
        npc.setSkin(source, skin);
        npcs.refresh(npc);
        npcs.markDirty();
        success(sender, "Skin of " + npc.name() + " set from " + source + ".");
    }

    private void speed(CommandSender sender, String[] args) {
        Npc npc = requireNpc(sender, args, "speed <name> <blocks per second>");
        if (npc == null || !requireArgs(sender, args, 3, "speed <name> <blocks per second>")) {
            return;
        }
        Double value = parseDouble(args[2]);
        if (value == null || value < Settings.MIN_SPEED || value > Settings.MAX_SPEED) {
            error(sender, "Speed must be between " + Settings.MIN_SPEED + " and " + Settings.MAX_SPEED + ".");
            return;
        }
        npc.setSpeed(value);
        npcs.markDirty();
        success(sender, "Speed of " + npc.name() + " set to " + value + " blocks/s.");
    }

    private void look(CommandSender sender, String[] args) {
        Npc npc = requireNpc(sender, args, "look <name> on|off");
        if (npc == null || !requireArgs(sender, args, 3, "look <name> on|off")) {
            return;
        }
        Boolean value = parseBoolean(args[2]);
        if (value == null) {
            error(sender, "Usage: look <name> on|off");
            return;
        }
        npc.setLookAtPlayers(value);
        npcs.markDirty();
        success(sender, npc.name() + (value ? " now looks at nearby players." : " no longer looks at players."));
    }

    private void setPaused(CommandSender sender, String[] args, boolean paused) {
        Npc npc = requireNpc(sender, args, (paused ? "pause" : "resume") + " <name>");
        if (npc == null) {
            return;
        }
        npc.setPaused(paused);
        npcs.markDirty();
        success(sender, npc.name() + (paused ? " paused." : " resumed."));
    }

    private void reload(CommandSender sender) {
        plugin.reload();
        success(sender, "TownNPC reloaded: " + npcs.count() + " NPC(s).");
    }

    private void path(CommandSender sender, String label, String[] args) {
        Npc npc = requireNpc(sender, args, "path <name> add|insert|set|move|remove|list|clear|show");
        if (npc == null || !requireArgs(sender, args, 3, "path <name> add|insert|set|move|remove|list|clear|show")) {
            return;
        }
        List<Waypoint> path = npc.waypoints();
        switch (args[2].toLowerCase(Locale.ROOT)) {
            case "add" -> {
                if (!checkLimit(sender, path)) {
                    return;
                }
                Location location = resolveLocation(sender, npc, args, 3, "path <name> add [jump] [sneak] [wait <s>] [at x y z [yaw]]");
                Waypoint waypoint = location == null ? null : applyFlags(sender, Waypoint.at(location), args, 3);
                if (waypoint == null) {
                    return;
                }
                path.add(waypoint);
                npcs.restart(npc);
                success(sender, "Added waypoint #" + (path.size() - 1) + " to " + npc.name() + describe(waypoint));
            }
            case "insert" -> {
                Integer index = index(sender, args, 3, path.size() + 1);
                if (index == null || !checkLimit(sender, path)) {
                    return;
                }
                Location location = resolveLocation(sender, npc, args, 4, "path <name> insert <index> [flags] [at x y z [yaw]]");
                Waypoint waypoint = location == null ? null : applyFlags(sender, Waypoint.at(location), args, 4);
                if (waypoint == null) {
                    return;
                }
                path.add(index, waypoint);
                npcs.restart(npc);
                success(sender, "Inserted waypoint #" + index + " into " + npc.name() + describe(waypoint));
            }
            case "set" -> {
                Integer index = index(sender, args, 3, path.size());
                if (index == null || !requireArgs(sender, args, 6, "path <name> set <index> jump|sneak|wait <value>")) {
                    return;
                }
                Waypoint updated = applyFlag(sender, path.get(index), args[4], args[5]);
                if (updated == null) {
                    return;
                }
                path.set(index, updated);
                npcs.markDirty();
                success(sender, "Waypoint #" + index + " updated" + describe(updated));
            }
            case "move" -> {
                Integer index = index(sender, args, 3, path.size());
                if (index == null) {
                    return;
                }
                Location location = resolveLocation(sender, npc, args, 4, "path <name> move <index> [at x y z [yaw]]");
                if (location == null) {
                    return;
                }
                path.set(index, path.get(index).withPosition(location));
                npcs.restart(npc);
                success(sender, "Waypoint #" + index + " moved to " + fmt(location.getX()) + ", "
                        + fmt(location.getY()) + ", " + fmt(location.getZ()) + ".");
            }
            case "remove" -> {
                Integer index = index(sender, args, 3, path.size());
                if (index == null) {
                    return;
                }
                if (path.size() == 1) {
                    error(sender, "An NPC needs at least one waypoint. Use /" + label + " remove " + npc.name() + " instead.");
                    return;
                }
                path.remove((int) index);
                npcs.restart(npc);
                success(sender, "Removed waypoint #" + index + " from " + npc.name() + ".");
            }
            case "list" -> {
                info(sender, "Path of " + npc.name() + " (" + path.size() + " waypoints, loop):");
                for (int i = 0; i < path.size(); i++) {
                    Waypoint wp = path.get(i);
                    info(sender, "#" + i + "  " + fmt(wp.x()) + ", " + fmt(wp.y()) + ", " + fmt(wp.z()) + describe(wp));
                }
            }
            case "clear" -> {
                Waypoint first = path.get(0);
                path.clear();
                path.add(first);
                npcs.restart(npc);
                success(sender, "Path of " + npc.name() + " cleared; waypoint #0 kept.");
            }
            case "show" -> {
                Player player = requirePlayer(sender);
                if (player == null) {
                    return;
                }
                showPath(player, npc);
                info(sender, "Showing path of " + npc.name() + " for " + SHOW_SECONDS + " seconds.");
            }
            default -> error(sender, "Usage: path <name> add|insert|set|move|remove|list|clear|show");
        }
    }

    private Waypoint applyFlags(CommandSender sender, Waypoint waypoint, String[] args, int from) {
        for (int i = from; i < args.length; i++) {
            String flag = args[i].toLowerCase(Locale.ROOT);
            if (flag.equals("at")) {
                i += 3;
                while (i + 1 < args.length && parseDouble(args[i + 1]) != null) {
                    i++;
                }
                continue;
            }
            if (flag.equals("wait")) {
                if (i + 1 >= args.length) {
                    error(sender, "wait needs a value in seconds.");
                    return null;
                }
                waypoint = applyFlag(sender, waypoint, flag, args[++i]);
            } else {
                waypoint = applyFlag(sender, waypoint, flag, "true");
            }
            if (waypoint == null) {
                return null;
            }
        }
        return waypoint;
    }

    private Waypoint applyFlag(CommandSender sender, Waypoint waypoint, String flag, String value) {
        switch (flag.toLowerCase(Locale.ROOT)) {
            case "jump", "sneak" -> {
                Boolean enabled = parseBoolean(value);
                if (enabled == null) {
                    error(sender, flag + " expects true or false.");
                    return null;
                }
                return flag.equalsIgnoreCase("jump") ? waypoint.withJump(enabled) : waypoint.withSneak(enabled);
            }
            case "wait" -> {
                Double seconds = parseDouble(value);
                if (seconds == null || seconds < 0 || seconds * 20 > Waypoint.MAX_WAIT_TICKS) {
                    error(sender, "wait must be between 0 and " + (Waypoint.MAX_WAIT_TICKS / 20) + " seconds.");
                    return null;
                }
                return waypoint.withWait((int) Math.round(seconds * 20));
            }
            default -> {
                error(sender, "Unknown flag '" + flag + "'. Use jump, sneak or wait <seconds>.");
                return null;
            }
        }
    }

    private void showPath(Player player, Npc npc) {
        List<Waypoint> path = List.copyOf(npc.waypoints());
        World world = player.getWorld();
        if (!world.getName().equals(npc.worldName())) {
            error(player, "You are not in the NPC's world.");
            return;
        }
        List<Location> points = new ArrayList<>();
        for (int i = 0; i < path.size() && points.size() < SHOW_MAX_POINTS; i++) {
            Waypoint a = path.get(i);
            Waypoint b = path.get((i + 1) % path.size());
            double dx = b.x() - a.x();
            double dy = b.y() - a.y();
            double dz = b.z() - a.z();
            int steps = Math.max(1, (int) Math.ceil(Math.sqrt(dx * dx + dy * dy + dz * dz) / 0.5));
            for (int s = 0; s < steps && points.size() < SHOW_MAX_POINTS; s++) {
                double t = (double) s / steps;
                points.add(new Location(world, a.x() + dx * t, a.y() + dy * t + 0.1, a.z() + dz * t));
            }
        }
        Particle.DustOptions segment = new Particle.DustOptions(Color.AQUA, 0.8f);
        Particle.DustOptions node = new Particle.DustOptions(Color.YELLOW, 1.5f);
        new BukkitRunnable() {
            int remaining = SHOW_SECONDS * 2;

            @Override
            public void run() {
                if (remaining-- <= 0 || !player.isOnline() || player.getWorld() != world) {
                    cancel();
                    return;
                }
                for (Location point : points) {
                    player.spawnParticle(Particle.DUST, point, 1, 0, 0, 0, 0, segment);
                }
                for (Waypoint wp : path) {
                    player.spawnParticle(Particle.DUST, wp.x(), wp.y() + 1.0, wp.z(), 3, 0.1, 0.3, 0.1, 0, node);
                }
            }
        }.runTaskTimer(plugin, 0L, 10L);
    }

    private Player requirePlayer(CommandSender sender) {
        if (sender instanceof Player player) {
            return player;
        }
        error(sender, "Only players can run this.");
        return null;
    }

    private Npc requireNpc(CommandSender sender, String[] args, String usage) {
        if (!requireArgs(sender, args, 2, usage)) {
            return null;
        }
        Npc npc = npcs.get(args[1]);
        if (npc == null) {
            error(sender, "No NPC named " + args[1] + ".");
        }
        return npc;
    }

    private boolean requireArgs(CommandSender sender, String[] args, int count, String usage) {
        if (args.length >= count) {
            return true;
        }
        error(sender, "Usage: /tnpc " + usage);
        return false;
    }

    private Location resolveLocation(CommandSender sender, Npc npc, String[] args, int from, String usage) {
        int at = -1;
        for (int i = from; i < args.length; i++) {
            if (args[i].equalsIgnoreCase("at")) {
                at = i + 1;
                break;
            }
        }
        if (npc == null && at < 0 && args.length > from) {
            at = from;
        }
        if (at < 0) {
            Player player = requirePlayer(sender);
            if (player == null) {
                return null;
            }
            if (npc != null && !player.getWorld().getName().equals(npc.worldName())) {
                error(sender, "The NPC lives in world " + npc.worldName() + "; go there to edit its path.");
                return null;
            }
            return player.getLocation();
        }
        World world;
        int coords = at;
        if (npc == null) {
            if (at >= args.length) {
                error(sender, "Usage: /tnpc " + usage);
                return null;
            }
            world = Bukkit.getWorld(args[at]);
            if (world == null) {
                error(sender, "Unknown world " + args[at] + ".");
                return null;
            }
            coords = at + 1;
        } else {
            world = Bukkit.getWorld(npc.worldName());
            if (world == null) {
                error(sender, "World " + npc.worldName() + " is not loaded.");
                return null;
            }
        }
        if (coords + 3 > args.length) {
            error(sender, "Usage: /tnpc " + usage);
            return null;
        }
        Double x = parseDouble(args[coords]);
        Double y = parseDouble(args[coords + 1]);
        Double z = parseDouble(args[coords + 2]);

        Double yaw = coords + 3 < args.length ? parseDouble(args[coords + 3]) : null;
        Double pitch = yaw != null && coords + 4 < args.length ? parseDouble(args[coords + 4]) : null;
        if (yaw == null) {
            yaw = 0.0;
        }
        if (pitch == null) {
            pitch = 0.0;
        }
        if (x == null || y == null || z == null
                || Math.abs(x) > 30_000_000 || Math.abs(z) > 30_000_000
                || y < world.getMinHeight() - 64 || y > world.getMaxHeight() + 64) {
            error(sender, "Coordinates must be numbers inside the world.");
            return null;
        }
        return new Location(world, x, y, z, yaw.floatValue(), pitch.floatValue());
    }

    private boolean checkLimit(CommandSender sender, List<Waypoint> path) {
        if (path.size() < npcs.settings().maxWaypoints()) {
            return true;
        }
        error(sender, "Waypoint limit reached (" + npcs.settings().maxWaypoints() + ").");
        return false;
    }

    private Integer index(CommandSender sender, String[] args, int position, int bound) {
        if (!requireArgs(sender, args, position + 1, "path <name> " + args[2] + " <index>")) {
            return null;
        }
        try {
            int index = Integer.parseInt(args[position]);
            if (index >= 0 && index < bound) {
                return index;
            }
        } catch (NumberFormatException ignored) {
        }
        error(sender, "Index must be between 0 and " + (bound - 1) + ".");
        return null;
    }

    private static String describe(Waypoint wp) {
        StringBuilder sb = new StringBuilder();
        if (wp.jump()) {
            sb.append(" jump");
        }
        if (wp.sneak()) {
            sb.append(" sneak");
        }
        if (wp.waitTicks() > 0) {
            sb.append(" wait ").append(fmt(wp.waitTicks() / 20.0)).append("s");
        }
        return sb.isEmpty() ? "" : "  [" + sb.toString().trim() + "]";
    }

    private static Double parseDouble(String text) {
        try {
            double value = Double.parseDouble(text);
            return Double.isFinite(value) ? value : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Boolean parseBoolean(String text) {
        return switch (text.toLowerCase(Locale.ROOT)) {
            case "true", "on", "yes" -> Boolean.TRUE;
            case "false", "off", "no" -> Boolean.FALSE;
            default -> null;
        };
    }

    private static String fmt(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }

    private static void info(CommandSender sender, String text) {
        sender.sendMessage(Component.text(text, NamedTextColor.GRAY));
    }

    private static void success(CommandSender sender, String text) {
        sender.sendMessage(Component.text(text, NamedTextColor.GREEN));
    }

    private static void error(CommandSender sender, String text) {
        sender.sendMessage(Component.text(text, NamedTextColor.RED));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return filter(ROOT, args[0]);
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (args.length == 2 && !sub.equals("list") && !sub.equals("reload") && !sub.equals("create")) {
            return filter(npcs.all().stream().map(Npc::name).toList(), args[1]);
        }
        if (args.length == 3) {
            switch (sub) {
                case "skin" -> {
                    return filter(List.of("mojang", "player", "clear"), args[2]);
                }
                case "look" -> {
                    return filter(List.of("on", "off"), args[2]);
                }
                case "path" -> {
                    return filter(PATH_SUB, args[2]);
                }
                default -> {
                    return List.of();
                }
            }
        }
        if (sub.equals("skin") && args.length == 4 && args[2].equalsIgnoreCase("player")) {
            return filter(Bukkit.getOnlinePlayers().stream().map(Player::getName).toList(), args[3]);
        }
        if (sub.equals("path") && args.length >= 4) {
            String pathSub = args[2].toLowerCase(Locale.ROOT);
            if (pathSub.equals("add") || (pathSub.equals("insert") && args.length >= 5)) {
                return filter(List.of("jump", "sneak", "wait", "at"), args[args.length - 1]);
            }
            if (pathSub.equals("set") && args.length == 5) {
                return filter(List.of("jump", "sneak", "wait"), args[4]);
            }
            if (pathSub.equals("set") && args.length == 6 && !args[4].equalsIgnoreCase("wait")) {
                return filter(List.of("true", "false"), args[5]);
            }
        }
        return List.of();
    }

    private static List<String> filter(List<String> options, String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        List<String> result = new ArrayList<>();
        for (String option : options) {
            if (option.toLowerCase(Locale.ROOT).startsWith(lower)) {
                result.add(option);
            }
        }
        return result;
    }
}
