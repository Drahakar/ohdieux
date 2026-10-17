# Déploiement autogéré

## Déploiement de base
Ce déploiement est adéquat pour un environment local. Voir [Considérations de
production](#considérations-de-production) pour configurer un
déploiment de production.

0. Créer l'image docker
```bash
docker build . -t ohdieux:latest
```
1. Créer un container docker
```bash
docker run --restart always \
  -p 8080:8080 \
  -e PORT=8080 \
  -e PUBLIC_URL="http://<my-accessible-ip>:8080" \
  -v ./data:/data \
  --name ohdieux \
  ohdieux:latest
```
2. Rendez vous au `http://localhost:8080/admin` pour ajouter de
   nouveaux programmes.

## Déploiement simple (avec archive média)
Ce déploiement sauvegarde les fichiers audio de chaque épisode découvert.
**Assurez-vous d'avoir beaucoup d'espace de disque disponible.***

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

## Considérations de production
* Le tableau de bord d'administrateur est disponible sous le chemin
  `/admin` **sans authentification**. Assurez-vous de restreindre
  l'accès au routes sous `/admin/**` si votre serveur est exposé à l'internet.
  ```
  # exemple pour nginx
  location ~* /admin {
      return 403;
  }
  ```
  
* Par défaut, Ohdieux ajoute à sa base de données tous les programmes
  demandés sur la route `/rss`. Si vous utilisez la fonctionnalité
  d'archivage média, il est **fortement recommandé** de désactiver
  cette fonctionnalité en assignant la valeur `false` à la variable
  `SCRAPER_AUTO_ADD_PROGRAMME` pour éviter de remplir votre disque dur.

* Par défault, Ohdieux utilise une base de données SQLite. Considérez
  l'utilisation d'un autre pilote JDBC ou un _load balancer_ avec une
  fonction de cache pour servir un grand nombre de requêtes.


## Exemple de configuration nginx
Si vous exposez Ohdieux derrière nginx, assurez-vous que `PUBLIC_URL`
corresponde à l'URL externe servie par nginx. Par exemple, si nginx
sert `https://feeds.example.com`, démarrez le conteneur avec
`-e PUBLIC_URL="https://feeds.example.com"`.

La configuration ci-dessous suppose que nginx termine TLS et transmet
les requêtes à un conteneur local ou un processus local à l'écoute sur
`127.0.0.1:8080`. Elle proxifie les routes publiques (`/`, `/rss`,
`/media/*`, `/health`) et bloque le tableau de bord d'administration,
qui n'est pas authentifié.

```nginx
upstream ohdieux_backend {
  server 127.0.0.1:8080;
  keepalive 16;
}

server {
  listen 80;
  listen [::]:80;
  server_name feeds.example.com;

  return 301 https://$host$request_uri;
}

server {
  listen 443 ssl http2;
  listen [::]:443 ssl http2;
  server_name feeds.example.com;

  ssl_certificate /etc/letsencrypt/live/feeds.example.com/fullchain.pem;
  ssl_certificate_key /etc/letsencrypt/live/feeds.example.com/privkey.pem;

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
    proxy_set_header X-Forwarded-Proto $scheme;
    proxy_set_header X-Forwarded-Host $host;
    proxy_set_header X-Forwarded-Port $server_port;

    proxy_redirect off;
  }
}
```

Si vous devez accéder à `/admin` à distance, remplacez les directives
`return 403;` par des règles `allow`/`deny` ou par `auth_basic`.

### nginx derrière Tailscale Funnel
Si Tailscale Funnel prend en charge HTTPS, nginx n'a pas besoin
d'écouter sur `443` ni de gérer les certificats. Funnel peut plutôt
transmettre les requêtes à une écoute HTTP locale de nginx, qui reste
responsable de bloquer le tableau de bord d'administration non
authentifié avant de proxifier vers Ohdieux.

Conservez `PUBLIC_URL` configuré avec l'URL publique de Funnel, par
exemple : `-e PUBLIC_URL="https://example-node.ts.net"`.

Exemple de commande Funnel :

```bash
tailscale funnel 443 http://127.0.0.1:8081
```

Exemple de configuration nginx pour cette architecture :

```nginx
upstream ohdieux_backend {
  server 127.0.0.1:8080;
  keepalive 16;
}

server {
  listen 127.0.0.1:8081;
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

Cette approche conserve le point d'entrée HTTPS public sur Funnel tout
en laissant nginx protéger `/admin` et proxifier les routes publiques
vers l'application sur `127.0.0.1:8080`.

### Exécuter nginx dans Docker
Si nginx s'exécute dans son propre conteneur, ne conservez pas
`127.0.0.1:8080` dans le bloc `upstream`. Depuis le conteneur nginx,
`127.0.0.1` pointe vers nginx lui-même, pas vers le conteneur Ohdieux.
Placez les deux conteneurs sur le même réseau Docker et proxifiez vers
le conteneur Ohdieux par son nom.

Exemple de configuration nginx pour un conteneur nginx séparé :

```nginx
upstream ohdieux_backend {
  server ohdieux:8080;
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

Vous n'avez pas besoin d'une image nginx personnalisée pour cette
architecture. Le plus simple est d'exécuter l'image standard
`nginx:alpine` et de monter votre fichier de configuration local dans
`/etc/nginx/conf.d/default.conf`.

Par exemple, si votre fichier de configuration est
`./nginx/funnel.conf`, lancez nginx avec :

```bash
docker run --restart always \
  --name ohdieux-nginx \
  --network ohdieux-net \
  -p 127.0.0.1:8081:8081 \
  --mount type=bind,src="$(pwd)/nginx/funnel.conf",target=/etc/nginx/conf.d/default.conf,readonly \
  nginx:alpine
```

Il est possible d'utiliser `nginx -c`, mais cette option attend un
fichier `nginx.conf` complet. Les exemples de ce guide sont des extraits
destinés à `conf.d`, donc le montage dans `default.conf` est plus
simple.

Exemple de commandes :

```bash
docker network create ohdieux-net

docker run --restart always \
  --name ohdieux \
  --network ohdieux-net \
  -e PORT=8080 \
  -e PUBLIC_URL="https://example-node.ts.net" \
  -v ./data:/data \
  ohdieux:latest

docker build -t ohdieux-nginx:latest ./nginx

docker run --restart always \
  --name ohdieux-nginx \
  --network ohdieux-net \
  -p 127.0.0.1:8081:8081 \
  --mount type=bind,src="$(pwd)/nginx/funnel.conf",target=/etc/nginx/conf.d/default.conf,readonly \
  nginx:alpine

tailscale funnel 443 http://127.0.0.1:8081
```

Le point important est que le conteneur Ohdieux s'appelle `ohdieux`, ce
qui permet à nginx de le joindre en `ohdieux:8080`. Le conteneur de
l'application n'a pas besoin d'inclure nginx; il est généralement plus
simple d'exécuter nginx dans un conteneur séparé.


## Variables d'environnement
Les variables d'environnement sont définies dans [application.conf](/src/main/resources/application.conf).

| Environment variable        | Description                                                                               | Default value                             |
|-----------------------------|-------------------------------------------------------------------------------------------|-------------------------------------------|
| PORT                        | Port d'écoute                                                                             | 9000                                      |
| HTTP_ADDRESS                | Address réseau d'écoute                                                                   | 0.0.0.0                                   |
| DATA_DIR                    | Dossier de base pour les données locales.                                                 | `./` localement, `/data/` dans docker.    |
| PUBLIC_URL                  | URL publique du serveur. (**Nécéssaire avec fonctionnalité d'archivage**)                 | _non spécifié_, ex. `https://example.com` |
| JDBC_DRIVER                 | Pilote de base de données. Pour le moment, seul `org.sqlite.JDBC` est supporté.           | `org.sqlite.JDBC`                         |
| JDBC_URL                    | Descriptif de connection pour la base de données (format JDBC)                            | `jdbc:sqlite:${DATA_DIR}ohdieux.db`       |
| JDBC_USERNAME               | Nom d'utilisateur pour la base de données (si requis)                                     | _non spécifié_                            |
| JDBC_PASSWORD               | Mot de passe pour la base de données (si requis)                                          | _non spécifié_                            |
| RC_BASE_URL                 | URL de base pour RC.                                                                      | `https://services.radio-canada.ca`        |
| USER_AGENT                  | Descriptif User-Agent                                                                     | Voir _application.conf_                   |
| ARCHIVE_MEDIA               | Activation de la fonction d'archivage (true/false)                                        | `false`                                   |
| ARCHIVE_BASEDIR             | Dossier de sauvegarde pour l'archivage (si activé).                                       | `${DATA_DIR}archive`                      |
| ARCHIVE_TEMP_DIR            | Dossier de sauvegarde pour téléchargements en cours                                       | `/tmp/ohdieux`                            |
| ARCHIVE_PROGRAMME_BLACKLIST | List de programmes à ne jamais archiver.                                                  | `[]`                                      |
| SCRAPER_REFRESH_INTERVAL    | Intervalle de rafraîchissement en secondes.                                               | `3600`                                    |
| SCRAPER_MAX_EPISODES        | Nombre maximal d'épisodes à sauvegarder (par programme).                                  | `500`                                     |
| SCRAPER_AUTO_ADD_PROGRAMME  | Activer l'ajout automatique de chaque programme demandé sur la route `/rss` (true/false). | `true`                                    |
| MANIFEST_SERVE_IMAGES       | Servir les fichiers images depuis l'archive locale. (true/false)                          | `false`                                   |
| MANIFEST_SERVE_MEDIA        | Servir les fichiers audio depuis l'archive locale. (true/false)                           | `false`                                   |
| MANIFEST_IMAGE_BASE_URL     | Configuration manuelle de l'URL de base pour les images à servir (déploiements avancés)   | `${PUBLIC_URL}/media/image/`              |
| MANIFEST_AUDIO_BASE_URL     | Configuration manuelle de l'URL de base pour les fichiers audio (déploiement avancés)     | `${PUBLIC_URL}/media/audio/`              |



