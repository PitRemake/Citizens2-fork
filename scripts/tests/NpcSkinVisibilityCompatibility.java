import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import net.citizensnpcs.api.npc.*;
import net.citizensnpcs.npc.CitizensNPC;
import net.citizensnpcs.nms.v1_8_R3.entity.EntityHumanNPC;
import net.citizensnpcs.nms.v1_8_R3.util.*;
import net.minecraft.server.v1_8_R3.*;
import org.bukkit.craftbukkit.v1_8_R3.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerQuitEvent;
import sun.misc.Unsafe;

/** Packaged Citizens profile lifecycle running the real Wind updatePlayer path. */
public final class NpcSkinVisibilityCompatibility {
    static Unsafe unsafe;
    static void check(boolean value,String message) { if(!value)throw new AssertionError(message); }
    static Field field(Class<?> type,String name)throws Exception { Field f=type.getDeclaredField(name);f.setAccessible(true);return f; }
    static <T>T allocate(Class<T> type)throws Exception { return type.cast(unsafe.allocateInstance(type)); }
    static FakeNpc npc(UUID id)throws Exception {
        FakeNpc entity=allocate(FakeNpc.class);
        entity.profile=new GameProfile(id,"SkinBot");
        entity.profile.getProperties().put("textures",new Property("textures","captured-texture","signature"));
        CitizensNPC npc=allocate(CitizensNPC.class);
        SimpleMetadataStore data=new SimpleMetadataStore();data.set("pitsim-combat-bot",true);
        field(AbstractNPC.class,"metadata").set(npc,data);field(EntityHumanNPC.class,"npc").set(entity,npc);
        entity.inventory=new PlayerInventory(entity);
        entity.watcher=new DataWatcher(entity);
        entity.attributes=new AttributeMapServer();
        entity.bukkit=allocate(FakePlayer.class);entity.bukkit.handle=entity;
        field(EntityPlayer.class,"playerInteractManager").set(entity,allocate(Interaction.class));
        return entity;
    }
    static EntityPlayer viewer()throws Exception {
        Viewer entity=allocate(Viewer.class);
        Connection connection=allocate(Connection.class);connection.sent=new ArrayList<>();
        field(PlayerConnection.class,"queuedPackets").set(connection,new LinkedBlockingQueue<Packet<?>>());
        entity.playerConnection=connection;
        entity.bukkit=allocate(FakePlayer.class);entity.bukkit.handle=entity;
        return entity;
    }
    static Tracker tracker(FakeNpc entity)throws Exception {
        Tracker tracker=allocate(Tracker.class);
        field(PlayerlistTrackerEntry.class,"trackedEntity").set(tracker,entity);
        field(PlayerlistTrackerEntry.class,"lastUpdatedPlayer").set(tracker,new ThreadLocal<EntityPlayer>());
        field(PlayerlistTrackerEntry.class,"skinlessProfileAdded").set(tracker,new ThreadLocal<Boolean>());
        field(EntityTrackerEntry.class,"tracker").set(tracker,entity);
        field(EntityTrackerEntry.class,"entityTracker").set(tracker,allocate(EntityTracker.class));
        Map<EntityPlayer,Boolean> players=new IdentityHashMap<>();
        field(EntityTrackerEntry.class,"trackedPlayerMap").set(tracker,players);
        field(EntityTrackerEntry.class,"trackedPlayers").set(tracker,players.keySet());
        field(EntityTrackerEntry.class,"b").setInt(tracker,64);
        return tracker;
    }
    static List<Packet<?>> take(EntityPlayer viewer)throws Exception {
        Queue<Packet<?>> queue=(Queue<Packet<?>>)field(PlayerConnection.class,"queuedPackets").get(viewer.playerConnection);
        List<Packet<?>> result=new ArrayList<>(queue);queue.clear();return result;
    }
    static Map<?,?> pending()throws Exception {return (Map<?,?>)field(PitSkinProfiles.class,"PENDING").get(null);}
    static void ticks(int count)throws Exception {
        Method drain=PitSkinProfiles.class.getDeclaredMethod("drain");drain.setAccessible(true);
        for(int i=0;i<count;i++)drain.invoke(null);
    }
    static PacketPlayOutPlayerInfo.EnumPlayerInfoAction action(Packet<?> packet)throws Exception {
        return (PacketPlayOutPlayerInfo.EnumPlayerInfoAction)field(PacketPlayOutPlayerInfo.class,"a").get(packet);
    }
    static void spawnOrder(List<Packet<?>> packets)throws Exception {
        check(packets.size()>=2,"Native spawn packets missing");
        check(packets.get(0) instanceof PacketPlayOutPlayerInfo && action(packets.get(0))==PacketPlayOutPlayerInfo.EnumPlayerInfoAction.ADD_PLAYER,"Profile must precede native spawn");
        check(packets.get(1) instanceof PacketPlayOutNamedEntitySpawn,"Native spawn must immediately follow profile");
        for(Packet<?> packet:packets) if(packet instanceof PacketPlayOutPlayerInfo) check(action(packet)!=PacketPlayOutPlayerInfo.EnumPlayerInfoAction.REMOVE_PLAYER,"Early profile removal");
    }
    public static void main(String[] args)throws Exception {
        unsafe=(Unsafe)field(Unsafe.class,"theUnsafe").get(null);
        org.bukkit.plugin.PluginManager plugins=(org.bukkit.plugin.PluginManager)Proxy.newProxyInstance(
                NpcSkinVisibilityCompatibility.class.getClassLoader(),new Class[]{org.bukkit.plugin.PluginManager.class},
                (p,m,a)->m.getReturnType()==Set.class?Collections.emptySet():m.getReturnType()==boolean.class?false:null);
        org.bukkit.craftbukkit.v1_8_R3.CraftServer server=allocate(org.bukkit.craftbukkit.v1_8_R3.CraftServer.class);
        field(org.bukkit.craftbukkit.v1_8_R3.CraftServer.class,"pluginManager").set(server,plugins);
        field(org.bukkit.Bukkit.class,"server").set(null,server);
        unsafe=(Unsafe)field(Unsafe.class,"theUnsafe").get(null);DispenserRegistry.c();
        check(PlayerlistTrackerEntry.hasNativeSpawnHook(),"Actual Wind native spawn hook unavailable");
        UUID id=UUID.randomUUID();FakeNpc first=npc(id);EntityPlayer viewer=viewer();
        Tracker tracker=tracker(first);first.setTracked(tracker);
        tracker.updatePlayer(viewer);spawnOrder(take(viewer));
        tracker.updatePlayer(viewer);check(take(viewer).isEmpty(),"Healthy tracked bot was resent");
        // Metadata reads must never publish another skin profile.
        first.getDataWatcher();check(take(viewer).isEmpty(),"Metadata caused a profile refresh");
        ticks(1);check(take(viewer).isEmpty(),"Profile removed before first-render grace");
        tracker.trackedPlayers.clear();tracker.updatePlayer(viewer);spawnOrder(take(viewer));
        ticks(1);check(take(viewer).isEmpty(),"Old range-entry removal erased new ADD");
        // Same UUID, different entity: late despawn of old entity must be harmless.
        FakeNpc replacement=npc(id);Tracker replacementTracker=tracker(replacement);replacement.setTracked(replacementTracker);
        replacementTracker.updatePlayer(viewer);spawnOrder(take(viewer));PitSkinProfiles.removed(first);
        ticks(1);check(take(viewer).isEmpty(),"Old entity removed replacement profile");
        ticks(1);List<Packet<?>> removed=take(viewer);
        check(removed.size()==1 && action(removed.get(0))==PacketPlayOutPlayerInfo.EnumPlayerInfoAction.REMOVE_PLAYER,"Exactly one final removal required");
        check(pending().isEmpty(),"Expired profiles retain viewers/entities");
        // Reconnect uses a new connection; quit clears the exact old session only.
        PitSkinProfiles.add(viewer,first);EntityPlayer reconnect=viewer();PitSkinProfiles.add(reconnect,replacement);
        new PitSkinProfiles().quit(new PlayerQuitEvent(viewer.getBukkitEntity(),""));
        check(pending().size()==1 && pending().containsKey(reconnect),"Quit deleted reconnect or retained old session");
        take(reconnect);ticks(2);take(reconnect);check(pending().isEmpty(),"Reconnect profile did not expire");
        // Many NPCs for one viewer use batched removals, not one task/packet per bot.
        int bots=120;
        for(int i=0;i<bots;i++)PitSkinProfiles.add(viewer,npc(UUID.randomUUID()));
        take(viewer);ticks(2);List<Packet<?>> batches=take(viewer);
        check(batches.size()==8,"Expected 120 removals / 15 entries = 8 packets, got "+batches.size());
        check(pending().isEmpty(),"Batch retained NPCs");
        // Concurrent viewers must receive their own ADD then native SPAWN.
        Tracker concurrent=tracker(npc(UUID.randomUUID()));List<EntityPlayer> viewers=new ArrayList<>();
        ExecutorService workers=Executors.newFixedThreadPool(8);List<Future<?>> jobs=new ArrayList<>();
        for(int i=0;i<32;i++){EntityPlayer v=viewer();viewers.add(v);jobs.add(workers.submit(()->concurrent.updatePlayer(v)));}
        for(Future<?> job:jobs)job.get();workers.shutdown();
        check(concurrent.trackedPlayers.size()==32,"Concurrent viewer admission corrupted native tracker map");
        for(EntityPlayer v:viewers)spawnOrder(take(v));
        ticks(2);for(EntityPlayer v:viewers)check(take(v).size()==1,"Viewer missed removal");
        check(pending().isEmpty(),"Concurrent tracking retained sessions");
        // Keeper is managed for packet ordering but keeps its longer skin policy.
        FakeNpc keeper=npc(UUID.randomUUID());
        keeper.getNPC().data().set("pitsim-combat-bot",false);
        keeper.getNPC().data().set("pitsim-lobby-role","keeper");
        PitSkinProfiles.add(viewer,keeper);take(viewer);
        ticks(19);check(take(viewer).isEmpty(),"Keeper skin policy was shortened");
        ticks(1);check(take(viewer).size()==1,"Keeper profile failed to expire");
        // Despawning on the last grace tick must not extend the bot's tab entry.
        PitSkinProfiles.add(viewer,first);take(viewer);ticks(1);PitSkinProfiles.removed(first);
        ticks(1);check(take(viewer).size()==1,"Despawn extended bot tab lifetime");
        check(pending().isEmpty(),"Short grace retained viewer/entity references");
        FakeNpc bare=npc(UUID.randomUUID());bare.getProfile().getProperties().clear();
        bare.getNPC().data().set("pitsim-skinless",true);
        Tracker bareTracker=tracker(bare);bare.setTracked(bareTracker);
        bareTracker.updatePlayer(viewer);skinlessOrder(take(viewer));
        check(pending().isEmpty(),"Skinless spawn retained a delayed profile");
        bareTracker.updatePlayer(viewer);check(take(viewer).isEmpty(),"Healthy skinless bot was replayed");
        bareTracker.trackedPlayers.clear();bareTracker.updatePlayer(viewer);skinlessOrder(take(viewer));
        ticks(25);check(take(viewer).isEmpty(),"Skinless range-entry scheduled a late packet");
        Tracker bareConcurrent=tracker(bare);ExecutorService bareWorkers=Executors.newFixedThreadPool(8);
        List<Future<?>> bareJobs=new ArrayList<>();List<EntityPlayer> bareViewers=new ArrayList<>();
        for(int i=0;i<32;i++){EntityPlayer v=viewer();bareViewers.add(v);bareJobs.add(bareWorkers.submit(()->bareConcurrent.updatePlayer(v)));}
        for(Future<?> job:bareJobs)job.get();bareWorkers.shutdown();
        for(EntityPlayer v:bareViewers)skinlessOrder(take(v));
        check(pending().isEmpty(),"Concurrent skinless tracking retained viewers");
        Tracker warped=tracker(bare);warped.updatePlayer(viewer);take(viewer);
        bare.locX=100.03125D;bare.locY=84D;bare.locZ=-.03125D;bare.yaw=90F;bare.pitch=-30F;
        warped.synchronizePosition();List<Packet<?>> corrections=take(viewer);
        check(corrections.size()==1 && corrections.get(0) instanceof PacketPlayOutEntityTeleport,"Teleport must send one body correction without a profile replay");
        check(field(EntityTrackerEntry.class,"xLoc").getInt(warped)==3201,"Teleport X baseline stale");
        check(field(EntityTrackerEntry.class,"yLoc").getInt(warped)==2688,"Teleport Y baseline stale");
        check(field(EntityTrackerEntry.class,"zLoc").getInt(warped)==-1,"Teleport negative Z baseline stale");
        check(!warped.trackedPlayerMap.get(viewer),"Teleport did not supply viewer's absolute baseline");
        check(!warped.isUpdating() && field(PlayerlistTrackerEntry.class,"skinlessProfileAdded").get(warped)!=null,"Tracking scope damaged by teleport");
        check(pending().isEmpty(),"Teleport correction created delayed profiles");
        System.out.println("TELEPORT_BASELINE_PASS: absolute body correction, quantized XYZ/rotation baseline, no ADD/SPAWN/REMOVE or delayed task");
        System.out.println("SKINLESS_PASS: ordered ADD/SPAWN/REMOVE in one queue; join/range/concurrent32; no delayed profiles or repeated packets");
        System.out.println("SKIN_VISIBILITY_PASS: real Wind join/range-entry/respawn; ADD before SPAWN; 2-tick bot grace; Keeper policy preserved; stale removals fenced; reconnect cleanup; 32 concurrent viewers");
        System.out.println("PROFILE_BATCH_PASS: 120 removals -> 8 packets; shared scheduler; zero retained expired profiles");
    }
    static void skinlessOrder(List<Packet<?>> packets)throws Exception {
        check(packets.size()>=3,"Skinless native spawn incomplete");
        check(action(packets.get(0))==PacketPlayOutPlayerInfo.EnumPlayerInfoAction.ADD_PLAYER,"Skinless ADD missing");
        check(packets.get(1) instanceof PacketPlayOutNamedEntitySpawn,"Skinless SPAWN missing or out of order");
        check(action(packets.get(packets.size()-1))==PacketPlayOutPlayerInfo.EnumPlayerInfoAction.REMOVE_PLAYER,"Skinless REMOVE must follow native spawn/equipment");
    }
    public static final class Tracker extends PlayerlistTrackerEntry {
        private Tracker(){super(null,64,2,true);}
        @Override public boolean c(EntityPlayer viewer){return true;}
        @Override protected boolean e(EntityPlayer viewer){return true;}
    }
    public static final class FakeNpc extends EntityHumanNPC {
        GameProfile profile;DataWatcher watcher;AttributeMapServer attributes;FakePlayer bukkit;
        private FakeNpc(){super(null,null,null,null,null);}
        @Override public GameProfile getProfile(){return profile;}
        @Override public DataWatcher getDataWatcher(){return watcher;}
        @Override public CraftPlayer getBukkitEntity(){return bukkit;}
        @Override public AttributeMapBase getAttributeMap(){return attributes;}
        @Override public Collection<MobEffect> getEffects(){return Collections.emptyList();}
        @Override public IChatBaseComponent getPlayerListName(){return null;}
    }
    public static final class Viewer extends EntityPlayer {
        FakePlayer bukkit;
        private Viewer(){super(null,null,null,null);}
        @Override public CraftPlayer getBukkitEntity(){return bukkit;}
    }
    public static final class FakePlayer extends CraftPlayer {
        EntityPlayer handle;
        private FakePlayer(){super(null,null);}
        @Override public EntityPlayer getHandle(){return handle;}
        @Override public boolean canSee(Player player){return true;}
        @Override public boolean canSee(org.bukkit.entity.Entity player){return true;}
    }
    public static final class Interaction extends PlayerInteractManager {
        private Interaction(){super(null);}
        @Override public WorldSettings.EnumGamemode getGameMode(){return WorldSettings.EnumGamemode.SURVIVAL;}
    }
    public static final class Connection extends PlayerConnection {
        List<Packet<?>> sent;
        private Connection(){super(null,null,null);}
        @Override public void sendPacket(Packet packet){sent.add(packet);}
    }
}
