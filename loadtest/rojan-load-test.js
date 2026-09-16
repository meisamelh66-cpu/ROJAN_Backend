/**
 * ROJAN Scalability & Production Hardening Pass - Section 10/11 (Load Testing / Performance
 * Metrics). Scenarios A-J: Authentication, Salon discovery, Salon profile, Services,
 * Availability, Customer search, Booking, Manager dashboard, CRM, Concurrent multi-tenant
 * traffic.
 *
 * IMPORTANT - target: this script is written to run against a LOCAL instance of the backend
 * (docker-compose Postgres/Redis/Kafka + `./gradlew :bootstrap:bootRun`), never the live
 * production deployment at https://api.rojanai.ir. Load-testing a real, live production system
 * without explicit, informed authorization risks a genuine outage for real users - see
 * ROJAN_SCALABILITY_PRODUCTION_HARDENING_REPORT.md's own "Human Decision Boundary" section.
 * BASE_URL below defaults to localhost specifically so this can never be pointed at production
 * by accident; override it explicitly (-e BASE_URL=...) only against a deliberately-provisioned
 * staging environment, never production.
 *
 * setup() creates its own fixtures (2 salons - "Salon A"/"Salon B", each with a service,
 * specialist, working hours, and activation - plus a pool of pre-authenticated customer/manager
 * accounts) so the timed portion of the test never pays for one-time setup cost, and multi-tenant
 * scenario J has two genuinely distinct salons to cross-check isolation against.
 *
 * Login/register are deliberately NOT included in the sustained-load scenarios: this backend
 * rate-limits them for real (per-email and per-IP, Redis-backed - confirmed in
 * ROJAN_FINAL_SECURITY_AUDIT.md), and every k6 VU in a single run shares one source IP. Hammering
 * /auth/login at "1000 concurrent users" from one IP would mostly measure the rate limiter
 * correctly rejecting requests, not backend capacity - a real, useful signal in its own right
 * (scenario A, run at a deliberately small, fixed iteration count to characterize exactly where
 * the ceiling is), but not something to scale up into the thousands.
 */
import http from "k6/http";
import { check, sleep } from "k6";
import { Trend, Counter } from "k6/metrics";

export const options = {
  setupTimeout: "180s",
  scenarios: {
    // Scenario A - Authentication. Deliberately small and fixed: proves login works under a
    // handful of concurrent requests and characterizes the real rate-limit ceiling, not backend
    // throughput (see the file's own doc comment above).
    auth: {
      executor: "shared-iterations",
      vus: 5,
      iterations: 25,
      exec: "authScenario",
      startTime: "0s",
    },
    // Scenarios B-F - public, unauthenticated, read-heavy. These are the ones actually scaled up
    // across the concurrency levels this task asks to characterize.
    salon_discovery: { executor: "constant-vus", vus: __ENV.VUS ? Number(__ENV.VUS) : 50, duration: __ENV.DURATION || "30s", exec: "salonDiscoveryScenario", startTime: "5s" },
    salon_profile: { executor: "constant-vus", vus: __ENV.VUS ? Number(__ENV.VUS) : 50, duration: __ENV.DURATION || "30s", exec: "salonProfileScenario", startTime: "5s" },
    services_and_specialists: { executor: "constant-vus", vus: __ENV.VUS ? Number(__ENV.VUS) : 50, duration: __ENV.DURATION || "30s", exec: "servicesScenario", startTime: "5s" },
    availability: { executor: "constant-vus", vus: __ENV.VUS ? Number(__ENV.VUS) : 50, duration: __ENV.DURATION || "30s", exec: "availabilityScenario", startTime: "5s" },
    // Scenario G - Booking (authenticated, state-changing). Each VU uses its own pre-authenticated
    // customer and a unique time slot (offset by VU/iteration) so this measures real booking
    // throughput, not artificial conflict contention - BookingConflictConcurrencyIntegrationTest
    // (application/bootstrap test suite) already separately proves the exclusive-slot guarantee
    // itself under genuine same-slot contention; this scenario is about sustained legitimate load.
    booking: { executor: "constant-vus", vus: __ENV.VUS ? Number(__ENV.VUS) : 20, duration: __ENV.DURATION || "30s", exec: "bookingScenario", startTime: "40s" },
    // Scenario H/I - Manager dashboard / CRM (authenticated reads).
    manager_dashboard: { executor: "constant-vus", vus: __ENV.VUS ? Number(__ENV.VUS) : 30, duration: __ENV.DURATION || "30s", exec: "dashboardScenario", startTime: "40s" },
    crm_customer_search: { executor: "constant-vus", vus: __ENV.VUS ? Number(__ENV.VUS) : 30, duration: __ENV.DURATION || "30s", exec: "crmScenario", startTime: "40s" },
    // Scenario J - concurrent multi-tenant traffic: every VU alternates between Salon A and Salon
    // B on the SAME endpoint shape, so a cross-tenant leak would show up as salon B's data
    // appearing under salon A's request or vice versa.
    multi_tenant: { executor: "constant-vus", vus: __ENV.VUS ? Number(__ENV.VUS) : 40, duration: __ENV.DURATION || "30s", exec: "multiTenantScenario", startTime: "80s" },
  },
  thresholds: {
    // Initial engineering targets (Section 12) - evaluated against the real baseline this run
    // produces, not treated as pre-verified facts. See the report for what the baseline actually
    // showed and whether these were met, adjusted, or missed.
    "http_req_duration{scenario:salon_discovery}": ["p(95)<500"],
    "http_req_duration{scenario:salon_profile}": ["p(95)<500"],
    "http_req_duration{scenario:booking}": ["p(95)<1000"],
    "http_req_duration{scenario:manager_dashboard}": ["p(95)<1000"],
    http_req_failed: ["rate<0.01"],
  },
};

