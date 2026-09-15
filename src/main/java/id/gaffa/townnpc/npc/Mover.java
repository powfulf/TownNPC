package id.gaffa.townnpc.npc;

import id.gaffa.townnpc.Settings;
import id.gaffa.townnpc.path.Waypoint;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.util.BoundingBox;

import java.util.List;

final class Mover {
    private static final double HALF_WIDTH = 0.3;
    private static final double FOOTPRINT = HALF_WIDTH - 0.01;
    private static final double STANDING_HEIGHT = 1.8;
    private static final double JUMP_VELOCITY = 0.42;
    private static final double GRAVITY = 0.08;
    private static final double DRAG = 0.98;
    private static final double MAX_FALL_PER_TICK = 3.92;
    private static final double RECOVERY_DISTANCE = 4.0;
    private static final double EPSILON = 1.0e-4;

    private final Settings settings;

    Mover(Settings settings) {
        this.settings = settings;
    }

    private double surface;

    boolean tick(Npc npc, World world) {
        List<Waypoint> path = npc.waypoints();
        if (path.size() < 2) {
            return true;
        }
        if (npc.nextWaypoint >= path.size()) {
            npc.nextWaypoint = 0;
        }

        if (npc.waitTicks > 0 && !npc.airborne) {
            npc.waitTicks--;
            return true;
        }

        Waypoint target = path.get(npc.nextWaypoint);
        double dx = target.x() - npc.x;
        double dz = target.z() - npc.z;
        double distance = Math.sqrt(dx * dx + dz * dz);

        double step = npc.speed() / 20.0;
        if (npc.sneakSegment || npc.lowCeiling) {
            step *= settings.sneakMultiplier();
        }

        double nx;
        double nz;
        boolean arrived = false;
        if (distance <= step) {
            nx = target.x();
            nz = target.z();
            arrived = true;
        } else {
            nx = npc.x + dx / distance * step;
            nz = npc.z + dz / distance * step;
        }
        if (distance > EPSILON) {
            npc.yaw = yawTowards(dx, dz);
        }

        if (!isLoaded(world, nx, nz)) {
            return false;
        }

        if (!npc.airborne && npc.pendingJump) {
            npc.pendingJump = false;
            npc.airborne = true;
            npc.velocityY = JUMP_VELOCITY;
        }

        double ny;
        if (npc.airborne) {
            ny = npc.y + npc.velocityY;
            boolean falling = npc.velocityY <= 0;
            npc.velocityY = (npc.velocityY - GRAVITY) * DRAG;
            if (npc.velocityY < -MAX_FALL_PER_TICK) {
                npc.velocityY = -MAX_FALL_PER_TICK;
            }
            if (falling && scanSurface(world, nx, nz, npc.y + EPSILON, ny)) {
                ny = surface;
                npc.airborne = false;
                npc.velocityY = 0;
            }
            if (ny < world.getMinHeight() - 16) {
                npc.resetTo(npc.nextWaypoint);
                return true;
            }
        } else {
            boolean found = scanSurface(world, nx, nz, npc.y + settings.maxJumpHeight(), npc.y - settings.stepHeight());
            if (!found) {
                npc.airborne = true;
                npc.velocityY = 0;
                ny = npc.y;
            } else {
                double rise = surface - npc.y;
                if (rise > settings.stepHeight() + EPSILON) {
                    npc.airborne = true;
                    npc.velocityY = JUMP_VELOCITY;
                    nx = npc.x;
                    nz = npc.z;
                    ny = npc.y;
                    arrived = false;
                } else {
                    ny = surface;
                }
            }
        }

        npc.x = nx;
        npc.y = ny;
        npc.z = nz;
        npc.onGround = !npc.airborne;

        long feetBlock = blockKey(nx, ny, nz);
        if (feetBlock != npc.lastFeetBlock) {
            npc.lastFeetBlock = feetBlock;
            npc.lowCeiling = hasLowCeiling(world, nx, ny, nz);
        }

        if (arrived) {
            arrive(npc, target, path.size());
        }
        return true;
    }

    private void arrive(Npc npc, Waypoint target, int pathSize) {
        if (Math.abs(npc.y - target.y()) > RECOVERY_DISTANCE) {
            npc.y = target.y();
            npc.airborne = false;
            npc.velocityY = 0;
        }
        npc.waitTicks = target.waitTicks();
        if (npc.waitTicks > 0) {
            npc.yaw = target.yaw();
            npc.pitch = target.pitch();
            npc.waitPitch = target.pitch();
        }
        npc.sneakSegment = target.sneak();
        npc.pendingJump = target.jump();
        npc.nextWaypoint = (npc.nextWaypoint + 1) % pathSize;
    }

