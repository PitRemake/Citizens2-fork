package net.citizensnpcs.nms.v1_8_R3.entity;

import java.lang.reflect.*;
import java.util.*;
import java.util.logging.Logger;
import net.citizensnpcs.api.ai.Navigator;
import net.citizensnpcs.api.npc.MetadataStore;
import net.citizensnpcs.api.npc.SimpleMetadataStore;
import net.citizensnpcs.npc.CitizensNPC;
import net.citizensnpcs.nms.v1_8_R3.util.PitControlFrame;
import net.citizensnpcs.nms.v1_8_R3.util.PlayerControllerMove;
import net.minecraft.server.v1_8_R3.*;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.v1_8_R3.CraftServer;
import sun.misc.Unsafe;

/** Real native travel/jump/collision/gravity/friction on a deterministic stone floor. */
public final class PitControlNativeTravel {
    private static Unsafe unsafe;
    private static Method consume;
    private static Field field(Class<?> type,String name)throws Exception { Field f=type.getDeclaredField(name);f.setAccessible(true);return f; }
    private static <T>T allocate(Class<T> type)throws Exception { return type.cast(unsafe.allocateInstance(type)); }
    private static void check(boolean value,String message) { if(!value)throw new AssertionError(message); }
    private static boolean step(TravelHuman human)throws Exception {
        human.lastX=human.locX;human.lastY=human.locY;human.lastZ=human.locZ;
        Object cache=field(World.class,"movementCache").get(human.world);cache.getClass().getMethod("clear").invoke(cache);
        try{return (Boolean)consume.invoke(human);}catch(InvocationTargetException failed){throw new AssertionError(failed.getCause());}
    }
    private static TravelHuman human()throws Exception {return human(1D);}
    private static TravelHuman human(double floorTop)throws Exception {
        TravelHuman human=allocate(TravelHuman.class);TestNPC npc=allocate(TestNPC.class);
        npc.metadata=new SimpleMetadataStore();npc.metadata.set("pitsim-combat-bot",true);
        npc.navigator=(Navigator)Proxy.newProxyInstance(Navigator.class.getClassLoader(),new Class[]{Navigator.class},(p,m,a)->m.getReturnType()==boolean.class?false:null);
        field(EntityHumanNPC.class,"npc").set(human,npc);
        field(EntityHumanNPC.class,"pitControl").set(human,new PitControlFrame());
        field(EntityHumanNPC.class,"controllerMove").set(human,allocate(TestMove.class));
        DataWatcher watcher=new DataWatcher(human);watcher.a(0,(byte)0);watcher.a(6,20F);field(Entity.class,"datawatcher").set(human,watcher);
        AttributeMapServer attributes=new AttributeMapServer();attributes.b(GenericAttributes.MOVEMENT_SPEED).setValue(.1D);
        field(EntityLiving.class,"c").set(human,attributes);field(EntityLiving.class,"effects").set(human,new HashMap<Integer,MobEffect>());
        field(Entity.class,"random").set(human,new Random(1L));
        human.abilities=new PlayerAbilities();human.inventory=new PlayerInventory(human);human.aM=.02F;
        FloorWorld world=allocate(FloorWorld.class);world.floorTop=floorTop;world.floor=new AxisAlignedBB(-256D,0D,-256D,256D,floorTop,256D);
        field(World.class,"methodProfiler").set(world,new MethodProfiler());
        field(World.class,"movementCache").set(world,field(World.class,"movementCache").getType().getConstructor().newInstance());
        field(World.class,"tacoSpigotConfig").set(world,allocate(field(World.class,"tacoSpigotConfig").getType()));
        field(World.class,"spigotConfig").set(world,allocate(field(World.class,"spigotConfig").getType()));
        human.world=world;human.width=.6F;human.length=1.8F;human.onGround=true;human.ticksLived=100;
        human.locX=8D;human.locY=floorTop;human.locZ=8D;human.a(new AxisAlignedBB(7.7D,floorTop,7.7D,8.3D,floorTop+1.8D,8.3D));
        return human;
    }
    public static void main(String[] args)throws Exception {
        unsafe=(Unsafe)field(Unsafe.class,"theUnsafe").get(null);
        CraftServer server=allocate(CraftServer.class);field(Bukkit.class,"server").set(null,server);
        DedicatedServer console=allocate(DedicatedServer.class);field(MinecraftServer.class,"primaryThread").set(console,Thread.currentThread());field(CraftServer.class,"console").set(server,console);
        field(CraftServer.class,"logger").set(server,Logger.getLogger("native-travel-fixture"));
        DispenserRegistry.c();consume=EntityHumanNPC.class.getDeclaredMethod("consumePitControl");consume.setAccessible(true);
        int priorTick=MinecraftServer.currentTick;
        try{run();}finally{MinecraftServer.currentTick=priorTick;}
    }
    private static void run()throws Exception {
        MinecraftServer.currentTick=1000;TravelHuman ground=human();
        for(int tick=0;tick<30;tick++) {
            check(ground.setPitControl(0F,0F,0F,1F,true,false),"Ground frame rejected");check(step(ground),"Ground frame missed");ground.ticksLived++;MinecraftServer.currentTick++;
        }
        System.out.printf(Locale.ROOT,"GROUND: z=%.6f y=%.6f motZ=%.6f motY=%.6f onGround=%s\n",ground.locZ,ground.locY,ground.motZ,ground.motY,ground.onGround);
        check(ground.locZ>10D,"Sustained native forward input failed to travel");
        TravelHuman jump=human();double peak=jump.locY;
        for(int tick=0;tick<30;tick++) {
            check(jump.setPitControl(0F,0F,0F,1F,true,tick==0),"Jump frame rejected");check(step(jump),"Jump frame missed");jump.ticksLived++;MinecraftServer.currentTick++;
            peak=Math.max(peak,jump.locY);
            System.out.printf(Locale.ROOT,"JUMP %02d: z=%.6f y=%.6f motZ=%.6f motY=%.6f onGround=%s air=%.4f\n",tick,jump.locZ,jump.locY,jump.motZ,jump.motY,jump.onGround,jump.aM);
        }
        check(peak>1.8D,"Native jump did not rise");check(jump.onGround&&Math.abs(jump.locY-1D)<1E-6,"Native jump did not land");check(jump.locZ>10D,"Native input stopped after jump");
        for(boolean inputBeforeCitizens:new boolean[]{true,false}) {
            TravelHuman ordered=human(.5D);MinecraftServer.currentTick=2000;
            if(!inputBeforeCitizens)check(ordered.setPitControl(0F,0F,0F,1F,true,true),"Prior scheduler submission rejected");
            double highest=ordered.locY;
            for(int tick=0;tick<60;tick++) {
                MinecraftServer.currentTick++;
                if(inputBeforeCitizens)check(ordered.setPitControl(0F,0F,0F,1F,true,tick==0),"Early submission rejected");
                ordered.ticksLived++; // Citizens TICKERS invokes world.g before PLAYERS.l.
                check(step(ordered),"Fresh input rejected for scheduler order "+inputBeforeCitizens+" at tick "+tick);
                if(!inputBeforeCitizens)check(ordered.setPitControl(0F,0F,0F,1F,true,false),"Late submission rejected");
                ordered.ticksLived++; // Wind's world entity pass invokes world.g again.
                highest=Math.max(highest,ordered.locY);
            }
            check(highest>1.5D,"Ordered bottom-slab native jump did not rise");check(ordered.onGround&&Math.abs(ordered.locY-.5D)<1E-6,"Ordered bottom-slab native jump did not land");check(ordered.locZ>20D,"Ordered native input stopped after jump");
            System.out.printf(Locale.ROOT,"ORDER_%s_SLAB: z=%.6f y=%.6f entityAge=%d serverTick=%d\n",inputBeforeCitizens?"BEFORE":"AFTER",ordered.locZ,ordered.locY,ordered.ticksLived,MinecraftServer.currentTick);
        }
        clockRules();
        System.out.println("NATIVE_TRAVEL_PASS: actual native g, bF and Entity.move through floor collision, gravity and friction; two explicit scheduler orderings and bounded server-clock frames; no full server/world scheduling claim");
    }
    private static void clockRules()throws Exception {
        TravelHuman same=human();MinecraftServer.currentTick=3000;same.setPitControl(0F,0F,0F,1F,false,false);same.ticksLived+=10;
        check(step(same),"Multiple native entity ages rejected same-server-tick input");check(!step(same),"Consumed frame replayed");
        TravelHuman next=human();MinecraftServer.currentTick=3100;next.setPitControl(0F,0F,0F,1F,false,false);next.ticksLived+=2;MinecraftServer.currentTick++;
        check(step(next),"Two world ticks rejected next-server-tick input");
        TravelHuman expired=human();MinecraftServer.currentTick=3200;expired.setPitControl(0F,0F,0F,1F,false,false);MinecraftServer.currentTick+=2;expired.ticksLived++;
        check(!step(expired),"Old input survived two server ticks despite one entity tick");
        TravelHuman rewind=human();MinecraftServer.currentTick=3300;rewind.setPitControl(0F,0F,0F,1F,false,false);MinecraftServer.currentTick--;rewind.ticksLived+=2;
        check(!step(rewind),"Future input accepted after server-clock rewind");
        TravelHuman wrap=human();MinecraftServer.currentTick=Integer.MAX_VALUE;wrap.setPitControl(0F,0F,0F,1F,false,false);MinecraftServer.currentTick=Integer.MIN_VALUE;wrap.ticksLived+=2;
        check(step(wrap),"Server-clock rollover lost following-tick input");
        TravelHuman cancel=human();MinecraftServer.currentTick=3400;cancel.setPitControl(0F,0F,0F,1F,false,false);cancel.clearPitControl();MinecraftServer.currentTick++;
        check(!step(cancel),"Canceled input survived into following server tick");
        TravelHuman replaced=human();MinecraftServer.currentTick=3500;replaced.setPitControl(0F,0F,0F,1F,false,false);MinecraftServer.currentTick+=2;replaced.setPitControl(0F,0F,0F,-1F,false,false);replaced.ticksLived+=3;
        check(step(replaced)&&replaced.locZ<8D,"Fresh replacement failed to replace expired forward input");
    }
    public static final class TravelHuman extends EntityHumanNPC {
        private TravelHuman(){super(null,null,null,null,null);}
        @Override protected void checkBlockCollisions() { }
        @Override protected void a(double distance,boolean ground,Block block,BlockPosition position) { }
        @Override protected boolean s_(){return false;}
        @Override public boolean V(){return false;}
        @Override public boolean ab(){return false;}
        @Override public boolean U(){return false;}
        @Override public boolean k_(){return false;}
        @Override public void checkMovement(double x,double y,double z){ }
        @Override public void b(Statistic statistic){ }
        @Override public void applyExhaustion(float amount){ }
    }
    public static final class FloorWorld extends WorldServer {
        AxisAlignedBB floor;double floorTop;
        private FloorWorld(){super(null,null,null,0,new MethodProfiler(),org.bukkit.World.Environment.NORMAL,null);}
        @Override public IBlockData getType(BlockPosition position){return getType(position.getX(),position.getY(),position.getZ());}
        @Override public IBlockData getType(int x,int y,int z){return y<0?Blocks.STONE.getBlockData():y==0?(floorTop==.5D?Blocks.STONE_SLAB:Blocks.STONE).getBlockData():Blocks.AIR.getBlockData();}
        @Override public List<AxisAlignedBB> getCubes(Entity entity,AxisAlignedBB bounds){return new ArrayList<AxisAlignedBB>(Collections.singletonList(floor));}
        @Override public boolean e(AxisAlignedBB bounds){return false;}
    }
    public static final class TestNPC extends CitizensNPC {
        SimpleMetadataStore metadata;Navigator navigator;
        private TestNPC(){super(null,0,"fixture",null,null);}
        @Override public MetadataStore data(){return metadata;}
        @Override public Navigator getNavigator(){return navigator;}
        @Override public boolean isSpawned(){return true;}
        @Override public boolean isFlyable(){return false;}
        @Override public boolean isProtected(){return true;}
    }
    public static final class TestMove extends PlayerControllerMove {
        private TestMove(){super(null);}
        @Override public boolean a(){return false;}
    }
}
