package blue.coordination.sdk;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** The original RCP source/parent ownership and gas oracles, fed an ordinary message. */
final class GeneralTimelineSourceOwnershipTest {
    @Test void incomingConsumersDoNotChangeGeneralSourcePublicationOrGas() throws Exception {
        // given
        var fixture = new RootedSourceIsolationTest().generalEntries();
        var baseline = fixture.verifySource(0);
        // when
        var one = fixture.verifySource(1);
        var two = fixture.verifySource(2);
        // then
        assertEquals(baseline, one);
        assertEquals(baseline, two);
    }

    @Test void generalParentLiveExecutionIsIndependentOfSourcePublicationOrder() throws Exception {
        // given
        var fixture = new RootedSourceIsolationTest().generalEntries();
        var before = fixture.verifyParent(false);
        // when
        var after = fixture.verifyParent(true);
        // then
        assertEquals(before, after);
    }

    @Test void failedGeneralSiblingRollsBackWithoutChangingIndependentParent() throws Exception {
        // given
        var fixture = new RootedSourceIsolationTest().generalEntries();
        var baseline = fixture.verifyParent(true, false);
        // when
        var afterFailure = fixture.verifyParent(true, true);
        // then
        assertEquals(baseline, afterFailure);
    }
}
