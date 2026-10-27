package org.edtp.edb.operation;

import org.edtp.edb.region.RegionSelection;
import org.edtp.edb.storage.SnapshotIO;
import org.edtp.edb.storage.SelectionMerge;
import org.edtp.edb.runtime.RuntimeStateCodec;
import org.edtp.edb.bridge.RegionAccess;

import com.mojang.logging.LogUtils;
import net.minecraft.SharedConstants;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.*;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;
import net.minecraft.world.level.chunk.storage.SimpleRegionStorage;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.util.ProblemReporter;
import org.slf4j.Logger;
import org.edtp.edb.adaptors.ModCompatibility;
import org.edtp.edb.ui.OperationMessages;


import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Main-thread coordinator. Futures are only joined after isDone; disk work never blocks a server frame. */
public final class RegionBackupService implements AutoCloseable {
    private static final Logger LOG = LogUtils.getLogger();
    private static final Map<MinecraftServer, RegionBackupService> SERVICES = new ConcurrentHashMap<>();
    private static final int BATCH = 32;
    private static final long FRAME_NANOS = 8_000_000L;
    private enum Phase { IDLE, INIT, SETTLE, LIGHT, CAPTURE, VALIDATE, PRESERVE, MASK, UNLOAD, DRAIN, APPLY, FLUSH, UNMASK, RELOAD, PUBLISH, CLEANUP, FAILED }
    private record Request(String name, String owner, String comment, RegionSelection region, boolean restore, SnapshotIO.Metadata expected, Consumer<String> reply) {
        String label() { return owner == null ? name : owner + " #" + name; }
    }
    private record Init(Path root, Path directory, SnapshotIO.Metadata metadata, CompoundTag runtime, Path preserved) {}
    private record BatchResult(long min, long max) {}
    private final MinecraftServer server;
    private final ExecutorService io = Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().name("edb-snapshot-io").factory());
    private final Queue<Runnable> deferred = new ConcurrentLinkedQueue<>();
    private volatile boolean active;
    private final java.util.concurrent.atomic.AtomicInteger pendingSigns = new java.util.concurrent.atomic.AtomicInteger();
    private volatile Phase phase = Phase.IDLE;
    private Request pending, request;
    private boolean catalogMutation;
    private ServerLevel level;
    private RegionSelection region;
    private Path directory, root;
    private Path preservedDirectory;
    private String completedResult;
    private SnapshotIO.Metadata metadata;
    private CompoundTag runtime;
    private CompletableFuture<?> work;
    private int cursor;
    private int batchOffset;
    private long lastProgress, minOrder, maxOrder, orderOffset;
    private Map<UUID, Long> entityOrder;
    private Map<Long, Long> blockEntityOrder;
    private final Set<UUID> restoredUuids = new HashSet<>(), currentUuids = new HashSet<>();
    private final Set<UUID> preservedUuids = new HashSet<>();
    private final Set<Long> originallyLoaded = new HashSet<>();
    private final List<CompletableFuture<?>> writes = new ArrayList<>();
    private volatile Throwable writeFailure;
    private boolean destructive, mask, validated, fakePlayersRestored;
    private ModCompatibility.PlayerRestore playerSession;
    private boolean boundaryPlayersRestored;
    private boolean boundaryPreserved;
    private String lastResult = "Idle";
    private long startedAt, frames, maxFrameNanos;
    private long lastReport;
    public record Metrics(long frames, long maxFrameNanos, long elapsedNanos) {}
    public Metrics metrics() { return new Metrics(frames, maxFrameNanos, System.nanoTime() - startedAt); }

    private RegionBackupService(MinecraftServer server) { this.server = server; }
    public static RegionBackupService get(MinecraftServer server) { return SERVICES.computeIfAbsent(server, RegionBackupService::new); }
    public static RegionBackupService find(MinecraftServer server) { return SERVICES.get(server); }
    public static boolean paused(MinecraftServer server) { var s = find(server); return s != null && s.active; }
    public static boolean maintenanceOnCurrentThread() {
        for (var service : SERVICES.values()) if (service.active && service.server.isSameThread()) return true;
        return false;
    }
    public boolean active() { return active; }
    public boolean preparing() { return active && phase == Phase.SETTLE; }
    public synchronized void acceptSign(Runnable handler) { if (!active) handler.run(); }
    /** Register before accepting the callback; serialized against entering maintenance. */
    public synchronized <T> CompletableFuture<Void> signUpdate(CompletableFuture<T> filter, Consumer<? super T> apply, Executor executor) {
        if (active) return CompletableFuture.completedFuture(null);
        pendingSigns.incrementAndGet();
        return filter.thenAcceptAsync(apply, executor).whenComplete((ignored, error) -> pendingSigns.decrementAndGet());
    }
    public boolean failed() { return phase == Phase.FAILED; }
    public void storageFailure(Throwable failure) { if (active) writeFailure = failure; }
    public void defer(Runnable task) { deferred.add(task); }
    public void requestBackup(String name, RegionSelection selection, Consumer<String> reply) { requestBackup(null, name, "", selection, reply); }
    public void requestBackup(String owner, String name, String comment, RegionSelection selection, Consumer<String> reply) {
        SnapshotIO.validateComment(comment);
        enqueue(new Request(name, owner, comment, selection, false, null, reply));
    }
    public void requestRestore(String name, Consumer<String> reply) { requestRestore(name, null, reply); }
    public void requestRestore(String name, SnapshotIO.Metadata expected, Consumer<String> reply) { requestRestore(null, name, expected, reply); }
    public void requestRestore(String owner, String name, SnapshotIO.Metadata expected, Consumer<String> reply) {
        enqueue(new Request(name, owner, "", null, true, expected, reply));
    }
    /** Main-thread reservation spans catalog IO and its callback, preventing deletion/publication races. */
    public void beginCatalogMutation() {
        if (busy()) throw new IllegalStateException("EDB 正在执行操作，请完成后重试");
        catalogMutation = true;
    }
    public void endCatalogMutation() { catalogMutation = false; }
    public boolean busy() { return active || pending != null || catalogMutation; }
    public record Progress(String name, boolean restore, String phase, int processed, int total, long elapsedSeconds) {}
    public Progress progressView() {
        Request current = active ? request : pending;
        return new Progress(current == null ? "" : current.label(), current != null && current.restore,
                active ? phase.name() : pending == null ? "IDLE" : "QUEUED", cursor,
                active && region != null ? region.size() : 0, active ? (System.nanoTime() - startedAt) / 1_000_000_000 : 0);
    }
    private void enqueue(Request next) {
        if (busy()) throw new IllegalStateException("Another EDB operation is running");
        org.edtp.edb.adaptors.ModCompatibility.verify();
        SnapshotIO.backup(SnapshotIO.scope(SnapshotIO.root(server), next.owner), next.name);
        pending = next;
    }
    public String status() { return active ? phase + " " + cursor + "/" + (region == null ? "?" : region.size()) + (phase == Phase.FAILED ? " — " + lastResult : "") : lastResult; }
    public synchronized void endTick() {
        if (active || pending == null) return;
        request = pending; pending = null; active = true; destructive = false; mask = false;
        preservedDirectory = null;
        region = request.region;
        validated = false; fakePlayersRestored = false; boundaryPlayersRestored = false; boundaryPreserved = false; playerSession = null;
        startedAt = System.nanoTime(); frames = 0; maxFrameNanos = 0;
        lastReport = startedAt;
        currentUuids.clear(); restoredUuids.clear(); originallyLoaded.clear(); writes.clear(); writeFailure = null;
        preservedUuids.clear();
        minOrder = Long.MAX_VALUE; maxOrder = Long.MIN_VALUE;
        transition(Phase.INIT);
        Request initializing = request;
        work = supply(() -> {
            Path root = SnapshotIO.root(server);
            String worldId = SnapshotIO.worldId(root);
            if (initializing.restore) {
                Path path = SnapshotIO.backup(SnapshotIO.scope(root, initializing.owner), initializing.name);
                var stored = SnapshotIO.readMetadata(path, worldId);
                if (initializing.expected != null && !initializing.expected.equals(stored))
                    throw new IllegalStateException("Backup metadata changed after preview; preview it again");
                var runtime = SnapshotIO.readRuntime(path);
                Path preserved = stored.region().wholeChunks() ? null : java.nio.file.Files.createTempDirectory(root, ".merge-");
                if (preserved != null) java.nio.file.Files.createDirectory(preserved.resolve("chunks"));
                return new Init(root, path, stored, runtime, preserved);
            }
            return new Init(root, SnapshotIO.begin(SnapshotIO.scope(root, initializing.owner), initializing.name), new SnapshotIO.Metadata(initializing.region, worldId, 0,
                    SharedConstants.getCurrentVersion().dataVersion().version()).withComment(initializing.comment), null, null);
        });
        OperationMessages.started(server, progressView());
        reply("EDB: simulation paused; preparing " + request.label());
    }
    /** Called instead of tickServer, while the outer server loop and watchdog remain live. */
    public void maintenanceFrame() {
        if (!active || phase == Phase.FAILED) return;
        long frameStart = System.nanoTime(); frames++;
        try {
            if (System.nanoTime() - lastReport > TimeUnit.SECONDS.toNanos(5)) {
                lastReport = System.nanoTime(); reply("EDB: " + status());
                OperationMessages.progress(server, progressView());
            }
            if (writeFailure != null) throw new IllegalStateException("World storage write failed", writeFailure);
            if (level != null) ModCompatibility.checkStorage(level);
            if (System.nanoTime() - lastProgress > TimeUnit.MINUTES.toNanos(5)) throw new IllegalStateException("No progress for five minutes in " + phase);
            switch (phase) {
                case INIT -> initialize();
                case SETTLE -> settle();
                case LIGHT -> light();
                case CAPTURE -> capture();
                case VALIDATE -> validate();
                case PRESERVE -> preserve();
                case MASK -> mask();
                case UNLOAD -> unload();
                case DRAIN -> drain();
                case APPLY -> apply();
                case FLUSH -> flush();
                case UNMASK -> unmask();
                case RELOAD -> reload();
                case PUBLISH -> { if (done()) finish("Backup complete: " + request.label() + " (" + region.size() + " chunks)"); }
                case CLEANUP -> { if (done()) { preservedDirectory = null; finish(completedResult); } }
                default -> {}
            }
        } catch (Exception failure) { fail(failure); }
        finally { maxFrameNanos = Math.max(maxFrameNanos, System.nanoTime() - frameStart); }
    }
    private void initialize() {
        if (!done()) return;
        org.edtp.edb.adaptors.ModCompatibility.verify();
        Init init = (Init) work.join(); root = init.root; directory = init.directory; metadata = init.metadata; runtime = init.runtime; preservedDirectory = init.preserved;
        region = metadata.region();
        level = null;
        for (ServerLevel candidate : server.getAllLevels())
            if (candidate.dimension().identifier().toString().equals(region.dimension())) level = candidate;
        if (level == null) throw new IllegalArgumentException("Dimension not available: " + region.dimension());
        if (region.blocks().maxY() < level.getMinY() || region.blocks().minY() > level.getMaxY()) throw new IllegalArgumentException("选区 Y 范围与维度高度没有交集");
        ModCompatibility.begin(level);
        if (request.restore) {
            ensurePlayersOutside();
            for (ServerPlayer player : server.getPlayerList().getPlayers()) player.closeContainer();
            org.edtp.edb.adaptors.ModCompatibility.beforeRestore(level);
        }
        transition(Phase.SETTLE);
    }
    private void ensurePlayersOutside() {
        for (ServerPlayer player : server.getPlayerList().getPlayers())
            if (player.level() == level && region.contains(player.chunkPosition())
                    && !ModCompatibility.snapshotPlayer(player))
                throw new IllegalArgumentException("请将真实玩家移出本次涉及的区块：" + player.getScoreboardName());
    }
    private RegionAccess.Chunks chunks() { return ModCompatibility.chunks(level); }
    private RegionAccess.Entities entities() { return (RegionAccess.Entities) ((RegionAccess.Level) level).edb$entities(); }
    private RegionAccess.Poi poi() { return (RegionAccess.Poi) level.getPoiManager(); }
    private RegionAccess.Tracker tracker() { return ModCompatibility.tracker(level); }
    private boolean entitiesLoaded(long key) { return ((RegionAccess.Level) level).edb$entities().areEntitiesLoaded(key); }
    private void pump() {
        long deadline = System.nanoTime() + FRAME_NANOS / 2;
        while (System.nanoTime() < deadline && level.getChunkSource().pollTask()) {}
        entities().edb$pump(); poi().edb$pump(); chunks().edb$unload(); chunks().edb$promote();
        level.getChunkSource().getLightEngine().tryScheduleUpdate();
    }
    private boolean holdersBusy() {
        if (ModCompatibility.customChunks()) return ModCompatibility.chunkBusy(level);
        for (ChunkHolder holder : chunks().edb$holders())
            if (((RegionAccess.Generation) holder).edb$busy() || !holder.getSaveSyncFuture().isDone()) return true;
        return false;
    }
    private void settle() {
        pump();
        // Vanilla block changes can enqueue POI updates on the general server queue.
        // Let the outer loop finish these while commands/packets remain gated, before taking the cut.
        if (server.getPendingTasksCount() != 0 || pendingSigns.get() != 0 || org.edtp.edb.adaptors.ModCompatibility.busy(server)) return;
        if (holdersBusy()) { cursor = 0; return; }
        long deadline = System.nanoTime() + FRAME_NANOS;
        for (int count = 0; cursor < region.size() && count < 128 && System.nanoTime() < deadline; count++) {
            ChunkPos pos = region.at(cursor); long key = pos.pack();
            if (chunks().edb$pending(key) || entities().edb$pending(key) || poi().edb$pending(key)) return;
            List<Entity> live = entities().edb$chunkEntities(key);
            if (!live.isEmpty() && !entitiesLoaded(key)) { entities().edb$request(key); return; }
            for (Entity entity : live) {
                if (entity instanceof Player && !ModCompatibility.snapshotPlayer(entity)) continue;
                Entity tree = entity.getRootVehicle();
                boolean selected = region.contains(tree.blockPosition());
                if (tree.getPassengersAndSelf().anyMatch(e -> region.contains(e.blockPosition()) != selected)
                        || selected && tree.getPassengersAndSelf().anyMatch(e -> e instanceof Player && !ModCompatibility.snapshotPlayer(e)))
                    throw new IllegalStateException("Passenger tree crosses the selection or includes a player: " + entity.getUUID());
                if (selected) tree.getPassengersAndSelf().forEach(member -> currentUuids.add(member.getUUID()));
            }
            ChunkHolder holder = chunks().edb$holder(key);
            if (holder != null) originallyLoaded.add(key);
            cursor++; progress();
        }
        if (cursor == region.size()) {
            if (!ModCompatibility.freeze(level)) return;
            // No gameplay, global tasks or chunk tasks will be pumped during the following capture/validation phases.
            work = ModCompatibility.customChunks() ? CompletableFuture.allOf(lightFence(), sync()) : lightFence(); transition(Phase.LIGHT);
        }
    }
    private CompletableFuture<?> lightFence() {
        List<CompletableFuture<?>> fences = new ArrayList<>();
        fences.add(org.edtp.edb.adaptors.scalablelux.ScalableLuxFence.fence(level));
        Set<Long> positions = new HashSet<>(originallyLoaded);
        for (ChunkHolder holder : chunks().edb$holders()) {
            positions.add(holder.getPos().pack());
        }
        for (long key : positions) {
            ChunkPos pos = ChunkPos.unpack(key);
            fences.add(level.getChunkSource().getLightEngine().waitForPendingTasks(pos.x(), pos.z()));
        }
        level.getChunkSource().getLightEngine().tryScheduleUpdate();
        return CompletableFuture.allOf(fences.toArray(CompletableFuture[]::new));
    }
    private void light() {
        level.getChunkSource().getLightEngine().tryScheduleUpdate();
        if (!done()) return;
        if (server.getPendingTasksCount() != 0) { ModCompatibility.preparing(level); work = null; transition(Phase.SETTLE); return; }
        if (request.restore) {
            if (validated) {
                ensurePlayersOutside();
                if (preservedDirectory == null || boundaryPreserved) startMask();
                else {
                    entityOrder = RuntimeStateCodec.entityOrder(level); blockEntityOrder = RuntimeStateCodec.blockEntityOrder(level);
                    transition(Phase.PRESERVE);
                }
            } else transition(Phase.VALIDATE);
        }
        else {
            metadata = new SnapshotIO.Metadata(region, metadata.worldId(), level.getGameTime(), metadata.dataVersion(), metadata.createdAt(), metadata.comment());
            runtime = RuntimeStateCodec.captureEvents(level, region);
            ModCompatibility.capturePlayers(level, region, runtime);
            entityOrder = RuntimeStateCodec.entityOrder(level); blockEntityOrder = RuntimeStateCodec.blockEntityOrder(level);
            transition(Phase.CAPTURE);
        }
        work = null;
    }
    private CompletableFuture<Optional<CompoundTag>> value(CompoundTag tag, SimpleRegionStorage storage, ChunkPos pos) {
        return tag == null ? storage.read(pos).thenApply(v -> v.map(CompoundTag::copy)) : CompletableFuture.completedFuture(Optional.of(tag));
    }
    private CompletableFuture<CompoundTag> captureChunk(ChunkPos pos) {
        ChunkHolder holder = chunks().edb$holder(pos.pack());
        ChunkAccess chunk = holder == null ? null : holder.getLatestChunk();
        if (chunk instanceof ImposterProtoChunk imposter) chunk = imposter.getWrapped();
        CompoundTag terrain = null;
        if (chunk != null) {
            if (!chunk.canBeSerialized()) throw new IllegalStateException("Chunk cannot yet be serialized: " + pos);
            terrain = SerializableChunkData.copyOf(level, chunk).write().copy();
            if (chunk instanceof LevelChunk full) RuntimeStateCodec.attachChunk(level, full, terrain, blockEntityOrder);
        }
        CompoundTag entityTag = entitiesLoaded(pos.pack()) ? captureEntities(pos) : null;
        var terrainFuture = value(terrain, level.getChunkSource().chunkMap, pos);
        var entityFuture = value(entityTag, entities().edb$storage(), pos);
        var poiFuture = value(poi().edb$snapshot(pos), poi().edb$storage(), pos);
        return CompletableFuture.allOf(terrainFuture, entityFuture, poiFuture).thenApply(ignored -> {
            CompoundTag record = new CompoundTag(); record.putInt("x", pos.x()); record.putInt("z", pos.z());
            put(record, "terrain", terrainFuture.join()); put(record, "entities", entityFuture.join()); put(record, "poi", poiFuture.join());
            return record;
        });
    }
    private static void put(CompoundTag record, String key, Optional<CompoundTag> value) {
        record.putBoolean(key + "_present", value.isPresent()); value.ifPresent(tag -> record.put(key, tag));
    }
    private CompoundTag captureEntities(ChunkPos pos) {
        ListTag list = new ListTag();
        try (ProblemReporter.ScopedCollector reporter = new ProblemReporter.ScopedCollector(LOG)) {
            for (Entity entity : entities().edb$chunkEntities(pos.pack())) {
                boolean fakeVehicle = !(entity instanceof Player) && !entity.isPassenger()
                        && entity.getPassengersAndSelf().anyMatch(ModCompatibility::snapshotPlayer);
                if (!entity.shouldBeSaved() && !fakeVehicle) continue;
                TagValueOutput output = TagValueOutput.createWithContext(reporter, level.registryAccess());
                if (entity.save(output)) {
                    CompoundTag tag = output.buildResult();
                    RuntimeStateCodec.attachEntity(entity, tag, level.getGameTime(), entityOrder); list.add(tag);
                }
            }
        }
        CompoundTag result = NbtUtils.addCurrentDataVersion(new CompoundTag());
        result.put("Entities", list); result.store("Position", ChunkPos.CODEC, pos); return result;
    }
    private void capture() {
        if (work != null) {
            if (!done()) return;
            BatchResult batch = (BatchResult) work.join(); minOrder = Math.min(minOrder, batch.min); maxOrder = Math.max(maxOrder, batch.max);
            work = null; progress();
        }
        if (cursor == region.size()) {
            runtime.putLong("min_order", minOrder); runtime.putLong("max_order", maxOrder);
            Path staging = directory, destination = SnapshotIO.backup(SnapshotIO.scope(root, request.owner), request.name);
            SnapshotIO.Metadata manifest = metadata; CompoundTag runtimeTag = runtime;
            work = supply(() -> { SnapshotIO.publish(staging, destination, manifest, runtimeTag); return null; });
            transition(Phase.PUBLISH); return;
        }
        List<CompletableFuture<CompoundTag>> batch = new ArrayList<>(); List<ChunkPos> positions = new ArrayList<>();
        long deadline = System.nanoTime() + FRAME_NANOS;
        do { ChunkPos pos = region.at(cursor++); positions.add(pos); batch.add(captureChunk(pos)); }
        while (cursor < region.size() && batch.size() < BATCH && System.nanoTime() < deadline);
        Path outputDirectory = directory; long capturedAt = metadata.gameTime();
        work = CompletableFuture.allOf(batch.toArray(CompletableFuture[]::new)).thenApplyAsync(ignored -> {
            long min = Long.MAX_VALUE, max = Long.MIN_VALUE;
            for (int i = 0; i < batch.size(); i++) {
                CompoundTag record = batch.get(i).join();
                RuntimeStateCodec.markStoredTimes(record, capturedAt);
                if (!region.coversChunk(positions.get(i))) SelectionMerge.trim(record, region);
                SelectionMerge.validateSnapshotEntities(record, region);
                Set<Long> orders = new HashSet<>(); RuntimeStateCodec.collectTickOrders(record.getCompoundOrEmpty("terrain"), orders);
                for (long order : orders) { min = Math.min(min, order); max = Math.max(max, order); }
                try { SnapshotIO.writeChunk(outputDirectory, positions.get(i), record); } catch (Exception e) { throw new CompletionException(e); }
            }
            return new BatchResult(min, max);
        }, io);
    }
    private void readBatch() {
        readBatch(false);
    }
    private void readBatch(boolean applying) {
        batchOffset = 0;
        int start = cursor, end = Math.min(region.size(), cursor + BATCH);
        Path inputDirectory = directory, preserved = preservedDirectory; RegionSelection selection = region;
        long offset = orderOffset;
        List<CompletableFuture<Optional<CompoundTag>>> currentTerrain = new ArrayList<>();
        if (!applying && !selection.wholeChunks()) for (int i = start; i < end; i++) {
            ChunkPos pos = selection.at(i);
            if (selection.coversChunk(pos)) { currentTerrain.add(CompletableFuture.completedFuture(Optional.empty())); continue; }
            var holder = chunks().edb$holder(pos.pack());
            ChunkAccess chunk = holder == null ? null : holder.getLatestChunk();
            if (chunk instanceof ImposterProtoChunk imposter) chunk = imposter.getWrapped();
            if (chunk == null) currentTerrain.add(level.getChunkSource().chunkMap.read(pos));
            else {
                if (!(chunk instanceof LevelChunk)) throw new IllegalArgumentException("边界区块仍在生成，无法精确恢复：" + pos);
                var full = new CompoundTag(); full.putString("Status", "minecraft:full");
                currentTerrain.add(CompletableFuture.completedFuture(Optional.of(full)));
            }
        }
        var records = supply(() -> {
            List<CompoundTag> result = new ArrayList<>();
            for (int index = start; index < end; index++) {
                ChunkPos pos = selection.at(index);
                var record = SnapshotIO.readChunk(inputDirectory, pos);
                if (applying) {
                    if (record.getBooleanOr("terrain_present", false)) RuntimeStateCodec.shiftTickOrders(record.getCompoundOrEmpty("terrain"), offset);
                    if (!selection.coversChunk(pos)) record = SelectionMerge.merge(record, SnapshotIO.readChunk(preserved, pos), selection);
                }
                result.add(record);
            }
            return result;
        });
        if (currentTerrain.isEmpty()) work = records;
        else work = records.thenCombine(CompletableFuture.allOf(currentTerrain.toArray(CompletableFuture[]::new)), (batch, ignored) -> {
            for (int i = 0; i < batch.size(); i++) {
                var pos = selection.at(start + i);
                if (selection.coversChunk(pos)) continue;
                var current = currentTerrain.get(i).join();
                if (current.isPresent()) SelectionMerge.requireFull(current.get(), pos);
                else if (batch.get(i).getBooleanOr("terrain_present", false))
                    throw new IllegalArgumentException("边界区块当前缺失，无法保留选区外地形：" + pos);
            }
            return batch;
        });
    }
    @SuppressWarnings("unchecked") private List<CompoundTag> batch() { return (List<CompoundTag>) work.join(); }
    private void validate() {
        if (work == null) { readBatch(); return; }
        if (!done()) return;
        List<CompoundTag> records = batch(); long deadline = System.nanoTime() + FRAME_NANOS;
        do {
            CompoundTag record = records.get(batchOffset++);
            ChunkPos pos = region.at(cursor++);
            if (record.getBooleanOr("terrain_present", false)) {
                CompoundTag terrain = record.getCompoundOrEmpty("terrain");
                if (!region.coversChunk(pos)) SelectionMerge.requireFull(terrain, pos);
                if (terrain.getIntOr("xPos", Integer.MIN_VALUE) != pos.x() || terrain.getIntOr("zPos", Integer.MIN_VALUE) != pos.z())
                    throw new IllegalArgumentException("Terrain position mismatch: " + pos);
                if (SerializableChunkData.parse(level, level.palettedContainerFactory(), terrain.copy()) == null)
                    throw new IllegalArgumentException("Invalid terrain data: " + pos);
                RuntimeStateCodec.validateTickData(terrain, pos, level.getGameTime());
                Set<Long> orders = new HashSet<>(); RuntimeStateCodec.collectTickOrders(terrain, orders);
                for (long order : orders) { minOrder = Math.min(minOrder, order); maxOrder = Math.max(maxOrder, order); }
                for (Tag raw : terrain.getListOrEmpty("entities")) validateEntity((CompoundTag) raw, pos, true);
            }
            var sections = record.getCompoundOrEmpty("poi").getCompoundOrEmpty("Sections");
            for (String key : sections.keySet())
                net.minecraft.world.entity.ai.village.poi.PoiSection.Packed.CODEC.parse(level.registryAccess().createSerializationContext(NbtOps.INSTANCE), sections.get(key)).getOrThrow();
            if (record.getBooleanOr("entities_present", false)) {
                var entityData = record.getCompoundOrEmpty("entities");
                if (!entityData.read("Position", ChunkPos.CODEC).orElseThrow().equals(pos)) throw new IllegalArgumentException("Entity storage position mismatch: " + pos);
                for (Tag raw : entityData.getListOrEmpty("Entities")) validateEntity((CompoundTag) raw, pos, true);
            }
        } while (batchOffset < records.size() && System.nanoTime() < deadline);
        progress();
        if (batchOffset < records.size()) return;
        work = null; progress();
        if (cursor == region.size()) {
            RuntimeStateCodec.validateEvents(region, runtime);
            long min = runtime.getLongOr("min_order", Long.MAX_VALUE), max = runtime.getLongOr("max_order", Long.MIN_VALUE);
            if (min != minOrder || max != maxOrder) throw new IllegalArgumentException("Tick order metadata does not match chunk records");
            orderOffset = 0;
            if (min <= max) {
                orderOffset = Math.subtractExact(level.nextSubTickCount(), min);
                ((RegionAccess.TickCounter) level).edb$reserve(Math.addExact(Math.addExact(max, orderOffset), 1));
            }
            // SETTLE drains work accepted before maintenance, including queued player transfers.
            ensurePlayersOutside();
            ModCompatibility.validatePlayers(level, region, runtime);
            playerSession = ModCompatibility.preparePlayers(level, region, runtime);
            destructive = true;
            ModCompatibility.removePlayers(level, region, runtime);
            validated = true;
            ModCompatibility.preparing(level);
            transition(Phase.SETTLE);
        }
    }
    private void validateEntity(CompoundTag tag, ChunkPos owner, boolean rootEntity) {
        var id = Identifier.parse(tag.getString("id").orElseThrow());
        if (!net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.containsKey(id) || id.toString().equals("minecraft:player"))
            throw new IllegalArgumentException("Unsupported entity type: " + id);
        var pos = tag.read("Pos", net.minecraft.world.phys.Vec3.CODEC).orElseThrow(() -> new IllegalArgumentException("Entity position missing"));
        if (!Double.isFinite(pos.x()) || !Double.isFinite(pos.y()) || !Double.isFinite(pos.z())) throw new IllegalArgumentException("Nonfinite entity position");
        if (!region.contains(net.minecraft.core.BlockPos.containing(pos))) throw new IllegalArgumentException("Entity outside exact selection");
        if (rootEntity && ChunkPos.pack(net.minecraft.core.BlockPos.containing(pos)) != owner.pack())
            throw new IllegalArgumentException("Root entity is outside its stored chunk: " + owner);
        UUID uuid = tag.read("UUID", UUIDUtil.CODEC).orElseThrow(() -> new IllegalArgumentException("Entity without UUID"));
        if (!restoredUuids.add(uuid)) throw new IllegalArgumentException("Duplicate UUID in backup: " + uuid);
        for (ServerLevel candidate : server.getAllLevels())
            if (((RegionAccess.Level) candidate).edb$entities().isLoaded(uuid) && !(candidate == level && currentUuids.contains(uuid)))
                throw new IllegalArgumentException("Entity UUID already loaded outside the region: " + uuid);
        for (Tag passenger : tag.getListOrEmpty("Passengers")) validateEntity((CompoundTag) passenger, owner, false);
    }
    private void startMask() {
        mask = true; tracker().edb$mask(region); transition(Phase.MASK);
    }
    /** Preserve current boundary chunks before unloading, including their runtime tick/entity fields. */
    private void preserve() {
        if (work != null) { if (!done()) return; work = null; progress(); }
        if (cursor == region.size()) {
            entityOrder = null; blockEntityOrder = null; boundaryPreserved = true;
            // Logout changes vanilla/NoTickVD tickets. Consume those changes before FlowSched
            // parks its root tickets; applying them to already parked roots removes them twice.
            if (playerSession.removeBoundaryPlayers()) {
                ModCompatibility.preparing(level); transition(Phase.SETTLE);
            } else startMask();
            return;
        }
        List<CompletableFuture<CompoundTag>> records = new ArrayList<>(); List<ChunkPos> positions = new ArrayList<>();
        long deadline = System.nanoTime() + FRAME_NANOS;
        do {
            ChunkPos pos = region.at(cursor++);
            if (!region.coversChunk(pos)) { positions.add(pos); records.add(captureChunk(pos)); }
        } while (cursor < region.size() && positions.size() < BATCH && System.nanoTime() < deadline);
        Path output = preservedDirectory, input = directory; long now = level.getGameTime();
        work = CompletableFuture.allOf(records.toArray(CompletableFuture[]::new)).thenRunAsync(() -> {
            for (int i = 0; i < records.size(); i++) {
                var record = records.get(i).join(); var pos = positions.get(i);
                RuntimeStateCodec.markStoredTimes(record, now);
                try {
                    var snapshot = SnapshotIO.readChunk(input, pos);
                    // These sets are stable (restoredUuids) or worker-owned (preservedUuids)
                    // until this serial preserve pass completes; no concurrent main-thread writes.
                    SelectionMerge.validateCurrentEntities(record, region, restoredUuids, preservedUuids);
                    if (record.getBooleanOr("terrain_present", false)) SelectionMerge.requireFull(record.getCompoundOrEmpty("terrain"), pos);
                    else if (snapshot.getBooleanOr("terrain_present", false)) throw new IllegalArgumentException("边界区块当前缺失，无法保留选区外地形：" + pos);
                    SnapshotIO.writeChunk(output, pos, record);
                } catch (Exception error) { throw new CompletionException(error); }
            }
        }, io);
    }
    private void mask() {
        int end = Math.min(region.size(), cursor + 128);
        while (cursor < end) tracker().edb$recheck(region.at(cursor++).pack());
        progress(); pump();
        if (cursor == region.size()) transition(Phase.UNLOAD);
    }
    private void unload() {
        pump();
        int end = Math.min(region.size(), cursor + 128);
        while (cursor < end) {
            long key = region.at(cursor).pack();
            if (!entities().edb$unloaded(key)) entities().edb$unload(key);
            if (chunks().edb$holder(key) != null || chunks().edb$pending(key) || !entities().edb$unloaded(key) || poi().edb$pending(key)) return;
            cursor++; progress();
        }
        if (cursor == region.size()) {
            if (!ModCompatibility.freeze(level)) return;
            work = CompletableFuture.allOf(sync(), lightFence()); transition(Phase.DRAIN);
        }
    }
    private CompletableFuture<Void> sync() {
        return CompletableFuture.allOf(level.getChunkSource().chunkMap.synchronize(true), entities().edb$storage().synchronize(true), poi().edb$storage().synchronize(true));
    }
    private void drain() {
        level.getChunkSource().getLightEngine().tryScheduleUpdate();
        if (!done()) return;
        for (CompletableFuture<?> write : writes) { if (!write.isDone()) return; write.join(); }
        writes.clear();
        ((RegionAccess.ChunkCache) level.getChunkSource()).edb$clearCache();
        ((RegionAccess.PathCache) level.getPathTypeCache()).edb$clear();
        ((RegionAccess.StructureCache) level).edb$invalidate(region);
        RuntimeStateCodec.restoreEvents(level, region, runtime);
        for (ServerPlayer player : level.players()) for (long key : originallyLoaded)
            if (player.getChunkTrackingView().contains(ChunkPos.unpack(key).x(), ChunkPos.unpack(key).z())) player.connection.chunkSender.dropChunk(player, ChunkPos.unpack(key));
        work = null; transition(Phase.APPLY);
    }
    private void apply() {
        if (work == null) { readBatch(true); return; }
        if (!done()) return;
        List<CompoundTag> batch = batch(); List<CompletableFuture<?>> saves = new ArrayList<>();
        destructive = true;
        for (CompoundTag record : batch) {
            ChunkPos pos = region.at(cursor++);
            poi().edb$invalidate(pos); entities().edb$invalidate(pos.pack()); chunks().edb$invalidate(pos.pack());
            CompoundTag terrain = payload(record, "terrain");
            saves.add(level.getChunkSource().chunkMap.write(pos, terrain));
            saves.add(entities().edb$storage().write(pos, payload(record, "entities")));
            saves.add(poi().edb$storage().write(pos, payload(record, "poi")));
        }
        work = CompletableFuture.allOf(saves.toArray(CompletableFuture[]::new)); transitionKeepingCursor(Phase.FLUSH); progress();
    }
    private static CompoundTag payload(CompoundTag record, String key) { return record.getBooleanOr(key + "_present", false) ? record.getCompoundOrEmpty(key) : null; }
    private void flush() {
        if (!done()) return;
        if (cursor < region.size()) { work = null; transitionKeepingCursor(Phase.APPLY); return; }
        work = sync(); transition(Phase.UNMASK);
    }
    private void unmask() {
        if (!done()) return;
        if (mask) org.edtp.edb.adaptors.ModCompatibility.blocksReplaced();
        tracker().edb$mask(null); mask = false;
        for (int end = Math.min(region.size(), cursor + 128); cursor < end; cursor++) tracker().edb$recheck(region.at(cursor).pack());
        progress();
        if (cursor == region.size() || !tracker().edb$batchBeforePump()) pump();
        if (cursor == region.size()) transition(Phase.RELOAD);
    }
    private void reload() {
        pump();
        if (holdersBusy()) return;
        if (!playerSession.ready()) return;
        for (long key : originallyLoaded) {
            ChunkHolder holder = chunks().edb$holder(key);
            if (holder != null && (!holder.getSaveSyncFuture().isDone() || entities().edb$pending(key))) return;
        }
        if (!fakePlayersRestored) {
            fakePlayersRestored = ModCompatibility.restoreNextPlayer(level, runtime);
            progress();
            return;
        }
        if (!boundaryPlayersRestored) {
            boundaryPlayersRestored = playerSession.restoreNext();
            progress(); return;
        }
        playerSession.restoreReferences(runtime);
        RuntimeStateCodec.ordersChanged(level); RuntimeStateCodec.installOrders(level);
        ModCompatibility.afterRestore(level, region);
        String result = "Restore complete: " + request.label() + " (" + region.size() + " chunks)";
        if (preservedDirectory == null) finish(result);
        else {
            completedResult = result; Path temporary = preservedDirectory;
            work = supply(() -> { cleanup(temporary); return null; }); transition(Phase.CLEANUP);
        }
    }
    /** Observe vanilla writes because synchronize alone can miss a previously failed write. */
    public void observeWrite(SimpleRegionStorage storage, CompletableFuture<?> future) {
        if (!active || level == null) return;
        if (storage == level.getChunkSource().chunkMap || storage == entities().edb$storage() || storage == poi().edb$storage()) {
            Request operation = request;
            future.whenComplete((v, error) -> { if (error != null && request == operation) writeFailure = error; });
            if (server.isSameThread()) { writes.removeIf(CompletableFuture::isDone); writes.add(future); }
        }
    }
    public static void observe(SimpleRegionStorage storage, CompletableFuture<?> future) {
        for (RegionBackupService service : SERVICES.values()) service.observeWrite(storage, future);
    }
    private boolean done() { if (work == null || !work.isDone()) return false; work.join(); return true; }
    private void transition(Phase phase) { this.phase = phase; cursor = 0; progress(); }
    private void transitionKeepingCursor(Phase phase) { this.phase = phase; }
    private void progress() { lastProgress = System.nanoTime(); }
    private void reply(String text) { LOG.info(text); request.reply.accept(text); }
    private void finish(String message) {
        if (preservedDirectory != null) {
            completedResult = message; Path temporary = preservedDirectory;
            work = supply(() -> { cleanup(temporary); return null; }); transition(Phase.CLEANUP); return;
        }
        lastResult = message; active = false; phase = Phase.IDLE; work = null; entityOrder = null; blockEntityOrder = null;
        if (playerSession != null) { playerSession.close(); playerSession = null; }
        ModCompatibility.end(level, destructive);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (ModCompatibility.snapshotPlayer(player)) player.connection.resetPosition();
            else ((RegionAccess.ClientReset) player.connection).edb$resetClient();
        }
        if (destructive) {
            ServerLevel restoredLevel = level; RegionSelection restoredRegion = region;
            server.schedule(new net.minecraft.server.TickTask(server.getTickCount(), () -> ((RegionAccess.StructureCache) restoredLevel).edb$invalidate(restoredRegion)));
        }
        reply(message);
        OperationMessages.finished(server, request.label(), request.restore, message.startsWith("EDB failed"), (System.nanoTime() - startedAt) / 1_000_000_000);
        LOG.info("EDB maintenance: {} frames, {} ms elapsed, longest frame {} ms", frames, (System.nanoTime() - startedAt) / 1_000_000, maxFrameNanos / 1_000_000);
        Runnable task; while ((task = deferred.poll()) != null) server.execute(task);
    }
    private void fail(Exception failure) {
        LOG.error("EDB operation failed in {}", phase, failure);
        lastResult = failure.toString();
        if (destructive || mask) {
            phase = Phase.FAILED;
            reply("EDB failed: " + lastResult + ". Simulation remains paused; inspect the log before stopping the server.");
            OperationMessages.failed(server);
        } else finish("EDB failed without applying a restore: " + lastResult);
    }
    private void cleanup(Path temporary) {
        try { SnapshotIO.discardMerge(root, temporary); }
        catch (Exception error) { LOG.warn("Operation finished but merge workspace could not be removed: {}", temporary, error); }
    }
    private <T> CompletableFuture<T> supply(CheckedSupplier<T> supplier) {
        return CompletableFuture.supplyAsync(() -> { try { return supplier.get(); } catch (Exception error) { throw new CompletionException(error); } }, io);
    }
    @FunctionalInterface private interface CheckedSupplier<T> { T get() throws Exception; }
    @Override public void close() { io.shutdown(); SERVICES.remove(server); org.edtp.edb.adaptors.ModCompatibility.close(server); for (ServerLevel level : server.getAllLevels()) RuntimeStateCodec.forget(level); }
}
