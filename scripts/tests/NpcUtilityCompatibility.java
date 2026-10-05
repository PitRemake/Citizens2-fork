import net.citizensnpcs.util.Util;
import org.bukkit.Material;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import java.lang.reflect.Proxy;

public class NpcUtilityCompatibility {
    private static void check(boolean result,String message){if(!result)throw new AssertionError(message);}
    private static java.lang.reflect.Field field(Class<?> type,String name)throws Exception {
        java.lang.reflect.Field field=type.getDeclaredField(name);field.setAccessible(true);return field;
    }
    static void bootstrap()throws Exception {
        sun.misc.Unsafe unsafe=(sun.misc.Unsafe)field(sun.misc.Unsafe.class,"theUnsafe").get(null);
        net.minecraft.server.v1_8_R3.DispenserRegistry.c();
        org.bukkit.craftbukkit.v1_8_R3.CraftServer server=(org.bukkit.craftbukkit.v1_8_R3.CraftServer)unsafe.allocateInstance(org.bukkit.craftbukkit.v1_8_R3.CraftServer.class);
        org.bukkit.plugin.PluginManager plugins=(org.bukkit.plugin.PluginManager)Proxy.newProxyInstance(
                Player.class.getClassLoader(),new Class<?>[]{org.bukkit.plugin.PluginManager.class},(p,m,a)-> {
                    if(m.getReturnType()==java.util.Set.class)return java.util.Collections.emptySet();
                    if(m.getReturnType()==boolean.class)return false;
                    if(m.getReturnType()==int.class)return 0;
                    return null;
                });
        field(org.bukkit.craftbukkit.v1_8_R3.CraftServer.class,"pluginManager").set(server,plugins);
        field(org.bukkit.craftbukkit.v1_8_R3.CraftServer.class,"logger").set(server,java.util.logging.Logger.getLogger("citizens-utility-test"));
        net.minecraft.server.v1_8_R3.MinecraftServer console=(net.minecraft.server.v1_8_R3.MinecraftServer)unsafe.allocateInstance(net.minecraft.server.v1_8_R3.DedicatedServer.class);
        field(net.minecraft.server.v1_8_R3.MinecraftServer.class,"primaryThread").set(console,Thread.currentThread());
        field(org.bukkit.craftbukkit.v1_8_R3.CraftServer.class,"console").set(server,console);
        field(org.bukkit.craftbukkit.v1_8_R3.CraftServer.class,"scoreboardManager").set(server,
                new org.bukkit.craftbukkit.v1_8_R3.scoreboard.CraftScoreboardManager(console,new net.minecraft.server.v1_8_R3.Scoreboard()));
        field(org.bukkit.Bukkit.class,"server").set(null,server);
    }
    public static void main(String[] args)throws Exception {
        bootstrap();
        for(EntityType type:EntityType.values()) {
            boolean expected=type==EntityType.BAT || type==EntityType.BLAZE || type==EntityType.ENDER_DRAGON ||
                    type==EntityType.GHAST || type==EntityType.WITHER;
            check(Util.isAlwaysFlyable(type)==expected,"Flight classification changed for "+type);
        }
        check(!Util.isOffHand(new PlayerInteractEvent(null,Action.LEFT_CLICK_AIR,null,null,null)),"1.8 has no off hand");
        check(!Util.isOffHand(new PlayerInteractEntityEvent(null,null)),"1.8 entity interaction has no off hand");
        ItemStack held=new ItemStack(Material.STICK);
        PlayerInventory inventory=(PlayerInventory)Proxy.newProxyInstance(PlayerInventory.class.getClassLoader(),new Class<?>[]{PlayerInventory.class},
                (p,m,a)->{if(m.getName().equals("getItemInHand"))return held;throw new AssertionError(m.getName());});
        sun.misc.Unsafe unsafe=(sun.misc.Unsafe)field(sun.misc.Unsafe.class,"theUnsafe").get(null);
        TestPlayer player=(TestPlayer)unsafe.allocateInstance(TestPlayer.class);player.inventory=inventory;
        check(Util.matchesItemInHand(player,"STICK"),"Named material match failed");
        check(Util.matchesItemInHand(player,"280"),"Legacy material match failed");
        check(!Util.matchesItemInHand(player,"340"),"Wrong legacy material accepted");
        check(Util.matchesItemInHand(player,"*"),"Wildcard match failed");
        System.out.println("Citizens utility compatibility: all entity types, 1.8 interactions and material matching passed");
    }
    private static final class TestPlayer extends org.bukkit.craftbukkit.v1_8_R3.entity.CraftPlayer {
        PlayerInventory inventory;
        private TestPlayer(){super(null,null);}
        @Override public PlayerInventory getInventory(){return inventory;}
    }
}
