package cn.nukkit.network;

import cn.nukkit.Nukkit;
import cn.nukkit.Player;
import cn.nukkit.Server;
import cn.nukkit.nbt.stream.FastByteArrayOutputStream;
import cn.nukkit.network.protocol.*;
import cn.nukkit.utils.BinaryStream;
import cn.nukkit.utils.ThreadCache;
import cn.nukkit.utils.Utils;
import cn.nukkit.utils.VarInt;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import lombok.extern.log4j.Log4j2;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ProtocolException;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * author: MagicDroidX
 * Nukkit Project
 */
@Log4j2
public class Network {

    private static final ThreadLocal<Inflater> INFLATER_RAW = ThreadLocal.withInitial(() -> new Inflater(true));
    private static final ThreadLocal<Deflater> DEFLATER_RAW = ThreadLocal.withInitial(() -> new Deflater(7, true));
    private static final ThreadLocal<byte[]> BUFFER = ThreadLocal.withInitial(() -> new byte[2 * 1024 * 1024]);


    public static final byte CHANNEL_NONE = 0;
    public static final byte CHANNEL_PRIORITY = 1; //Priority channel, only to be used when it matters
    public static final byte CHANNEL_WORLD_CHUNKS = 2; //Chunk sending
    public static final byte CHANNEL_MOVEMENT = 3; //Movement sending
    public static final byte CHANNEL_BLOCKS = 4; //Block updates or explosions
    public static final byte CHANNEL_WORLD_EVENTS = 5; //Entity, level or blockentity entity events
    public static final byte CHANNEL_ENTITY_SPAWNING = 6; //Entity spawn/despawn channel
    public static final byte CHANNEL_TEXT = 7; //Chat and other text stuff
    public static final byte CHANNEL_END = 31;

    private Class<? extends DataPacket>[] packetPool = new Class[256];

    private final Server server;

    private final Set<SourceInterface> interfaces = new HashSet<>();

    private final Set<AdvancedSourceInterface> advancedInterfaces = new HashSet<>();

    private double upload = 0;
    private double download = 0;

    private String name;
    private String subName;

    public Network(Server server) {
        this.registerPackets();
        this.server = server;
    }

    public static byte[] inflateRaw(byte[] data) throws IOException, DataFormatException {
        Inflater inflater = INFLATER_RAW.get();
        try {
            inflater.setInput(data);
            inflater.finished();

            FastByteArrayOutputStream bos = ThreadCache.fbaos.get();
            bos.reset();
            byte[] buf = BUFFER.get();
            while (!inflater.finished()) {
                int i = inflater.inflate(buf);
                if (i == 0) {
                    throw new IOException("Could not decompress the data. Needs input: " + inflater.needsInput() + ", Needs Dictionary: " + inflater.needsDictionary());
                }
                bos.write(buf, 0, i);
            }
            return bos.toByteArray();
        } finally {
            inflater.reset();
        }
    }

    public static byte[] deflateRaw(byte[] data, int level) throws IOException {
        Deflater deflater = DEFLATER_RAW.get();
        try {
            deflater.setLevel(data.length < Server.getInstance().networkCompressionThreshold ? 0 : level);
            deflater.setInput(data);
            deflater.finish();
            FastByteArrayOutputStream bos = ThreadCache.fbaos.get();
            bos.reset();
            byte[] buffer = BUFFER.get();
            while (!deflater.finished()) {
                int i = deflater.deflate(buffer);
                bos.write(buffer, 0, i);
            }

            return bos.toByteArray();
        } finally {
            deflater.reset();
        }
    }

    public static byte[] deflateRaw(byte[][] datas, int level) throws IOException {
        Deflater deflater = DEFLATER_RAW.get();
        try {
            deflater.setLevel(level);
            FastByteArrayOutputStream bos = ThreadCache.fbaos.get();
            bos.reset();
            byte[] buffer = BUFFER.get();

            for (byte[] data : datas) {
                deflater.setInput(data);
                while (!deflater.needsInput()) {
                    int i = deflater.deflate(buffer);
                    bos.write(buffer, 0, i);
                }
            }
            deflater.finish();
            while (!deflater.finished()) {
                int i = deflater.deflate(buffer);
                bos.write(buffer, 0, i);
            }
            return bos.toByteArray();
        } finally {
            deflater.reset();
        }
    }

