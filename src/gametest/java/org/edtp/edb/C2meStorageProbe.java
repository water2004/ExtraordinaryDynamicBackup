package org.edtp.edb;

import org.edtp.edb.runtime.RuntimeStateCodec;
import org.edtp.edb.bridge.RegionAccess;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;
import net.minecraft.world.level.chunk.storage.SimpleRegionStorage;
import org.edtp.edb.adaptors.c2me.C2MEStorageFailures;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/** Optional manually-invoked GameTest probes for the C2ME storage and serializer adapters. */
final class C2meStorageProbe {
    private static long storageDeadline() { return System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(30); }
    private static final String C2ME_MOD = "c2me-rewrites-chunkio";
    private C2meStorageProbe() {}

    /** Call at the end of the C2ME-only compatibility GameTest; never waits on the server thread. */
    static void probeSwallowedStorageFailure(GameTestHelper test, Runnable next) {
        if (!FabricLoader.getInstance().isModLoaded(C2ME_MOD)) {
            next.run();
            return;
        }

        final Path tempRoot;
        final Path blocker;
        final SimpleRegionStorage storage;
        try {
            tempRoot = Files.createTempDirectory("edb-c2me-storage-probe-");
            blocker = tempRoot.resolve("region-folder-is-a-file");
            Files.writeString(blocker, "not a directory");
            storage = new SimpleRegionStorage(
                    new RegionStorageInfo("edb-c2me-probe", test.getLevel().dimension(), "probe"),
                    blocker,
                    DataFixers.getDataFixer(),
                    true,
                    DataFixTypes.CHUNK
            );
        } catch (Exception setupFailure) {
            test.assertTrue(false, "Could not create isolated C2ME storage probe: " + setupFailure);
            return;
        }

        long baseline = C2MEStorageFailures.epoch();
        ChunkPos position = new ChunkPos(123_457, -89_123);
        CompoundTag value = new CompoundTag();
        value.putInt("edb_probe", 1);
        CompletableFuture<Void> write;
        CompletableFuture<Void> sync;
        try {
            write = storage.write(position, value);
            sync = storage.synchronize(true);
        } catch (Throwable enqueueFailure) {
            closeProbe(test, storage, tempRoot, blocker, "Storage write failed before reaching C2ME's async error handler: " + enqueueFailure, next);
            return;
        }

        CompletableFuture<Void> fence = CompletableFuture.allOf(write, sync);
        await(test, fence, storageDeadline(), () -> {
            String problem = null;
            C2MEStorageFailures.Failure observedFailure = C2MEStorageFailures.failureAfter(baseline);
            if (write.isCompletedExceptionally()) {
                problem = "C2ME storage write acknowledgement completed exceptionally: " + failureOf(write);
            } else if (sync.isCompletedExceptionally()) {
                problem = "C2ME synchronize future did not swallow the injected disk failure: " + failureOf(sync);
            } else if (observedFailure == null) {
                problem = "C2ME synchronize completed without an observed storage failure; the failure mixin may be missing or the invalid path did not reach the backend";
            } else if (!"write".equals(observedFailure.operation()) || !observedFailure.message().contains(position.toString())) {
                problem = "Observed C2ME storage failure did not match the isolated probe chunk " + position + ": "
                        + observedFailure;
            }
            closeProbe(test, storage, tempRoot, blocker, problem, next);
        }, () -> closeProbe(test, storage, tempRoot, blocker, "Timed out waiting for isolated C2ME write/synchronize futures", next));
    }

