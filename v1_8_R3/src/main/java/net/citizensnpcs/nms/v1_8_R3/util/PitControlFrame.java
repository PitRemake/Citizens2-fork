package net.citizensnpcs.nms.v1_8_R3.util;

/** One reusable player-input frame. It never retains a Bukkit entity or task. */
public final class PitControlFrame {
    private boolean pending;
    private int submittedTick;
    private float yaw, pitch, strafe, forward;
    private boolean sprint, jump;

    public boolean submit(int tick, float yaw, float pitch, float strafe, float forward,
            boolean sprint, boolean jump) {
        clear();
        if (!Float.isFinite(yaw) || !Float.isFinite(pitch) || !Float.isFinite(strafe) || !Float.isFinite(forward))
            return false;
        this.yaw = wrap(yaw);
        this.pitch = Math.max(-90F, Math.min(90F, pitch));
        this.strafe = Math.max(-1F, Math.min(1F, strafe));
        this.forward = Math.max(-1F, Math.min(1F, forward));
        double length = Math.sqrt(this.strafe * this.strafe + this.forward * this.forward);
        if (length > 1D) {
            this.strafe /= length;
            this.forward /= length;
        }
        // Forward-diagonal (W+A/W+D) travel can sprint; orbit/backpedal stay walking.
        this.sprint = sprint && this.forward > 0F && Math.abs(this.strafe) <= this.forward + 1.0E-6F;
        this.jump = jump;
        submittedTick = tick;
        pending = true;
        return true;
    }

    /** Scheduler-before-world and scheduler-after-world ordering both admit one following tick. */
    public boolean consume(int tick) {
        int age = tick - submittedTick;
        boolean accepted = pending && age >= 0 && age <= 1;
        pending = false;
        return accepted;
    }
    public void clear() {
        pending = false;
        strafe = forward = 0F;
        sprint = jump = false;
    }
    public float yaw() { return yaw; }
    public float pitch() { return pitch; }
    public float strafe() { return strafe; }
    public float forward() { return forward; }
    public boolean sprint() { return sprint; }
    public boolean jump() { return jump; }
    private static float wrap(float angle) {
        float wrapped = angle % 360F;
        if (wrapped >= 180F) wrapped -= 360F;
        if (wrapped < -180F) wrapped += 360F;
        return wrapped;
    }
}
