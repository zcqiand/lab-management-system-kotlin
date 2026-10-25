#!/bin/sh
# Usage: lab-management-system-kotlin.sh <DOCKER_USERNAME> <DOCKER_PASSWORD> [VERSION]
#
# 由 .github/workflows/ci.yml 的 deploy job 远程调用:
#   ssh deploy@vps -- cd /home/deploy/lab-management-system-kotlin
#                    && sh lab-management-system-kotlin.sh $DOCKER_USERNAME $DOCKER_PASSWORD $VERSION
#
# VERSION 默认是 latest。tag-based deploy 时显式传 tag 名（v0.x.y-YYYYMMDD）。
# CI 同时 push :latest + :<tag> 两份镜像,回滚只要手动指定旧 tag 再跑一次本脚本。
#
# 与姊妹仓 saas-identity-platform-kotlin.sh 的差异:
#   - 端口 X09 lab 段 5209(host=container);域名 lab-kotlin.xiangru.uk
#   - env 契约 16 key = .env.example 全集(L0.5 形;DB 未接线但全键 fail-fast require)
#   - lab 消费 saas IdP:同栈匹配 LAB_SAAS_BASE_URL=https://saas-kotlin.xiangru.uk
#     (springboot→saas-springboot / aspnetcore→saas-aspnetcore 家族语义,kotlin→saas-kotlin)
#   - secrets 四件:DATABASE_URL / JWT_SIGNING_KEY / LAB_SAAS_CLIENT_SECRET /
#     LAB_SAAS_SERVICE_PASSWORD(其余 12 键字面量)
#
# 前置: deploy 用户需在 docker 组中(sudo usermod -aG docker deploy)。
#        kotlin.env 必须由 setup-vps.sh 或本脚本首启生成。

set -eu

USERNAME="${1:-}"
PASSWORD="${2:-}"
VERSION="${3:-latest}"
IMAGE="${USERNAME}/lab-management-system-kotlin:${VERSION}"
BASE="/home/deploy/lab-management-system-kotlin"
CONTAINER_NAME="lab-management-system-kotlin"

NGINX_DOMAIN="${NGINX_DOMAIN:-lab-kotlin.xiangru.uk}"
NGINX_CERT_BASENAME="${NGINX_CERT_BASENAME:-xiangru-uk}"

if [ -z "$USERNAME" ] || [ -z "$PASSWORD" ]; then
  echo "Usage: $0 <DOCKER_USERNAME> <DOCKER_PASSWORD> [VERSION]" >&2
  exit 2
fi

# kotlin.env 自举保护: 缺失时, 如四 secrets 在环境里, 自动生成(含字面量契约值);
# 否则 fail fast。setup-vps.sh 仍是首推(VPS 一次性), 本分支仅给"先有 env 临时上线"场景。
# key 集合 = .env.example 全集 16 key(deploy parity 的比对基线)。
if [ ! -f "$BASE/kotlin.env" ]; then
  if [ -n "${DATABASE_URL:-}" ] && [ -n "${JWT_SIGNING_KEY:-}" ] \
     && [ -n "${LAB_SAAS_CLIENT_SECRET:-}" ] && [ -n "${LAB_SAAS_SERVICE_PASSWORD:-}" ]; then
    echo "→ bootstrapping $BASE/kotlin.env from env secrets"
    umask 077
    {
      printf 'SERVER_PORT=5209\n'
      # prod PG 单串直传 secret（postgres:<secret>@100.79.128.25:5432/lab_prod 整串在案）
      printf 'DATABASE_URL=%s\n' "$DATABASE_URL"
      printf 'JWT_SIGNING_KEY=%s\n' "$JWT_SIGNING_KEY"
      printf 'JWT_ISSUER=lab-management-system\n'
      printf 'JWT_TTL_SECONDS=3600\n'
      printf 'JWT_REFRESH_TTL_SECONDS=604800\n'
      # saas 代理:同栈匹配(kotlin→saas-kotlin,家族语义)
      printf 'LAB_SAAS_BASE_URL=https://saas-kotlin.xiangru.uk\n'
      printf 'LAB_SSO_LOGIN_URL=https://saas-kotlin.xiangru.uk\n'
      printf 'LAB_SAAS_CLIENT_ID=lab-management\n'
      printf 'LAB_SAAS_CLIENT_SECRET=%s\n' "$LAB_SAAS_CLIENT_SECRET"
      printf 'LAB_SAAS_DEFAULT_TENANT_ID=00000000-0000-0000-0000-000000000001\n'
      printf 'LAB_SSO_CALLBACK_REDIRECT=https://lab-kotlin.xiangru.uk/login\n'
      printf 'LAB_SAAS_SERVICE_USER=alice\n'
      printf 'LAB_SAAS_SERVICE_PASSWORD=%s\n' "$LAB_SAAS_SERVICE_PASSWORD"
      printf 'LAB_SAAS_SERVICE_CLIENT_ID=lab-management\n'
      # prod 只有三前端域名,无 localhost(dev origin 不进 production)
      printf 'LAB_CORS_ALLOWED_ORIGINS=https://lab-vue.xiangru.uk,https://lab-react.xiangru.uk,https://lab-nextjs.xiangru.uk\n'
    } > "$BASE/kotlin.env"
    chown deploy:deploy "$BASE/kotlin.env" 2>/dev/null || true
    chmod 600 "$BASE/kotlin.env"
  else
    echo "ERROR: $BASE/kotlin.env missing AND DATABASE_URL/JWT_SIGNING_KEY/LAB_SAAS_CLIENT_SECRET/LAB_SAAS_SERVICE_PASSWORD secrets not forwarded" >&2
    exit 1
  fi
