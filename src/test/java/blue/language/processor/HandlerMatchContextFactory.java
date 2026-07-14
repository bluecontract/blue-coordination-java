package blue.language.processor;

import blue.language.Blue;
import blue.language.model.Node;
import blue.language.processor.model.MarkerContract;
import java.util.Collections;
import java.util.Map;

public final class HandlerMatchContextFactory {
    private HandlerMatchContextFactory() {
    }

    public static HandlerMatchContext create(Blue blue,
                                             String handlerKey,
                                             String channelKey,
                                             Node event) {
        Map<String, MarkerContract> markers = Collections.emptyMap();
        return new HandlerMatchContext("/",
                handlerKey,
                channelKey,
                event,
                markers,
                new ContractMatchingService(blue));
    }
}
