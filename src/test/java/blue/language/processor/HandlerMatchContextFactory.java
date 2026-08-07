package blue.language.processor;

import blue.coordination.processor.CoordinationTestRuntime;
import blue.language.model.Node;
import blue.language.processor.model.MarkerContract;
import java.util.Collections;
import java.util.Map;

public final class HandlerMatchContextFactory {
    private HandlerMatchContextFactory() {
    }

    public static HandlerMatchContext create(CoordinationTestRuntime runtime,
                                             String handlerKey,
                                             String channelKey,
                                             Node event) {
        Map<String, MarkerContract> markers = Collections.emptyMap();
        return new HandlerMatchContext("/",
                handlerKey,
                channelKey,
                event,
                markers,
                new ContractMatchingService(
                        runtime.language()
                                .processing()
                                .runtimeAccess()),
                new RuntimeWorkSession(
                        new GasMeter(),
                        RuntimeWorkSession.Mode.PROCESSING));
    }
}
