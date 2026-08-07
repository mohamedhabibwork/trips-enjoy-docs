# payment-service — Gateway Registry

> **Source of truth** for the 46 payment gateways supported by
> `payment-service`. Every other doc in this folder (README, BRD,
> SRS, ERD, INTEGRATION, WORKFLOWS, TECH) refers to this file as
> the single canonical list of `gateway_id`s.
>
> Each row corresponds to one `payment_gateways` catalog row in
> [`ERD.md`](./ERD.md) 3 and one driver package under
> `internal/payment/drivers/<gateway_id>/` in [`TECH.md`](./TECH.md) 5.
>
> The reference implementation is the upstream Laravel package
> [`Nafezly/payments`](https://github.com/Nafezly/payments) — this
> service ports every one of its 46 gateway classes (see
> `Nafezly\Payments\Classes\*Payment`) into the platform's
> driver model. The factory key for each gateway (the string
> passed to `PaymentFactory::get(...)`) is computed by appending
> `Payment` to the gateway's logical name; the platform's
> `gateway_id` is the same logical name in `snake_case`.

## 1. Column legend

| Column | Meaning |
|---|---|
| `gateway_id` | Stable identifier used in `payment_gateways.id`, in every event `data.gateway_id`, and in `payment_intents.gateway_id`. |
| `class_fqn` | Fully-qualified class name in `Nafezly\Payments\Classes\` (the upstream port-target). |
| `factory_key` | The string passed to `PaymentFactory::get($key)` upstream; equals the PascalCase form of `gateway_id` for most rows. |
| `family` | One of `card`, `mena_wallet`, `mena_aggregator`, `crypto`, `e_currency`, `direct_card_3ds`, `payout`, `latam`, `apac`, `local_apm`. |
| `mode` | How the gateway presents the payment: `hosted_redirect` (customer is sent to a hosted page), `iframe` (platform loads an iframe), `direct_api` (platform calls the gateway API and tokenises), `crypto` (hosted crypto pay URL or address), `html_auto_submit` (platform renders an auto-submitting form). |
| `required_config_keys` | Config keys from `payment.gateway.<id>.*` in `configuration-service` (see [`README.md` 13](./README.md#13-configuration)). |
| `supported_methods` | Driver capabilities — `pay`, `verify`, `refund`, `payout`. |
| `default_currency` | ISO 4217 default when caller does not supply one. |
| `region_bias` | Region(s) where the gateway is the primary choice. |
| `signature_scheme` | Per-gateway webhook / callback signing — one of `hmac_sha256`, `hmac_sha512`, `rsa_sha256`, `md5`, `sha256`, `paypal_sdk`, `none`, `paymob_hmac`, `kashier_hmac`. The driver's `verify` method uses this scheme verbatim. |
| `verify_style` | Where the verification comes from: `get_redirect` (browser-return GET), `webhook_post` (server-to-server POST), `signed_webhook` (HMAC-verified POST), `cache_lookup` (server-side cache of `payment_id ↔ gateway_id`), `iframe_postback` (form post from iframe). |
| `status_string` | The exact success status convention emitted by the gateway that the driver must translate into the platform's `state=captured` / `state=authorized` / `state=refunded`. Listed here because 46 gateways use 46 different conventions. |
| `quirks` | Per-gateway gotchas — disabled methods, hardcoded fields, version constraints, default values that must be overridden. |

## 2. Global cards / wallets

These gateways accept international cards and are usable in any region.

| gateway_id | class_fqn | factory_key | family | mode | required_config_keys | methods | default_currency | region_bias | signature_scheme | verify_style | status_string | quirks |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| `stripe` | `Nafezly\Payments\Classes\StripePayment` | `Stripe` | card | direct_api | `secret_key`, `public_key`, `webhook_secret` | pay, verify, refund, payout | `usd` | global | `hmac_sha256` | signed_webhook | `succeeded` | Webhook secret declared but unused in upstream; driver performs GET-by-id fallback. Returns Stripe Elements HTML. |
| `paypal` | `Nafezly\Payments\Classes\PayPalPayment` | `PayPal` | card | hosted_redirect | `client_id`, `secret`, `mode` (`sandbox`/`live`) | pay, verify, refund, payout | `USD` | global | `paypal_sdk` | get_redirect | `COMPLETED` + `statusCode==201` | Uses official `paypal/paypal-checkout-sdk`. Locale `ar`→`ar-SA`, else `en-US`. |
| `paypal_credit` | `Nafezly\Payments\Classes\PayPalCreditPayment` | `PayPalCredit` | card | direct_api | `client_id`, `secret`, `mode`, `currency` | pay, verify | `USD` | global (US underwriting) | `paypal_sdk` | get_redirect | `COMPLETED` | Raw cURL, no SDK. Requires `birth_date` from caller; uses `ip-api.com` + `api.ipify.org` for geo. Truncates inputs to 230 chars. |
| `myfatoorah` | `Nafezly\Payments\Classes\MyFatoorahPayment` | `MyFatoorah` | mena_aggregator | hosted_redirect | `api_key`, `base_url`, `currency` | pay, verify, refund | `USD` | KWT / MENA | `hmac_sha256` (callback cache) | cache_lookup | `InvoiceStatus=='Paid'` | Phone truncated to last 11 chars; language uppercased AR/EN. |
| `tap` | `Nafezly\Payments\Classes\TapPayment` | `Tap` | mena_aggregator | hosted_redirect | `secret_key`, `public_key`, `currency`, `lang_code` | pay, verify, refund | `USD` | MENA (KWT-led) | `hmac_sha256` (Bearer) | cache_lookup | `CAPTURED` | Returns `redirect_url` + `process_data` + `html`. |
| `payrexx` | `Nafezly\Payments\Classes\PayrexxPayment` | `Payrexx` | local_apm | hosted_redirect | `instance_name`, `api_key` | pay, verify | none | EU (Swiss-led) | `hmac_sha256` (base64) | signed_webhook | `ApiSignature` present | **Upstream verify() hits the Tap endpoint — copy/paste bug.** Disabled by default until a working verify is provided; see 5. |
| `payop` | `Nafezly\Payments\Classes\PayopPayment` | `Payop` | local_apm | hosted_redirect | `public_key`, `secret_key`, `jwt` | pay, verify | `USD` | global APM aggregator | `sha256` (sha256 of `amount:currency:orderId:secretKey`) | cache_lookup | `success` / `paid` | Caches `PAYOP_{payment_id}` → invoice id. |
| `wise` | `Nafezly\Payments\Classes\WisePayment` | `Wise` | payout | hosted_redirect | `api_key`, `balance_id`, `profile_id` | pay, verify, payout | `USD` | global cross-border | `none` (browser headers) | get_redirect | subtitle contains `Paid`, badge `POSITIVE` | Spoofs browser headers (`X-Access-Token: Tr4n5f3rw153`, Firefox UA). Helper `payments($type)` lists summaries. |

## 3. MENA wallets and aggregators

Gateways with primary market in the Middle East / North Africa.

| gateway_id | class_fqn | factory_key | family | mode | required_config_keys | methods | default_currency | region_bias | signature_scheme | verify_style | status_string | quirks |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| `kashier` | `Nafezly\Payments\Classes\KashierPayment` | `Kashier` | mena_aggregator | iframe / hosted_redirect | `account_key`, `iframe_key`, `token`, `url`, `mode`, `currency`, `webhook_url` | pay, verify | `EGP` | EG | `kashier_hmac` | signed_webhook | `paymentStatus=='SUCCESS'` and HMAC match | Toggle `setHostedPayment(true)` for redirect URL; otherwise iframe HTML. |
| `paytabs` | `Nafezly\Payments\Classes\PaytabsPayment` | `Paytabs` | mena_aggregator | hosted_redirect | `profile_id`, `server_key`, `base_url`, `checkout_lang`, `currency` | pay, verify | `EGP` | EG | `hmac_sha256` (server key as header) | signed_webhook | `response_status=='A'` | Customer street hardcoded as `Not Available Data`, zip `00000` — caller must override. |
| `telr` | `Nafezly\Payments\Classes\TelrPayment` | `Telr` | mena_aggregator | hosted_redirect | `merchant_id`, `api_key`, `mode` | pay, verify | `SAR` | UAE / KSA | `sha256` | get_redirect | `order.status.text=='Paid'` | Upstream uses `=` (not `==`) in check but the side effect still stores `Paid`. |
| `clickpay` | `Nafezly\Payments\Classes\ClickPayPayment` | `ClickPay` | mena_aggregator | hosted_redirect | `server_key`, `profile_id` | pay, verify | `SAR` | KSA / Gulf | `hmac_sha512` (server key as header) | signed_webhook | `response_status=='A'` | `pay()` references undefined `$uniqid` (bug — driver fixes to `$unique_id`); callback URL downgrades https→http (driver keeps https). |
| `hyperpay` | `Nafezly\Payments\Classes\HyperPayPayment` | `HyperPay` | direct_card_3ds | iframe | `url`, `base_url`, `token`, `currency`, `credit_id`, `mada_id`, `apple_id` | pay, verify | `SAR` | KSA | `hmac_sha256` (Bearer) | iframe_postback | `000.000.000`/`000.100.110`/`000.100.111`/`000.100.112` | 3DS flow handled client-side; entityId selected by `$source` (`CREDIT`/`MADA`/`APPLE`). |
| `paysky` | `Nafezly\Payments\Classes\PaySkyPayment` | `Paysky` | direct_card_3ds | iframe | `mid`, `tid`, `secret` (hex), `mode` | pay, verify | `EGP` | EG | `sha256` (HMAC over sorted query) | signed_webhook | `success` | Server-side SHA-256 HMAC over canonical sorted query; hex-decoded secret. |
| `paymob` | `Nafezly\Payments\Classes\PaymobPayment` | `Paymob` | mena_aggregator | hosted_redirect | `public_key`, `secret_key`, `integration_id` (comma-list), `currency`, `hmac` | pay, verify, refund | `EGP` | EG | `paymob_hmac` | signed_webhook | `success=='true'` | **Upstream `refund()` ends with `dd()` — driver must implement refund directly via Paymob void_refund API** (see 5). |
| `paymob_wallet` | `Nafezly\Payments\Classes\PaymobWalletPayment` | `PaymobWallet` | mena_wallet | hosted_redirect | `api_key`, `wallet_integration_id`, `currency` | pay, verify | `EGP` | EG | `paymob_hmac` | signed_webhook | `success=='true'` | 4-step flow: auth token → order → payment key → wallet pay link. |
| `fawry` | `Nafezly\Payments\Classes\FawryPayment` | `Fawry` | mena_wallet | hosted_redirect | `url`, `merchant`, `secret`, `display_mode`, `pay_mode` | pay, verify | `EGP` | EG | `sha256` | webhook_post | `paymentStatus=='PAID'` | Signature string `merchantCode:merchantRefNum:customerProfileId:itemCode:quantity:amount:secret`. |
| `opay` | `Nafezly\Payments\Classes\OpayPayment` | `Opay` | mena_aggregator | hosted_redirect | `secret_key`, `public_key`, `merchant_id`, `country_code`, `base_url`, `currency` | pay, verify | `EGP` | Africa / MENA | `hmac_sha512` | cache_lookup | `code=='00000'` and `data.status` truthy | Headers: `MerchantId`, `Authorization: Bearer`, JSON. |
| `thawani` | `Nafezly\Payments\Classes\ThawaniPayment` | `Thawani` | mena_wallet | hosted_redirect | `url`, `api_key`, `publishable_key` | pay, verify | `OMR` | Oman | `hmac_sha256` | cache_lookup | session `paid` | **Amount is in baisa (×1000), not cents.** `payment.gateway.thawani.amount_unit='baisa'` set in metadata. |
| `paylink` | `Nafezly\Payments\Classes\PaylinkPayment` | `Paylink` | mena_wallet | hosted_redirect | `api_key`, `app_id`, `mode` | pay, verify | `SAR` | KSA | `hmac_sha256` (Bearer) | get_redirect | `orderStatus=='Paid'` | Phone defaults to `96612345678`; URL switch by `paylink_mode`. |
| `mamo` | `Nafezly\Payments\Classes\MamoPayment` | `Mamo` | mena_wallet | hosted_redirect | `base_url`, `api_key` | pay, verify | `AED` | UAE | `hmac_sha256` (Bearer) | cache_lookup | `charges[0].status=='captured'` | Factory key in upstream is `Mamo` (class is `MamoPayment`). |
| `ziina` | `Nafezly\Payments\Classes\ZiinaPayment` | `Ziina` | mena_wallet | hosted_redirect | `api_key`, `base_url`, `currency`, `test` (bool) | pay, verify | `AED` | UAE | `hmac_sha256` (Bearer) | cache_lookup | `completed` | Intent id cached 24h. |
| `fawaterak` | `Nafezly\Payments\Classes\FawaterakPayment` | `Fawaterak` | mena_aggregator | hosted_redirect | `api_key`, `vendor_key`, `base_url`, `currency`, `webhook_url`, `payment_method_id` | pay, verify | `EGP` | EG | `hmac_sha256` | signed_webhook | `isPaidWebhook` true + `hashKey` match | Optional `invoiceInitPay` when `FAWATERAK_PAYMENT_METHOD_ID` set. |
| `xpay` | `Nafezly\Payments\Classes\XPayPayment` | `XPay` | mena_wallet | hosted_redirect | `api_key`, `community_id`, `variable_amount_id`, `base_url`, `currency` | pay, verify | `EGP` | EG | `hmac_sha256` (x-api-key) | get_redirect | `transaction_status=='SUCCESSFUL'` | |
| `yallapay` | `Nafezly\Payments\Classes\YallaPayPayment` | `YallaPay` | mena_aggregator | hosted_redirect | `public_key`, `secret_key`, `webhook_secret` | pay, verify | `USD` | MENA | `hmac_sha256` (webhook secret) | signed_webhook | `status ∈ {1, 'Paid', 'success'}` | Upstream calls `checkRequiredFields(..., 'OPAY')` — wrong gateway name; driver fixes the label. |
| `korapay` | `Nafezly\Payments\Classes\KoraPayPayment` | `KoraPay` | mena_aggregator | hosted_redirect | `public_key`, `secret_key`, `encryption_key`, `base_url`, `currency`, `webhook_url` | pay, verify, refund | `USD` | Africa (Nigeria-led) | `hmac_sha256` (Bearer) | signed_webhook | `event=='charge.success'` and `data.status=='success'` | Webhook branch + GET status fallback. |
| `bigpay` | `Nafezly\Payments\Classes\BigPayPayment` | `BigPay` | mena_aggregator | hosted_redirect | `key`, `secret`, `mode` | pay, verify | none | KSA | `hmac_sha256` (Basic) | get_redirect | `SUCCESS` / `PAYED` | Mastercard gateway (`bobsal.gateway.mastercard.com`). |
| `paycec` | `Nafezly\Payments\Classes\PaycecPayment` | `Paycec` | mena_aggregator | hosted_redirect | `merchant_username`, `merchant_secret`, `mode` | pay, verify | `USD` | TR | `hmac_sha512` | get_redirect | `isSuccessful==true` | |
| `payzink` | `Nafezly\Payments\Classes\PayzinkPayment` | `Payzink` | mena_aggregator | hosted_redirect | `publishable_key`, `secret_key`, `base_url`, `currency`, `action`, `webhook_url`, `must_3d_secure` | pay, verify | `USD` | MENA | `hmac_sha256` (Bearer) | signed_webhook | `state ∈ {PURCHASED, CAPTURED, AUTHORISED}` | Access token cached 240 min. |
| `payzink_direct` | `Nafezly\Payments\Classes\PayzinkDirectPayment` | `PayzinkDirect` | direct_card_3ds | direct_api | same as `payzink` + `card` details | pay, verify | `USD` | MENA | `hmac_sha256` (Bearer) | signed_webhook | `state ∈ {PURCHASED, CAPTURED, AUTHORISED}` | 3DS challenge via `3dsHref`. |
| `totalpay` | `Nafezly\Payments\Classes\TotalPayPayment` | `TotalPay` | mena_aggregator | hosted_redirect | `api_key`, `hosted_session_api_key`, `outlet_id`, `realm`, `gateway_url`, `paypage_url`, `currency`, `operation`, `must_3ds`, `paypage_slim`, `paypage_language`, `mask_payment_info`, `payment_attempts` | pay, verify | `AED` | UAE / MENA | `hmac_sha256` (Bearer) | signed_webhook | `state ∈ {PURCHASED, CAPTURED, AUTHORISED}` | N-Genius / Network International. 3DS cache 2h, order ref cache 48h. |
| `totalpay_direct` | `Nafezly\Payments\Classes\TotalPayDirectPayment` | `TotalPayDirect` | direct_card_3ds | direct_api | same as `totalpay` + `card` details | pay, verify | `AED` | UAE / MENA | `hmac_sha256` (Bearer) | signed_webhook | `state ∈ {PURCHASED, CAPTURED, AUTHORISED}` | 3DS finalize with `PaRes`. |

## 4. Crypto and e-currency

| gateway_id | class_fqn | factory_key | family | mode | required_config_keys | methods | default_currency | region_bias | signature_scheme | verify_style | status_string | quirks |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| `now_payments` | `Nafezly\Payments\Classes\NowPaymentsPayment` | `NowPayments` | crypto | crypto | `api_key` | pay, verify | `usd` (fiat) | global crypto | `hmac_sha512` (x-api-key) | webhook_post | `finished` | `pay()` returns crypto pay address (acts as redirect). `get_minimum_amount($from, $to, $fiat_equivalent)` is called by the driver before authorize. |
| `now_payments_invoice` | `Nafezly\Payments\Classes\NowPaymentsInvoicePayment` | `NowPaymentsInvoice` | crypto | hosted_redirect | `api_key` | pay, verify | `usd` | global crypto | `hmac_sha512` | webhook_post | `payment_status=='finished'` | Hosted invoice page. |
| `binance` | `Nafezly\Payments\Classes\BinancePayment` | `Binance` | crypto | crypto | `api` (cert SN), `secret` | pay, verify | `BUSD` (hardcoded) | global crypto | `hmac_sha256` (raw payload) | signed_webhook | `completed` | **Currency hardcoded to BUSD** — driver records `payment.gateway.binance.currency_hardcoded='BUSD'` in metadata. |
| `coin_payments` | `Nafezly\Payments\Classes\CoinPaymentsPayment` | `CoinPayments` | crypto | crypto | `public_key`, `private_key` | pay, verify | `USDT` (currency2) | global crypto | `hmac_sha512` (sha512 of body) | cache_lookup | `status==100` and `status_text=='Complete'` | Retries 3×, 100ms. |
| `cryptomus` | `Nafezly\Payments\Classes\CryptomusPayment` | `Cryptomus` | crypto | crypto | `merchant_id`, `api_key` | pay, verify | `USD` | global crypto | `md5` (base64(json) + api_key) | cache_lookup | `result.status ∈ {paid, paid_over}` | `setNetwork($network)` setter. |
| `heleket` | `Nafezly\Payments\Classes\HeleketPayment` | `Heleket` | crypto | crypto | `merchant_id`, `api_key` | pay, verify | `USD` (fallback) | global crypto | `md5` | cache_lookup | `result.status ∈ {paid, paid_over}` | Same `sign` scheme as Cryptomus. |
| `enot` | `Nafezly\Payments\Classes\EnotPayment` | `Enot` | crypto | hosted_redirect | `key`, `secret`, `shop_id` | pay, verify | `USD` | CIS / RU | `none` (x-api-key) | cache_lookup | `data.status=='success'` | |
| `perfect_money` | `Nafezly\Payments\Classes\PerfectMoneyPayment` | `PerfectMoney` | e_currency | html_auto_submit | `id`, `passphrase` | pay, verify | `USD` | global e-currency | `md5` (V2 hash) | signed_webhook | `PAYMENT_BATCH_NUM` + V2_HASH match | V2 hash = MD5 of `PAYMENT_ID:PAYEE_ACCOUNT:AMOUNT:UNITS:BATCH:PAYER:UPPER(MD5(passphrase)):TIMESTAMPGMT`. **CSRF must be exempt on the verify route** (upstream notes this). |
| `volet` | `Nafezly\Payments\Classes\VoletPayment` | `Volet` | e_currency | html_auto_submit | `account_email`, `sci_name`, `sci_password`, `sci_url`, `currency` | pay, verify, payout | `USD` | global e-wallet | `sha256` (`ac_transfer:ac_start_date:ac_sci_name:ac_src_wallet:ac_dest_wallet:ac_order_id:ac_amount:ac_merchant_currency:PASSWORD`) | signed_webhook | `COMPLETED` | Formerly Advcash. |
| `payeer` | `Nafezly\Payments\Classes\PayeerPayment` | `Payeer` | e_currency | hosted_redirect | `api_key`, `additional_api_key`, `merchant_id` | pay, verify, payout | `USD` | CIS / RU | `sha256` (sha256 of `m_operation_id:...:API_KEY`) | signed_webhook | `m_status=='success'` | **Uses mcrypt (PHP 7.1 only — deprecated/removed in 7.2+)**; the platform's Kotlin driver re-implements Rijndael-256 in JVM crypto. IP allowlist is commented out upstream. |

## 5. Disabled / broken gateways

The following gateways are present in the upstream package but ship
with upstream code paths that are non-functional as-is. The driver
package is still present (so the gateway_id is reserved and the
config row is documented) but `state='disabled'` in the catalog.
Operators can re-enable only after the upstream issue is patched
or the platform's driver implements the missing path.

| gateway_id | class_fqn | family | why disabled | what's needed to enable |
|---|---|---|---|---|
| `changelly` | `Nafezly\Payments\Classes\ChangellyPayment` | crypto | `pay()` ends with `dd($response)` (halts execution) AND uses hardcoded placeholder data (`"14.08"`, `"johndoe@example.com"`). `verify()` calls the Paymob HMAC path. | Reimplement `pay()` against the documented Changelly pay endpoint and write a real `verify()`. |
| `prime` | `Nafezly\Payments\Classes\PrimePayment` | crypto | `pay()` calls Prime endpoint then `dd()`; the function then unconditionally falls through to a BigPay wrapper. `verify()` queries the BigPay endpoint. Effectively a BigPay wrapper with dead Prime code. | Disable permanently; route any `prime` callers to `bigpay`. |
| `payrexx` | `Nafezly\Payments\Classes\PayrexxPayment` | local_apm | `verify()` calls the Tap API endpoint (copy/paste bug). | Replace the verify path with the documented Payrexx `ApiSignature` verification. |
| `paymob.refund` | (method, not a gateway) | mena_aggregator | `PaymobPayment::refund()` ends with `dd(...)`. | The platform driver implements refund directly against Paymob's void_refund API and bypasses the broken upstream method. The gateway is `enabled`; only the refund path is overridden. |

## 6. Resolution precedence

When a payment intent is created without an explicit `gateway_id`,
the platform resolves one in this order (mirrors file-service 6):

1. `payment_intent.gateway_pin` — operator pinned this intent to a gateway (admin override).
2. `payment.gateway.override.tenant.<tenant_id>` — tenant-level pin.
3. `payment.gateway.override.region.<gateway_region>` — region default.
4. `payment.gateway.override.currency.<iso4217>` — currency default.
5. `payment.gateway.override.payment_method.<method>` — payment-method default.
6. `payment.gateway.default` — env default.
7. The first `state='enabled'` gateway matching `region` AND `currency` AND `method` sorted by `priority` ASC.

The result of resolution is recorded in
`payment_gateway_assignments` (3 of [`ERD.md`](./ERD.md)) with
the `source` discriminator (`gateway_pin`, `tenant_override`,
`region_default`, `currency_default`, `method_default`, `env_default`,
`auto`).

## 7. Vault path convention

For every gateway, credentials live in Vault at:

```
secret/payment-service/gateway/<gateway_id>/<env>
```

Where `<env>` ∈ {`dev`, `staging`, `prod`, `prod-eu`, `prod-mena`,
`prod-apac`, ...}. The platform enforces one key per gateway per
environment per the constraint in
[`architecture/SECURITY_ARCHITECTURE.md` 5](../../architecture/SECURITY_ARCHITECTURE.md#5-secrets)
("API keys (provider credentials for payment, SMS, etc.) are
issued per environment per provider account").

## 8. Per-gateway isolation

Every outbound call to a gateway goes through the platform's
five-layer isolation pattern (per
[`architecture/SERVICE_ISOLATION.md` L49–51](../../architecture/SERVICE_ISOLATION.md)):

- **Timeout**: 5s for I/O; 1s for `probe`.
- **Bulkhead**: per-gateway connection pool, sized via the env
  defaults (at least the BEST-EFFORT floor: 25 in-flight, 50
  queue, 500ms timeout). With 46 gateways this is ≥ 1150
  in-flight per replica just for gateway calls — sized
  accordingly in the deployment manifest.
- **Circuit breaker**: per-gateway; opens at 5 consecutive 5xx/timeout
  in 30s; half-open after 60s. State is **persisted** so restarts
  don't re-trip a known-bad gateway.
- **Retry**: 3 attempts with exponential backoff (100ms, 400ms,
  1.6s); never on 4xx; never on signature errors; carries
  `Idempotency-Key` on every attempt.
- **Fallback**: DEGRADABLE — route to the next-priority gateway in
  the same region per the registry. CRITICAL — no fallback for
  `authorize` (the intent fails and the saga compensates).

## 9. Health probes

Each gateway declares a `health_url` in
`payment.gateway.<id>.health_url`. The platform runs a synthetic
probe on the schedule defined in `payment.gateway.<id>.probe_interval_seconds`
(default 30s). Probe results roll up to
`payment_gateways.health` (`healthy`/`degraded`/`unreachable`) and
emit `payment.gateway.health.changed.v1` on every transition. Probe
results are also written to the partitioned
`payment_gateway_health_events` table.

A gateway in `unreachable` health is skipped during resolution (it
remains `enabled` for existing intents but is excluded from the
auto-resolution path until it recovers to `healthy` for two
consecutive probes).

---

## See also

### Sibling docs for this service

- [`README.md`](./README.md) — purpose, bounded context, responsibilities
- [`BRD.md`](./BRD.md) — business requirements
- [`SRS.md`](./SRS.md) — functional + non-functional requirements
- [`ERD.md`](./ERD.md) — data model (entities, relationships) — includes the `payment_gateways` catalog table
- [`INTEGRATION.md`](./INTEGRATION.md) — inter-service contracts (APIs, events, sagas)
- [`WORKFLOWS.md`](./WORKFLOWS.md) — operational workflows
- [`TECH.md`](./TECH.md) — technology profile (runtime, libraries, `PaymentGatewayDriver` interface, per-driver SDK list)

### Platform-wide

- [`../../architecture/SERVICE_ISOLATION.md`](../../architecture/SERVICE_ISOLATION.md) — five-layer isolation pattern; per-downstream circuit / bulkhead / retry / timeout / fallback
- [`../../architecture/DOWNSTREAM_ERROR_CATALOG.md`](../../architecture/DOWNSTREAM_ERROR_CATALOG.md) — per-vendor error mapping anchor
- [`../../architecture/SECURITY_ARCHITECTURE.md`](../../architecture/SECURITY_ARCHITECTURE.md) — Vault paths, PCI scope
- [`../../architecture/EVENT_ARCHITECTURE.md`](../../architecture/EVENT_ARCHITECTURE.md) — additive-only event-schema evolution
- [`../../services/file-service/`](../../services/file-service/) — the canonical driver-pattern reference (`StorageDriver` interface, `storage_drivers` catalog)
- [`../../services/configuration-service/`](../../services/configuration-service/) — owner of the `payment.gateway.*` config-key family
