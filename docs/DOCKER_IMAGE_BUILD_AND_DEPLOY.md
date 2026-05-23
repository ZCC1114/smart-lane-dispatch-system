# 前后端 Docker 镜像打包与服务器部署流程

本文档用于把当前代码打包成 `server`、`web` 两个业务镜像，并部署到现场服务器。

适用场景：

- 已有服务器环境，只升级前后端业务代码。
- 服务器不能直接联网拉镜像，需要在有网电脑打包后上传。
- 服务器上已有 `/opt/smart-lane/smart-lane-dispatch-system` 项目目录和 Docker Compose 环境。

不适用场景：

- 全新服务器从零安装 Docker、Compose、MySQL、Redis、MQTT、Nginx。全新安装优先看 `docs/UBUNTU_25_10_DOCKER_DEPLOY.md`。

## 1. 关键约定

项目使用 `compose.yaml` 管理服务：

| 服务 | 容器名 | 说明 |
| --- | --- | --- |
| `server` | `smart-lane-server` | Spring Boot 后端，端口 8080，仅容器内暴露 |
| `web` | `smart-lane-web` | Next.js 前端，端口 3000，仅容器内暴露 |
| `nginx` | `smart-lane-nginx` | 对外 HTTP 入口，默认服务器端口 3002 |
| `mysql` | `smart-lane-mysql` | 业务数据库 |
| `redis` | `smart-lane-redis` | 缓存 |
| `mqtt` | `smart-lane-mqtt` | Mosquitto，1883 和 9001 |

`server`、`web` 由源码构建，默认镜像名由 Compose 项目名决定：

```text
smart-lane-dispatch-system-server:latest
smart-lane-dispatch-system-web:latest
```

本文命令统一使用 Compose 项目名：

```bash
smart-lane-dispatch-system
```

## 2. 有网电脑打包镜像

在有网电脑进入项目根目录：

```bash
cd /path/to/smart-lane-dispatch-system
git status -sb
```

建议先确认代码已经提交，避免打包了临时改动：

```bash
git log -1 --oneline
```

如果构建电脑是 Apple Silicon Mac，而服务器是常见 x86_64/amd64，必须指定 amd64：

```bash
export DOCKER_DEFAULT_PLATFORM=linux/amd64
```

准备版本号和输出目录：

```bash
VERSION="$(git rev-parse --short HEAD)-$(date +%Y%m%d%H%M)"
PKG_DIR="deploy-artifacts/smart-lane-onsite-upgrade-${VERSION}"

rm -rf "$PKG_DIR"
mkdir -p "$PKG_DIR/images" "$PKG_DIR/project" "$PKG_DIR/env"
```

准备 `.env`。如果当前目录没有 `.env`，先复制样例：

```bash
test -f .env || cp .env.example .env
```

现场服务器是 `172.17.2.10:3002` 时，至少确认这些值：

```bash
grep -E '^(APP_PUBLIC_HOST|APP_CORS_ALLOWED_ORIGINS|DOCKER_IMAGE_REGISTRY)=' .env
```

推荐值：

```dotenv
APP_PUBLIC_HOST=172.17.2.10
APP_CORS_ALLOWED_ORIGINS=http://172.17.2.10:3002
DOCKER_IMAGE_REGISTRY=docker.m.daocloud.io/library
```

构建前后端业务镜像：

```bash
docker compose -p smart-lane-dispatch-system build server web
```

确认镜像架构。服务器是 amd64 时应输出 `amd64`：

```bash
docker image inspect smart-lane-dispatch-system-server:latest --format '{{.Architecture}}'
docker image inspect smart-lane-dispatch-system-web:latest --format '{{.Architecture}}'
```

导出前后端业务镜像：

```bash
docker save -o "$PKG_DIR/images/smart-lane-business-images-amd64-${VERSION}.tar" \
  smart-lane-dispatch-system-server:latest \
  smart-lane-dispatch-system-web:latest
```

打包项目文件。这个包不包含现场 `.env`，不会直接覆盖服务器配置：

