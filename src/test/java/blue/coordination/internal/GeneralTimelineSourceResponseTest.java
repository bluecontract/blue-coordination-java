package blue.coordination.internal;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

final class GeneralTimelineSourceResponseTest {
    @Test void generalSourceResponseRetainsExactOriginalAndRejectsForeignTerminal() {
        // given
        var scenario = new SourcePrerequisiteResultStorageValidationTest();
        // when
        org.junit.jupiter.api.function.Executable execute = () -> scenario.verifyLiveResponse(true);
        // then
        assertDoesNotThrow(execute);
    }
}
