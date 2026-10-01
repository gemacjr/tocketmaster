# TocketMaster developer entrypoints.
#
# Requires: Docker Desktop, JDK 17 (Corretto), Node 20+.

JAVA_HOME := $(shell /usr/libexec/java_home -v 17)
export JAVA_HOME

.PHONY: help infra up down build test run-event run-booking run-search run-queue run-all \
        frontend-install frontend-dev reindex smoke containers-build containers-up down-all

help:
	@echo "  make infra            start postgres + redis + opensearch + nginx edge"
	@echo "  make run-event        run event-service on :18081 (host)"
	@echo "  make run-search       run search-service on :18082 (host)"
	@echo "  make run-booking      run booking-service on :18083 (host)"
	@echo "  make run-queue        run queue-service on :18084 (host)"
	@echo "  make frontend-dev     vite dev server on :5173 (proxies to services)"
	@echo "  make reindex          rebuild the OpenSearch index from Postgres"
	@echo "  make smoke            end-to-end API smoke test against localhost:18080"
	@echo "  make build            compile all modules"
	@echo "  make test             unit + Testcontainers concurrency tests"
	@echo "  make containers-up    fully containerized stack on :18080"
	@echo "  make down-all         stop everything"

infra:
	cd infra && docker compose up -d postgres redis opensearch nginx

run-event:
	./gradlew :services:event-service:bootRun

run-search:
	./gradlew :services:search-service:bootRun

run-booking:
	./gradlew :services:booking-service:bootRun

run-queue:
	./gradlew :services:queue-service:bootRun

# start all four services in the background; logs in .run/
run-all:
	@mkdir -p .run
	@for svc in event search booking queue; do \
	  (./gradlew :services:$${svc}-service:bootRun > .run/$$svc.log 2>&1 &); \
	done
	@echo "starting event/search/booking/queue — logs in .run/*.log"

frontend-install:
	cd frontend && npm install

frontend-dev:
	cd frontend && npm run dev

reindex:
	curl -s -X POST http://localhost:18082/api/admin/search/reindex; echo

build:
	./gradlew build -x test

test:
	./gradlew test

containers-up:
	docker compose --profile containers up -d --build

containers-build:
	docker compose --profile containers build

down:
	cd infra && docker compose down

down-all:
	cd infra && docker compose --profile containers down -v

smoke:
	bash infra/smoke.sh
