package net.citizensnpcs.nms.v1_8_R3.entity;

import java.io.IOException;
import java.net.Socket;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.craftbukkit.v1_8_R3.CraftServer;
import org.bukkit.craftbukkit.v1_8_R3.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.bukkit.metadata.MetadataValue;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Vector;

import com.mojang.authlib.GameProfile;

import net.citizensnpcs.Settings.Setting;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.event.NPCEnderTeleportEvent;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.trait.trait.Inventory;
import net.citizensnpcs.api.util.SpigotUtil;
import net.citizensnpcs.nms.v1_8_R3.network.EmptyNetHandler;
import net.citizensnpcs.nms.v1_8_R3.network.EmptyNetworkManager;
import net.citizensnpcs.nms.v1_8_R3.util.NMSImpl;
import net.citizensnpcs.nms.v1_8_R3.util.PlayerControllerJump;
import net.citizensnpcs.nms.v1_8_R3.util.PlayerControllerMove;
import net.citizensnpcs.nms.v1_8_R3.util.PlayerNavigation;
import net.citizensnpcs.nms.v1_8_R3.util.PitControlFrame;
import net.citizensnpcs.nms.v1_8_R3.util.PlayerlistTrackerEntry;
import net.citizensnpcs.npc.CitizensNPC;
import net.citizensnpcs.npc.ai.NPCHolder;
import net.citizensnpcs.npc.skin.SkinPacketTracker;
import net.citizensnpcs.npc.skin.SkinnableEntity;
import net.citizensnpcs.trait.Gravity;
import net.citizensnpcs.trait.SkinTrait;
import net.citizensnpcs.util.EmptySocket;
import net.citizensnpcs.util.NMS;
import net.citizensnpcs.util.Util;
import net.minecraft.server.v1_8_R3.AttributeInstance;
import net.minecraft.server.v1_8_R3.AxisAlignedBB;
import net.minecraft.server.v1_8_R3.Block;
import net.minecraft.server.v1_8_R3.BlockPosition;
import net.minecraft.server.v1_8_R3.DamageSource;
import net.minecraft.server.v1_8_R3.DataWatcher;
import net.minecraft.server.v1_8_R3.Entity;
import net.minecraft.server.v1_8_R3.EntityPlayer;
import net.minecraft.server.v1_8_R3.EnumProtocolDirection;
import net.minecraft.server.v1_8_R3.GenericAttributes;
import net.minecraft.server.v1_8_R3.ItemStack;
import net.minecraft.server.v1_8_R3.MinecraftServer;
import net.minecraft.server.v1_8_R3.NavigationAbstract;
import net.minecraft.server.v1_8_R3.NetworkManager;
import net.minecraft.server.v1_8_R3.Packet;
import net.minecraft.server.v1_8_R3.PacketPlayOutEntityEquipment;
import net.minecraft.server.v1_8_R3.PlayerInteractManager;
import net.minecraft.server.v1_8_R3.WorldServer;
import net.minecraft.server.v1_8_R3.WorldSettings;

public class EntityHumanNPC extends EntityPlayer implements NPCHolder, SkinnableEntity {
    private PlayerControllerJump controllerJump;
    private PlayerControllerMove controllerMove;
    private final Map<Integer, ItemStack> equipmentCache = new HashMap<Integer, ItemStack>();
    private int jumpTicks = 0;
    private PlayerNavigation navigation;
    private final CitizensNPC npc;
    private final Location packetLocationCache = new Location(null, 0, 0, 0);
    private final SkinPacketTracker skinTracker;
    private PlayerlistTrackerEntry trackerEntry;
    private int updateCounter = 0;
    private int pickupCounter;
    private final ItemStack[] pitEquipment = new ItemStack[5];
    private final PitControlFrame pitControl = new PitControlFrame();
    private boolean pitControlActive;
    private boolean pitPlayerTicked;
    private int pitPlayerTick;
    private int pitHurtTick;

    public EntityHumanNPC(MinecraftServer minecraftServer, WorldServer world, GameProfile gameProfile,
            PlayerInteractManager playerInteractManager, NPC npc) {
        super(minecraftServer, world, gameProfile, playerInteractManager);

        this.npc = (CitizensNPC) npc;
        if (npc != null) {
            skinTracker = new SkinPacketTracker(this);
            playerInteractManager.setGameMode(WorldSettings.EnumGamemode.SURVIVAL);
            initialise(minecraftServer);
        } else {
            skinTracker = null;
        }
    }

