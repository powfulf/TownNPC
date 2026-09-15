package id.gaffa.townnpc.nms;

import com.google.common.collect.LinkedHashMultimap;
import com.google.common.collect.Multimap;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundRotateHeadPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.Avatar;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

public final class PacketBridge {
    private static final int ID_SHARED_FLAGS = accessorId(Entity.class, "DATA_SHARED_FLAGS_ID", 0);
    private static final int ID_POSE = accessorId(Entity.class, "DATA_POSE", 6);
    private static final int ID_SKIN_PARTS = Avatar.DATA_PLAYER_MODE_CUSTOMISATION.id();

    private static final byte FLAG_SNEAKING = 0x02;
    private static final byte ALL_SKIN_PARTS = 0x7F;

    private PacketBridge() {
    }

    public static int nextEntityId() {
        return Entity.nextEntityId();
    }

    public static void send(Player player, Packet<?> packet) {
        ServerGamePacketListenerImpl connection = ((CraftPlayer) player).getHandle().connection;
        if (connection != null) {
            connection.send(packet);
        }
    }

    public static void send(Player player, List<Packet<?>> packets) {
        ServerGamePacketListenerImpl connection = ((CraftPlayer) player).getHandle().connection;
        if (connection == null) {
            return;
        }
        for (Packet<?> packet : packets) {
            connection.send(packet);
        }
    }

    public static boolean isConnected(Player player) {
        ServerPlayer handle = ((CraftPlayer) player).getHandle();
        return handle.connection != null && !handle.connection.isDisconnected();
    }

    public static GameProfile profile(UUID uuid, String name, String textureValue, String textureSignature) {
        if (textureValue == null || textureValue.isEmpty()) {
            return new GameProfile(uuid, name);
        }
        Multimap<String, Property> map = LinkedHashMultimap.create();
        map.put("textures", new Property("textures", textureValue, textureSignature));
        return new GameProfile(uuid, name, new PropertyMap(map));
    }

    public static Packet<?> playerInfoAdd(GameProfile profile) {
        ClientboundPlayerInfoUpdatePacket.Entry entry = new ClientboundPlayerInfoUpdatePacket.Entry(
                profile.id(), profile, false, 0, GameType.SURVIVAL, null, true, 0, null);
        return new ClientboundPlayerInfoUpdatePacket(EnumSet.of(
                ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER,
                ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LISTED,
                ClientboundPlayerInfoUpdatePacket.Action.UPDATE_GAME_MODE), entry);
    }

    public static Packet<?> playerInfoRemove(UUID uuid) {
        return new ClientboundPlayerInfoRemovePacket(List.of(uuid));
    }

    public static Packet<?> addPlayer(int entityId, UUID uuid, double x, double y, double z,
                                      float yaw, float pitch, float headYaw) {
        return new ClientboundAddEntityPacket(entityId, uuid, x, y, z, pitch, yaw,
                EntityType.PLAYER, 0, Vec3.ZERO, headYaw);
    }

    public static Packet<?> entityData(int entityId, boolean sneaking) {
        List<SynchedEntityData.DataValue<?>> values = new ArrayList<>(3);
        values.add(new SynchedEntityData.DataValue<>(ID_SHARED_FLAGS, EntityDataSerializers.BYTE,
                sneaking ? FLAG_SNEAKING : (byte) 0));
        values.add(new SynchedEntityData.DataValue<>(ID_POSE, EntityDataSerializers.POSE,
                sneaking ? Pose.CROUCHING : Pose.STANDING));
        values.add(new SynchedEntityData.DataValue<>(ID_SKIN_PARTS, EntityDataSerializers.BYTE, ALL_SKIN_PARTS));
        return new ClientboundSetEntityDataPacket(entityId, values);
    }

    public static Packet<?> moveRelative(int entityId, short dx, short dy, short dz,
                                         float yaw, float pitch, boolean onGround) {
        return new ClientboundMoveEntityPacket.PosRot(entityId, dx, dy, dz, angle(yaw), angle(pitch), onGround);
    }

    public static Packet<?> rotate(int entityId, float yaw, float pitch, boolean onGround) {
        return new ClientboundMoveEntityPacket.Rot(entityId, angle(yaw), angle(pitch), onGround);
    }

    public static Packet<?> teleport(int entityId, double x, double y, double z,
                                     float yaw, float pitch, boolean onGround) {
        return new ClientboundEntityPositionSyncPacket(entityId,
                new PositionMoveRotation(new Vec3(x, y, z), Vec3.ZERO, yaw, pitch), onGround);
    }

    public static Packet<?> headRotation(int entityId, float headYaw) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer(8));
        try {
            buf.writeVarInt(entityId);
            buf.writeByte(angle(headYaw));
            return ClientboundRotateHeadPacket.STREAM_CODEC.decode(buf);
        } finally {
            buf.release();
        }
    }

    public static Packet<?> removeEntity(int entityId) {
        return new ClientboundRemoveEntitiesPacket(entityId);
    }

    public static byte angle(float degrees) {
        return (byte) Math.floor(degrees * 256.0f / 360.0f);
    }

    private static int accessorId(Class<?> owner, String fieldName, int fallback) {
        try {
            Field field = owner.getDeclaredField(fieldName);
            field.setAccessible(true);
            return ((EntityDataAccessor<?>) field.get(null)).id();
        } catch (ReflectiveOperationException | RuntimeException e) {
            return fallback;
        }
    }
}