fi
if ! grep -q '^DATABASE_URL=..*' "$BASE/kotlin.env"; then
  echo "ERROR: $BASE/kotlin.env has no DATABASE_URL line" >&2
  exit 1
fi

# nginx vhost 重渲染(每次 deploy 都跑,ADR-0018:容器端口变了 vhost 必须跟)。
# 模板每次都从 master 拉最新 —— VPS 本地老模板会渲染出老端口全家族 502(2026-09-03 事故)。
# ?v=$(date +%s) cache-buster：CDN/raw 缓存防在案（flutter 先例）。
NGINX_SITES_AVAILABLE="/etc/nginx/sites-available"
NGINX_SITES_ENABLED="/etc/nginx/sites-enabled"
NGINX_VHOST_FILE="${NGINX_SITES_AVAILABLE}/${NGINX_DOMAIN}"
NGINX_VHOST_LINK="${NGINX_SITES_ENABLED}/${NGINX_DOMAIN}"
NGINX_TEMPLATE="${BASE}/nginx-vps.conf.example"

echo "→ fetching nginx-vps.conf.example template (always fresh from master)"
curl -fsSL "https://raw.githubusercontent.com/zcqiand/lab-management-system-kotlin/refs/heads/master/deploy/nginx-vps.conf.example?v=$(date +%s)" -o "${NGINX_TEMPLATE}"

# 渲染到临时文件 —— sed 顺序:cert 归一化规则必须排在 <domain>/YOUR_DOMAIN 通配之前
# (先替换 <domain> 会把 cert 路径占位符一并吃掉,2026-09-03 事故根因)。
TMP_VHOST="$(mktemp -t vpstpl.XXXXXX)"
sed \
  -e "s|/etc/nginx/ssl/<domain>\.crt|/etc/nginx/ssl/${NGINX_CERT_BASENAME}.cert|g" \
  -e "s|/etc/nginx/ssl/<domain>\.cert|/etc/nginx/ssl/${NGINX_CERT_BASENAME}.cert|g" \
  -e "s|/etc/nginx/ssl/<domain>\.key|/etc/nginx/ssl/${NGINX_CERT_BASENAME}.key|g" \
  -e "s|/etc/nginx/ssl/your-cert\.crt|/etc/nginx/ssl/${NGINX_CERT_BASENAME}.cert|g" \
  -e "s|/etc/nginx/ssl/your-cert\.cert|/etc/nginx/ssl/${NGINX_CERT_BASENAME}.cert|g" \
  -e "s|/etc/nginx/ssl/your-cert\.key|/etc/nginx/ssl/${NGINX_CERT_BASENAME}.key|g" \
  -e "s|<domain>|${NGINX_DOMAIN}|g" \
  -e "s|lab\.YOUR_DOMAIN|${NGINX_DOMAIN}|g" \
  -e "s|saas\.YOUR_DOMAIN|${NGINX_DOMAIN}|g" \
  "${NGINX_TEMPLATE}" > "${TMP_VHOST}"

if [ -e "${NGINX_VHOST_FILE}" ] && diff -q "${TMP_VHOST}" "${NGINX_VHOST_FILE}" >/dev/null 2>&1; then
  echo "→ nginx vhost ${NGINX_VHOST_FILE} unchanged, skip"
  rm -f "${TMP_VHOST}"
else
  echo "→ rendering nginx vhost ${NGINX_VHOST_FILE} (domain=${NGINX_DOMAIN} cert=${NGINX_CERT_BASENAME})"
  if [ -w "${NGINX_SITES_AVAILABLE}" ]; then
    cp "${TMP_VHOST}" "${NGINX_VHOST_FILE}"
  else
    sudo cp "${TMP_VHOST}" "${NGINX_VHOST_FILE}" \
      || { echo "ERROR: sudo cp ${NGINX_VHOST_FILE} failed"; rm -f "${TMP_VHOST}"; exit 1; }
  fi
  if [ -w "${NGINX_SITES_ENABLED}" ]; then
    ln -sf "${NGINX_VHOST_FILE}" "${NGINX_VHOST_LINK}"
  else
    sudo ln -sf "${NGINX_VHOST_FILE}" "${NGINX_VHOST_LINK}" \
      || { echo "ERROR: sudo ln ${NGINX_VHOST_LINK} failed"; rm -f "${TMP_VHOST}"; exit 1; }
  fi
  rm -f "${TMP_VHOST}"
  echo "→ nginx -t"
  sudo nginx -t
  echo "→ systemctl reload nginx"
  sudo systemctl reload nginx
  echo "✓ nginx reloaded"
