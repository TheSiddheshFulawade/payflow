package com.siddheshfulawade.payflow.system;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * The one compose stack of all system tests: started once per JVM (never two stacks, memory), removed with
 * {@code down -v} when the JVM ends. Plain `docker compose` through ProcessBuilder, no extra dependency.
 */
final class SystemStack {

    static final Path ROOT = repoRoot();
    // Invariant: own project name, so `down -v` here can never remove the developer's demo stack (project "payflow").
    static final String PROJECT = "payflow-systemtest";
    private static final String COMPOSE = ROOT.resolve("deploy/compose.yaml").toString();

    private static final String DEMO_PROJECT = "payflow";

    private static boolean started;
    private static Exception failure;

    private SystemStack() {}

    /**
     * Starts the stack on the first call (builds images, waits for healthy), does nothing afterwards.
     * Invariant: if the first start failed, every later call throws that same failure. The tests must never go on
     * and talk to whatever else answers on localhost (the developer's demo stack).
     */
    static synchronized void ensureUp() throws Exception {
        if (failure != null) {
            throw failure;
        }
        if (started) {
            return;
        }
        try {
            requireNoDemoStack(
                    capture(List.of("docker", "compose", "-p", DEMO_PROJECT, "-f", COMPOSE, "ps", "-q"), 60));
            // registered before `up`: a stack that half started is removed as well
            Runtime.getRuntime().addShutdownHook(new Thread(SystemStack::tearDown, "payflow-stack-down"));
            run(compose("up", "-d", "--build", "--wait"), 600);
            started = true;
        } catch (Exception | AssertionError e) {
            failure = e instanceof Exception ex ? ex : new IllegalStateException(e);
            throw failure;
        }
    }

    /** Fails if {@code docker compose -p payflow ps -q} printed any container id: the demo stack owns our ports. */
    static void requireNoDemoStack(String psQuietOutput) {
        if (!psQuietOutput.isBlank()) {
            throw new IllegalStateException("demo stack (project payflow) is running; stop it first: "
                    + "docker compose -f deploy/compose.yaml down");
        }
    }

    /** {@code docker compose <args>} against the test project, e.g. {@code compose("kill", "wallet-service")}. */
    static void composeRun(String... args) throws Exception {
        run(compose(args), 180);
    }

    /** Like {@link #composeRun} but a failure is only printed: used to put things back in a {@code finally}. */
    static void composeQuietly(String... args) {
        try {
            run(compose(args), 180);
        } catch (Throwable e) {
            System.err.println("ignored: docker compose " + String.join(" ", args) + " -> " + e.getMessage());
        }
    }

    /**
     * Runs one SELECT with psql inside the postgres container and returns its rows as comma separated fields.
     * No JDBC driver needed. Every selected value must be free of commas (ids, keys, states and numbers are).
     */
    static List<List<String>> query(String database, String sql) throws Exception {
        String out = capture(
                compose(
                        "exec",
                        "-T",
                        "postgres",
                        "psql",
                        "-U",
                        "postgres",
                        "-d",
                        database,
                        "-At",
                        "-F",
                        ",",
                        "-c",
                        sql),
                60);
        List<List<String>> rows = new ArrayList<>();
        for (String line : out.split("\\R")) {
            if (!line.isBlank()) {
                rows.add(Arrays.asList(line.split(",", -1)));
            }
        }
        return rows;
    }

    private static List<String> compose(String... args) {
        List<String> command = new ArrayList<>(List.of("docker", "compose", "-p", PROJECT, "-f", COMPOSE));
        command.addAll(Arrays.asList(args));
        return command;
    }

    private static void tearDown() {
        try {
            run(compose("down", "-v", "--remove-orphans"), 120);
        } catch (Throwable e) {
            System.err.println("could not remove project " + PROJECT + ": " + e.getMessage());
        }
    }

    private static void run(List<String> command, long timeoutSeconds) throws Exception {
        Process process = new ProcessBuilder(command)
                .directory(ROOT.toFile())
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.INHERIT)
                .start();
        finish(process, command, timeoutSeconds);
    }

    private static String capture(List<String> command, long timeoutSeconds) throws Exception {
        Path out = Files.createTempFile("payflow-psql", ".txt");
        try {
            Process process = new ProcessBuilder(command)
                    .directory(ROOT.toFile())
                    .redirectError(ProcessBuilder.Redirect.INHERIT)
                    .redirectOutput(out.toFile())
                    .start();
            finish(process, command, timeoutSeconds);
            return Files.readString(out);
        } finally {
            Files.deleteIfExists(out);
        }
    }

    private static void finish(Process process, List<String> command, long timeoutSeconds) throws Exception {
        boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly(); // a hung docker process must not outlive the test
        }
        assertThat(finished).as("%s finished", command).isTrue();
        assertThat(process.exitValue()).as("%s exit code", command).isZero();
    }

    private static Path repoRoot() {
        Path dir = Paths.get("").toAbsolutePath();
        while (dir != null && !Files.exists(dir.resolve("SPEC.md"))) {
            dir = dir.getParent();
        }
        if (dir == null) {
            throw new IllegalStateException("repository root (folder with SPEC.md) not found");
        }
        return dir;
    }
}