    public void addStatistics(double upload, double download) {
        this.upload += upload;
        this.download += download;
    }

    public double getUpload() {
        return upload;
    }

    public double getDownload() {
        return download;
    }

    public void resetStatistics() {
        this.upload = 0;
        this.download = 0;
    }

    public Set<SourceInterface> getInterfaces() {
        return interfaces;
    }

    public void processInterfaces() {
        for (SourceInterface interfaz : this.interfaces) {
            try {
                interfaz.process();
            } catch (Exception e) {
                if (Nukkit.DEBUG > 1) {
                    this.server.getLogger().logException(e);
                }

                interfaz.emergencyShutdown();
                this.unregisterInterface(interfaz);
                log.fatal(this.server.getLanguage().translateString("nukkit.server.networkError", new String[]{interfaz.getClass().getName(), Utils.getExceptionMessage(e)}));
            }
        }
    }

    public void registerInterface(SourceInterface interfaz) {
        this.interfaces.add(interfaz);
        if (interfaz instanceof AdvancedSourceInterface) {
            this.advancedInterfaces.add((AdvancedSourceInterface) interfaz);
            ((AdvancedSourceInterface) interfaz).setNetwork(this);
        }
        interfaz.setName(this.name + "!@#" + this.subName);
    }

    public void unregisterInterface(SourceInterface sourceInterface) {
        this.interfaces.remove(sourceInterface);
        if (sourceInterface instanceof AdvancedSourceInterface) {
            this.advancedInterfaces.remove(sourceInterface);
        }
    }

    public void setName(String name) {
        this.name = name;
        this.updateName();
    }

    public String getName() {
        return name;
    }

    public String getSubName() {
        return subName;
    }

    public void setSubName(String subName) {
        this.subName = subName;
    }

    public void updateName() {
        for (SourceInterface interfaz : this.interfaces) {
            interfaz.setName(this.name + "!@#" + this.subName);
        }
    }

    public void registerPacket(byte id, Class<? extends DataPacket> clazz) {
        this.packetPool[id & 0xff] = clazz;
    }

    public Server getServer() {
        return server;
    }

    public void processBatch(BatchPacket packet, Player player) {
        List<DataPacket> packets = new ObjectArrayList<>();
        try {
            this.processBatch(packet.payload, packets, player.getNetworkSession().getCompression());
        } catch (ProtocolException e) {
            player.close("", e.getMessage());
            log.error("Unable to process player packets ", e);
        }
    }

    public void processBatch(byte[] payload, Collection<DataPacket> packets, CompressionProvider compression) throws ProtocolException {
        byte[] data;
        try {
            data = compression.decompress(payload);
        } catch (Exception e) {
            log.debug("Exception while inflating batch packet", e);
            return;
        }

        BinaryStream stream = new BinaryStream(data);
        try {
            int count = 0;
            while (!stream.feof()) {
                count++;
                if (count >= 1000) {
                    throw new ProtocolException("Illegal batch with " + count + " packets");
                }
                byte[] buf = stream.getByteArray();

                ByteArrayInputStream bais = new ByteArrayInputStream(buf);
                int header = (int) VarInt.readUnsignedVarInt(bais);

                // | Client ID | Sender ID | Packet ID |
                // |   2 bits  |   2 bits  |  10 bits  |
                int packetId = header & 0x3ff;

                DataPacket pk = this.getPacket(packetId);

                if (pk != null) {
                    pk.setBuffer(buf, buf.length - bais.available());
                    try {
                        pk.decode();
                    } catch (Exception e) {
                        if (log.isTraceEnabled()) {
                            log.trace("Dumping Packet\n{}", ByteBufUtil.prettyHexDump(Unpooled.wrappedBuffer(buf)));
                        }
                        log.error("Unable to decode packet", e);
                        throw new IllegalStateException("Unable to decode " + pk.getClass().getSimpleName());
                    }

                    packets.add(pk);
                } else {
                    log.debug("Received unknown packet with ID: {}", Integer.toHexString(packetId));
                }
            }
        } catch (Exception e) {
            if (log.isDebugEnabled()) {
                log.debug("Error whilst decoding batch packet", e);
            }
        }
    }

