# Animarvle Cloudflare Worker (Turbo Streaming Relay)

This is the serverless edge backend that powers **AniVortex-style instant HLS & Multi-Audio streaming** in Animarvle.

### 💰 Cost: $0.00 / Month (Cloudflare Free Tier)
Cloudflare gives you **100,000 requests per day for free** forever. You will never need to pay for hosting this relay.

---

### 🌟 Key Features & Architecture

1. **Phased Multi-Audio Resolver Pipeline:**
   - **Tier 1 (Fast Primaries):** VidLink, Videasy, MovieBox (resolves English, Multi-Audio, and primary HLS streams in <800ms).
   - **Tier 2 (Regional Multi-Audio):** MoviesMod (Hindi, Tamil, Telugu), PelisPlus (Spanish/Castellano), AnimeHLS (Japanese Sub/Dub), Donghua (Chinese Sub/Dub).
   - **Subrequest Budgeting:** Enforces strict per-resolver timeouts and limits to stay strictly below Cloudflare Free Tier's 50-subrequest ceiling, preventing HTTP 500 crashes.

2. **Google Drive & Direct Stream Byte-Range Proxying (`/proxy/stream`):**
   - Automatically resolves multi-hop form redirects and token decryption (e.g. DriveSeed, FastDL).
   - Proxies Google Drive direct streams with full HTTP 206 partial content slicing (`Range` headers), enabling seamless ExoPlayer seeking without hitting drive quota walls.
   - Sniffs and preserves Matroska / EBML (`.mkv`) headers for flawless high-definition multi-track audio playback.

3. **Turnstile & Bot Wall Filtering:**
   - Detects and drops Cloudflare Turnstile bot-challenged domains (e.g. `embed69.org`) before passing streams to the client, preventing dead server entries.

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
4. Enjoy instant, buffer-free adaptive HLS and multi-language audio playback!
