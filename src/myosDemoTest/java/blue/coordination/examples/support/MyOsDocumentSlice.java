package blue.coordination.examples.support;

import blue.coordination.engine.api.CoordinationFragmentSlice;
import blue.coordination.engine.api.DocumentSessionId;
import blue.language.model.Node;

import java.util.List;
import java.util.Objects;

/** Topology result used to load a bounded embedded document slice. */
public record MyOsDocumentSlice(
        DocumentSessionId owningRootSessionId,
        String absolutePath,
        MyOsDocumentIdentity logicalDocument,
        String currentLogicalRootBlueId,
        List<MyOsTopologyLink> relationshipChain,
        CoordinationFragmentSlice physicalSlice) {

    public MyOsDocumentSlice {
        Objects.requireNonNull(owningRootSessionId, "owningRootSessionId");
        Objects.requireNonNull(absolutePath, "absolutePath");
        Objects.requireNonNull(logicalDocument, "logicalDocument");
        Objects.requireNonNull(currentLogicalRootBlueId,
                "currentLogicalRootBlueId");
        relationshipChain = List.copyOf(relationshipChain);
        Objects.requireNonNull(physicalSlice, "physicalSlice");
        if (!currentLogicalRootBlueId.equals(
                physicalSlice.selectedRootBlueId())) {
            throw new IllegalArgumentException(
                    "Physical slice differs from the logical current Root");
        }
    }

    public List<String> selectedFragmentBlueIds() {
        return physicalSlice.fragmentBlueIds();
    }

    public Node exactSelectedRoot() {
        return physicalSlice.exactSelectedRoot();
    }
}
