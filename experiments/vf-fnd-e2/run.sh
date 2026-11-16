#!/usr/bin/env bash
set -euo pipefail
# No production build/config changes. Copy exact checked-out server into runner-local scratch.
out=/tmp/vf-fnd-e2
mkdir -p "$out"
cp -a apps/server "$out/server"
cp experiments/vf-fnd-e2/GuardedHybridExperimentTest.java "$out/server/src/test/java/io/wyrmgate/iam/idp/protocol/"
cp experiments/vf-fnd-e2/AssertionReplayExperimentTest.java "$out/server/src/test/java/io/wyrmgate/iam/idp/protocol/"
python3 - "$out/server/pom.xml" <<'PY'
import pathlib,sys
p=pathlib.Path(sys.argv[1])
s=p.read_text()
s=s.replace('<dependencies>','<dependencies>\n        <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-oauth2-authorization-server</artifactId><scope>test</scope></dependency>',1)
p.write_text(s)
PY
git rev-parse HEAD
java -version
sh apps/server/mvnw -version
sha256sum apps/server/src/main/java/io/wyrmgate/iam/idp/protocol/{IdpProtocolService,JdbcIdpAuthorizationCodeRepository,IdpJwtIssuer}.java
sh apps/server/mvnw -B -ntp --strict-checksums -f "$out/server/pom.xml" dependency:tree > "$out/dependency-tree.txt"
sh apps/server/mvnw -B -ntp --strict-checksums -f "$out/server/pom.xml" -Dtest=GuardedHybridExperimentTest,AssertionReplayExperimentTest,IdpProtocolServiceTest,IdpBrowserSessionServiceTest,IdpJwtIssuerTest,IdpRuntimePersistenceIntegrationTest,IdpInteractionPersistenceIntegrationTest,TenantIsolationSchemaIntegrationTest,PersistenceArchitectureTest test
