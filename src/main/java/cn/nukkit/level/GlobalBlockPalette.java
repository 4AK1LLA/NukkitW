package cn.nukkit.level;

import cn.nukkit.Server;
import cn.nukkit.block.BlockID;
import cn.nukkit.nbt.NBTIO;
import cn.nukkit.nbt.tag.CompoundTag;
import cn.nukkit.nbt.tag.ListTag;
import cn.nukkit.network.protocol.ProtocolInfo;
import it.unimi.dsi.fastutil.ints.Int2IntMap;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import lombok.extern.log4j.Log4j2;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteOrder;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.zip.GZIPInputStream;

@Log4j2
public class GlobalBlockPalette {
    private static final Int2IntMap legacyToRuntimeId = new Int2IntOpenHashMap();
    private static final Int2IntMap runtimeIdToLegacy = new Int2IntOpenHashMap();

    private static final Int2IntMap legacyToRuntimeId_589 = new Int2IntOpenHashMap();
    private static final Int2IntMap runtimeIdToLegacy_589 = new Int2IntOpenHashMap();

    static {
        init(ProtocolInfo.CURRENT_PROTOCOL);
        init(ProtocolInfo.PROTOCOL_589);
    }

    private static void init(int protocol) {
        Int2IntMap legacyToRuntimeId = getLegacyToRuntimeIdMap(protocol);
        Int2IntMap runtimeIdToLegacy = getRuntimeIdToLegacyMap(protocol);
        String fileName = getFileName(protocol);

        legacyToRuntimeId.defaultReturnValue(-1);
        runtimeIdToLegacy.defaultReturnValue(-1);

        ListTag<CompoundTag> tag;
        try (InputStream stream = Server.class.getClassLoader().getResourceAsStream(fileName)) {
            if (stream == null) {
                throw new AssertionError("Unable to locate block state nbt");
            }
            //noinspection unchecked
            tag = (ListTag<CompoundTag>) NBTIO.readTag(new BufferedInputStream(new GZIPInputStream(stream)), ByteOrder.BIG_ENDIAN, false);
        } catch (IOException e) {
            throw new AssertionError("Unable to load block palette", e);
        }

        List<CompoundTag> stateOverloads = new ObjectArrayList<>();
        for (CompoundTag state : tag.getAll()) {
            if (!registerBlockState(legacyToRuntimeId, runtimeIdToLegacy, state, false)) {
                stateOverloads.add(state);
            }
        }

        for (CompoundTag state : stateOverloads) {
            log.debug("Registering block palette overload: {}", state.getString("name"));
            registerBlockState(legacyToRuntimeId, runtimeIdToLegacy, state, true);
        }
    }

    private static boolean registerBlockState(Int2IntMap legacyToRuntimeId, Int2IntMap runtimeIdToLegacy, CompoundTag state, boolean force) {
        int blockId = state.getInt("id");
        int meta = state.getShort("data");
        int runtimeId = state.getInt("runtimeId");
        boolean stateOverload = state.getBoolean("stateOverload");

        if (stateOverload && !force) {
            return false;
        }

        int legacyId = blockId << 6 | meta;
        legacyToRuntimeId.put(legacyId, runtimeId);
        runtimeIdToLegacy.putIfAbsent(runtimeId, legacyId);
        return true;
    }

    public static int getOrCreateRuntimeId(int id, int meta) {
        int legacyId = id << 6 | meta;
        int runtimeId = legacyToRuntimeId.get(legacyId);
        if (runtimeId == -1) {
            runtimeId = legacyToRuntimeId.get(id << 6);
            if (runtimeId == -1 && id != BlockID.INFO_UPDATE) {
                log.info("Unable to find runtime id for {}", id);
                return getOrCreateRuntimeId(BlockID.INFO_UPDATE, 0);
            } else if (id == BlockID.INFO_UPDATE){
                throw new IllegalStateException("InfoUpdate state is missing!");
            }
        }
        return runtimeId;
    }

    public static int getOrCreateRuntimeId(int protocol, int id, int meta) {
        Int2IntMap legacyToRuntimeId = getLegacyToRuntimeIdMap(protocol);

        int legacyId = id << 6 | meta;
        int runtimeId = legacyToRuntimeId.get(legacyId);
        if (runtimeId == -1) {
            runtimeId = legacyToRuntimeId.get(id << 6);
            if (runtimeId == -1 && id != BlockID.INFO_UPDATE) {
                log.info("Unable to find runtime id for {}", id);
                return getOrCreateRuntimeId(protocol, BlockID.INFO_UPDATE, 0);
            } else if (id == BlockID.INFO_UPDATE){
                throw new IllegalStateException("InfoUpdate state is missing!");
            }
        }
        return runtimeId;
    }

    //TODO: check usages of this methods
    public static int getOrCreateRuntimeId(int legacyId) throws NoSuchElementException {
        return getOrCreateRuntimeId(legacyId >> 4, legacyId & 0xf);
    }

    public static int getLegacyFullId(int runtimeId) {
        return runtimeIdToLegacy.get(runtimeId);
    }

    private static Int2IntMap getLegacyToRuntimeIdMap(int protocol) {
        if (protocol == ProtocolInfo.PROTOCOL_589) {
            return legacyToRuntimeId_589;
        }
        return legacyToRuntimeId;
    }

    private static Int2IntMap getRuntimeIdToLegacyMap(int protocol) {
        if (protocol == ProtocolInfo.PROTOCOL_589) {
            return runtimeIdToLegacy_589;
        }
        return runtimeIdToLegacy;
    }

    private static String getFileName(int protocol) {
        if (protocol == ProtocolInfo.PROTOCOL_589) {
            return "runtime_block_states_589.dat";
        }
        return "runtime_block_states.dat";
    }
}
