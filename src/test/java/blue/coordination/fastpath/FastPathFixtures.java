package blue.coordination.fastpath;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

final class FastPathFixtures {
    private FastPathFixtures() { }

    static ProjectionGenerationKey generation(long revision) {
        return new ProjectionGenerationKey(
                "environment", "session", "root-" + revision, revision,
                "inventory-" + revision, "subscriptions-" + revision,
                "runtime");
    }

    static AdmittedOccurrence occurrence(int index, String scope) {
        List<String> chain = new ArrayList<String>();
        chain.add("root-scope-id");
        if (!"/".equals(scope)) chain.add("scope-id-" + index);
        String scopeBlueId = chain.get(chain.size() - 1);
        return new AdmittedOccurrence(
                "public-" + index,
                scope,
                scopeBlueId,
                "channel-" + index,
                "type-" + (index % 3),
                index,
                "header-" + index,
                "checkpoint-" + index,
                chain,
                Arrays.asList("source-" + index),
                Arrays.asList("dependency-" + index),
                Arrays.asList("timeline:" + (index % 4)),
                Arrays.asList(scope, scope + ("/".equals(scope) ? "contracts" : "/contracts")));
    }

    static AdmittedProjection projection(int count, long revision) {
        List<AdmittedOccurrence> values = new ArrayList<AdmittedOccurrence>();
        for (int index = 0; index < count; index++) {
            values.add(occurrence(index, "/orders/order-" + index));
        }
        return new AdmittedProjection(generation(revision), values);
    }

    static ProjectionDelta dependencyRefresh(
            AdmittedOccurrence oldValue,
            String changedPath) {
        AdmittedOccurrence refreshed = oldValue.withDependencyEvidence(
                oldValue.headerIdentityBlueId() + "-new",
                oldValue.checkpointDomainBlueId() + "-new",
                Collections.singletonList("dependency-new"),
                Collections.singletonList(changedPath));
        return new ProjectionDelta(
                Collections.<AdmittedOccurrence>emptyList(),
                Collections.<String>emptyList(),
                Collections.singletonList(refreshed),
                Collections.singletonList(changedPath),
                true);
    }
}
