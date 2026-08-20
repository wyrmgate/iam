.PHONY: help dev-init dev-up dev-down dev-reset dev-logs dev-status server-build server-test server-run console-install console-build console-typecheck console-dev build test

COMPOSE_FILE := deploy/compose/local.yml
LOCAL_ENV := deploy/config/local.env
LOCAL_ENV_EXAMPLE := deploy/config/local.env.example
MAVEN := ./apps/server/mvnw

help:
	@printf '%s\n' \
		'Wyrmgate IAM engineering commands' \
		'' \
		'Local infrastructure:' \
		'  make dev-init           Create local env file if missing' \
		'  make dev-up             Start PostgreSQL, Valkey, Mailpit, and OTel' \
		'  make dev-down           Stop local infrastructure' \
		'  make dev-reset          Destroy local data and recreate infrastructure' \
		'  make dev-logs           Follow local infrastructure logs' \
		'  make dev-status         Show local infrastructure status' \
		'' \
		'Applications:' \
		'  make server-build       Build the Spring Boot server' \
		'  make server-test        Run server unit/architecture tests' \
		'  make server-run         Run the server against local PostgreSQL' \
		'  make console-install    Install console dependencies' \
		'  make console-build      Typecheck and build the React console' \
		'  make console-typecheck  Typecheck the React console' \
		'  make console-dev        Run the Vite development server' \
		'  make build              Build server and console' \
		'  make test               Run currently available tests/checks'

dev-init:
	@test -f $(LOCAL_ENV) || cp $(LOCAL_ENV_EXAMPLE) $(LOCAL_ENV)
	@printf '%s\n' 'Local config ready: $(LOCAL_ENV)'

dev-up: dev-init
	docker compose --env-file $(LOCAL_ENV) -f $(COMPOSE_FILE) up -d --wait

dev-down:
	@test -f $(LOCAL_ENV) || cp $(LOCAL_ENV_EXAMPLE) $(LOCAL_ENV)
	docker compose --env-file $(LOCAL_ENV) -f $(COMPOSE_FILE) down

dev-reset:
	@test -f $(LOCAL_ENV) || cp $(LOCAL_ENV_EXAMPLE) $(LOCAL_ENV)
	docker compose --env-file $(LOCAL_ENV) -f $(COMPOSE_FILE) down -v --remove-orphans
	docker compose --env-file $(LOCAL_ENV) -f $(COMPOSE_FILE) up -d --wait

dev-logs:
	@test -f $(LOCAL_ENV) || cp $(LOCAL_ENV_EXAMPLE) $(LOCAL_ENV)
	docker compose --env-file $(LOCAL_ENV) -f $(COMPOSE_FILE) logs -f

dev-status:
	@test -f $(LOCAL_ENV) || cp $(LOCAL_ENV_EXAMPLE) $(LOCAL_ENV)
	docker compose --env-file $(LOCAL_ENV) -f $(COMPOSE_FILE) ps

server-build:
	$(MAVEN) -f apps/server/pom.xml clean verify

server-test:
	$(MAVEN) -f apps/server/pom.xml test

server-run: dev-up
	set -a; . $(LOCAL_ENV); set +a; $(MAVEN) -f apps/server/pom.xml spring-boot:run

console-install:
	npm --prefix apps/console install

console-build:
	npm --prefix apps/console run build

console-typecheck:
	npm --prefix apps/console run typecheck

console-dev:
	npm --prefix apps/console run dev

build: server-build console-build

test: server-test console-typecheck
