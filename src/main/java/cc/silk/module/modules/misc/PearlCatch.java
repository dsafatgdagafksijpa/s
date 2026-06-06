package cc.silk.module.modules.misc;

import cc.silk.event.impl.player.TickEvent;
import cc.silk.mixin.MinecraftClientAccessor;
import cc.silk.module.Category;
import cc.silk.module.Module;
import cc.silk.module.setting.BooleanSetting;
import cc.silk.module.setting.NumberSetting;
import cc.silk.utils.friend.FriendManager;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.projectile.thrown.EnderPearlEntity;
import net.minecraft.entity.projectile.WindChargeEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.Hand;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.MathHelper;

import java.util.Comparator;
import java.util.List;

/**
 * PearlCatch
 *
 * Tracks ender pearls in the world (yours or enemies'), predicts their position
 * accounting for gravity, smoothly rotates the camera toward the predicted intercept
 * point, and auto-fires a wind charge when the crosshair is close enough.
 *
 * Smooth aim: speed-based lerp — fast when far away, slows as it closes in.
 * Prediction: simulates pearl trajectory N ticks ahead (gravity + drag).
 */
public final class PearlCatch extends Module {

    // ── Settings ─────────────────────────────────────────────────────────────
    private final BooleanSetting catchOwnPearls   = new BooleanSetting("Catch Own Pearls",   true);
    private final BooleanSetting catchEnemyPearls = new BooleanSetting("Catch Enemy Pearls", false);
    private final NumberSetting  aimSpeed         = new NumberSetting("Aim Speed",    1.0, 15.0, 6.0,  0.5);
    private final NumberSetting  fireThreshold    = new NumberSetting("Fire FOV",     1.0, 30.0, 5.0,  0.5);
    private final NumberSetting  predictionTicks  = new NumberSetting("Predict Ticks",1.0, 20.0, 6.0,  1.0);
    private final NumberSetting  maxRange         = new NumberSetting("Max Range",    5.0, 64.0, 32.0, 1.0);

    // ── Pearl physics constants (Minecraft internals) ─────────────────────────
    // Ender pearl drag per tick (applied after gravity)
    private static final double PEARL_DRAG    = 0.99;
    // Gravity applied to pearl per tick
    private static final double PEARL_GRAVITY = 0.03;

    // Wind charge travel speed (blocks/tick, approximate)
    // Used to estimate how many ticks until wind charge reaches the pearl
    private static final double WIND_CHARGE_SPEED = 1.5;

    // ── State ─────────────────────────────────────────────────────────────────
    private EnderPearlEntity trackedPearl = null;
    private boolean          fired        = false;

    public PearlCatch() {
        super("Pearl Catch", "Smoothly aims wind charge to catch ender pearls", -1, Category.MISC);
        this.addSettings(catchOwnPearls, catchEnemyPearls, aimSpeed, fireThreshold, predictionTicks, maxRange);
    }

    @EventHandler
    private void onTick(TickEvent event) {
        if (isNull()) return;

        // ── 1. Find best pearl to track ───────────────────────────────────────
        trackedPearl = findBestPearl();
        if (trackedPearl == null) {
            fired = false;
            return;
        }

        // ── 2. Predict where the pearl will be when wind charge arrives ────────
        Vec3d eyePos    = mc.player.getEyePos();
        double distNow  = eyePos.distanceTo(trackedPearl.getPos());

        // Estimate travel time of wind charge to reach approximately where pearl is
        int travelTicks = (int) Math.ceil(distNow / WIND_CHARGE_SPEED);
        // Clamp to user's prediction setting
        int simTicks    = Math.min(travelTicks, (int) predictionTicks.getValue());

        Vec3d predictedPos = simulatePearl(trackedPearl, simTicks);

        // ── 3. Compute angles from eye to predicted position ───────────────────
        float[] targetAngles = getAnglesTo(eyePos, predictedPos);
        float targetYaw   = targetAngles[0];
        float targetPitch = targetAngles[1];

        float currentYaw   = mc.player.getYaw();
        float currentPitch = mc.player.getPitch();

        // ── 4. Speed-based smooth aim ──────────────────────────────────────────
        // Angular distance to target
        float yawDelta   = wrapDegrees(targetYaw   - currentYaw);
        float pitchDelta = wrapDegrees(targetPitch - currentPitch);
        float angularDist = (float) Math.sqrt(yawDelta * yawDelta + pitchDelta * pitchDelta);

        // Speed scales with distance: fast when far, slows as it closes in
        // aimSpeed setting acts as the base multiplier
        float speed = Math.min(angularDist, (float) aimSpeed.getValue() * (angularDist / 45f + 0.3f));
        speed = Math.max(speed, 0.3f); // minimum movement so it always closes in

        if (angularDist > 0.01f) {
            float ratio      = speed / angularDist;
            float newYaw     = currentYaw   + yawDelta   * ratio;
            float newPitch   = currentPitch + pitchDelta * ratio;
            newPitch         = MathHelper.clamp(newPitch, -90f, 90f);

            mc.player.setYaw(newYaw);
            mc.player.setPitch(newPitch);
        }

        // ── 5. Auto-fire when crosshair is within fire threshold ───────────────
        if (!fired && angularDist <= (float) fireThreshold.getValue()) {
            if (hasWindCharge() && !isWindChargeCoolingDown()) {
                fireWindCharge();
                fired = true;
            }
        }

        // Reset fired flag once we lose the pearl (it was caught or despawned)
        if (!trackedPearl.isAlive()) {
            fired        = false;
            trackedPearl = null;
        }
    }

