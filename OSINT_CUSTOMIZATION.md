# NOVA OSINT customization

NOVA's OSINT workspace is a configurable, public-source research tool. It does not log in to accounts, bypass access controls, or access private data.

## Built-in modules

The **Customize investigation** screen can independently enable or disable:

- GitHub public account lookup
- GitLab public account lookup
- Candidate profile URL generation (these URLs are leads, not verified accounts)
- A public-web search link
- Domain DNS records
- Domain RDAP records
- Certificate Transparency names
- User-configured public HTTPS sources

The selected modules, result limit, and time budget are saved locally in the app. The quick preset reduces the number of checks; all other settings can be adjusted individually.

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

Every finding retains a source URL and a confidence label. Candidate profile URLs and custom provider responses are explicitly unverified. A username or name match is not proof that two accounts belong to the same person. Reports are saved locally and can be shared by the user.
