package net.citizensnpcs.nms.v1_8_R3.network;

import net.minecraft.server.v1_8_R3.EntityPlayer;
import net.minecraft.server.v1_8_R3.MinecraftServer;
import net.minecraft.server.v1_8_R3.NetworkManager;
import net.minecraft.server.v1_8_R3.Packet;
import net.minecraft.server.v1_8_R3.PlayerConnection;

public class EmptyNetHandler extends PlayerConnection {
    public EmptyNetHandler(MinecraftServer minecraftServer, NetworkManager networkManager, EntityPlayer entityPlayer) {
        super(minecraftServer, networkManager, entityPlayer);
    }

    @Override
    public void sendPacket(Packet packet) {
    }

    /**
     * WindSpigot queues tracker packets before calling sendPacket, but flushes
     * that queue only for real players. An NPC has no client, so retaining its
     * velocity, attribute and metadata packets would grow without bound.
     *
     * No @Override: the vanilla CraftBukkit compile target has no queuePacket.
     */
    public void queuePacket(Packet packet) {
    }
}
