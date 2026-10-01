# 08 — Scaling & AWS Deployment

## Capacity math (from the guide's estimates)

- 50M tickets/year ≈ ~1.6 tickets/s average; on-sale peak modeled at **10k reads/s + 2k writes/s**.
- Reads (catalog/search): CDN + Redis caches → DB sees a small fraction. Stateless services scale
  horizontally; autoscale on CPU 60%, min = desired, max = 3× desired.
- Writes: the funnel is deliberate — waiting room admits ≤ N/s per hot event; Redis holds kill
  losers pre-DB; Postgres claims are single-row updates. A single db.r6g.large comfortably
  handles tens of thousands of row-lock updates/min.
- Storage: 20k seats × ~200 events/day ≈ 4M ticket rows/day churn-free (rows are reused per
  event lifecycle); partition `tickets` by event_id range if it ever exceeds low billions.

## AWS mapping (`infra/terraform`)

| Component | Service | Notes |
|---|---|---|
| Edge/SPA | **CloudFront** + S3 (OAC) | `/api/*` forwarded to ALB uncached; SPA cached |
| API edge | **ALB** (public) | path rules mirror nginx template; idle timeout 3600 s for SSE |
| Compute | **ECS Fargate**, one service per module | task health check `/actuator/health`; rolling deploys |
| Autoscaling | App Auto Scaling target-tracking CPU 60% | |
| Relational | **RDS PostgreSQL 16**, Multi-AZ, gp3, secrets in Secrets Manager | credentials injected via task `secretsFrom` |
| Cache/queue | **ElastiCache Redis 7.1** | dev: single node; prod: cluster mode / replica + failover |
| Search | **OpenSearch 2.13** (VPC, t3.small.search dev) | SG-restricted to services |
| Secrets | Secrets Manager | JSON secret `{username,password}`; services read via ECS exec role |

Networking (`modules/network`): VPC /16, 2 AZs, public ALB subnets + private service subnets,
one NAT gateway (dev), SG chain: internet → ALB SG → services SG (+ self for internal calls).

### Deploy flow

```bash
cd infra/terraform
terraform init && terraform apply -var-file=envs/dev.tfvars   # provisions everything
aws ecr get-login-password | docker login …                   # then per service:
docker buildx build --platform linux/amd64 --build-arg MODULE=event-service \
  -f infra/docker/service.Dockerfile -t <ecr-url>:latest .
aws ecs update-service --cluster tocketmaster --force-new-deployment …
# frontend: aws s3 sync frontend/dist s3://<bucket>/
```

Terraform is authored but not applied here (no terraform/aws CLI installed); `terraform init &&
validate` after installing the toolchain is the first verification step.

## What prod would add next

RDS read replicas + PgBouncer, Redis replication groups with automatic failover, multi-AZ
OpenSearch with zone awareness, WAF rate rules at CloudFront, OIDC auth replacing the
`X-User-Id` header, real PSP webhooks signed and verified, blue/green via CodeDeploy.
