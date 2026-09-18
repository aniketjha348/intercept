# INTERCEPT on AWS — one-time setup, then everything is automatic

You push → APK builds, backend deploys, website updates. Users get in-app updates.

## 0. GitHub Secrets (repo → Settings → Secrets → Actions)

| Secret | Value |
|---|---|
| `AWS_REGION` | e.g. `ap-south-1` (Mumbai — closest to Indian users) |
| `AWS_ROLE_ARN` | IAM role for GitHub OIDC (see §1) |
| `ECR_REPO` | e.g. `intercept-backend` |
| `API_URL` | App Runner URL, e.g. `https://xxx.ap-south-1.awsapprunner.com` (no trailing slash) |
| `WEBSITE_BUCKET` | e.g. `intercept-website` |
| `CF_DISTRIBUTION_ID` | CloudFront distribution id |

## 1. IAM role for GitHub (no access keys)

- IAM → Identity providers → Add **token.actions.githubusercontent.com**
- Role → trusted entity = Web Identity, provider above, `token.actions.githubusercontent.com:sub` = `repo:YOUR-USER/intercept:*`
- Attach policies: `AmazonEC2ContainerRegistryPowerUser`, plus scoped S3/CloudFront rights for the website bucket.

## 2. Backend → ECR + App Runner (one time)

1. ECR → Create repository `intercept-backend`.
2. App Runner → Create service → **Container registry / ECR** → pick `intercept-backend:latest` → enable **automatic deployments**.
3. Port `8000`, Health check path `/health`.
4. Environment variables:
   - `DATABASE_URL` = Neon string (from local `.env`, never git)
   - `GOOGLE_API_KEY` = Gemini key
   - `LLM_MODEL` = `gemini-2.5-flash-lite`
   - `APK_URL` = `https://github.com/YOUR-USER/intercept/releases/latest/download/app-debug.apk`
5. Create → copy the service URL → set as `API_URL` secret. Done — every backend push redeploys.

## 3. Website → S3 + CloudFront (one time)

1. S3 → bucket `intercept-website`, Block Public Access **ON**, static hosting **OFF** (CloudFront serves it).
2. CloudFront → Origin = the S3 bucket (OAC), Default root object `index.html`, error pages 404 → `/index.html` (optional).
3. Set `WEBSITE_BUCKET` + `CF_DISTRIBUTION_ID` secrets. Every website push syncs + invalidates.

## 4. Release flow (your weekly loop)

```powershell
git tag v0.3.0; git push origin v0.3.0
```

1. `build-apk` workflow builds + creates a **GitHub Release with the APK**.
2. `APK_URL` (`.../releases/latest/download/...`) now serves the new APK automatically.
3. Add entry to `intercept-backend/app/updates.json` → push → backend redeploys.
4. Phone apps show the update dialog; website download + changelog update themselves.

No Studio. No manual uploads. One tag runs the whole startup.
