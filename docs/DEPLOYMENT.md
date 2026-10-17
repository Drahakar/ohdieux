# Self-hosted Deployment

## Basic Deployment
This setup is fine for simple local deployments. See [Production
Considerations](#production-considerations) to configure a production
deployment.

0. Build the docker image.
```bash
docker build . -t ohdieux:latest
```
1. Run a basic docker installation.
```bash
docker run --restart always \
  -p 8080:8080 \
  -e PORT=8080 \
  -e PUBLIC_URL="https://ohdieux.taila21b4e.ts.net" \
  -v ./data:/data \
  --name ohdieux \
  192.168.1.20:5000/ohdieux
```
2. Head over to `http://localhost:8080/admin` to start adding programmes.

## Simple Deployment (with media archival)
This deployment will save the audio files for every episode.
**Ensure that you have a large amount of hard drive space.***

```bash
docker run --restart always \
  -p 8080:8080 \
  -e PORT=8080 \
  -e PUBLIC_URL="https://ohdieux.taila21b4e.ts.net" \
  -e ARCHIVE_MEDIA=true \
  -e MANIFEST_SERVE_IMAGES=true \
  -e MANIFEST_SERVE_MEDIA=true \
  -v ./data:/data \
  --name ohdieux \
  192.168.1.20:5000/ohdieux
```

## Production Considerations
* The admin dashboard is available on `/admin` **without any
  authentication**. Therefore, if exposing to the internet, you should
  forbid unauthenticated requests to `/admin/**`. 
  ```
  # nginx example
  location ~* /admin {
      return 403;
  }
  ```
  
* By default, Ohdieux is set to start tracking every queried
  manifest. If you are using the media archive, it is **highly
  recommended** to disable this setting by setting
  `SCRAPER_AUTO_ADD_PROGRAMME` to `false` to avoid filling up your
  hard drive.

* By default, Ohdieux uses an SQLite database. Consider using another
  JDBC driver or a caching load balancer if serving a large number of
  requests.


## nginx in Docker Behind Tailscale Funnel
This guide assumes the following deployment model:

1. Tailscale Funnel handles the public HTTPS endpoint.
2. nginx runs in Docker using the stock `nginx:alpine` image.
3. The nginx config file lives on the host and is mounted into the nginx container.
4. Ohdieux is published on host port `8080`.
5. nginx proxies to that host port and blocks unauthenticated access to `/admin` for Funnel traffic.

Because Funnel terminates HTTPS, nginx does not need to listen on `443`
or manage certificates. Keep `PUBLIC_URL` set to the external Funnel
URL, for example `https://example-node.ts.net`.

This setup does not use a custom Docker network.

Save the following nginx config on the host, for example as
`./nginx/funnel.conf`:

```nginx
upstream ohdieux_backend {
  server host.docker.internal:8080;
  keepalive 16;
}

server {
  listen 8081;
  server_name _;

  proxy_read_timeout 300s;
  proxy_send_timeout 300s;

  location = /admin {
    return 403;
  }

  location ^~ /admin/ {
    return 403;
  }

  location / {
    proxy_pass http://ohdieux_backend;
    proxy_http_version 1.1;

    proxy_set_header Host $host;
    proxy_set_header X-Real-IP $remote_addr;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto https;
    proxy_set_header X-Forwarded-Host $host;

    proxy_redirect off;
  }
}
```

On Linux, the nginx container needs an explicit host mapping for
`host.docker.internal`. Keep the `--add-host
host.docker.internal:host-gateway` line in the nginx command below. On
Docker Desktop, `host.docker.internal` already resolves automatically,
so that line can be removed.

Run Ohdieux with port `8080` published on the host, then run nginx with
the host config mounted into the stock image:

```bash
docker run --restart always \
  --name ohdieux \
  -p 8080:8080 \
  -e PORT=8080 \
  -e PUBLIC_URL="https://ohdieux.taila21b4e.ts.net" \
  -v ./data:/data \
  ohdieux:latest

docker run --restart always \
  --name ohdieux-nginx \
  -p 127.0.0.1:8081:8081 \
  --add-host host.docker.internal:host-gateway \
  --mount type=bind,src=/root/nginx.conf,target=/etc/nginx/conf.d/default.conf,readonly \
  nginx:alpine

tailscale funnel 443 http://127.0.0.1:8081
```

Using `nginx -c` is possible, but it expects a full top-level
`nginx.conf`. The config above is a `conf.d` snippet, so mounting it at
`/etc/nginx/conf.d/default.conf` is the simpler option.

Validate the mounted nginx config with:

```bash
docker exec ohdieux-nginx nginx -t
```

In this setup, direct traffic to host port `8080` bypasses nginx and can
reach `/admin` directly. Traffic arriving through Tailscale Funnel still
goes through nginx and gets the `/admin` block. If LAN clients should
not reach `/admin`, restrict access to `8080` with host firewall rules
or stop publishing that port directly.


## Environment Variables
Environment variables are defined and used in [application.conf](/src/main/resources/application.conf).

| Environment variable        | Description                                                                       | Default value                       |
|-----------------------------|-----------------------------------------------------------------------------------|-------------------------------------|
| PORT                        | Listening port                                                                    | 9000                                |
| HTTP_ADDRESS                | Binding address                                                                   | 0.0.0.0                             |
| DATA_DIR                    | Global default data directory.                                                    | `./` locally, `/data/` in docker.   |
| PUBLIC_URL                  | Public-facing base URL. (**Required if using archive**)                           | `unset`, e.g. `https://example.com` |
| JDBC_DRIVER                 | Database driver class-name. Currently, only `org.sqlite.JDBC` is supported.       | `org.sqlite.JDBC`                   |
| JDBC_URL                    | Database connection string (JDBC format)                                          | `jdbc:sqlite:${DATA_DIR}ohdieux.db` |
| JDBC_USERNAME               | Database username (if required)                                                   | `unset`                             |
| JDBC_PASSWORD               | Database password (if required)                                                   | `unset`                             |
| RC_BASE_URL                 | Base RC API URL                                                                   | `https://services.radio-canada.ca`  |
| USER_AGENT                  | User-Agent string                                                                 | See _application.conf_              |
| ARCHIVE_MEDIA               | Whether to download discovered audio files (true/false)                           | `false`                             |
| ARCHIVE_BASEDIR             | Where to save audio/image files (if archive is enabled).                          | `${DATA_DIR}archive`                |
| ARCHIVE_TEMP_DIR            | Where to save in-progress downloads.                                              | `/tmp/ohdieux`                      |
| ARCHIVE_PROGRAMME_BLACKLIST | List of programmes for which to ignore archive setting.                           | `[]`                                |
| SCRAPER_REFRESH_INTERVAL    | Scraping interval in seconds.                                                     | `3600`                              |
| SCRAPER_MAX_EPISODES        | Max number of episodes to scrape per programme.                                   | `500`                               |
| SCRAPER_AUTO_ADD_PROGRAMME  | Whether to automatically start tracking every queried programme (true/false).     | `true`                              |
| MANIFEST_SERVE_IMAGES       | Whether to serve manifest images from local archive or upstream CDN. (true/false) | `false`                             |
| MANIFEST_SERVE_MEDIA        | Whether to serve audio files from archive instead of upstream CDN. (true/false)   | `false`                             |
| MANIFEST_IMAGE_BASE_URL     | Override image archive public URL. (Advanced deployments only.)                   | `${PUBLIC_URL}/media/image/`        |
| MANIFEST_AUDIO_BASE_URL     | Override audio archive public URL. (Advanced deployments only.)                   | `${PUBLIC_URL}/media/audio/`        |