    // ── Pearl selection ───────────────────────────────────────────────────────

    private EnderPearlEntity findBestPearl() {
        if (mc.world == null || mc.player == null) return null;

        Vec3d eyePos = mc.player.getEyePos();
        double maxRangeSq = maxRange.getValue() * maxRange.getValue();

        List<EnderPearlEntity> pearls = mc.world.getEntitiesByClass(
                EnderPearlEntity.class,
                mc.player.getBoundingBox().expand(maxRange.getValue()),
                pearl -> {
                    if (!pearl.isAlive()) return false;
                    if (pearl.squaredDistanceTo(mc.player) > maxRangeSq) return false;

                    boolean isOwn    = pearl.getOwner() == mc.player;
                    boolean isEnemy  = isEnemyPearl(pearl);

                    if (isOwn    && catchOwnPearls.getValue())   return true;
                    if (isEnemy  && catchEnemyPearls.getValue()) return true;
                    return false;
                }
        );

        if (pearls.isEmpty()) return null;

        // Prioritise own pearls, then closest
        pearls.sort(Comparator
                .<EnderPearlEntity, Integer>comparing(p -> p.getOwner() == mc.player ? 0 : 1)
                .thenComparingDouble(p -> p.squaredDistanceTo(mc.player)));

        return pearls.get(0);
    }

    private boolean isEnemyPearl(EnderPearlEntity pearl) {
        if (pearl.getOwner() == null) return false;
        if (pearl.getOwner() == mc.player) return false;
        if (!(pearl.getOwner() instanceof PlayerEntity owner)) return false;
        return !FriendManager.isFriend(owner.getUuid());
    }

    // ── Physics simulation ────────────────────────────────────────────────────

    /**
     * Simulates the pearl's trajectory for {@code ticks} ticks.
     * Mirrors Minecraft's ThrownItemEntity motion logic:
     *   velocity.y -= gravity
     *   velocity   *= drag
     *   position   += velocity
     */
    private Vec3d simulatePearl(EnderPearlEntity pearl, int ticks) {
        double x  = pearl.getX();
        double y  = pearl.getY();
        double z  = pearl.getZ();
        double vx = pearl.getVelocity().x;
        double vy = pearl.getVelocity().y;
        double vz = pearl.getVelocity().z;

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

    /** Returns [yaw, pitch] in degrees from {@code from} to {@code to}. */
    private float[] getAnglesTo(Vec3d from, Vec3d to) {
        double dx    = to.x - from.x;
        double dy    = to.y - from.y;
        double dz    = to.z - from.z;
        double horiz = Math.sqrt(dx * dx + dz * dz);

        float yaw   = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90f;
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, horiz));

        return new float[]{ yaw, pitch };
    }

    /** Wraps an angle delta into [-180, 180]. */
    private float wrapDegrees(float degrees) {
        degrees = degrees % 360f;
        if (degrees >= 180f)  degrees -= 360f;
        if (degrees < -180f)  degrees += 360f;
        return degrees;
    }

    // ── Wind charge helpers ───────────────────────────────────────────────────

    private boolean hasWindCharge() {
        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getStack(i).getItem() == Items.WIND_CHARGE) return true;
        }
        // Also check off-hand
        return mc.player.getOffHandStack().getItem() == Items.WIND_CHARGE;
    }

    private boolean isWindChargeCoolingDown() {
        return mc.player.getItemCooldownManager()
                .isCoolingDown(new ItemStack(Items.WIND_CHARGE));
    }

    private void fireWindCharge() {
        // Switch to wind charge slot if not already holding one
        int windSlot = -1;
        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getStack(i).getItem() == Items.WIND_CHARGE) {
                windSlot = i;
                break;
            }
        }

        Hand hand = Hand.MAIN_HAND;

        if (windSlot != -1) {
            // Temporarily switch to wind charge slot
            int prev = mc.player.getInventory().selectedSlot;
            mc.player.getInventory().selectedSlot = windSlot;
            mc.interactionManager.interactItem(mc.player, hand);
            // Switch back immediately — pearl tracking will continue next tick
            mc.player.getInventory().selectedSlot = prev;
        } else if (mc.player.getOffHandStack().getItem() == Items.WIND_CHARGE) {
            // Wind charge is in off-hand
            mc.interactionManager.interactItem(mc.player, Hand.OFF_HAND);
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    public void onEnable() {
        trackedPearl = null;
        fired        = false;
    }

    @Override
    public void onDisable() {
        trackedPearl = null;
        fired        = false;
    }
}
