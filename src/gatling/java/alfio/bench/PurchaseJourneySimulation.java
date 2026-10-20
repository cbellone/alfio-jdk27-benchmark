package alfio.bench;

import io.gatling.javaapi.core.ChainBuilder;
import io.gatling.javaapi.core.PopulationBuilder;
import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Session;
import io.gatling.javaapi.core.Simulation;
import io.gatling.javaapi.http.Http;
import io.gatling.javaapi.http.HttpRequestActionBuilder;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Random;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static io.gatling.javaapi.core.CoreDsl.*;
import static io.gatling.javaapi.http.HttpDsl.*;

/**
 * Open-model workload for alf.io: visitors arrive at a fixed rate, regardless of how fast the server responds.
 * <p>
 * Each arrival is a "journey": 3 anonymous page views, then a full purchase (reserve, attendee data, confirm).
 * 10% of the carts are abandoned, 30% of the buyers ask for an invoice, 30% pay on site.
 * Every random choice is derived from the journey index, so every run sends exactly the same traffic.
 * <p>
 * Configuration (environment variables):
 * <ul>
 *     <li>{@code BASE_URL}, {@code API_KEY}, {@code EVENT}</li>
 *     <li>{@code JOURNEY_SEED}: selects a different sequence of journeys (e.g. to train an AOT cache on other traffic than the measured one)</li>
 *     <li>{@code RATE}: journeys per second at steady state</li>
 *     <li>{@code RAMP_SECONDS}: linear ramp-up from 10% to 100% of {@code RATE}</li>
 *     <li>{@code STEADY_SECONDS}: duration at {@code RATE} (warm-up + measurement)</li>
 *     <li>{@code ASSERT_P95_MS}: if set, the run fails when p95 or the error rate exceed the limits (capacity probes)</li>
 *     <li>{@code START_MARKER}: file where the epoch millis of the first arrival is written</li>
 *     <li>{@code LATENCY_LOG}: CSV file where the latency of every request is written at the end of the run</li>
 * </ul>
 */
public class PurchaseJourneySimulation extends Simulation {

    private static final String BASE_URL = env("BASE_URL", "http://localhost:8080");
    private static final String API_KEY = env("API_KEY", "");
    private static final String EVENT = env("EVENT", "load-bench");
    private static final long JOURNEY_SEED = Long.parseLong(env("JOURNEY_SEED", "0"));
    private static final double RATE = Double.parseDouble(env("RATE", "1"));
    private static final Duration RAMP = Duration.ofSeconds(Long.parseLong(env("RAMP_SECONDS", "60")));
    private static final Duration STEADY = Duration.ofSeconds(Long.parseLong(env("STEADY_SECONDS", "300")));
    private static final String ASSERT_P95_MS = env("ASSERT_P95_MS", "");
    private static final String START_MARKER = System.getenv("START_MARKER");
    private static final String LATENCY_LOG = System.getenv("LATENCY_LOG");
    private static final Queue<String> LATENCIES = new ConcurrentLinkedQueue<>();

    private static final String EVENT_API = "/api/v2/public/event/" + EVENT;
    private static final String RESERVATION_API = "/api/v2/public/reservation/#{rid}";
    private static final Map<String, String> SESSION_HEADERS = Map.of("x-auth-token", "#{gid}", "X-CSRF-TOKEN", "#{csrf}");
    private static final Map<String, String> ADMIN_HEADERS = Map.of("Authorization", "apikey " + API_KEY);

    private static String env(String name, String defaultValue) {
        var value = System.getenv(name);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return value;
    }

    private static Iterator<Map<String, Object>> journeys() {
        return Stream.iterate(JOURNEY_SEED * 1_000_000_000L, i -> i + 1).map(i -> {
            var random = new Random(i);
            Map<String, Object> journey = new HashMap<>();
            journey.put("journey", i);
            journey.put("categoryIndex", random.nextInt(2));
            journey.put("quantity", 1 + random.nextInt(4));
            journey.put("abandon", random.nextDouble() < 0.1);
            journey.put("invoice", random.nextDouble() < 0.3);
            journey.put("onSite", random.nextDouble() < 0.3);
            return journey;
        }).iterator();
    }

    private static String reservationRequest(Session session) {
        List<Integer> categories = session.getList("categories");
        int category = categories.get(session.getInt("categoryIndex") % categories.size());
        return """
            {"reservation":[{"ticketCategoryId":%d,"quantity":%d}]}""".formatted(category, session.getInt("quantity"));
    }

    private static String contactAndTickets(Session session) {
        long journey = session.getLong("journey");
        List<String> uuids = session.getList("uuids");
        var tickets = IntStream.range(0, uuids.size())
            .mapToObj(n -> """
                "%s":{"firstName":"Att%d","lastName":"Ee%d","email":"att%d-%d@example.org","userLanguage":"en","additional":{}}"""
                .formatted(uuids.get(n), journey, n, journey, n))
            .collect(Collectors.joining(","));
        return """
            {"firstName":"Buyer%d","lastName":"Bench","email":"buyer%d@example.org",
             "billingAddress":"Via Benchmark 1\\n6742 Pollegio","billingAddressLine1":"Via Benchmark 1",
             "billingAddressZip":"6742","billingAddressCity":"Pollegio","billingAddressCompany":"",
             "vatCountryCode":"CH","skipVatNr":true,"addCompanyBillingDetails":false,
             "postponeAssignment":false,"invoiceRequested":%b,"tickets":{%s}}"""
            .formatted(journey, journey, session.getBoolean("invoice"), tickets);
    }