    @Override
    protected void a(double d0, boolean flag, Block block, BlockPosition blockposition) {
        if (npc == null || !npc.isFlyable()) {
            super.a(d0, flag, block, blockposition);
        }
    }

    @Override
    public boolean a(EntityPlayer entityplayer) {
        if (npc != null && trackerEntry == null) {
            return false;
        }
        return super.a(entityplayer);
    }

    @Override
    public boolean ae() {
        return npc == null ? super.ae()
                : npc.data().has(NPC.COLLIDABLE_METADATA) ? npc.data().<Boolean> get(NPC.COLLIDABLE_METADATA)
                        : !npc.isProtected();
    }

    @Override
    protected String bo() {
        return NMSImpl.getSoundEffect(npc, super.bo(), NPC.HURT_SOUND_METADATA);
    }

    @Override
    protected String bp() {
        return NMSImpl.getSoundEffect(npc, super.bp(), NPC.DEATH_SOUND_METADATA);
    }

    @Override
    public void collide(net.minecraft.server.v1_8_R3.Entity entity) {
        // this method is called by both the entities involved - cancelling
        // it will not stop the NPC from moving.
        super.collide(entity);
        if (npc != null) {
            Util.callCollisionEvent(npc, entity.getBukkitEntity());
        }
    }

    @Override
    public boolean damageEntity(DamageSource damagesource, float f) {
        boolean damaged = super.damageEntity(damagesource, f);

        // knock back velocity is cancelled and sent to client for handling when
        // the entity is a player. there is no client so make this happen
        // manually.
        if (damaged && velocityChanged) {
            velocityChanged = false;
        }
        return damaged;
    }

    @Override
    public void die(DamageSource damagesource) {
        releasePitControl();
        // players that die are not normally removed from the world. when the
        // NPC dies, we are done with the instance and it should be removed.
        if (dead) {
            return;
        }
        super.die(damagesource);
        Bukkit.getScheduler().runTaskLater(CitizensAPI.getPlugin(), new Runnable() {
            @Override
            public void run() {
                world.removeEntity(EntityHumanNPC.this);
            }
        }, 15); // give enough time for death and smoke animation
    }

    @Override
    public void e(float f, float f1) {
        if (npc == null || !npc.isFlyable()) {
            super.e(f, f1);
        }
    }

    @Override
    public void enderTeleportTo(double d0, double d1, double d2) {
        if (npc == null)
            super.enderTeleportTo(d0, d1, d2);
        NPCEnderTeleportEvent event = new NPCEnderTeleportEvent(npc);
        Bukkit.getPluginManager().callEvent(event);
        if (!event.isCancelled()) {
            super.enderTeleportTo(d0, d1, d2);
        }
    }

    @Override
    public void g(double x, double y, double z) {
        Vector vector = Util.callPushEvent(npc, x, y, z);
        if (vector != null) {
            super.g(vector.getX(), vector.getY(), vector.getZ());
        }
    }

    @Override
    public void g(float f, float f1) {
        if (npc == null || !npc.isFlyable()) {
            super.g(f, f1);
        } else {
            NMSImpl.flyingMoveLogic(this, f, f1);
        }
    }

    @Override
    public CraftPlayer getBukkitEntity() {
        if (npc != null && bukkitEntity == null) {
            bukkitEntity = new PlayerNPC(this);
        }
        return super.getBukkitEntity();
    }

    public PlayerControllerJump getControllerJump() {
        return controllerJump;
    }

    public PlayerControllerMove getControllerMove() {
        return controllerMove;
    }

    @Override
    public DataWatcher getDataWatcher() {
        if (!PlayerlistTrackerEntry.hasNativeSpawnHook() && trackerEntry != null && trackerEntry.isUpdating()) {
            trackerEntry.updateLastPlayer();
        }
        return super.getDataWatcher();
    }

    public NavigationAbstract getNavigation() {
        return navigation;
    }

    @Override
    public NPC getNPC() {
        return npc;
    }

    @Override
    public String getSkinName() {
        String skinName = npc.getOrAddTrait(SkinTrait.class).getSkinName();
        if (skinName == null) {
            skinName = npc.getName();
        }
        return skinName.toLowerCase();
    }

    @Override
    public SkinPacketTracker getSkinTracker() {
        return skinTracker;
    }

    @Override
    public boolean inBlock() {
        if (npc == null || noclip || isSleeping()) {
            return super.inBlock();
        }
        return Util.inBlock(getBukkitEntity());
    }

