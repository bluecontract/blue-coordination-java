package blue.coordination.fastpath;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Compact, body-free event-planning projection of one already admitted
 * subscription occurrence. Expensive semantic validation belongs to
 * admission; event-time code reads this immutable scalar projection only.
 */
public final class AdmittedOccurrence implements Comparable<AdmittedOccurrence> {
    private static final char SEPARATOR = '\u001f';

    private final String publicKey;
    private final String languageKey;
    private final String scopePath;
    private final String scopeBlueId;
    private final String channelKey;
    private final String effectiveTypeBlueId;
    private final int order;
    private final String headerIdentityBlueId;
    private final String checkpointDomainBlueId;
    private final List<String> scopeChainBlueIds;
    private final List<String> sourceContributionBlueIds;
    private final List<String> dependencyBlueIds;
    private final List<String> subscriptionKeys;
    private final Set<String> dependencyPaths;
    private final String semanticFingerprint;

    public AdmittedOccurrence(
            String publicKey,
            String scopePath,
            String scopeBlueId,
            String channelKey,
            String effectiveTypeBlueId,
            int order,
            String headerIdentityBlueId,
            String checkpointDomainBlueId,
            Collection<String> scopeChainBlueIds,
            Collection<String> sourceContributionBlueIds,
            Collection<String> dependencyBlueIds,
            Collection<String> subscriptionKeys,
            Collection<String> dependencyPaths) {
        this.publicKey = text(publicKey, "publicKey");
        this.scopePath = canonicalScope(scopePath);
        this.scopeBlueId = text(scopeBlueId, "scopeBlueId");
        this.channelKey = text(channelKey, "channelKey");
        this.languageKey = this.scopePath + SEPARATOR + this.channelKey;
        this.effectiveTypeBlueId = text(effectiveTypeBlueId, "effectiveTypeBlueId");
        this.order = order;
        this.headerIdentityBlueId = text(headerIdentityBlueId, "headerIdentityBlueId");
        this.checkpointDomainBlueId = text(
                checkpointDomainBlueId, "checkpointDomainBlueId");
        this.scopeChainBlueIds = textList(scopeChainBlueIds, "scopeChainBlueId");
        if (this.scopeChainBlueIds.isEmpty()
                || !this.scopeBlueId.equals(this.scopeChainBlueIds.get(
                        this.scopeChainBlueIds.size() - 1))) {
            throw new IllegalArgumentException(
                    "scope chain must terminate at scopeBlueId for " + publicKey);
        }
        this.sourceContributionBlueIds = textList(
                sourceContributionBlueIds, "sourceContributionBlueId");
        this.dependencyBlueIds = textList(dependencyBlueIds, "dependencyBlueId");
        this.subscriptionKeys = textList(subscriptionKeys, "subscriptionKey");
        this.dependencyPaths = canonicalPathSet(dependencyPaths);
        this.semanticFingerprint = fingerprint();
    }

    public String publicKey() { return publicKey; }
    public String languageKey() { return languageKey; }
    public String scopePath() { return scopePath; }
    public String scopeBlueId() { return scopeBlueId; }
    public String channelKey() { return channelKey; }
    public String effectiveTypeBlueId() { return effectiveTypeBlueId; }
    public int order() { return order; }
    public String headerIdentityBlueId() { return headerIdentityBlueId; }
    public String checkpointDomainBlueId() { return checkpointDomainBlueId; }
    public List<String> scopeChainBlueIds() { return scopeChainBlueIds; }
    public List<String> sourceContributionBlueIds() {
        return sourceContributionBlueIds;
    }
    public List<String> dependencyBlueIds() { return dependencyBlueIds; }
    public List<String> subscriptionKeys() { return subscriptionKeys; }
    public Set<String> dependencyPaths() { return dependencyPaths; }
    public String semanticFingerprint() { return semanticFingerprint; }

    /** Returns a dependency-only replacement while retaining occurrence identity. */
    public AdmittedOccurrence withDependencyEvidence(
            String newHeaderIdentity,
            String newCheckpointDomain,
            Collection<String> newDependencyBlueIds,
            Collection<String> newDependencyPaths) {
        return new AdmittedOccurrence(
                publicKey,
                scopePath,
                scopeBlueId,
                channelKey,
                effectiveTypeBlueId,
                order,
                newHeaderIdentity,
                newCheckpointDomain,
                scopeChainBlueIds,
                sourceContributionBlueIds,
                newDependencyBlueIds,
                subscriptionKeys,
                newDependencyPaths);
    }

