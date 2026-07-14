package org.fsu.codeclones;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fr.inria.controlflow.BranchKind;
import fr.inria.controlflow.ControlFlowGraph;
import fr.inria.controlflow.ControlFlowNode;
import java.util.List;
import org.junit.jupiter.api.Test;
import spoon.Launcher;
import spoon.support.compiler.VirtualFile;
import spoon.reflect.code.CtCasePattern;
import spoon.reflect.code.CtLocalVariable;
import spoon.reflect.code.CtReturn;
import spoon.reflect.declaration.CtType;
import spoon.reflect.visitor.filter.TypeFilter;

class ModernExpressionEncoderTest {

    @Test
    void encodesModernExpressionsWithoutDroppingTheContainingMethod() {
        String source = """
                class Sample {
                    record Pair(String first, String second) {}

                    Object transform(Object candidate) {
                        java.util.function.Function<String, String> reference = String::trim;
                        java.util.function.Predicate<String> lambda = text -> text.isBlank();
                        return switch (candidate) {
                            case String text -> lambda.test(reference.apply(text));
                            case Pair(_, _) -> candidate;
                            default -> null;
                        };
                    }
                }
                """;
        Launcher launcher = new Launcher();
        launcher.getEnvironment().setComplianceLevel(25);
        launcher.addInputResource(new VirtualFile(source, "Sample.java"));
        launcher.buildModel();
        CtType<?> sample = launcher.getFactory().Type().get("Sample");

        List<CtLocalVariable<?>> variables =
                sample.getElements(new TypeFilter<>(CtLocalVariable.class));
        CtReturn<?> returnStatement = sample.getElements(new TypeFilter<>(CtReturn.class)).get(0);
        List<CtCasePattern> casePatterns =
                sample.getElements(new TypeFilter<>(CtCasePattern.class));
        assertFalse(casePatterns.isEmpty());

        for (CtLocalVariable<?> variable : variables) {
            assertDoesNotThrow(() -> assertEncodes(variable));
        }
        assertDoesNotThrow(() -> assertEncodes(returnStatement));
        for (CtCasePattern casePattern : casePatterns) {
            assertDoesNotThrow(() -> assertEncodes(casePattern));
        }
    }

    private static void assertEncodes(spoon.reflect.declaration.CtElement statement) {
        ControlFlowNode node =
                new ControlFlowNode(statement, new ControlFlowGraph(), BranchKind.STATEMENT);

        CompletePathEncoder complete = new CompletePathEncoder(node);
        SortedMultisetPathEncoder multiset = new SortedMultisetPathEncoder(node);

        assertTrue(complete.getEncoding().length > 0);
        assertTrue(multiset.getEncoding().length > 0);
    }
}
