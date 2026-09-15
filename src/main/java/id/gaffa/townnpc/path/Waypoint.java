package id.gaffa.townnpc.path;

import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;

import java.util.LinkedHashMap;
import java.util.Map;

public record Waypoint(double x, double y, double z, float yaw, float pitch,
                       boolean jump, boolean sneak, int waitTicks) {
    public static final int MAX_WAIT_TICKS = 20 * 60 * 10;

    public Waypoint {
        waitTicks = Math.max(0, Math.min(MAX_WAIT_TICKS, waitTicks));
        yaw = wrapDegrees(yaw);
        pitch = Math.max(-90f, Math.min(90f, pitch));
    }

    public static Waypoint at(Location location) {
        return new Waypoint(location.getX(), location.getY(), location.getZ(),
                location.getYaw(), location.getPitch(), false, false, 0);
    }

    public Waypoint withJump(boolean value) {
        return new Waypoint(x, y, z, yaw, pitch, value, sneak, waitTicks);
    }

    public Waypoint withSneak(boolean value) {
        return new Waypoint(x, y, z, yaw, pitch, jump, value, waitTicks);
    }

    public Waypoint withWait(int ticks) {
        return new Waypoint(x, y, z, yaw, pitch, jump, sneak, ticks);
    }

    public Waypoint withPosition(Location location) {
        return new Waypoint(location.getX(), location.getY(), location.getZ(),
                location.getYaw(), location.getPitch(), jump, sneak, waitTicks);
    }

    public Map<String, Object> serialize() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("x", x);
        map.put("y", y);
        map.put("z", z);
        map.put("yaw", (double) yaw);
        map.put("pitch", (double) pitch);
        if (jump) {
            map.put("jump", true);
        }
        if (sneak) {
            map.put("sneak", true);
        }
        if (waitTicks > 0) {
            map.put("wait", waitTicks);
        }
        return map;
    }

    public static Waypoint deserialize(Map<?, ?> map) {
        double x = number(map.get("x"));
        double y = number(map.get("y"));
        double z = number(map.get("z"));
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
            return null;
        }
        float yaw = (float) number(map.get("yaw"));
        float pitch = (float) number(map.get("pitch"));
        if (!Float.isFinite(yaw)) {
            yaw = 0f;
        }
        if (!Float.isFinite(pitch)) {
            pitch = 0f;
        }
        boolean jump = Boolean.TRUE.equals(map.get("jump"));
        boolean sneak = Boolean.TRUE.equals(map.get("sneak"));
        int wait = (int) number(map.get("wait"));
        return new Waypoint(x, y, z, yaw, pitch, jump, sneak, wait);
    }

    public static Waypoint deserialize(ConfigurationSection section) {
        return deserialize(section.getValues(false));
    }

    private static double number(Object value) {
        return value instanceof Number n ? n.doubleValue() : 0.0;
    }

    public static float wrapDegrees(float degrees) {
        float wrapped = degrees % 360f;
        if (wrapped >= 180f) {
            wrapped -= 360f;
        }
        if (wrapped < -180f) {
            wrapped += 360f;
        }
        return wrapped;
    }
}
