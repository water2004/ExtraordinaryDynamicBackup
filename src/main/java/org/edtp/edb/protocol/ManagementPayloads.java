package org.edtp.edb.protocol;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** V1 is read-only. All mutations continue to use the ordinary Brigadier command packets. */
public final class ManagementPayloads {
    private ManagementPayloads() {}
    private static <T extends CustomPacketPayload> CustomPacketPayload.Type<T> type(String name) {
        return new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("extraordinarydynamicbackup", name + "_v1"));
    }
    public record Request(int sequence, boolean catalog, String region, int regionPage, int backupPage) implements CustomPacketPayload {
        public static final Type<Request> TYPE = ManagementPayloads.type("management_request");
        public static final StreamCodec<RegistryFriendlyByteBuf, Request> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Request::sequence, ByteBufCodecs.BOOL, Request::catalog,
                ByteBufCodecs.stringUtf8(64), Request::region, ByteBufCodecs.VAR_INT, Request::regionPage,
                ByteBufCodecs.VAR_INT, Request::backupPage, Request::new);
        public Type<Request> type() { return TYPE; }
    }
    public record State(int sequence, CompoundTag data) implements CustomPacketPayload {
        public static final Type<State> TYPE = ManagementPayloads.type("management_state");
        public static final StreamCodec<RegistryFriendlyByteBuf, State> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, State::sequence, ByteBufCodecs.COMPOUND_TAG, State::data, State::new);
        public Type<State> type() { return TYPE; }
    }
    public enum Result { NONE, REGION_SAVED, BACKUP_QUEUED, COMMENT_SAVED }
    public record Feedback(String text, boolean error, Result result) implements CustomPacketPayload {
        public static final Type<Feedback> TYPE = ManagementPayloads.type("management_feedback");
        public static final StreamCodec<RegistryFriendlyByteBuf, Feedback> CODEC = StreamCodec.composite(
                ByteBufCodecs.stringUtf8(2048), Feedback::text, ByteBufCodecs.BOOL, Feedback::error,
                ByteBufCodecs.VAR_INT.map(id -> Result.values()[id], Result::ordinal), Feedback::result, Feedback::new);
        public Type<Feedback> type() { return TYPE; }
    }
    public record Open() implements CustomPacketPayload {
        public static final Type<Open> TYPE = ManagementPayloads.type("management_open");
        public static final StreamCodec<RegistryFriendlyByteBuf, Open> CODEC = StreamCodec.unit(new Open());
        public Type<Open> type() { return TYPE; }
    }
    public static void register() {
        PayloadTypeRegistry.serverboundPlay().register(Request.TYPE, Request.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(State.TYPE, State.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(Feedback.TYPE, Feedback.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(Open.TYPE, Open.CODEC);
    }
}