```bash
tar \
  --exclude='.git' \
  --exclude='.env' \
  --exclude='.DS_Store' \
  --exclude='deploy-artifacts' \
  --exclude='smart-lane-offline' \
  --exclude='run-logs' \
  --exclude='server/target' \
  --exclude='web/node_modules' \
  --exclude='web/.next' \
  -czf "$PKG_DIR/project/smart-lane-dispatch-system-${VERSION}.tar.gz" \
  compose.yaml deploy docs scripts server web README.md .env.example .nvmrc
```

保存一份现场配置参考文件：

```bash
cp .env "$PKG_DIR/env/onsite.env"
```

生成校验文件：

```bash
(
  cd "$PKG_DIR"
  if command -v sha256sum >/dev/null 2>&1; then
    find images project env -type f | sort | xargs sha256sum > SHA256SUMS
  else
    find images project env -type f | sort | xargs shasum -a 256 > SHA256SUMS
  fi
)
```

查看打包结果：

```bash
find "$PKG_DIR" -maxdepth 3 -type f -print
du -sh "$PKG_DIR"
```

典型输出：

```text
deploy-artifacts/smart-lane-onsite-upgrade-xxxx-yyyymmddHHMM/
  SHA256SUMS
  images/smart-lane-business-images-amd64-xxxx-yyyymmddHHMM.tar
  project/smart-lane-dispatch-system-xxxx-yyyymmddHHMM.tar.gz
  env/onsite.env
```

## 3. 上传到服务器

把整个 `$PKG_DIR` 上传到服务器，例如：

```text
/home/supervisor/smart-lane-upgrade-${VERSION}
```

服务器上应看到：

```text
/home/supervisor/smart-lane-upgrade-${VERSION}/
  SHA256SUMS
  images/...
  project/...
  env/onsite.env
```

## 4. 服务器升级部署

以下命令在服务器 SSH 终端执行。

进入上传目录并校验文件：

```bash
cd /home/supervisor/smart-lane-upgrade-你的版本号
sha256sum -c SHA256SUMS
```

全部显示 `OK` 后继续。

加载前后端镜像：

```bash
sudo docker load -i images/smart-lane-business-images-amd64-你的版本号.tar
sudo docker image ls | grep smart-lane-dispatch-system
```

进入服务器项目目录：

```bash
APP_DIR=/opt/smart-lane/smart-lane-dispatch-system
cd "$APP_DIR"
```

升级前备份数据库和 `.env`：

```bash
set -a
source .env
set +a

mkdir -p backups
sudo docker compose -p smart-lane-dispatch-system exec -T mysql \
  mysqldump -uroot -p"$MYSQL_ROOT_PASSWORD" smart_lane_dispatch \
  > backups/pre_upgrade_$(date +%F_%H%M%S).sql

sudo cp .env backups/.env.$(date +%F_%H%M%S)
```

覆盖项目文件。这个项目包不包含 `.env`：

```bash
sudo tar -xzf /home/supervisor/smart-lane-upgrade-你的版本号/project/smart-lane-dispatch-system-你的版本号.tar.gz -C "$APP_DIR"
sudo chmod 644 deploy/mosquitto/password_file
```

如果这次需要同步现场参数，可以参考上传包里的 `env/onsite.env`，手动对比合并到服务器 `.env`。不要直接覆盖服务器 `.env`，避免丢失数据库密码、JWT 密钥等现场配置。

检查 Compose 配置：

```bash
sudo docker compose -p smart-lane-dispatch-system config >/tmp/smart-lane-compose-check.yaml
```

只重建前后端服务：

```bash
sudo docker compose -p smart-lane-dispatch-system up -d --no-build --force-recreate --no-deps server
sudo docker compose -p smart-lane-dispatch-system up -d --no-build --force-recreate --no-deps web
sudo docker compose -p smart-lane-dispatch-system ps
```

如果这次同时改了 `compose.yaml`、`deploy/nginx/default.conf`、MQTT 配置或 `.env` 中 Nginx/MQTT 相关变量，再按需重建：

```bash
sudo docker compose -p smart-lane-dispatch-system up -d --no-build --force-recreate --no-deps nginx
sudo docker compose -p smart-lane-dispatch-system up -d --no-build --force-recreate --no-deps mqtt
```

