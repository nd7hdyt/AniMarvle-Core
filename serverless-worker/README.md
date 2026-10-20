# Animarvle Cloudflare Worker (Turbo Streaming Relay)

This is the serverless edge backend that powers **AniVortex-style instant HLS streaming** in Animarvle.

### 💰 Cost: $0.00 / Month (Cloudflare Free Tier)
Cloudflare gives you **100,000 requests per day for free** forever. You will never need to pay for hosting this relay.

---

### 🚀 How to Deploy in 2 Minutes

#### Method A: One-Command CLI (Recommended)
1. Open PowerShell or Terminal in this folder (`serverless-worker`):
   ```bash
   cd "serverless-worker"
   npx wrangler login
   ```
2. Deploy to your free Cloudflare account:
   ```bash
   npx wrangler deploy
   ```
3. Cloudflare will give you a public URL, for example:
   `https://animarvle-edge.<your-subdomain>.workers.dev`

---

#### Method B: Zero-Install Cloudflare Web Dashboard
1. Go to [dash.cloudflare.com](https://dash.cloudflare.com) and log in or create a free account.
2. In the left menu, click **Compute (Workers & Pages)** ➔ **Create application** ➔ **Create Worker**.
3. Name it `animarvle-edge` and click **Deploy**.
4. Click **Edit code**, copy the entire contents of `worker.js`, paste it into the editor, and click **Save and deploy**.
5. Copy your worker URL (e.g. `https://animarvle-edge.<your-subdomain>.workers.dev`).

---

### 📱 Connecting to Animarvle App
1. Open **Animarvle** on your device.
2. Go to **Profile** ➔ **Player Settings**.
3. Under **Streaming Server**, tap **Cloudflare Worker URL** and paste your URL.
4. Enjoy instant, buffer-free adaptive HLS playback with in-player quality switching!
