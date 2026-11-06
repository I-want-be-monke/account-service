import java.io.BufferedWriter;
import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

/** Local E11 generator. It has no option for changing the destination URL. */
public class LoadHarness {
    private static final URI BASE = URI.create("https://localhost:18448");
    private static final String PROJECT = "account-service-e11";
    private static final String DATABASE = "account_service_e11";
    private static final String APP = PROJECT + "-app-1";
    private static final String DB = PROJECT + "-db-1";
    private static final String NETWORK = PROJECT + "_default";
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Europe/Moscow");
    private static final Pattern INPUT = Pattern.compile("<input\\b[^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern ATTRIBUTE = Pattern.compile("([\\w:-]+)\\s*=\\s*(\"[^\"]*\"|'[^']*')");
    private static final Pattern OPERATION = Pattern.compile("/operations/([0-9a-fA-F-]{36})");
    private static final Pattern CSV_ESCAPE = Pattern.compile("[,\"\\r\\n]");
    private static final Pattern REPORT_TOTAL = Pattern.compile("data-total-key=\"([A-Za-z][A-Za-z0-9]*)\"[^>]*>"
        + "\\s*<strong>(-?[0-9]+\\.[0-9]{2})</strong>");
    private static final Pattern REPORT_ROWS = Pattern.compile("<strong\\s+id=\"total-rows\"[^>]*>\\s*([0-9]+)\\s*</strong>");
    private static final String REPORT_LABEL = "GET /reports/result ";
    private static final List<String> SCREEN_ROUTES = List.of("GET /accounts/{id}", "GET /accounts/{id}/operations",
        "GET /my/accounts", "GET /clients?accountNumber", "GET /accounts?number", "GET /accounts/{id}/statement/result",
        "GET /accounts/{id}/operations/new", "GET /operations/{id}/confirm");

    enum Role {
        ADMIN(2), OPERATOR(60), ACCOUNTANT(40), MANAGER(18), CLIENT(180);
        final int target;
        Role(int target) { this.target = target; }
        boolean reports() { return this == ADMIN || this == ACCOUNTANT || this == MANAGER; }
    }
    enum ReportType { CHARGES, PAYMENTS, DEBT, MOVEMENT }
    enum Phase { RAMP, WARMUP, STEADY, REPORT_TAIL, COLD, TEARDOWN, TELEMETRY }
    record Account(UUID id, String number, LocalDate historyStart) {}
    record Actor(Role role, String login, Account account) {}
    record DbAccount(Account account, UUID client, String status) {}
    record DbUser(Role role, String client) {}
    record Fixture(List<Actor> actors, List<Account> readPool, long accounts, long posted, long monthPosted) {}
    record ReportTask(ReportType type, int ordinal) {}

    public static void main(String[] args) {
        try {
            if (Arrays.asList(args).contains("--help")) {
                System.out.println("scripts\\load-test.cmd [--check] [--users 300] [--runs 1] "
                    + "[--ramp 300] [--warmup 300] [--steady 1200] [--report-tail 0] "
                    + "[--report-month 2026-10] [--reports-per-type 30] [--seed 1101] "
                    + "[--timeout 60] [--telemetry 15] [--env .local/e11/stand.env] "
                    + "[--tls .local/e11/tls.p12] [--fixtures .local/e11-verification/fixtures.csv] "
                    + "[--output .local/e11-verification/load-unique]");
                System.out.println("300-user workloads require Docker with at least 4 vCPU and 8000000000 bytes of memory,"
                    + " exactly 200000 accounts and at least 1000000 POSTED operations.");
                System.out.println("Start each pilot invocation from the same baseline. Reduced runs default to --runs 3 and --report-tail 180.");
                System.out.println("Use --cold-report CHARGES|PAYMENTS|DEBT|MOVEMENT with --report-month, --fixtures and --output"
                    + " to run one report request after restarting the isolated services.");
                return;
            }
            Options options = Options.parse(args);
            if (options.pilot()) {
                require(options.runs() == 1 && options.ramp() == 300 && options.warmup() == 300
                    && options.steady() == 1200 && options.tail() == 0,
                    "A 300-user pilot invocation requires --runs 1 --ramp 300 --warmup 300 --steady 1200 --report-tail 0.");
                require(options.telemetry() > 0, "A 300-user pilot invocation requires telemetry.");
            }
            if (options.pilot() || options.coldReport() != null) assertPilotResources();
            assertIsolated();
            Properties settings = new Properties();
            try (var reader = Files.newBufferedReader(options.env())) { settings.load(reader); }
            require(DATABASE.equals(settings.getProperty("DB_NAME")), "Environment must name " + DATABASE);
            Fixture fixture = options.coldReport() == null ? fixtures(options.fixtures(), options.month()) : coldFixture(options);
            if (options.pilot()) {
                require(fixture.accounts() == 200_000 && fixture.posted() >= 1_000_000,
                    "Pilot measurements require 200000 accounts and at least 1000000 POSTED operations. Actual: "
                        + fixture.accounts() + " accounts, " + fixture.posted() + " POSTED.");
            }
            SSLContext tls = trust(options.tls(), property(settings, "TLS_PASSWORD"));
            List<Actor> actors = options.coldReport() == null ? chooseActors(fixture.actors(), options.users(), options.seed())
                : fixture.actors();
            for (Actor actor : actors) property(settings, "DEMO_" + actor.role() + "_PASSWORD");
            try (HttpClient client = http(tls)) {
                var ready = client.send(HttpRequest.newBuilder(BASE.resolve("/actuator/health/readiness"))
                    .timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofString());
                require(ready.statusCode() == 200 && ready.body().contains("\"UP\""), "E11 HTTPS readiness is not UP");
            }
            String monthEvidence = options.coldReport() == null ? fixture.monthPosted() + " POSTED"
                : "counts from seed marker, no live ledger scan";
            System.out.printf("Guard OK. %s / %s / %s, %d accounts, %d POSTED, report month %s (%s).%n",
                PROJECT, DATABASE, BASE, fixture.accounts(), fixture.posted(), options.month(), monthEvidence);
            if (options.check()) return;
            Path output = localOutput(options.output());
            writeProfile(output, options, fixture, actors);
            if (options.coldReport() != null) {
                boolean successful = executeColdReport(options, settings, tls, actors.getFirst(),
                    Files.createDirectory(output.resolve("run-01")));
                System.out.println("Evidence: " + output);
                if (!successful) System.exit(2);
                return;
            }
            boolean successful = true;
            for (int i = 1; i <= options.runs(); i++) {
                assertIsolated();
                Path run = Files.createDirectory(output.resolve("run-%02d".formatted(i)));
                System.out.printf("Run %d/%d: %d accounts with independent logins, ramp/warm-up/steady %d/%d/%d s.%n",
                    i, options.runs(), actors.size(), options.ramp(), options.warmup(), options.steady());
                successful &= execute(options, settings, tls, fixture, actors, run, i);
            }
            System.out.println("Evidence: " + output);
            if (!successful) System.exit(2);
        } catch (Exception failure) {
            System.err.println("Load harness refused or stopped: " + failure.getMessage());
            System.exit(1);
        }
    }

    private static boolean execute(Options options, Properties settings, SSLContext tls, Fixture fixture,
                                   List<Actor> actors, Path runPath, int run) throws Exception {
        try (HttpClient client = http(tls); Evidence evidence = new Evidence(runPath);
             var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var state = new RunState(options, actors);
            var futures = new ArrayList<Future<?>>();
            for (int index = 0; index < actors.size(); index++) {
                Actor actor = actors.get(index);
                long delay = TimeUnit.SECONDS.toNanos(options.ramp()) * index / actors.size();
                var session = new Session(client, actor, state, evidence);
                String password = property(settings, "DEMO_" + actor.role() + "_PASSWORD");
                int ordinal = index;
                futures.add(workers.submit(() -> runUser(session, password, state, fixture.readPool(),
                    delay, options.seed() + 1000003L * run + ordinal, evidence)));
            }
            if (options.telemetry() > 0) {
                futures.add(workers.submit(() -> sampleTelemetry(state, runPath, client)));
            }
            for (var future : futures) future.get();
            evidence.writeSummary(runPath, options.steady());
            long screenP95 = evidence.p95("STEADY:screen");
            long slowScreenRoutes = evidence.stats.entrySet().stream().filter(e -> e.getKey().startsWith("STEADY:screen-route:"))
                .filter(e -> e.getValue().percentile(95) > 2000).count();
            long missingScreenRoutes = SCREEN_ROUTES.stream().filter(route -> evidence.p95("STEADY:screen-route:" + route) < 0).count();
            long slowReportTypes = evidence.stats.entrySet().stream().filter(e -> e.getKey().startsWith("REPORT:route:"))
                .filter(e -> e.getValue().buckets.lastKey() > 10000).count();
            var steadyReportCounts = new EnumMap<ReportType, Long>(ReportType.class);
            for (ReportType type : ReportType.values()) {
                var samples = evidence.stats.get("STEADY:completed-report-route:" + REPORT_LABEL + type);
                steadyReportCounts.put(type, samples == null ? 0L : samples.count - samples.errors);
            }
            String facts = "runId=" + state.unique + "\nrequestedUsers=" + actors.size()
                + "\nuniqueAuthenticatedSessions=" + state.cookies.size()
                + "\nreadyBySteady=" + state.readyBySteady.get() + "\npeakActiveUsers=" + state.peak.get()
                + "\nsteadyStartEpochMs=" + state.timestampMillis(state.steadyStart)
                + "\nsteadyEndEpochMs=" + state.timestampMillis(state.steadyEnd)
                + "\nsteadyUsersWithSuccessfulRequests=" + evidence.steadyUsers.size()
                + "\nleftBeforeSteadyEnd=" + state.leftBeforeSteadyEnd.get()
                + "\nmaxParallelReports=" + state.reportPeak.get() + "\nplannedReportSamples="
                + 4 * options.reportsPerType() + "\nremainingReportSamples=" + state.remaining.get()
                + "\nhttpUnexpectedErrors=" + evidence.errors + "\nhttp5xx=" + evidence.serverErrors
                + "\ntelemetryErrors=" + evidence.telemetryErrors + "\nsteadyScreenP95Ms=" + screenP95
                + "\nsteadyScreenRoutesOver2Seconds=" + slowScreenRoutes + "\nsteadyScreenRoutesMissing=" + missingScreenRoutes
                + "\nreportTypesOver10Seconds=" + slowReportTypes
                + "\nsuccessfulFinancialActions=" + evidence.successfulFinancialActions
                + "\nscenarioErrors=" + evidence.scenarioErrors + "\n";
            for (var entry : steadyReportCounts.entrySet()) {
                facts += "steadyCompletedReports." + entry.getKey() + "=" + entry.getValue() + "\n";
            }
            Files.writeString(runPath.resolve("run.properties"), facts);
            System.out.printf("Authenticated %d/%d, ready by steady %d, report samples %d/%d, HTTP 5xx %d, unexpected HTTP errors %d.%n",
                state.cookies.size(), actors.size(), state.readyBySteady.get(),
                4 * options.reportsPerType() - state.remaining.get(), 4 * options.reportsPerType(),
                evidence.serverErrors, evidence.errors);
            boolean steadyPilotComplete = !options.pilot() || (state.leftBeforeSteadyEnd.get() == 0
                && evidence.steadyUsers.size() == actors.size() && missingScreenRoutes == 0
                && steadyReportCounts.values().stream().allMatch(count -> count >= options.reportsPerType()));
            return steadyPilotComplete && state.cookies.size() == actors.size() && state.readyBySteady.get() == actors.size()
                && state.remaining.get() == 0 && evidence.errors == 0 && evidence.scenarioErrors == 0
                && screenP95 >= 0 && screenP95 <= 2000 && slowScreenRoutes == 0 && slowReportTypes == 0;
        }
    }

    private static boolean executeColdReport(Options options, Properties settings, SSLContext tls, Actor actor, Path runPath)
        throws IOException {
        try (HttpClient client = http(tls); Evidence evidence = new Evidence(runPath)) {
            var state = new RunState(options, List.of(actor));
            var session = new Session(client, actor, state, evidence);
            ReportType type = options.coldReport();
            String action = "report_" + type;
            try {
                session.login(property(settings, "DEMO_" + actor.role() + "_PASSWORD"), Phase.COLD);
                state.active.set(1);
                session.report(new ReportTask(type, 1), Phase.COLD);
                evidence.scenario(actor, Phase.COLD, action, true);
            } catch (StepFailure failed) {
                evidence.scenario(actor, Phase.COLD, action, false);
            } finally {
                if (state.active.getAndSet(0) == 1) {
                    try { session.logout(); }
                    catch (StepFailure failed) { evidence.scenario(actor, Phase.TEARDOWN, "logout", false); }
                }
            }
            evidence.writeSummary(runPath, options.steady());
            var samples = evidence.stats.get("REPORT:route:" + REPORT_LABEL + type);
            long maxMs = samples == null ? -1 : samples.buckets.lastKey();
            Files.writeString(runPath.resolve("run.properties"), "runId=" + state.unique + "\nrequestedUsers=1"
                + "\nuniqueAuthenticatedSessions=" + state.cookies.size() + "\ncoldReport=" + type + "\nmaxReportMs=" + maxMs
                + "\nhttpUnexpectedErrors=" + evidence.errors + "\nhttp5xx=" + evidence.serverErrors
                + "\nscenarioErrors=" + evidence.scenarioErrors + "\n");
            return state.cookies.size() == 1 && samples != null && samples.count == 1 && maxMs <= 10_000
                && evidence.errors == 0 && evidence.scenarioErrors == 0;
        }
    }

    private static void runUser(Session session, String password, RunState run, List<Account> readPool,
                                long delay, long seed, Evidence evidence) {
        var random = new SplittableRandom(seed);
        boolean authenticated = false;
        long serial = 0;
        try {
            sleepUntil(run.start + delay);
            Phase loginPhase = run.phase();
            session.login(password, loginPhase);
            authenticated = true;
            int active = run.active.incrementAndGet();
            run.peak.accumulateAndGet(active, Math::max);
            if (System.nanoTime() <= run.steadyStart) run.readyBySteady.incrementAndGet();
            if (session.actor.role() == Role.ADMIN) run.observer.compareAndSet(null, session);
            Phase phase;
            while ((phase = run.workloadPhase()) != null) {
                ReportTask report = run.reportUsers.contains(session.actor.login()) && run.isMeasurement(phase)
                    ? run.reports.poll() : null;
                if (report != null) {
                    int concurrent = run.reportsInFlight.incrementAndGet();
                    run.reportPeak.accumulateAndGet(concurrent, Math::max);
                    try {
                        session.report(report, phase);
                        evidence.scenario(session.actor, phase, "report_" + report.type(), true);
                    } catch (StepFailure failed) {
                        evidence.scenario(session.actor, phase, "report_" + report.type(), false);
                    } finally {
                        run.reportsInFlight.decrementAndGet();
                        run.remaining.decrementAndGet();
                    }
                    continue;
                }
                double choice = random.nextDouble();
                double financial = session.actor.role() == Role.ACCOUNTANT ? run.financeProbability : 0;
                double search = session.actor.role() != Role.CLIENT ? run.searchProbability : 0;
                String action = choice < financial ? "finance" : choice < financial + search ? "search"
                    : choice < financial + search + 4.0 / 37.0 ? "statement" : "view";
                Account account = session.actor.role() == Role.CLIENT || random.nextInt(5) == 0
                    ? session.actor.account() : readPool.get(random.nextInt(readPool.size()));
                try {
                    switch (action) {
                        case "finance" -> session.financial(phase, random.nextBoolean(), ++serial);
                        case "search" -> session.search(phase, account, random.nextBoolean());
                        case "statement" -> session.statement(phase, account);
                        default -> session.view(phase, account, random.nextBoolean());
                    }
                    evidence.scenario(session.actor, phase, action, true);
                } catch (StepFailure failed) {
                    evidence.scenario(session.actor, phase, action, false);
                }
                Thread.sleep(random.nextInt(1000, 3001));
            }
        } catch (StepFailure failed) {
            evidence.scenario(session.actor, run.phase(), "login", false);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } finally {
            if (authenticated) {
                if (System.nanoTime() < run.steadyEnd) run.leftBeforeSteadyEnd.incrementAndGet();
                run.observer.compareAndSet(session, null);
                run.active.decrementAndGet();
                try { session.logout(); }
                catch (StepFailure failed) { evidence.scenario(session.actor, Phase.TEARDOWN, "logout", false); }
            }
        }
    }

    static final class RunState {
        final Options options;
        final long clockStart = System.nanoTime();
        final long epochStartMillis = System.currentTimeMillis();
        final long start = clockStart + TimeUnit.SECONDS.toNanos(1);
        final long rampEnd, steadyStart, steadyEnd, tailEnd;
        final double financeProbability, searchProbability;
        final Set<String> reportUsers;
        final ConcurrentLinkedQueue<ReportTask> reports = new ConcurrentLinkedQueue<>();
        final Set<String> cookies = ConcurrentHashMap.newKeySet();
        final AtomicInteger active = new AtomicInteger(), peak = new AtomicInteger(), readyBySteady = new AtomicInteger();
        final AtomicInteger leftBeforeSteadyEnd = new AtomicInteger();
        final AtomicInteger remaining, reportsInFlight = new AtomicInteger(), reportPeak = new AtomicInteger();
        final AtomicReference<Session> observer = new AtomicReference<>();
        final String unique = UUID.randomUUID().toString().substring(0, 12);

        RunState(Options options, List<Actor> actors) {
            this.options = options;
            rampEnd = start + TimeUnit.SECONDS.toNanos(options.ramp());
            steadyStart = rampEnd + TimeUnit.SECONDS.toNanos(options.warmup());
            steadyEnd = steadyStart + TimeUnit.SECONDS.toNanos(options.steady());
            tailEnd = steadyEnd + TimeUnit.SECONDS.toNanos(options.tail());
            long accountants = actors.stream().filter(actor -> actor.role() == Role.ACCOUNTANT).count();
            long staff = actors.stream().filter(actor -> actor.role() != Role.CLIENT).count();
            // Four HTTP samples per financial action. These weights target HTTP, not action, proportions.
            financeProbability = actors.size() / (37.0 * accountants);
            searchProbability = 8.0 * actors.size() / (37.0 * staff);
            var selectedReporters = new java.util.LinkedHashSet<String>();
            for (Role role : List.of(Role.ADMIN, Role.ACCOUNTANT, Role.MANAGER)) {
                actors.stream().filter(actor -> actor.role() == role).findFirst()
                    .ifPresent(actor -> selectedReporters.add(actor.login()));
            }
            actors.stream().filter(actor -> actor.role().reports() && !selectedReporters.contains(actor.login()))
                .sorted(Comparator.comparingInt(actor -> actor.role() == Role.MANAGER ? 0 : 1))
                .limit(5 - selectedReporters.size()).map(Actor::login).toList().forEach(selectedReporters::add);
            reportUsers = Set.copyOf(selectedReporters);
            for (int ordinal = 1; ordinal <= options.reportsPerType(); ordinal++) {
                for (ReportType type : ReportType.values()) reports.add(new ReportTask(type, ordinal));
            }
            remaining = new AtomicInteger(reports.size());
        }

        Phase phase() { return phase(System.nanoTime()); }

        Phase phase(long now) {
            if (now < rampEnd) return Phase.RAMP;
            if (now < steadyStart) return Phase.WARMUP;
            if (now < steadyEnd) return Phase.STEADY;
            return Phase.REPORT_TAIL;
        }

        Phase workloadPhase() {
            long now = System.nanoTime();
            if (now >= steadyEnd && (now >= tailEnd || remaining.get() == 0)) return null;
            return phase(now);
        }

        boolean inWorkload() { return workloadPhase() != null; }

        long timestampMillis(long nanoTime) { return epochStartMillis + Math.floorDiv(nanoTime - clockStart, 1_000_000); }

        boolean isMeasurement(Phase phase) { return phase == Phase.STEADY || phase == Phase.REPORT_TAIL; }
    }

    static final class Session {
        private final HttpClient client;
        private final Actor actor;
        private final RunState run;
        private final Evidence evidence;
        private final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER);

        Session(HttpClient client, Actor actor, RunState run, Evidence evidence) {
            this.client = client;
            this.actor = actor;
            this.run = run;
            this.evidence = evidence;
        }

        void login(String password, Phase phase) throws StepFailure {
            var page = request(phase, "login", "GET /login", "/login", null, 200, hasField("_csrf"), "");
            request(phase, "login", "POST /login", "/login", form("username", actor.login(),
                "password", password, "_csrf", field(page, "_csrf")), 302,
                response -> "/".equals(location(response)), "");
            var sessionCookie = cookies.getCookieStore().getCookies().stream()
                .filter(cookie -> cookie.getName().equals("ACCOUNT_SESSION") && cookie.getSecure())
                .findFirst().orElseThrow(StepFailure::new);
            if (!run.cookies.add(sessionCookie.getValue())) throw new StepFailure();
        }

        void logout() throws StepFailure {
            var page = request(Phase.TEARDOWN, "logout", "GET /", "/", null, 200, hasField("_csrf"), "");
            request(Phase.TEARDOWN, "logout", "POST /logout", "/logout", form("_csrf", field(page, "_csrf")),
                302, response -> "/login?logout".equals(location(response)), "");
        }

        void view(Phase phase, Account account, boolean variant) throws StepFailure {
            String path = "/accounts/" + account.id();
            if (actor.role() == Role.CLIENT && variant) {
                get(phase, "view", "GET /my/accounts", "/my/accounts");
            } else if (actor.role() != Role.ADMIN && variant) {
                get(phase, "view", "GET /accounts/{id}/operations", path + "/operations");
            } else {
                get(phase, "view", "GET /accounts/{id}", path);
            }
        }

        void search(Phase phase, Account account, boolean clients) throws StepFailure {
            if (clients) get(phase, "search", "GET /clients?accountNumber", "/clients?accountNumber=" + account.number());
            else get(phase, "search", "GET /accounts?number", "/accounts?number=" + account.number());
        }

        void statement(Phase phase, Account account) throws StepFailure {
            LocalDate from = run.options.month().atDay(1);
            LocalDate to = run.options.month().atEndOfMonth();
            if (from.isBefore(account.historyStart())) from = account.historyStart();
            if (to.isBefore(from)) to = from;
            get(phase, "statement", "GET /accounts/{id}/statement/result", "/accounts/" + account.id()
                + "/statement/result?from=" + from + "&to=" + to);
        }

        void financial(Phase phase, boolean charge, long serial) throws StepFailure {
            String kind = charge ? "CHARGE" : "PAYMENT";
            String newPath = "/accounts/" + actor.account().id() + "/operations/new";
            var page = request(phase, "finance", "GET /accounts/{id}/operations/new", newPath + "?kind=" + kind,
                null, 200, hasField("_csrf"), "");
            LocalDate today = LocalDate.now(BUSINESS_ZONE);
            String body = form("_csrf", field(page, "_csrf"), "kind", kind, "operationDate", today.toString(),
                "periodStart", today.withDayOfMonth(1).toString(), "amount", "0.01", "basis", "E11 controlled workload",
                "externalNumber", "E11-LOAD-" + run.unique + "-" + actor.login() + "-" + serial,
                "paymentMethod", charge ? "" : "MANUAL_TRANSFER");
            var created = request(phase, "finance", "POST /accounts/{id}/operations/new", newPath, body, 302,
                response -> operationPath(response) != null, "");
            String operation = operationPath(created);
            String confirm = operation + "/confirm";
            var confirmation = request(phase, "finance", "GET /operations/{id}/confirm", confirm, null, 200,
                response -> hasField("_csrf").test(response) && hasField("token").test(response), "");
            request(phase, "finance", "POST /operations/{id}/confirm", confirm,
                form("_csrf", field(confirmation, "_csrf"), "token", field(confirmation, "token")), 302,
                response -> (operation + "?posted").equals(location(response)), "");
        }

        void report(ReportTask report, Phase phase) throws StepFailure {
            YearMonth month = run.options.month();
            String dates = switch (report.type()) {
                case CHARGES, PAYMENTS -> "month=" + month;
                case DEBT -> "asOf=" + month.atEndOfMonth();
                case MOVEMENT -> "from=" + month.atDay(1) + "&to=" + month.atEndOfMonth();
            };
            String series = phase == Phase.COLD ? "cold-first" : (report.ordinal() == 1 ? "first-of-run" : "repeat");
            request(phase, "report", REPORT_LABEL + report.type(),
                "/reports/result?type=" + report.type() + "&" + dates + "&size=20", null, 200, item -> true, series, report);
        }

        private void get(Phase phase, String category, String label, String path) throws StepFailure {
            request(phase, category, label, path, null, 200, response -> true, "");
        }

        HttpResponse<byte[]> request(Phase phase, String category, String label, String path, String form,
                                     int expected, Predicate<HttpResponse<byte[]>> validation, String series)
            throws StepFailure {
            return request(phase, category, label, path, form, expected, validation, series, null);
        }

        private HttpResponse<byte[]> request(Phase phase, String category, String label, String path, String form,
                                            int expected, Predicate<HttpResponse<byte[]>> validation, String series,
                                            ReportTask report) throws StepFailure {
            URI uri = BASE.resolve(path);
            if (!sameOrigin(uri)) throw new IllegalArgumentException("Request destination was not E11 localhost");
            long start = System.nanoTime();
            long stamp = run.timestampMillis(start);
            Phase measuredPhase = switch (phase) {
                case COLD, TEARDOWN, TELEMETRY -> phase;
                default -> run.phase(start);
            };
            int active = run.active.get();
            int sent = form == null ? 0 : form.getBytes(StandardCharsets.UTF_8).length;
            HttpResponse<byte[]> response;
            try {
                var request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(run.options.timeout()))
                    .header("User-Agent", "Account-Service-E11/1.0");
                for (var header : cookies.get(uri, Map.of()).entrySet()) {
                    for (String value : header.getValue()) request.header(header.getKey(), value);
                }
                if (form == null) request.GET();
                else request.header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                    .POST(HttpRequest.BodyPublishers.ofString(form));
                response = client.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
                cookies.put(uri, response.headers().map());
            } catch (IOException | InterruptedException failure) {
                evidence.http(stamp, elapsed(start, System.nanoTime()), measuredPhase, category, label, actor, 0, false,
                    failure.getClass().getSimpleName(), 0, sent, active, uri, series, "", false);
                if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
                throw new StepFailure();
            }
            long finish = System.nanoTime();
            long duration = elapsed(start, finish);
            var totals = report == null ? null : reportTotals(response);
            boolean validReport = report == null
                || (totals.containsKey("totalRows") && totals.size() == (report.type() == ReportType.MOVEMENT ? 9 : 4));
            boolean optionalGcSample = measuredPhase == Phase.TELEMETRY && response.statusCode() == 404
                && uri.getPath().equals("/actuator/metrics/jvm.gc.pause");
            boolean success = optionalGcSample || (response.statusCode() == expected && validation.test(response) && validReport);
            evidence.http(stamp, duration, measuredPhase, category, label, actor, response.statusCode(), success,
                success ? "" : "Expected HTTP " + expected + " and matching response", response.body().length,
                sent, active, uri, series, response.headers().firstValue("X-Request-ID")
                    .filter(value -> value.matches("[0-9a-fA-F-]{36}")).orElse(""), finish <= run.steadyEnd);
            if (!success) throw new StepFailure();
            if (report != null) evidence.report(actor, measuredPhase, report, totals, stamp);
            return response;
        }
    }

    private static final class StepFailure extends Exception {}

    static final class Evidence implements AutoCloseable {
        private final BufferedWriter jtl, scenarios, reportTotals;
        private final Map<String, Stats> stats = new TreeMap<>();
        private final Set<String> steadyUsers = new HashSet<>();
        long errors, serverErrors, scenarioErrors, telemetryErrors, successfulFinancialActions;

        Evidence(Path path) throws IOException {
            jtl = Files.newBufferedWriter(path.resolve("raw-results.jtl"), StandardOpenOption.CREATE_NEW);
            scenarios = Files.newBufferedWriter(path.resolve("scenario-results.csv"), StandardOpenOption.CREATE_NEW);
            reportTotals = Files.newBufferedWriter(path.resolve("report-totals.csv"), StandardOpenOption.CREATE_NEW);
            jtl.write("timeStamp,elapsed,label,responseCode,responseMessage,threadName,dataType,success,failureMessage,"
                + "bytes,sentBytes,grpThreads,allThreads,URL,phase,category,series,requestId\n");
            scenarios.write("timeStamp,login,role,phase,action,success\n");
            reportTotals.write("timeStamp,login,phase,type,ordinal,column,amount\n");
        }

        synchronized void http(long stamp, long millis, Phase phase, String category, String label, Actor actor,
                               int status, boolean ok, String failure, int bytes, int sent, int active, URI uri, String series,
                               String requestId, boolean finishedBySteadyEnd) {
            try {
                jtl.write(csv(stamp, millis, label, status == 0 ? "NON_HTTP" : status, ok ? "OK" : "Unexpected",
                    actor.login(), "text", ok, failure, bytes, sent, active, active, uri, phase, category, series, requestId));
            } catch (IOException ex) { throw new IllegalStateException("Unable to write JTL", ex); }
            if (phase == Phase.TELEMETRY) {
                if (!ok) telemetryErrors++;
            } else {
                if (!ok) errors++;
                if (status >= 500 && status < 600) serverErrors++;
            }
            String byPhase = phase + ":";
            add(byPhase + "all", stamp, millis, status, ok);
            add(byPhase + "category:" + category, stamp, millis, status, ok);
            add(byPhase + "route:" + label, stamp, millis, status, ok);
            if (phase == Phase.STEADY && Set.of("view", "search", "statement", "finance").contains(category)) {
                if (ok && finishedBySteadyEnd) steadyUsers.add(actor.login());
                add("STEADY:profile", stamp, millis, status, ok);
                if (label.startsWith("GET ")) {
                    add("STEADY:screen", stamp, millis, status, ok);
                    add("STEADY:screen-route:" + label, stamp, millis, status, ok);
                }
            }
            if (category.equals("report")) {
                add("REPORT:route:" + label, stamp, millis, status, ok);
                add("REPORT:series:" + series + ":" + label, stamp, millis, status, ok);
                if (phase == Phase.STEADY && finishedBySteadyEnd) {
                    add("STEADY:completed-report-route:" + label, stamp, millis, status, ok);
                    if (ok) steadyUsers.add(actor.login());
                }
            }
        }

        synchronized void scenario(Actor actor, Phase phase, String action, boolean ok) {
            try { scenarios.write(csv(System.currentTimeMillis(), actor.login(), actor.role(), phase, action, ok)); }
            catch (IOException ex) { throw new IllegalStateException("Unable to write scenario evidence", ex); }
            if (!ok) scenarioErrors++;
            if (ok && action.equals("finance")) successfulFinancialActions++;
        }

        synchronized void report(Actor actor, Phase phase, ReportTask task, Map<String, String> totals, long stamp) {
            try {
                for (var entry : totals.entrySet()) reportTotals.write(csv(stamp, actor.login(), phase, task.type(),
                    task.ordinal(), entry.getKey(), entry.getValue()));
            } catch (IOException ex) { throw new IllegalStateException("Unable to write report totals", ex); }
        }

        private void add(String key, long stamp, long millis, int code, boolean ok) {
            stats.computeIfAbsent(key, ignored -> new Stats()).add(stamp, millis, code, ok);
        }

        long p95(String scope) { return stats.containsKey(scope) ? stats.get(scope).percentile(95) : -1; }

        void writeSummary(Path path, int steadySeconds) throws IOException {
            try (var writer = Files.newBufferedWriter(path.resolve("summary.csv"), StandardOpenOption.CREATE_NEW)) {
                writer.write("scope,samples,errors,http5xx,errorRate,throughputPerSecond,p50Ms,p95Ms,p99Ms,maxMs\n");
                for (var entry : stats.entrySet()) {
                    var s = entry.getValue();
                    writer.write(csv(entry.getKey(), s.count, s.errors, s.http5xx, decimal((double) s.errors / s.count),
                        decimal(entry.getKey().startsWith("STEADY:") ? s.count / (double) steadySeconds
                            : s.count * 1000.0 / Math.max(1, s.last - s.first)),
                        s.percentile(50), s.percentile(95), s.percentile(99), s.buckets.lastKey()));
                }
            }
        }

        @Override public void close() throws IOException {
            try { jtl.close(); }
            finally { try { scenarios.close(); } finally { reportTotals.close(); } }
        }
    }

    static final class Stats {
        final TreeMap<Long, Long> buckets = new TreeMap<>();
        long count, errors, http5xx, first = Long.MAX_VALUE, last;

        void add(long start, long millis, int code, boolean ok) {
            buckets.merge(millis, 1L, Long::sum);
            count++;
            if (!ok) errors++;
            if (code >= 500 && code < 600) http5xx++;
            first = Math.min(first, start);
            last = Math.max(last, start + millis);
        }

        long percentile(int percentile) {
            long rank = (count * percentile + 99) / 100;
            long seen = 0;
            for (var entry : buckets.entrySet()) {
                seen += entry.getValue();
                if (seen >= rank) return entry.getKey();
            }
            throw new IllegalStateException("No samples");
        }
    }

    private static void sampleTelemetry(RunState run, Path path, HttpClient client) {
        try (var resources = Files.newBufferedWriter(path.resolve("telemetry.csv"), StandardOpenOption.CREATE_NEW);
             var metrics = Files.newBufferedWriter(path.resolve("actuator-metrics.jsonl"), StandardOpenOption.CREATE_NEW)) {
            resources.write("time,phase,activeUsers,hostCpuPercent,generatorCpuPercent,hostFreeRamBytes,"
                + "generatorHeapBytes,freeDiskBytes,pgActivity,containerStats,readiness\n");
            var os = (com.sun.management.OperatingSystemMXBean) java.lang.management.ManagementFactory.getOperatingSystemMXBean();
            while (run.inWorkload()) {
                int activeUsers = run.active.get();
                long sampleStart = System.nanoTime();
                Instant time = Instant.ofEpochMilli(run.timestampMillis(sampleStart));
                Phase phase = run.phase(sampleStart);
                String activity = safeProbe(() -> sql("""
                    select json_build_object('active',count(*) filter(where state='active'),
                        'lockWaiters',count(*) filter(where wait_event_type='Lock'),
                        'maxQueryAgeMs',coalesce(max(extract(epoch from clock_timestamp()-query_start)*1000)
                          filter(where state='active'),0),
                        'maxLockAgeMs',coalesce(max(extract(epoch from clock_timestamp()-query_start)*1000)
                          filter(where wait_event_type='Lock'),0),
                        'slowQueries',coalesce(json_agg(left(query,300)) filter(where state='active'
                          and clock_timestamp()-query_start > interval '1 second'),'[]'::json))
                    from pg_stat_activity where datname=current_database() and pid<>pg_backend_pid()
                    """));
                String containers = safeProbe(() -> docker("stats", "--no-stream", "--format",
                    "{{.Name}} {{.CPUPerc}} {{.MemUsage}} {{.BlockIO}}", APP, DB));
                String readiness = safeProbe(() -> {
                    var response = client.send(HttpRequest.newBuilder(BASE.resolve("/actuator/health/readiness"))
                        .timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
                    return response.statusCode() + "|" + text(response).strip();
                });
                long heap = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
                resources.write(csv(time, phase, activeUsers, cpu(os.getCpuLoad()), cpu(os.getProcessCpuLoad()),
                    os.getFreeMemorySize(), heap, Files.getFileStore(path).getUsableSpace(), activity, containers, readiness));
                resources.flush();
                var observer = run.observer.get();
                if (observer != null) {
                    for (String metric : List.of("hikaricp.connections.active", "hikaricp.connections.pending",
                        "hikaricp.connections.acquire", "jvm.memory.used?tag=area:heap", "jvm.memory.max?tag=area:heap",
                        "jvm.gc.pause")) {
                        if (!run.inWorkload()) break;
                        String payload = "null";
                        try {
                            var response = observer.request(Phase.TELEMETRY, "telemetry", "GET /actuator/metrics",
                                "/actuator/metrics/" + metric, null, 200,
                                item -> text(item).strip().startsWith("{"), "");
                            if (response.statusCode() == 200) payload = text(response).strip();
                        } catch (StepFailure ignored) { }
                        metrics.write("{\"time\":\"" + Instant.now() + "\",\"metric\":\"" + metric
                            + "\",\"payload\":" + payload + "}\n");
                    }
                    metrics.flush();
                }
                long next = Math.min(run.tailEnd, System.nanoTime() + TimeUnit.SECONDS.toNanos(run.options.telemetry()));
                while (run.inWorkload() && System.nanoTime() < next) Thread.sleep(250);
            }
        } catch (IOException failure) { throw new IllegalStateException("Unable to write telemetry", failure); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }

    @FunctionalInterface interface Probe { String read() throws Exception; }

    private static String safeProbe(Probe probe) {
        try { return probe.read(); }
        catch (Exception unavailable) { return "UNAVAILABLE"; }
    }

    private static String cpu(double value) { return value < 0 ? "UNAVAILABLE" : decimal(100 * value); }

    private static void assertPilotResources() throws Exception {
        String reported = docker("info", "--format", "{{.NCPU}}|{{.MemTotal}}");
        require(reported.matches("[0-9]+\\|[0-9]+"), "Cannot determine effective Docker CPU and memory for the pilot.");
        String[] resources = reported.split("\\|");
        long vcpu = Long.parseLong(resources[0]);
        long memory = Long.parseLong(resources[1]);
        require(vcpu >= 4 && memory >= 8_000_000_000L,
            "Pilot requires Docker with at least 4 vCPU and 8000000000 bytes of memory. Actual: "
                + vcpu + " vCPU, " + memory + " bytes.");
    }

    private static void assertIsolated() throws Exception {
        for (String service : List.of("app", "db")) {
            String container = service.equals("app") ? APP : DB;
            String actual = docker("inspect", "--format", "{{index .Config.Labels `com.docker.compose.project`}}"
                + "|{{index .Config.Labels `com.docker.compose.service`}}|{{.State.Running}}", container);
            require(actual.equals(PROJECT + "|" + service + "|true"), "Unexpected Compose container " + container);
            String network = docker("inspect", "--format",
                "{{range $key,$value := .NetworkSettings.Networks}}{{$key}} {{end}}", container);
            require(network.equals(NETWORK), "Unexpected network for " + container);
        }
        require(docker("port", APP, "18448/tcp").equals("127.0.0.1:18448"), "Unexpected HTTPS port mapping");
        String volume = docker("inspect", "--format", "{{range .Mounts}}"
            + "{{if eq .Destination `/var/lib/postgresql/data`}}{{.Name}}{{end}}{{end}}", DB);
        require(volume.equals(PROJECT + "_account-data"), "Unexpected E11 PostgreSQL volume");
        // Inspect locally, compare only the settings that prove the destination, and never log the env array.
        var settings = docker("inspect", "--format", "{{range .Config.Env}}{{println .}}{{end}}", APP).lines().toList();
        require(settings.containsAll(Set.of("DB_HOST=db", "DB_PORT=5432", "DB_NAME=" + DATABASE)),
            "App does not use the E11 PostgreSQL service");
        require(settings.stream().noneMatch(setting -> setting.startsWith("SPRING_DATASOURCE_URL=")
            || setting.startsWith("SPRING_APPLICATION_JSON=") || setting.startsWith("SPRING_CONFIG_LOCATION=")
            || setting.startsWith("SPRING_CONFIG_ADDITIONAL_LOCATION=")
            || ((setting.startsWith("JAVA_TOOL_OPTIONS=") || setting.startsWith("JDK_JAVA_OPTIONS=")
                || setting.startsWith("_JAVA_OPTIONS=")) && setting.contains("-Dspring."))),
            "Unexpected Spring datasource or configuration override in the E11 app");
        require(Set.of("null", "[]").contains(docker("inspect", "--format", "{{json .Config.Cmd}}", APP)),
            "Unexpected command override in the E11 app");
        require(sql("select current_database()").equals(DATABASE), "Connected database is not " + DATABASE);
    }

    private static List<String> fixtureRows(Path path) throws IOException {
        var lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        require(!lines.isEmpty() && lines.getFirst().replace("\ufeff", "")
            .equals("role,login,accountId,accountNumber,historyStartOn"), "Unexpected fixture header");
        return lines.subList(1, lines.size());
    }

    private static Fixture coldFixture(Options options) throws Exception {
        require(options.month().isBefore(YearMonth.now(BUSINESS_ZONE)), "A cold report requires a completed calendar month.");
        // The baseline is reconciled before the restart. Do not scan the ledger or the read pool here.
        var marker = sql("""
            select details->>'accounts',details->>'operations',details->>'reportMonth',details->>'seed'
            from audit_event where object_type='E11_DATASET' and action='E11_SEED_APPLIED'
                and result='SUCCESS' and details->>'profile'='pilot' limit 2
            """).lines().toList();
        require(marker.size() == 1, "Expected exactly one pilot seed marker before the cold report.");
        String[] counts = marker.getFirst().split("\\|", -1);
        require(counts.length == 4 && counts[0].equals("200000") && counts[1].equals("1000000")
            && counts[2].equals(options.month().toString()) && counts[3].equals(Long.toString(options.seed())),
            "Pilot seed marker does not match the required counts, report month or seed.");

        String[] fields = fixtureRows(options.fixtures()).stream().filter(row -> row.startsWith("ACCOUNTANT,"))
            .findFirst().orElseThrow(() -> new IllegalArgumentException("Missing cold-report accountant fixture.")).split(",", -1);
        require(fields.length == 5 && fields[1].matches("e11-accountant-[0-9]{3}"), "Invalid cold-report accountant fixture.");
        var account = new Account(UUID.fromString(fields[2]), fields[3], LocalDate.parse(fields[4]));
        String identity = sql("select a.account_number,a.history_start_on from app_user u cross join personal_account a"
            + " where u.login='" + fields[1] + "' and u.status='ACTIVE' and u.role_code='ACCOUNTANT'"
            + " and a.id='" + account.id() + "'::uuid and a.status='OPEN'"
            + " and a.account_number between 'LS-8000000181' and 'LS-8000000220'");
        require(identity.equals(account.number() + "|" + account.historyStart()), "Cold-report fixture does not match the pilot database.");
        return new Fixture(List.of(new Actor(Role.ACCOUNTANT, fields[1], account)), List.of(),
            Long.parseLong(counts[0]), Long.parseLong(counts[1]), -1);
    }

    private static Fixture fixtures(Path path, YearMonth month) throws Exception {
        var rows = fixtureRows(path);
        var users = new LinkedHashMap<String, DbUser>();
        for (String row : sql("select login,role_code,client_id from app_user where login like 'e11-%' and status='ACTIVE'")
            .lines().toList()) {
            if (row.isEmpty()) continue;
            var fields = row.split("\\|", -1);
            users.put(fields[0], new DbUser(Role.valueOf(fields[1]), fields[2]));
        }
        var accounts = new LinkedHashMap<UUID, DbAccount>();
        for (String row : sql("select id,account_number,history_start_on,client_id,status from personal_account "
            + "where account_number between 'LS-8000000001' and 'LS-8000000300'").lines().toList()) {
            if (row.isEmpty()) continue;
            var fields = row.split("\\|", -1);
            var account = new Account(UUID.fromString(fields[0]), fields[1], LocalDate.parse(fields[2]));
            accounts.put(account.id(), new DbAccount(account, UUID.fromString(fields[3]), fields[4]));
        }
        var actors = new ArrayList<Actor>();
        var seenUsers = new java.util.HashSet<String>();
        var seenAccounts = new java.util.HashSet<UUID>();
        for (String row : rows) {
            if (row.isBlank()) continue;
            var f = row.split(",", -1);
            require(f.length == 5, "Malformed synthetic fixture row");
            Role role = Role.valueOf(f[0]);
            String login = f[1];
            var account = new Account(UUID.fromString(f[2]), f[3], LocalDate.parse(f[4]));
            var user = users.get(login);
            var actual = accounts.get(account.id());
            require(login.matches("e11-" + role.name().toLowerCase(Locale.ROOT) + "-[0-9]{3}")
                && seenUsers.add(login) && seenAccounts.add(account.id()) && user != null && user.role() == role
                && actual != null && actual.account().equals(account) && actual.status().equals("OPEN"),
                "Fixture identity or dedicated account does not match the E11 database");
            require(role != Role.CLIENT || actual.client().toString().equals(user.client()), "Client fixture owns a different account");
            actors.add(new Actor(role, login, account));
        }
        var counts = sql("select (select count(*) from personal_account),count(*) filter(where storage_status='POSTED'),"
            + "count(*) filter(where storage_status='POSTED' and operation_date between date '" + month.atDay(1)
            + "' and date '" + month.atEndOfMonth() + "') from financial_operation").split("\\|", -1);
        long count = Long.parseLong(counts[0]);
        long monthPosted = Long.parseLong(counts[2]);
        require(monthPosted > 0, "The configured report month has no posted operations");
        long stride = Math.max(1, (count + 511) / 512);
        var pool = new ArrayList<Account>();
        for (String row : sql("select id,account_number,history_start_on from personal_account "
            + "where account_number between 'LS-8000000001' and 'LS-8000200000' "
            + "and mod(substring(account_number from 4)::bigint," + stride + ")=0 order by account_number limit 512")
            .lines().toList()) {
            if (row.isEmpty()) continue;
            var f = row.split("\\|", -1);
            pool.add(new Account(UUID.fromString(f[0]), f[1], LocalDate.parse(f[2])));
        }
        require(!pool.isEmpty(), "No E11 screen account pool");
        return new Fixture(List.copyOf(actors), List.copyOf(pool), count, Long.parseLong(counts[1]), monthPosted);
    }

    private static List<Actor> chooseActors(List<Actor> actors, int count, long seed) {
        Map<Role, Integer> seats = new EnumMap<>(Role.class);
        for (Role role : Role.values()) seats.put(role, Math.max(1, count * role.target / 300));
        while (seats.values().stream().mapToInt(Integer::intValue).sum() < count) {
            Role role = Arrays.stream(Role.values()).max(Comparator.comparingDouble(r -> count * r.target / 300.0 - seats.get(r)))
                .orElseThrow();
            seats.compute(role, (key, value) -> value + 1);
        }
        while (seats.values().stream().mapToInt(Integer::intValue).sum() > count) {
            Role role = Arrays.stream(Role.values()).filter(r -> seats.get(r) > 1)
                .max(Comparator.comparingDouble(r -> seats.get(r) - count * r.target / 300.0)).orElseThrow();
            seats.compute(role, (key, value) -> value - 1);
        }
        var result = new ArrayList<Actor>();
        for (Role role : Role.values()) {
            var selected = actors.stream().filter(actor -> actor.role() == role).sorted(Comparator.comparing(Actor::login))
                .limit(seats.get(role)).toList();
            require(selected.size() == seats.get(role), "Insufficient fixtures for " + role);
            result.addAll(selected);
        }
        // Interleave roles through the ramp while keeping the choice reproducible.
        java.util.Collections.shuffle(result, new java.util.Random(seed));
        return List.copyOf(result);
    }

    private static SSLContext trust(Path path, String password) throws Exception {
        var source = KeyStore.getInstance("PKCS12");
        char[] secret = password.toCharArray();
        try (var input = Files.newInputStream(path)) { source.load(input, secret); }
        finally { Arrays.fill(secret, '\0'); }
        var certificate = (X509Certificate) source.getCertificate("accounts-local");
        require(certificate != null, "The E11 PKCS12 lacks accounts-local");
        certificate.checkValidity();
        var pinned = KeyStore.getInstance(KeyStore.getDefaultType());
        pinned.load(null);
        pinned.setCertificateEntry("e11-local", certificate);
        var managers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        managers.init(pinned);
        var context = SSLContext.getInstance("TLS");
        context.init(null, managers.getTrustManagers(), null);
        return context;
    }

    private static HttpClient http(SSLContext tls) {
        return HttpClient.newBuilder().sslContext(tls).version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    private static String sql(String query) throws Exception {
        return docker("exec", DB, "psql", "-X", "-qAt", "-v", "ON_ERROR_STOP=1", "-U", "account_owner",
            "-d", DATABASE, "-c", query);
    }

    private static String docker(String... arguments) throws Exception {
        var command = new ArrayList<String>();
        command.add("docker.exe");
        command.addAll(List.of(arguments));
        var process = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var read = workers.submit(() -> new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
            if (!process.waitFor(20, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException("Docker guard or observation timed out");
            }
            if (process.exitValue() != 0)
                throw new IOException("Docker " + arguments[0] + " guard or observation failed, exit " + process.exitValue());
            return read.get().strip();
        }
    }

    private static void writeProfile(Path output, Options options, Fixture fixture, List<Actor> actors) throws IOException {
        String roles = Arrays.stream(Role.values()).map(role -> role + "=" + actors.stream()
            .filter(actor -> actor.role() == role).count()).reduce((left, right) -> left + "\n" + right).orElseThrow();
        Files.writeString(output.resolve("profile.properties"), "startedAt=" + Instant.now() + "\nproject=" + PROJECT
            + "\ndatabase=" + DATABASE + "\nurl=" + BASE + "\njava=" + System.getProperty("java.version")
            + "\naccounts=" + fixture.accounts() + "\nposted=" + fixture.posted() + "\nreportMonth=" + options.month()
            + "\nmonthPosted=" + (options.coldReport() == null ? fixture.monthPosted() : "NOT_MEASURED")
            + "\nseed=" + options.seed() + "\nusers=" + actors.size()
            + "\nruns=" + options.runs() + "\nrampSeconds=" + options.ramp() + "\nwarmupSeconds=" + options.warmup()
            + "\nsteadySeconds=" + options.steady() + "\nreportTailSeconds=" + options.tail()
            + "\nreportsPerType=" + options.reportsPerType()
            + (options.coldReport() == null ? "\nthinkMillis=1000..3000"
                : "\ncoldReport=" + options.coldReport() + "\ncountsSource=E11_SEED_APPLIED") + "\nreadPool="
            + fixture.readPool().size() + "\nfixtureHotShare=0.2\n" + roles + "\n");
    }

    private static Path localOutput(Path path) throws IOException {
        Path local = Path.of(".local").toRealPath();
        Path absolute = path.toAbsolutePath().normalize();
        require(absolute.startsWith(local) && !absolute.equals(local), "Output must be inside .local");
        Path existing = absolute.getParent();
        while (!Files.exists(existing)) existing = existing.getParent();
        require(existing.toRealPath().startsWith(local), "Output resolves outside .local");
        Files.createDirectories(absolute.getParent());
        return Files.createDirectory(absolute);
    }

    private static void sleepUntil(long deadline) throws InterruptedException {
        long remaining = deadline - System.nanoTime();
        if (remaining > 0) TimeUnit.NANOSECONDS.sleep(remaining);
    }

    private static long elapsed(long start, long finish) { return Math.ceilDiv(finish - start, 1_000_000); }

    private static String text(HttpResponse<byte[]> response) { return new String(response.body(), StandardCharsets.UTF_8); }

    private static Map<String, String> reportTotals(HttpResponse<byte[]> response) {
        var totals = new LinkedHashMap<String, String>();
        String html = text(response);
        var matches = REPORT_TOTAL.matcher(html);
        while (matches.find()) totals.put(matches.group(1), matches.group(2));
        var rows = REPORT_ROWS.matcher(html);
        if (rows.find()) totals.put("totalRows", rows.group(1));
        return totals;
    }

    private static Predicate<HttpResponse<byte[]>> hasField(String name) {
        return response -> fieldOrNull(response, name) != null;
    }

    private static String field(HttpResponse<byte[]> response, String name) throws StepFailure {
        String value = fieldOrNull(response, name);
        if (value == null) throw new StepFailure();
        return value;
    }

    private static String fieldOrNull(HttpResponse<byte[]> response, String name) {
        var tags = INPUT.matcher(text(response));
        while (tags.find()) {
            String found = null;
            String value = null;
            var attributes = ATTRIBUTE.matcher(tags.group());
            while (attributes.find()) {
                String key = attributes.group(1).toLowerCase(Locale.ROOT);
                String raw = attributes.group(2);
                if (key.equals("name")) found = raw.substring(1, raw.length() - 1);
                if (key.equals("value")) value = raw.substring(1, raw.length() - 1);
            }
            if (name.equals(found) && value != null && !value.isBlank()) return value;
        }
        return null;
    }

    private static String location(HttpResponse<byte[]> response) {
        try {
            URI uri = BASE.resolve(response.headers().firstValue("Location").orElseThrow());
            if (!sameOrigin(uri)) return "";
            return uri.getRawPath() + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
        } catch (RuntimeException failure) { return ""; }
    }

    private static String operationPath(HttpResponse<byte[]> response) {
        String location = location(response);
        if (!location.endsWith("?created")) return null;
        String path = location.substring(0, location.indexOf('?'));
        var match = OPERATION.matcher(path);
        return match.matches() ? path : null;
    }

    private static boolean sameOrigin(URI uri) {
        return "https".equals(uri.getScheme()) && "localhost".equals(uri.getHost()) && uri.getPort() == 18448
            && uri.getUserInfo() == null;
    }

    private static String form(String... keyValue) {
        var parts = new ArrayList<String>();
        for (int i = 0; i < keyValue.length; i += 2) {
            parts.add(URLEncoder.encode(keyValue[i], StandardCharsets.UTF_8) + "="
                + URLEncoder.encode(keyValue[i + 1], StandardCharsets.UTF_8));
        }
        return String.join("&", parts);
    }

    private static String csv(Object... values) {
        return Arrays.stream(values).map(Object::toString).map(value -> CSV_ESCAPE.matcher(value).find()
            ? "\"" + value.replace("\"", "\"\"") + "\"" : value).reduce((left, right) -> left + "," + right).orElse("") + "\n";
    }

    private static String decimal(double value) { return String.format(Locale.ROOT, "%.4f", value); }

    private static String property(Properties properties, String key) {
        String value = properties.getProperty(key);
        require(value != null && !value.isBlank(), "Required setting is missing: " + key);
        return value;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }

    record Options(boolean check, int users, int runs, int ramp, int warmup, int steady, int tail, int reportsPerType,
                   int timeout, int telemetry, long seed, YearMonth month, Path env, Path tls, Path fixtures, Path output,
                   ReportType coldReport) {
        static Options parse(String[] arguments) {
            var values = new LinkedHashMap<String, String>();
            boolean check = false;
            for (int i = 0; i < arguments.length; i++) {
                String key = arguments[i];
                if (key.equals("--check")) { check = true; continue; }
                require(i + 1 < arguments.length && Set.of("--users", "--runs", "--ramp", "--warmup", "--steady",
                    "--report-tail", "--reports-per-type", "--timeout", "--telemetry", "--seed", "--report-month",
                    "--env", "--tls", "--fixtures", "--output", "--cold-report").contains(key), "Unknown or incomplete option " + key);
                require(values.putIfAbsent(key, arguments[++i]) == null, "Repeated option " + key);
            }
            Path output = Path.of(values.getOrDefault("--output", ".local/e11-verification/load-"
                + Instant.now().toString().replace(':', '-') + "-" + UUID.randomUUID().toString().substring(0, 6)));
            ReportType coldReport = values.containsKey("--cold-report") ? ReportType.valueOf(values.get("--cold-report")) : null;
            boolean cold = coldReport != null;
            require(!cold || (!check && Set.of("--users", "--runs", "--ramp", "--warmup", "--steady", "--report-tail",
                "--reports-per-type", "--telemetry").stream().noneMatch(values::containsKey)),
                "--cold-report cannot be combined with --check or workload controls.");
            int users = cold ? 1 : number(values, "--users", 300, 5, 300);
            return new Options(check, users, number(values, "--runs", cold || users == 300 ? 1 : 3, 1, 10),
                number(values, "--ramp", cold ? 0 : 300, 0, 3600), number(values, "--warmup", cold ? 0 : 300, 0, 3600),
                cold ? 0 : number(values, "--steady", 1200, 1, 7200),
                number(values, "--report-tail", cold || users == 300 ? 0 : 180, 0, 1800),
                cold ? 1 : number(values, "--reports-per-type", 30, 30, 300), number(values, "--timeout", 60, 1, 300),
                number(values, "--telemetry", cold ? 0 : 15, 0, 120), Long.parseLong(values.getOrDefault("--seed", "1101")),
                YearMonth.parse(values.getOrDefault("--report-month", "2026-10")),
                Path.of(values.getOrDefault("--env", ".local/e11/stand.env")),
                Path.of(values.getOrDefault("--tls", ".local/e11/tls.p12")),
                Path.of(values.getOrDefault("--fixtures", ".local/e11-verification/fixtures.csv")), output, coldReport);
        }

        boolean pilot() { return !check && users == 300; }

        private static int number(Map<String, String> values, String key, int fallback, int min, int max) {
            int result = Integer.parseInt(values.getOrDefault(key, Integer.toString(fallback)));
            require(result >= min && result <= max, key + " must be between " + min + " and " + max);
            return result;
        }
    }
}
