# raidzOn backend

Independent Java 21 / Spring Boot application with Maven.

From this directory within the full repository:

```sh
mvn test
mvn package
mvn spring-boot:run
```

The test suite uses shared JSON fixtures from `../test-fixtures/`. The executable artifact is `target/raidzonbackend-0.1.0-SNAPSHOT.jar`:

```sh
java -jar target/raidzonbackend-0.1.0-SNAPSHOT.jar
```

By default the application binds to `127.0.0.1:8080`. Set `SERVER_ADDRESS=0.0.0.0` in a container/hosting environment, and set `PORT` if the platform requires another port. The Docker image sets these defaults for container use.

From the repository root:

```sh
mvn -f raidzonbackend/pom.xml test
docker build -t raidzonbackend ./raidzonbackend
docker run --rm -p 127.0.0.1:8080:8080 raidzonbackend
```

The Docker build packages without running tests because its independent build context contains no sibling fixtures. Run the repository test command first in CI. Health is available at `/actuator/health`.

The backend contains a pure match engine for the supported rules, immutable event history, latest-event Undo and deterministic replay. Shared full-match fixtures verify parity with the frontend, including clocks, player states, queues, statistics, lifecycle and scoring components. See [the engine decision](../docs/decisions/002-deterministic-match-engine.md) for assumptions and boundaries.

The default profile exposes health only. The postgres profile enables authentication, device-bound bearer sessions, guest claiming and ordered match-event HTTP writes. MSG91 is integrated but disabled by default. See [authentication, SMS configuration and deployment](../docs/decisions/005-authentication-and-sync.md) and [the persistence decision](../docs/decisions/004-postgres-event-persistence.md).

## PostgreSQL profile

Set `SPRING_PROFILES_ACTIVE=postgres`, `RAIDZON_DATABASE_URL` (for example `jdbc:postgresql://127.0.0.1:5432/raidzon`), `RAIDZON_DATABASE_USERNAME`, and `RAIDZON_DATABASE_PASSWORD`, then run the application normally. Flyway applies the migration before the match repository is available. No credentials are committed. Without this profile the API boots with health only and does not connect to PostgreSQL.

For real database tests, use a **dedicated test database** and set `RAIDZON_TEST_DATABASE_URL`, `RAIDZON_TEST_DATABASE_USERNAME`, and `RAIDZON_TEST_DATABASE_PASSWORD`, then run `mvn test`. The test role needs CREATE SCHEMA privileges. Tests create isolated `test_match_*` / `test_profile_*` / `test_auth_*` schemas containing synthetic data and leave them for inspection; use an ephemeral test database. Without the URL these database tests are skipped. Domain, codec and default-profile tests still run.
# raidzon-backend
