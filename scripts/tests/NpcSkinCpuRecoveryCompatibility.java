import java.lang.reflect.*;
import java.util.*;
import java.util.logging.Logger;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import net.citizensnpcs.api.*;
import net.citizensnpcs.api.npc.*;
import net.citizensnpcs.npc.skin.*;
import net.citizensnpcs.npc.profile.*;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.craftbukkit.v1_8_R3.CraftServer;
import org.bukkit.craftbukkit.v1_8_R3.entity.CraftPlayer;
import org.bukkit.craftbukkit.v1_8_R3.scheduler.CraftScheduler;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import sun.misc.Unsafe;

/** Work counters plus skin fetch/lifecycle regressions; no network or real player data. */
public final class NpcSkinCpuRecoveryCompatibility {
    static Unsafe unsafe;static QueueScheduler scheduler;static int managedReads,ordinaryReads,refreshes;
    static List<NPC> npcs=new ArrayList<>();static World world;static Plugin plugin;
    static Field field(Class<?> type,String name)throws Exception{Field f=type.getDeclaredField(name);f.setAccessible(true);return f;}
    static <T>T allocate(Class<T> type)throws Exception{return type.cast(unsafe.allocateInstance(type));}
    static void check(boolean value,String label){if(!value)throw new AssertionError(label);}
    static Object call(Object target,String name,Class<?>[] types,Object...args)throws Exception {
        Method m=target.getClass().getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(target,args);
    }
    static Object defaults(Class<?> type){if(type==boolean.class)return false;if(type==int.class)return 0;if(type==long.class)return 0L;return null;}
    static NPC npc(boolean managed)throws Exception {
        SimpleMetadataStore data=new SimpleMetadataStore();data.set("pitsim-combat-bot",managed);
        FakePlayer player=player();DummySkinTracker skin=allocate(DummySkinTracker.class);skin.managed=managed;player.skin=skin;
        net.citizensnpcs.trait.SkinTrait trait=new net.citizensnpcs.trait.SkinTrait();
        NPC npc=(NPC)Proxy.newProxyInstance(NPC.class.getClassLoader(),new Class[]{NPC.class},(p,m,a)->{
            if(m.getName().equals("data"))return data;
            if(m.getName().equals("getEntity")){if(managed)managedReads++;else ordinaryReads++;return player;}
            if(m.getName().equals("getUniqueId"))return player.id;
            if(m.getName().equals("isSpawned"))return true;
            if(m.getName().equals("getName"))return "SkinBot";
            if(m.getName().equals("getOrAddTrait"))return trait;
            return defaults(m.getReturnType());
        });player.npc=npc;return npc;
    }
    static FakePlayer player()throws Exception {FakePlayer p=allocate(FakePlayer.class);p.id=UUID.randomUUID();p.online=true;return p;}
    static void init()throws Exception {
        unsafe=(Unsafe)field(Unsafe.class,"theUnsafe").get(null);
        CraftServer server=allocate(CraftServer.class);
        org.bukkit.plugin.PluginManager plugins=(org.bukkit.plugin.PluginManager)Proxy.newProxyInstance(
            Plugin.class.getClassLoader(),new Class[]{org.bukkit.plugin.PluginManager.class},(p,m,a)->{
                if(m.getReturnType()==Set.class)return Collections.emptySet();return defaults(m.getReturnType());});
        field(CraftServer.class,"pluginManager").set(server,plugins);
        field(CraftServer.class,"logger").set(server,Logger.getLogger("citizens-test"));
        field(Bukkit.class,"server").set(null,server);
        net.minecraft.server.v1_8_R3.DedicatedServer console=allocate(net.minecraft.server.v1_8_R3.DedicatedServer.class);
        field(net.minecraft.server.v1_8_R3.MinecraftServer.class,"primaryThread").set(console,Thread.currentThread());
        for(Field f:net.minecraft.server.v1_8_R3.MinecraftServer.class.getDeclaredFields()) {
            if(f.getType()==net.minecraft.server.v1_8_R3.PlayerList.class) {
                f.setAccessible(true);f.set(console,allocate(net.minecraft.server.v1_8_R3.DedicatedPlayerList.class));
            }
        }
        field(CraftServer.class,"console").set(server,console);
        field(CraftServer.class,"playerList").set(server,allocate(net.minecraft.server.v1_8_R3.DedicatedPlayerList.class));
        field(CraftServer.class,"scoreboardManager").set(server,new org.bukkit.craftbukkit.v1_8_R3.scoreboard.CraftScoreboardManager(
                console,new net.minecraft.server.v1_8_R3.Scoreboard()));
        scheduler=new QueueScheduler();field(CraftServer.class,"scheduler").set(server,scheduler);
        plugin=(Plugin)Proxy.newProxyInstance(Plugin.class.getClassLoader(),new Class[]{Plugin.class},(p,m,a)->{
            if(m.getName().equals("isEnabled"))return true;
            if(m.getName().equals("getLogger"))return Logger.getLogger("citizens-test");
            return defaults(m.getReturnType());});
        NPCRegistry registry=(NPCRegistry)Proxy.newProxyInstance(NPCRegistry.class.getClassLoader(),new Class[]{NPCRegistry.class},
            (p,m,a)->m.getName().equals("iterator")?npcs.iterator():defaults(m.getReturnType()));
        CitizensPlugin citizens=(CitizensPlugin)Proxy.newProxyInstance(CitizensPlugin.class.getClassLoader(),new Class[]{CitizensPlugin.class},(p,m,a)->{
            if(m.getName().equals("getPlugin"))return plugin;
            if(m.getName().equals("getNPCRegistry"))return registry;
            return defaults(m.getReturnType());});
        field(CitizensAPI.class,"instance").set(null,citizens);
        world=(World)Proxy.newProxyInstance(World.class.getClassLoader(),new Class[]{World.class},(p,m,a)->{
            if(m.getName().equals("equals"))return p==a[0];
            if(m.getName().equals("hashCode"))return 1;
            return defaults(m.getReturnType());});
    }
    static void benchmark(boolean baseline)throws Exception {
        for(int i=0;i<200;i++)npcs.add(npc(true));for(int i=0;i<10;i++)npcs.add(npc(false));
        SkinUpdateTracker tracker=new SkinUpdateTracker(new HashMap<String,NPCRegistry>());
        List<FakePlayer> viewers=new ArrayList<>();for(int i=0;i<20;i++)viewers.add(player());
        long start=System.nanoTime();
        for(int i=0;i<100;i++)for(FakePlayer viewer:viewers)tracker.updatePlayer(viewer,10,i==99);
        int tasks=scheduler.jobs.size();scheduler.flush();long elapsed=System.nanoTime()-start;
        if(!baseline){check(tasks==20,"Refreshes did not coalesce");check(managedReads==0,"Managed bots entered discarded skin scans");check(ordinaryReads==10,"NPC snapshot not shared across viewers");check(refreshes==200,"Ordinary NPC viewer updates changed");
            tracker.updatePlayer(viewers.get(0),10,false);tracker.removePlayer(viewers.get(0).id);scheduler.flush();
            check(((Map<?,?>)field(SkinUpdateTracker.class,"pendingUpdates").get(tracker)).isEmpty(),"Quit retained pending refresh");}
        System.out.println("SKIN_CPU_WORK: delayed_tasks="+tasks+" managed_entity_reads="+managedReads+" ordinary_entity_reads="+ordinaryReads+" ordinary_refreshes="+refreshes+" fixture_ms="+(elapsed/1000000.0));
    }
    static void result(Object request,ProfileFetchResult result,GameProfile profile)throws Exception {
        call(request,"setResult",new Class[]{GameProfile.class,ProfileFetchResult.class},profile,result);scheduler.flush();
    }
    static void recovery()throws Exception {
        Class<?> fetchClass=Class.forName("net.citizensnpcs.npc.profile.ProfileFetchThread");Constructor<?> ctor=fetchClass.getDeclaredConstructor();ctor.setAccessible(true);Object fetch=ctor.newInstance();
        Method method=fetchClass.getDeclaredMethod("fetch",String.class,ProfileFetchHandler.class);method.setAccessible(true);
        List<ProfileFetchResult> callbacks=new ArrayList<>();ProfileFetchHandler handler=r->callbacks.add(r.getResult());
        method.invoke(fetch,"retrybot",handler);Map<String,ProfileRequest> requested=(Map<String,ProfileRequest>)field(fetchClass,"requested").get(fetch);
        ProfileRequest request=requested.get("retrybot");((Deque<?>)field(fetchClass,"queue").get(fetch)).clear();result(request,ProfileFetchResult.FAILED,null);
        check(callbacks.equals(Arrays.asList(ProfileFetchResult.FAILED)),"Initial failure not delivered");
        method.invoke(fetch,"retrybot",handler);method.invoke(fetch,"retrybot",handler);scheduler.flush();
        check(request.getResult()==ProfileFetchResult.PENDING && callbacks.size()==1,"Retry handler received stale failure");
        check(((Deque<?>)field(fetchClass,"queue").get(fetch)).size()==1,"Same-name retries duplicated upstream fetch");
        GameProfile profile=new GameProfile(UUID.randomUUID(),"retrybot");profile.getProperties().put("textures",new Property("textures","texture","signature"));
        result(request,ProfileFetchResult.SUCCESS,profile);check(callbacks.size()==3 && callbacks.get(2)==ProfileFetchResult.SUCCESS,"Retry result missed joined handlers");
        // Exercise the Skin callback itself through the shared ProfileFetcher request.
        Skin skin=Skin.get("recover_skin",false);
        FakePlayer waiting=(FakePlayer)npc(false).getEntity();waiting.skinName="recover_skin";
        ((Map<SkinnableEntity,Void>)field(Skin.class,"pending").get(skin)).put(waiting,null);
        call(skin,"fetch",new Class[]{});
        Object thread=field(ProfileFetcher.class,"PROFILE_THREAD").get(null);Class<?> type=thread.getClass();
        Map<String,ProfileRequest> requests=(Map<String,ProfileRequest>)field(type,"requested").get(thread);
        ProfileRequest failed=requests.get("recover_skin");((Deque<?>)field(type,"queue").get(thread)).clear();
        result(failed,ProfileFetchResult.FAILED,null);
        check(!field(Skin.class,"hasFetched").getBoolean(skin) && !field(Skin.class,"fetching").getBoolean(skin),"Skin failure latched forever");
        check(field(Skin.class,"retryTask").get(skin)!=null,"Skin failure did not schedule retry");
        // Execute the backoff task; repeated apply attempts cannot skip its delay.
        call(skin,"fetch",new Class[]{});check(((Deque<?>)field(type,"queue").get(thread)).isEmpty(),"Backoff bypassed");
        scheduler.flush();check(failed.getResult()==ProfileFetchResult.PENDING,"Skin retry did not restart request");
        GameProfile recovered=new GameProfile(UUID.randomUUID(),"recover_skin");recovered.getProperties().put("textures",new Property("textures","texture","signature"));
        result(failed,ProfileFetchResult.SUCCESS,recovered);check(skin.hasSkinData(),"Successful retry did not load texture");
        Skin retired=Skin.get("retired_skin",false);call(retired,"fetch",new Class[]{});
        ProfileRequest retiredRequest=requests.get("retired_skin");((Deque<?>)field(type,"queue").get(thread)).clear();
        result(retiredRequest,ProfileFetchResult.FAILED,null);scheduler.flush();
        check(field(Skin.class,"retryTask").get(retired)==null && ((Deque<?>)field(type,"queue").get(thread)).isEmpty(),"Retired/no-NPC skin retries retained a perpetual task");
        NPC bare=npc(true);bare.data().set("pitsim-skinless",true);
        Map<?,?> skinCache=(Map<?,?>)field(Skin.class,"CACHE").get(null);
        int beforeCache=skinCache.size();
        FakePlayer barePlayer=(FakePlayer)bare.getEntity();
        check(Skin.get(barePlayer,false)==null && Skin.get(barePlayer,true)==null,"Skinless NPC requested textures");
        check(skinCache.size()==beforeCache,"Skinless lookup created a cache entry");
        System.out.println("SKINLESS_FETCH_PASS: normal/forced lookups create zero skins and zero fetch work");
        // Keep objects alive deliberately: prove bounded cache even without GC.
        List<Skin> held=new ArrayList<>();for(int i=0;i<10000;i++)held.add(Skin.get("cache"+i,false));
        check(((Map<?,?>)field(Skin.class,"CACHE").get(null)).size()<=2048,"Skin cache is unbounded");
        Deque<?> queue=(Deque<?>)field(fetchClass,"queue").get(fetch);
        for(int i=0;i<10000;i++){method.invoke(fetch,"history"+i,null);ProfileRequest r=requested.get("history"+i);field(ProfileRequest.class,"result").set(r,ProfileFetchResult.SUCCESS);queue.clear();}
        check(requested.size()<=2048,"Completed profile cache is unbounded");
        System.out.println("SKIN_RECOVERY_PASS: transient failure retries/backoff; handlers receive fresh success; same-name fetch dedup; skin/profile caches <= 2048");
    }
    public static void main(String[] args)throws Exception {init();boolean baseline=args.length>0;benchmark(baseline);if(!baseline)recovery();}
    public static final class FakePlayer extends CraftPlayer implements SkinnableEntity {
        UUID id;boolean online;NPC npc;SkinPacketTracker skin;String skinName;
        private FakePlayer(){super(null,null);}
        @Override public UUID getUniqueId(){return id;}
        @Override public boolean isOnline(){return online;}
        @Override public boolean isValid(){return true;}
        @Override public boolean hasMetadata(String name){return false;}
        @Override public World getWorld(){return world;}
        @Override public Location getLocation(Location to){to.setWorld(world);to.setX(0);to.setY(64);to.setZ(0);return to;}
        @Override public boolean canSee(Player other){return true;}
        @Override public Player getBukkitEntity(){return this;}
        @Override public NPC getNPC(){return npc;}
        @Override public SkinPacketTracker getSkinTracker(){return skin;}
        @Override public String getSkinName(){return skinName==null?"skinbot":skinName;}
        @Override public GameProfile getProfile(){return new GameProfile(id,"SkinBot");}
        @Override public void setSkinFlags(byte flags){}
        @Override public void setSkinName(String name){}
        @Override public void setSkinName(String name,boolean force){}
        @Override public void setSkinPersistent(String name,String signature,String data){}
    }
    public static final class DummySkinTracker extends SkinPacketTracker {
        boolean managed;
        private DummySkinTracker(){super(null);}
        @Override public void updateViewer(Player viewer){if(!managed)refreshes++;}
    }
    public static final class Task implements BukkitTask {
        Runnable action;boolean cancelled;
        Task(Runnable action){this.action=action;}
        public int getTaskId(){return 1;}public Plugin getOwner(){return plugin;}public boolean isSync(){return true;}public void cancel(){cancelled=true;}
    }
    public static final class QueueScheduler extends CraftScheduler {
        List<Task> jobs=new ArrayList<>();
        @Override public BukkitTask runTaskLater(Plugin owner,Runnable action,long delay){Task t=new Task(action);jobs.add(t);return t;}
        @Override public int scheduleSyncDelayedTask(Plugin owner,Runnable action,long delay){runTaskLater(owner,action,delay);return 1;}
        @Override public int scheduleSyncDelayedTask(Plugin owner,Runnable action){return scheduleSyncDelayedTask(owner,action,0);}
        @Override public BukkitTask runTaskTimer(Plugin owner,Runnable action,long delay,long period){return new Task(action);}
        @Override public BukkitTask runTaskTimerAsynchronously(Plugin owner,Runnable action,long delay,long period){return new Task(action);}
        void flush(){List<Task> batch=new ArrayList<>(jobs);jobs.clear();for(Task task:batch)if(!task.cancelled)task.action.run();}
    }
}
