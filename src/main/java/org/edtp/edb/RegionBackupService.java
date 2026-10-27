package org.edtp.edb;

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
    private enum Phase { IDLE, INIT, SETTLE, LIGHT, CAPTURE, VALIDATE, MASK, UNLOAD, DRAIN, APPLY, FLUSH, UNMASK, RELOAD, PUBLISH, FAILED }
    private record Request(String name, RegionSelection region, boolean restore, Consumer<String> reply) {}
    private record Init(Path root, Path directory, SnapshotIO.Metadata metadata, CompoundTag runtime) {}
    private record BatchResult(long min, long max) {}
    private final MinecraftServer server;
    private final ExecutorService io = Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().name("edb-snapshot-io").factory());
    private final Queue<Runnable> deferred = new ConcurrentLinkedQueue<>();
    private volatile boolean active;
    private final java.util.concurrent.atomic.AtomicInteger pendingSigns = new java.util.concurrent.atomic.AtomicInteger();
    private volatile Phase phase = Phase.IDLE;
    private Request pending, request;
    private ServerLevel level;
    private RegionSelection region;
    private Path directory, root;
    private SnapshotIO.Metadata metadata;
    private CompoundTag runtime;
    private CompletableFuture<?> work;
    private int cursor;
    private int batchOffset;
    private long lastProgress, minOrder, maxOrder, orderOffset;
    private Map<UUID, Long> entityOrder;
    private Map<Long, Long> blockEntityOrder;
    private final Set<UUID> restoredUuids = new HashSet<>(), currentUuids = new HashSet<>();
    private final Set<Long> originallyLoaded = new HashSet<>();
    private final List<CompletableFuture<?>> writes = new ArrayList<>();
    private volatile Throwable writeFailure;
    private boolean destructive, mask, validated, fakePlayersRestored;
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
    public void requestBackup(String name, RegionSelection selection, Consumer<String> reply) { enqueue(new Request(name, selection, false, reply)); }
    public void requestRestore(String name, Consumer<String> reply) { enqueue(new Request(name, null, true, reply)); }
    public void list(Consumer<String> reply) {
        Path snapshots = SnapshotIO.root(server);
        supply(() -> SnapshotIO.list(snapshots)).whenComplete((names, error) -> server.execute(() ->
                reply.accept(error == null ? (names.isEmpty() ? "No backups" : String.join(", ", names)) : "Cannot list backups: " + error)));
    }
    private void enqueue(Request next) {
        if (active || pending != null) throw new IllegalStateException("Another EDB operation is running");
        org.edtp.edb.adaptors.ModCompatibility.verify();
        SnapshotIO.backup(SnapshotIO.root(server), next.name);
        pending = next;
    }
    public String status() { return active ? phase + " " + cursor + "/" + (region == null ? "?" : region.size()) + (phase == Phase.FAILED ? " — " + lastResult : "") : lastResult; }
    public synchronized void endTick() {
        if (active || pending == null) return;
        request = pending; pending = null; active = true; destructive = false; mask = false;
        validated = false; fakePlayersRestored = false;
        startedAt = System.nanoTime(); frames = 0; maxFrameNanos = 0;
        lastReport = startedAt;
        currentUuids.clear(); restoredUuids.clear(); originallyLoaded.clear(); writes.clear(); writeFailure = null;
        minOrder = Long.MAX_VALUE; maxOrder = Long.MIN_VALUE;
        transition(Phase.INIT);
        Request initializing = request;
        work = supply(() -> {
            Path root = SnapshotIO.root(server);
            String worldId = SnapshotIO.worldId(root);
            if (initializing.restore) {
                Path path = SnapshotIO.backup(root, initializing.name);
                return new Init(root, path, SnapshotIO.readMetadata(path, worldId), SnapshotIO.readRuntime(path));
            }
            return new Init(root, SnapshotIO.begin(root, initializing.name), new SnapshotIO.Metadata(initializing.region, worldId, 0,
                    SharedConstants.getCurrentVersion().dataVersion().version()), null);
        });
        reply("EDB: simulation paused; preparing " + request.name);
    }
    /** Called instead of tickServer, while the outer server loop and watchdog remain live. */
    public void maintenanceFrame() {
        if (!active || phase == Phase.FAILED) return;
        long frameStart = System.nanoTime(); frames++;
        try {
            if (System.nanoTime() - lastReport > TimeUnit.SECONDS.toNanos(5)) {
                lastReport = System.nanoTime(); reply("EDB: " + status());
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
                case MASK -> mask();
                case UNLOAD -> unload();
                case DRAIN -> drain();
                case APPLY -> apply();
                case FLUSH -> flush();
                case UNMASK -> unmask();
                case RELOAD -> reload();
                case PUBLISH -> { if (done()) finish("Backup complete: " + request.name + " (" + region.size() + " chunks)"); }
                default -> {}
            }
        } catch (Exception failure) { fail(failure); }
        finally { maxFrameNanos = Math.max(maxFrameNanos, System.nanoTime() - frameStart); }
    }
    private void initialize() {
        if (!done()) return;
        org.edtp.edb.adaptors.ModCompatibility.verify();
        Init init = (Init) work.join(); root = init.root; directory = init.directory; metadata = init.metadata; runtime = init.runtime;
        region = metadata.region();
        level = null;
        for (ServerLevel candidate : server.getAllLevels())
            if (candidate.dimension().identifier().toString().equals(region.dimension())) level = candidate;
        if (level == null) throw new IllegalArgumentException("Dimension not available: " + region.dimension());
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
            if (!ModCompatibility.snapshotPlayer(player) && player.level() == level && region.contains(player.chunkPosition()))
                throw new IllegalArgumentException("Move players outside the restoration region first: " + player.getScoreboardName());
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
                if (!region.contains(tree.chunkPosition()) || tree.getPassengersAndSelf().anyMatch(e -> e instanceof Player && !ModCompatibility.snapshotPlayer(e)))
                    throw new IllegalStateException("Passenger tree crosses the selection or includes a player: " + entity.getUUID());
                tree.getPassengersAndSelf().forEach(member -> currentUuids.add(member.getUUID()));
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
                mask = true; tracker().edb$mask(region); transition(Phase.MASK);
            } else transition(Phase.VALIDATE);
        }
        else {
            metadata = new SnapshotIO.Metadata(region, metadata.worldId(), level.getGameTime(), metadata.dataVersion());
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
            Path staging = directory, destination = SnapshotIO.backup(root, request.name);
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
                Set<Long> orders = new HashSet<>(); RuntimeStateCodec.collectTickOrders(record.getCompoundOrEmpty("terrain"), orders);
                for (long order : orders) { min = Math.min(min, order); max = Math.max(max, order); }
                try { SnapshotIO.writeChunk(outputDirectory, positions.get(i), record); } catch (Exception e) { throw new CompletionException(e); }
            }
            return new BatchResult(min, max);
        }, io);
    }
    private void readBatch() {
        batchOffset = 0;
        int start = cursor, end = Math.min(region.size(), cursor + BATCH);
        Path inputDirectory = directory; RegionSelection selection = region;
        work = supply(() -> {
            List<CompoundTag> result = new ArrayList<>();
            for (int index = start; index < end; index++) result.add(SnapshotIO.readChunk(inputDirectory, selection.at(index)));
            return result;
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
        if (rootEntity && ChunkPos.pack(net.minecraft.core.BlockPos.containing(pos)) != owner.pack())
            throw new IllegalArgumentException("Root entity is outside its stored chunk: " + owner);
        UUID uuid = tag.read("UUID", UUIDUtil.CODEC).orElseThrow(() -> new IllegalArgumentException("Entity without UUID"));
        if (!restoredUuids.add(uuid)) throw new IllegalArgumentException("Duplicate UUID in backup: " + uuid);
        for (ServerLevel candidate : server.getAllLevels())
            if (((RegionAccess.Level) candidate).edb$entities().isLoaded(uuid) && !(candidate == level && currentUuids.contains(uuid)))
                throw new IllegalArgumentException("Entity UUID already loaded outside the region: " + uuid);
        for (Tag passenger : tag.getListOrEmpty("Passengers")) validateEntity((CompoundTag) passenger, owner, false);
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
        if (work == null) { readBatch(); return; }
        if (!done()) return;
        List<CompoundTag> batch = batch(); List<CompletableFuture<?>> saves = new ArrayList<>();
        destructive = true;
        for (CompoundTag record : batch) {
            ChunkPos pos = region.at(cursor++);
            poi().edb$invalidate(pos); entities().edb$invalidate(pos.pack()); chunks().edb$invalidate(pos.pack());
            CompoundTag terrain = payload(record, "terrain");
            if (terrain != null) RuntimeStateCodec.shiftTickOrders(terrain, orderOffset);
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
        for (long key : originallyLoaded) {
            ChunkHolder holder = chunks().edb$holder(key);
            if (holder != null && (!holder.getSaveSyncFuture().isDone() || entities().edb$pending(key))) return;
        }
        if (!fakePlayersRestored) {
            fakePlayersRestored = ModCompatibility.restoreNextPlayer(level, runtime);
            progress();
            return;
        }
        RuntimeStateCodec.ordersChanged(level); RuntimeStateCodec.installOrders(level);
        ModCompatibility.afterRestore(level, region);
        finish("Restore complete: " + request.name + " (" + region.size() + " chunks)");
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
        lastResult = message; active = false; phase = Phase.IDLE; work = null; entityOrder = null; blockEntityOrder = null;
        ModCompatibility.end(level, destructive);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) ((RegionAccess.ClientReset) player.connection).edb$resetClient();
        if (destructive) {
            ServerLevel restoredLevel = level; RegionSelection restoredRegion = region;
            server.schedule(new net.minecraft.server.TickTask(server.getTickCount(), () -> ((RegionAccess.StructureCache) restoredLevel).edb$invalidate(restoredRegion)));
        }
        reply(message);
        LOG.info("EDB maintenance: {} frames, {} ms elapsed, longest frame {} ms", frames, (System.nanoTime() - startedAt) / 1_000_000, maxFrameNanos / 1_000_000);
        Runnable task; while ((task = deferred.poll()) != null) server.execute(task);
    }
    private void fail(Exception failure) {
        LOG.error("EDB operation failed in {}", phase, failure);
        lastResult = failure.toString();
        if (destructive || mask) {
            phase = Phase.FAILED;
            reply("EDB failed: " + lastResult + ". Simulation remains paused; inspect the log before stopping the server.");
        } else finish("EDB failed without applying a restore: " + lastResult);
    }
    private <T> CompletableFuture<T> supply(CheckedSupplier<T> supplier) {
        return CompletableFuture.supplyAsync(() -> { try { return supplier.get(); } catch (Exception error) { throw new CompletionException(error); } }, io);
    }
    @FunctionalInterface private interface CheckedSupplier<T> { T get() throws Exception; }
    @Override public void close() { io.shutdown(); SERVICES.remove(server); org.edtp.edb.adaptors.ModCompatibility.close(server); for (ServerLevel level : server.getAllLevels()) RuntimeStateCodec.forget(level); }
}