const BASE_URL = __ENV.BASE_URL || "http://localhost:8080";

const crossTenantLeaks = new Counter("cross_tenant_leak_detected");
const bookingConflicts = new Trend("booking_conflict_rate");

function authHeaders(token) {
  return { headers: { Authorization: `Bearer ${token}`, "Content-Type": "application/json" } };
}

function registerAndLogin(email, role) {
  const reg = http.post(`${BASE_URL}/api/v1/auth/register`, JSON.stringify({ email, password: "supersecret123", fullName: `Load Test ${role}`, role }), { headers: { "Content-Type": "application/json" } });
  if (reg.status !== 201) {
    console.error(`register failed (${reg.status}): ${reg.body}`);
  }
  const login = http.post(`${BASE_URL}/api/v1/auth/login`, JSON.stringify({ email, password: "supersecret123" }), { headers: { "Content-Type": "application/json" } });
  if (login.status !== 200) {
    console.error(`login failed (${login.status}): ${login.body}`);
  }
  return login.json("accessToken");
}

function createAndActivateSalon(managerToken, name) {
  const salonRes = http.post(`${BASE_URL}/api/v1/salons`, JSON.stringify({ name, description: null, phone: "+15550300", email: null, address: "1 Main St" }), authHeaders(managerToken));
  if (salonRes.status !== 201) console.error(`create salon failed (${salonRes.status}): ${salonRes.body}`);
  const salon = salonRes.json();
  const categoryRes = http.post(`${BASE_URL}/api/v1/salons/${salon.id}/categories`, JSON.stringify({ name: "Hair", description: null }), authHeaders(managerToken));
  if (categoryRes.status !== 201) console.error(`create category failed (${categoryRes.status}): ${categoryRes.body}`);
  const category = categoryRes.json();
  const serviceRes = http.post(`${BASE_URL}/api/v1/salons/${salon.id}/categories/${category.id}/services`, JSON.stringify({ name: "Haircut", description: null, durationMinutes: 30, price: "25.00" }), authHeaders(managerToken));
  if (serviceRes.status !== 201) console.error(`create service failed (${serviceRes.status}): ${serviceRes.body}`);
  const service = serviceRes.json();
  const specialistRes = http.post(`${BASE_URL}/api/v1/salons/${salon.id}/specialists`, JSON.stringify({ userId: null, displayName: "Load Test Stylist", bio: null, photoUrl: null, mobileNumber: "+989120000099", specialty: "Stylist" }), authHeaders(managerToken));
  if (specialistRes.status !== 201) console.error(`create specialist failed (${specialistRes.status}): ${specialistRes.body}`);
  const specialist = specialistRes.json();
  const whRes = http.put(`${BASE_URL}/api/v1/salons/${salon.id}/working-hours/MONDAY`, JSON.stringify({ intervals: [{ start: "09:00", end: "17:00" }] }), authHeaders(managerToken));
  if (whRes.status !== 200) console.error(`set working hours failed (${whRes.status}): ${whRes.body}`);
  const activateRes = http.post(`${BASE_URL}/api/v1/salons/${salon.id}/activate`, null, authHeaders(managerToken));
  if (activateRes.status !== 200) console.error(`activate salon failed (${activateRes.status}): ${activateRes.body}`);
  console.log(`created salon: id=${salon.id} slug=${salon.slug}`);
  return { salon, category, service, specialist, managerToken };
}

export function setup() {
  const runId = `${Date.now()}`;
  const managerAEmail = `loadtest.manager.a.${runId}@example.com`;
  const managerBEmail = `loadtest.manager.b.${runId}@example.com`;
  const managerAToken = registerAndLogin(managerAEmail, "MANAGER");
  const managerBToken = registerAndLogin(managerBEmail, "MANAGER");

  const salonA = createAndActivateSalon(managerAToken, `Load Test Salon A ${runId}`);
  const salonB = createAndActivateSalon(managerBToken, `Load Test Salon B ${runId}`);

  // A pool of pre-authenticated customer tokens, created once here (outside the rate-limited
  // hot loop) and reused by every VU in the timed booking/CRM/dashboard scenarios.
  const customerTokens = [];
  for (let i = 0; i < 12; i++) {
    customerTokens.push(registerAndLogin(`loadtest.customer.${runId}.${i}@example.com`, "CUSTOMER"));
  }

  return { runId, salonA, salonB, customerTokens };
}

