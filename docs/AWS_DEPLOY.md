# INTERCEPT on AWS — infra as code + CI/CD (no click-ops)

Push → built → deployed → verified. Three CloudFormation stacks (in `infra/`),
three GitHub workflows (in `.github/workflows`). You run the stacks **once**;
every push after that deploys itself.

```
push to main ─┬─ intercept-backend/** ─→ ECR push ─→ App Runner auto-deploy ─→ smoke test ✅/❌
              ├─ intercept-website/** ─→ S3 sync ─→ CloudFront invalidation
              └─ intercept-android/** or tag v* ─→ debug APK (+ AAB) ─→ GitHub Release on tags
```

## 0. One-time: AWS CLI + region

```powershell
aws configure  # your IAM user keys (only used from this laptop, never in CI)
$REGION = "ap-south-1"  # Mumbai — closest to Indian users
```

## 1. One-time: deploy the 3 stacks (order matters)

```powershell
# 1/3 — deploy role for GitHub (OIDC, zero long-lived keys)
aws cloudformation deploy --stack-name intercept-github-oidc --region $REGION `
  --template-file infra/github-oidc.yml --capabilities CAPABILITY_NAMED_IAM `
  --parameter-overrides GitHubRepo=aniketjha348/intercept WebsiteBucket=intercept-website

# 2/3 — backend (ECR + App Runner, auto-deploy ON, /health gate)
aws cloudformation deploy --stack-name intercept-backend --region $REGION `
  --template-file infra/backend.yml --capabilities CAPABILITY_NAMED_IAM `
  --parameter-overrides DatabaseUrl="postgresql://..." GoogleApiKey="AIza..."

# 3/3 — website (private S3 + CloudFront HTTPS)
aws cloudformation deploy --stack-name intercept-website --region us-east-1 `
  --template-file infra/website.yml `
  --parameter-overrides BucketName=intercept-website
```

Secrets stay secret: `DatabaseUrl`/`GoogleApiKey` are `NoEcho` parameters — they
never appear in outputs or logs. (Tip: paste them in the App Runner console
afterwards instead of shell history.)

## 2. One-time: GitHub Secrets (repo → Settings → Secrets → Actions)

Get values from: `aws cloudformation describe-stacks --stack-name <name> --query "Stacks[0].Outputs"`.

| Secret | From |
|---|---|
| `AWS_REGION` | `ap-south-1` |
| `AWS_ROLE_ARN` | stack 1/3 → `RoleArn` |
| `ECR_REPO` | `intercept-backend` |
| `API_URL` | stack 2/3 → `ServiceUrl` (no trailing slash) |
| `WEBSITE_BUCKET` | stack 3/3 → `BucketName` |
| `CF_DISTRIBUTION_ID` | stack 3/3 → `DistributionId` |

Backend `APK_URL` env comes from the stack parameter (defaults to the GitHub
`latest` release download — update it if you rename releases).

## 3. Everyday: just push

```powershell
git push origin main            # backend/website auto-deploy, smoke test gates backend
git tag v0.3.0; git push origin v0.3.0   # + public GitHub Release with the APK/AAB
```

- Backend pipeline **fails red** if `$API_URL/health` isn't `ok` within ~10 min
  (App Runner still rolls; the red build tells you to look).
- First-ever deploy: smoke job skips itself until `API_URL` is set.
- ECR keeps the last 10 images (rollback = redeploy any tag in console).
- Website serves from CloudFront (`CdnDomain` output) — share that URL with users.

## 4. Operate

- Logs: App Runner console → Logs (no SSH needed).
- Env change (rotate key, point new DB): App Runner console → Configuration →
  Environment variables → redeploys automatically. Or re-run stack 2/3 command.
- Rollback backend: ECR console → pick previous `:sha` image → App Runner deploy.
- Costs (rough, Mumbai): App Runner ~$7/mo always-on 1vCPU/2GB + ECR pennies +
  S3/CloudFront nearly free at this scale. Free-tier alternative stays Render.
