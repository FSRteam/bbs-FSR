# CNB 构建用 CF Worker Maven 代理（带 Key 验证）

本文档提供一套可直接部署的 Cloudflare Worker 代理方案，用于缓解 CNB 构建节点到 NeoForged Maven 源的 TLS 握手不稳定问题。

## 1. 目标

- 仅代理 Maven 依赖下载请求（`GET`/`HEAD`）。
- 仅放行 `releases` 路径，避免开放代理风险。
- 通过 `x-proxy-key` 或 `Authorization: Bearer` 强制鉴权。
- 上游请求失败时自动重试（最多 3 次）。
- 支持边缘缓存，减少回源次数。

## 2. 建议目录

```text
cf-worker-maven-proxy/
  ├─ src/
  │   └─ index.js
  └─ wrangler.toml
```

## 3. Worker 脚本（`src/index.js`）

```js
/**
 * Maven proxy on Cloudflare Workers
 * - 只允许 GET/HEAD
 * - 只允许 /releases/ 路径
 * - 通过 x-proxy-key 或 Authorization: Bearer <key> 验证
 * - 上游失败自动重试（最多 3 次）
 */
const DEFAULT_ORIGIN_BASE = "https://neoforged.forgecdn.net";
const ALLOWED_PREFIXES = ["/releases/"];
const KEY_HEADER = "x-proxy-key";
const MAX_ATTEMPTS = 3;

function sleep(ms)
{
  return new Promise((resolve) => setTimeout(resolve, ms));
}

function constantTimeEqual(a, b)
{
  const ea = new TextEncoder().encode(a || "");
  const eb = new TextEncoder().encode(b || "");
  const max = Math.max(ea.length, eb.length);
  let diff = ea.length ^ eb.length;

  for (let i = 0; i < max; i++)
  {
    diff |= (ea[i] ?? 0) ^ (eb[i] ?? 0);
  }

  return diff === 0;
}

function readClientKey(request)
{
  const k = request.headers.get(KEY_HEADER);

  if (k && k.trim())
  {
    return k.trim();
  }

  const auth = request.headers.get("authorization");

  if (auth && auth.startsWith("Bearer "))
  {
    return auth.slice(7).trim();
  }

  return "";
}

function buildUpstreamHeaders(request)
{
  const out = new Headers();
  const pass = [
    "range",
    "if-none-match",
    "if-modified-since",
    "accept",
    "accept-encoding",
    "user-agent"
  ];

  for (const h of pass)
  {
    const v = request.headers.get(h);

    if (v)
    {
      out.set(h, v);
    }
  }

  return out;
}

async function fetchWithRetry(url, init)
{
  let lastError = null;

  for (let attempt = 1; attempt <= MAX_ATTEMPTS; attempt++)
  {
    try
    {
      const resp = await fetch(url, {
        ...init,
        cf: {
          cacheEverything: true,
          cacheTtlByStatus: {
            "200-299": 86400,
            "404": 30,
            "500-599": 0
          }
        }
      });

      if ((resp.status === 429 || (resp.status >= 500 && resp.status <= 599)) && attempt < MAX_ATTEMPTS)
      {
        await sleep(300 * attempt);
        continue;
      }

      return { resp, attempt };
    }
    catch (err)
    {
      lastError = err;

      if (attempt < MAX_ATTEMPTS)
      {
        await sleep(300 * attempt);
        continue;
      }
    }
  }

  throw lastError ?? new Error("upstream request failed");
}

export default {
  async fetch(request, env)
  {
    const url = new URL(request.url);

    // 健康检查，无需 key
    if (url.pathname === "/healthz")
    {
      return Response.json({
        ok: true,
        colo: request.cf?.colo ?? null,
        asn: request.cf?.asn ?? null,
        time: new Date().toISOString()
      });
    }

    if (request.method !== "GET" && request.method !== "HEAD")
    {
      return new Response("Method Not Allowed", { status: 405 });
    }

    const serverKey = env.PROXY_KEY || "";
    const clientKey = readClientKey(request);

    if (!serverKey || !constantTimeEqual(clientKey, serverKey))
    {
      return new Response("Unauthorized", {
        status: 401,
        headers: {
          "www-authenticate": "Bearer"
        }
      });
    }

    if (!ALLOWED_PREFIXES.some((p) => url.pathname.startsWith(p)))
    {
      return new Response("Forbidden path", { status: 403 });
    }

    const originBase = (env.ORIGIN_BASE || DEFAULT_ORIGIN_BASE).replace(/\/+$/, "");
    const upstreamUrl = `${originBase}${url.pathname}${url.search}`;

    try
    {
      const { resp, attempt } = await fetchWithRetry(upstreamUrl, {
        method: request.method,
        headers: buildUpstreamHeaders(request),
        redirect: "follow"
      });

      const headers = new Headers(resp.headers);
      headers.delete("set-cookie");
      headers.set("x-proxy-upstream", new URL(upstreamUrl).host);
      headers.set("x-proxy-attempt", String(attempt));

      return new Response(resp.body, {
        status: resp.status,
        statusText: resp.statusText,
        headers
      });
    }
    catch (_err)
    {
      return new Response("Bad Gateway: upstream fetch failed", { status: 502 });
    }
  }
};
```