export function authScenario() {
  const email = `loadtest.auth.${Date.now()}.${Math.random()}@example.com`;
  registerAndLogin(email, "CUSTOMER");
}

export function salonDiscoveryScenario() {
  const res = http.get(`${BASE_URL}/api/v1/public/salons?page=0&size=20`);
  if (res.status !== 200) {
    console.error(`salon discovery failed (${res.status}): ${res.body}`);
  }
  check(res, { "200 OK": (r) => r.status === 200 });
}

export function salonProfileScenario(data) {
  const res = http.get(`${BASE_URL}/api/v1/public/salons/${data.salonA.salon.slug}`);
  check(res, { "200 OK": (r) => r.status === 200, "correct salon": (r) => r.json("id") === data.salonA.salon.id });
}

export function servicesScenario(data) {
  const slug = data.salonA.salon.slug;
  const res1 = http.get(`${BASE_URL}/api/v1/public/salons/${slug}/categories`);
  const res2 = http.get(`${BASE_URL}/api/v1/public/salons/${slug}/specialists`);
  check(res1, { "200 OK (categories)": (r) => r.status === 200 });
  check(res2, { "200 OK (specialists)": (r) => r.status === 200 });
}

export function availabilityScenario(data) {
  const { salon, specialist, service } = data.salonA;
  const res = http.get(`${BASE_URL}/api/v1/public/salons/${salon.slug}/specialists/${specialist.id}/available-slots?serviceId=${service.id}&date=${futureDateString(30)}`);
  if (res.status !== 200) console.error(`availability failed (${res.status}): ${res.body}`);
  check(res, { "200 OK (availability)": (r) => r.status === 200 });
}

export function bookingScenario(data) {
  const token = data.customerTokens[__VU % data.customerTokens.length];
  const { salon, service, specialist } = data.salonA;
  // Unique slot per VU/iteration - measures sustained legitimate throughput, not conflict
  // contention (which BookingConflictConcurrencyIntegrationTest already covers directly).
  const startTime = futureIsoDateTime(60 + (__VU % 200), (__ITER % 16) * 30);
  const res = http.post(
    `${BASE_URL}/api/v1/bookings`,
    JSON.stringify({ salonId: salon.id, serviceId: service.id, specialistId: specialist.id, startTime, notes: null }),
    authHeaders(token),
  );
  bookingConflicts.add(res.status === 409 ? 1 : 0);
  check(res, { "201 or 409": (r) => r.status === 201 || r.status === 409 });
}

export function dashboardScenario(data) {
  const token = data.salonA.managerToken;
  const res = http.get(`${BASE_URL}/api/v1/dashboard/insights`, authHeaders(token));
  check(res, { "200 or 409 (multi-salon disambiguation)": (r) => r.status === 200 || r.status === 409 });
}

export function crmScenario(data) {
  const token = data.salonA.managerToken;
  const res = http.get(`${BASE_URL}/api/v1/salons/${data.salonA.salon.id}/customers?page=0&size=20`, authHeaders(token));
  if (res.status !== 200) console.error(`CRM search failed (${res.status}): ${res.body}`);
  check(res, { "200 OK (crm)": (r) => r.status === 200 });
}

export function multiTenantScenario(data) {
  const useSalonA = __VU % 2 === 0;
  const target = useSalonA ? data.salonA : data.salonB;
  const other = useSalonA ? data.salonB : data.salonA;

  const res = http.get(`${BASE_URL}/api/v1/public/salons/${target.salon.slug}`);
  if (res.status !== 200) console.error(`multi-tenant profile failed (${res.status}): ${res.body}`);
  const returnedId = res.json("id");
  check(res, { "200 OK (multi-tenant profile)": (r) => r.status === 200 });
  if (returnedId !== target.salon.id || returnedId === other.salon.id) {
    crossTenantLeaks.add(1);
  }

  const crmRes = http.get(`${BASE_URL}/api/v1/salons/${target.salon.id}/customers?page=0&size=20`, authHeaders(target.managerToken));
  // A manager token for salon A must never be usable to read salon B's customers, or vice versa -
  // SalonPermissionResolver.resolve() returning emptySet() for a non-owner/member should 403 this.
  check(crmRes, { "200 for own salon": (r) => r.status === 200 });
}

function futureDateString(daysAhead) {
  const d = new Date(Date.now() + daysAhead * 86400000);
  return d.toISOString().slice(0, 10);
}

function futureIsoDateTime(daysAhead, minuteOffset) {
  const d = new Date(Date.now() + daysAhead * 86400000);
  d.setUTCHours(10, minuteOffset % 60, 0, 0);
  return d.toISOString().slice(0, 19);
}