    private void initialise(MinecraftServer minecraftServer) {
        Socket socket = new EmptySocket();
        NetworkManager conn = null;
        try {
            conn = new EmptyNetworkManager(EnumProtocolDirection.CLIENTBOUND);
            playerConnection = new EmptyNetHandler(minecraftServer, conn, this);
            conn.a(playerConnection);
            socket.close();
        } catch (IOException e) {
            // swallow
        }

        AttributeInstance range = getAttributeInstance(GenericAttributes.FOLLOW_RANGE);
        if (range == null) {
            range = getAttributeMap().b(GenericAttributes.FOLLOW_RANGE);
        }
        range.setValue(Setting.DEFAULT_PATHFINDING_RANGE.asDouble());

        controllerJump = new PlayerControllerJump(this);
        controllerMove = new PlayerControllerMove(this);
        navigation = new PlayerNavigation(this, world);
        invulnerableTicks = 0;
        NMS.setStepHeight(getBukkitEntity(), 1); // the default (0) breaks step climbing

        setSkinFlags((byte) 0xFF);
    }

    public boolean isNavigating() {
        return npc.getNavigator().isNavigating();
    }

    /** Main-thread, one-tick input for marked Pit bots; ordinary Citizens navigation is untouched. */
    public boolean setPitControl(float yaw, float pitch, float strafe, float forward, boolean sprint, boolean jump) {
        if (!Bukkit.isPrimaryThread()) return false;
        if (npc == null || dead || vehicle != null || !npc.isSpawned()
                || !npc.data().<Boolean>get("pitsim-combat-bot", false) || npc.isFlyable()
                || npc.getNavigator().isNavigating() || controllerMove.a()) {
            releasePitControl();
            return false;
        }
        // Citizens' compatibility ticker and the world can both advance entity age.
        // Input lifetime belongs to the server tick, not those entity callbacks.
        if (!pitControl.submit(MinecraftServer.currentTick, yaw, pitch, strafe, forward, sprint, jump)) {
            releasePitControl();
            return false;
        }
        pitControlActive = true;
        applyPitRotation();
        setSprinting(pitControl.sprint());
        return true;
    }

    private void applyPitRotation() {
        yaw = pitControl.yaw();
        pitch = pitControl.pitch();
        // Body and head share the intended view; native tracking owns interpolation/packets.
        aI = aJ = aK = yaw;
    }

    /** Clear queued keys before damage recovery, teleport, despawn or a held bot tick. */
    public void clearPitControl() {
        if (!Bukkit.isPrimaryThread()) return;
        releasePitControl();
    }

    private void releasePitControl() {
        if (pitControl != null) pitControl.clear();
        if (!pitControlActive) return;
        pitControlActive = false;
        aZ = ba = 0F;
        i(false);
        jumpTicks = 0;
        setSprinting(false);
    }

    private boolean consumePitControl() {
        if (!pitControl.consume(MinecraftServer.currentTick)) {
            releasePitControl();
            return false;
        }
        if (dead || vehicle != null || !npc.isSpawned() || !npc.data().<Boolean>get("pitsim-combat-bot", false)
                || npc.isFlyable() || npc.getNavigator().isNavigating() || controllerMove.a()) {
            releasePitControl();
            return false;
        }
        applyPitRotation();
        setSprinting(pitControl.sprint());
        AttributeInstance speed = getAttributeInstance(GenericAttributes.MOVEMENT_SPEED);
        // Navigation's speed multiplier must not become a persistent player-input speed boost.
        speed.setValue(.1D);
        k((float)speed.getValue());
        aZ = pitControl.strafe();
        ba = pitControl.forward();
        i(pitControl.jump());
        try {
            moveOnCurrentHeading(true);
        } finally {
            // No held keys survive this physics step, including during knockback recovery.
            aZ = ba = 0F;
            i(false);
            pitControl.clear();
        }
        return true;
    }

    @Override
    public boolean k_() {
        if (npc == null || !npc.isFlyable()) {
            return super.k_();
        } else {
            return false;
        }
    }

