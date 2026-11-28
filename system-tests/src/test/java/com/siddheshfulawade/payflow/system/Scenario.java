package com.siddheshfulawade.payflow.system;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.siddheshfulawade.payflow.system.invariants.HoldRow;
import com.siddheshfulawade.payflow.system.invariants.InvariantChecker;
import com.siddheshfulawade.payflow.system.invariants.LedgerRow;
import com.siddheshfulawade.payflow.system.invariants.PaymentRow;
import com.siddheshfulawade.payflow.system.invariants.Snapshot;
import com.siddheshfulawade.payflow.system.invariants.Violation;
import com.siddheshfulawade.payflow.system.invariants.WalletRow;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.awaitility.core.ConditionTimeoutException;

/**
 * One test's own wallets and payments on the shared stack. Every wallet id is unique to the run, so the database may
 * hold other rows (earlier runs, the demo) and the snapshot still covers exactly this test.
 *
 * <p>A client in this harness behaves like a real one: a request that fails (service down, timeout) is sent again
 * with the same Idempotency-Key until the stack answers.
 */
final class Scenario {

    /** The deadline of REQ-PF-050: every payment terminal within 3 minutes after the components are back. */
    static final Duration TERMINAL_WITHIN = Duration.ofMinutes(3);

    record Spec(String key, String payer, String payee, long amount) {}

    private final String name;
    private final String run = UUID.randomUUID().toString().substring(0, 8);
    private final String aliceSub;
    private final List<String> wallets = new ArrayList<>();
    private final AtomicLong seededTotal = new AtomicLong();
    private final AtomicInteger keyCounter = new AtomicInteger();
    private final AtomicInteger accepted = new AtomicInteger();
    private final Map<String, String> paymentIdByKey = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<String> problems = new ConcurrentLinkedQueue<>();
    private final long startNanos = System.nanoTime();

    /** Starts the shared stack if it is not up yet. */
    Scenario(String name) throws Exception {
        SystemStack.ensureUp();
        this.name = name;
        this.aliceSub = Api.subject(Api.token("alice"));
    }

    /** Seeds a wallet owned by alice (the payer's owner is the payment's creator, REQ-PF-003); returns its id. */
    synchronized String seed(String role, long balance) throws Exception {
        String id = SafeIds.require(name + "-" + run + "-" + role);
        Api.seedWallet(id, aliceSub, balance);
        wallets.add(id);
        seededTotal.addAndGet(balance);
        return id;
    }

    Spec spec(String payer, String payee, long amount) {
        return new Spec("sys-" + run + "-" + keyCounter.incrementAndGet(), payer, payee, amount);
    }

    /** Payments the stack has accepted so far (202 or 200), counting every copy: lets a test inject a fault mid-batch. */
    int accepted() {
        return accepted.get();
    }

    /**
     * Sends every spec {@code copies} times, all copies concurrently, from 8 client threads. With copies above 1 this
     * is the retrying client of C5: same key, same body, at the same time.
     */
    CompletableFuture<Void> sendAsync(List<Spec> specs, int copies) {
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<CompletableFuture<Void>> sends = new ArrayList<>();
        for (Spec spec : specs) {
            for (int copy = 0; copy < copies; copy++) {
                sends.add(CompletableFuture.runAsync(() -> sendUntilAccepted(spec), pool));
            }
        }
        return CompletableFuture.allOf(sends.toArray(CompletableFuture[]::new)).whenComplete((v, e) -> pool.shutdown());
    }

    /** Same-key requests must all answer with one payment id (REQ-PF-002). */
    private void sendUntilAccepted(Spec spec) {
        AtomicReference<String> id = new AtomicReference<>();
        AtomicReference<String> refusal = new AtomicReference<>();
        await().atMost(TERMINAL_WITHIN)
                .pollInterval(Duration.ofMillis(500))
                // connection refused, timeouts and a service still starting throw: the client tries again
                .ignoreExceptions()
                .until(() -> {
                    Api.Response response = Api.post(
                            Api.PAYMENTS,
                            Api.token("alice"),
                            spec.key(),
                            Api.paymentBody(spec.payer(), spec.payee(), spec.amount()));
                    if (response.status() == 202 || response.status() == 200) {
                        id.set(Api.JSON.readTree(response.body()).path("id").asString());
                        return true;
                    }
                    if (response.status() >= 500) {
                        return false; // the service is up but failing: also worth a retry
                    }
                    refusal.set(response.status() + " " + response.body());
                    return true; // a 4xx will not get better by sending it again
                });
        if (refusal.get() != null) {
            problems.add("request " + spec.key() + " refused: " + refusal.get());
            return;
        }
        accepted.incrementAndGet();
        String first = paymentIdByKey.putIfAbsent(spec.key(), id.get());
        if (first != null && !first.equals(id.get())) {
            problems.add("key " + spec.key() + " answered with two payments: " + first + " and " + id.get());
        }
    }

    /** The state a client sees for one payment. */
    String stateOf(String paymentId) throws Exception {
        return Api.get(Api.PAYMENTS + "/" + paymentId, Api.token("alice"))
                .path("state")
                .asString();
    }

    String paymentIdOf(Spec spec) {
        return paymentIdByKey.get(spec.key());
    }

