package org.edtp.edb.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import org.edtp.edb.client.ManagementClient;
import org.edtp.edb.protocol.ManagementPayloads;

/** Async command acknowledgement shared by the native input dialog and region form. */
final class CommandSubmission {
    private boolean pending;
    private long version, sentAt;
    private ManagementPayloads.Result expected;
    boolean pending() { return pending; }
    boolean submit(String command, ManagementPayloads.Result expected) {
        if (pending) return false;
        var client = ManagementClient.INSTANCE; long before = client.feedbackVersion;
        if (!client.command(command)) return false;
        this.expected = expected; version = before; sentAt = System.nanoTime(); pending = true;
        return true;
    }
    void tick(Screen parent, Runnable update) {
        if (!pending) return;
        var client = ManagementClient.INSTANCE;
        if (client.failureVersion > version || !client.allowed()) { pending = false; update.run(); }
        else if (client.resultVersion(expected) > version) { pending = false; Minecraft.getInstance().gui.setScreen(parent); }
        else if (System.nanoTime() - sentAt > 15_000_000_000L) {
            pending = false; client.notice("未收到操作结果", true); update.run();
        }
    }
}
