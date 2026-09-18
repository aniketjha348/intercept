# INTERCEPT on AWS — Terraform + ECS + GitHub Actions (the real pipeline)

```
push main ─┬─ backend/** ─→ docker build → ECR push → ECS rolling deploy → smoke ✅/❌
           ├─ website/** ─→ S3 sync (＋ CDN purge when enabled)
           └─ android/** or tag v* ─→ debug APK (+ AAB) ─→ GitHub Release on tags
```

Infra lives in `terraform/backend-app` (VPC → ALB → ECS Fargate → ECR,
Secrets Manager, OIDC deploy role, CloudWatch logs). The old `infra/*.yml`
CloudFormation files remain only as fallback.

## 0. Laptop setup (once)

```powershell
pip install awscli            # or the v2 MSI
aws configure                 # IAM user keys — local only, never in CI (CI uses OIDC)
$REGION = "ap-south-1"
```

## 1. Remote state bucket (once)

```powershell
aws s3 mb s3://intercept-tfstate-<account-id> --region $REGION
aws s3api put-bucket-versioning --bucket intercept-tfstate-<account-id> `
  --versioning-configuration Status=Enabled --region $REGION
```

## 2. Deploy (from `terraform/backend-app`)

```powershell
terraform init -backend-config="bucket=intercept-tfstate-<account-id>" `
  -backend-config="key=backend-app/terraform.tfstate" `
  -backend-config="region=ap-south-1"
# First time only, if the ECR repo already exists (CloudFormation era):
terraform import aws_ecr_repository.app intercept-backend
# Secrets come from your local .env (masked in output, encrypted in state):
terraform apply -var "db_url=..." -var "google_key=..."
terraform output    # → api_url, ecr_uri, deploy_role_arn
```

## 3. GitHub Secrets (repo → Settings → Secrets → Actions)

| Secret | Value |
|---|---|
| `AWS_REGION` | `ap-south-1` |
| `AWS_ROLE_ARN` | `terraform output -raw deploy_role_arn` |
| `AWS_ACCOUNT_ID` | your 12-digit account id |
| `ECR_REPO` | `intercept-backend` |
| `API_URL` | `terraform output -raw api_url` (no trailing slash) |
| `WEBSITE_BUCKET` | `intercept-website` |
| `CF_DISTRIBUTION_ID` | empty until the CDN is enabled |

## 4. Everyday

```powershell
git push origin main                 # deploys itself, smoke-gated
```

## 4b. Shipping an app update (website + in-app updater move together)

One release = 4 edits, same numbers everywhere, then tag. Miss one and users
either never see the update or download a dead link:

1. `intercept-android/app/build.gradle.kts` → bump `versionCode` (+1) + `versionName`.
2. `intercept-backend/app/updates.json` → append entry (same code/name/notes, no `apk_url` key — the server injects it).
3. `intercept-website/updates.json` → same entry + `"apk_url": "https://github.com/aniketjha348/intercept/releases/latest/download/app-debug.apk"`.
4. Commit + push, then:
```powershell
git tag v0.3.0; git push origin v0.3.0   # public APK/AAB Release; `latest` moves itself
```

Check after: live `/app/latest` shows the new code, the website download card
shows the new name, and an old install pops the update dialog.

Backend secrets rotate without rebuilds: update the Secrets Manager value →
push anything (or run the workflow) → fresh tasks pick it up.

## 5. Graduate (later, in order)

1. **HTTPS**: add a domain → ACM cert → `:443` listener + `:80` redirect (ALB swap only).
2. **Private subnets + NAT** (~$32/mo) instead of public-subnet tasks.
3. **CloudFront** for the website (`EnableCdn=true` path) + `CF_DISTRIBUTION_ID`.
4. **App Runner** (`infra/backend.yml`) only if you want zero-VPC simplicity back.

## Costs now (Mumbai, pilot)

Fargate 0.25vCPU/0.5GB 1 task ~$9/mo + ALB ~$17/mo + ECR/S3/logs pennies.
EC2 pilot (if still up) should be deleted after cutover — two backends = double bill.
Before deployment, verify that all required environment variables and configuration settings are properly configured.
