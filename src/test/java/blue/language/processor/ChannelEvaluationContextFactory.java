package blue.language.processor;

import blue.language.model.Node;
import blue.language.processor.model.ChannelContract;
import blue.language.processor.model.MarkerContract;
import java.util.Map;

public final class ChannelEvaluationContextFactory {
    private ChannelEvaluationContextFactory() {
    }

    @SafeVarargs
    public static ChannelEvaluationContext create(String bindingKey,
                                                  Node event,
                                                  Map<String, ChannelContract> channels,
                                                  Map<String, MarkerContract> markers,
                                                  ChannelProcessor<? extends ChannelContract>... processors) {
        ContractProcessorRegistry registry = new ContractProcessorRegistry();
        if (processors != null) {
            for (ChannelProcessor<? extends ChannelContract> processor : processors) {
                register(registry, processor);
            }
        }
        return new ChannelEvaluationContext("/", bindingKey, event, null, channels, markers, registry);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void register(ContractProcessorRegistry registry, ChannelProcessor processor) {
        registry.registerChannel(processor);
    }
}
