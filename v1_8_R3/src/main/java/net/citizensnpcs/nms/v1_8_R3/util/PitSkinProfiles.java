package net.citizensnpcs.nms.v1_8_R3.util;

import java.lang.reflect.Method;
import java.util.*;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.v1_8_R3.entity.CraftPlayer;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.server.PluginDisableEvent;
import net.citizensnpcs.Settings.Setting;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.nms.v1_8_R3.entity.EntityHumanNPC;
import net.minecraft.server.v1_8_R3.EntityPlayer;
import net.minecraft.server.v1_8_R3.Packet;
import net.minecraft.server.v1_8_R3.PacketPlayOutPlayerInfo;
import net.minecraft.server.v1_8_R3.PlayerConnection;

/** Short-lived managed profiles, shared across tracker/entity replacements. */
public final class PitSkinProfiles implements Listener, Runnable {
    private static final Map<EntityPlayer, Map<UUID, Removal>> PENDING = new IdentityHashMap<>();
    private static final Method QUEUE_PACKET = queueMethod();
    private static long tick;
    private static long nextDue = Long.MAX_VALUE;
    private static boolean started;

    public static boolean manages(NPC npc) {
        return npc.data().get("pitsim-combat-bot", false)
                || "keeper".equals(npc.data().get("pitsim-lobby-role", ""));
    }

    public static synchronized void start() {
        if (started) return;
        PitSkinProfiles service = new PitSkinProfiles();
        Bukkit.getPluginManager().registerEvents(service, CitizensAPI.getPlugin());
        Bukkit.getScheduler().runTaskTimer(CitizensAPI.getPlugin(), service, 1, 1);
        started = true;
    }

    public static synchronized void add(EntityPlayer viewer, EntityHumanNPC entity) {
        sendOrdered(viewer, new PacketPlayOutPlayerInfo(PacketPlayOutPlayerInfo.EnumPlayerInfoAction.ADD_PLAYER, entity));
        // The native tracker queues REMOVE immediately after SPAWN for these
        // entities. Do not retain a viewer/entity or schedule a skin window.
        if (skinless(entity.getNPC())) return;
        Map<UUID, Removal> entries = PENDING.get(viewer);
        if (entries == null) PENDING.put(viewer, entries = new HashMap<>());
        // Bots need a render pass to cache their skin, but a full second of
        // profile lifetime causes visible tab flashes on every life/range entry.
        // Keeper and other ordinary NPC policies remain unchanged.
        boolean bot = entity.getNPC().data().get("pitsim-combat-bot", false);
        int minimum = bot ? 2 : 20;
        int fallback = bot ? 2 : Math.max(20, Setting.TABLIST_REMOVE_PACKET_DELAY.asInt());
        int delay = Math.max(minimum, Math.min(100, entity.getNPC().data().<Integer>get(
                "pitsim-skin-profile-ticks", fallback)));
        // New ADD supersedes old removal. Viewer identity fences reconnects.
        entries.put(entity.getProfile().getId(), new Removal(entity, tick + delay));
        nextDue = Math.min(nextDue, tick + delay);
    }

    public static boolean skinless(NPC npc) {
        return manages(npc) && npc.data().get("pitsim-skinless", false);
    }

    public static void completeSkinlessSpawn(EntityPlayer viewer, EntityHumanNPC entity) {
        sendOrdered(viewer, new PacketPlayOutPlayerInfo(PacketPlayOutPlayerInfo.EnumPlayerInfoAction.REMOVE_PLAYER, entity));
    }

    public static synchronized void removed(EntityHumanNPC entity) {
        UUID id = entity.getProfile().getId();
        for (Map<UUID, Removal> entries : PENDING.values()) {
            Removal removal = entries.get(id);
            if (removal != null && removal.entity == entity) {
                // Never lengthen an already shorter skin window on despawn.
                removal.due = Math.min(removal.due, tick + 2);
                nextDue = Math.min(nextDue, removal.due);
            }
        }
    }

    @Override public void run() { drain(); }

    private static synchronized void drain() {
        tick++;
        if (tick < nextDue) return;
        nextDue = Long.MAX_VALUE;
        int max = Math.max(1, Setting.MAX_PACKET_ENTRIES.asInt());
        Iterator<Map.Entry<EntityPlayer, Map<UUID, Removal>>> viewers = PENDING.entrySet().iterator();
        while (viewers.hasNext()) {
            Map.Entry<EntityPlayer, Map<UUID, Removal>> viewer = viewers.next();
            List<EntityPlayer> expired = null;
            Iterator<Removal> entries = viewer.getValue().values().iterator();
            while (entries.hasNext()) {
                Removal removal = entries.next();
                if (removal.due > tick) {
                    nextDue = Math.min(nextDue, removal.due);
                    continue;
                }
                entries.remove();
                if (expired == null) expired = new ArrayList<>();
                expired.add(removal.entity);
                if (expired.size() == max) {
                    removeBatch(viewer.getKey(), expired);
                    expired.clear();
                }
            }
            if (expired != null && !expired.isEmpty()) removeBatch(viewer.getKey(), expired);
            if (viewer.getValue().isEmpty()) viewers.remove();
        }
    }

    private static void removeBatch(EntityPlayer viewer, List<EntityPlayer> entities) {
        sendOrdered(viewer, new PacketPlayOutPlayerInfo(PacketPlayOutPlayerInfo.EnumPlayerInfoAction.REMOVE_PLAYER,
                entities.toArray(new EntityPlayer[entities.size()])));
    }

    // Keep ADD, native SPAWN and REMOVE on the same Wind connection queue.
    static void sendOrdered(EntityPlayer viewer, Packet<?> packet) {
        if (viewer.playerConnection == null) return;
        if (QUEUE_PACKET == null) { viewer.playerConnection.sendPacket(packet); return; }
        try { QUEUE_PACKET.invoke(viewer.playerConnection, packet); }
        catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Cannot queue Citizens skin profile", failure);
        }
    }

    private static Method queueMethod() {
        try { return PlayerConnection.class.getMethod("queuePacket", Packet.class); }
        catch (NoSuchMethodException expectedOnVanilla) { return null; }
    }

    @EventHandler public void quit(PlayerQuitEvent event) {
        if (event.getPlayer() instanceof CraftPlayer) synchronized (PitSkinProfiles.class) {
            PENDING.remove(((CraftPlayer)event.getPlayer()).getHandle());
        }
    }

    @EventHandler public void disable(PluginDisableEvent event) {
        if (event.getPlugin() == CitizensAPI.getPlugin()) synchronized (PitSkinProfiles.class) {
            PENDING.clear(); started = false; tick = 0; nextDue = Long.MAX_VALUE;
        }
    }

    private static final class Removal {
        final EntityHumanNPC entity;
        long due;
        Removal(EntityHumanNPC entity, long due) { this.entity = entity; this.due = due; }
    }
}
