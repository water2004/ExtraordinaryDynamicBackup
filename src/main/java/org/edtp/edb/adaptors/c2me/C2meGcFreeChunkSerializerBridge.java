package org.edtp.edb.adaptors.c2me;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import org.edtp.edb.bridge.RegionAccess;
import org.edtp.edb.runtime.RuntimeStateCodec;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/** Reflection-only bridge to C2ME's optional, allocation-reduced NBT writer. */
public final class C2meGcFreeChunkSerializerBridge {
    private static final String WRITER_CLASS = "com.ishland.c2me.rewrites.chunk_serializer.common.NbtWriter";
    private static final ClassValue<WriterMethods> METHODS = new ClassValue<>() {
        @Override
        protected WriterMethods computeValue(Class<?> type) {
            if (!type.getName().equals(WRITER_CLASS)) {
                throw new IllegalStateException("Unexpected C2ME chunk serializer writer: " + type.getName());
            }
            try {
                return new WriterMethods(
                        type.getMethod("getStringBytes", String.class),
                        type.getMethod("putElement", byte[].class, Tag.class)
                );
            } catch (ReflectiveOperationException exception) {
                throw new IllegalStateException("Unsupported C2ME gcFreeChunkSerializer NBT writer API", exception);
            }
        }
    };

    private C2meGcFreeChunkSerializerBridge() {}

    public static void append(Object serializedChunk, Object writer) {
        if (!(serializedChunk instanceof RegionAccess.RuntimeChunk runtimeChunk)) {
            throw new IllegalStateException("C2ME serialized chunk is missing EDB's runtime NBT bridge");
        }
        CompoundTag runtime = runtimeChunk.edb$pending();
        if (runtime == null) return;

        WriterMethods methods = METHODS.get(writer.getClass());
        try {
            byte[] key = (byte[]) methods.stringBytes.invoke(null, RuntimeStateCodec.KEY);
            methods.putElement.invoke(writer, key, runtime.copy());
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtimeException) throw runtimeException;
            if (cause instanceof Error error) throw error;
            throw new IllegalStateException("C2ME failed to append EDB runtime NBT", cause);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Cannot append EDB runtime NBT to C2ME serializer", exception);
        }
    }

    private record WriterMethods(Method stringBytes, Method putElement) {}
}
