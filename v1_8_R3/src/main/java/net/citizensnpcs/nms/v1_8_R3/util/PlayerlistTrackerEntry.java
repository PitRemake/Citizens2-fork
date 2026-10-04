package net.citizensnpcs.nms.v1_8_R3.util;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.List;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import net.citizensnpcs.Settings.Setting;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.nms.v1_8_R3.entity.EntityHumanNPC;
import net.citizensnpcs.util.NMS;
import net.minecraft.server.v1_8_R3.Entity;
import net.minecraft.server.v1_8_R3.EntityPlayer;
import net.minecraft.server.v1_8_R3.EntityTrackerEntry;
import net.minecraft.server.v1_8_R3.PacketPlayOutAnimation;
import net.minecraft.server.v1_8_R3.Packet;
import net.minecraft.server.v1_8_R3.EntityHuman;
import net.minecraft.server.v1_8_R3.PacketPlayOutNamedEntitySpawn;
import net.minecraft.server.v1_8_R3.PacketPlayOutEntityTeleport;
import net.minecraft.server.v1_8_R3.MathHelper;

public class PlayerlistTrackerEntry extends EntityTrackerEntry {
    // WindSpigot can update different viewers of this entry concurrently. The
    // spawn packet constructor calls the NPC's getDataWatcher(), so keep that
    // callback bound to the viewer on the current tracking thread.
    private final ThreadLocal<EntityPlayer> lastUpdatedPlayer = new ThreadLocal<EntityPlayer>();
    private final ThreadLocal<Boolean> skinlessProfileAdded = new ThreadLocal<Boolean>();
    private final Entity trackedEntity;

    public PlayerlistTrackerEntry(Entity entity, int i, int j, boolean flag) {
        super(entity, i, j, flag);
        trackedEntity = entity;
    }

    public PlayerlistTrackerEntry(EntityTrackerEntry entry) {
        this(getTracker(entry), getB(entry), getC(entry), getU(entry));
    }

    public boolean isUpdating() {
        return lastUpdatedPlayer.get() != null;
    }

    public void updateLastPlayer() {
        final EntityPlayer entityplayer = lastUpdatedPlayer.get();
        if (entityplayer == null)
            return;
        final Entity tracker = trackedEntity;
        lastUpdatedPlayer.remove();
        if (isPitTabHidden(tracker)) {
            PitSkinProfiles.add(entityplayer, (EntityHumanNPC) tracker);
            if (PitSkinProfiles.skinless(((EntityHumanNPC) tracker).getNPC())) skinlessProfileAdded.set(true);
            return;
        }
        NMS.sendTabListAdd(entityplayer.getBukkitEntity(), (Player) tracker.getBukkitEntity());
        if (!Setting.DISABLE_TABLIST.asBoolean())
            return;
        Bukkit.getScheduler().scheduleSyncDelayedTask(CitizensAPI.getPlugin(), new Runnable() {
            @Override
            public void run() {
                NMSImpl.sendPacket(entityplayer.getBukkitEntity(), new PacketPlayOutAnimation(tracker, 0));
                NMS.sendTabListRemove(entityplayer.getBukkitEntity(), (Player) tracker.getBukkitEntity());
            }
        }, Setting.TABLIST_REMOVE_PACKET_DELAY.asInt());
    }

    // Vanilla's c() is private; Wind exposes it as protected. Deliberately no
    // @Override so the pinned vanilla compile API remains supported.
    protected Packet c() {
        // Only an actual native spawn needs a profile. DataWatcher is also
        // accessed for metadata/effects, so it is not a reliable spawn hook.
        if (trackedEntity.dead) return null;
        updateLastPlayer();
        return new PacketPlayOutNamedEntitySpawn((EntityHuman) trackedEntity);
    }

    public static boolean hasNativeSpawnHook() { return NATIVE_SPAWN_HOOK; }

    private static final boolean NATIVE_SPAWN_HOOK = nativeSpawnHook();
    private static boolean nativeSpawnHook() {
        try { return Modifier.isProtected(EntityTrackerEntry.class.getDeclaredMethod("c").getModifiers()); }
        catch (NoSuchMethodException absent) { return false; }
    }

