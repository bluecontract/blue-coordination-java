package blue.coordination.internal;

import blue.coordination.api.*;
import blue.coordination.api.storage.*;
import blue.language.model.Node;
import blue.language.processor.ExternalOrderKey;
import java.math.BigInteger;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.Function;

/** Fixture generator compiled only against the authenticated preceding candidate. */
public final class CaptureSessionFrames {
    public static void main(String[] args) throws Exception {
        Map<String, byte[]> records = new LinkedHashMap<>();
        var bytes = new CoordinationImmutableObjectStore() {
            public Optional<byte[]> get(String address, int maximum) { return Optional.ofNullable(records.get(address)); }
            public byte[] putIfAbsent(String address, byte[] frame) {
                records.putIfAbsent(address, frame.clone()); return records.get(address).clone();
            }
        };
        var limits = new DocumentSessionStorage.Limits(4*1024*1024,256,32L*1024*1024);
        var storage = new DocumentSessionStorage(bytes, limits);
        var order = ExternalOrderKey.of(List.of(BigInteger.ONE,"timeline","entry"));
        var initial = ExactValue.verified(new Node().name("baseline-session").value("original"));
        var id = DocumentId.of("baseline-session");
        var revision = new DocumentRevision(id,0,0,DocumentRevision.Kind.INITIALIZATION,
                null,initial,null,order,initial.blueId(),null,List.of(),0,null);
        var layout = new EmbeddedOnlyLayout(initial,initial.frozen(),Map.of("/",initial),List.of(),List.of(),
                EmbeddedLayoutPlan.managedRoot(new RoutingSurface(List.of(),false)));
        var session = new DocumentSession(id, initial, layout,List.of(),order,revision);
        String flatAddress = storage.retain(session);
        var encoder = DocumentSessionStorage.class.getDeclaredMethod("encodeSession",DocumentSession.StoredState.class,Function.class,Function.class);
        encoder.setAccessible(true);
        Function<RootedDocumentView,String> noViews = ignored -> {throw new AssertionError("unexpected view");};
        byte[] inline=(byte[])encoder.invoke(storage,session.storedState(),noViews,null);
        String inlineAddress=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(inline));
        records.put(inlineAddress,inline);
        try (var cold=storage.openScope()) {
            for(String address:List.of(flatAddress,inlineAddress)) {
                var restored=cold.open(id,address);
                if(restored.epoch()!=0 || !restored.currentRepresentation().sameExactValue(initial)) throw new AssertionError("baseline roundtrip");
            }
        }
        Path out=Path.of(args[0]);Files.write(out.resolve("session-inline-v1.bin"),inline);
        Files.write(out.resolve("session-flat-v2.bin"),records.get(flatAddress));
        System.out.println("baselineRoundtrip=PASS inline="+inlineAddress+" flat="+flatAddress+" representation="+initial.blueId());
    }
}
