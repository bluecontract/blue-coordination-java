package blue.coordination.sdk;

import java.util.Objects;
import java.util.Optional;

/**
 * Read-only source of serialized whole exact Blue values for static authored
 * admission.
 *
 * <p>Coordination asks only for an exact BlueId encountered as a pure
 * reference at an effective {@code Process Embedded} occurrence path. It does
 * not enumerate or scan the provider. Returned content is parsed defensively
 * and its exact identity is verified before any managed state is written.</p>
 */
@FunctionalInterface
public interface ExactNodeProvider {
    /** Returns one whole exact Blue JSON or YAML value, when available. */
    Optional<String> findExactContent(String blueId);

    /** Returns a provider which contains no application exact nodes. */
    static ExactNodeProvider empty() {
        return ignored -> Optional.empty();
    }

    /** Creates a provider for one immutable serialized exact value. */
    static ExactNodeProvider of(String blueId, String exactContent) {
        String selectedBlueId = requireText(blueId, "blueId");
        String retained = requireText(exactContent, "exactContent");
        return requested -> selectedBlueId.equals(requireText(
                requested, "blueId"))
                ? Optional.of(retained)
                : Optional.empty();
    }

    /** Creates a provider for one SDK exact value. */
    static ExactNodeProvider of(ExactBlueValue exactValue) {
        ExactBlueValue retained = Objects.requireNonNull(
                exactValue, "exactValue");
        return of(retained.blueId(), retained.json());
    }

    private static String requireText(String value, String label) {
        String checked = Objects.requireNonNull(value, label).trim();
        if (checked.isEmpty()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return checked;
    }
}