## 4. Wrangler 配置（`wrangler.toml`）

```toml
name = "neoforged-maven-proxy"
main = "src/index.js"
compatibility_date = "2026-04-05"
workers_dev = true

[vars]
ORIGIN_BASE = "https://neoforged.forgecdn.net"
```

## 5. 部署步骤

```bash
# 1) 登录
npx wrangler login

# 2) 写入密钥（推荐 32+ 字符随机值）
npx wrangler secret put PROXY_KEY

# 3) 部署
npx wrangler deploy
```

部署后会得到：

- `https://<worker-subdomain>.workers.dev/healthz`
- `https://<worker-subdomain>.workers.dev/releases/...`

## 6. 请求示例

```bash
# 成功（带 key）
curl -I "https://<worker-domain>/releases/net/neoforged/installertools/installertools/2.1.2/installertools-2.1.2-fatjar.jar" \
  -H "x-proxy-key: <your-secret>"

# 未鉴权（应 401）
curl -I "https://<worker-domain>/releases/net/neoforged/installertools/installertools/2.1.2/installertools-2.1.2-fatjar.jar"
```

## 7. Gradle 接入（Header 鉴权）

可放在 `settings.gradle` 的 `dependencyResolutionManagement.repositories` 或 `build.gradle` 的 `repositories`。

```groovy
import org.gradle.api.credentials.HttpHeaderCredentials
import org.gradle.authentication.http.HttpHeaderAuthentication

def proxyKey = (findProperty("bbsProxyKey") ?: System.getenv("BBS_PROXY_KEY") ?: "").toString()

dependencyResolutionManagement {
    repositories {
        maven {
            name = "bbsCfProxy"
            url = uri("https://<worker-domain>/releases")
            credentials(HttpHeaderCredentials) {
                name = "x-proxy-key"
                value = proxyKey
            }
            authentication {
                header(HttpHeaderAuthentication)
            }
        }

        // 保留后备源
        mavenCentral()
    }
}
```

`gradle.properties` 示例：

```properties
bbsProxyKey=replace-with-your-secret
```

## 8. CNB 中的 key 注入建议

- 推荐在 CNB 仓库变量或密钥管理里注入 `BBS_PROXY_KEY`。
- 构建脚本无需明文回显 key。
- 避免在日志中输出 `-PbbsProxyKey=...` 这类命令行参数。

## 9. 运行期排障要点

- 401：key 未配置或错误。
- 403：请求路径不在 `/releases/` 白名单。
- 502：Worker 到上游请求失败（可结合 `x-proxy-attempt` 观察重试情况）。
- 若依赖包体积较大，优先用 `GET` + `Range`，并观察 CF 缓存命中率。

## 10. 安全建议

- 定期轮换 `PROXY_KEY`。
- 使用 Cloudflare 自定义域并启用 WAF/速率限制。
- 将 Worker 仅用于 Maven 只读代理，不要扩展为通用转发器。
