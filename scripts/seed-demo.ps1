<#
.SYNOPSIS
    Seeds the ecommerce-api demo storefront with a demo user, an admin user, and a small catalog.

.DESCRIPTION
    Idempotent: safe to run multiple times against the same running stack. It will:
      1. Register (or, if already registered, log in as) demo@example.com — a plain USER account
         for browsing/adding to cart/checkout.
      2. Register (or log in as) admin@example.com, then promote it to ADMIN directly in Postgres —
         there is no API path to create an ADMIN account (User.role defaults to "USER" and nothing
         in AuthService ever sets it otherwise), so this is the honest way to get one for a local
         demo. Requires `docker compose up -d` (or at least the `db` service) to already be running.
      3. As the (now-)admin, creates 1-2 categories and ~6 products. Both catalog endpoints reject
         duplicates with 409 (slug for categories, SKU for products) — those 409s are treated as
         "already seeded" and skipped, not errors.

    Demo credentials are intentionally plaintext here — this seeds a local/demo database only.

.PARAMETER BaseUrl
    Base URL of the running app. Defaults to http://localhost:8080.

.PARAMETER DbService
    Name of the Postgres service in docker-compose.yml. Defaults to "db".
#>
param(
    [string]$BaseUrl = "http://localhost:8080",
    [string]$DbService = "db"
)

$ApiBase = "$BaseUrl/api"

$DemoEmail = "demo@example.com"
$DemoPassword = "DemoPass123!"
$AdminEmail = "admin@example.com"
$AdminPassword = "AdminPass123!"

function Write-Step($msg) { Write-Host "==> $msg" -ForegroundColor Cyan }
function Write-Info($msg) { Write-Host "    $msg" -ForegroundColor Gray }
function Write-Warn($msg) { Write-Host "    $msg" -ForegroundColor Yellow }

function Invoke-Api {
    param(
        [Parameter(Mandatory)][string]$Method,
        [Parameter(Mandatory)][string]$Path,
        $Body = $null,
        [string]$Token = $null
    )
    $headers = @{}
    if ($Token) { $headers["Authorization"] = "Bearer $Token" }
    $uri = "$ApiBase$Path"
    try {
        if ($null -ne $Body) {
            $json = $Body | ConvertTo-Json -Depth 6
            $data = Invoke-RestMethod -Method $Method -Uri $uri -Headers $headers -ContentType "application/json" -Body $json
        } else {
            $data = Invoke-RestMethod -Method $Method -Uri $uri -Headers $headers
        }
        return [PSCustomObject]@{ Ok = $true; StatusCode = 200; Data = $data; Code = $null; Message = $null }
    } catch {
        $statusCode = $null
        if ($_.Exception.Response) { $statusCode = [int]$_.Exception.Response.StatusCode }
        $errBody = $null
        if ($_.ErrorDetails -and $_.ErrorDetails.Message) {
            try { $errBody = $_.ErrorDetails.Message | ConvertFrom-Json } catch { }
        }
        return [PSCustomObject]@{
            Ok         = $false
            StatusCode = $statusCode
            Data       = $null
            Code       = if ($errBody) { $errBody.code } else { $null }
            Message    = if ($errBody) { $errBody.message } else { $_.Exception.Message }
        }
    }
}

function Get-EnvValue {
    param([string]$Name, [string]$Default)
    if (Test-Path ".env") {
        $line = Get-Content ".env" | Where-Object { $_ -match "^\s*$Name\s*=" } | Select-Object -First 1
        if ($line) { return ($line -split "=", 2)[1].Trim() }
    }
    return $Default
}

function Register-OrLogIn {
    param([string]$Email, [string]$Password, [string]$FirstName, [string]$LastName)
    $reg = Invoke-Api -Method POST -Path "/auth/register" -Body @{
        email     = $Email
        password  = $Password
        firstName = $FirstName
        lastName  = $LastName
    }
    if ($reg.Ok) {
        Write-Info "Registered $Email"
        return $reg.Data.token
    }
    if ($reg.StatusCode -eq 409) {
        Write-Info "$Email already registered, logging in instead"
        $login = Invoke-Api -Method POST -Path "/auth/login" -Body @{ email = $Email; password = $Password }
        if ($login.Ok) { return $login.Data.token }
        throw "Login failed for $Email ($($login.StatusCode)): $($login.Message)"
    }
    throw "Register failed for $Email ($($reg.StatusCode)): $($reg.Message)"
}

function Ensure-AdminRole {
    param([string]$Email)
    $pgUser = Get-EnvValue -Name "POSTGRES_USER" -Default "postgres"
    $pgDb = Get-EnvValue -Name "POSTGRES_DB" -Default "ecommerce"
    $sql = "UPDATE users SET role='ADMIN' WHERE email='$Email';"
    Write-Info "Promoting $Email to ADMIN via 'docker compose exec $DbService psql' (no API path exists for this)"
    docker compose exec -T $DbService psql -U $pgUser -d $pgDb -c $sql | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw "Could not promote $Email to ADMIN — is 'docker compose up -d' running with the '$DbService' service healthy?"
    }
}

