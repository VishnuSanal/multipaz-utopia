# Brewery demo deployment

Builds a standalone container image for the Brewery demo server. Unlike the
Multipaz "server bundle" (which packs many reference servers behind one nginx),
this image runs a single service — the brewery backend's fat jar — directly.

## Build

```bash
# Native architecture
./gradlew :deployment:buildDockerImage
```

This depends on `:organizations:brewery:backend:shadowJar`, which produces the
self-contained `backend-all.jar` baked into the image.

## Run locally

```bash
./gradlew :deployment:runDockerImage
# or
podman run --rm -p 8010:8010 multipaz/brewery:latest
```

Then open <http://localhost:8010>.

## Configuration

| Env var | Default | Description |
|---|---|---|
| `BASE_URL` | `http://localhost:8010` | Externally reachable URL advertised in protocol messages. Set to e.g. `https://brewery.example.com` in production. |
| `PORT` | `8010` | Port the server listens on inside the container. |
| `EXTRA_PARAMS` | _(empty)_ | Extra `-param key=value` arguments passed through to the server. |

Persist the sqlite database and logs across runs by mounting volumes:

```bash
podman run -d --rm \
  -p 127.0.0.1:8010:8010 \
  -e BASE_URL=https://brewery.example.com \
  -v /your/db/folder:/app/data:z \
  -v /your/logs/folder:/app/logs:z \
  multipaz/brewery:latest
```

TLS termination and ingress routing are left to your environment (e.g. a fronting
nginx or cloud load balancer pointing at port 8010).
