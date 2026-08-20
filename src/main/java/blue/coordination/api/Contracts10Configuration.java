package blue.coordination.api;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Explicit artifact and public-Root inputs for Contracts 1.0 Coordination.
 *
 * <p>The engine never invents release identities. A closure-enabled factory
 * accepts this value only after the caller binds the exact final Language and
 * Contracts specification artifacts it intends to execute.</p>
 *
 * @param blueLanguageSpecificationIdentity exact final Language artifact ID
 * @param contractsSpecificationIdentity exact final Contracts artifact ID
 * @param publicRootDocumentIds public feeder Root lineages for this engine
 */
public record Contracts10Configuration(
        String blueLanguageSpecificationIdentity,
        String contractsSpecificationIdentity,
        Set<DocumentId> publicRootDocumentIds) {
    private static final Pattern SHA_256_IDENTITY = Pattern.compile(
            "^sha256:[0-9a-f]{64}$");

    /** Validates exact artifact identities and canonical public Root order. */
    public Contracts10Configuration {
        blueLanguageSpecificationIdentity = requireIdentity(
                blueLanguageSpecificationIdentity,
                "blueLanguageSpecificationIdentity");
        contractsSpecificationIdentity = requireIdentity(
                contractsSpecificationIdentity,
                "contractsSpecificationIdentity");
        TreeSet<DocumentId> canonical = new TreeSet<>((left, right) ->
                comparePortableText(left.value(), right.value()));
        Objects.requireNonNull(
                publicRootDocumentIds, "publicRootDocumentIds")
                .forEach(root -> canonical.add(Objects.requireNonNull(
                        root, "publicRootDocumentId")));
        if (canonical.isEmpty()) {
            throw new IllegalArgumentException(
                    "Contracts 1.0 requires at least one public Root");
        }
        publicRootDocumentIds = Collections.unmodifiableSet(
                new LinkedHashSet<>(canonical));
    }

    private static String requireIdentity(String value, String label) {
        String checked = Objects.requireNonNull(value, label);
        if (!SHA_256_IDENTITY.matcher(checked).matches()) {
            throw new IllegalArgumentException(
                    label + " must be a lowercase sha256 identity");
        }
        return checked;
    }

    private static int comparePortableText(String left, String right) {
        int leftOffset = 0;
        int rightOffset = 0;
        while (leftOffset < left.length() && rightOffset < right.length()) {
            int leftPoint = left.codePointAt(leftOffset);
            int rightPoint = right.codePointAt(rightOffset);
            if (leftPoint != rightPoint) {
                return Integer.compare(leftPoint, rightPoint);
            }
            leftOffset += Character.charCount(leftPoint);
            rightOffset += Character.charCount(rightPoint);
        }
        return Integer.compare(left.length(), right.length());
    }
}