    /**
     * Process packets obtained from batch packets
     * Required to perform additional analyses and filter unnecessary packets
     *
     * @param packets
     */
    public void processPackets(Player player, List<DataPacket> packets) {
        if (packets.isEmpty()) return;
        packets.forEach(player::handleDataPacket);
    }

    public DataPacket getPacket(int id) {
        Class<? extends DataPacket> clazz = this.packetPool[id];
        if (clazz != null) {
            try {
                return clazz.newInstance();
            } catch (Exception e) {
                Server.getInstance().getLogger().logException(e);
            }
        }
        return null;
    }

    public void sendPacket(InetSocketAddress socketAddress, ByteBuf payload) {
        for (AdvancedSourceInterface sourceInterface : this.advancedInterfaces) {
            sourceInterface.sendRawPacket(socketAddress, payload);
        }
    }

    public void blockAddress(InetAddress address) {
        for (AdvancedSourceInterface sourceInterface : this.advancedInterfaces) {
            sourceInterface.blockAddress(address);
        }
    }

    public void blockAddress(InetAddress address, int timeout) {
        for (AdvancedSourceInterface sourceInterface : this.advancedInterfaces) {
            sourceInterface.blockAddress(address, timeout);
        }
    }

    public void unblockAddress(InetAddress address) {
        for (AdvancedSourceInterface sourceInterface : this.advancedInterfaces) {
            sourceInterface.unblockAddress(address);
        }
    }

