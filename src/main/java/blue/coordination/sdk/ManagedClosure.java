package blue.coordination.sdk;

import blue.coordination.api.DocumentId;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Immutable complete managed-closure admission definition. */
public final class ManagedClosure {
    private final Map<String, Member> members;
    private final List<OccurrenceBinding> bindings;
    private final Set<String> publicRoots;
    private final ActivationPolicy activationPolicy;

    private ManagedClosure(Builder builder) {
        if (builder.members.isEmpty()) {
            throw new IllegalStateException(
                    "A managed closure requires at least one document");
        }
        if (builder.publicRoots.isEmpty()) {
            throw new IllegalStateException(
                    "A managed closure requires at least one public Root");
        }
        if (builder.activationPolicy == null) {
            throw new IllegalStateException(
                    "Select an activation policy before build");
        }
        Set<DocumentId> documentIds = new LinkedHashSet<>();
        builder.members.forEach((alias, member) -> {
            if (!documentIds.add(member.id())) {
                throw new IllegalStateException(
                        "Managed document lineage is repeated: "
                                + member.id());
            }
        });
        for (OccurrenceBinding binding : builder.bindings) {
            requireAlias(builder.members, binding.sourceAlias(),
                    "binding source");
            requireAlias(builder.members, binding.targetAlias(),
                    "binding target");
        }
        for (String root : builder.publicRoots) {
            requireAlias(builder.members, root, "public Root");
        }
        this.members = Collections.unmodifiableMap(
                new LinkedHashMap<>(builder.members));
        this.bindings = List.copyOf(builder.bindings);
        this.publicRoots = Collections.unmodifiableSet(
                new LinkedHashSet<>(builder.publicRoots));
        this.activationPolicy = builder.activationPolicy;
    }

    /** Starts a complete closure definition. */
    public static Builder builder() {
        return new Builder();
    }

    /** Stable aliases in deterministic authored order. */
    public List<String> documentAliases() {
        return List.copyOf(members.keySet());
    }

    /** Stable document lineage identities in deterministic authored order. */
    public List<DocumentId> documentIds() {
        return members.values().stream().map(Member::id).toList();
    }

    /** Public Root aliases in deterministic authored order. */
    public Set<String> publicRootAliases() {
        return publicRoots;
    }

    /** Temporal policy applied atomically to the complete closure. */
    public ActivationPolicy activationPolicy() {
        return activationPolicy;
    }

    Map<String, Member> members() {
        return members;
    }

    List<OccurrenceBinding> bindings() {
        return bindings;
    }

    Set<String> publicRoots() {
        return publicRoots;
    }

    private static void requireAlias(
            Map<String, Member> members,
            String alias,
            String role) {
        if (!members.containsKey(alias)) {
            throw new IllegalStateException(
                    "Unknown " + role + " alias: " + alias);
        }
    }

    record Member(String alias, DocumentId id, String authoredYaml) {
        Member {
            alias = SdkPreconditions.requireText(alias, "alias");
            id = Objects.requireNonNull(id, "id");
            authoredYaml = SdkPreconditions.requireText(
                    authoredYaml, "authoredYaml");
        }
    }

    record OccurrenceBinding(
            String sourceAlias,
            String path,
            String targetAlias) {
        OccurrenceBinding {
            sourceAlias = SdkPreconditions.requireText(
                    sourceAlias, "sourceAlias");
            path = SdkPreconditions.requireOccurrencePath(path);
            targetAlias = SdkPreconditions.requireText(
                    targetAlias, "targetAlias");
        }
    }

    /** Mutable construction scope that produces one immutable definition. */
    public static final class Builder {
        private final Map<String, Member> members = new LinkedHashMap<>();
        private final List<OccurrenceBinding> bindings = new ArrayList<>();
        private final Set<String> bindingSlots = new LinkedHashSet<>();
        private final Set<String> publicRoots = new LinkedHashSet<>();
        private ActivationPolicy activationPolicy;

        private Builder() {
        }

        /** Adds one member whose stable lineage defaults to its alias. */
        public Builder document(String alias, String authoredYaml) {
            return document(alias, DocumentId.of(alias), authoredYaml);
        }

        /** Adds one member with an explicit stable managed lineage. */
        public Builder document(
                String alias,
                DocumentId documentId,
                String authoredYaml) {
            Member member = new Member(alias, documentId, authoredYaml);
            if (members.putIfAbsent(member.alias(), member) != null) {
                throw new IllegalArgumentException(
                        "Duplicate document alias: " + member.alias());
            }
            return this;
        }

        /** Adds managed-lineage evidence for one authored occurrence. */
        public Builder bindOccurrence(
                String sourceAlias,
                String path,
                String targetAlias) {
            OccurrenceBinding binding = new OccurrenceBinding(
                    sourceAlias, path, targetAlias);
            String slot = binding.sourceAlias() + '\u0000' + binding.path();
            if (!bindingSlots.add(slot)) {
                throw new IllegalArgumentException(
                        "Duplicate occurrence binding: "
                                + binding.sourceAlias() + binding.path());
            }
            bindings.add(binding);
            return this;
        }

        /** Marks one member alias as an externally authorized public Root. */
        public Builder publicRoot(String alias) {
            publicRoots.add(SdkPreconditions.requireText(alias, "alias"));
            return this;
        }

        /** Selects birth-at-admission semantics for the complete closure. */
        public Builder fromNow() {
            return activation(ActivationPolicy.fromNow());
        }

        /** Selects an explicit supported temporal admission policy. */
        public Builder activation(ActivationPolicy policy) {
            this.activationPolicy = Objects.requireNonNull(policy, "policy");
            return this;
        }

        /** Validates and freezes the complete admission definition. */
        public ManagedClosure build() {
            return new ManagedClosure(this);
        }
    }
}
