.PHONY: up down build test demo logs

up:
	docker compose up --build -d

down:
	docker compose down -v

build:
	docker compose build

test:
	mvn test

demo:
	./scripts/sample_requests.sh

logs:
	docker compose logs -f api