    @Override
    public void updatePlayer(final EntityPlayer entityplayer) {
        // prevent updates to NPC "viewers"
        if (entityplayer instanceof EntityHumanNPC)
            return;
        final EntityPlayer previous = lastUpdatedPlayer.get();
        final Boolean previousAdded = skinlessProfileAdded.get();
        skinlessProfileAdded.remove();
        lastUpdatedPlayer.set(entityplayer);
        try {
            if (isPitTabHidden(trackedEntity) && entityplayer.playerConnection != null) {
                // Wind's trackedPlayerMap is not a concurrent map. Keep
                // admissions for this NPC serialized, while other NPCs can
                // still be tracked by different workers.
                synchronized (this) {
                    super.updatePlayer(entityplayer);
                }
            } else {
                super.updatePlayer(entityplayer);
            }
        } finally {
            try {
                if (Boolean.TRUE.equals(skinlessProfileAdded.get()))
                    PitSkinProfiles.completeSkinlessSpawn(entityplayer, (EntityHumanNPC) trackedEntity);
            } finally {
                if (previousAdded == null) skinlessProfileAdded.remove();
                else skinlessProfileAdded.set(previousAdded);
                if (previous == null) lastUpdatedPlayer.remove();
                else lastUpdatedPlayer.set(previous);
            }
        }
    }

    @Override public void a(EntityPlayer player) {
        synchronized (this) { super.a(player); }
    }

    @Override public void clear(EntityPlayer player) {
        synchronized (this) { super.clear(player); }
    }

    @Override public void track(List<EntityHuman> players) {
        if (isPitTabHidden(trackedEntity)) synchronized (this) { super.track(players); }
        else super.track(players);
    }

    /** Queue one absolute correction after old movement and rebase future deltas. */
    public synchronized void synchronizePosition() {
        if (!isPitTabHidden(trackedEntity) || !NATIVE_SPAWN_HOOK) return;
        try {
            field("xLoc").setInt(this, MathHelper.floor(trackedEntity.locX * 32D));
            field("yLoc").setInt(this, MathHelper.floor(trackedEntity.locY * 32D));
            field("zLoc").setInt(this, MathHelper.floor(trackedEntity.locZ * 32D));
            field("yRot").setInt(this, MathHelper.d(trackedEntity.yaw * 256F / 360F));
            field("xRot").setInt(this, MathHelper.d(trackedEntity.pitch * 256F / 360F));
            field("lastOnGround").setBoolean(this, trackedEntity.onGround);
            field("ticksSinceLastForcedTeleport").setInt(this, 0);
            Packet<?> packet = new PacketPlayOutEntityTeleport(trackedEntity);
            for (EntityPlayer viewer : trackedPlayers) PitSkinProfiles.sendOrdered(viewer, packet);
            // The absolute correction now supplies every existing viewer's baseline.
            java.util.Map<EntityPlayer, Boolean> viewers = (java.util.Map<EntityPlayer, Boolean>) field("trackedPlayerMap").get(this);
            for (java.util.Map.Entry<EntityPlayer, Boolean> viewer : viewers.entrySet()) viewer.setValue(false);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Cannot synchronize Wind NPC teleport", failure);
        }
    }

    private static Field field(String name) throws NoSuchFieldException {
        Field field = EntityTrackerEntry.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static boolean isPitTabHidden(Entity tracker) {
        if (!(tracker instanceof EntityHumanNPC))
            return false;
        return PitSkinProfiles.manages(((EntityHumanNPC) tracker).getNPC());
    }

    private static int getB(EntityTrackerEntry entry) {
        try {
            return (Integer) B.get(entry);
        } catch (IllegalArgumentException e) {
            e.printStackTrace();
        } catch (IllegalAccessException e) {
            e.printStackTrace();
        }
        return 0;
    }

    private static int getC(EntityTrackerEntry entry) {
        try {
            return (Integer) C.get(entry);
        } catch (IllegalArgumentException e) {
            e.printStackTrace();
        } catch (IllegalAccessException e) {
            e.printStackTrace();
        }
        return 0;
    }

    private static Entity getTracker(EntityTrackerEntry entry) {
        try {
            return (Entity) TRACKER.get(entry);
        } catch (IllegalArgumentException e) {
            e.printStackTrace();
        } catch (IllegalAccessException e) {
            e.printStackTrace();
        }
        return null;
    }

    private static boolean getU(EntityTrackerEntry entry) {
        try {
            return (Boolean) U.get(entry);
        } catch (IllegalArgumentException e) {
            e.printStackTrace();
        } catch (IllegalAccessException e) {
            e.printStackTrace();
        }
        return false;
    }

    private static Field B = NMS.getField(EntityTrackerEntry.class, "b");
    private static Field C = NMS.getField(EntityTrackerEntry.class, "c");
    private static Field TRACKER = NMS.getField(EntityTrackerEntry.class, "tracker");
    private static Field U = NMS.getField(EntityTrackerEntry.class, "u");
}
