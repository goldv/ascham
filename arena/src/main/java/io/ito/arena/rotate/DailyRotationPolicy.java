package io.ito.arena.rotate;

/** Rotates on the UTC day boundary: one segment per table per trading day (the v1 default). */
public final class DailyRotationPolicy implements RotationPolicy {

    @Override
    public boolean shouldRotate(Context context) {
        return !context.today().equals(context.currentDay());
    }
}
