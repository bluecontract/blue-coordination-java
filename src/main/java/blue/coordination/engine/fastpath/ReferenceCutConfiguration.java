package blue.coordination.engine.fastpath;

import java.util.Objects;

/** Immutable, environment-bound policy for sparse-Root compilation. */
public final class ReferenceCutConfiguration {
    public static final long DEFAULT_MAXIMUM_CACHE_WEIGHT_BYTES =
            64L * 1024L * 1024L;
    public static final double DEFAULT_MINIMUM_NODE_REDUCTION = 0.15d;

    private final ReferenceCutMode mode;
    private final long maximumCacheWeightBytes;
    private final double minimumNodeReduction;
    private final int maximumCuts;

    public ReferenceCutConfiguration(
            ReferenceCutMode mode,
            long maximumCacheWeightBytes,
            double minimumNodeReduction,
            int maximumCuts) {
        this.mode = Objects.requireNonNull(mode, "mode");
        if (maximumCacheWeightBytes <= 0L) {
            throw new IllegalArgumentException(
                    "maximumCacheWeightBytes must be positive");
        }
        if (!Double.isFinite(minimumNodeReduction)
                || minimumNodeReduction < 0.0d
                || minimumNodeReduction >= 1.0d) {
            throw new IllegalArgumentException(
                    "minimumNodeReduction must be in [0, 1)");
        }
        if (maximumCuts <= 0) {
            throw new IllegalArgumentException("maximumCuts must be positive");
        }
        this.maximumCacheWeightBytes = maximumCacheWeightBytes;
        this.minimumNodeReduction = minimumNodeReduction;
        this.maximumCuts = maximumCuts;
    }

    public static ReferenceCutConfiguration disabled() {
        return new ReferenceCutConfiguration(
                ReferenceCutMode.DISABLED,
                DEFAULT_MAXIMUM_CACHE_WEIGHT_BYTES,
                DEFAULT_MINIMUM_NODE_REDUCTION,
                16_384);
    }

    public static ReferenceCutConfiguration verifiedDefaults() {
        return new ReferenceCutConfiguration(
                ReferenceCutMode.VERIFIED,
                DEFAULT_MAXIMUM_CACHE_WEIGHT_BYTES,
                DEFAULT_MINIMUM_NODE_REDUCTION,
                16_384);
    }

    public static ReferenceCutConfiguration shadowDifferential() {
        return new ReferenceCutConfiguration(
                ReferenceCutMode.SHADOW_DIFFERENTIAL,
                DEFAULT_MAXIMUM_CACHE_WEIGHT_BYTES,
                0.0d,
                16_384);
    }

    public ReferenceCutMode mode() { return mode; }
    public long maximumCacheWeightBytes() {
        return maximumCacheWeightBytes;
    }
    public double minimumNodeReduction() { return minimumNodeReduction; }
    public int maximumCuts() { return maximumCuts; }
    public boolean enabled() { return mode != ReferenceCutMode.DISABLED; }
}
