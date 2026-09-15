package id.gaffa.townnpc;

import org.bukkit.configuration.file.FileConfiguration;

public record Settings(
        double viewDistance,
        int viewerCheckInterval,
        boolean simulateWithoutViewers,
        double defaultSpeed,
        double sneakMultiplier,
        double stepHeight,
        double maxJumpHeight,
        boolean lookEnabled,
        double lookRadius,
        double lookFov,
        int skinTimeoutSeconds,
        long skinCacheMillis,
        int maxNpcs,
        int maxWaypoints) {
    public static final double MIN_SPEED = 0.1;
    public static final double MAX_SPEED = 10.0;

    public static Settings load(FileConfiguration config) {
        return new Settings(
                clamp(config.getDouble("view-distance", 48), 8, 128),
                (int) clamp(config.getInt("viewer-check-interval", 10), 1, 100),
                config.getBoolean("simulate-without-viewers", false),
                clamp(config.getDouble("movement.default-speed", 2.5), MIN_SPEED, MAX_SPEED),
                clamp(config.getDouble("movement.sneak-multiplier", 0.3), 0.05, 1.0),
                clamp(config.getDouble("movement.step-height", 0.6), 0.0, 1.0),
                clamp(config.getDouble("movement.max-jump-height", 1.25), 0.5, 1.25),
                config.getBoolean("look.enabled", true),
                clamp(config.getDouble("look.radius", 6.0), 1.0, 32.0),
                clamp(config.getDouble("look.fov", 140), 10, 180),
                (int) clamp(config.getInt("skin.timeout-seconds", 5), 1, 30),
                (long) (clamp(config.getDouble("skin.cache-days", 7), 0.01, 365) * 86_400_000L),
                (int) clamp(config.getInt("limits.max-npcs", 100), 1, 1000),
                (int) clamp(config.getInt("limits.max-waypoints", 500), 2, 5000));
    }

    public double viewDistanceSquared() {
        return viewDistance * viewDistance;
    }

    public double lookRadiusSquared() {
        return lookRadius * lookRadius;
    }

    private static double clamp(double value, double min, double max) {
        if (!Double.isFinite(value)) {
            return min;
        }
        return Math.max(min, Math.min(max, value));
    }
}
