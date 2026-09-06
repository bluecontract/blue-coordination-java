package blue.coordination.sdk;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

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
 *
 * <p>An empty result and a thrown {@link RuntimeException} both mean the
 * provider has not answered yet: the demanding work is suspended as
 * {@code NEEDS_RESOURCES} and the same retained entry is retried once the
 * provider answers. A thrown failure is never interpreted as evidence about
 * the requested content, so a transient host fault (database or network
 * error) cannot consume an entry.</p>
 */
@FunctionalInterface
public interface ExactNodeProvider {
    /** Returns one whole direct exact Blue JSON or YAML value, when available. */
    Optional<String> findExactContent(String blueId);

    /**
     * Returns complete provider evidence for one exact value.
     *
     * <p>The default preserves the original serialized-content provider
     * contract. Proof-aware providers should be created with
     * {@link #withEvidence(Function)}.</p>
     */
    default Optional<ExactNodeEvidence> findExactEvidence(String blueId) {
        return findExactContent(blueId).map(ExactNodeEvidence::ordinary);
    }

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
        ExactNodeEvidence evidence = retained.providerEvidence();
        return withEvidence(requested -> retained.blueId().equals(
                requireText(requested, "blueId"))
                ? Optional.of(evidence) : Optional.empty());
    }

    /**
     * Creates a proof-aware provider while retaining the legacy functional
     * string-provider surface for ordinary callers.
     */
    static ExactNodeProvider withEvidence(
            Function<String, Optional<ExactNodeEvidence>> lookup) {
        Function<String, Optional<ExactNodeEvidence>> selected =
                Objects.requireNonNull(lookup, "lookup");
        return new ExactNodeProvider() {
            @Override
            public Optional<String> findExactContent(String blueId) {
                return findExactEvidence(blueId)
                        .map(ExactNodeEvidence::exactContent);
            }

            @Override
            public Optional<ExactNodeEvidence> findExactEvidence(
                    String blueId) {
                return Objects.requireNonNull(selected.apply(requireText(
                        blueId, "blueId")), "provider evidence result");
            }
        };
    }

    private static String requireText(String value, String label) {
        String checked = Objects.requireNonNull(value, label).trim();
        if (checked.isEmpty()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return checked;
    }
}
