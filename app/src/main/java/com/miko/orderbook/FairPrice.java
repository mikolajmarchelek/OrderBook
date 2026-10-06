package com.miko.orderbook;
import java.util.Random;

public class FairPrice {
    private final Random random;
    private final double sigmaTicks; //volotility per step
    private double value; //current fair value in ticks

    public FairPrice(double startTicks, double sigmaTicks, Random random) {
        if (startTicks <= 0) {
        throw new IllegalArgumentException("start price must be > 0: " + startTicks);
        }
        if (sigmaTicks < 0) {
        throw new IllegalArgumentException("sigma must be >= 0: " + sigmaTicks);
        }
        this.value = startTicks;
        this.sigmaTicks = sigmaTicks;
        this.random = random;
    }

    // Advance one step: value += sigma * Z. Returns the new value.
    public double step() { 
        double z = random.nextGaussian();
        value += sigmaTicks * z;
        value = Math.max(value, 1.0);
        return value;
    }

    public double getValue() { return value; }

    // Nearest whole tick, for placing real orders.
    public long roundedTicks() {
        return Math.round(value);
    }   
}