fi

# 存量 env-file 补键:逐 key append-if-missing 到 kotlin.env 全集
# (显式写值,漂移在 deploy 期暴露;key 集合 = .env.example,secret 类走 fail-fast 不落字面)。
if [ -f "$BASE/kotlin.env" ]; then
  append_if_missing() {
    key="$1"; val="$2"
    if ! grep -q "^${key}=" "$BASE/kotlin.env"; then
      echo "→ append ${key} to existing $BASE/kotlin.env"
      umask 077
      printf '%s=%s\n' "$key" "$val" >> "$BASE/kotlin.env"
    fi
  }
  append_if_missing SERVER_PORT '5209'
  append_if_missing JWT_ISSUER 'lab-management-system'
  append_if_missing JWT_TTL_SECONDS '3600'
  append_if_missing JWT_REFRESH_TTL_SECONDS '604800'
  append_if_missing LAB_SAAS_BASE_URL 'https://saas-kotlin.xiangru.uk'
  append_if_missing LAB_SSO_LOGIN_URL 'https://saas-kotlin.xiangru.uk'
  append_if_missing LAB_SAAS_CLIENT_ID 'lab-management'
  append_if_missing LAB_SAAS_DEFAULT_TENANT_ID '00000000-0000-0000-0000-000000000001'
  append_if_missing LAB_SSO_CALLBACK_REDIRECT 'https://lab-kotlin.xiangru.uk/login'
  append_if_missing LAB_SAAS_SERVICE_USER 'alice'
  append_if_missing LAB_SAAS_SERVICE_CLIENT_ID 'lab-management'
  append_if_missing LAB_CORS_ALLOWED_ORIGINS 'https://lab-vue.xiangru.uk,https://lab-react.xiangru.uk,https://lab-nextjs.xiangru.uk'
  if ! grep -q '^JWT_SIGNING_KEY=..*' "$BASE/kotlin.env"; then
    echo "ERROR: $BASE/kotlin.env JWT_SIGNING_KEY missing/empty — 去 GitHub Secrets 补 JWT_SIGNING_KEY 后重跑" >&2
    exit 1
  fi
  if ! grep -q '^DATABASE_URL=..*' "$BASE/kotlin.env"; then
    echo "ERROR: $BASE/kotlin.env DATABASE_URL missing/empty — 去 GitHub Secrets 补 DATABASE_URL 后重跑" >&2
    exit 1
  fi
  if ! grep -q '^LAB_SAAS_CLIENT_SECRET=..*' "$BASE/kotlin.env"; then
    echo "ERROR: $BASE/kotlin.env LAB_SAAS_CLIENT_SECRET missing/empty — 去 GitHub Secrets 补 LAB_SAAS_CLIENT_SECRET 后重跑" >&2
    exit 1
  fi
  if ! grep -q '^LAB_SAAS_SERVICE_PASSWORD=..*' "$BASE/kotlin.env"; then
    echo "ERROR: $BASE/kotlin.env LAB_SAAS_SERVICE_PASSWORD missing/empty — 去 GitHub Secrets 补 LAB_SAAS_SERVICE_PASSWORD 后重跑" >&2
    exit 1
  fi
fi

echo "→ image: $IMAGE"
echo "→ docker login"
printf '%s' "$PASSWORD" | docker login -u "$USERNAME" --password-stdin

echo "→ docker pull"
docker pull "$IMAGE"

echo "→ docker stop & rm $CONTAINER_NAME"
docker stop "$CONTAINER_NAME" 2>/dev/null || true
docker rm "$CONTAINER_NAME" 2>/dev/null || true

echo "→ docker run"
docker run -d \
  --name "$CONTAINER_NAME" \
  --restart unless-stopped \
  -p "127.0.0.1:5209:5209" \
  --env-file "$BASE/kotlin.env" \
  "$IMAGE"

echo "→ docker image prune"
docker image prune -f

echo "→ docker ps"
docker ps --filter name="$CONTAINER_NAME"

# 健康检查: 直接 wget /health 探 200。容器死亡提前终止循环, 立刻报失败。
i=0
while [ $i -lt 120 ]; do
  if wget --tries=1 --timeout=3 -q "http://127.0.0.1:5209/health" -O /dev/null 2>/dev/null; then
    echo "→ /health 200 (host 127.0.0.1:5209) after ${i}s"
    break
  fi
  if ! docker inspect --format='{{.State.Running}}' "$CONTAINER_NAME" 2>/dev/null | grep -q true; then
    echo "→ container not running, logs:"
    docker logs --tail 30 "$CONTAINER_NAME"
    exit 1
  fi
  i=$((i+1))
  sleep 1
done

if [ $i -ge 120 ]; then
  echo "→ /health 仍未 200(120s 上限), logs:"
  docker logs --tail 30 "$CONTAINER_NAME"
  exit 1
fi

echo "→ deploy done at $(date -u)"
