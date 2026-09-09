package blue.coordination.sdk;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Admission must use the same declared forward scope as later rooted work. */
final class RootedAdmissionSourceScopeTest {
    @Test void aNewObserverAdmissionExcludesExistingIncomingObservers() throws Exception {
        try (var fixture = new RootedSdkFixture()) {
            var source = fixture.start("source.yaml", "rcp2/source", Map.of());
            String savedSource = fixture.retain(source);
            var sourceView = fixture.control.selectedView(source.id());
            var sourceHistory = fixture.history(source);
            var first = fixture.startYaml(parent("first", savedSource), "rcp2/first");
            var firstView = fixture.control.selectedView(first.id());
            var firstHistory = fixture.history(first);
            var second = fixture.startYaml(parent("second", savedSource), "rcp2/second");
            var selected = fixture.control.selectedView(second.id());
            assertEquals(Set.of(second.id().value(), source.id().value()), selected.managedDocuments()
                    .stream().map(document -> document.documentId().value()).collect(Collectors.toSet()));
            assertEquals(1, selected.occurrences().size());
            assertEquals(second.id().value(), selected.occurrences().get(0).sourceDocumentId().value());
            assertEquals(source.id().value(), selected.occurrences().get(0).targetDocumentId().value());
            assertEquals(sourceView.closureIdentity(), fixture.control.selectedView(source.id()).closureIdentity());
            assertEquals(firstView.closureIdentity(), fixture.control.selectedView(first.id()).closureIdentity());
            assertEquals(sourceHistory, fixture.history(source));
            assertEquals(firstHistory, fixture.history(first));
            assertEquals(savedSource, source.snapshot().blueId());
            blue.coordination.internal.CoordinationTestControl.attach(fixture.blue.advanced().rawEngine()).restartFromStores();
            assertEquals(selected.closureIdentity(), fixture.control.selectedView(second.id()).closureIdentity());
            assertEquals(sourceView.closureIdentity(), fixture.control.selectedView(source.id()).closureIdentity());
            assertEquals(firstView.closureIdentity(), fixture.control.selectedView(first.id()).closureIdentity());
            assertEquals(sourceHistory, fixture.history(source));
            assertEquals(firstHistory, fixture.history(first));
        }
    }

    private static String parent(String name, String source) throws Exception {
        return RootedSdkFixture.resource("parent.yaml").replace("RCP2 Parent", "Rooted " + name)
                .replace("rcp2/parent", "rcp2/" + name) + "\nchild:\n  blueId: " + source + "\n";
    }
}
