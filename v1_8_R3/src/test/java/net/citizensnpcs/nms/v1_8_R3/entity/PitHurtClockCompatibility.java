package net.citizensnpcs.nms.v1_8_R3.entity;

import java.lang.reflect.*;
import java.util.*;
import java.util.logging.Logger;
import net.citizensnpcs.api.ai.Navigator;
import net.citizensnpcs.api.npc.MetadataStore;
import net.citizensnpcs.api.npc.SimpleMetadataStore;
import net.citizensnpcs.npc.CitizensNPC;
import net.citizensnpcs.nms.v1_8_R3.util.NMSImpl;
import net.minecraft.server.v1_8_R3.*;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.v1_8_R3.CraftServer;
import org.bukkit.craftbukkit.v1_8_R3.entity.CraftPlayer;
import org.bukkit.command.SimpleCommandMap;
import org.bukkit.plugin.SimplePluginManager;
import sun.misc.Unsafe;

/** Actual World.g, Citizens NMS.tick, EntityPlayer.t_ and native damage admission.
 * Only health application, unrelated world/network side effects and NPC AI are fixtures.
 */
public final class PitHurtClockCompatibility {
    private static Unsafe unsafe;
    private static TestServer console;
    private static NMSImpl nms;
    private static Method hurtTickGetter;
    private static int checks;
    private static Field field(Class<?> type, String name) throws Exception {
        Field value = type.getDeclaredField(name); value.setAccessible(true); return value;
    }
    private static <T> T allocate(Class<T> type) throws Exception { return type.cast(unsafe.allocateInstance(type)); }
    private static void check(boolean value, String label) { checks++; if(!value) throw new AssertionError(label); }
    private static void equal(int expected, int actual, String label) { check(expected == actual, label + ": " + actual); }
    private static void equal(float expected, float actual, String label) { check(expected == actual, label + ": " + actual); }
    private static boolean hit(TestHuman human, float amount) { return human.damageEntity(DamageSource.GENERIC, amount); }
    private static int hurtTick(TestHuman human) throws Exception {
        check(hurtTickGetter != null, "Native maintenance counter API is missing");
        return (Integer)hurtTickGetter.invoke(human);
    }
    private static Fixture fixture(boolean marked) throws Exception {
        Fixture fixture = new Fixture();
        fixture.human = allocate(TestHuman.class);
        fixture.npc = allocate(TestNPC.class);
        fixture.npc.metadata = new SimpleMetadataStore();
        fixture.npc.metadata.set("pitsim-combat-bot", marked);
        fixture.npc.metadata.set("removefromplayerlist", true);
        fixture.npc.metadata.set("packet-update-delay", Integer.MAX_VALUE);
        fixture.npc.navigator = (Navigator)Proxy.newProxyInstance(Navigator.class.getClassLoader(), new Class[]{Navigator.class},
                (proxy, method, args) -> method.getReturnType() == boolean.class ? false : null);
        field(EntityHumanNPC.class, "npc").set(fixture.human, fixture.npc);
        fixture.human.applied = new ArrayList<>(); fixture.human.accept = true;
        fixture.human.abilities = new PlayerAbilities();
        fixture.human.maxNoDamageTicks = 20;
        field(Entity.class, "defaultActivationState").setBoolean(fixture.human, true); fixture.human.ad = true;
        fixture.human.locY = 32D; fixture.human.af = 2;
        fixture.human.world = fixture.world = allocate(TestWorld.class);
        fixture.world.chunk = allocate(Chunk.class); field(Chunk.class, "neighbors").setInt(fixture.world.chunk, -1);
        field(World.class, "players").set(fixture.world, new ArrayList<EntityHuman>());
        field(World.class, "methodProfiler").set(fixture.world, new MethodProfiler());
        Field config = field(World.class, "paperSpigotConfig"); config.set(fixture.world, allocate(config.getType()));
        field(Entity.class, "random").set(fixture.human, new Random(1L));
        DataWatcher watcher = new DataWatcher(fixture.human); watcher.a(0, (byte)0); watcher.a(6, 20F);
        field(Entity.class, "datawatcher").set(fixture.human, watcher);
        field(EntityPlayer.class, "server").set(fixture.human, console);
        field(EntityPlayer.class, "chunkCoordIntPairQueue").set(fixture.human, new ArrayList<ChunkCoordIntPair>());
        fixture.interaction = new TestInteraction(); field(EntityPlayer.class, "playerInteractManager").set(fixture.human, fixture.interaction);
        fixture.human.activeContainer = fixture.container = new TestContainer();
        fixture.bukkit = allocate(TestPlayer.class); fixture.bukkit.handle = fixture.human;
        fixture.bukkit.id = UUID.randomUUID(); fixture.human.bukkit = fixture.bukkit;
        fixture.npc.human = fixture.human;
        return fixture;
    }
    private static void both(Fixture fixture) {
        check(!nms.tick(fixture.bukkit), "Marked ticker unexpectedly removed its entry");
        fixture.world.g(fixture.human);
    }
    private static void tenActualTicks() throws Exception {
        MinecraftServer.currentTick = 100;
        Fixture f = fixture(true);
        both(f);
        equal(2, f.npc.updates, "Citizens update was lost on a duplicate NPC path");
        equal(2, f.human.ticksLived, "World entity age ownership changed");
        check(hit(f.human, 7F), "Initial full hit rejected (hp=" + f.human.getHealth() + ",timer=" + f.human.noDamageTicks + ",invul=" + f.human.invulnerableTicks + ",applied=" + f.human.applied + ")");
        both(f);
        equal(20, f.human.noDamageTicks, "Fresh hit aged during same-server-tick duplicates");
        equal(1, hurtTick(f.human), "Duplicate NPC paths advanced the receipt clock");
        equal(1, f.interaction.steps, "Two NPC paths repeated native interaction maintenance");
        equal(1, f.container.steps, "Two NPC paths repeated native container maintenance");
        for(int tick = 101; tick < 110; tick++) {
            MinecraftServer.currentTick = tick; both(f);
            equal(120 - tick, f.human.noDamageTicks, "NPC hurt timer aged faster than the server clock");
            check(!hit(f.human, 7F) && !hit(f.human, 5F), "Equal/weaker hit admitted before ten actual ticks");
        }
        MinecraftServer.currentTick = 110; both(f);
        equal(10, f.human.noDamageTicks, "Native half-window boundary did not arrive at actual tick ten");
        check(hit(f.human, 7F), "Equal full hit rejected at native ten-tick boundary");
        both(f); equal(20, f.human.noDamageTicks, "Boundary fresh hit aged on a duplicate path");
        check(f.human.applied.equals(Arrays.asList(7F, 7F)), "Equal-hit application changed: " + f.human.applied);
        equal(11, f.interaction.steps, "Native maintenance count does not match actual server ticks");
        equal(11, hurtTick(f.human), "Native receipt count diverged from actual maintenance entries");
    }
    private static void strongerFreshAndCancelled() throws Exception {
        MinecraftServer.currentTick = 200; Fixture f = fixture(true); both(f);
        check(hit(f.human, 7F), "Initial stronger fixture hit rejected");
        check(hit(f.human, 9F), "Stronger native hit rejected");
        check(f.human.applied.equals(Arrays.asList(7F, 2F)), "Stronger hit did not apply only its difference");
        both(f); equal(20, f.human.noDamageTicks, "Stronger hit timer moved in same server tick");
        equal(9F, f.human.lastDamage, "Clock guard changed native lastDamage");
        f.human.accept = false;
        check(!hit(f.human, 11F), "Cancelled native damage accepted");
        equal(9F, f.human.lastDamage, "Cancelled damage changed lastDamage");
        equal(20, f.human.noDamageTicks, "Cancelled damage changed immunity");
        f.human.accept = true;
        f.human.noDamageTicks = 0;
        both(f); equal(0, f.human.noDamageTicks, "Explicit clear was reconstructed by duplicate tick");
        check(hit(f.human, 4F), "Fresh hit between duplicate paths rejected");
        both(f); equal(20, f.human.noDamageTicks, "Fresh reset between duplicate paths was decremented");
        equal(4F, f.human.lastDamage, "Fresh reset lastDamage changed");
        MinecraftServer.currentTick++; both(f); equal(19, f.human.noDamageTicks, "Fresh reset failed to age on next tick");
        for(int value : new int[]{Integer.MIN_VALUE, -1, 0, 1, Integer.MAX_VALUE}) {
            Fixture edge = fixture(true); edge.human.noDamageTicks = value;
            MinecraftServer.currentTick++; both(edge);
            equal(value > 0 ? value - 1 : value, edge.human.noDamageTicks, "Native integer timer semantics changed");
            both(edge); equal(value > 0 ? value - 1 : value, edge.human.noDamageTicks, "Duplicate integer timer aged");
        }
    }
    private static void callbacksAndReentry() throws Exception {
        MinecraftServer.currentTick = 300; Fixture f = fixture(true);
        f.human.noDamageTicks = 11; f.human.lastDamage = 7F;
        f.interaction.callback = () -> {
            equal(11, f.human.noDamageTicks, "Interaction callback saw a synthetic timer");
            check(!hit(f.human, 7F), "Interaction callback accepted early equal hit");
            f.human.t_();
            equal(11, f.human.noDamageTicks, "Reentrant callback advanced native immunity");
        };
        f.container.callback = () -> {
            equal(10, f.human.noDamageTicks, "Container callback saw wrong native boundary");
            check(hit(f.human, 7F), "Container callback lost legitimate boundary full damage");
            f.human.t_(); equal(20, f.human.noDamageTicks, "Reentry aged new container hit");
        };
        both(f);
        equal(1, f.interaction.steps, "Reentrant interaction repeated native work");
        equal(1, f.container.steps, "Reentrant container repeated native work");
        equal(20, f.human.noDamageTicks, "Fresh callback hit aged on the second NPC path");
        equal(4, f.npc.updates, "NPC updates were lost during native/reentrant calls");
        Fixture fresh = fixture(true); MinecraftServer.currentTick++;
        fresh.interaction.callback = () -> { check(hit(fresh.human, 7F), "Fresh interaction callback hit rejected"); };
        both(fresh); equal(19, fresh.human.noDamageTicks, "First native tick no longer ages pre-decrement interaction hit");
        fresh.interaction.callback = null; fresh.container.callback = null;
        MinecraftServer.currentTick++;
        fresh.human.noDamageTicks = 11; fresh.human.lastDamage = 7F;
        fresh.npc.callback = () -> {
            equal(10, fresh.human.noDamageTicks, "NPC update saw a transiently changed immunity timer");
        };
        both(fresh); equal(10, fresh.human.noDamageTicks, "Duplicate update lost true timer boundary");
    }
    private static void exceptionsAndLegacy() throws Exception {
        MinecraftServer.currentTick = 400; Fixture before = fixture(true); before.human.noDamageTicks = 20;
        before.interaction.callback = () -> { throw new Stop(); };
        try { before.human.t_(); throw new AssertionError("Interaction exception swallowed"); } catch(Stop expected) { }
        before.human.t_(); equal(1, before.interaction.steps, "Failed native attempt was repeated in same tick");
        equal(1, hurtTick(before.human), "Failed native entry did not stamp its receipt clock");
        equal(20, before.human.noDamageTicks, "Exception before native timer changed immunity");
        MinecraftServer.currentTick++; before.interaction.callback = null; both(before);
        equal(19, before.human.noDamageTicks, "Native step did not resume next actual tick");
        Fixture after = fixture(true); after.human.noDamageTicks = 20; MinecraftServer.currentTick++;
        after.container.callback = () -> { throw new Stop(); };
        try { after.human.t_(); throw new AssertionError("Container exception swallowed"); } catch(Stop expected) { }
        after.human.t_(); equal(19, after.human.noDamageTicks, "Exception after native timer allowed double decrement");
        equal(1, hurtTick(after.human), "Failed container entry replayed receipt clock");
        equal(1, after.container.steps, "Failed container work repeated in same tick");
        Fixture ordinary = fixture(false); ordinary.human.noDamageTicks = 20; MinecraftServer.currentTick++;
        both(ordinary); equal(18, ordinary.human.noDamageTicks, "Unmarked Citizens native behavior changed");
        equal(2, hurtTick(ordinary.human), "Unmarked native entries were not counted");
        equal(2, ordinary.interaction.steps, "Unmarked maintenance was gated"); equal(2, ordinary.npc.updates, "Unmarked NPC update changed");
        ordinary.npc.metadata.set("pitsim-combat-bot", true); both(ordinary);
        equal(17, ordinary.human.noDamageTicks, "Newly marked NPC skipped its first native step");
        ordinary.npc.metadata.set("pitsim-combat-bot", false); ordinary.human.t_(); equal(16, ordinary.human.noDamageTicks, "Removing marker retained gate");
        ordinary.npc.metadata.set("pitsim-combat-bot", true); both(ordinary); equal(15, ordinary.human.noDamageTicks, "Re-marking did not reinitialize native gate");
        equal(5, hurtTick(ordinary.human), "Marker transitions lost actual native entry count");
        Fixture noNPC = fixture(true); field(EntityHumanNPC.class, "npc").set(noNPC.human, null); noNPC.human.noDamageTicks = 20;
        noNPC.human.t_(); noNPC.human.t_(); equal(18, noNPC.human.noDamageTicks, "Null NPC vanilla native behavior changed");
        equal(2, hurtTick(noNPC.human), "Null NPC native entries were not counted");
        equal(0, noNPC.npc.updates, "Null NPC dispatched Citizens update");
    }
    private static void clockEdges() throws Exception {
        Fixture f = fixture(true); f.human.noDamageTicks = 20;
        for(int tick : new int[]{0, 1, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE + 1, 0, -1}) {
            MinecraftServer.currentTick = tick; int before = f.human.noDamageTicks;
            both(f); equal(before - 1, f.human.noDamageTicks, "Clock zero/wrap/rewind lost exactly one native step");
            both(f); equal(before - 1, f.human.noDamageTicks, "Duplicate edge clock repeated native step");
        }
        Fixture replacement = fixture(true); replacement.human.noDamageTicks = 20;
        both(replacement); equal(19, replacement.human.noDamageTicks, "New entity inherited another entity's tick gate");
        equal(1, hurtTick(replacement.human), "New entity inherited another entity's receipt clock");
        Fixture counterWrap = fixture(true); counterWrap.human.noDamageTicks = 20;
        field(EntityHumanNPC.class, "pitHurtTick").setInt(counterWrap.human, Integer.MAX_VALUE);
        MinecraftServer.currentTick++; both(counterWrap);
        equal(Integer.MIN_VALUE, hurtTick(counterWrap.human), "Native receipt counter did not wrap normally");
        both(counterWrap); equal(Integer.MIN_VALUE, hurtTick(counterWrap.human), "Duplicate entry advanced wrapped receipt counter");
        MinecraftServer.currentTick++; both(counterWrap);
        equal(Integer.MIN_VALUE + 1, hurtTick(counterWrap.human), "Wrapped counter did not resume one native entry per tick");
    }
    public static void main(String[] args) throws Exception {
        unsafe = (Unsafe)field(Unsafe.class, "theUnsafe").get(null);
        CraftServer bukkit = allocate(CraftServer.class); field(Bukkit.class, "server").set(null, bukkit);
        field(CraftServer.class, "pluginManager").set(bukkit, new SimplePluginManager(bukkit, new SimpleCommandMap(bukkit)));
        console = allocate(TestServer.class); field(MinecraftServer.class, "primaryThread").set(console, Thread.currentThread());
        field(CraftServer.class, "console").set(bukkit, console); field(CraftServer.class, "logger").set(bukkit, Logger.getLogger("hurt-clock-fixture"));
        field(CraftServer.class, "scoreboardManager").set(bukkit,
                new org.bukkit.craftbukkit.v1_8_R3.scoreboard.CraftScoreboardManager(console, new Scoreboard()));
        DispenserRegistry.c(); nms = allocate(NMSImpl.class);
        try { hurtTickGetter = EntityHumanNPC.class.getMethod("getPitHurtTick"); }
        catch(NoSuchMethodException baseline) { hurtTickGetter = null; }
        int previous = MinecraftServer.currentTick;
        try { tenActualTicks(); strongerFreshAndCancelled(); callbacksAndReentry(); exceptionsAndLegacy(); clockEdges(); }
        finally { MinecraftServer.currentTick = previous; }
        System.out.println("PIT_HURT_CLOCK_PASS: " + checks + " checks; actual World.g/Citizens NMS.tick dispatch and native player tick/damage admission, ten actual server ticks, stronger deltas, fresh/cancelled hits, callbacks/reentry/errors, legacy marker gating and clock edges; no live server scheduling claim");
    }
    private static final class Fixture {
        TestHuman human; TestNPC npc; TestWorld world; TestInteraction interaction; TestContainer container; TestPlayer bukkit;
    }
    public static final class TestHuman extends EntityHumanNPC {
        List<Float> applied; boolean accept; TestPlayer bukkit;
        private TestHuman() { super(null, null, null, null, null); }
        @Override public CraftPlayer getBukkitEntity() { return bukkit; }
        @Override public Entity C() { return this; }
        @Override public boolean isSpectator() { return false; }
        @Override public boolean isSleeping() { return false; }
        @Override public boolean isInvulnerable(DamageSource source) { return false; }
        @Override protected boolean d(DamageSource source, float damage) { if(accept) applied.add(damage); return accept; }
        @Override protected void ac() { }
        @Override public void makeSound(String sound, float volume, float pitch) { }
    }
    public static final class TestNPC extends CitizensNPC {
        SimpleMetadataStore metadata; Navigator navigator; TestHuman human; int updates; Runnable callback;
        private TestNPC() { super(null, 0, "fixture", null, null); }
        @Override public MetadataStore data() { return metadata; }
        @Override public Navigator getNavigator() { return navigator; }
        @Override public void update() { updates++; if(callback != null) callback.run(); }
        @Override public boolean isSpawned() { return true; }
        @Override public boolean isProtected() { return false; }
    }
    public static final class TestPlayer extends CraftPlayer {
        TestHuman handle; UUID id;
        private TestPlayer() { super(null, null); }
        @Override public EntityPlayer getHandle() { return handle; }
        @Override public UUID getUniqueId() { return id; }
        @Override public boolean isValid() { return true; }
        @Override public double getHealth() { return 20D; }
    }
    public static final class TestInteraction extends PlayerInteractManager {
        int steps; Runnable callback;
        TestInteraction() { super(null); }
        @Override public void a() { steps++; if(callback != null) callback.run(); }
    }
    public static final class TestContainer extends Container {
        int steps; Runnable callback;
        @Override public org.bukkit.inventory.InventoryView getBukkitView() { return null; }
        @Override public boolean a(EntityHuman player) { return true; }
        @Override public void b() { steps++; if(callback != null) callback.run(); }
    }
    public static final class TestWorld extends WorldServer {
        Chunk chunk;
        private TestWorld() { super(null, null, null, 0, null, org.bukkit.World.Environment.NORMAL, null); }
        @Override public void broadcastEntityEffect(Entity entity, byte effect) { }
        @Override public boolean isChunkLoaded(int x, int z, boolean empty) { return true; }
        @Override public Chunk getChunkIfLoaded(int x, int z) { return chunk; }
    }
    public static final class TestServer extends DedicatedServer {
        TestServer() { super(null, null); }
        @Override public boolean ae() { return false; }
    }
    private static final class Stop extends RuntimeException { }
}
