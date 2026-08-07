package blue.coordination.processor.bex;

import blue.bex.api.BexGasLedgerHost;
import blue.language.model.Node;
import blue.language.processor.ProcessorExecutionContext;
import blue.language.processor.WorkingDocument;
import blue.language.snapshot.FrozenNode;

import java.util.Map;

/**
 * Minimal immutable/live capabilities required to host BEX for one workflow
 * step.
 *
 * <p>The BEX adapter depends on this role instead of the workflow package's
 * concrete context. This keeps the adapter reusable and makes the package
 * dependency point from workflow to BEX only.</p>
 */
public interface BexWorkflowStepContext {
    ProcessorExecutionContext processorContext();

    BexGasLedgerHost bexGasLedgerHost();

    Node eventRef();

    Map<String, Object> stepResults();

    FrozenNode currentContractFrozenNode();

    Node currentContractNodeRef();

    FrozenNode workingCanonicalAt(String absolutePointer);

    FrozenNode workingResolvedAt(String absolutePointer);

    WorkingDocument workingDocument();
}
