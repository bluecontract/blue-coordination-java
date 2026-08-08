package blue.coordination.examples.support;

/**
 * Explicit, bounded performance knobs for the myOS executable examples.
 * Values are deterministic for one JVM and never affect Blue semantics.
 */
public final class MyOsPerformanceTuning {
    private static final String PARALLELISM_PROPERTY =
            "blue.myos.rootPreparationParallelism";
    private static final String QUEUE_PROPERTY =
            "blue.myos.rootPreparationQueueCapacity";

    private MyOsPerformanceTuning() { }

    public static int rootPreparationParallelism() {
        int processors = Runtime.getRuntime().availableProcessors();
        int defaultValue = Math.max(1, Math.min(4, processors));
        return positiveProperty(PARALLELISM_PROPERTY, defaultValue, 16);
    }

    public static int rootPreparationQueueCapacity() {
        int defaultValue = Math.max(
                16, rootPreparationParallelism() * 4);
        return positiveProperty(QUEUE_PROPERTY, defaultValue, 4_096);
    }

    private static int positiveProperty(
            String name,
            int defaultValue,
            int maximum) {
        String supplied = System.getProperty(name);
        if (supplied == null || supplied.trim().isEmpty()) {
            return defaultValue;
        }
        final int parsed;
        try {
            parsed = Integer.parseInt(supplied.trim());
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException(
                    name + " must be an integer", invalid);
        }
        if (parsed <= 0 || parsed > maximum) {
            throw new IllegalArgumentException(
                    name + " must be in [1," + maximum + "]");
        }
        return parsed;
    }
}
