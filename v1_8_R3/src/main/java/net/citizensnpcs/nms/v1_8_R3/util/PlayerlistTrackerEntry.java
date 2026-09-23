package net.citizensnpcs.nms.v1_8_R3.util;

import java.lang.reflect.Field;

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

public class PlayerlistTrackerEntry extends EntityTrackerEntry {
    // WindSpigot can update different viewers of this entry concurrently. The
    // spawn packet constructor calls the NPC's getDataWatcher(), so keep that
    // callback bound to the viewer on the current tracking thread.
    private final ThreadLocal<EntityPlayer> lastUpdatedPlayer = new ThreadLocal<EntityPlayer>();

    public PlayerlistTrackerEntry(Entity entity, int i, int j, boolean flag) {
        super(entity, i, j, flag);
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
        final Entity tracker = getTracker(this);
        lastUpdatedPlayer.remove();
        NMS.sendTabListAdd(entityplayer.getBukkitEntity(), (Player) tracker.getBukkitEntity());
        if (isPitTabHidden(tracker)) {
            // The 1.8 client resolves the skin through NetworkPlayerInfo on
            // its first render. Removing the profile in this same network
            // pass leaves the NPC permanently on the default skin.
            Bukkit.getScheduler().scheduleSyncDelayedTask(CitizensAPI.getPlugin(), new Runnable() {
                @Override public void run() {
                    NMS.sendTabListRemove(entityplayer.getBukkitEntity(), (Player) tracker.getBukkitEntity());
                }
            }, Math.max(2, Setting.TABLIST_REMOVE_PACKET_DELAY.asInt()));
            return;
        }
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

    @Override
    public void updatePlayer(final EntityPlayer entityplayer) {
        // prevent updates to NPC "viewers"
        if (entityplayer instanceof EntityHumanNPC)
            return;
        final EntityPlayer previous = lastUpdatedPlayer.get();
        lastUpdatedPlayer.set(entityplayer);
        try {
            super.updatePlayer(entityplayer);
        } finally {
            if (previous == null)
                lastUpdatedPlayer.remove();
            else
                lastUpdatedPlayer.set(previous);
        }
    }

    private static boolean isPitTabHidden(Entity tracker) {
        if (!(tracker instanceof EntityHumanNPC))
            return false;
        net.citizensnpcs.api.npc.NPC npc = ((EntityHumanNPC) tracker).getNPC();
        return npc.data().get("pitsim-combat-bot", false)
                || "keeper".equals(npc.data().get("pitsim-lobby-role", ""));
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
