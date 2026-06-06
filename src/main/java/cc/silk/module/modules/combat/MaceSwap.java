package cc.silk.module.modules.combat;

import cc.silk.event.impl.player.AttackEntityEvent;
import cc.silk.event.impl.player.TickEvent;
import cc.silk.event.impl.input.KeyboardEvent;
import cc.silk.mixin.MinecraftClientAccessor;
import cc.silk.module.Category;
import cc.silk.module.Module;
import cc.silk.module.modules.misc.Teams;
import cc.silk.module.setting.BooleanSetting;
import cc.silk.module.setting.NumberSetting;
import cc.silk.utils.friend.FriendManager;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.AxeItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import org.lwjgl.glfw.GLFW;

/**
 * MaceSwap
 *
 * Toggle: C key (ignored when any screen is open — chat, inventory, etc.)
 *
 * On left-click attack:
 *   Normal target  → swap to best mace (Density if fell >= threshold, else Breach)
 *                    → hit → swap to sword
 *
 *   Shielding target (semi-auto, two clicks):
 *     Click 1 → swap to axe → hit (shield break)
 *     Click 2 → swap to Density mace → hit → swap to sword
 *
 * All swaps are tick-delayed (swapDelay ticks between each step).
 */
public final class MaceSwap extends Module {

    // ── Settings ────────────────────────────────────────────────────────────
    private final NumberSetting  fallThreshold  = new NumberSetting("Fall Threshold",      1.0, 20.0, 3.0, 0.5);
    private final NumberSetting  swapDelay      = new NumberSetting("Swap Delay (ticks)",  1,   10,   2,   1);
    private final BooleanSetting targetPlayers  = new BooleanSetting("Target Players",     true);
    private final BooleanSetting targetMobs     = new BooleanSetting("Target Mobs",        false);

    // ── State machine ────────────────────────────────────────────────────────
    /**
     * IDLE       – waiting for a click
     * AXE_SWAP   – ticking down before switching to axe
     * AXE_HIT    – firing the axe hit this tick
     * MACE_SWAP  – ticking down before switching to mace
     * MACE_HIT   – firing the mace hit this tick
     * SWORD_SWAP – ticking down before switching back to sword, then IDLE
     */
    private enum State { IDLE, AXE_SWAP, AXE_HIT, MACE_SWAP, MACE_HIT, SWORD_SWAP }

    private State  state        = State.IDLE;
    private int    tickCounter  = 0;
    private Entity pendingTarget = null;

    /** True after click 1 on a shielding player — waiting for click 2 */
    private boolean awaitingSecondClick = false;

    // Track local player fall distance
    private double  fallStartY = -1;
    private boolean isFalling  = false;

    public MaceSwap() {
        super("Mace Swap", "Smart mace/axe swap on left-click", -1, Category.COMBAT);
        this.addSettings(fallThreshold, swapDelay, targetPlayers, targetMobs);
    }

    // ── Keybind: C toggles the module (skipped when any screen is open) ───

    @EventHandler
    private void onKey(KeyboardEvent event) {
        // mc.currentScreen != null means chat / inventory / any GUI is open — ignore
        if (mc.currentScreen != null) return;
        if (event.key == GLFW.GLFW_KEY_C && event.action == GLFW.GLFW_PRESS) {
            this.toggle();
        }
    }

    // ── Tick handler ─────────────────────────────────────────────────────────

    @EventHandler
    private void onTick(TickEvent event) {
        if (isNull()) return;

        trackFall();
        tickStateMachine();
    }

    private void trackFall() {
        boolean onGround = mc.player.isOnGround();
        double  currentY = mc.player.getY();
        double  velY     = mc.player.getVelocity().y;

        if (onGround) {
            isFalling  = false;
            fallStartY = currentY; // reset baseline to ground level each landing
            return;
        }

        if (!isFalling && velY < -0.1) {
            isFalling  = true;
            fallStartY = currentY;
        }
    }

    private double getFallDistance() {
        if (fallStartY == -1) return 0;
        return Math.max(0, fallStartY - mc.player.getY());
    }

    // ── Attack event (left-click interception) ────────────────────────────────

    @EventHandler
    private void onAttack(AttackEntityEvent event) {
        if (isNull()) return;

        Entity target = event.entity;
        if (!isValidTarget(target)) return;
        if (FriendManager.isFriend(target.getUuid())) return;

        boolean targetShielding = isShielding(target);

        if (targetShielding) {
            if (!awaitingSecondClick) {
                // Click 1: cancel vanilla swing, start axe swap sequence
                event.cancel();
                pendingTarget       = target;
                awaitingSecondClick = true;
                startState(State.AXE_SWAP);
            } else {
                // Click 2: cancel vanilla swing, start mace swap sequence
                event.cancel();
                pendingTarget       = target;
                awaitingSecondClick = false;
                startState(State.MACE_SWAP);
            }
        } else {
            // Normal target: cancel vanilla swing, go straight to mace
            event.cancel();
            pendingTarget       = target;
            awaitingSecondClick = false;
            startState(State.MACE_SWAP);
        }
    }