function Ensure-Category {
    param([string]$Token, [hashtable]$Category)
    $res = Invoke-Api -Method POST -Path "/categories" -Token $Token -Body @{
        name        = $Category.name
        slug        = $Category.slug
        description = $Category.description
    }
    if ($res.Ok) {
        Write-Info "Created category '$($Category.name)'"
        return $res.Data.id
    }
    if ($res.StatusCode -eq 409) {
        Write-Info "Category '$($Category.name)' already seeded, reusing"
        $all = Invoke-Api -Method GET -Path "/categories"
        $existing = $all.Data | Where-Object { $_.slug -eq $Category.slug } | Select-Object -First 1
        if ($existing) { return $existing.id }
        throw "Category '$($Category.name)' conflicted (409) but could not be found by slug afterwards"
    }
    throw "Failed to create category '$($Category.name)' ($($res.StatusCode)): $($res.Message)"
}

function Ensure-Product {
    param([string]$Token, [hashtable]$Product, [long]$CategoryId)
    $res = Invoke-Api -Method POST -Path "/products" -Token $Token -Body @{
        name          = $Product.name
        description   = $Product.description
        price         = $Product.price
        sku           = $Product.sku
        stockQuantity = $Product.stock
        categoryId    = $CategoryId
    }
    if ($res.Ok) {
        Write-Info "Created product '$($Product.name)' [$($Product.sku)]"
        return
    }
    if ($res.StatusCode -eq 409) {
        Write-Info "Product [$($Product.sku)] already seeded, skipping"
        return
    }
    throw "Failed to create product '$($Product.name)' ($($res.StatusCode)): $($res.Message)"
}

# --- 0. Sanity check the app is up ---
Write-Step "Checking $BaseUrl is reachable"
$ping = Invoke-Api -Method GET -Path "/products"
if (-not $ping.Ok) {
    throw "Could not reach $ApiBase/products ($($ping.StatusCode)): $($ping.Message). Is 'docker compose up -d' running?"
}
Write-Info "App is up"

# --- 1. Demo user (plain USER) ---
Write-Step "Seeding demo user"
Register-OrLogIn -Email $DemoEmail -Password $DemoPassword -FirstName "Demo" -LastName "Shopper" | Out-Null
Write-Info "$DemoEmail / $DemoPassword ready"

# --- 2. Admin user, promoted directly in Postgres ---
Write-Step "Seeding admin user"
$adminToken = Register-OrLogIn -Email $AdminEmail -Password $AdminPassword -FirstName "Demo" -LastName "Admin"
Ensure-AdminRole -Email $AdminEmail
Write-Info "$AdminEmail / $AdminPassword ready (ADMIN)"

# --- 3. Catalog: categories + products ---
Write-Step "Seeding catalog"
$categories = @(
    @{ name = "Home & Kitchen"; slug = "home-kitchen"; description = "Everyday essentials for the home." }
    @{ name = "Outdoors & Camping"; slug = "outdoors-camping"; description = "Gear for getting outside." }
)
$categoryIds = @{}
foreach ($cat in $categories) {
    $categoryIds[$cat.slug] = Ensure-Category -Token $adminToken -Category $cat
}

$products = @(
    @{ name = "Ceramic Pour-Over Coffee Dripper"; sku = "ANT-COF-001"; price = 28.00; stock = 42; category = "home-kitchen"; description = "Hand-glazed stoneware dripper for a clean, slow cup." }
    @{ name = "Cast Iron Skillet, 10 inch"; sku = "ANT-SKI-010"; price = 45.00; stock = 15; category = "home-kitchen"; description = "Pre-seasoned cast iron that goes from stovetop to oven." }
    @{ name = "Linen Kitchen Towel Set (3-pack)"; sku = "ANT-TOW-003"; price = 22.00; stock = 60; category = "home-kitchen"; description = "Softens with every wash; generously sized for real cleanup." }
    @{ name = "Insulated Steel Camp Mug"; sku = "ANT-MUG-016"; price = 18.50; stock = 75; category = "outdoors-camping"; description = "Double-walled stainless mug that keeps coffee hot on the trail." }
    @{ name = "Packable Rain Shell Jacket"; sku = "ANT-JKT-022"; price = 89.00; stock = 20; category = "outdoors-camping"; description = "Folds into its own pocket; keeps you dry without the bulk." }
    @{ name = "Compact Camp Stove"; sku = "ANT-STV-005"; price = 64.00; stock = 12; category = "outdoors-camping"; description = "One-burner stove that boils a liter of water in under 4 minutes." }
)
foreach ($p in $products) {
    Ensure-Product -Token $adminToken -Product $p -CategoryId $categoryIds[$p.category]
}

Write-Step "Done"
Write-Host ""
Write-Host "Demo storefront ready at $BaseUrl" -ForegroundColor Green
Write-Host "  Shopper login: $DemoEmail / $DemoPassword"
Write-Host "  Admin login:   $AdminEmail / $AdminPassword"
