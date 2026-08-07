package blue.coordination.examples.support;

import java.util.Objects;

/** Authored Operation Request payload and its source/target Channel roles. */
public record MyOsDemoOperation(
        String operation,
        String sourceChannel,
        String handlerChannel,
        String requestYaml,
        MyOsDemoAuthority authority) {

    public MyOsDemoOperation {
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(sourceChannel, "sourceChannel");
        Objects.requireNonNull(handlerChannel, "handlerChannel");
        requestYaml = requestYaml == null || requestYaml.isBlank()
                ? "{}"
                : requestYaml.strip();
    }

    public static Builder operation(String operation) {
        return new Builder(operation);
    }

    public static final class Builder {
        private final String operation;
        private String sourceChannel;
        private String handlerChannel;
        private String requestYaml = "{}";
        private MyOsDemoAuthority authority;

        private Builder(String operation) {
            this.operation = Objects.requireNonNull(operation, "operation");
        }

        public Builder through(String channel) {
            sourceChannel = channel;
            handlerChannel = channel;
            return this;
        }

        public Builder from(String source) {
            sourceChannel = source;
            return this;
        }

        public Builder to(String target) {
            handlerChannel = target;
            return this;
        }

        public Builder request(String yaml) {
            requestYaml = yaml;
            return this;
        }

        public Builder onBehalfOf(MyOsDemoAuthority value) {
            authority = value;
            return this;
        }

        public MyOsDemoOperation build() {
            return new MyOsDemoOperation(
                    operation,
                    Objects.requireNonNull(sourceChannel, "sourceChannel"),
                    Objects.requireNonNull(handlerChannel, "handlerChannel"),
                    requestYaml,
                    authority);
        }
    }
}