    /** Waits until exactly {@code expected} payments of this run exist and every one is terminal. */
    void awaitAllTerminal(int expected) {
        AtomicReference<String> last = new AtomicReference<>("nothing read yet");
        try {
            await().atMost(TERMINAL_WITHIN)
                    .pollInterval(Duration.ofSeconds(2))
                    .ignoreExceptions()
                    .until(() -> {
                        List<List<String>> rows = SystemStack.query(
                                "payment",
                                "select state, count(*) from payment where payer in (" + walletList()
                                        + ") group by state");
                        last.set(rows.toString());
                        long total = rows.stream()
                                .mapToLong(r -> Long.parseLong(r.get(1)))
                                .sum();
                        long open = rows.stream()
                                .filter(r -> !List.of("COMPLETED", "REVERSED", "REJECTED")
                                        .contains(r.get(0)))
                                .mapToLong(r -> Long.parseLong(r.get(1)))
                                .sum();
                        return total == expected && open == 0;
                    });
        } catch (ConditionTimeoutException e) {
            throw new AssertionError(
                    "expected " + expected + " terminal payments within " + TERMINAL_WITHIN + ", last count by state "
                            + last.get(),
                    e);
        }
    }

    /** Reads this run's rows from the payment and wallet databases. */
    Snapshot snapshot() throws Exception {
        String wallet = walletList();
        List<PaymentRow> payments = SystemStack.query(
                        "payment",
                        "select id, client_id, idempotency_key, payer, payee, amount, state from payment where payer in ("
                                + wallet + ")")
                .stream()
                .map(r -> new PaymentRow(
                        UUID.fromString(r.get(0)),
                        r.get(1),
                        r.get(2),
                        r.get(3),
                        r.get(4),
                        Long.parseLong(r.get(5)),
                        r.get(6)))
                .toList();
        List<WalletRow> walletRows =
                SystemStack.query("wallet", "select id, available, held from wallet where id in (" + wallet + ")")
                        .stream()
                        .map(r -> new WalletRow(r.get(0), Long.parseLong(r.get(1)), Long.parseLong(r.get(2))))
                        .toList();
        List<HoldRow> holds =
                SystemStack.query(
                                "wallet", "select payment_id, state, amount from hold where payer in (" + wallet + ")")
                        .stream()
                        .map(r -> new HoldRow(UUID.fromString(r.get(0)), r.get(1), Long.parseLong(r.get(2))))
                        .toList();
        List<LedgerRow> ledger = SystemStack.query(
                        "wallet",
                        "select payment_id, wallet_id, kind, amount from ledger_entry where wallet_id in (" + wallet
                                + ")")
                .stream()
                .map(r -> new LedgerRow(
                        r.get(0).isEmpty() ? null : UUID.fromString(r.get(0)),
                        r.get(1),
                        r.get(2),
                        Long.parseLong(r.get(3))))
                .toList();
        return new Snapshot(walletRows, holds, ledger, payments, seededTotal.get());
    }

    /**
     * The end of every system and chaos test (SPEC section 7). Waits for {@code expectedPayments} terminal payments,
     * snapshots the databases and asserts the money invariants: sum(available + held) unchanged, sum(held) = 0, every
     * payment terminal, each COMPLETED captured once, REVERSED and REJECTED moved no money. On top: the client never
     * got two payment ids for one key, and payments created = distinct keys sent.
     *
     * <p>Not checked: "reconciliation of the run reports only MATCHED". The job takes business dates up to today
     * minus 2 only, so it cannot see payments made now.
     */
    Map<String, Long> assertInvariants(int expectedPayments) throws Exception {
        awaitAllTerminal(expectedPayments);
        Snapshot snapshot = snapshot();

        List<Violation> violations = new InvariantChecker().check(snapshot);
        assertThat(violations).as("invariant violations of %s", name).isEmpty();
        assertThat(problems).as("client-side problems of %s", name).isEmpty();
        assertThat(snapshot.payments())
                .as("payments created = distinct idempotency keys sent")
                .hasSize(expectedPayments)
                .hasSize(paymentIdByKey.size());
        assertThat(snapshot.payments().stream().map(PaymentRow::id).collect(Collectors.toSet()))
                .as("payment ids the clients were given")
                .isEqualTo(Map.copyOf(paymentIdByKey).values().stream()
                        .map(UUID::fromString)
                        .collect(Collectors.toSet()));

        Map<String, Long> byState = new TreeMap<>(
                snapshot.payments().stream().collect(Collectors.groupingBy(PaymentRow::state, Collectors.counting())));
        // one line per scenario: the README chaos table is built from these
        System.out.printf(
                "SCENARIO %s payments=%d COMPLETED=%d REVERSED=%d REJECTED=%d durationSeconds=%d%n",
                name,
                snapshot.payments().size(),
                byState.getOrDefault("COMPLETED", 0L),
                byState.getOrDefault("REVERSED", 0L),
                byState.getOrDefault("REJECTED", 0L),
                Duration.ofNanos(System.nanoTime() - startNanos).toSeconds());
        return byState;
    }

    // Invariant: SafeIds.sqlList validates every id as [a-z0-9-]+, so none can break out of the quotes
    private synchronized String walletList() {
        return SafeIds.sqlList(wallets);
    }
}
