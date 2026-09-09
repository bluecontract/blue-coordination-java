package blue.coordination.sdk;

import blue.language.codec.jackson.UncheckedObjectMapper;
import blue.language.processor.closure.ClosureInvocationInput;
import blue.language.processor.closure.ClosureProcessResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/** Lossless charge-level review artifacts extracted from actual processor records. */
final class RootedGasEvidence {
    private RootedGasEvidence() { }
    static void write(String name, ClosureInvocationInput input, ClosureProcessResult result) throws IOException {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("invocationIdentity", result.invocationIdentity());
        record.put("inputClosureIdentity", result.inputClosureIdentity());
        record.put("causeIdentity", input.cause().causeIdentity());
        record.put("causeKind", input.cause().kind().toString());
        record.put("budget", input.executionPolicy().sharedLimit());
        record.put("gasManifestIdentity", input.environment().gasManifestIdentity());
        record.put("runtimeRegistryIdentity", input.environment().runtimeRegistryIdentity());
        record.put("status", result.status().toString());
        record.put("gas", result.totalGas());
        record.put("gasTraceIdentity", result.gasTraceIdentity());
        var charges = new ArrayList<Map<String, Object>>();
        result.gasTrace().forEach(g -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("sequence", g.sequence()); row.put("namespace", g.namespace().wireValue());
            row.put("counter", g.counter()); row.put("quantity", g.quantity());
            row.put("weight", g.weight()); row.put("subtotal", g.subtotal());
            row.put("documentId", g.documentId() == null ? null : g.documentId().value());
            row.put("scopePath", g.scopePath()); row.put("activationGeneration", g.activationGeneration());
            row.put("componentGeneration", g.componentGeneration()); row.put("contractKey", g.contractKey());
            row.put("logicalPath", g.logicalPath()); row.put("workOccurrenceId", g.workOccurrenceId());
            row.put("reason", g.reason()); charges.add(row);
        });
        record.put("charges", charges);
        var rejected = result.rejectedCharge();
        record.put("rejectedCharge", rejected == null ? null : Map.of(
                "identity", rejected.rejectedChargeIdentity(), "namespace", rejected.namespace().wireValue(),
                "counter", rejected.counter(), "quantity", rejected.quantity(), "weight", rejected.weight(),
                "subtotal", rejected.subtotal(), "remainingBeforeCharge", rejected.remainingBeforeCharge()));
        record.put("outputClosureIdentity", result.outputClosureIdentity());
        record.put("companionIdentity", result.commitCompanion() == null ? null : result.commitCompanion().companionIdentity());
        record.put("ownedDocuments", result.rootedProjection() == null ? null : result.rootedProjection()
                .ownedDocumentIds().stream().map(d -> d.value()).toList());
        Path directory = Path.of("build", "rooted-evidence", "gas");
        Files.createDirectories(directory);
        Files.writeString(directory.resolve(name + ".json"), UncheckedObjectMapper.JSON_MAPPER.writeValueAsString(record));
    }
}
