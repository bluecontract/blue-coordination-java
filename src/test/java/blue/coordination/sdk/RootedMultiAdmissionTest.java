package blue.coordination.sdk;

import blue.coordination.internal.CoordinationTestControl;
import java.util.LinkedHashMap;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** The packaged two-pair sequence admits exact component inventories in either lexical order. */
final class RootedMultiAdmissionTest {
    @Test void independentParentAdmissionsRetainCompleteExactComponents() throws Exception {
        try (var f = new RootedSdkFixture()) {
            var handles = new LinkedHashMap<String, DocumentHandle>();
            var histories = new LinkedHashMap<String, java.util.List<String>>();
            for (String role : new String[]{"parent-first", "source-first"}) {
                String st = "preview/" + role + "/source", pt = "preview/" + role + "/parent";
                var source = f.startYaml(RootedSdkFixture.resource("source.yaml").replace("rcp2/source", st), st);
                var parent = f.startYaml(RootedSdkFixture.resource("parent.yaml").replace("rcp2/parent", pt)
                        + "\nchild:\n  blueId: " + source.snapshot().blueId() + "\n", pt);
                var event = f.append(source, st, "tick", 100L, "{}", true);
                if (role.equals("source-first"))
                    assertEquals(EntryDisposition.APPLIED, f.blue.processing().processNext(source).entry(event).disposition());
                var sourcePrefix = f.history(source);
                assertEquals(EntryDisposition.APPLIED, f.blue.processing().processNext(parent).entry(event).disposition());
                assertEquals(sourcePrefix, f.history(source));
                assertEquals(1L, ((Number) parent.snapshot().valueAt("/seen").copyNode().getValue()).longValue());
                if (role.equals("parent-first"))
                    assertEquals(EntryDisposition.APPLIED, f.blue.processing().processNext(source).entry(event).disposition());
                handles.put(role + "/source", source); handles.put(role + "/parent", parent);
                for (var prior : histories.entrySet()) assertEquals(prior.getValue(), f.history(handles.get(prior.getKey())));
                handles.forEach((key, handle) -> histories.put(key, f.history(handle)));
            }
            var heads = new LinkedHashMap<String, String>();
            handles.forEach((key, handle) -> heads.put(key, handle.snapshot().blueId()));
            CoordinationTestControl.attach(f.blue.advanced().rawEngine()).restartFromStores();
            handles.forEach((key, handle) -> {
                assertEquals(heads.get(key), handle.snapshot().blueId());
                assertEquals(histories.get(key), f.history(handle));
            });
        }
    }
}