    /**
     * Optional synchronous probe for the gcFree serializer config. Returns false when C2ME's
     * serializer module is absent or disabled; when enabled, throws if the extension fails
     * to survive the real serializer and vanilla parse path.
     */
    static boolean probeGcFreeSerializerRuntimeRoundTrip(GameTestHelper test, LevelChunk chunk) {
        if (!FabricLoader.getInstance().isModLoaded("c2me-rewrites-chunk-serializer")) return false;
        try {
            Class<?> module = Class.forName("com.ishland.c2me.rewrites.chunk_serializer.ModuleEntryPoint");
            if (!module.getField("enabled").getBoolean(null)) return false;

            RegionAccess.RuntimeChunk runtimeChunk = (RegionAccess.RuntimeChunk) chunk;
            CompoundTag previous = runtimeChunk.edb$pending();
            CompoundTag marker = new CompoundTag();
            String nonce = UUID.randomUUID().toString();
            marker.putString("probe_nonce", nonce);
            runtimeChunk.edb$pending(marker.copy());
            try {
                ServerLevel level = test.getLevel();
                SerializableChunkData serialized = SerializableChunkData.copyOf(level, chunk);
                CompoundTag encoded = serializeWithC2me(serialized);
                SerializableChunkData parsed = SerializableChunkData.parse(level, level.palettedContainerFactory(), encoded.copy());
                if (parsed == null || !((Object) parsed instanceof RegionAccess.RuntimeChunk)) {
                    throw new AssertionError("C2ME serializer output did not parse as a runtime chunk snapshot");
                }
                RegionAccess.RuntimeChunk parsedRuntime = (RegionAccess.RuntimeChunk) (Object) parsed;
                CompoundTag actual = parsedRuntime.edb$pending();
                if (actual == null || !actual.getStringOr("probe_nonce", "").equals(nonce)) {
                    throw new AssertionError("C2ME gcFree serializer lost " + RuntimeStateCodec.KEY + " during serialize/parse round trip");
                }
                return true;
            } finally {
                runtimeChunk.edb$pending(previous);
            }
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause();
            throw new AssertionError("C2ME gcFree serializer probe failed", cause == null ? exception : cause);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("Unsupported C2ME gcFree serializer probe API", exception);
        } catch (IOException exception) {
            throw new AssertionError("Could not decode C2ME gcFree serializer output", exception);
        }
    }

    private static CompoundTag serializeWithC2me(SerializableChunkData serialized) throws ReflectiveOperationException, IOException {
        Class<?> access = Class.forName("com.ishland.c2me.base.common.registry.SerializerAccess");
        Object serializer = access.getMethod("getSerializer").invoke(null);
        Class<?> contract = Class.forName("com.ishland.c2me.base.common.registry.SerializerAccess$Serializer");
        Method serialize = Arrays.stream(contract.getMethods())
                .filter(method -> method.getName().equals("serialize")
                        && method.getParameterCount() == 1
                        && method.getParameterTypes()[0].isInstance(serialized))
                .findFirst()
                .orElseThrow(() -> new NoSuchMethodException("SerializerAccess.Serializer.serialize(SerializedChunk)"));
        Object either = serialize.invoke(serializer, serialized);
        Optional<?> left = (Optional<?>) either.getClass().getMethod("left").invoke(either);
        if (left.isPresent()) return (CompoundTag) left.get();
        Optional<?> right = (Optional<?>) either.getClass().getMethod("right").invoke(either);
        byte[] raw = (byte[]) right.orElseThrow(() -> new IllegalStateException("C2ME serializer returned no NBT output"));
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(raw))) {
            return NbtIo.read(input, NbtAccounter.unlimitedHeap());
        }
    }

    private static void closeProbe(GameTestHelper test, SimpleRegionStorage storage, Path tempRoot, Path blocker,
                                   String priorProblem, Runnable next) {
        CompletableFuture<Void> close = CompletableFuture.runAsync(() -> {
            try {
                storage.close();
            } catch (IOException exception) {
                throw new CompletionException(exception);
            }
        });
        await(test, close, storageDeadline(), () -> {
            String problem = priorProblem;
            if (close.isCompletedExceptionally()) {
                String closeProblem = "Isolated C2ME storage close failed: " + failureOf(close);
                problem = problem == null ? closeProblem : problem + "; " + closeProblem;
            } else {
                try {
                    Files.deleteIfExists(blocker);
                    Files.deleteIfExists(tempRoot);
                } catch (IOException cleanupFailure) {
                    String cleanupProblem = "Could not remove isolated C2ME storage probe directory: " + cleanupFailure;
                    problem = problem == null ? cleanupProblem : problem + "; " + cleanupProblem;
                }
            }
            test.assertTrue(problem == null, problem == null ? "" : problem);
            next.run();
        }, () -> test.assertTrue(false, "Timed out closing isolated C2ME storage probe; temporary directory retained at " + tempRoot));
    }

    private static void await(GameTestHelper test, CompletableFuture<?> future, long deadline,
                              Runnable completed, Runnable timedOut) {
        if (future.isDone()) {
            completed.run();
        } else if (System.nanoTime() >= deadline) {
            timedOut.run();
        } else {
            test.runAfterDelay(1, () -> await(test, future, deadline, completed, timedOut));
        }
    }

    private static Throwable failureOf(CompletableFuture<?> future) {
        try {
            future.join();
            return null;
        } catch (CompletionException exception) {
            return exception.getCause() == null ? exception : exception.getCause();
        }
    }
}
