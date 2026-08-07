package blue.coordination.processor.bex;

import blue.bex.BexExecutionEvidenceUnavailableException;
import blue.bex.BexInvalidExecutionEvidenceException;
import blue.bex.api.BexEngine;
import blue.bex.api.BexExecutionContext;
import blue.bex.api.BexGasLedgerHost;
import blue.bex.contracts.BexContractsFailureBoundary;
import blue.bex.contracts.BexContractsExecutionContext;
import blue.bex.contracts.ProcessorExecutionContextBexDocumentView;
import blue.bex.contracts.ProcessorExecutionContextBexGasLedgerHost;
import blue.bex.contracts.ProcessorExecutionContextBexSemanticIdentityBoundary;
import blue.coordination.processor.CoordinationProcessorOptions;
import blue.coordination.processor.workflow.StepExecutionContext;
import blue.bex.value.BexValue;
import blue.language.processor.ExecutionEvidenceUnavailableException;
import blue.language.processor.InvalidExecutionEvidenceException;
import blue.language.runtime.BlueLanguage;

import java.util.Arrays;
import java.lang.reflect.Method;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class BexModularApiMigrationTest {

    @Test
    void shouldRetainConcreteStepContextAsDelegatingCompatibilityOverloads()
            throws NoSuchMethodException {
        // given
        Method create = BexWorkflowContextFactory.class.getMethod(
                "create", StepExecutionContext.class, long.class);
        Method currentContract =
                BexWorkflowContextFactory.class.getMethod(
                        "currentContractBinding",
                        StepExecutionContext.class);

        // when
        Class<?> createResult = create.getReturnType();
        Class<?> bindingResult = currentContract.getReturnType();

        // then
        assertEquals(BexExecutionContext.class, createResult);
        assertEquals(BexValue.class, bindingResult);
        assertTrue(create.isAnnotationPresent(Deprecated.class));
        assertTrue(currentContract.isAnnotationPresent(Deprecated.class));
    }

    @Test
    void shouldExposeOnlyCurrentContractsHostedAdapters()
            throws ClassNotFoundException {
        // given
        ClassLoader loader = getClass().getClassLoader();

        // when
        Class<?> composition = Class.forName(
                "blue.bex.contracts.BexContractsExecutionContext",
                false,
                loader);
        Class<?> gasHost = Class.forName(
                "blue.bex.contracts.ProcessorExecutionContextBexGasLedgerHost",
                false,
                loader);

        // then
        assertSame(BexContractsExecutionContext.class, composition);
        assertSame(ProcessorExecutionContextBexGasLedgerHost.class, gasHost);
        assertTrue(BexGasLedgerHost.class.isAssignableFrom(gasHost));
        assertTrue(ProcessorExecutionContextBexDocumentView.class
                .getName().startsWith("blue.bex.contracts."));
        assertTrue(ProcessorExecutionContextBexSemanticIdentityBoundary.class
                .getName().startsWith("blue.bex.contracts."));
        assertThrows(ClassNotFoundException.class,
                () -> Class.forName(
                        "blue.bex.api.ProcessorExecutionContextBexGasLedgerHost",
                        false,
                        loader));
        assertThrows(ClassNotFoundException.class,
                () -> Class.forName(
                        "blue.bex.api.ProcessorExecutionContextBexDocumentView",
                        false,
                        loader));
        assertThrows(ClassNotFoundException.class,
                () -> Class.forName(
                        "blue.bex.output.ProcessorExecutionContextBexSemanticIdentityBoundary",
                        false,
                        loader));
    }

    @Test
    void shouldResolveOneBlueLanguageClassAcrossBexAndCoordination()
            throws NoSuchMethodException {
        // given
        Class<?> bexLanguageParameter = BexEngine.Builder.class
                .getMethod("language", BlueLanguage.class)
                .getParameterTypes()[0];
        Class<?> coordinationLanguageResult =
                CoordinationProcessorOptions.class
                        .getMethod("language")
                        .getReturnType();

        // when
        ClassLoader bexLanguageLoader =
                bexLanguageParameter.getClassLoader();
        ClassLoader coordinationLanguageLoader =
                coordinationLanguageResult.getClassLoader();

        // then
        assertSame(BlueLanguage.class, bexLanguageParameter);
        assertSame(BlueLanguage.class, coordinationLanguageResult);
        assertSame(bexLanguageLoader, coordinationLanguageLoader);
    }

    @Test
    void shouldRetainExactBorrowedLanguageRuntimeInOptions() {
        // given
        BlueLanguage language = BlueLanguage.builder().build();
        try {
            // when
            CoordinationProcessorOptions options =
                    CoordinationProcessorOptions.builder()
                            .language(language)
                            .build();

            // then
            assertSame(language, options.language());
        } finally {
            language.close();
        }
    }

    @Test
    void shouldPreserveUnavailableAndInvalidEvidenceClassifications() {
        // given
        BexExecutionEvidenceUnavailableException unavailable =
                new BexExecutionEvidenceUnavailableException(
                        "provider temporarily unavailable",
                        Arrays.asList("z-id", "a-id"));
        BexInvalidExecutionEvidenceException invalid =
                new BexInvalidExecutionEvidenceException(
                        "provider returned invalid evidence");

        // when
        RuntimeException translatedUnavailable =
                BexContractsFailureBoundary.INSTANCE.translate(
                        unavailable);
        RuntimeException translatedInvalid =
                BexContractsFailureBoundary.INSTANCE.translate(
                        invalid);

        // then
        ExecutionEvidenceUnavailableException exactUnavailable =
                assertInstanceOf(
                        ExecutionEvidenceUnavailableException.class,
                        translatedUnavailable);
        assertEquals(Arrays.asList("a-id", "z-id"),
                exactUnavailable.requiredExactBlueIds());
        assertInstanceOf(InvalidExecutionEvidenceException.class,
                translatedInvalid);
    }
}
