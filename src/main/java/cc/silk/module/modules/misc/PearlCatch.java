package cc.silk.module.modules.misc;

import cc.silk.module.Category;
import cc.silk.module.Module;
import cc.silk.module.setting.BooleanSetting;
import cc.silk.utils.friend.FriendManager;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;

public final class PearlCatch extends Module {

    private final BooleanSetting autoCatch = new BooleanSetting("Auto Catch", true);

    public PearlCatch() {
        super("Pearl Catch", "Automatically catches pearls", -1, Category.MISC);
        this.addSettings(autoCatch);
    }

    @Override
    public void onTick() {
        if (!autoCatch.getValue() || mc.player == null) return;

        // Check if Ender Pearl is on cooldown
        if (mc.player.getItemCooldownManager().isCoolingDown(new ItemStack(Items.ENDER_PEARL))) {
            return;
        }

        // Check if Wind Charge (or equivalent) is on cooldown
        if (mc.player.getItemCooldownManager().isCoolingDown(new ItemStack(Items.WIND_CHARGE))) {
            return;
        }

        // Example: Loop through nearby players to catch pearls
        for (PlayerEntity player : mc.world.getPlayers()) {
            if (player == mc.player) continue;
            if (FriendManager.isFriend(player.getUuid())) continue;

            // Your pearl catching logic here
            // e.g., check if pearl is near player and throw to catch
        }
    }
}
