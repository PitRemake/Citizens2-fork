import io.netty.channel.embedded.EmbeddedChannel;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.LinkedBlockingQueue;
import net.citizensnpcs.nms.v1_8_R3.entity.EntityHumanNPC;
import net.citizensnpcs.nms.v1_8_R3.network.EmptyNetHandler;
import net.minecraft.server.v1_8_R3.*;
import sun.misc.Unsafe;

/** Offline fixtures executing the packaged Citizens class and real Wind tracker/queue code. */
public final class NpcPacketQueueCompatibility {
    private static final Unsafe UNSAFE;
    static {
        try {
            Field field = Unsafe.class.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            UNSAFE = (Unsafe) field.get(null);
        } catch (Exception failure) { throw new ExceptionInInitializerError(failure); }
    }

    private static <T> T allocate(Class<T> type) throws Exception {
        return type.cast(UNSAFE.allocateInstance(type));
    }
    private static void set(Object target, Class<?> owner, String name, Object value) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    private static EntityTrackerEntry tracker(EntityPlayer self, EntityPlayer... viewers) throws Exception {
        EntityTrackerEntry entry = allocate(EntityTrackerEntry.class);
        set(entry, EntityTrackerEntry.class, "tracker", self);
        Map<EntityPlayer, Boolean> map = new IdentityHashMap<>();
        for (EntityPlayer viewer : viewers) map.put(viewer, false);
        set(entry, EntityTrackerEntry.class, "trackedPlayerMap", map);
        set(entry, EntityTrackerEntry.class, "trackedPlayers", map.keySet());
        return entry;
    }
    private static LinkedBlockingQueue<Packet<?>> queue(PlayerConnection connection) throws Exception {
        LinkedBlockingQueue<Packet<?>> packets = new LinkedBlockingQueue<>();
        set(connection, PlayerConnection.class, "queuedPackets", packets);
        return packets;
    }

    public static void main(String[] args) throws Exception {
        Method broadcast = EntityTrackerEntry.class.getDeclaredMethod("broadcastInternal", Packet.class);
        Method selfBroadcast = EntityTrackerEntry.class.getDeclaredMethod("broadcastIncludingSelfInternal", Packet.class);
        broadcast.setAccessible(true);
        selfBroadcast.setAccessible(true);
        EntityHumanNPC npc = allocate(EntityHumanNPC.class);
        EmptyNetHandler npcConnection = allocate(EmptyNetHandler.class);
        npc.playerConnection = npcConnection;
        LinkedBlockingQueue<Packet<?>> npcQueue = queue(npcConnection);
        EntityPlayer player = allocate(EntityPlayer.class);
        RecordingConnection playerConnection = allocate(RecordingConnection.class);
        playerConnection.sent = new ArrayList<>();
        player.playerConnection = playerConnection;
        LinkedBlockingQueue<Packet<?>> playerQueue = queue(playerConnection);
        EntityTrackerEntry entry = tracker(npc, npc, player);
        Packet<?>[] packets = {
            new PacketPlayOutEntityVelocity(42, 0.1, 0.2, 0.3),
            new PacketPlayOutUpdateAttributes(42, Collections.<AttributeInstance>emptyList()),
            new PacketPlayOutEntityMetadata(42, new DataWatcher(null), true)
        };
        List<Packet<?>> expected = new ArrayList<>();
        for (int i = 0; i < 100000; i++) {
            Packet<?> packet = packets[i % packets.length];
            broadcast.invoke(entry, packet); // Actual tracker dispatch to NPC and real-player viewers.
            expected.add(packet);
            selfBroadcast.invoke(entry, packet); // Actual tracker's NPC self queue path.
            check(npcQueue.isEmpty(), "NPC tracker queue grew at update " + i);
        }
        check(playerQueue.size() == 100000, "Ordinary player lost queued tracker packets");
        check(playerConnection.sent.equals(expected), "Ordinary viewer lost direct self-broadcast packets");
        playerConnection.sent.clear();
        EmbeddedChannel channel = new EmbeddedChannel();
        try {
            NetworkManager network = new NetworkManager(EnumProtocolDirection.SERVERBOUND);
            network.channel = channel;
            set(playerConnection, PlayerConnection.class, "networkManager", network);
            playerConnection.sendQueuedPackets(); // Real Wind flush, recording only the final network send.
            check(playerQueue.isEmpty(), "Ordinary queue did not drain");
            check(playerConnection.sent.equals(expected), "Ordinary flush lost or reordered tracker packets");
            playerConnection.sendQueuedPackets();
            check(playerConnection.sent.size() == 100000, "Second flush duplicated packets");
        } finally { channel.finish(); }
        check(npcQueue.isEmpty(), "NPC queue must remain empty without periodic clearing");
        System.out.println("PASS NPC viewer and self tracker paths: 200000 queue attempts, zero retained");
        System.out.println("PASS ordinary viewer: 100000 queued and flushed in order, no duplication");
        System.out.println("PASS ordinary direct tracker broadcast: 100000 packets preserved");
    }
    public static final class RecordingConnection extends PlayerConnection {
        List<Packet<?>> sent;
        private RecordingConnection() { super(null, null, null); }
        @Override public void sendPacket(Packet packet) { sent.add(packet); }
    }
}
