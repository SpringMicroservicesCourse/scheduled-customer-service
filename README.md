# scheduled-customer-service

> SpringBucks customer service with scheduled monitoring, circuit breaker, and bulkhead patterns

[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.4.5-brightgreen.svg)](https://spring.io/projects/spring-boot)
[![Spring Cloud](https://img.shields.io/badge/Spring%20Cloud-2024.0.2-blue.svg)](https://spring.io/projects/spring-cloud)
[![Java](https://img.shields.io/badge/Java-21-orange.svg)](https://openjdk.org/)
[![Resilience4j](https://img.shields.io/badge/Resilience4j-2.x-blue.svg)](https://resilience4j.readme.io/)
[![License](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

A sophisticated customer service demonstrating Spring Task Scheduling, Application Event mechanism, OpenFeign integration, and Resilience4j patterns (Circuit Breaker & Bulkhead) for robust microservice communication.

## Features

- **Scheduled Task Monitoring** with `@Scheduled` annotation
- **Application Event Mechanism** for internal decoupling
- **OpenFeign** declarative HTTP client
- **Circuit Breaker Pattern** with Resilience4j
- **Bulkhead Pattern** for concurrency control
- **Service Discovery** with Consul
- **Custom HTTP Client Configuration** with connection pooling
- **Automatic Order Pickup** via polling mechanism
- **Event-Driven Workflow** with `ApplicationEventPublisher`

## Tech Stack

- **Spring Boot** 3.4.5
- **Spring Cloud** 2024.0.2
- **OpenFeign** for HTTP communication
- **Resilience4j** for resilience patterns
- **Consul** for service discovery
- **Joda Money** 2.0.2 for money handling
- **Apache HttpClient 5** for connection management
- **Vavr** 0.10.4 for functional programming
- **Lombok** for boilerplate reduction
- **Maven** 3.8+

## Getting Started

### Prerequisites

- **JDK 21** or higher
- **Maven 3.8+** (or use included Maven Wrapper)
- **Running Consul** (port 8500)
- **Running kafka-waiter-service** (port 8080)
- **Running kafka-barista-service** (port 8070)

### Installation & Run

```bash
# Clone the repository
git clone https://github.com/SpringMicroservicesCourse/spring-cloud-stream-kafka
cd scheduled-customer-service

# Ensure infrastructure is running
# 1. Consul on port 8500
# 2. kafka-waiter-service on port 8080
# 3. kafka-barista-service on port 8070

# Run the application
./mvnw spring-boot:run
```

### Alternative: Run as JAR

```bash
# Build
./mvnw clean package

# Run
java -jar target/scheduled-customer-service-0.0.1-SNAPSHOT.jar
```

## Configuration

### Application Properties

```properties
# Server Configuration
server.port=8090

# Feign Timeout Configuration
feign.client.config.default.connect-timeout=500
feign.client.config.default.read-timeout=500

# Consul Service Discovery
spring.cloud.consul.host=localhost
spring.cloud.consul.port=8500
spring.cloud.consul.discovery.prefer-ip-address=true

# Circuit Breaker Configuration (Resilience4j 2.x)
resilience4j.circuitbreaker.instances.order.failure-rate-threshold=50
resilience4j.circuitbreaker.instances.order.wait-duration-in-open-state=5s
resilience4j.circuitbreaker.instances.order.sliding-window-type=COUNT_BASED
resilience4j.circuitbreaker.instances.order.sliding-window-size=5
resilience4j.circuitbreaker.instances.order.minimum-number-of-calls=3

# Bulkhead Configuration
resilience4j.bulkhead.instances.order.max-concurrent-calls=1
resilience4j.bulkhead.instances.order.max-wait-duration=50ms
```

### Configuration Highlights

| Property | Value | Description |
|----------|-------|-------------|
| `failure-rate-threshold` | 50 | Open circuit at 50% failure rate |
| `wait-duration-in-open-state` | 5s | Wait 5s before half-open attempt |
| `sliding-window-size` | 5 | Track last 5 calls |
| `max-concurrent-calls` | 1 | Allow only 1 concurrent call |

### Resilience4j 1.x vs 2.x

⚠️ **Critical Configuration Difference**

| Feature | v1.x (Old) | v2.x (New - Spring Boot 3.x) |
|---------|------------|------------------------------|
| **Prefix** | `backends` | `instances` ⚠️ |
| **Window Size** | `ring-buffer-size-in-closed-state` | `sliding-window-size` ⚠️ |
| **Window Type** | N/A | `sliding-window-type=COUNT_BASED` ⚠️ Required |
| **Min Calls** | N/A | `minimum-number-of-calls` ⚠️ Required |
| **Bulkhead Max** | `max-concurrent-call` | `max-concurrent-calls` ⚠️ Plural |
| **Bulkhead Wait** | `max-wait-time` | `max-wait-duration` ⚠️ |

**If using v1.x parameters, Circuit Breaker and Bulkhead will NOT trigger!**

## API Endpoints

### Customer Operations

| Method | Path | Description | Example |
|--------|------|-------------|---------|
| GET | `/customer/menu` | View coffee menu | `curl http://localhost:8090/customer/menu` |
| POST | `/customer/order` | Create and pay order | `curl -X POST http://localhost:8090/customer/order` |

### Order Flow

```
1. POST /customer/order
   ↓
2. Feign → waiter-service (create order: INIT)
   ↓
3. Feign → waiter-service (pay order: PAID)
   ↓
4. Publish OrderWaitingEvent
   ↓
5. Scheduled task monitors order state
   ↓
6. When state = BREWED → Feign update (TAKEN)
```

## Key Components

### 1. Scheduled Order Monitoring

**File:** `scheduler/CoffeeOrderScheduler.java`

```java
@Component
@Slf4j
public class CoffeeOrderScheduler {
    @Autowired
    private CoffeeOrderService coffeeOrderService;
    private Map<Long, CoffeeOrder> orderMap = new ConcurrentHashMap<>();
    
    /**
     * Listen for new orders via Application Event
     */
    @EventListener
    public void acceptOrder(OrderWaitingEvent event) {
        orderMap.put(event.getOrder().getId(), event.getOrder());
    }
    
    /**
     * Poll order status every second
     * Auto-pickup when state = BREWED
     */
    @Scheduled(fixedRate = 1000)
    public void waitForCoffee() {
        if (orderMap.isEmpty()) {
            return;
        }
        log.info("I'm waiting for my coffee.");
        orderMap.values().stream()
                .map(o -> coffeeOrderService.getOrder(o.getId()))
                .filter(o -> OrderState.BREWED == o.getState())
                .forEach(o -> {
                    log.info("Order [{}] is READY, I'll take it.", o);
                    coffeeOrderService.updateState(o.getId(),
                            OrderStateRequest.builder()
                                    .state(OrderState.TAKEN).build());
                    orderMap.remove(o.getId());
                });
    }
}
```

**Key Features:**
- **Event-Driven**: Decouples controller from scheduler via events
- **Thread-Safe**: Uses `ConcurrentHashMap` for concurrent access
- **Auto-Cleanup**: Removes orders after pickup to prevent memory leaks
- **Polling Strategy**: Checks every 1 second (configurable)

### 2. Controller with Resilience Patterns

**File:** `controller/CustomerController.java`

```java
@RestController
@RequestMapping("/customer")
@Slf4j
public class CustomerController implements ApplicationEventPublisherAware {
    private ApplicationEventPublisher applicationEventPublisher;
    
    @PostMapping("/order")
    @CircuitBreaker(name = "order")  // ← Circuit breaker protection
    @Bulkhead(name = "order")        // ← Concurrency limiting
    public CoffeeOrder createAndPayOrder() {
        // Create order via Feign
        NewOrderRequest orderRequest = NewOrderRequest.builder()
                .customer("Ray Chu")
                .items(Arrays.asList("capuccino"))
                .build();
        CoffeeOrder order = coffeeOrderService.create(orderRequest);
        
        // Pay order via Feign
        order = coffeeOrderService.updateState(order.getId(),
                OrderStateRequest.builder().state(OrderState.PAID).build());
        
        // Publish event for scheduler
        applicationEventPublisher.publishEvent(new OrderWaitingEvent(order));
        return order;
    }
    
    @Override
    public void setApplicationEventPublisher(ApplicationEventPublisher publisher) {
        this.applicationEventPublisher = publisher;
    }
}
```

**Resilience Features:**
- **Circuit Breaker**: Prevents cascading failures
- **Bulkhead**: Limits concurrent calls to protect resources
- **Programmatic Fallback**: Uses Vavr `Try` for error recovery

### 3. Application Event

**File:** `support/OrderWaitingEvent.java`

```java
@Data
public class OrderWaitingEvent extends ApplicationEvent {
    private CoffeeOrder order;
    
    public OrderWaitingEvent(CoffeeOrder order) {
        super(order);
        this.order = order;
    }
}
```

**Why Application Events?**
- ✅ **In-Memory**: Fast, no network overhead
- ✅ **Decoupling**: Controller doesn't know about scheduler
- ✅ **Simple**: Built-in Spring feature
- ⚠️ **Not Persistent**: Events lost on restart (use Kafka for durability)

## Resilience Patterns Explained

### Circuit Breaker States

```
CLOSED ──(50% failures)──> OPEN ──(5s wait)──> HALF_OPEN
  ↑                          │                      │
  └─────(success)────────────┴──(success)───────────┘
                                   │
                          (failure)└─> OPEN
```

**Configuration:**
- `sliding-window-size=5`: Track last 5 calls
- `failure-rate-threshold=50`: Open at 50% failure
- `minimum-number-of-calls=3`: Need 3 calls before calculation
- `wait-duration-in-open-state=5s`: Wait 5s before retry

### Bulkhead Pattern

```
Request 1 ──> [SLOT 1: OCCUPIED] ──> Processing
Request 2 ──> [Queue: 50ms wait] ──> Rejected or Accepted
Request 3 ──> [Queue: 50ms wait] ──> Rejected (timeout)
```

**Configuration:**
- `max-concurrent-calls=1`: Only 1 concurrent call allowed
- `max-wait-duration=50ms`: Max wait time in queue

## Monitoring

### Circuit Breaker State

```bash
curl http://localhost:8090/actuator/health
```

**Response:**
```json
{
  "status": "UP",
  "components": {
    "circuitBreakers": {
      "status": "UP",
      "details": {
        "order": {
          "status": "UP",
          "state": "CLOSED",
          "failureRate": "0.0%"
        }
      }
    }
  }
}
```

### Metrics

```bash
# Circuit breaker calls
curl http://localhost:8090/actuator/metrics/resilience4j.circuitbreaker.calls

# Bulkhead available concurrent calls
curl http://localhost:8090/actuator/metrics/resilience4j.bulkhead.available.concurrent.calls
```

## Best Practices Demonstrated

1. **Task Scheduling**: Use `@Scheduled` for periodic operations
2. **Event Mechanism**: Internal decoupling with Spring Events
3. **Resilience Patterns**: Circuit Breaker + Bulkhead for stability
4. **HTTP Client Tuning**: Custom connection pool configuration
5. **Service Discovery**: Dynamic endpoint resolution via Consul
6. **Declarative HTTP**: Clean API calls with OpenFeign
7. **AOP Support**: Enable with `@EnableAspectJAutoProxy` for Resilience4j annotations

## Development vs Production

### Development (Current Configuration)

```properties
# Short timeouts for quick feedback
feign.client.config.default.connect-timeout=500
feign.client.config.default.read-timeout=500

# Aggressive bulkhead for testing
resilience4j.bulkhead.instances.order.max-concurrent-calls=1
```

### Production (Recommended)

```properties
# Longer timeouts for stability
feign.client.config.default.connect-timeout=3000
feign.client.config.default.read-timeout=10000

# Relaxed bulkhead for throughput
resilience4j.bulkhead.instances.order.max-concurrent-calls=10
resilience4j.bulkhead.instances.order.max-wait-duration=5s

# Circuit breaker tuning
resilience4j.circuitbreaker.instances.order.sliding-window-size=100
resilience4j.circuitbreaker.instances.order.minimum-number-of-calls=20
```

## Testing

```bash
# Run unit tests
./mvnw test

# Integration test
./mvnw verify

# End-to-end test
curl -X POST http://localhost:8090/customer/order
```

## Troubleshooting

### Circuit Breaker Not Triggering

**Check:**
1. ✅ Using `instances` (not `backends`) in configuration
2. ✅ `sliding-window-type=COUNT_BASED` is set
3. ✅ `minimum-number-of-calls` is configured
4. ✅ `@EnableAspectJAutoProxy` annotation is present
5. ✅ Resilience4j dependency is `resilience4j-spring-boot3`

### Bulkhead Not Working

**Check:**
1. ✅ Using `max-concurrent-calls` (plural, not singular)
2. ✅ Using `max-wait-duration` (not `max-wait-time`)
3. ✅ Configuration prefix is `instances` (not `backends`)

### Scheduled Task Not Running

**Check:**
1. ✅ `@EnableScheduling` annotation is present
2. ✅ `orderMap` is not empty (publish event first)
3. ✅ No exceptions in logs

### Feign Client Connection Failed

**Check:**
1. ✅ Consul is running: `docker ps | grep consul`
2. ✅ waiter-service is registered in Consul
3. ✅ Service name matches: `waiter-service`

## Workflow Explained

### Complete Order Processing Flow

```
1. Customer Controller
   │
   ├─> Feign call: Create Order (INIT)
   ├─> Feign call: Pay Order (PAID)
   └─> Publish OrderWaitingEvent
        │
        ├─> Scheduler @EventListener adds to orderMap
        │
        └─> @Scheduled task (every 1s)
             │
             ├─> Query order status via Feign
             ├─> Filter: state == BREWED?
             └─> Yes → Update to TAKEN → Remove from map
```

### Application Event vs Kafka

| Feature | Application Event | Kafka Message |
|---------|-------------------|---------------|
| **Scope** | In-process | Cross-service |
| **Durability** | Memory (lost on restart) | Persistent |
| **Speed** | Very fast | Network latency |
| **Use Case** | Internal decoupling | Microservice communication |

## Key Components

### 1. Main Application Class

**File:** `CustomerServiceApplication.java`

```java
@SpringBootApplication
@EnableDiscoveryClient
@EnableFeignClients
@EnableAspectJAutoProxy  // ⚠️ Required for Circuit Breaker annotations
@EnableScheduling        // ⚠️ Required for @Scheduled tasks
public class CustomerServiceApplication {
    
    @Bean
    public CloseableHttpClient httpClient() {
        return HttpClients.custom()
                .setConnectionManager(PoolingHttpClientConnectionManagerBuilder.create()
                        .setMaxConnTotal(200)
                        .setMaxConnPerRoute(20)
                        .build())
                .evictIdleConnections(TimeValue.ofSeconds(30))
                .disableAutomaticRetries()
                .setKeepAliveStrategy(new CustomConnectionKeepAliveStrategy())
                .build();
    }
}
```

**Critical Annotations:**
- `@EnableAspectJAutoProxy`: Required for Resilience4j annotation-based features
- `@EnableScheduling`: Activates `@Scheduled` task support
- `@EnableFeignClients`: Scans for Feign client interfaces

### 2. Feign Client Interfaces

**File:** `integration/CoffeeOrderService.java`

```java
@FeignClient(name = "waiter-service", contextId = "coffeeOrder")
public interface CoffeeOrderService {
    @GetMapping("/order/{id}")
    CoffeeOrder getOrder(@PathVariable("id") Long id);
    
    @PostMapping(path = "/order/", consumes = MediaType.APPLICATION_JSON_VALUE)
    CoffeeOrder create(@RequestBody NewOrderRequest newOrder);
    
    @PutMapping("/order/{id}")
    CoffeeOrder updateState(@PathVariable("id") Long id,
                            @RequestBody OrderStateRequest orderState);
}
```

**Design Benefits:**
- ✅ **Declarative**: No manual HTTP client code
- ✅ **Type-Safe**: Compile-time validation
- ✅ **Integrated**: Works with Consul discovery
- ✅ **Resilient**: Supports timeout and retry configuration

### 3. Custom Connection Keep-Alive Strategy

**File:** `support/CustomConnectionKeepAliveStrategy.java`

```java
public class CustomConnectionKeepAliveStrategy implements ConnectionKeepAliveStrategy {
    private final long DEFAULT_SECONDS = 30;
    
    @Override
    public TimeValue getKeepAliveDuration(HttpResponse response, HttpContext context) {
        return Arrays.stream(response.getHeaders("Connection"))
                .filter(h -> StringUtils.equalsIgnoreCase(h.getName(), "timeout"))
                .findFirst()
                .map(h -> NumberUtils.toLong(h.getValue(), DEFAULT_SECONDS))
                .orElse(DEFAULT_SECONDS) * 1000;
    }
}
```

**Why Custom Strategy?**
- Dynamically adjust keep-alive based on server response
- Prevent connection exhaustion
- Optimize resource usage

## Scheduled Task Deep Dive

### Fixed Rate vs Fixed Delay

```java
// Fixed Rate: Execute every 1000ms regardless of execution time
@Scheduled(fixedRate = 1000)
public void taskA() { }

// Fixed Delay: Wait 1000ms after previous execution completes
@Scheduled(fixedDelay = 1000)
public void taskB() { }

// Initial Delay: Wait 5s before first execution
@Scheduled(initialDelay = 5000, fixedRate = 1000)
public void taskC() { }

// Cron Expression: Execute at specific times
@Scheduled(cron = "0 0 12 * * ?")  // Every day at noon
public void taskD() { }
```

## Monitoring

### Health Check

```bash
curl http://localhost:8090/actuator/health
```

### Circuit Breaker Events

```bash
# Check circuit breaker metrics
curl http://localhost:8090/actuator/metrics/resilience4j.circuitbreaker.state

# Output:
# 0 = CLOSED (normal)
# 1 = OPEN (blocking calls)
# 2 = HALF_OPEN (testing recovery)
```

### Consul Service Status

Visit: `http://localhost:8500/ui/dc1/services/customer-service`

## Best Practices Demonstrated

1. **Scheduled Tasks**: Use thread pool to prevent blocking
2. **Event Mechanism**: Decouple modules with Application Events
3. **Circuit Breaker**: Prevent cascading failures
4. **Bulkhead**: Isolate resources for stability
5. **Connection Pooling**: Reuse HTTP connections efficiently
6. **Service Discovery**: Dynamic endpoint resolution
7. **Graceful Shutdown**: Clean up resources with `@PreDestroy`

## Common Issues & Solutions

### Issue: Scheduled Task Doesn't Run

**Solutions:**
1. Add `@EnableScheduling` to main application class
2. Ensure method is in Spring-managed bean (`@Component`)
3. Check no exceptions thrown during execution

### Issue: Circuit Breaker Always Closed

**Solutions:**
1. Verify `minimum-number-of-calls` threshold is met
2. Check failure rate threshold (need >= 50% failures)
3. Ensure `@EnableAspectJAutoProxy` is present

### Issue: Feign Timeout

**Solutions:**
1. Increase timeout values in `application.properties`
2. Check target service health
3. Verify Consul registration

## Extended Practice

**Suggested Enhancements:**

1. Add WebSocket for real-time order updates
2. Implement retry mechanism with exponential backoff
3. Create admin dashboard for order monitoring
4. Add custom fallback methods for circuit breaker
5. Implement async scheduled tasks with thread pool
6. Add Prometheus metrics export
7. Create integration tests with WireMock

## References

- [Spring Task Scheduling](https://docs.spring.io/spring-framework/docs/current/reference/html/integration.html#scheduling)
- [Spring Application Events](https://docs.spring.io/spring-framework/docs/current/reference/html/core.html#context-functionality-events)
- [OpenFeign Documentation](https://docs.spring.io/spring-cloud-openfeign/docs/current/reference/html/)
- [Resilience4j Spring Boot](https://resilience4j.readme.io/docs/getting-started-3)
- [Consul Service Discovery](https://www.consul.io/docs)

## License

MIT License - see [LICENSE](LICENSE) file for details.

## About Us

我們主要專注在敏捷專案管理、物聯網（IoT）應用開發和領域驅動設計（DDD）。喜歡把先進技術和實務經驗結合，打造好用又靈活的軟體解決方案。近來也積極結合 AI 技術，推動自動化工作流，讓開發與運維更有效率、更智慧。持續學習與分享，希望能一起推動軟體開發的創新和進步。

## Contact

**風清雲談** - 專注於敏捷專案管理、物聯網（IoT）應用開發和領域驅動設計（DDD）。

- 🌐 官方網站：[風清雲談部落格](https://blog.fengqing.tw/)
- 📘 Facebook：[風清雲談粉絲頁](https://www.facebook.com/profile.php?id=61576838896062)
- 💼 LinkedIn：[Chu Kuo-Lung](https://www.linkedin.com/in/chu-kuo-lung)
- 📺 YouTube：[雲談風清頻道](https://www.youtube.com/channel/UCXDqLTdCMiCJ1j8xGRfwEig)
- 📧 Email：[fengqing.tw@gmail.com](mailto:fengqing.tw@gmail.com)

---

**⭐ 如果這個專案對您有幫助，歡迎給個 Star！**