    private void registerPackets() {
        this.packetPool = new Class[256];

        this.registerPacket(ProtocolInfo.ADD_ENTITY_PACKET, AddEntityPacket.class);
        this.registerPacket(ProtocolInfo.ADD_ITEM_ENTITY_PACKET, AddItemEntityPacket.class);
        this.registerPacket(ProtocolInfo.ADD_PAINTING_PACKET, AddPaintingPacket.class);
        this.registerPacket(ProtocolInfo.ADD_PLAYER_PACKET, AddPlayerPacket.class);
        this.registerPacket(ProtocolInfo.ADVENTURE_SETTINGS_PACKET, AdventureSettingsPacket.class);
        this.registerPacket(ProtocolInfo.ANIMATE_PACKET, AnimatePacket.class);
        this.registerPacket(ProtocolInfo.ANVIL_DAMAGE_PACKET, AnvilDamagePacket.class);
        this.registerPacket(ProtocolInfo.AVAILABLE_COMMANDS_PACKET, AvailableCommandsPacket.class);
        this.registerPacket(ProtocolInfo.BATCH_PACKET, BatchPacket.class);
        this.registerPacket(ProtocolInfo.BLOCK_ENTITY_DATA_PACKET, BlockEntityDataPacket.class);
        this.registerPacket(ProtocolInfo.BLOCK_EVENT_PACKET, BlockEventPacket.class);
        this.registerPacket(ProtocolInfo.BLOCK_PICK_REQUEST_PACKET, BlockPickRequestPacket.class);
        this.registerPacket(ProtocolInfo.BOOK_EDIT_PACKET, BookEditPacket.class);
        this.registerPacket(ProtocolInfo.BOSS_EVENT_PACKET, BossEventPacket.class);
        this.registerPacket(ProtocolInfo.CHANGE_DIMENSION_PACKET, ChangeDimensionPacket.class);
        this.registerPacket(ProtocolInfo.CHUNK_RADIUS_UPDATED_PACKET, ChunkRadiusUpdatedPacket.class);
        this.registerPacket(ProtocolInfo.CLIENTBOUND_MAP_ITEM_DATA_PACKET, ClientboundMapItemDataPacket.class);
        this.registerPacket(ProtocolInfo.COMMAND_REQUEST_PACKET, CommandRequestPacket.class);
        this.registerPacket(ProtocolInfo.CONTAINER_CLOSE_PACKET, ContainerClosePacket.class);
        this.registerPacket(ProtocolInfo.CONTAINER_OPEN_PACKET, ContainerOpenPacket.class);
        this.registerPacket(ProtocolInfo.CONTAINER_SET_DATA_PACKET, ContainerSetDataPacket.class);
        this.registerPacket(ProtocolInfo.CRAFTING_DATA_PACKET, CraftingDataPacket.class);
        this.registerPacket(ProtocolInfo.CRAFTING_EVENT_PACKET, CraftingEventPacket.class);
        this.registerPacket(ProtocolInfo.DISCONNECT_PACKET, DisconnectPacket.class);
        this.registerPacket(ProtocolInfo.ENTITY_EVENT_PACKET, EntityEventPacket.class);
        this.registerPacket(ProtocolInfo.ENTITY_FALL_PACKET, EntityFallPacket.class);
        this.registerPacket(ProtocolInfo.FULL_CHUNK_DATA_PACKET, LevelChunkPacket.class);
        this.registerPacket(ProtocolInfo.GAME_RULES_CHANGED_PACKET, GameRulesChangedPacket.class);
        this.registerPacket(ProtocolInfo.HURT_ARMOR_PACKET, HurtArmorPacket.class);
        this.registerPacket(ProtocolInfo.INTERACT_PACKET, InteractPacket.class);
        this.registerPacket(ProtocolInfo.INVENTORY_CONTENT_PACKET, InventoryContentPacket.class);
        this.registerPacket(ProtocolInfo.INVENTORY_SLOT_PACKET, InventorySlotPacket.class);
        this.registerPacket(ProtocolInfo.INVENTORY_TRANSACTION_PACKET, InventoryTransactionPacket.class);
        this.registerPacket(ProtocolInfo.ITEM_FRAME_DROP_ITEM_PACKET, ItemFrameDropItemPacket.class);
        this.registerPacket(ProtocolInfo.LEVEL_EVENT_PACKET, LevelEventPacket.class);
        this.registerPacket(ProtocolInfo.LEVEL_SOUND_EVENT_PACKET_V1, LevelSoundEventPacketV1.class);
        this.registerPacket(ProtocolInfo.LOGIN_PACKET, LoginPacket.class);
        this.registerPacket(ProtocolInfo.MAP_INFO_REQUEST_PACKET, MapInfoRequestPacket.class);
        this.registerPacket(ProtocolInfo.MOB_ARMOR_EQUIPMENT_PACKET, MobArmorEquipmentPacket.class);
        this.registerPacket(ProtocolInfo.MOB_EQUIPMENT_PACKET, MobEquipmentPacket.class);
        this.registerPacket(ProtocolInfo.MODAL_FORM_REQUEST_PACKET, ModalFormRequestPacket.class);
        this.registerPacket(ProtocolInfo.MODAL_FORM_RESPONSE_PACKET, ModalFormResponsePacket.class);
        this.registerPacket(ProtocolInfo.MOVE_ENTITY_ABSOLUTE_PACKET, MoveEntityAbsolutePacket.class);
        this.registerPacket(ProtocolInfo.MOVE_PLAYER_PACKET, MovePlayerPacket.class);
        this.registerPacket(ProtocolInfo.PLAYER_ACTION_PACKET, PlayerActionPacket.class);
        this.registerPacket(ProtocolInfo.PLAYER_INPUT_PACKET, PlayerInputPacket.class);
        this.registerPacket(ProtocolInfo.PLAYER_LIST_PACKET, PlayerListPacket.class);
        this.registerPacket(ProtocolInfo.PLAYER_HOTBAR_PACKET, PlayerHotbarPacket.class);
        this.registerPacket(ProtocolInfo.PLAY_SOUND_PACKET, PlaySoundPacket.class);
        this.registerPacket(ProtocolInfo.PLAY_STATUS_PACKET, PlayStatusPacket.class);
        this.registerPacket(ProtocolInfo.REMOVE_ENTITY_PACKET, RemoveEntityPacket.class);
        this.registerPacket(ProtocolInfo.REQUEST_CHUNK_RADIUS_PACKET, RequestChunkRadiusPacket.class);
        this.registerPacket(ProtocolInfo.RESOURCE_PACKS_INFO_PACKET, ResourcePacksInfoPacket.class);
        this.registerPacket(ProtocolInfo.RESOURCE_PACK_STACK_PACKET, ResourcePackStackPacket.class);
        this.registerPacket(ProtocolInfo.RESOURCE_PACK_CLIENT_RESPONSE_PACKET, ResourcePackClientResponsePacket.class);
        this.registerPacket(ProtocolInfo.RESOURCE_PACK_DATA_INFO_PACKET, ResourcePackDataInfoPacket.class);
        this.registerPacket(ProtocolInfo.RESOURCE_PACK_CHUNK_DATA_PACKET, ResourcePackChunkDataPacket.class);
        this.registerPacket(ProtocolInfo.RESOURCE_PACK_CHUNK_REQUEST_PACKET, ResourcePackChunkRequestPacket.class);
        this.registerPacket(ProtocolInfo.PLAYER_SKIN_PACKET, PlayerSkinPacket.class);
        this.registerPacket(ProtocolInfo.RESPAWN_PACKET, RespawnPacket.class);
        this.registerPacket(ProtocolInfo.RIDER_JUMP_PACKET, RiderJumpPacket.class);
        this.registerPacket(ProtocolInfo.SET_COMMANDS_ENABLED_PACKET, SetCommandsEnabledPacket.class);
        this.registerPacket(ProtocolInfo.SET_DIFFICULTY_PACKET, SetDifficultyPacket.class);
        this.registerPacket(ProtocolInfo.SET_ENTITY_DATA_PACKET, SetEntityDataPacket.class);
        this.registerPacket(ProtocolInfo.SET_ENTITY_LINK_PACKET, SetEntityLinkPacket.class);
        this.registerPacket(ProtocolInfo.SET_ENTITY_MOTION_PACKET, SetEntityMotionPacket.class);
        this.registerPacket(ProtocolInfo.SET_HEALTH_PACKET, SetHealthPacket.class);
        this.registerPacket(ProtocolInfo.SET_PLAYER_GAME_TYPE_PACKET, SetPlayerGameTypePacket.class);
        this.registerPacket(ProtocolInfo.SET_SPAWN_POSITION_PACKET, SetSpawnPositionPacket.class);
        this.registerPacket(ProtocolInfo.SET_TITLE_PACKET, SetTitlePacket.class);
        this.registerPacket(ProtocolInfo.SET_TIME_PACKET, SetTimePacket.class);
        this.registerPacket(ProtocolInfo.SERVER_SETTINGS_REQUEST_PACKET, ServerSettingsRequestPacket.class);
        this.registerPacket(ProtocolInfo.SERVER_SETTINGS_RESPONSE_PACKET, ServerSettingsResponsePacket.class);
        this.registerPacket(ProtocolInfo.SHOW_CREDITS_PACKET, ShowCreditsPacket.class);
        this.registerPacket(ProtocolInfo.SPAWN_EXPERIENCE_ORB_PACKET, SpawnExperienceOrbPacket.class);
        this.registerPacket(ProtocolInfo.START_GAME_PACKET, StartGamePacket.class);
        this.registerPacket(ProtocolInfo.TAKE_ITEM_ENTITY_PACKET, TakeItemEntityPacket.class);
        this.registerPacket(ProtocolInfo.TEXT_PACKET, TextPacket.class);
        this.registerPacket(ProtocolInfo.UPDATE_ATTRIBUTES_PACKET, UpdateAttributesPacket.class);
        this.registerPacket(ProtocolInfo.UPDATE_BLOCK_PACKET, UpdateBlockPacket.class);
        this.registerPacket(ProtocolInfo.UPDATE_TRADE_PACKET, UpdateTradePacket.class);
        this.registerPacket(ProtocolInfo.MOVE_ENTITY_DELTA_PACKET, MoveEntityDeltaPacket.class);
        this.registerPacket(ProtocolInfo.SET_LOCAL_PLAYER_AS_INITIALIZED_PACKET, SetLocalPlayerAsInitializedPacket.class);
        this.registerPacket(ProtocolInfo.NETWORK_STACK_LATENCY_PACKET, NetworkStackLatencyPacket.class);
        this.registerPacket(ProtocolInfo.UPDATE_SOFT_ENUM_PACKET, UpdateSoftEnumPacket.class);
        this.registerPacket(ProtocolInfo.NETWORK_CHUNK_PUBLISHER_UPDATE_PACKET, NetworkChunkPublisherUpdatePacket.class);
        this.registerPacket(ProtocolInfo.AVAILABLE_ENTITY_IDENTIFIERS_PACKET, AvailableEntityIdentifiersPacket.class);
        this.registerPacket(ProtocolInfo.LEVEL_SOUND_EVENT_PACKET_V2, LevelSoundEventPacket.class);
        this.registerPacket(ProtocolInfo.SCRIPT_CUSTOM_EVENT_PACKET, ScriptCustomEventPacket.class);
        this.registerPacket(ProtocolInfo.SPAWN_PARTICLE_EFFECT_PACKET, SpawnParticleEffectPacket.class);
        this.registerPacket(ProtocolInfo.BIOME_DEFINITION_LIST_PACKET, BiomeDefinitionListPacket.class);
        this.registerPacket(ProtocolInfo.LEVEL_SOUND_EVENT_PACKET, LevelSoundEventPacket.class);
        this.registerPacket(ProtocolInfo.LEVEL_EVENT_GENERIC_PACKET, LevelEventGenericPacket.class);
        this.registerPacket(ProtocolInfo.LECTERN_UPDATE_PACKET, LecternUpdatePacket.class);
        this.registerPacket(ProtocolInfo.VIDEO_STREAM_CONNECT_PACKET, VideoStreamConnectPacket.class);
        this.registerPacket(ProtocolInfo.CLIENT_CACHE_STATUS_PACKET, ClientCacheStatusPacket.class);
        this.registerPacket(ProtocolInfo.MAP_CREATE_LOCKED_COPY_PACKET, MapCreateLockedCopyPacket.class);
        this.registerPacket(ProtocolInfo.EMOTE_PACKET, EmotePacket.class);
        this.registerPacket(ProtocolInfo.ON_SCREEN_TEXTURE_ANIMATION_PACKET, OnScreenTextureAnimationPacket.class);
        this.registerPacket(ProtocolInfo.COMPLETED_USING_ITEM_PACKET, CompletedUsingItemPacket.class);
        this.registerPacket(ProtocolInfo.NETWORK_SETTINGS_PACKET, NetworkSettingsPacket.class);
        this.registerPacket(ProtocolInfo.CODE_BUILDER_PACKET, CodeBuilderPacket.class);
        this.registerPacket(ProtocolInfo.PLAYER_AUTH_INPUT_PACKET, PlayerAuthInputPacket.class);
        this.registerPacket(ProtocolInfo.CREATIVE_CONTENT_PACKET, CreativeContentPacket.class);
        this.registerPacket(ProtocolInfo.DEBUG_INFO_PACKET, DebugInfoPacket.class);
        this.registerPacket(ProtocolInfo.EMOTE_LIST_PACKET, EmoteListPacket.class);
        this.registerPacket(ProtocolInfo.PACKET_VIOLATION_WARNING_PACKET, PacketViolationWarningPacket.class);
        this.registerPacket(ProtocolInfo.PLAYER_ARMOR_DAMAGE_PACKET, PlayerArmorDamagePacket.class);
        this.registerPacket(ProtocolInfo.PLAYER_ENCHANT_OPTIONS_PACKET, PlayerEnchantOptionsPacket.class);
        this.registerPacket(ProtocolInfo.UPDATE_PLAYER_GAME_TYPE_PACKET, UpdatePlayerGameTypePacket.class);
        this.registerPacket(ProtocolInfo.UPDATE_ABILITIES_PACKET, UpdateAbilitiesPacket.class);
        this.registerPacket(ProtocolInfo.REQUEST_ABILITY_PACKET, RequestAbilityPacket.class);
        this.registerPacket(ProtocolInfo.UPDATE_ADVENTURE_SETTINGS_PACKET, UpdateAdventureSettingsPacket.class);
        this.registerPacket(ProtocolInfo.FILTER_TEXT_PACKET, FilterTextPacket.class);
        this.registerPacket(ProtocolInfo.TOAST_REQUEST_PACKET, ToastRequestPacket.class);
        this.registerPacket(ProtocolInfo.REQUEST_NETWORK_SETTINGS_PACKET, RequestNetworkSettingsPacket.class);
        this.registerPacket(ProtocolInfo.CLIENT_TO_SERVER_HANDSHAKE_PACKET, ClientToServerHandshakePacket.class);
    }
}
