# Domain, HTTPS, and Tunneling Architecture

## The Challenge
You are running Obsidian Optimizer on a local laptop (or a home server). Home networks sit behind routers with Network Address Translation (NAT) and dynamic IPs. To access it from anywhere using your new custom domain, we need a secure way to tunnel traffic into your local machine and serve it over HTTPS.

## The Recommendation: Cloudflare Tunnels (Zero Trust)
While traditional setups use port-forwarding on your router + Certbot for SSL, the modern, secure way to expose a local web app is **Cloudflare Tunnels**. 

### Why Cloudflare Tunnels?
1.  **No Port Forwarding**: Your laptop opens an outbound connection to Cloudflare. Your home network stays locked down.
2.  **Free & Automatic HTTPS**: Cloudflare handles the SSL certificates entirely at their edge. You don't need to run Certbot or renew certificates.
3.  **Docker Integration**: It runs as a tiny, lightweight container right next to your Nginx container.

---

## Step-by-Step Setup Plan

### 1. DNS Setup
1.  Create a free Cloudflare account.
2.  Add your new domain to Cloudflare.
3.  Cloudflare will give you two "Nameservers". Log into the registrar where you bought the domain (e.g., Namecheap, GoDaddy) and change the domain's nameservers to the ones Cloudflare provided.
4.  Cloudflare now manages your DNS.

### 2. Creating the Tunnel (Cloudflare Zero Trust)
1.  In Cloudflare, go to **Zero Trust > Networks > Tunnels**.
2.  Create a new tunnel (e.g., "obsidian-laptop").
3.  Cloudflare will give you a **Tunnel Token**.

### 3. Updating `docker-compose.yml`
We will add a new service to your Docker Compose file to run the tunnel agent (`cloudflared`). 

```yaml
services:
  # ... your existing nginx, spring-boot, postgres containers ...

  cloudflared:
    image: cloudflare/cloudflared:latest
    command: tunnel run
    environment:
      - TUNNEL_TOKEN=${CLOUDFLARE_TUNNEL_TOKEN}
    restart: always
```

### 4. Routing the Traffic
Back in the Cloudflare Dashboard, configure the tunnel:
*   **Public Hostname**: `obsidian.yourdomain.com`
*   **Service**: Define where the tunnel should forward traffic on your local machine. Since the tunnel container is in the same Docker network as your Nginx container, you simply set the destination to `http://nginx:80`.

### The Result
1.  You type `https://obsidian.yourdomain.com` on your mobile phone on a train.
2.  Traffic hits Cloudflare's edge servers (secure, HTTPS automatically applied).
3.  Cloudflare routes the traffic down the secure tunnel directly into your laptop's `cloudflared` container.
4.  The container securely hands the request to your Nginx container.

---

## Alternative: Traditional Port Forwarding & Certbot
If you prefer not to use Cloudflare Tunnels, here is the traditional path:

1.  **Dynamic DNS (DDNS)**: Since your home IP changes, run a DDNS updater container to keep your domain pointed to your home router.
2.  **Port Forwarding**: Log into your router and forward Ports `80` and `443` to your laptop's local IP address.
3.  **Certbot Container**: Add a `certbot` container to your `docker-compose.yml`.
4.  **Nginx Config**: Update `nginx.conf` to handle ACME challenge routing and reference the Let's Encrypt SSL certificates mounted via Docker volumes.

*Verdict*: Cloudflare Tunnels is wildly superior for home-laptop scenarios because it avoids exposing your home router to internet port scans and completely eliminates SSL certificate management.