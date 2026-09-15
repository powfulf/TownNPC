package id.gaffa.townnpc.npc;

import id.gaffa.townnpc.nms.PacketBridge;
import id.gaffa.townnpc.path.Waypoint;
import id.gaffa.townnpc.skin.SkinData;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

public final class Npc {
    public static final Pattern NAME = Pattern.compile("^[A-Za-z0-9_]{1,16}$");

    private final String name;
    private final UUID uuid;
    private final int entityId = PacketBridge.nextEntityId();

    private String worldName;
    private double speed;
    private boolean lookAtPlayers = true;
    private boolean paused;
    private String skinSource = "";
    private SkinData skin;
    private final List<Waypoint> waypoints = new ArrayList<>();

    double x;
    double y;
    double z;
    float yaw;
    float pitch;
    float headYaw;
    boolean onGround = true;
    boolean airborne;
    double velocityY;
    boolean sneakSegment;
    boolean lowCeiling;
    boolean sneakingShown;
    boolean pendingJump;
    int waitTicks;
    float waitPitch;
    int nextWaypoint;
    long lastFeetBlock = Long.MIN_VALUE;
    long sentX;
    long sentY;
    long sentZ;
    byte sentYaw;
    byte sentPitch;
    byte sentHeadYaw;
    Player lookTarget;
    final Set<Player> viewers = new HashSet<>();

    public Npc(String name, UUID uuid, String worldName, double speed) {
        this.name = name;
        this.uuid = uuid;
        this.worldName = worldName;
        this.speed = speed;
    }

    public String name() {
        return name;
    }

    public UUID uuid() {
        return uuid;
    }

    public int entityId() {
        return entityId;
    }

    public String worldName() {
        return worldName;
    }

    public void setWorldName(String worldName) {
        this.worldName = worldName;
    }

    public double speed() {
        return speed;
    }

    public void setSpeed(double speed) {
        this.speed = speed;
    }

    public boolean lookAtPlayers() {
        return lookAtPlayers;
    }

    public void setLookAtPlayers(boolean lookAtPlayers) {
        this.lookAtPlayers = lookAtPlayers;
    }

    public boolean paused() {
        return paused;
    }

    public void setPaused(boolean paused) {
        this.paused = paused;
    }

    public String skinSource() {
        return skinSource;
    }

    public SkinData skin() {
        return skin;
    }

    public void setSkin(String source, SkinData skin) {
        this.skinSource = source == null ? "" : source;
        this.skin = skin;
    }

    public List<Waypoint> waypoints() {
        return waypoints;
    }

    public double x() {
        return x;
    }

    public double y() {
        return y;
    }

    public double z() {
        return z;
    }

    public float yaw() {
        return yaw;
    }

    public float pitch() {
        return pitch;
    }

    public int viewerCount() {
        return viewers.size();
    }

    public boolean sneakingShown() {
        return sneakingShown;
    }

    public boolean sneaking() {
        return sneakSegment || lowCeiling;
    }

    public boolean airborne() {
        return airborne;
    }

    public int waitTicks() {
        return waitTicks;
    }

    public int nextWaypoint() {
        return nextWaypoint;
    }

    void resetTo(int index) {
        if (waypoints.isEmpty()) {
            return;
        }
        index = Math.floorMod(index, waypoints.size());
        Waypoint wp = waypoints.get(index);
        x = wp.x();
        y = wp.y();
        z = wp.z();
        yaw = wp.yaw();
        pitch = wp.pitch();
        headYaw = yaw;
        airborne = false;
        velocityY = 0;
        onGround = true;
        pendingJump = false;
        sneakSegment = false;
        lowCeiling = false;
        waitTicks = 0;
        lastFeetBlock = Long.MIN_VALUE;
        nextWaypoint = waypoints.size() > 1 ? (index + 1) % waypoints.size() : 0;
    }
}
