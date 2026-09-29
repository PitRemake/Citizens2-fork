import java.lang.reflect.*;
import java.util.*;
import net.citizensnpcs.api.npc.*;
import net.citizensnpcs.npc.CitizensNPC;
import net.citizensnpcs.nms.v1_8_R3.entity.EntityHumanNPC;
import net.minecraft.server.v1_8_R3.*;
import sun.misc.Unsafe;

/** Executes the packaged bot update path against actual WindSpigot equipment. */
public final class NpcBotOptimizationCompatibility {
    private static Unsafe unsafe;
    static void check(boolean value,String label){if(!value)throw new AssertionError(label);}
    static Field field(Class<?> type,String name)throws Exception{Field f=type.getDeclaredField(name);f.setAccessible(true);return f;}
    static <T>T allocate(Class<T> type)throws Exception{return type.cast(unsafe.allocateInstance(type));}
    static void update(EntityHumanNPC npc, Method method)throws Exception{
        field(EntityHumanNPC.class,"updateCounter").set(npc,100000);method.invoke(npc,false);
    }
    public static void main(String[] args)throws Exception {
        unsafe=(Unsafe)field(Unsafe.class,"theUnsafe").get(null);DispenserRegistry.c();
        Method scan=EntityHumanNPC.class.getDeclaredMethod("shouldScanItems",boolean.class,int.class,int.class,int.class);scan.setAccessible(true);
        int scans=0;Set<Integer> phases=new HashSet<>();
        for(int id=0;id<4;id++)for(int tick=0;tick<100;tick++){
            check((Boolean)scan.invoke(null,false,4,tick,id),"Ordinary NPC scan rate changed");
            check((Boolean)scan.invoke(null,true,0,tick,id),"Interval lower bound failed");
            if((Boolean)scan.invoke(null,true,4,tick,id)){scans++;if(tick<4)phases.add(tick);}
        }
        check(scans==100 && phases.size()==4,"Scans must stagger at 25% of baseline");
        EntityHumanNPC entity=allocate(EntityHumanNPC.class);CitizensNPC npc=allocate(CitizensNPC.class);
        SimpleMetadataStore data=new SimpleMetadataStore();data.set("pitsim-combat-bot",true);data.set(net.citizensnpcs.api.npc.NPC.Metadata.PACKET_UPDATE_DELAY,0);
        field(AbstractNPC.class,"metadata").set(npc,data);field(EntityHumanNPC.class,"npc").set(entity,npc);
        field(EntityHumanNPC.class,"pitEquipment").set(entity,new ItemStack[5]);
        entity.inventory=new PlayerInventory(entity);WorldServer world=allocate(WorldServer.class);
        Tracker tracker=allocate(Tracker.class);tracker.sent=new ArrayList<>();world.tracker=tracker;entity.world=world;
        Method update=EntityHumanNPC.class.getDeclaredMethod("updatePackets",boolean.class);update.setAccessible(true);
        update(entity,update);check(tracker.sent.isEmpty(),"Empty equipment must not send updates");
        entity.inventory.items[0]=new ItemStack(Items.DIAMOND_SWORD);entity.inventory.armor[3]=new ItemStack(Items.DIAMOND_HELMET);
        update(entity,update);check(tracker.sent.size()==2,"Hand and helmet should send exactly two changed slots");
        Set<Integer> slots=new HashSet<>();for(Packet<?> packet:tracker.sent)slots.add(field(PacketPlayOutEntityEquipment.class,"b").getInt(packet));
        check(slots.equals(new HashSet<>(Arrays.asList(0,4))),"Helmet or hand slot missing");
        tracker.sent.clear();update(entity,update);check(tracker.sent.isEmpty(),"Unchanged equipment was resent");
        NBTTagCompound tag=new NBTTagCompound();tag.setString("owner","one");entity.inventory.items[0].setTag(tag);
        update(entity,update);check(tracker.sent.size()==1,"NBT mutation should send only the changed hand");
        tracker.sent.clear();tag.setString("owner","two");update(entity,update);
        check(tracker.sent.size()==1,"In-place NBT changes must not alias the snapshot");
        tracker.sent.clear();entity.inventory.armor[3]=null;update(entity,update);
        check(tracker.sent.size()==1 && field(PacketPlayOutEntityEquipment.class,"b").getInt(tracker.sent.get(0))==4,"Removing helmet must clear it for observers");
        System.out.println("BOT_OPTIMIZATION_PASS: item scans 100/400; changed hand/helmet/NBT/removal packets only; unchanged equipment 0 packets");
    }
    public static final class Tracker extends EntityTracker {
        List<Packet<?>> sent;
        private Tracker(){super(null);}
        @Override public void a(Entity entity,Packet<?> packet){sent.add(packet);}
    }
}