不要执行：

```bash
docker compose down -v
```

`down -v` 会删除 MySQL、Redis、MQTT 的 Docker volume，可能清空业务数据。

## 5. 验证

查看服务状态：

```bash
sudo docker compose -p smart-lane-dispatch-system ps
```

检查健康接口：

```bash
curl -f http://127.0.0.1:3002/actuator/health
curl -I http://127.0.0.1:3002/
```

查看日志：

```bash
sudo docker compose -p smart-lane-dispatch-system logs --tail=100 server
sudo docker compose -p smart-lane-dispatch-system logs --tail=100 web
sudo docker compose -p smart-lane-dispatch-system logs --tail=100 nginx
sudo docker compose -p smart-lane-dispatch-system logs --tail=100 mqtt
```

浏览器访问：

```text
http://172.17.2.10:3002/
http://172.17.2.10:3002/screen
http://172.17.2.10:3002/camera-test
```

MQTT 联调时可在服务器上监听：

```bash
sudo docker compose -p smart-lane-dispatch-system exec mqtt \
  mosquitto_sub -h 127.0.0.1 -p 1883 -u jcadmin -P 'jcadmin@12345' -t '#' -v
```

## 6. 回滚

如果升级后需要回退：

1. 找到上一个可用版本的镜像 tar 和项目 tar。
2. 重新执行 `docker load`。
3. 重新解压上一个项目包到 `$APP_DIR`。
4. 如果 `.env` 被修改过，从 `backups/.env.*` 恢复。
5. 重新创建 `server`、`web`。

示例：

```bash
APP_DIR=/opt/smart-lane/smart-lane-dispatch-system
cd "$APP_DIR"

sudo cp backups/.env.你的备份时间 .env
sudo docker load -i /home/supervisor/old-package/images/smart-lane-business-images-amd64-old.tar
sudo tar -xzf /home/supervisor/old-package/project/smart-lane-dispatch-system-old.tar.gz -C "$APP_DIR"

sudo docker compose -p smart-lane-dispatch-system up -d --no-build --force-recreate --no-deps server
sudo docker compose -p smart-lane-dispatch-system up -d --no-build --force-recreate --no-deps web
sudo docker compose -p smart-lane-dispatch-system ps
```

数据库一般不随代码回滚自动恢复。只有确认新版本写入了不可兼容数据时，才从 `backups/pre_upgrade_*.sql` 恢复数据库。

## 7. 常见问题

### 7.1 服务器提示 no matching manifest 或 exec format error

通常是镜像架构不对。构建电脑是 Mac M 系列时，要重新打包前执行：

```bash
export DOCKER_DEFAULT_PLATFORM=linux/amd64
docker compose -p smart-lane-dispatch-system build server web
```

### 7.2 修改了前端环境变量但页面不生效

`NEXT_PUBLIC_*` 变量在 `web` 镜像构建时写入前端包。修改这类变量后，需要重新构建并部署 `web` 镜像。

常见相关变量：

```text
APP_PUBLIC_HOST
APP_DEVICE_MQTT_USERNAME
APP_DEVICE_MQTT_PASSWORD
```

### 7.3 `docker compose up --no-build` 找不到 server/web 镜像

确认加载的镜像名是：

```bash
sudo docker image ls | grep smart-lane-dispatch-system
```

应看到：

```text
smart-lane-dispatch-system-server
smart-lane-dispatch-system-web
```

如果名字不一致，需要用相同 Compose 项目名重新构建：

```bash
docker compose -p smart-lane-dispatch-system build server web
```

### 7.4 Mosquitto 启动失败或认证失败

确认密码文件权限：

```bash
sudo chmod 644 deploy/mosquitto/password_file
sudo docker compose -p smart-lane-dispatch-system up -d --no-build --force-recreate --no-deps mqtt
```

### 7.5 只改后端代码，可以只重建 server 吗

可以。打包时仍建议导出 server/web 两个镜像，部署时可只重建后端：

```bash
sudo docker compose -p smart-lane-dispatch-system up -d --no-build --force-recreate --no-deps server
```

只改前端代码时同理只重建 `web`。
