.PHONY: help server-build server-test server-run console-install console-build console-typecheck console-dev build test

help:
	@printf '%s\n' \
		'Wyrmgate IAM engineering commands' \
		'' \
		'  make server-build       Build the Spring Boot server' \
		'  make server-test        Run server unit/architecture tests' \
		'  make server-run         Run the server (requires PostgreSQL)' \
		'  make console-install    Install console dependencies' \
		'  make console-build      Typecheck and build the React console' \
		'  make console-typecheck  Typecheck the React console' \
		'  make console-dev        Run the Vite development server' \
		'  make build              Build server and console' \
		'  make test               Run currently available tests/checks'

server-build:
	gradle -p apps/server clean build

server-test:
	gradle -p apps/server test

server-run:
	gradle -p apps/server bootRun

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
