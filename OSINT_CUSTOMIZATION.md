# NOVA OSINT customization

NOVA's OSINT workspace is a configurable, public-source research tool. It does not log in to accounts, bypass access controls, or access private data.

## Built-in modules

The **Customize investigation** screen can independently enable or disable:

- GitHub public account lookup (exact handle)
- GitLab public account lookup (exact handle)
- DEV Community public profile lookup (exact handle)
- Hacker News public profile and contribution counts (exact handle)
- Stack Overflow public display-name search (exact display-name candidates only)
- Candidate profile URL generation (these URLs are leads, not verified accounts)
- A public-web search link
- Domain DNS records
- Domain RDAP records
- Certificate Transparency names
- User-configured public HTTPS sources

The selected modules, result limit, and time budget are saved locally in the app. The quick preset reduces the number of checks; the deep preset enables the public profile adapters and uses a longer time budget. Each provider has an independent adapter, so one provider failure does not prevent other checks.

## Custom public sources

Custom sources are optional and disabled until explicitly enabled. Add at most ten entries, one per line:

```text
Provider name|https://public-provider.example/search?q={query}
```

The `{query}` placeholder is replaced with the URL-encoded investigation target. NOVA performs an HTTPS GET through its configured network transport and records a bounded response as **unverified provider output**. It does not interpret provider output as a confirmed identity or fact.

Requirements:
- HTTPS only; HTTP, embedded URL credentials, and non-standard ports are rejected.
- Use a fully qualified public hostname.
- Localhost, local-network IP literals, and local hostname suffixes are rejected.
- A maximum of ten configured sources is retained.
- The app asks for confirmation before sending a target to enabled custom providers.
- Custom provider responses must be reviewed at their original source. A successful HTTP response is not proof that its contents are accurate.

This first adapter version supports unauthenticated public GET endpoints. It does not store API keys or custom request headers. Do not put secrets in a URL template.

## Performance and limits

- OSINT runs on a background IO dispatcher and does not call NOVA's model inference engine.
- The time budget is configurable from 5 to 120 seconds.
- The report finding limit is configurable from 5 to 200.
- Each HTTP response remains bounded by the engine's response-size cap.
- One source failure is recorded as a limitation and does not cancel the other collectors.
- Time limits are cooperative between collectors; an individual in-flight network request can still consume its configured connection/read timeout.

## Evidence quality

The report includes a **source coverage** section for each built-in username provider: found, no match, blocked, rate limited, unavailable, error, skipped, or disabled. “No match” means only that this provider returned no exact account for this query; it does not prove the account does not exist elsewhere. Stack Overflow searches display names and therefore returns candidates, not verified usernames.

Every finding retains a source URL and a calibrated confidence label. Exact handles establish only that a public account exists on that platform; they do not establish who controls it or link two accounts to the same person. NOVA does not collect email addresses, phone numbers, private messages, home addresses, or live-location data. Candidate profile URLs and custom provider responses remain explicitly unverified. Reports are saved locally and can be shared by the user.
