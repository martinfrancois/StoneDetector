package org.dlr.foobar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AnalysisCompletenessTest {

    @TempDir Path temporaryDirectory;

    @Test
    void incompleteAnalysisReturnsNonzeroAndWritesTheFailure() throws Exception {
        Path errors = temporaryDirectory.resolve("errors.txt");
        SpoonBigCloneBenchDriver driver = new SpoonBigCloneBenchDriver(temporaryDirectory.toString());

        driver.reportAnalysisFailure(new IllegalStateException("unsupported expression"));
        driver.logErrors(errors.toString());

        assertEquals(2, driver.analysisExitCode());
        assertTrue(Files.readString(errors).contains("unsupported expression"));
    }

    @Test
    void parallelAnalysisAccountsForEveryModernJavaSource() throws Exception {
        Path sources = Files.createDirectory(temporaryDirectory.resolve("valid"));
        for (int index = 0; index < 6; index++) {
            Files.writeString(
                    sources.resolve("Sample" + index + ".java"),
                    modernSource(index),
                    StandardCharsets.UTF_8);
        }
        Files.writeString(sources.resolve("README.txt"), "not Java", StandardCharsets.UTF_8);
        Path errors = temporaryDirectory.resolve("errors.txt");

        ProcessResult result = runStone(sources, errors, "--skipclones");

        assertEquals(0, result.exitCode(), result.stderr());
        assertTrue(result.stderr().contains("Successfully created AST for 6 out of 6 files"));
        assertTrue(
                Pattern.compile("Successfully encoded paths for (\\d+) out of \\1 methods")
                        .matcher(result.stderr())
                        .find(),
                result.stderr());
        assertTrue(Files.readString(errors).isEmpty());
        assertFalse(result.stderr().contains("Analysis incomplete"));
    }

    @Test
    void analyzesLanguageFeaturesThroughJava25() throws Exception {
        Path sources = Files.createDirectory(temporaryDirectory.resolve("java25"));
        for (FeatureSource feature : java25LanguageFeatures()) {
            Files.writeString(
                    sources.resolve(feature.fileName()), feature.source(), StandardCharsets.UTF_8);
        }
        Path errors = temporaryDirectory.resolve("java25-errors.txt");

        ProcessResult result = runStone(sources, errors, "--skipclones");

        assertEquals(0, result.exitCode(), result.stderr());
        assertTrue(result.stderr().contains("Successfully created AST for 5 out of 5 files"));
        assertTrue(result.stderr().contains("Successfully created CFG for 9 out of 9 methods"));
        assertTrue(result.stderr().contains("Successfully created DomTree for 9 out of 9 methods"));
        assertTrue(result.stderr().contains("Successfully encoded paths for 9 out of 9 methods"));
        assertTrue(result.stdout().isEmpty(), result.stdout());
        assertTrue(Files.readString(errors).isEmpty());
        assertFalse(result.stderr().contains("Analysis incomplete"));
    }

    @Test
    void parallelCloneOutputIsDeterministicAndSorted() throws Exception {
        Path sources = Files.createDirectory(temporaryDirectory.resolve("clones"));
        for (int index = 0; index < 6; index++) {
            Files.writeString(
                    sources.resolve("Clone" + index + ".java"),
                    cloneSource(index),
                    StandardCharsets.UTF_8);
        }

        ProcessResult first = runStone(sources, temporaryDirectory.resolve("first-errors.txt"));
        ProcessResult second = runStone(sources, temporaryDirectory.resolve("second-errors.txt"));
        List<String> outputLines = first.stdout().lines().toList();
        List<String> sortedLines = new ArrayList<>(outputLines);
        Collections.sort(sortedLines);

        assertEquals(0, first.exitCode(), first.stderr());
        assertEquals(0, second.exitCode(), second.stderr());
        assertFalse(outputLines.isEmpty());
        assertEquals(sortedLines, outputLines);
        assertEquals(first.stdout(), second.stdout());
    }

    private ProcessResult runStone(Path sources, Path errors, String... additionalArguments)
            throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(SpoonBigCloneBenchDriver.class.getName());
        command.add("--directory=" + sources);
        command.add("--error-file=" + errors);
        command.addAll(List.of(additionalArguments));

        Process process = new ProcessBuilder(command)
                .directory(Path.of("").toAbsolutePath().toFile())
                .start();
        String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        return new ProcessResult(process.waitFor(), stdout, stderr);
    }

    private static String modernSource(int index) {
        return """
                class Sample%d {
                    record Pair(String first, String second) {}

                    Object transform(Object candidate) {
                        java.util.function.Function<String, String> reference = String::trim;
                        return switch (candidate) {
                            case String text -> reference.apply(text);
                            case Pair(_, _) -> candidate;
                            default -> null;
                        };
                    }
                }
                """.formatted(index);
    }

    private static String cloneSource(int index) {
        return """
                class Clone%d {
                    int calculate(int input) {
                        int result = input;
                        result += 1;
                        result += 2;
                        result += 3;
                        result += 4;
                        result += 5;
                        result += 6;
                        result += 7;
                        result += 8;
                        result += 9;
                        result += 10;
                        result += 11;
                        result += 12;
                        result += 13;
                        result += 14;
                        result += 15;
                        return result;
                    }
                }
                """.formatted(index);
    }

    private static List<FeatureSource> java25LanguageFeatures() {
        return List.of(
                new FeatureSource("ModernLanguage.java", modernLanguageSource()),
                new FeatureSource("ModuleImport.java", moduleImportSource()),
                new FeatureSource("CompactSource.java", compactSource()),
                new FeatureSource("FlexibleConstructor.java", flexibleConstructorSource()),
                new FeatureSource("PrimitivePatterns.java", primitivePatternsSource()));
    }

    private static String modernLanguageSource() {
        return """
                sealed interface Shape permits Circle, Rectangle {}

                record Circle(double radius) implements Shape {}

                final class Rectangle implements Shape {
                    private final double width;

                    Rectangle(double width) {
                        this.width = width;
                    }

                    double width() {
                        return width;
                    }
                }

                class ModernLanguage {
                    /**
                     * ## Markdown heading
                     *
                     * * Java 23 documentation comments remain comments to clone analysis.
                     */
                    String describe(Shape shape) {
                        if (shape instanceof Rectangle rectangle && rectangle.width() > 0) {
                            return \"""
                                    positive rectangle
                                    \""";
                        }
                        return switch (shape) {
                            case Circle(double radius) when radius > 0 -> "circle " + radius;
                            case Rectangle _ -> "rectangle";
                            default -> "other";
                        };
                    }

                    void consume(java.util.List<String> values) {
                        for (String _ : values) {
                            Runnable task = () -> {
                                System.out.println("first");
                                System.out.println("second");
                            };
                            task.run();
                        }
                    }
                }
                """;
    }

    private static String moduleImportSource() {
        return """
                import module java.base;

                class ModuleImport {
                    List<String> values() {
                        return List.of("value");
                    }
                }
                """;
    }

    private static String compactSource() {
        return """
                void main() {
                    IO.println("compact source");
                }
                """;
    }

    private static String flexibleConstructorSource() {
        return """
                class Parent {
                    Parent(int value) {}
                }

                class FlexibleConstructor extends Parent {
                    FlexibleConstructor(String input) {
                        int parsed = Integer.parseInt(input);
                        super(parsed);
                    }
                }
                """;
    }

    private static String primitivePatternsSource() {
        return """
                class PrimitivePatterns {
                    long normalize(long value) {
                        return switch (value) {
                            case long number when number > 0L -> number;
                            default -> 0L;
                        };
                    }
                }
                """;
    }

    private record ProcessResult(int exitCode, String stdout, String stderr) {}

    private record FeatureSource(String fileName, String source) {}
}
