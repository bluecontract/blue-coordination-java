package blue.language.processor;

/**
 * Test-only access to the package-owned runtime-work lifecycle.
 */
public final class RuntimeWorkSessionTestSupport {
    private RuntimeWorkSessionTestSupport() {
    }

    public static RuntimeWorkSession processing(
            GasMeter parent) {
        return new RuntimeWorkSession(
                parent,
                RuntimeWorkSession.Mode.PROCESSING);
    }

    public static void complete(
            RuntimeWorkSession session) {
        session.complete();
    }

    public static void failDeterministically(
            RuntimeWorkSession session) {
        session.failDeterministically();
    }
}
