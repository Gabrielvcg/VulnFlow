.PHONY: up down logs test verify demo terraform-format terraform-validate

up:
	docker compose up --build -d

down:
	docker compose down

logs:
	docker compose logs -f backend

test:
	cd backend && ./mvnw test

verify:
	cd backend && ./mvnw verify

demo:
	sh scripts/demo.sh

terraform-format:
	docker run --rm -v "$(CURDIR)/infrastructure/aws:/workspace" -w /workspace hashicorp/terraform:1.15.8 fmt -check -recursive
	docker run --rm -v "$(CURDIR)/infrastructure/bootstrap:/workspace" -w /workspace hashicorp/terraform:1.15.8 fmt -check

terraform-validate:
	docker run --rm -v "$(CURDIR)/infrastructure/aws:/workspace" -w /workspace hashicorp/terraform:1.15.8 init -backend=false -input=false -lockfile=readonly
	docker run --rm -v "$(CURDIR)/infrastructure/aws:/workspace" -w /workspace hashicorp/terraform:1.15.8 validate
	docker run --rm -v "$(CURDIR)/infrastructure/bootstrap:/workspace" -w /workspace hashicorp/terraform:1.15.8 init -backend=false -input=false -lockfile=readonly
	docker run --rm -v "$(CURDIR)/infrastructure/bootstrap:/workspace" -w /workspace hashicorp/terraform:1.15.8 validate
