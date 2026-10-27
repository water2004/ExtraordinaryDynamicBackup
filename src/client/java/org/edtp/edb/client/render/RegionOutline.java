package org.edtp.edb.client.render;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import fi.dy.masa.malilib.interfaces.IRenderer;
import fi.dy.masa.malilib.render.MaLiLibPipelines;
import fi.dy.masa.malilib.render.RenderContext;
import fi.dy.masa.malilib.render.RenderUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderBuffers;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.util.profiling.ProfilerFiller;
import org.edtp.edb.region.RegionSelection;
import org.joml.Vector4f;

import java.util.function.Supplier;

/** Twelve lines per frame regardless of region size. Does not query blocks or load chunks. */
public final class RegionOutline implements IRenderer {
    private static RegionSelection selected;
    public static void show(RegionSelection selection) { selected = selection; }
    public static void clear() { selected = null; }
    public static RegionSelection selected() { return selected; }
    public Supplier<String> getProfilerSectionSupplier() { return () -> "edb:region_outline"; }
    @Override public void onRenderWorldLast(RenderTarget target, CameraRenderState camera, Frustum frustum,
            RenderBuffers buffers, GpuBufferSlice fog, Vector4f color, ProfilerFiller profiler) {
        var level = Minecraft.getInstance().level; var region = selected;
        if (level == null || region == null || !region.dimension().equals(level.dimension().identifier().toString())) return;
        var b = region.blocks(); var cam = RenderUtils.camPos();
        double[] x = {b.minX(), (double) b.maxX() + 1}, z = {b.minZ(), (double) b.maxZ() + 1};
        double[] y = {b.fullHeight() ? level.getMinY() : b.minY(), b.fullHeight() ? (double) level.getMaxY() + 1 : (double) b.maxY() + 1};
        try (RenderContext context = new RenderContext(() -> "edb:region_outline", MaLiLibPipelines.DEBUG_LINES_MASA_SIMPLE_NO_DEPTH_NO_CULL, 0)) {
            var buffer = context.getBuilder();
            for (int axis = 0; axis < 3; axis++) for (int i = 0; i < 2; i++) for (int j = 0; j < 2; j++) {
                double[] a = axis == 0 ? new double[]{x[0], y[i], z[j]} : axis == 1 ? new double[]{x[i], y[0], z[j]} : new double[]{x[i], y[j], z[0]};
                double[] end = a.clone(); end[axis] = axis == 0 ? x[1] : axis == 1 ? y[1] : z[1];
                for (double[] point : new double[][]{a, end}) buffer.addVertex((float) (point[0] - cam.x), (float) (point[1] - cam.y), (float) (point[2] - cam.z))
                        .setColor(0.2F, 0.95F, 1F, 1F).setLineWidth(2F);
            }
            var mesh = buffer.build();
            if (mesh != null) try (mesh) { context.draw(mesh, false, true); }
        } catch (Exception error) {
            selected = null;
            com.mojang.logging.LogUtils.getLogger().warn("EDB region outline disabled after rendering failure", error);
        }
    }
}
