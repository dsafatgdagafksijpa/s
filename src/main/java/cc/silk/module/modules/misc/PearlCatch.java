package cc.silk.module.modules.misc;

import cc.silk.event.impl.player.TickEvent;
import cc.silk.event.impl.input.HandleInputEvent;
import cc.silk.mixin.MinecraftClientAccessor;
import cc.silk.module.Category;
import cc.silk.module.Module;
import cc.silk.module.setting.BooleanSetting;
import cc.silk.module.setting.NumberSetting;
import cc.silk.utils.friend.FriendManager;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.projectile.thrown.EnderPearlEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.Hand;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.MathHelper;
import org.lwjgl.glfw.GLFW;

import java.util.Comparator;
import java.util.List;

/**
 * PearlCatch
 *
 * Toggle: V key (ignored when any screen is open — chat, inventory, etc.)
 *
 * Tracks ender pearls in the world (yours or enemies'), predicts their position
 * accounting for gravity, smoothly rotates the camera toward the predicted intercept
 * point, and auto-fires a wind charge when the crosshair is close enough.
 *
 * Smooth aim: speed-based — fast when far away, slows as it closes in.
 * Prediction: simulates pearl trajectory N ticks ahead (gravity + drag).
 */
public final class PearlCatch extends Module {

    // ── Settings ─────────────────────────────────────────────────────────────
    private final BooleanSetting catchOwnPearls   = new BooleanSetting("Catch Own Pearls",   true);
    private final BooleanSetting catchEnemyPearls = new BooleanSetting("Catch Enemy Pearls", false);
    private final NumberSetting  aimSpeed         = new NumberSetting("Aim Speed",      1.0, 15.0, 6.0,  0.5);
    private final NumberSetting  fireThreshold    = new NumberSetting("Fire FOV",       1.0, 30.0, 5.0,  0.5);
    private final NumberSetting  predictionTicks  = new NumberSetting("Predict Ticks",  1.0, 20.0, 6.0,  1.0);
    private final NumberSetting  maxRange         = new NumberSetting("Max Range",      5.0, 64.0, 32.0, 1.0);

    // ── Pearl physics constants ───────────────────────────────────────────────
    private static final double PEARL_DRAG        = 0.99;
    private static final double PEARL_GRAVITY     = 0.03;
    private static final double WIND_CHARGE_SPEED = 1.5; // blocks/tick, approx

    // ── State ─────────────────────────────────────────────────────────────────
    private EnderPearlEntity trackedPearl = null;
    private boolean          fired        = false;

    // Key debounce
    private boolean vKeyWasDown = false;

    public PearlCatch() {
        super("Pearl Catch", "Smoothly aims wind charge to catch ender pearls", -1, Category.MISC);
        this.addSettings(catchOwnPearls, catchEnemyPearls, aimSpeed, fireThreshold, predictionTicks, maxRange);
    }

    // ── Keybind: V toggles the module via HandleInputEvent ───────────────────

    @EventHandler
    private void onHandleInput(HandleInputEvent event) {
        if (mc.currentScreen != null) {
            vKeyWasDown = false;
            return;
        }
        boolean vDown = GLFW.glfwGetKey(mc.getWindow().getHandle(), GLFW.GLFW_KEY_V) == GLFW.GLFW_PRESS;
        if (vDown && !vKeyWasDown) {
            this.toggle();
        }
        vKeyWasDown = vDown;
    }

    // ── Main tick ─────────────────────────────────────────────────────────────

    @EventHandler
    private void onTick(TickEvent event) {
        if (isNull()) return;

        // 1. Find best pearl to track
        trackedPearl = findBestPearl();
        if (trackedPearl == null) {
            fired = false;
            return;
        }

        // 2. Predict where pearl will be when wind charge arrives
        Vec3d  eyePos      = mc.player.getEyePos();
        double distNow     = eyePos.distanceTo(trackedPearl.getPos());
        int    travelTicks = (int) Math.ceil(distNow / WIND_CHARGE_SPEED);
        int    simTicks    = Math.min(travelTicks, (int) predictionTicks.getValue());

        Vec3d predictedPos = simulatePearl(trackedPearl, simTicks);

        // 3. Compute angles to predicted position
        float[] targetAngles = getAnglesTo(eyePos, predictedPos);
        float targetYaw   = targetAngles[0];
        float targetPitch = targetAngles[1];

        float currentYaw   = mc.player.getYaw();
        float currentPitch = mc.player.getPitch();

        // 4. Speed-based smooth aim — fast when far, slows as it closes in
        float yawDelta    = wrapDegrees(targetYaw   - currentYaw);
        float pitchDelta  = wrapDegrees(targetPitch - currentPitch);
        float angularDist = (float) Math.sqrt(yawDelta * yawDelta + pitchDelta * pitchDelta);

        float speed = Math.min(angularDist, (float) aimSpeed.getValue() * (angularDist / 45f + 0.3f));
        speed = Math.max(speed, 0.3f);

        if (angularDist > 0.01f) {
            float ratio    = speed / angularDist;
            float newYaw   = currentYaw   + yawDelta   * ratio;
            float newPitch = MathHelper.clamp(currentPitch + pitchDelta * ratio, -90f, 90f);
            mc.player.setYaw(newYaw);
            mc.player.setPitch(newPitch);
        }

        // 5. Auto-fire when within Fire FOV threshold
        if (!fired && angularDist <= (float) fireThreshold.getValue()) {
            if (hasWindCharge() && !isWindChargeCoolingDown()) {
                fireWindCharge();
                fired = true;
            }
        }

        // Reset once pearl is gone
        if (!trackedPearl.isAlive()) {
            fired        = false;
            trackedPearl = null;
        }
    }

