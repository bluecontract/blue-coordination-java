package blue.coordination.engine.fastpath;

import blue.coordination.engine.CoordinationProcessingEngine
        .VerifiedNodeAccessAuthority;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

final class ExactNodeHandleIsolationTest {

    @Test
    void publicBorrowMustNotExposeTheVerifiedMutableNode() {
        // given
        Object owner = new Object();
        Node exact = new Node().properties(
                "value", new Node().value("verified"));
        String blueId = DirectBlueIdCalculator.calculateBlueId(exact);
        ExactNodeHandle handle = ExactNodeHandle.copyAndVerify(
                blueId, exact, owner);

        // when
        Node publicBorrow = handle.borrow(owner);
        publicBorrow.properties("value", new Node().value("forged"));

        // then
        assertEquals(
                "verified",
                handle.copy().getProperties().get("value").getValue());
        assertEquals(
                blueId,
                DirectBlueIdCalculator.calculateBlueId(handle.copy()));
    }

    @Test
    void rawNodeAndOwnershipAuthoritiesMustNotBePubliclyObtainable() {
        // when / then
        for (Method method : ExactNodeHandle.class.getDeclaredMethods()) {
            if (method.getName().equals("borrowTrusted")) {
                assertFalse(Modifier.isPublic(method.getModifiers()));
            }
        }
        for (Method method
                : PreparedRootExecutionContext.class.getMethods()) {
            assertFalse(method.getName().equals("ownershipToken"));
        }
        assertEquals(
                0,
                VerifiedNodeAccessAuthority.class.getConstructors().length);
    }
}