    @Override
    public int compareTo(AdmittedOccurrence other) {
        int compared = codePointCompare(scopePath, other.scopePath);
        if (compared != 0) return compared;
        compared = Integer.compare(order, other.order);
        if (compared != 0) return compared;
        compared = codePointCompare(channelKey, other.channelKey);
        if (compared != 0) return compared;
        return codePointCompare(effectiveTypeBlueId, other.effectiveTypeBlueId);
    }

    @Override
    public boolean equals(Object supplied) {
        if (this == supplied) return true;
        if (!(supplied instanceof AdmittedOccurrence)) return false;
        AdmittedOccurrence other = (AdmittedOccurrence) supplied;
        return semanticFingerprint.equals(other.semanticFingerprint)
                && publicKey.equals(other.publicKey)
                && scopeChainBlueIds.equals(other.scopeChainBlueIds)
                && dependencyPaths.equals(other.dependencyPaths);
    }

    @Override
    public int hashCode() {
        return Objects.hash(publicKey, semanticFingerprint,
                scopeChainBlueIds, dependencyPaths);
    }

    private String fingerprint() {
        MessageDigest digest = sha256();
        add(digest, "blue.coordination/admitted-occurrence/1.0");
        add(digest, publicKey);
        add(digest, languageKey);
        add(digest, scopeBlueId);
        add(digest, effectiveTypeBlueId);
        add(digest, Integer.toString(order));
        add(digest, headerIdentityBlueId);
        add(digest, checkpointDomainBlueId);
        addAll(digest, scopeChainBlueIds);
        addAll(digest, sourceContributionBlueIds);
        addAll(digest, dependencyBlueIds);
        addAll(digest, subscriptionKeys);
        addAll(digest, dependencyPaths);
        return "sha256:" + hex(digest.digest());
    }

    static int codePointCompare(String left, String right) {
        int leftIndex = 0;
        int rightIndex = 0;
        while (leftIndex < left.length() && rightIndex < right.length()) {
            int leftPoint = left.codePointAt(leftIndex);
            int rightPoint = right.codePointAt(rightIndex);
            if (leftPoint != rightPoint) return Integer.compare(leftPoint, rightPoint);
            leftIndex += Character.charCount(leftPoint);
            rightIndex += Character.charCount(rightPoint);
        }
        return Integer.compare(left.length() - leftIndex, right.length() - rightIndex);
    }

    static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    static void add(MessageDigest digest, String value) {
        byte[] bytes = text(value, "digest value").getBytes(StandardCharsets.UTF_8);
        digest.update((byte) (bytes.length >>> 24));
        digest.update((byte) (bytes.length >>> 16));
        digest.update((byte) (bytes.length >>> 8));
        digest.update((byte) bytes.length);
        digest.update(bytes);
    }

    static void addAll(MessageDigest digest, Collection<String> values) {
        add(digest, Integer.toString(values.size()));
        for (String value : values) add(digest, value);
    }

    static String hex(byte[] bytes) {
        char[] alphabet = "0123456789abcdef".toCharArray();
        char[] result = new char[bytes.length * 2];
        for (int index = 0; index < bytes.length; index++) {
            int value = bytes[index] & 0xff;
            result[index * 2] = alphabet[value >>> 4];
            result[index * 2 + 1] = alphabet[value & 0x0f];
        }
        return new String(result);
    }

    static String canonicalScope(String value) {
        String exact = text(value, "scopePath");
        if (!exact.startsWith("/") || (exact.length() > 1 && exact.endsWith("/"))
                || exact.contains("//")) {
            throw new IllegalArgumentException("scopePath must be canonical: " + exact);
        }
        return exact;
    }

    static Set<String> canonicalPathSet(Collection<String> supplied) {
        List<String> paths = new ArrayList<String>(
                Objects.requireNonNull(supplied, "dependencyPaths"));
        for (int index = 0; index < paths.size(); index++) {
            paths.set(index, canonicalScope(paths.get(index)));
        }
        Collections.sort(paths, AdmittedOccurrence::codePointCompare);
        Set<String> unique = new LinkedHashSet<String>(paths);
        if (unique.size() != paths.size()) {
            throw new IllegalArgumentException("duplicate dependency path");
        }
        return Collections.unmodifiableSet(unique);
    }

    static List<String> textList(Collection<String> values, String name) {
        List<String> result = new ArrayList<String>(
                Objects.requireNonNull(values, name + "s"));
        for (String value : result) text(value, name);
        return Collections.unmodifiableList(result);
    }

    static String text(String value, String name) {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException(name + " must be non-empty");
        }
        return value;
    }
}