    private static String payment(Session session) {
        String proxy = "OFFLINE";
        String method = "BANK_TRANSFER";
        if (session.getBoolean("onSite")) {
            proxy = "ON_SITE";
            method = "ON_SITE";
        }
        return """
            {"paymentProxy":"%s","selectedPaymentMethod":"%s","termAndConditionsAccepted":true,"privacyPolicyAccepted":true}"""
            .formatted(proxy, method);
    }

    /**
     * Executes the request and records its latency, so that the analysis can compute percentiles
     * for the measurement window only (Gatling's own report covers the whole run).
     */
    private static ChainBuilder timed(String name, Function<Http, HttpRequestActionBuilder> request) {
        return exec(session -> session.remove("rt").remove("status"))
            .exec(request.apply(http(name)).check(responseTimeInMillis().saveAs("rt"), status().lt(400).saveAs("status")))
            .exec(session -> {
                long responseTime = -1;
                if (session.contains("rt")) {
                    responseTime = session.getLong("rt");
                }
                String outcome = "KO";
                if (session.contains("status")) {
                    outcome = "OK";
                }
                LATENCIES.add(System.currentTimeMillis() + "," + name + "," + responseTime + "," + outcome);
                return session;
            });
    }

    // anonymous visitor: server-rendered event page + the API calls the SPA makes.
    // Each visit is a different visitor, with its own session
    private final ChainBuilder browse = exec(
        flushCookieJar(),
        timed("event page", r -> r.get("/event/" + EVENT).header("Accept", "text/html")),
        timed("event", r -> r.get(EVENT_API)),
        timed("categories", r -> r.get(EVENT_API + "/ticket-categories?lang=en")),
        timed("events", r -> r.get("/api/v2/public/events"))
    );

    private final ChainBuilder purchase = exec(
        timed("info", r -> r.get("/api/v2/info")),
        // the public API falls back to the session cookie when there is no x-auth-token header:
        // start with a clean cookie jar, so that the server creates a new session and returns its id
        flushCookieJar(),
        timed("event (buyer)", r -> r.get(EVENT_API)
            .check(header("X-Auth-Token").saveAs("gid"), header("XSRF-TOKEN").saveAs("csrf"))),
        timed("categories (buyer)", r -> r.get(EVENT_API + "/ticket-categories?lang=en").header("x-auth-token", "#{gid}")
            .check(jsonPath("$.ticketCategories[*].id").ofInt().findAll().saveAs("categories"))),
        timed("reserve", r -> r.post(EVENT_API + "/reserve-tickets?lang=en").headers(SESSION_HEADERS)
            .body(StringBody(PurchaseJourneySimulation::reservationRequest))
            .check(jsonPath("$.value").saveAs("rid"))),
        timed("reservation", r -> r.get(RESERVATION_API).header("x-auth-token", "#{gid}")
            .check(jsonPath("$.ticketsByCategory[*].tickets[*].uuid").findAll().saveAs("uuids")))
    ).doIfOrElse(session -> session.getBoolean("abandon")).then(
        timed("abandon", r -> r.delete(RESERVATION_API).headers(SESSION_HEADERS))
    ).orElse(
        exec(
            timed("contact and tickets", r -> r.post(RESERVATION_API + "/validate-to-overview?lang=en&ignoreWarnings=true")
                .headers(SESSION_HEADERS).body(StringBody(PurchaseJourneySimulation::contactAndTickets))),
            timed("overview", r -> r.get(RESERVATION_API).header("x-auth-token", "#{gid}")),
            timed("confirm", r -> r.post(RESERVATION_API + "?lang=en").headers(SESSION_HEADERS)
                .body(StringBody(PurchaseJourneySimulation::payment))),
            timed("status", r -> r.get(RESERVATION_API + "/status").header("x-auth-token", "#{gid}")),
            timed("confirmation page", r -> r.get(RESERVATION_API).header("x-auth-token", "#{gid}"))
        )
    );

    private final ScenarioBuilder journey = scenario("journey")
        .feed(journeys())
        .repeat(3).on(browse)
        .exec(purchase);

    private final ScenarioBuilder stats = scenario("admin stats")
        .exec(timed("admin: event stats", r -> r.get("/api/v1/admin/event/" + EVENT + "/stats").headers(ADMIN_HEADERS)));

    // builds the full attendee list in memory
    private final ScenarioBuilder export = scenario("admin export")
        .exec(timed("admin: export attendees", r -> r.get("/api/v1/admin/event/" + EVENT + "/download-attendees").headers(ADMIN_HEADERS)));

    private static PopulationBuilder arrivals(ScenarioBuilder scenario, double rate) {
        return scenario.injectOpen(
            rampUsersPerSec(rate / 10).to(rate).during(RAMP),
            constantUsersPerSec(rate).during(STEADY)
        );
    }

    {
        var httpProtocol = http.baseUrl(BASE_URL)
            .acceptHeader("application/json")
            .contentTypeHeader("application/json")
            .disableCaching();
        var setUp = setUp(
            arrivals(journey, RATE),
            arrivals(stats, RATE / 10),
            arrivals(export, RATE / 50)
        ).protocols(httpProtocol);
        if (!ASSERT_P95_MS.isEmpty()) {
            setUp.assertions(
                global().failedRequests().percent().lt(1.0),
                global().responseTime().percentile(95.0).lt(Integer.parseInt(ASSERT_P95_MS))
            );
        }
    }

    @Override
    public void after() {
        if (LATENCY_LOG != null) {
            try {
                Files.writeString(Path.of(LATENCY_LOG), "epoch_millis,request,response_time_ms,outcome\n" + String.join("\n", LATENCIES) + "\n");
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    @Override
    public void before() {
        if (START_MARKER != null) {
            try {
                Files.writeString(Path.of(START_MARKER), Long.toString(System.currentTimeMillis()));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }
}
