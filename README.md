# Circuit Breaker Demo (Spring Boot + Resilience4j)

A small Spring Boot project that shows **what a circuit breaker is, why you need it, and how to use it** with
[Resilience4j](https://resilience4j.readme.io/).

There is a fake "pricing service" that you can switch between **healthy**, **failing** and **slow** with one HTTP call.
You can then watch the circuit breaker react in real time.

---

## 1. The problem

Modern apps call other services (payments, pricing, inventory...). Sooner or later one of them has a bad day:
it errors out or becomes very slow.

Without protection:

1. Your app keeps calling the sick service.
2. Each call waits (a long time) and then fails.
3. Threads pile up waiting, your app becomes slow too, and finally **it goes down as well**.
4. The sick service gets even more traffic, so it can't recover. This is called a **cascading failure**.

## 2. The idea: a circuit breaker

It works like the **electrical fuse** in your house. When something is wrong, it "trips" and cuts the connection
so the damage doesn't spread. Later it carefully checks if things are OK again.

The circuit breaker sits between your code and the remote service and has **3 states**:

```
                failure rate >= threshold
        +-------------------------------------+
        |                                     v
   +---------+                           +---------+
   | CLOSED  |                           |  OPEN   |
   | (normal)|                           | (blocks)|
   +---------+                           +---------+
        ^                                     |
        |  trial calls succeed                | wait time is over (10s)
        |                                     v
        |                              +-------------+
        +------------------------------| HALF_OPEN   |
                                       | (testing)   |
              trial calls fail  -----> +-------------+
              (back to OPEN)
```

| State | What happens |
|---|---|
| **CLOSED** | Normal. All calls go to the remote service. The breaker counts successes and failures. |
| **OPEN** | The service is considered sick. Calls are **blocked immediately** (no waiting, no load on the sick service) and a **fallback** answer is returned. |
| **HALF_OPEN** | After a waiting time, a few **trial calls** are let through. If they work -> back to CLOSED. If they fail -> back to OPEN. |

### Fallback
A fallback is the "plan B" answer you return when the real call can't be made: a default value, a cached value,
an empty list, a friendly message. The user gets *something* instead of an error page or a long wait.

### What counts as a failure?
- An exception (e.g. connection error, HTTP 500).
- A **slow call** (slower than a limit you choose). A very slow service is almost as bad as a dead one.
- **Not** business errors like "product not found". That is a valid answer, not an outage, so we ignore it.

---

## 3. How it is implemented here

### Dependencies (`pom.xml`)
| Dependency | Why |
|---|---|
| `resilience4j-spring-boot3` | The circuit breaker library + Spring Boot integration |
| `spring-boot-starter-aop` | The `@CircuitBreaker` annotation works through Spring AOP (a proxy around your bean) |
| `spring-boot-starter-actuator` | Shows the breaker state at `/actuator/circuitbreakers` |

### Project structure
```
src/main/java/com/example/circuitbreaker
├── CircuitBreakerDemoApplication.java
├── client/
│   ├── PricingClient.java          # fake remote service, behaviour can be switched
│   ├── FailureMode.java            # HEALTHY | FAILING | SLOW
│   └── RemoteServiceException.java # the "service is down" error
├── service/PricingService.java     # <-- @CircuitBreaker + fallbacks live here
├── controller/PricingController.java
└── model/PriceResponse.java
src/main/resources/application.yml  # <-- breaker configuration
src/test/.../CircuitBreakerFlowTest.java
```

### The annotation (`PricingService`)

```java
@CircuitBreaker(name = "pricing", fallbackMethod = "fallback")
public PriceResponse getPrice(String product) {
    BigDecimal price = client.fetchPrice(product);   // the risky remote call
    return new PriceResponse(product, price, "REMOTE");
}
```

- `name = "pricing"` links the method to the settings in `application.yml`.
- `fallbackMethod = "fallback"` names the plan-B method(s).

There are three `fallback` methods. Resilience4j picks the one whose exception parameter matches best:

| Exception | Fallback behaviour | Response `source` |
|---|---|---|
| `CallNotPermittedException` (breaker is OPEN) | Return default price, remote service **not called** | `FALLBACK_CIRCUIT_OPEN` |
| `IllegalArgumentException` (business error) | Rethrow it (controller returns 404) | - |
| anything else (`Throwable`) | Return default price | `FALLBACK_ERROR` |

### The configuration (`application.yml`)

| Setting | Value | Meaning |
|---|---|---|
| `slidingWindowType` | `COUNT_BASED` | Judge the **last N calls** (the other option is `TIME_BASED`). |
| `slidingWindowSize` | `10` | N = 10 calls. |
| `minimumNumberOfCalls` | `5` | Don't decide anything before 5 calls were seen (avoids opening because of 1 unlucky call). |
| `failureRateThreshold` | `50` | Open when **50%+** of the calls in the window failed. |
| `slowCallDurationThreshold` | `1s` | A call slower than 1 second counts as "slow". |
| `slowCallRateThreshold` | `80` | Open when **80%+** of the calls are slow. |
| `waitDurationInOpenState` | `10s` | Stay OPEN for 10 seconds before testing again. |
| `automaticTransitionFromOpenToHalfOpenEnabled` | `true` | Move to HALF_OPEN by itself after the wait (no new request needed). |
| `permittedNumberOfCallsInHalfOpenState` | `3` | Number of trial calls in HALF_OPEN. |
| `recordExceptions` | `RemoteServiceException`, `TimeoutException` | These count as failures. |
| `ignoreExceptions` | `IllegalArgumentException` | Business errors never count. |

---

## 4. Run it

**Requirements:** Java 17+ and Maven. No database or other services needed.

```bash
mvn spring-boot:run
```

### Walkthrough

Open a second terminal and follow these steps. Watch the app log in the first one.

**Step 1 - everything is fine (CLOSED)**
```bash
curl localhost:8080/prices/laptop
# {"product":"laptop","price":999.99,"source":"REMOTE"}
```

**Step 2 - break the remote service**
```bash
curl -X POST localhost:8080/simulate/FAILING
```

**Step 3 - call it a few times**
```bash
for i in 1 2 3 4 5 6 7; do curl -s localhost:8080/prices/laptop; echo; done
```
- Calls 1-5: `"source":"FALLBACK_ERROR"` - the real service was called and failed.
  After 5 failures (100% >= 50%) the breaker **opens**.
- Calls 6-7: `"source":"FALLBACK_CIRCUIT_OPEN"` - **the remote service is not called anymore**.
  The log shows `Circuit is OPEN` and the "Remote call #" counter stops growing.

**Step 4 - check the state**
```bash
curl localhost:8080/status
# "breakerState":"OPEN", "notPermittedCalls": 2, ...
curl localhost:8080/actuator/circuitbreakers     # same info, Actuator format
curl localhost:8080/actuator/circuitbreakerevents # history of state changes and calls
```

**Step 5 - fix the service, then wait ~10 seconds**
```bash
curl -X POST localhost:8080/simulate/HEALTHY
sleep 10
curl localhost:8080/status        # "breakerState":"HALF_OPEN"
```

**Step 6 - trial calls close the circuit**
```bash
for i in 1 2 3; do curl -s localhost:8080/prices/laptop; echo; done
curl localhost:8080/status        # "breakerState":"CLOSED"  - back to normal
```

**Bonus - slow service**
```bash
curl -X POST localhost:8080/simulate/SLOW
for i in 1 2 3 4 5 6; do curl -s localhost:8080/prices/laptop; echo; done
```
Each call succeeds but takes 2s (> 1s), so they count as slow calls. After 5 of them the breaker opens
and the next calls are instant again.

**Business errors do not open the breaker**
```bash
curl -i localhost:8080/prices/banana    # 404 Unknown product, breaker stays CLOSED
```

### Run the test
```bash
mvn test
```
`CircuitBreakerFlowTest` checks the whole life cycle automatically:
CLOSED -> OPEN (after failures) -> blocked calls -> HALF_OPEN -> CLOSED,
and that business errors don't open the breaker.

---

## 5. Good practices

- **Always write a fallback** that makes sense for the business (default value, cache, "try again later").
- **Tune the numbers** to your traffic. A tiny window with a tiny threshold opens too easily; a huge one reacts too late.
- **Use one breaker per remote dependency** (`pricing`, `payments`, ...), so one sick service doesn't block the others.
- **Don't count business errors** as failures (`ignoreExceptions`).
- **Combine with other Resilience4j tools:**
  - `@Retry` - try again a few times (put it *inside* the breaker).
  - `@TimeLimiter` - stop waiting after X seconds.
  - `@Bulkhead` / `@RateLimiter` - limit concurrent calls / calls per second.
- **Monitor it.** Alert when a breaker opens - it means a dependency is in trouble.
- Remember the proxy rule: `@CircuitBreaker` only works when the method is called **from another bean**,
  not from inside the same class.