    private boolean scanSurface(World world, double x, double z, double top, double bottom) {
        int minBx = floor(x - FOOTPRINT);
        int maxBx = floor(x + FOOTPRINT);
        int minBz = floor(z - FOOTPRINT);
        int maxBz = floor(z + FOOTPRINT);
        int minBy = Math.max(world.getMinHeight(), floor(bottom));
        int maxBy = Math.min(world.getMaxHeight() - 1, floor(top));
        double best = Double.NaN;
        for (int by = maxBy; by >= minBy; by--) {
            for (int bx = minBx; bx <= maxBx; bx++) {
                for (int bz = minBz; bz <= maxBz; bz++) {
                    Block block = world.getBlockAt(bx, by, bz);
                    if (block.isPassable()) {
                        continue;
                    }
                    for (BoundingBox box : block.getCollisionShape().getBoundingBoxes()) {
                        if (!overlapsFootprint(box, bx, bz, x, z)) {
                            continue;
                        }
                        double boxTop = by + box.getMaxY();
                        if (boxTop <= top + EPSILON && boxTop >= bottom - EPSILON
                                && (Double.isNaN(best) || boxTop > best)) {
                            best = boxTop;
                        }
                    }
                }
            }
            if (!Double.isNaN(best)) {
                break;
            }
        }
        surface = best;
        return !Double.isNaN(best);
    }

    private boolean hasLowCeiling(World world, double x, double y, double z) {
        int minBx = floor(x - FOOTPRINT);
        int maxBx = floor(x + FOOTPRINT);
        int minBz = floor(z - FOOTPRINT);
        int maxBz = floor(z + FOOTPRINT);
        double headBottom = y + 1.0;
        double headTop = y + STANDING_HEIGHT;
        int minBy = Math.max(world.getMinHeight(), floor(headBottom));
        int maxBy = Math.min(world.getMaxHeight() - 1, floor(headTop));
        for (int by = minBy; by <= maxBy; by++) {
            for (int bx = minBx; bx <= maxBx; bx++) {
                for (int bz = minBz; bz <= maxBz; bz++) {
                    Block block = world.getBlockAt(bx, by, bz);
                    if (block.isPassable()) {
                        continue;
                    }
                    for (BoundingBox box : block.getCollisionShape().getBoundingBoxes()) {
                        if (!overlapsFootprint(box, bx, bz, x, z)) {
                            continue;
                        }
                        double boxBottom = by + box.getMinY();
                        double boxTop = by + box.getMaxY();
                        if (boxBottom < headTop - EPSILON && boxTop > headBottom + EPSILON) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    private static boolean overlapsFootprint(BoundingBox box, int bx, int bz, double x, double z) {
        return bx + box.getMinX() < x + FOOTPRINT && bx + box.getMaxX() > x - FOOTPRINT
                && bz + box.getMinZ() < z + FOOTPRINT && bz + box.getMaxZ() > z - FOOTPRINT;
    }

    private static boolean isLoaded(World world, double x, double z) {
        int minCx = floor(x - HALF_WIDTH) >> 4;
        int maxCx = floor(x + HALF_WIDTH) >> 4;
        int minCz = floor(z - HALF_WIDTH) >> 4;
        int maxCz = floor(z + HALF_WIDTH) >> 4;
        for (int cx = minCx; cx <= maxCx; cx++) {
            for (int cz = minCz; cz <= maxCz; cz++) {
                if (!world.isChunkLoaded(cx, cz)) {
                    return false;
                }
            }
        }
        return true;
    }

    static float yawTowards(double dx, double dz) {
        return Waypoint.wrapDegrees((float) Math.toDegrees(Math.atan2(-dx, dz)));
    }

    static float pitchTowards(double dx, double dy, double dz) {
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        return (float) -Math.toDegrees(Math.atan2(dy, horizontal));
    }

    private static int floor(double value) {
        return (int) Math.floor(value);
    }

    private static long blockKey(double x, double y, double z) {
        return ((long) floor(x) & 0x3FFFFFFL) << 38 | ((long) floor(z) & 0x3FFFFFFL) << 12 | ((long) floor(y) & 0xFFFL);
    }
}