    @Override
    public void l() {
        if (npc == null) {
            super.l();
            return;
        }

        super.K();
        boolean navigating = npc.getNavigator().isNavigating() || controllerMove.a();
        boolean controlled = consumePitControl();
        if (!controlled && !navigating && getBukkitEntity() != null
                && (!npc.hasTrait(Gravity.class) || npc.getOrAddTrait(Gravity.class).hasGravity())
                && Util.isLoaded(getBukkitEntity().getLocation(LOADED_LOCATION))
                && SpigotUtil.checkYSafe(locY, getBukkitEntity().getWorld())) {
            moveWithFallDamage(0, 0);
        }
        if (Math.abs(motX) < EPSILON && Math.abs(motY) < EPSILON && Math.abs(motZ) < EPSILON) {
            motX = motY = motZ = 0;
        }
        if (!controlled && navigating) {
            if (!NMSImpl.isNavigationFinished(navigation)) {
                NMSImpl.updateNavigation(navigation);
            }
            moveOnCurrentHeading();
        }
        NMSImpl.updateAI(this);

        if (npc.data().get(NPC.Metadata.COLLIDABLE, !npc.isProtected())) {
            bL();
        }

        if (npc.data().get(NPC.Metadata.PICKUP_ITEMS, !npc.isProtected())
                && shouldScanItems(npc.data().<Boolean>get("pitsim-combat-bot", false),
                        npc.data().<Integer>get("pitsim-item-scan-interval", 1), pickupCounter++, getId())) {
            AxisAlignedBB axisalignedbb = null;
            if (this.vehicle != null && !this.vehicle.dead) {
                axisalignedbb = this.getBoundingBox().a(this.vehicle.getBoundingBox()).grow(1.0, 0.0, 1.0);
            } else {
                axisalignedbb = this.getBoundingBox().grow(1.0, 0.5, 1.0);
            }

            for (Entity entity : this.world.getEntities(this, axisalignedbb)) {
                if (!entity.dead) {
                    entity.d(this);
                }
            }
        }
    }

    private void moveOnCurrentHeading() {
        moveOnCurrentHeading(false);
    }

    private void moveOnCurrentHeading(boolean controlled) {
        if (aY) {
            if (onGround && jumpTicks == 0) {
                bF();
                jumpTicks = 10;
            }
        } else {
            jumpTicks = 0;
        }
        aZ *= 0.98F;
        ba *= 0.98F;
        bb *= 0.9F;
        moveWithFallDamage(aZ, ba); // movement method
        if (controlled) aI = aJ = aK = yaw;
        else NMS.setHeadYaw(getBukkitEntity(), yaw);
        if (jumpTicks > 0) {
            jumpTicks--;
        }
    }

    private void moveWithFallDamage(float mx, float my) {
        double y = this.locY;

        g(mx, my);
        if (!npc.isProtected()) {
            a(this.locY - y, onGround);
        }
    }

    public void setMoveDestination(double x, double y, double z, double speed) {
        controllerMove.a(x, y, z, speed);
    }

    public void setShouldJump() {
        controllerJump.a();
    }

    @Override
    public void setSkinFlags(byte flags) {
        // set skin flag byte
        getDataWatcher().watch(10, flags);
    }

    @Override
    public void setSkinName(String name) {
        npc.getOrAddTrait(SkinTrait.class).setSkinName(name);
    }

    @Override
    public void setSkinName(String name, boolean forceUpdate) {
        npc.getOrAddTrait(SkinTrait.class).setSkinName(name, forceUpdate);
    }

    @Override
    public void setSkinPersistent(String skinName, String signature, String data) {
        npc.getOrAddTrait(SkinTrait.class).setSkinPersistent(skinName, signature, data);
    }

    public void setTracked(PlayerlistTrackerEntry trackerEntry) {
        this.trackerEntry = trackerEntry;
    }

    /** Native player-maintenance entries, independent of duplicate world entity ages.
     * Int wrapping matches native timer-deadline arithmetic; this is not a cooldown.
     */
    public int getPitHurtTick() {
        return pitHurtTick;
    }

    @Override
    public void t_() {
        if (npc == null || !npc.data().<Boolean>get("pitsim-combat-bot", false)) {
            pitPlayerTicked = false;
            pitHurtTick++;
            super.t_();
        } else if (!pitPlayerTicked || pitPlayerTick != MinecraftServer.currentTick) {
            // Wind's entity pass and Citizens' compatibility ticker can both call
            // this method in one server tick. Native player maintenance, including
            // hurt immunity, must advance only once. Stamp before callbacks so a
            // nested invocation cannot repeat it; keep Citizens' work below intact.
            pitPlayerTicked = true;
            pitPlayerTick = MinecraftServer.currentTick;
            pitHurtTick++;
            super.t_();
        }
        if (npc == null)
            return;
        if (updateCounter + 1 > Setting.PACKET_UPDATE_DELAY.asInt()) {
            updateEffects = true;
        }
        this.noclip = isSpectator();
        Bukkit.getServer().getPluginManager().unsubscribeFromPermission("bukkit.broadcast.user", bukkitEntity);

        boolean navigating = npc.getNavigator().isNavigating();
        updatePackets(navigating);

        npc.update();
    }

    public void updateAI() {
        controllerMove.c();
        controllerJump.b();
    }

