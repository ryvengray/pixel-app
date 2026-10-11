# Gray service

This is Gray's private service: it calls DeepSeek, runs background reminders and sends FCM notifications. It runs on the local computer behind the existing HTTPS/frp entry point. The complete deployment guide, including the `/gray/` Nginx context, is in [DEPLOYMENT.md](DEPLOYMENT.md).

## Docker Compose deployment

From the repository root:

1. Copy `server/.env.example` to `server/.env`, set `GRAY_PUBLIC_BASE_URL` to the public HTTPS address provided by frp, then set `DEEPSEEK_API_KEY`. Keep this file private.
2. Start the service with `docker compose up -d --build`.
3. Confirm it is healthy with `docker compose ps` and `curl http://127.0.0.1:8787/health`.

The database is stored in `server/data/` on the local computer and is not tracked by Git. The Compose file binds port `8787` only to `127.0.0.1`, so it is not exposed on the local network. Follow [DEPLOYMENT.md](DEPLOYMENT.md) to forward that port through frp and publish it below the `/gray/` context on the Aliyun Nginx HTTPS endpoint.

To update after source changes, run `docker compose up -d --build`. Use `docker compose logs -f gray-service` to inspect the service. `docker compose down` stops the container without deleting the database volume directory.

Expose only the HTTPS Nginx endpoint to the phone. The app sends its device-specific token in an `Authorization: Bearer` header after it has been paired.

## Pairing a phone

No long-lived shared access token is typed into the phone. After the service is running, execute this on the local computer:

```bash
docker compose exec gray-service python -m app.pairing
```

It prints a one-time QR code that expires after ten minutes. In Gray, open “助理设置” and tap “扫码配对这台设备”. The app exchanges this code over HTTPS for a device-specific token, encrypts that token with Android Keystore, and never shows it. The server stores only a SHA-256 hash of the device token.

The app attaches the device token as a Bearer header for each request. A later device-management screen can revoke an individual device without affecting others.

FCM is optional at first. After creating a Firebase service account, save its JSON as `server/private/firebase-service-account.json` and retain the path in `.env.example`. The entire `private/` directory is ignored by Git. A registered device then receives reminders as system notifications; every reminder is also retained in the message inbox for later retrieval.

The phone is the source of truth for the user’s complete long-term memory. This service retains only the synchronized memory projection necessary for service-side reminders and proactive work. Delete events are tombstones and remove the service copy.
