package mchorse.bbs_mod.compat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Source contract for critical Gradle build and development run gates. */
final class BuildFailureGateSourceTest
{
    private BuildFailureGateSourceTest()
    {}

    static void runAll()
    {
        String build = readProjectFile("build.gradle");
        String readme = readProjectFile("README.md");

        check(build.contains("forceBuildProperty"), "build no longer reads the explicit forceBuild property");
        check(build.contains("ignoreExitValue = forceBuild"), "JavaExec test failures are no longer strict by default");
        check(build.contains("ignoreFailures = forceBuild"), "standard Gradle Test failures are not covered by the build gate");
        check(build.contains("BbsTestErrorOutputStream"), "uncaptured test ERROR diagnostics are not inspected");
        check(build.contains("dependsOn bbsVerificationTaskNames.collect"), "verification tasks drifted away from the check lifecycle");
        check(build.contains("systemProperty 'terminal.jline', 'false'"),
            "headless test processes may emit terminal capability warnings again");
        check(build.contains("runs {\n    client {\n        modSource sourceSets.client\n    }\n}"),
            "development client no longer includes the client source set");
        check(readme.contains("-PforceBuild=true"), "README does not document the only forced-build override");
    }

    private static String readProjectFile(String relativePath)
    {
        Path current = Path.of("").toAbsolutePath().normalize();

        for (int i = 0; i < 8 && current != null; i += 1)
        {
            Path direct = current.resolve(relativePath);

            if (Files.isRegularFile(direct))
            {
                return read(direct);
            }

            Path nested = current.resolve("new").resolve(relativePath);

            if (Files.isRegularFile(nested))
            {
                return read(nested);
            }

            current = current.getParent();
        }

        throw new AssertionError("could not locate " + relativePath);
    }

    private static String read(Path path)
    {
        try
        {
            return Files.readString(path)
                .replace("\r\n", "\n");
        }
        catch (IOException e)
        {
            throw new AssertionError("could not read " + path, e);
        }
    }

    private static void check(boolean condition, String message)
    {
        if (!condition)
        {
            throw new AssertionError(message);
        }
    }
}