    private void updatePackets(boolean navigating) {
        if (updateCounter++ <= npc.data().<Integer> get(NPC.Metadata.PACKET_UPDATE_DELAY,
                Setting.PACKET_UPDATE_DELAY.asInt()))
            return;
        updateCounter = 0;
        if (npc.data().<Boolean>get("pitsim-combat-bot", false)) {
            // Native tracking sends the complete initial equipment. Subsequent changes
            // go only to existing trackers and only for dirty slots, including the helmet.
            for (int slot = 0; slot < pitEquipment.length; slot++) {
                ItemStack equipment = getEquipment(slot);
                if (ItemStack.equals(pitEquipment[slot], equipment)) continue;
                pitEquipment[slot] = equipment == null ? null : equipment.cloneItemStack();
                ((WorldServer)world).getTracker().a(this, new PacketPlayOutEntityEquipment(getId(), slot, equipment));
            }
            return;
        }
        boolean itemChanged = false;
        for (int slot = 0; slot < this.inventory.armor.length; slot++) {
            ItemStack equipment = getEquipment(slot);
            ItemStack cache = equipmentCache.get(slot);
            if (!(cache == null && equipment == null)
                    && (cache == null ^ equipment == null || !ItemStack.equals(cache, equipment))) {
                itemChanged = true;
            }
            equipmentCache.put(slot, equipment);
        }
        if (!itemChanged)
            return;
        Location current = getBukkitEntity().getLocation(packetLocationCache);
        Packet<?>[] packets = new Packet[this.inventory.armor.length];
        for (int i = 0; i < this.inventory.armor.length; i++) {
            packets[i] = new PacketPlayOutEntityEquipment(getId(), i, getEquipment(i));
        }
        NMSImpl.sendPacketsNearby(getBukkitEntity(), current, packets);
    }

    public void updatePathfindingRange(float pathfindingRange) {
        this.navigation.setRange(pathfindingRange);
    }

    static boolean shouldScanItems(boolean pitBot, int requestedInterval, int tick, int entityId) {
        int interval = pitBot ? Math.max(1, Math.min(4, requestedInterval)) : 1;
        return Math.floorMod((long)tick + entityId, interval) == 0;
    }

    public static class PlayerNPC extends CraftPlayer implements NPCHolder, SkinnableEntity {
        private final CraftServer cserver;
        private final CitizensNPC npc;

        private PlayerNPC(EntityHumanNPC entity) {
            super((CraftServer) Bukkit.getServer(), entity);
            this.npc = entity.npc;
            this.cserver = (CraftServer) Bukkit.getServer();
            npc.getOrAddTrait(Inventory.class);
        }

        @Override
        public Player getBukkitEntity() {
            return this;
        }

        @Override
        public EntityHumanNPC getHandle() {
            return (EntityHumanNPC) this.entity;
        }

        @Override
        public List<MetadataValue> getMetadata(String metadataKey) {
            return cserver.getEntityMetadata().getMetadata(this, metadataKey);
        }

        @Override
        public NPC getNPC() {
            return npc;
        }

        @Override
        public String getSkinName() {
            return ((SkinnableEntity) this.entity).getSkinName();
        }

        @Override
        public SkinPacketTracker getSkinTracker() {
            return ((SkinnableEntity) this.entity).getSkinTracker();
        }

        @Override
        public boolean hasMetadata(String metadataKey) {
            return cserver.getEntityMetadata().hasMetadata(this, metadataKey);
        }

        @Override
        public void removeMetadata(String metadataKey, Plugin owningPlugin) {
            cserver.getEntityMetadata().removeMetadata(this, metadataKey, owningPlugin);
        }

        @Override
        public void setMetadata(String metadataKey, MetadataValue newMetadataValue) {
            cserver.getEntityMetadata().setMetadata(this, metadataKey, newMetadataValue);
        }

        @Override
        public void setSkinFlags(byte flags) {
            ((SkinnableEntity) this.entity).setSkinFlags(flags);
        }

        @Override
        public void setSkinName(String name) {
            ((SkinnableEntity) this.entity).setSkinName(name);
        }

        @Override
        public void setSkinName(String skinName, boolean forceUpdate) {
            ((SkinnableEntity) this.entity).setSkinName(skinName, forceUpdate);
        }

        @Override
        public void setSkinPersistent(String skinName, String signature, String data) {
            ((SkinnableEntity) this.entity).setSkinPersistent(skinName, signature, data);
        }
    }

    private static final float EPSILON = 0.003F;
    private static final Location LOADED_LOCATION = new Location(null, 0, 0, 0);
}
