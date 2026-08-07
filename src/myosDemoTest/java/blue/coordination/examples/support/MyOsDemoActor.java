package blue.coordination.examples.support;

import java.util.Objects;

/** Exact actor identity used by one MyOS demo Timeline. */
public record MyOsDemoActor(String actorId, String actorType, String accountId) {

    public MyOsDemoActor {
        Objects.requireNonNull(actorId, "actorId");
        Objects.requireNonNull(actorType, "actorType");
        Objects.requireNonNull(accountId, "accountId");
    }

    public static MyOsDemoActor principal(String actorId) {
        return new MyOsDemoActor(actorId, "MyOS/Principal Actor", actorId);
    }

    public static MyOsDemoActor agent(String actorId) {
        return new MyOsDemoActor(actorId, "MyOS/MyOS Agent Actor", actorId);
    }

    public static MyOsDemoActor admin() {
        return new MyOsDemoActor(
                "myos-admin", "MyOS/MyOS Admin Actor", "myos-admin");
    }

    String toYaml(int spaces) {
        String indent = " ".repeat(spaces);
        return """
                %stype: %s
                %saccountId: %s
                """.formatted(indent, actorType, indent, accountId);
    }
}
