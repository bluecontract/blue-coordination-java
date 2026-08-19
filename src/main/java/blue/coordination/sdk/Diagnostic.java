package blue.coordination.sdk;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Stable machine code and immutable human-readable result detail. */
public record Diagnostic(
        String code,
        String message,
        Map<String, String> details) {
    private static final Diagnostic NONE = new Diagnostic(
            "NONE", "", Map.of());

    /** Defensively copies detail fields and validates their text keys. */
    public Diagnostic {
        code = SdkPreconditions.requireText(code, "code");
        message = Objects.requireNonNull(message, "message");
        Map<String, String> copied = new LinkedHashMap<>();
        Objects.requireNonNull(details, "details").forEach(
                (key, value) -> copied.put(
                        SdkPreconditions.requireText(key, "detail key"),
                        Objects.requireNonNull(value, "detail value")));
        details = Collections.unmodifiableMap(copied);
        if (code.equals("NONE") && (!message.isEmpty() || !details.isEmpty())) {
            throw new IllegalArgumentException(
                    "The NONE diagnostic cannot carry failure detail");
        }
    }

    /** No diagnostic for a successful terminal result. */
    public static Diagnostic none() {
        return NONE;
    }

    /** Whether this value contains a terminal/failure diagnostic. */
    public boolean present() {
        return !code.equals("NONE");
    }
}
