package org.dlr.foobar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Assumptions;
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
    void explicitClasspathResolvesProjectTypesWithoutRunningABuildTool() throws Exception {
        Path project = Files.createDirectory(temporaryDirectory.resolve("classpath-project"));
        Path dependencySources = Files.createDirectories(project.resolve("dependency/p"));
        Path classes = Files.createDirectory(project.resolve("classes"));
        Path incompleteClasses = Files.createDirectory(project.resolve("incomplete-classes"));
        Path sources = Files.createDirectory(project.resolve("sources"));
        Path dependency = dependencySources.resolve("Callbacks.java");
        Files.writeString(dependency, callbackTypes(), StandardCharsets.UTF_8);
        compile(classes, dependency);
        Files.writeString(sources.resolve("Sample.java"), classpathDependentSource(), StandardCharsets.UTF_8);
        Files.writeString(
                sources.resolve("module-info.java"),
                "open module sample.module { requires unavailable.module; }\n",
                StandardCharsets.UTF_8);
        Path classpathFile = project.resolve("classpath.txt");
        Files.writeString(classpathFile, "classes\n", StandardCharsets.UTF_8);
        Path incompleteClasspathFile = project.resolve("incomplete-classpath.txt");
        Files.writeString(incompleteClasspathFile, "incomplete-classes\n", StandardCharsets.UTF_8);
        Path incompleteClasspathErrors = project.resolve("incomplete-classpath-errors.txt");
        Path errors = project.resolve("errors.txt");

        ProcessResult unresolved = runStone(
                project,
                incompleteClasspathErrors,
                "--source-root=sources",
                "--classpath-file=" + incompleteClasspathFile,
                "--skipclones");
        ProcessResult result = runStone(
                project,
                errors,
                "--source-root=sources",
                "--classpath-file=" + classpathFile,
                "--skipclones");

        assertEquals(2, unresolved.exitCode(), unresolved.stderr() + unresolved.stdout());
        assertTrue(unresolved.stderr().contains("Analysis incomplete"));
        assertFalse(Files.readString(incompleteClasspathErrors).isEmpty());
        assertEquals(0, result.exitCode(), result.stderr() + result.stdout());
        assertTrue(result.stderr().contains("Successfully created AST for 2 out of 2 files"));
        assertTrue(result.stderr().contains("Successfully encoded paths for 1 out of 1 methods"));
        assertTrue(result.stdout().isEmpty(), result.stdout());
        assertTrue(Files.readString(errors).isEmpty());
    }

    @Test
    void explicitSourceRootsAnalyzeOnlyTheRequestedSourceSet() throws Exception {
        Path project = Files.createDirectory(temporaryDirectory.resolve("source-root-project"));
        Path selectedOne = Files.createDirectory(project.resolve("selected"));
        Path selectedTwo = Files.createDirectory(selectedOne.resolve("generated"));
        Path excluded = Files.createDirectory(project.resolve("excluded"));
        Files.writeString(selectedOne.resolve("ValidOne.java"), cloneSource(1), StandardCharsets.UTF_8);
        Files.writeString(selectedTwo.resolve("ValidTwo.java"), cloneSource(2), StandardCharsets.UTF_8);
        Files.writeString(excluded.resolve("Broken.java"), "class Broken { void method( { }", StandardCharsets.UTF_8);
        Path errors = project.resolve("errors.txt");

        ProcessResult result = runStone(
                project,
                errors,
                "--source-root=selected",
                "--source-root=selected/generated",
                "--skipclones");

        assertEquals(0, result.exitCode(), result.stderr() + result.stdout());
        assertTrue(result.stderr().contains("Successfully created AST for 2 out of 2 files"));
        assertTrue(result.stdout().isEmpty(), result.stdout());
        assertTrue(Files.readString(errors).isEmpty());
    }

    @Test
    void explicitSourceManifestAnalyzesOnlyValidatedDeduplicatedSources() throws Exception {
        Path project = Files.createDirectory(temporaryDirectory.resolve("source-manifest-project"));
        Path selected = Files.createDirectory(project.resolve("selected"));
        Path first = selected.resolve("First.java");
        Path second = selected.resolve("Second.java");
        Files.writeString(first, cloneSource(1), StandardCharsets.UTF_8);
        Files.writeString(second, cloneSource(2), StandardCharsets.UTF_8);
        Files.writeString(
                project.resolve("Broken.java"),
                "class Broken { void method( { }",
                StandardCharsets.UTF_8);
        Path alias = project.resolve("FirstAlias.java");
        createSymbolicLinkOrSkip(alias, project.relativize(first));
        Path manifest = project.resolve("sources.txt");
        Files.writeString(
                manifest,
                "selected/Second.java\n"
                        + first.toAbsolutePath()
                        + "\nFirstAlias.java\n\n",
                StandardCharsets.UTF_8);
        Path errors = project.resolve("errors.txt");

        ProcessResult result = runStone(
                project,
                errors,
                "--source-file-list=" + manifest,
                "--skipclones");

        assertEquals(0, result.exitCode(), result.stderr() + result.stdout());
        assertTrue(result.stderr().contains("Successfully created AST for 2 out of 2 files"));
        assertFalse(result.stderr().contains("Broken.java"));
        assertTrue(Files.readString(errors).isEmpty());
    }

    @Test
    void sourceManifestAcceptsUtf8BomOnItsFirstEntry() throws Exception {
        Path project = Files.createDirectory(temporaryDirectory.resolve("bom-source-manifest"));
        Path source = project.resolve("Valid.java");
        Files.writeString(source, cloneSource(1), StandardCharsets.UTF_8);
        Path manifest = project.resolve("sources.txt");
        Files.writeString(manifest, "\uFEFFValid.java\n", StandardCharsets.UTF_8);
        Path errors = project.resolve("errors.txt");

        ProcessResult result = runStone(
                project,
                errors,
                "--source-file-list=" + manifest,
                "--skipclones");

        assertEquals(0, result.exitCode(), result.stderr() + result.stdout());
        assertTrue(result.stderr().contains("Successfully created AST for 1 out of 1 files"));
        assertTrue(Files.readString(errors).isEmpty());
    }

    @Test
    void sourceManifestDeduplicatesHardLinkedSources() throws Exception {
        Path project = Files.createDirectory(temporaryDirectory.resolve("hard-link-source-manifest"));
        Path source = project.resolve("Original.java");
        Files.writeString(
                source,
                "public class Original { void method() {} }\n",
                StandardCharsets.UTF_8);
        Path alias = project.resolve("Alias.java");
        createHardLinkOrSkip(alias, source);
        Path manifest = project.resolve("sources.txt");
        Files.writeString(manifest, "Original.java\nAlias.java\n", StandardCharsets.UTF_8);
        Path errors = project.resolve("errors.txt");

        ProcessResult result = runStone(
                project,
                errors,
                "--source-file-list=" + manifest,
                "--skipclones");

        assertEquals(0, result.exitCode(), result.stderr() + result.stdout());
        assertTrue(result.stderr().contains("Successfully created AST for 1 out of 1 files"));
        assertTrue(result.stderr().contains("Original.java"), result.stderr());
        assertFalse(result.stderr().contains("Alias.java"), result.stderr());
        assertTrue(Files.readString(errors).isEmpty());
    }

    @Test
    void selectedManifestFailureRemainsFailClosedAndDeterministic() throws Exception {
        Path project = Files.createDirectory(temporaryDirectory.resolve("failing-manifest-project"));
        Path first = project.resolve("FirstBroken.java");
        Path second = project.resolve("SecondBroken.java");
        Files.writeString(first, classpathDependentSource(), StandardCharsets.UTF_8);
        Files.writeString(second, classpathDependentSource(), StandardCharsets.UTF_8);
        Path manifest = project.resolve("sources.txt");
        Files.writeString(
                manifest,
                "SecondBroken.java\nFirstBroken.java\n",
                StandardCharsets.UTF_8);
        Files.createDirectory(project.resolve("incomplete-classes"));
        Path classpathFile = project.resolve("incomplete-classpath.txt");
        Files.writeString(classpathFile, "incomplete-classes\n", StandardCharsets.UTF_8);
        Path errors = project.resolve("errors.txt");

        ProcessResult result = runStone(
                project,
                errors,
                "--source-file-list=" + manifest,
                "--classpath-file=" + classpathFile,
                "--skipclones");
        String diagnostics = Files.readString(errors);

        assertEquals(2, result.exitCode(), result.stderr() + result.stdout());
        assertTrue(result.stderr().contains("Successfully created AST for 0 out of 2 files"));
        assertTrue(result.stderr().contains("Analysis incomplete"));
        assertTrue(diagnostics.indexOf("FirstBroken.java") < diagnostics.indexOf("SecondBroken.java"));
    }

    @Test
    void sourceManifestCannotBeCombinedWithSourceRoots() throws Exception {
        Path project = Files.createDirectory(temporaryDirectory.resolve("conflicting-source-selection"));
        Path source = project.resolve("Valid.java");
        Files.writeString(source, cloneSource(1), StandardCharsets.UTF_8);
        Path manifest = project.resolve("sources.txt");
        Files.writeString(manifest, "Valid.java\n", StandardCharsets.UTF_8);
        Path errors = project.resolve("errors.txt");

        ProcessResult result = runStone(
                project,
                errors,
                "--source-file-list=" + manifest,
                "--source-root=.",
                "--skipclones");

        assertEquals(1, result.exitCode(), result.stderr() + result.stdout());
        assertTrue(result.stdout().contains(
                "--source-file-list cannot be combined with --source-root"));
        assertFalse(result.stderr().contains("Parsing Java source file"));
        assertFalse(Files.exists(errors));
    }

    @Test
    void sourceManifestRejectsInvalidEntriesBeforeAnalysis() throws Exception {
        Path project = Files.createDirectory(temporaryDirectory.resolve("invalid-source-manifest"));
        Path nonJava = project.resolve("README.txt");
        Files.writeString(nonJava, "not Java", StandardCharsets.UTF_8);
        Path external = temporaryDirectory.resolve("External.java");
        Files.writeString(external, cloneSource(3), StandardCharsets.UTF_8);

        assertManifestRejected(project, "missing.java\n", "Source file is not a readable file");
        assertManifestRejected(project, "README.txt\n", "Source file is not a Java source");
        assertManifestRejected(
                project,
                external.toAbsolutePath() + "\n",
                "Source file resolves outside the working directory");
        assertManifestRejected(project, "invalid\u0000path.java\n", "invalid path");
        assertManifestRejected(project, "\n  \n", "contains no Java sources");
    }

    @Test
    void invalidExplicitClasspathFailsBeforeSourceAnalysis() throws Exception {
        Path project = Files.createDirectory(temporaryDirectory.resolve("invalid-classpath-project"));
        Files.writeString(project.resolve("Valid.java"), cloneSource(1), StandardCharsets.UTF_8);
        Path classpathFile = project.resolve("classpath.txt");
        Files.writeString(classpathFile, "missing/classes\n", StandardCharsets.UTF_8);
        Path errors = project.resolve("errors.txt");

        ProcessResult result =
                runStone(project, errors, "--classpath-file=" + classpathFile, "--skipclones");

        assertEquals(1, result.exitCode(), result.stderr() + result.stdout());
        assertTrue(result.stdout().contains("Classpath entry does not exist: missing/classes"));
        assertFalse(result.stderr().contains("Parsing Java source file"));
        assertFalse(Files.exists(errors));
    }

    @Test
    void inTreeSymbolicLinkRootsAreCanonicalizedAndAnalyzedOnce() throws Exception {
        Path project = Files.createDirectory(temporaryDirectory.resolve("symlink-source-root-project"));
        Path sources = Files.createDirectory(project.resolve("sources"));
        Files.writeString(sources.resolve("Valid.java"), cloneSource(1), StandardCharsets.UTF_8);
        Path linkedSources = project.resolve("linked-sources");
        createSymbolicLinkOrSkip(linkedSources, sources.getFileName());
        Path errors = project.resolve("errors.txt");

        ProcessResult result = runStone(
                project,
                errors,
                "--source-root=sources",
                "--source-root=linked-sources",
                "--skipclones");

        assertEquals(0, result.exitCode(), result.stderr() + result.stdout());
        assertTrue(result.stderr().contains("Successfully created AST for 1 out of 1 files"));
        assertTrue(result.stdout().isEmpty(), result.stdout());
        assertTrue(Files.readString(errors).isEmpty());
    }

    @Test
    void sourceRootsDeduplicateHardLinkedSources() throws Exception {
        Path project = Files.createDirectory(temporaryDirectory.resolve("hard-link-source-root"));
        Path source = project.resolve("Original.java");
        Files.writeString(source, cloneSource(1), StandardCharsets.UTF_8);
        createHardLinkOrSkip(project.resolve("Alias.java"), source);
        Path errors = project.resolve("errors.txt");

        ProcessResult result = runStone(project, errors, "--skipclones");

        assertEquals(0, result.exitCode(), result.stderr() + result.stdout());
        assertTrue(result.stderr().contains("Successfully created AST for 1 out of 1 files"));
        assertTrue(Files.readString(errors).isEmpty());
    }

    @Test
    void intermediateSymbolicLinkCannotEscapeTheWorkingDirectory() throws Exception {
        Path project = Files.createDirectory(temporaryDirectory.resolve("symlink-containment-project"));
        Path external = Files.createDirectories(temporaryDirectory.resolve("external/sources"));
        Files.writeString(external.resolve("External.java"), cloneSource(1), StandardCharsets.UTF_8);
        Path linkedParent = project.resolve("linked-parent");
        createSymbolicLinkOrSkip(linkedParent, external.getParent());
        Path errors = project.resolve("errors.txt");

        ProcessResult result =
                runStone(project, errors, "--source-root=linked-parent/sources", "--skipclones");

        assertEquals(1, result.exitCode(), result.stderr() + result.stdout());
        assertTrue(result.stdout().contains(
                "Source root resolves outside the working directory: linked-parent/sources"));
        assertFalse(result.stderr().contains("Parsing Java source file"));
        assertFalse(Files.exists(errors));
    }

    @Test
    void canonicalAbsoluteRootWorksWithASymbolicLinkWorkingDirectory() throws Exception {
        Path project = Files.createDirectory(temporaryDirectory.resolve("real-project"));
        Path sources = Files.createDirectory(project.resolve("sources"));
        Files.writeString(sources.resolve("Valid.java"), cloneSource(1), StandardCharsets.UTF_8);
        Path linkedProject = temporaryDirectory.resolve("linked-project");
        createSymbolicLinkOrSkip(linkedProject, project);
        Path errors = project.resolve("errors.txt");

        ProcessResult result =
                runStone(linkedProject, errors, "--source-root=" + sources, "--skipclones");

        assertEquals(0, result.exitCode(), result.stderr() + result.stdout());
        assertTrue(result.stderr().contains("Successfully created AST for 1 out of 1 files"));
        assertTrue(result.stdout().isEmpty(), result.stdout());
        assertTrue(Files.readString(errors).isEmpty());
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

    private void assertManifestRejected(Path project, String contents, String expectedMessage)
            throws Exception {
        Path manifest = project.resolve("manifest-" + Math.abs(contents.hashCode()) + ".txt");
        Files.writeString(manifest, contents, StandardCharsets.UTF_8);
        Path errors = project.resolve("errors-" + Math.abs(contents.hashCode()) + ".txt");

        ProcessResult result = runStone(
                project,
                errors,
                "--source-file-list=" + manifest,
                "--skipclones");

        assertEquals(1, result.exitCode(), result.stderr() + result.stdout());
        assertTrue(result.stdout().contains(expectedMessage), result.stdout());
        assertFalse(result.stderr().contains("Parsing Java source file"));
        assertFalse(Files.exists(errors));
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

    private static String callbackTypes() {
        return """
                package p;

                public final class Callbacks {
                    private Callbacks() {}

                    public interface Source {
                        Object call(java.util.function.Function<Object, Object> callback);
                    }

                    public interface Marker {}
                }
                """;
    }

    private static String classpathDependentSource() {
        return """
                import p.Callbacks.Marker;
                import p.Callbacks.Source;

                class Sample {
                    Object transform(Source source) {
                        return source.call(value -> {
                            final class Local implements Marker {}
                            return new Local();
                        });
                    }
                }
                """;
    }

    private static void compile(Path outputDirectory, Path source) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertTrue(compiler != null, "Tests require a JDK, not a JRE");
        int result = compiler.run(
                null,
                OutputStream.nullOutputStream(),
                OutputStream.nullOutputStream(),
                "-d",
                outputDirectory.toString(),
                source.toString());
        assertEquals(0, result, "Dependency fixture must compile");
    }

    private static void createSymbolicLinkOrSkip(Path link, Path target) {
        try {
            Files.createSymbolicLink(link, target);
        } catch (IOException | UnsupportedOperationException | SecurityException exception) {
            Assumptions.assumeTrue(
                    false, "Symbolic links are unavailable: " + exception.getClass().getSimpleName());
        }
    }

    private static void createHardLinkOrSkip(Path link, Path target) {
        try {
            Files.createLink(link, target);
        } catch (IOException | UnsupportedOperationException | SecurityException exception) {
            Assumptions.assumeTrue(
                    false, "Hard links are unavailable: " + exception.getClass().getSimpleName());
        }
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