    // ── Pearl selection ───────────────────────────────────────────────────────

    private EnderPearlEntity findBestPearl() {
        if (mc.world == null || mc.player == null) return null;

        double maxRangeSq = maxRange.getValue() * maxRange.getValue();

        List<EnderPearlEntity> pearls = mc.world.getEntitiesByClass(
                EnderPearlEntity.class,
                mc.player.getBoundingBox().expand(maxRange.getValue()),
                pearl -> {
                    if (!pearl.isAlive()) return false;
                    if (pearl.squaredDistanceTo(mc.player) > maxRangeSq) return false;
                    boolean isOwn   = pearl.getOwner() == mc.player;
                    boolean isEnemy = isEnemyPearl(pearl);
                    if (isOwn   && catchOwnPearls.getValue())   return true;
                    if (isEnemy && catchEnemyPearls.getValue()) return true;
                    return false;
                }
        );

        if (pearls.isEmpty()) return null;

        // Own pearls take priority, then closest
        pearls.sort(Comparator
                .<EnderPearlEntity, Integer>comparing(p -> p.getOwner() == mc.player ? 0 : 1)
                .thenComparingDouble(p -> p.squaredDistanceTo(mc.player)));

        return pearls.get(0);
    }

    private boolean isEnemyPearl(EnderPearlEntity pearl) {
        if (pearl.getOwner() == null || pearl.getOwner() == mc.player) return false;
        if (!(pearl.getOwner() instanceof PlayerEntity owner)) return false;
        return !FriendManager.isFriend(owner.getUuid());
    }

    // ── Physics simulation ────────────────────────────────────────────────────

    /**
     * Simulates the pearl's trajectory for {@code ticks} ticks.
     * Mirrors Minecraft's ThrownItemEntity motion:
     *   vy -= gravity, velocity *= drag, position += velocity
     */
    private Vec3d simulatePearl(EnderPearlEntity pearl, int ticks) {
        double x  = pearl.getX(),          y  = pearl.getY(),          z  = pearl.getZ();
        double vx = pearl.getVelocity().x, vy = pearl.getVelocity().y, vz = pearl.getVelocity().z;

        for (int i = 0; i < ticks; i++) {
            vy -= PEARL_GRAVITY;
            vx *= PEARL_DRAG;
            vy *= PEARL_DRAG;
            vz *= PEARL_DRAG;
            x  += vx;
            y  += vy;
            z  += vz;
        }

        return new Vec3d(x, y, z);
    }

    // ── Angle helpers ─────────────────────────────────────────────────────────

    private float[] getAnglesTo(Vec3d from, Vec3d to) {
        double dx    = to.x - from.x;
        double dy    = to.y - from.y;
        double dz    = to.z - from.z;
        double horiz = Math.sqrt(dx * dx + dz * dz);
        float  yaw   = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90f;
        float  pitch = (float) -Math.toDegrees(Math.atan2(dy, horiz));
        return new float[]{ yaw, pitch };
    }

    private float wrapDegrees(float d) {
        d = d % 360f;
        if (d >= 180f)  d -= 360f;
        if (d < -180f)  d += 360f;
        return d;
    }

    // ── Wind charge helpers ───────────────────────────────────────────────────

    private boolean hasWindCharge() {
        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getStack(i).getItem() == Items.WIND_CHARGE) return true;
        }
        return mc.player.getOffHandStack().getItem() == Items.WIND_CHARGE;
    }

    private boolean isWindChargeCoolingDown() {
        return mc.player.getItemCooldownManager().isCoolingDown(new ItemStack(Items.WIND_CHARGE));
    }

    private void fireWindCharge() {
        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getStack(i).getItem() == Items.WIND_CHARGE) {
                int prev = mc.player.getInventory().selectedSlot;
                mc.player.getInventory().selectedSlot = i;
                mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
                mc.player.getInventory().selectedSlot = prev;
                return;
            }
        }
        if (mc.player.getOffHandStack().getItem() == Items.WIND_CHARGE) {
            mc.interactionManager.interactItem(mc.player, Hand.OFF_HAND);
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    public void onEnable() {
        trackedPearl = null;
        fired        = false;
        vKeyWasDown  = false;
    }

    @Override
    public void onDisable() {
        trackedPearl = null;
        fired        = false;
        vKeyWasDown  = false;
    }
}
