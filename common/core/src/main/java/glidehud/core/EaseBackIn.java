package glidehud.core;

/**
 * Port of Glide's EaseBackIn animation (used for the target HUD appear / disappear).
 * Keeps the original timer semantics, including the reversed-time sampling used when
 * the direction is BACKWARDS.
 */
public final class EaseBackIn {

    public enum Direction {
        FORWARDS,
        BACKWARDS
    }

    private final int duration;
    private final double endPoint;
    private final float easeAmount;
    private long lastMs;
    private Direction direction;

    public EaseBackIn(int ms, double endPoint, float easeAmount) {
        this.duration = ms;
        this.endPoint = endPoint;
        this.easeAmount = easeAmount;
        this.direction = Direction.FORWARDS;
        this.lastMs = System.currentTimeMillis();
    }

    public void reset() {
        this.lastMs = System.currentTimeMillis();
    }

    public void setDirection(Direction newDirection) {
        if (this.direction != newDirection) {
            this.direction = newDirection;
            long elapsed = elapsed();
            this.lastMs = System.currentTimeMillis() - ((long) this.duration - Math.min((long) this.duration, elapsed));
        }
    }

    public Direction getDirection() {
        return this.direction;
    }

    private long elapsed() {
        long value = System.currentTimeMillis() - this.lastMs;
        return value < 0L ? 0L : value;
    }

    private boolean done() {
        return this.elapsed() >= (long) this.duration;
    }

    private double equation(double x) {
        double x1 = x / (double) this.duration;
        float shrink = this.easeAmount + 1.0F;
        return Math.max(0.0, 1.0 + (double) shrink * Math.pow(x1 - 1.0, 3.0)
                + (double) this.easeAmount * Math.pow(x1 - 1.0, 2.0));
    }

    public double getValue() {
        if (this.direction == Direction.FORWARDS) {
            if (this.done()) {
                return this.endPoint;
            }
            return this.equation(this.elapsed()) * this.endPoint;
        }
        if (this.done()) {
            return 0.0;
        }
        double revTime = Math.min((long) this.duration, Math.max(0L, (long) this.duration - this.elapsed()));
        return this.equation(revTime) * this.endPoint;
    }

    public float getValueFloat() {
        return (float) this.getValue();
    }
}
