package blue.coordination.processor;

import blue.language.model.Node;
import blue.language.provider.ExactNodeGraphFragments;
import blue.language.utils.BlueIdCalculator;
import blue.language.utils.NodeToMapListOrValue;
import blue.language.utils.UncheckedObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * Persistence-neutral admission checks for immutable physical fragments.
 *
 * <p>A store may race on {@link ImmutableFragmentStore#putIfAbsent}; the
 * winner is always read back and compared with the proposed canonical bytes.
 * A duplicate is idempotent only when those bytes agree. Same-BlueId content
 * in another physical representation is an evidence failure under this
 * profile.</p>
 */
public final class CoordinationFragmentAdmissionVerifier {

    private CoordinationFragmentAdmissionVerifier() {
    }

    /**
     * Minimal callback implemented by an immutable content-addressed store.
     */
    public interface ImmutableFragmentStore {

        /**
         * Reads the current winner for one profile and identity.
         */
        Node read(String profileIdentity, String blueId);

        /**
         * Attempts immutable first-writer admission.
         *
         * @return {@code true} only when this call installed the value
         */
        boolean putIfAbsent(
                String profileIdentity,
                String blueId,
                Node exactFragment);
    }

    /**
     * Outcome of a byte-verified immutable admission.
     */
    public enum AdmissionStatus {
        ADMITTED,
        IDEMPOTENT_DUPLICATE
    }

    /**
     * Admits one canonical fragment and verifies the stored race winner.
     */
    public static AdmissionStatus admit(
            String profileIdentity,
            String blueId,
            Node proposed,
            ImmutableFragmentStore store) {
        requireSupportedProfile(
                profileIdentity);
        Node checked =
                Objects.requireNonNull(
                        proposed, "proposed")
                        .clone();
        requireIdentity(
                blueId,
                checked,
                "Proposed fragment");
        requireCanonicalDirectRepresentation(
                checked,
                "Proposed fragment");
        ImmutableFragmentStore checkedStore =
                Objects.requireNonNull(
                        store, "store");
        Node before =
                checkedStore.read(
                        profileIdentity,
                        blueId);
        boolean installed = false;
        if (before == null) {
            installed =
                    checkedStore.putIfAbsent(
                            profileIdentity,
                            blueId,
                            checked.clone());
        }
        Node winner =
                checkedStore.read(
                        profileIdentity,
                        blueId);
        if (winner == null) {
            throw evidenceFailure(
                    "Store did not return a winner after admission for "
                            + blueId);
        }
        verifyWinner(
                profileIdentity,
                blueId,
                checked,
                winner);
        return installed
                ? AdmissionStatus.ADMITTED
                : AdmissionStatus.IDEMPOTENT_DUPLICATE;
    }

    /**
     * Verifies an already stored duplicate or concurrent race winner.
     */
    public static void verifyWinner(
            String profileIdentity,
            String blueId,
            Node proposed,
            Node storedWinner) {
        requireSupportedProfile(
                profileIdentity);
        Node checkedProposed =
                Objects.requireNonNull(
                        proposed, "proposed");
        Node checkedWinner =
                Objects.requireNonNull(
                        storedWinner,
                        "storedWinner");
        requireIdentity(
                blueId,
                checkedProposed,
                "Proposed fragment");
        requireIdentity(
                blueId,
                checkedWinner,
                "Stored winner");
        requireCanonicalDirectRepresentation(
                checkedProposed,
                "Proposed fragment");
        requireCanonicalDirectRepresentation(
                checkedWinner,
                "Stored winner");
        if (!NodeToMapListOrValue.get(
                checkedProposed).equals(
                NodeToMapListOrValue.get(
                        checkedWinner))) {
            throw evidenceFailure(
                    "Immutable winner bytes disagree for profile "
                            + profileIdentity
                            + " and BlueId "
                            + blueId);
        }
    }

    /**
     * Returns a stable SHA-256 identity of one physical node representation.
     */
    public static String physicalFragmentIdentity(
            Node fragment) {
        String json =
                UncheckedObjectMapper.JSON_MAPPER
                        .writeValueAsString(
                                NodeToMapListOrValue.get(
                                        Objects.requireNonNull(
                                                fragment,
                                                "fragment")));
        return "sha256:"
                + sha256Hex(
                json.getBytes(
                        StandardCharsets.UTF_8));
    }

    /**
     * Returns a stable digest of a complete immutable fragment inventory.
     */
    public static String inventoryIdentity(
            String profileIdentity,
            Collection<CoordinationDocumentSplitter.FragmentRoot>
                    fragmentRoots,
            Map<String, Node> fragments,
            Collection<CoordinationDocumentSplitter.EdgeOccurrence>
                    edgeOccurrences) {
        requireSupportedProfile(
                profileIdentity);
        StringBuilder canonical =
                new StringBuilder();
        append(canonical, profileIdentity);

        List<CoordinationDocumentSplitter.FragmentRoot> roots =
                new ArrayList<>(
                        Objects.requireNonNull(
                                fragmentRoots,
                                "fragmentRoots"));
        roots.sort(
                Comparator
                        .comparing(
                                (CoordinationDocumentSplitter.FragmentRoot value)
                                        -> value.kind().name())
                        .thenComparing(
                                CoordinationDocumentSplitter
                                .FragmentRoot::absolutePath)
                        .thenComparing(
                                CoordinationDocumentSplitter
                                .FragmentRoot::blueId));
        for (CoordinationDocumentSplitter.FragmentRoot root : roots) {
            append(canonical, root.kind().name());
            append(canonical, root.absolutePath());
            append(canonical, root.blueId());
        }

        SortedMap<String, Node> orderedFragments =
                new TreeMap<>(
                        Objects.requireNonNull(
                                fragments,
                                "fragments"));
        for (Map.Entry<String, Node> fragment
                : orderedFragments.entrySet()) {
            append(canonical, fragment.getKey());
            append(
                    canonical,
                    physicalFragmentIdentity(
                            fragment.getValue()));
        }

        List<CoordinationDocumentSplitter.EdgeOccurrence> edges =
                new ArrayList<>(
                        Objects.requireNonNull(
                                edgeOccurrences,
                                "edgeOccurrences"));
        edges.sort(
                Comparator
                        .comparing(
                                (CoordinationDocumentSplitter.EdgeOccurrence value)
                                        -> value.rootKind().name())
                        .thenComparing(
                                CoordinationDocumentSplitter
                                .EdgeOccurrence::rootBlueId)
                        .thenComparing(
                                CoordinationDocumentSplitter
                                .EdgeOccurrence::ownerNodeBlueId)
                        .thenComparing(
                                CoordinationDocumentSplitter
                                .EdgeOccurrence::absolutePointer)
                        .thenComparing(
                                value -> value.edgeKind().name())
                        .thenComparing(
                                CoordinationDocumentSplitter
                                .EdgeOccurrence::childBlueId)
                        .thenComparing(
                                CoordinationDocumentSplitter
                                .EdgeOccurrence::ownerRelativePointer)
                        .thenComparing(
                                CoordinationDocumentSplitter
                                .EdgeOccurrence::originalPureReference)
                        .thenComparing(
                                value -> nullToEmpty(
                                        value.handlerEffectiveTypeBlueId()))
                        .thenComparing(
                                value -> nullToEmpty(
                                        value.executableBodyField()))
                        .thenComparing(
                                value -> value
                                        .sourceContributionBlueIds()
                                        .toString()));
        for (CoordinationDocumentSplitter.EdgeOccurrence edge : edges) {
            append(canonical, edge.fragmentationProfileIdentity());
            append(canonical, edge.schemaIdentity());
            append(canonical, edge.rootKind().name());
            append(canonical, edge.rootBlueId());
            append(canonical, edge.ownerNodeBlueId());
            append(canonical, edge.ownerScopePath());
            append(canonical, edge.absolutePointer());
            append(canonical, edge.ownerRelativePointer());
            append(canonical, edge.childBlueId());
            append(canonical, edge.edgeKind().name());
            append(
                    canonical,
                    Boolean.toString(
                            edge.originalPureReference()));
            append(
                    canonical,
                    Boolean.toString(
                            edge.splitterCreated()));
            append(canonical, edge.handlerEffectiveTypeBlueId());
            append(canonical, edge.executableBodyField());
            for (String source
                    : edge.sourceContributionBlueIds()) {
                append(canonical, source);
            }
            append(canonical, "<sources-end>");
        }
        return "sha256:"
                + sha256Hex(
                canonical.toString()
                        .getBytes(
                                StandardCharsets.UTF_8));
    }

    private static void requireSupportedProfile(
            String profileIdentity) {
        if (!CoordinationDocumentSplitter.FRAGMENTATION_PROFILE_ID.equals(
                profileIdentity)) {
            throw evidenceFailure(
                    "Unsupported fragmentation profile "
                            + profileIdentity);
        }
    }

    private static void requireIdentity(
            String expected,
            Node node,
            String label) {
        String actual =
                BlueIdCalculator.calculateBlueId(
                        node);
        if (!Objects.equals(
                expected,
                actual)) {
            throw evidenceFailure(
                    label
                            + " identity is "
                            + actual
                            + ", expected "
                            + expected);
        }
    }

    private static void requireCanonicalDirectRepresentation(
            Node fragment,
            String label) {
        Node canonicalDirect =
                new ExactNodeGraphFragments(
                        fragment)
                        .roots().get(0)
                        .directFragment();
        if (!NodeToMapListOrValue.get(
                canonicalDirect).equals(
                NodeToMapListOrValue.get(
                        fragment))) {
            throw evidenceFailure(
                    label
                            + " is not the canonical direct-node "
                            + "representation");
        }
    }

    private static void append(
            StringBuilder target,
            String value) {
        String normalized =
                value != null ? value : "<null>";
        target.append(
                normalized.length())
                .append(':')
                .append(normalized);
    }

    private static String nullToEmpty(
            String value) {
        return value != null ? value : "";
    }

    private static String sha256Hex(
            byte[] bytes) {
        final byte[] digest;
        try {
            digest =
                    MessageDigest.getInstance(
                            "SHA-256")
                            .digest(bytes);
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException(
                    "SHA-256 is unavailable",
                    failure);
        }
        StringBuilder hexadecimal =
                new StringBuilder(
                        digest.length * 2);
        for (byte value : digest) {
            hexadecimal.append(
                    String.format(
                            "%02x",
                            value & 0xff));
        }
        return hexadecimal.toString();
    }

    private static IllegalArgumentException evidenceFailure(
            String message) {
        return new IllegalArgumentException(
                "Invalid Coordination fragment evidence: "
                        + message);
    }
}
