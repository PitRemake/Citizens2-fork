import java.lang.management.ManagementFactory;
import java.lang.reflect.*;
import java.util.*;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.npc.SimpleMetadataStore;
import net.citizensnpcs.api.trait.Trait;
import net.citizensnpcs.api.trait.trait.Equipment;
import net.citizensnpcs.api.trait.trait.Inventory;
import net.citizensnpcs.api.util.MemoryDataKey;
import net.minecraft.server.v1_8_R3.*;
import org.bukkit.Material;
import org.bukkit.craftbukkit.v1_8_R3.entity.CraftPlayer;
import org.bukkit.craftbukkit.v1_8_R3.inventory.CraftItemStack;
import org.bukkit.inventory.EntityEquipment;
import sun.misc.Unsafe;

/** Executes the packaged API trait against real Wind CraftItemStack/NBT behavior. */
public final class NpcEquipmentSnapshotCompatibility {
    private static Unsafe unsafe;
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    private static Field field(Class<?> type,String name)throws Exception{
        Field f=type.getDeclaredField(name);f.setAccessible(true);return f;
    }
    private static final class Fixture {
        final Equipment trait=new Equipment();
        final Inventory inventory=new Inventory();
        final SimpleMetadataStore data=new SimpleMetadataStore();
        final ItemStack[] live=new ItemStack[6];
        final TestPlayer player;
        boolean spawned=true;
        Fixture()throws Exception{
            data.set("pitsim-combat-bot",true);
            player=(TestPlayer)unsafe.allocateInstance(TestPlayer.class);
            player.equipment=(EntityEquipment)Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class[]{EntityEquipment.class},(p,m,a)->{
                String name=m.getName();
                int slot;
                switch(name){
                    case "getItemInHand":case "setItemInHand":slot=0;break;
                    case "getHelmet":case "setHelmet":slot=1;break;
                    case "getChestplate":case "setChestplate":slot=2;break;
                    case "getLeggings":case "setLeggings":slot=3;break;
                    case "getBoots":case "setBoots":slot=4;break;
                    default:throw new AssertionError("Unexpected equipment call "+name);
                }
                if(name.charAt(0)=='g')return CraftItemStack.asCraftMirror(live[slot]);
                live[slot]=CraftItemStack.asNMSCopy((org.bukkit.inventory.ItemStack)a[0]);return null;
            });
            NPC npc=(NPC)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{NPC.class},(p,m,a)->{
                if(m.getName().equals("getEntity"))return player;
                if(m.getName().equals("isSpawned"))return spawned;
                if(m.getName().equals("data"))return data;
                if(m.getName().equals("getOrAddTrait")&&a[0]==Inventory.class)return inventory;
                throw new AssertionError("Unexpected NPC call "+m.getName());
            });
            field(Trait.class,"npc").set(trait,npc);field(Trait.class,"npc").set(inventory,npc);
            Item[] types={Items.DIAMOND_SWORD,Items.DIAMOND_HELMET,Items.DIAMOND_CHESTPLATE,Items.DIAMOND_LEGGINGS,Items.DIAMOND_BOOTS};
            for(int i=0;i<5;i++){
                live[i]=new ItemStack(types[i]);NBTTagCompound tag=new NBTTagCompound();
                for(int n=0;n<16;n++)tag.setString("field"+n,"snapshot-payload-"+n);
                live[i].setTag(tag);
            }
        }
    }
    public static final class TestPlayer extends CraftPlayer {
        EntityEquipment equipment;int updates;
        private TestPlayer(){super(null,null);}
        @Override public EntityEquipment getEquipment(){return equipment;}
        @Override public void updateInventory(){updates++;}
    }
    public static void main(String[] args)throws Exception{
        unsafe=(Unsafe)field(Unsafe.class,"theUnsafe").get(null);NpcUtilityCompatibility.bootstrap();
        Fixture f=new Fixture();f.trait.run();
        org.bukkit.inventory.ItemStack[] snapshots=f.trait.getEquipment().clone();
        for(int i=0;i<5;i++)check(snapshots[i]!=null,"Missing equipment slot "+i);
        for(int tick=0;tick<1000;tick++){
            f.trait.run();
            for(int i=0;i<5;i++)check(f.trait.get(i)==snapshots[i],"Unchanged slot cloned "+i);
        }
        // In-place native mutations must be detected without identity-based shortcuts.
        for(int i=0;i<5;i++){
            f.live[i].getTag().setString("changed","slot"+i);f.trait.run();
            check(f.trait.get(i)!=snapshots[i],"In-place NBT missed in slot "+i);
            check(CraftItemStack.asNMSCopy(snapshots[i]).getTag().getString("changed").isEmpty(),"Old snapshot aliased live NBT");
            for(int j=i+1;j<5;j++)check(f.trait.get(j)==snapshots[j],"Unchanged neighboring slot cloned");
            snapshots[i]=f.trait.get(i);
        }
        f.live[0].count=3;f.trait.run();check(f.trait.get(0).getAmount()==3,"Count change lost");
        f.live[0].setData(7);f.trait.run();check(f.trait.get(0).getDurability()==7,"Damage change lost");
        f.live[0]=new ItemStack(Items.BOW);f.trait.run();check(f.trait.get(0).getType()==Material.BOW,"Sword/bow swap lost");

        org.bukkit.inventory.ItemStack exposed=f.trait.get(1);exposed.setDurability((short)19);
        check(f.live[1].getData()!=19,"Exposed snapshot changed inventory");f.trait.run();
        check(f.trait.get(1)!=exposed&&f.trait.get(1).getDurability()==f.live[1].getData(),"Exposed snapshot mutation not repaired");
        org.bukkit.inventory.ItemStack liveMirror=CraftItemStack.asCraftMirror(f.live[2]);
        f.trait.getEquipment()[2]=liveMirror;f.trait.run();
        check(f.trait.get(2)!=liveMirror,"Public array replacement retained an unsafe live mirror");
        org.bukkit.inventory.ItemStack detached=f.trait.get(2);f.live[2].getTag().setString("late","native-change");
        check(CraftItemStack.asNMSCopy(detached).getTag().getString("late").isEmpty(),"Replacement snapshot aliased live NBT");
        f.trait.run();check(f.trait.get(2)!=detached,"Subsequent native change lost");

        f.live[3]=null;f.trait.run();check(f.trait.get(3)==null,"Null did not clear slot");
        f.trait.getEquipment()[4]=new org.bukkit.inventory.ItemStack(Material.AIR);f.live[4]=null;f.trait.run();
        check(f.trait.get(4)==null,"AIR did not normalize to null");
        f.trait.set(Equipment.EquipmentSlot.OFF_HAND,new org.bukkit.inventory.ItemStack(Material.STICK,2));
        f.trait.run();check(f.trait.get(Equipment.EquipmentSlot.OFF_HAND).getAmount()==2,"Unsupported 1.8 offhand state lost");

        f.data.set("pitsim-combat-bot",false);f.trait.run();org.bukkit.inventory.ItemStack ordinary=f.trait.get(0);
        f.trait.run();check(f.trait.get(0)!=ordinary,"Ordinary NPC snapshot semantics changed");
        f.data.set("pitsim-combat-bot",true);f.trait.run();org.bukkit.inventory.ItemStack resumed=f.trait.get(0);
        f.trait.run();check(f.trait.get(0)==resumed,"Optimization did not resume after marker change");
        f.spawned=false;f.live[0]=new ItemStack(Items.STONE_SWORD);f.trait.run();
        check(f.trait.get(0)==resumed,"Despawned trait unexpectedly polled entity");
        f.spawned=true;f.trait.onSpawn();check(f.live[0].getItem()==Items.BOW,"Respawn did not restore saved hand");
        check(f.player.updates>0,"Original spawn/set inventory updates lost");
        f.trait.run();check(f.trait.get(0).getType()==Material.BOW,"Respawn snapshot incorrect");

        MemoryDataKey saved=new MemoryDataKey();f.trait.save(saved);Fixture loaded=new Fixture();
        loaded.trait.load(saved);loaded.trait.onSpawn();loaded.trait.run();
        for(int i=0;i<6;i++)check(Objects.equals(f.trait.get(i),loaded.trait.get(i)),"Load/save changed slot "+i);
        loaded.trait.set(Equipment.EquipmentSlot.HAND,new org.bukkit.inventory.ItemStack(Material.STONE_SWORD,2));
        loaded.trait.run();check(loaded.live[0].getItem()==Items.STONE_SWORD&&loaded.trait.get(0).getAmount()==2,"set hand behavior changed");

        com.sun.management.ThreadMXBean bean=(com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();
        if(bean.isThreadAllocatedMemorySupported()){
            bean.setThreadAllocatedMemoryEnabled(true);Fixture measured=new Fixture();
            long fast=allocated(bean,measured,true),normal=allocated(bean,measured,false);
            check(fast*2<normal,"Expected material allocation reduction: fast="+fast+" ordinary="+normal);
            System.out.println("Equipment snapshot allocations over 1000 polls: optimized="+fast+", ordinary="+normal);
        }
        System.out.println("EQUIPMENT_SNAPSHOT_PASS: stable clones avoided; native NBT/count/damage/type, all 1.8 slots, null/AIR, exposed mutations, markers, respawn, load/save verified");
    }
    private static long allocated(com.sun.management.ThreadMXBean bean,Fixture f,boolean marked){
        f.data.set("pitsim-combat-bot",marked);for(int i=0;i<300;i++)f.trait.run();
        long before=bean.getThreadAllocatedBytes(Thread.currentThread().getId());
        for(int i=0;i<1000;i++)f.trait.run();
        return bean.getThreadAllocatedBytes(Thread.currentThread().getId())-before;
    }
}
