package id.gaffa.townnpc.npc;

import id.gaffa.townnpc.Settings;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.util.BoundingBox;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

public final class Pathfinder {
    private static final double MAX_PARTIAL_FLOOR = 0.75;
    private static final double SNEAK_HEIGHT = 1.5;
    private static final double STAND_HEIGHT = 1.8;
    private static final double JUMP_COST = 2.0;
    private static final double DROP_COST = 0.5;
    private static final double SNEAK_COST = 1.0;
    private static final double DIAGONAL = Math.sqrt(2.0);
    private static final int[][] DIRECTIONS = {
            {1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}
    };

    private final Settings settings;
    private final World world;
    private final Map<Long, Node> nodes = new HashMap<>();
    private final Map<Long, double[]> shapeCache = new HashMap<>();

    private static final class Node {
        final int x;
        final int y;
        final int z;
        final double floor;
        final boolean lowCeiling;
        double g = Double.MAX_VALUE;
        double f;
        Node parent;
        boolean closed;

        Node(int x, int y, int z, double floor, boolean lowCeiling) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.floor = floor;
            this.lowCeiling = lowCeiling;
        }
    }

    public Pathfinder(Settings settings, World world) {
        this.settings = settings;
        this.world = world;
    }

    public List<double[]> find(double fromX, double fromY, double fromZ, double toX, double toY, double toZ) {
        double dx = toX - fromX;
        double dz = toZ - fromZ;
        if (dx * dx + dz * dz > settings.pathMaxDistance() * settings.pathMaxDistance()) {
            return null;
        }
        Node start = locate(floor(fromX), floor(fromY), floor(fromZ));
        Node goal = locate(floor(toX), floor(toY), floor(toZ));
        if (start == null || goal == null) {
            return null;
        }
        if (start == goal) {
            return Collections.emptyList();
        }
        PriorityQueue<Node> open = new PriorityQueue<>((a, b) -> Double.compare(a.f, b.f));
        start.g = 0;
        start.f = heuristic(start, goal);
        open.add(start);
        int expanded = 0;
        int limit = settings.pathMaxNodes();
        while (!open.isEmpty()) {
            Node current = open.poll();
            if (current.closed) {
                continue;
            }
            if (current == goal) {
                return build(goal);
            }
            current.closed = true;
            if (++expanded > limit) {
                return null;
            }
            for (int[] dir : DIRECTIONS) {
                boolean diagonal = dir[0] != 0 && dir[1] != 0;
                if (diagonal && !canCutCorner(current, dir)) {
                    continue;
                }
                Node next = step(current, dir[0], dir[1]);
                if (next == null || next.closed) {
                    continue;
                }
                if (diagonal && Math.abs(next.floor - current.floor) > 1.0e-6) {
                    continue;
                }
                double cost = current.g + moveCost(current, next, diagonal);
                if (cost < next.g) {
                    next.g = cost;
                    next.f = cost + heuristic(next, goal);
                    next.parent = current;
                    open.add(next);
                }
            }
        }
        return null;
    }

    private boolean canCutCorner(Node from, int[] dir) {
        Node a = step(from, dir[0], 0);
        Node b = step(from, 0, dir[1]);
        return a != null && b != null;
    }

    private Node step(Node from, int dx, int dz) {
        int x = from.x + dx;
        int z = from.z + dz;
        for (int y = from.y + 1; y >= from.y - settings.pathMaxDrop(); y--) {
            Node candidate = node(x, y, z);
            if (candidate == null) {
                continue;
            }
            double rise = candidate.floor - from.floor;
            if (rise > settings.maxJumpHeight() + 1.0e-6) {
                continue;
            }
            if (rise > settings.stepHeight() + 1.0e-6 && (from.lowCeiling || !clearAbove(from))) {
                continue;
            }
            if (from.floor - candidate.floor > settings.pathMaxDrop() + 1.0e-6) {
                return null;
            }
            if (candidate.floor < from.floor - 1.0e-6 && !columnClear(x, z, from.floor)) {
                return null;
            }
            return candidate;
        }
        return null;
    }

    private boolean columnClear(int x, int z, double floor) {
        int minY = (int) Math.floor(floor);
        int maxY = (int) Math.floor(floor + SNEAK_HEIGHT - 1.0e-6);
        for (int y = minY; y <= maxY; y++) {
            Block block = world.getBlockAt(x, y, z);
            if (block.isLiquid()) {
                return false;
            }
            if (!block.isPassable() && collisionBottom(block, y) < floor + SNEAK_HEIGHT - 1.0e-6) {
                return false;
            }
        }
        return true;
    }

    private boolean clearAbove(Node node) {
        Block above = world.getBlockAt(node.x, node.y + 2, node.z);
        return above.isPassable() && !above.isLiquid();
    }

    private double moveCost(Node from, Node to, boolean diagonal) {
        double cost = diagonal ? DIAGONAL : 1.0;
        double rise = to.floor - from.floor;
        if (rise > settings.stepHeight() + 1.0e-6) {
            cost += JUMP_COST;
        } else if (rise < -1.0e-6) {
            cost += DROP_COST * -rise;
        }
        if (to.lowCeiling) {
            cost += SNEAK_COST;
        }
        return cost;
    }

    private static double heuristic(Node a, Node b) {
        double dx = a.x - b.x;
        double dy = a.floor - b.floor;
        double dz = a.z - b.z;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private Node locate(int x, int y, int z) {
        for (int dy = 1; dy >= -2; dy--) {
            Node node = node(x, y + dy, z);
            if (node != null) {
                return node;
            }
        }
        return null;
    }

    private Node node(int x, int y, int z) {
        long key = key(x, y, z);
        Node cached = nodes.get(key);
        if (cached != null) {
            return cached;
        }
        if (nodes.containsKey(key)) {
            return null;
        }
        Node created = createNode(x, y, z);
        nodes.put(key, created);
        return created;
    }

    private Node createNode(int x, int y, int z) {
        if (y < world.getMinHeight() || y + 2 >= world.getMaxHeight()) {
            return null;
        }
        if (!world.isChunkLoaded(x >> 4, z >> 4) || !Bukkit.isOwnedByCurrentRegion(world, x >> 4, z >> 4)) {
            return null;
        }
        Block feet = world.getBlockAt(x, y, z);
        if (feet.isLiquid()) {
            return null;
        }
        double floor;
        double[] feetShape = shape(feet, x, y, z);
        if (feetShape != null) {
            if (feetShape[0] - y > MAX_PARTIAL_FLOOR) {
                return null;
            }
            floor = feetShape[0];
        } else {
            Block below = world.getBlockAt(x, y - 1, z);
            double[] belowShape = shape(below, x, y - 1, z);
            if (belowShape == null || Math.abs(belowShape[1] - y) > 1.0e-3 || below.isLiquid()) {
                return null;
            }
            floor = y;
        }
        Block head = world.getBlockAt(x, y + 1, z);
        if (!head.isPassable() || head.isLiquid()) {
            return null;
        }
        Block above = world.getBlockAt(x, y + 2, z);
        boolean lowCeiling = false;
        if (!above.isPassable()) {
            double bottom = collisionBottom(above, y + 2);
            if (bottom < floor + SNEAK_HEIGHT - 1.0e-6) {
                return null;
            }
            lowCeiling = bottom < floor + STAND_HEIGHT - 1.0e-6;
        }
        return new Node(x, y, z, floor, lowCeiling);
    }

    private double[] shape(Block block, int x, int y, int z) {
        long key = key(x, y, z);
        if (shapeCache.containsKey(key)) {
            return shapeCache.get(key);
        }
        double[] result = null;
        if (!block.isPassable()) {
            double lowest = Double.MAX_VALUE;
            double highest = -Double.MAX_VALUE;
            for (BoundingBox box : block.getCollisionShape().getBoundingBoxes()) {
                lowest = Math.min(lowest, y + box.getMaxY());
                highest = Math.max(highest, y + box.getMaxY());
            }
            if (highest > -Double.MAX_VALUE) {
                result = new double[]{lowest, highest};
            }
        }
        shapeCache.put(key, result);
        return result;
    }

    private static double collisionBottom(Block block, int y) {
        double bottom = Double.MAX_VALUE;
        for (BoundingBox box : block.getCollisionShape().getBoundingBoxes()) {
            bottom = Math.min(bottom, y + box.getMinY());
        }
        return bottom == Double.MAX_VALUE ? y : bottom;
    }

    private static List<double[]> build(Node goal) {
        List<double[]> route = new ArrayList<>();
        for (Node node = goal; node != null; node = node.parent) {
            route.add(new double[]{node.x + 0.5, node.floor, node.z + 0.5});
        }
        Collections.reverse(route);
        return route;
    }

    private static int floor(double value) {
        return (int) Math.floor(value);
    }

    private static long key(int x, int y, int z) {
        return ((long) x & 0x3FFFFFFL) << 38 | ((long) z & 0x3FFFFFFL) << 12 | ((long) y & 0xFFFL);
    }
}
