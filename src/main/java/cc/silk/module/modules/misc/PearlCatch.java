package cc.silk.module.modules.misc;

import cc.silk.event.impl.player.TickEvent;
import cc.silk.mixin.MinecraftClientAccessor;
import cc.silk.module.Category;
import cc.silk.module.Module;
import cc.silk.module.setting.KeybindSetting;
import cc.silk.module.setting.NumberSetting;
import cc.silk.utils.keybinding.KeyUtils;
import cc.silk.utils.math.TimerUtil;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.item.Items;
import org.lwjgl.glfw.GLFW;

public final class PearlCatch extends Module {

    private final KeybindSetting pearlChargeKeybind =
            new KeybindSetting("Pearl Charge Key", GLFW.GLFW_KEY_H, true);

    private final NumberSetting windDelay =
            new NumberSetting("Wind Delay", 0, 2000, 200, 1);

    private final TimerUtil pearlDelayTimer = new TimerUtil();

    private boolean keyPressed = false;
    private boolean pearlThrown = false;
    private int originalSlot = -1;

    public PearlCatch() {
        super("Pearl Catch", "Throws pearl then windcharge", -1, Category.MISC);
        this.addSettings(pearlChargeKeybind, windDelay);
        this.getSettings().removeIf(setting ->
                setting instanceof KeybindSetting && !setting.equals(pearlChargeKeybind));
    }

    @EventHandler
    private void onTickEvent(TickEvent event) {
        if (isNull() || mc.currentScreen != null) return;

        boolean currentKeyState = KeyUtils.isKeyPressed(pearlChargeKeybind.getKeyCode());

        if (currentKeyState && !keyPressed) {
            throwPearl();
        }

        if (pearlThrown && pearlDelayTimer.hasElapsedTime(windDelay.getValueInt())) {
            throwWindCharge();
            pearlThrown = false;
        }

        keyPressed = currentKeyState;
    }

    private void throwPearl() {
        int pearlSlot = findPearlSlot();
        if (pearlSlot == -1) return;

        if (mc.player.getItemCooldownManager().isCoolingDown(Items.ENDER_PEARL)) {
            return;
        }

        mc.player.getInventory().selectedSlot = pearlSlot;
        ((MinecraftClientAccessor) mc).invokeDoItemUse();

        pearlThrown = true;
        pearlDelayTimer.reset();
    }

    private void throwWindCharge() {
        int windChargeSlot = findWindChargeSlot();
        if (windChargeSlot == -1) return;

        if (mc.player.getItemCooldownManager().isCoolingDown(Items.WIND_CHARGE)) {
            return;
        }

        mc.player.getInventory().selectedSlot = windChargeSlot;
        ((MinecraftClientAccessor) mc).invokeDoItemUse();
    }

    private int findPearlSlot() {
        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getStack(i).getItem() == Items.ENDER_PEARL) {
                return i;
            }
        }
        return -1;
    }

    private int findWindChargeSlot() {
        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getStack(i).getItem() == Items.WIND_CHARGE) {
                return i;
            }
        }
        return -1;
    }

    @Override
    public void onEnable() {
        keyPressed = false;
        pearlThrown = false;
        originalSlot = -1;
        pearlDelayTimer.reset();
        super.onEnable();
    }

    @Override
    public void onDisable() {
        pearlThrown = false;
        super.onDisable();
    }

    @Override
    public int getKey() {
        return -1;
    }
}
