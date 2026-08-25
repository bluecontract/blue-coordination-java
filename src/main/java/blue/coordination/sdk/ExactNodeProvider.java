package blue.coordination.sdk;

import java.util.Objects;
import java.util.Optional;

/**
 * Read-only application source of serialized whole exact Blue values and type
 * definitions.
 *
 * <p>Coordination asks only for an exact BlueId encountered during ordinary
 * Language resolution or at an effective {@code Process Embedded} occurrence
 * path. It does not enumerate or scan the provider. Returned content is parsed
 * defensively and its direct exact identity is verified before use; missing,
 * malformed, indirect, or identity-mismatched content fails closed.</p>
 *
 * <p>Provider content is direct BlueId input. Applications authoring YAML with
 * runtime aliases should first call
 * {@link ExactValues#providerContentYaml(String)} and supply the resulting
 * {@link ExactBlueValue#json()}.</p>
 */
@FunctionalInterface
public interface ExactNodeProvider {
    /** Returns one whole direct exact Blue JSON or YAML value, when available. */
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
