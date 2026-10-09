package net.citizensnpcs.nms.v1_8_R3.entity;

import java.lang.reflect.*;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
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

/** Offline native entry points. Travel/jump are intercepted; no fake world tick is claimed. */
public final class PitControlCompatibility {
    private static Unsafe unsafe;
    private static Method consume;
    private static int checks;
    private static Field field(Class<?> type,String name)throws Exception {
        Field value=type.getDeclaredField(name);value.setAccessible(true);return value;
    }
    private static <T>T allocate(Class<T> type)throws Exception{return type.cast(unsafe.allocateInstance(type));}
    private static void check(boolean value,String label){checks++;if(!value)throw new AssertionError(label);}
    private static void equal(double expected,double actual,String label){check(Math.abs(expected-actual)<1E-6,label+": "+actual);}
    private static boolean step(TestHuman human)throws Exception {
        try{return (Boolean)consume.invoke(human);}catch(InvocationTargetException failed){throw new AssertionError(failed.getCause());}
    }
    private static TestHuman human(boolean marked)throws Exception {
        TestHuman human=allocate(TestHuman.class);TestNPC npc=allocate(TestNPC.class);
        npc.metadata=new SimpleMetadataStore();npc.metadata.set("pitsim-combat-bot",marked);npc.spawned=true;
        npc.navigator=(Navigator)Proxy.newProxyInstance(Navigator.class.getClassLoader(),new Class[]{Navigator.class},(proxy,method,args)->{
            if(method.getName().equals("isNavigating"))return npc.navigating;
            return method.getReturnType()==boolean.class?false:null;
        });
        field(EntityHumanNPC.class,"npc").set(human,npc);human.testNPC=npc;
        field(EntityHumanNPC.class,"pitControl").set(human,new PitControlFrame());
        TestMove move=allocate(TestMove.class);field(EntityHumanNPC.class,"controllerMove").set(human,move);human.testMove=move;
        DataWatcher watcher=new DataWatcher(human);watcher.a(0,(byte)0);field(Entity.class,"datawatcher").set(human,watcher);
        AttributeMapServer attributes=new AttributeMapServer();attributes.b(GenericAttributes.MOVEMENT_SPEED).setValue(.7D);
        field(EntityLiving.class,"c").set(human,attributes);
        human.ticksLived=100;human.onGround=true;human.locX=9D;human.locY=20D;human.locZ=30D;
        human.motX=.35D;human.motY=.12D;human.motZ=-.27D;
        return human;
    }
    private static void frameRules()throws Exception {
        PitControlFrame frame=new PitControlFrame();
        check(frame.submit(4,900F,200F,2F,3F,true,true),"Finite frame rejected");
        equal(-180F,frame.yaw(),"Wrapped yaw");equal(90F,frame.pitch(),"Clamped pitch");
        equal(1D,Math.sqrt(frame.strafe()*frame.strafe()+frame.forward()*frame.forward()),"Normalized input");
        check(frame.sprint(),"Normalized forward-diagonal input lost sprint");
        check(frame.consume(4),"Same-tick input missed");check(!frame.consume(4),"Frame replayed");
        frame.submit(4,0F,0F,-1F,1F,true,false);check(frame.sprint(),"Left forward-diagonal input lost sprint");
        frame.submit(4,0F,0F,1F,0F,true,false);check(!frame.sprint(),"Pure strafe input sprinted");
        frame.submit(4,0F,0F,1F,.5F,true,false);check(!frame.sprint(),"Side-dominant input sprinted");
        frame.submit(4,10F,20F,0F,1F,true,false);frame.submit(4,30F,40F,0F,-1F,true,true);
        check(frame.consume(5),"Following tick input missed");equal(30F,frame.yaw(),"Newest input must replace previous frame");check(!frame.sprint(),"Backward input sprinted");
        frame.submit(4,0F,0F,0F,1F,false,false);check(!frame.consume(6),"Stale input accepted");check(!frame.consume(5),"Rejected stale input revived");
        frame.submit(4,0F,0F,0F,1F,false,false);check(!frame.consume(3),"Future tick accepted");
        frame.submit(Integer.MAX_VALUE,0F,0F,0F,1F,false,false);check(frame.consume(Integer.MIN_VALUE),"Tick rollover rejected next tick");
        for(int input=0;input<4;input++)for(float invalid:new float[]{Float.NaN,Float.POSITIVE_INFINITY,Float.NEGATIVE_INFINITY}) {
            float[] values={0F,0F,0F,1F};values[input]=invalid;
            frame.submit(8,0F,0F,0F,1F,true,true);
            check(!frame.submit(8,values[0],values[1],values[2],values[3],true,true),"Nonfinite input accepted");
            check(!frame.consume(8),"Invalid replacement retained old input");
        }
        frame.submit(8,0F,0F,1F,1F,true,true);frame.clear();check(!frame.consume(8),"Explicitly cleared frame replayed");
        for(Field member:PitControlFrame.class.getDeclaredFields())check(member.getType().isPrimitive(),"Frame retains a reference: "+member.getName());
    }
    private static void nativeTravel()throws Exception {
        TestHuman human=human(true);
        AttributeInstance speed=human.getAttributeInstance(GenericAttributes.MOVEMENT_SPEED);
        UUID boostId=UUID.fromString("06c30c00-67f8-4e35-9e46-a6fb50198700");
        speed.b(new AttributeModifier(boostId,"fixture potion speed",.4D,2));
        check(human.setPitControl(450F,-200F,0F,1F,true,true),"Marked native input rejected");
        equal(90F,human.yaw,"Immediate yaw");equal(-90F,human.pitch,"Immediate pitch");
        check(step(human),"Native frame not consumed");check(human.travels==1&&human.jumps==1,"Native travel/jump dispatch count");
        equal(.98F,human.lastForward,"Native input damping");equal(0F,human.lastStrafe,"Unexpected strafe");
        equal(.1D,speed.b(),"Navigation base leaked into input movement");equal(.1D*1.4D*1.3D,human.speedAtTravel,"Native sprint/potion modifiers lost");
        check(speed.a(boostId)!=null,"Existing speed modifier removed");check(human.isSprinting(),"Sprint metadata disappeared before native tracking");
        equal(90F,human.aI,"Body yaw");equal(90F,human.aJ,"Previous body yaw");equal(90F,human.aK,"Head yaw");
        check(human.aZ==0F&&human.ba==0F&&!human.jumpHeld(),"Held keys survived native travel");
        check(!step(human)&&human.travels==1,"Native physics ran twice for one frame");
        check(!human.isSprinting()&&human.jumpLatch()==0,"Missing next input retained sprint/jump state");
        TestHuman duplicateAge=human(true);
        check(duplicateAge.setPitControl(0F,0F,0F,1F,true,false),"Server-clock frame rejected");
        duplicateAge.ticksLived+=10;
        check(step(duplicateAge)&&duplicateAge.travels==1,"Entity age invalidated fresh same-server-tick input");
        check(duplicateAge.setPitControl(0F,0F,0F,1F,true,false),"Next-server-tick frame rejected");
        duplicateAge.ticksLived+=10;MinecraftServer.currentTick++;
        check(step(duplicateAge)&&duplicateAge.travels==2,"Duplicate entity ticks invalidated one-server-tick input");
        TestHuman diagonal=human(true);
        check(diagonal.setPitControl(0F,0F,1F,1F,true,false)&&step(diagonal),"Diagonal native input rejected");
        equal(.98D/Math.sqrt(2D),diagonal.lastStrafe,"Diagonal strafe damping");equal(.98D/Math.sqrt(2D),diagonal.lastForward,"Diagonal forward damping");
        check(diagonal.isSprinting()&&diagonal.travels==1,"Diagonal frame did not sprint through one native travel");
        check(diagonal.setPitControl(0F,0F,1F,0F,true,false)&&step(diagonal)&&!diagonal.isSprinting(),"Pure native strafe sprinted");
        check(diagonal.setPitControl(0F,0F,0F,-1F,true,false)&&step(diagonal)&&!diagonal.isSprinting(),"Native backward input sprinted");
    }
    private static void cancellations()throws Exception {
        TestHuman human=human(true);
        check(human.setPitControl(10F,0F,0F,1F,true,true),"Initial control rejected");check(step(human),"Initial jumping frame not consumed");
        human.clearPitControl();check(!human.isSprinting()&&!human.jumpHeld()&&human.jumpLatch()==0,"Explicit cancel did not release owned keys");
        equal(.35D,human.motX,"Cancel erased knockback X");equal(.12D,human.motY,"Cancel erased knockback Y");equal(-.27D,human.motZ,"Cancel erased knockback Z");
        equal(9D,human.locX,"Cancel teleported X");equal(20D,human.locY,"Cancel teleported Y");equal(30D,human.locZ,"Cancel teleported Z");
        human.ticksLived++;MinecraftServer.currentTick++;human.setPitControl(20F,0F,0F,1F,true,true);check(step(human)&&human.jumps==2,"Released jump latch blocked next press");
        human.setPitControl(30F,0F,0F,1F,true,false);human.ticksLived+=2;MinecraftServer.currentTick+=2;
        check(!step(human)&&!human.isSprinting(),"Stale frame retained owned sprint");
        human.setPitControl(40F,0F,0F,1F,true,false);
        check(!human.setPitControl(Float.NaN,0F,0F,1F,true,false),"Invalid native input accepted");check(!step(human)&&!human.isSprinting(),"Invalid native input retained prior frame");
        TestHuman offthread=human(true);offthread.setPitControl(55F,0F,0F,1F,true,false);
        AtomicBoolean result=new AtomicBoolean(true);Thread worker=new Thread(()->result.set(offthread.setPitControl(88F,0F,0F,0F,false,false)),"pit-input-fixture");
        worker.start();worker.join(3000L);check(!worker.isAlive()&&!result.get(),"Off-thread setter accepted control");
        equal(55F,offthread.yaw,"Rejected off-thread call changed rotation");check(offthread.isSprinting()&&step(offthread),"Off-thread rejection erased main-thread frame");
    }
    private static void eligibilityAndLegacy()throws Exception {
        for(int mode=0;mode<7;mode++) {
            TestHuman human=human(true);human.setPitControl(10F,0F,0F,1F,true,true);
            switch(mode) {
                case 0:human.dead=true;break;
                case 1:human.vehicle=allocate(EntityArrow.class);break;
                case 2:human.testNPC.spawned=false;break;
                case 3:human.testNPC.metadata.set("pitsim-combat-bot",false);break;
                case 4:human.testNPC.flyable=true;break;
                case 5:human.testNPC.navigating=true;break;
                default:human.testMove.active=true;break;
            }
            check(!step(human)&&human.travels==0,"Ineligible frame travelled, mode "+mode);
            check(!human.isSprinting()&&!human.jumpHeld(),"Ineligible frame retained owned keys, mode "+mode);
            check(!human.setPitControl(99F,0F,0F,1F,true,true),"Ineligible setter accepted, mode "+mode);
        }
        TestHuman ordinary=human(false);ordinary.aZ=.4F;ordinary.ba=.5F;ordinary.i(true);ordinary.setSprinting(true);ordinary.setJumpLatch(7);
        check(!ordinary.setPitControl(90F,0F,0F,1F,false,false),"Ordinary NPC accepted Pit input");ordinary.clearPitControl();check(!step(ordinary),"Ordinary NPC consumed Pit input");
        equal(.4F,ordinary.aZ,"Ordinary strafe changed");equal(.5F,ordinary.ba,"Ordinary forward changed");
        check(ordinary.jumpHeld()&&ordinary.isSprinting()&&ordinary.jumpLatch()==7,"Ordinary NPC keys were cleared");
    }
    public static void main(String[] args)throws Exception {
        unsafe=(Unsafe)field(Unsafe.class,"theUnsafe").get(null);
        CraftServer server=allocate(CraftServer.class);field(Bukkit.class,"server").set(null,server);
        DedicatedServer console=allocate(DedicatedServer.class);field(MinecraftServer.class,"primaryThread").set(console,Thread.currentThread());field(CraftServer.class,"console").set(server,console);
        field(CraftServer.class,"logger").set(server,Logger.getLogger("pit-control-fixture"));
        DispenserRegistry.c();consume=EntityHumanNPC.class.getDeclaredMethod("consumePitControl");consume.setAccessible(true);
        int priorTick=MinecraftServer.currentTick;
        try{MinecraftServer.currentTick=100;frameRules();nativeTravel();cancellations();eligibilityAndLegacy();}
        finally{MinecraftServer.currentTick=priorTick;}
        System.out.println("PIT_CONTROL_PASS: "+checks+" checks; one native travel/jump dispatch, bounded finite input, native modifiers, cancellation/knockback, eligibility and untouched ordinary NPCs; no full world tick claim");
    }
    public static final class TestHuman extends EntityHumanNPC {
        TestNPC testNPC;TestMove testMove;int travels,jumps;float lastStrafe,lastForward;double speedAtTravel;
        private TestHuman(){super(null,null,null,null,null);}
        @Override public void g(float strafe,float forward){travels++;lastStrafe=strafe;lastForward=forward;speedAtTravel=getAttributeInstance(GenericAttributes.MOVEMENT_SPEED).getValue();}
        @Override public void bF(){jumps++;}
        boolean jumpHeld(){return aY;}
        int jumpLatch(){try{return field(EntityHumanNPC.class,"jumpTicks").getInt(this);}catch(Exception failure){throw new AssertionError(failure);}}
        void setJumpLatch(int value){try{field(EntityHumanNPC.class,"jumpTicks").setInt(this,value);}catch(Exception failure){throw new AssertionError(failure);}}
    }
    public static final class TestNPC extends CitizensNPC {
        SimpleMetadataStore metadata;Navigator navigator;boolean spawned,flyable,navigating;
        private TestNPC(){super(null,0,"fixture",null,null);}
        @Override public MetadataStore data(){return metadata;}
        @Override public Navigator getNavigator(){return navigator;}
        @Override public boolean isSpawned(){return spawned;}
        @Override public boolean isFlyable(){return flyable;}
        @Override public boolean isProtected(){return true;}
    }
    public static final class TestMove extends PlayerControllerMove {
        boolean active;
        private TestMove(){super(null);}
        @Override public boolean a(){return active;}
    }
}
