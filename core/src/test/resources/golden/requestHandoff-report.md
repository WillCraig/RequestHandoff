# RequestHandoff export report

Requests exported: 2
Requests skipped: 0
Warnings: 2

## Limitations

These limits apply to every v0.1 export:

- Inline response-handler and pre-request scripts are translated to Postman `pm.*` where a documented equivalent exists. Calls without a safe equivalent are left as comments and listed in this report. A script file is inlined when its text is available; a missing script file is named in a comment and is not dropped.
- WebSocket and gRPC requests are skipped.
- Basic, Bearer, Digest, and API key credentials, and OAuth 2.0 references, are exported as Postman auth objects. The matching Authorization header or API key field is omitted so Postman does not send it twice. Unrecognized Authorization schemes stay headers. Access tokens are not acquired or refreshed.
- Dynamic variables with a Postman equivalent are rewritten (`{{$uuid}}` becomes `{{$guid}}`). Unmapped dynamic variables are left unchanged and listed below. `{{$random.integer(from, to)}}` becomes `{{$randomInt}}`, which ignores that range. Host environment variables (`{{$env.NAME}}`, `{{$processEnv NAME}}`, and `{{$dotenv NAME}}`) are not read.

## Skipped requests

None.

## Not converted

- health.http — GET {{host}}/health — Auth variable 'token' is not in the public environment. Private values are off, so a placeholder was exported and the secret was not written.
- http-client.private.env.json — Private environment file was present and was not read. Private values are excluded unless you explicitly enable them.

## Environments

Named environments written: dev.

## Output files

- `api.postman_collection.json`
- `dev.postman_environment.json`
- `requestHandoff-report.md`
