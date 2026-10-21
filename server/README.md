# Faznet upload server

Small Flask app that lets users send playlists to a TV/phone running Faznet using the device's
MAC + PIN (shown in Faznet → "Upload from the web").

## Run with Docker (recommended)

```bash
docker build -t faznet-server ./server
docker run -d --name faznet --restart unless-stopped \
  -p 8080:8080 -v faznet-data:/data \
  faznet-server
```

Put it behind HTTPS (playlist URLs contain usernames and passwords). Example with Caddy:

```
faznet.yourdomain.com {
    reverse_proxy 127.0.0.1:8080
}
```

## Run without Docker

```bash
cd server
python3 -m venv .venv && . .venv/bin/activate
pip install -r requirements.txt
FAZNET_DATA=/var/lib/faznet gunicorn -w 2 -b 127.0.0.1:8080 app:app
```

## Point the app at it

* Per device: Faznet → device icon → **Server** → `https://faznet.yourdomain.com`
* Or bake it into the APK: build with `-PfaznetServer=https://faznet.yourdomain.com`
  (sets `BuildConfig.SERVER_URL`).

## Security notes

* A device must register (MAC + PIN) before anything can be uploaded to it; the first registration wins.
* PINs are stored as HMAC-SHA256 hashes (secret in `$FAZNET_DATA/secret.key` or `FAZNET_SECRET`).
* 10 wrong PINs for one MAC within 15 minutes locks that MAC for 15 minutes.
* Playlist credentials are stored in SQLite because they must be delivered to the device — protect `/data`.