    // ── State machine tick ────────────────────────────────────────────────────

    private void tickStateMachine() {
        if (state == State.IDLE) return;

        tickCounter--;
        if (tickCounter > 0) return; // still counting down

        switch (state) {
            case AXE_SWAP -> {
                int axeSlot = findAxeSlot();
                if (axeSlot != -1) mc.player.getInventory().selectedSlot = axeSlot;
                startState(State.AXE_HIT);
            }
            case AXE_HIT -> {
                if (pendingTarget != null && isValidTarget(pendingTarget)) {
                    ((MinecraftClientAccessor) mc).invokeDoAttack();
                }
                // Return to IDLE — player must click again for the mace hit
                state       = State.IDLE;
                tickCounter = 0;
            }
            case MACE_SWAP -> {
                int maceSlot = findBestMaceSlot(getFallDistance());
                if (maceSlot != -1) mc.player.getInventory().selectedSlot = maceSlot;
                startState(State.MACE_HIT);
            }
            case MACE_HIT -> {
                if (pendingTarget != null && isValidTarget(pendingTarget)) {
                    ((MinecraftClientAccessor) mc).invokeDoAttack();
                }
                pendingTarget = null;
                startState(State.SWORD_SWAP);
            }
            case SWORD_SWAP -> {
                int swordSlot = findSwordSlot();
                if (swordSlot != -1) mc.player.getInventory().selectedSlot = swordSlot;
                state       = State.IDLE;
                tickCounter = 0;
            }
            default -> {
                state       = State.IDLE;
                tickCounter = 0;
            }
        }
    }

    private void startState(State next) {
        state       = next;
        tickCounter = (int) swapDelay.getValue();
    }

    // ── Item finders ──────────────────────────────────────────────────────────

    private int findAxeSlot() {
        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getStack(i).getItem() instanceof AxeItem) return i;
        }
        return -1;
    }

    /**
     * Density if fell >= threshold, Breach otherwise.
     * Falls back to the other enchant, then any mace, if preferred isn't found.
     */
    private int findBestMaceSlot(double fallDist) {
        boolean preferDensity = fallDist >= fallThreshold.getValueFloat();
        int preferred = preferDensity ? findDensityMaceSlot() : findBreachMaceSlot();
        if (preferred != -1) return preferred;
        int fallback  = preferDensity ? findBreachMaceSlot()  : findDensityMaceSlot();
        if (fallback  != -1) return fallback;
        return findAnyMaceSlot();
    }

    private int findDensityMaceSlot() {
        for (int i = 0; i < 9; i++) {
            ItemStack s = mc.player.getInventory().getStack(i);
            if (s.getItem() == Items.MACE && hasEnchant(s, "density")) return i;
        }
        return -1;
    }

    private int findBreachMaceSlot() {
        for (int i = 0; i < 9; i++) {
            ItemStack s = mc.player.getInventory().getStack(i);
            if (s.getItem() == Items.MACE && hasEnchant(s, "breach")) return i;
        }
        return -1;
    }

    private int findAnyMaceSlot() {
        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getStack(i).getItem() == Items.MACE) return i;
        }
        return -1;
    }

    private int findSwordSlot() {
        for (int i = 0; i < 9; i++) {
            if (isSword(mc.player.getInventory().getStack(i))) return i;
        }
        return -1;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private boolean hasEnchant(ItemStack stack, String keyword) {
        return stack.getEnchantments().getEnchantments().stream()
                .anyMatch(e -> e.getIdAsString().contains(keyword));
    }

    private boolean isShielding(Entity entity) {
        return entity instanceof PlayerEntity player
                && player.isHolding(Items.SHIELD)
                && player.isBlocking();
    }

    private boolean isSword(ItemStack stack) {
        return stack.getItem() == Items.WOODEN_SWORD
                || stack.getItem() == Items.STONE_SWORD
                || stack.getItem() == Items.IRON_SWORD
                || stack.getItem() == Items.GOLDEN_SWORD
                || stack.getItem() == Items.DIAMOND_SWORD
                || stack.getItem() == Items.NETHERITE_SWORD;
    }

    private boolean isValidTarget(Entity entity) {
        if (entity == null || entity == mc.player || entity == mc.cameraEntity) return false;
        if (!(entity instanceof LivingEntity living)) return false;
        if (!living.isAlive() || living.isDead()) return false;
        if (Teams.isTeammate(entity)) return false;
        if (entity instanceof PlayerEntity) return targetPlayers.getValue();
        return targetMobs.getValue();
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    public void onEnable() {
        resetAll();
    }

    @Override
    public void onDisable() {
        // Best-effort sword return when toggled off mid-sequence
        int swordSlot = findSwordSlot();
        if (swordSlot != -1) mc.player.getInventory().selectedSlot = swordSlot;
        resetAll();
    }

    private void resetAll() {
        state               = State.IDLE;
        tickCounter         = 0;
        pendingTarget       = null;
        awaitingSecondClick = false;
        fallStartY          = -1;
        isFalling           = false;
    }
}
