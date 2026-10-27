package org.edtp.edb.client.gui;

import fi.dy.masa.malilib.gui.GuiBase;
import net.minecraft.client.Minecraft;
import org.edtp.edb.client.ManagementClient;
import org.edtp.edb.protocol.ManagementPayloads;

/** Keep the user's draft until the common server handler acknowledges this operation kind. */
abstract class CommandForm extends GuiBase implements EdbScreen {
    protected boolean submitting;
    private long version, sentAt;
    private ManagementPayloads.Result expected;
    protected void submit(String command, ManagementPayloads.Result expected) {
        var client = ManagementClient.INSTANCE; long before = client.feedbackVersion;
        if (!client.command(command)) return;
        this.expected = expected; version = before; sentAt = System.nanoTime(); submitting = true; initGui();
    }
    @Override public void tick() {
        if (!submitting) return;
        var client = ManagementClient.INSTANCE;
        if (client.failureVersion > version || !client.allowed()) { submitting = false; initGui(); }
        else if (client.resultVersion(expected) > version) { submitting = false; Minecraft.getInstance().gui.setScreen(getParent()); }
        else if (System.nanoTime() - sentAt > 15_000_000_000L) {
            submitting = false; client.notice("未收到操作结果", true); initGui();
        }
    }
}
