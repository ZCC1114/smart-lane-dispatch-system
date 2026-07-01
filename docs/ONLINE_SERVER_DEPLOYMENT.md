# 线上服务器打包与升级部署文档

本文档用于现场线上服务器的业务升级部署。目标是只升级 `server`、`web` 两个业务镜像和项目文件，不重置 MySQL、Redis、MQTT 等持久化数据。

适用场景：

- 线上服务器已经部署过本系统。
- 只更新后端、前端业务代码。
- 本地有网机器构建 Docker 镜像，打包后上传到服务器。
- 服务器上 Docker 已安装，但普通用户可能没有 Docker 权限，需要 `sudo`。

不适用场景：

- 全新服务器初始化。
- 需要重装 Docker、MySQL、Redis、MQTT、Nginx。
- 需要清空数据库或重建 Docker volume。

生产环境禁止执行：

```bash
sudo docker compose down -v
```

`down -v` 会删除 MySQL、Redis、MQTT 等 volume，导致业务数据丢失。

## 1. 目录与变量约定

本系统线上升级统一使用下面变量。后续命令先替换 `VERSION`。

```bash
VERSION="79245d4-202606162259"
PROJECT_NAME="smart-lane-dispatch-system"
UPGRADE_DIR="/home/supervisor/smart-lane-onsite-upgrade-${VERSION}"
APP_DIR="/home/supervisor/smart-lane-dispatch-system"
IMAGE_TAR="${UPGRADE_DIR}/images/smart-lane-business-images-amd64-${VERSION}.tar"
PROJECT_TAR="${UPGRADE_DIR}/project/smart-lane-dispatch-system-${VERSION}.tar.gz"
```

说明：

- `UPGRADE_DIR` 是上传到服务器的离线升级包目录。
- `APP_DIR` 是服务器当前正在运行的项目目录。
- 现场实际目录以服务器 `ls /home/supervisor` 结果为准，不要想当然使用 `/opt/...`。
- 本次现场实际项目目录是 `/home/supervisor/smart-lane-dispatch-system`。

## 2. 本地打包前检查

进入本地项目根目录：

```bash
cd /Users/daoheng/Documents/git-workspace/smart-lane-dispatch-system
```

确认 Docker 已启动：

```bash
docker info
```

如果出现类似错误：

```text
Cannot connect to the Docker daemon
```

说明 Docker Desktop 没启动或 Docker daemon 异常。先启动 Docker Desktop，再重新执行打包。

如果本地是 Apple Silicon Mac，线上服务器通常是 x86_64/amd64，必须指定 amd64：

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

准备 `.env`：

```bash
test -f .env || cp .env.example .env
```

检查镜像源和访问域名配置：

```bash
grep -E '^(APP_PUBLIC_HOST|APP_CORS_ALLOWED_ORIGINS|APP_CORS_ALLOWED_ORIGIN_PATTERNS|DOCKER_IMAGE_REGISTRY)=' .env
```

如果现场既有内网访问，也有公网或临时路由器访问，必须同时把所有入口写入 `APP_CORS_ALLOWED_ORIGINS` 和 `APP_CORS_ALLOWED_ORIGIN_PATTERNS`。

示例：

```dotenv
APP_PUBLIC_HOST=139.224.203.95
APP_CORS_ALLOWED_ORIGINS=http://139.224.203.95:3002,http://172.17.2.10:3002,http://localhost:3002,http://127.0.0.1:3002
APP_CORS_ALLOWED_ORIGIN_PATTERNS=http://139.224.203.95:3002,http://172.17.2.10:3002,http://localhost:*,http://127.0.0.1:*
DOCKER_IMAGE_REGISTRY=docker.m.daocloud.io/library
```

## 3. 本地构建 Docker 镜像

构建业务镜像：

```bash
docker compose -p smart-lane-dispatch-system build server web
```

确认两个镜像都是 `amd64`：

```bash
docker image inspect smart-lane-dispatch-system-server:latest --format '{{.Architecture}}'
docker image inspect smart-lane-dispatch-system-web:latest --format '{{.Architecture}}'
```

正常输出：

```text
amd64
amd64
```

如果输出 `arm64`，说明没有正确设置：

```bash
export DOCKER_DEFAULT_PLATFORM=linux/amd64
```

需要重新构建镜像。

### 3.1 基础镜像下载 EOF 处理

如果构建前端时出现类似错误：

```text
short read: unexpected EOF
failed to compute cache key
```

通常是基础镜像下载中断，不是代码错误。可以单独重试拉取基础镜像：

```bash
REGISTRY="$(grep '^DOCKER_IMAGE_REGISTRY=' .env | cut -d= -f2-)"
: "${REGISTRY:=docker.m.daocloud.io/library}"

docker pull --platform linux/amd64 "${REGISTRY}/node:22-slim"
docker compose -p smart-lane-dispatch-system build web
```

如果 `npm ci` 很久没有输出，先不要中断。跨架构构建 amd64 镜像时，前端依赖安装可能明显变慢。

`npm audit` 漏洞提示不影响镜像构建，不能在部署窗口临时执行 `npm audit fix --force`，否则可能引入依赖破坏性升级。

## 4. 本地导出离线升级包

导出镜像：

```bash
docker save -o "$PKG_DIR/images/smart-lane-business-images-amd64-${VERSION}.tar" \
  smart-lane-dispatch-system-server:latest \
  smart-lane-dispatch-system-web:latest
```

打包项目文件。项目包不包含 `.env`，不会覆盖现场配置。

macOS 打包时建议加 `COPYFILE_DISABLE=1` 和 `--no-xattrs`，减少服务器解压时的 Mac 扩展属性提示：

```bash
export COPYFILE_DISABLE=1

tar --no-xattrs \
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

如果当前系统的 `tar` 不支持 `--no-xattrs`，去掉该参数再执行。服务器解压时出现 `LIBARCHIVE.xattr.com.apple.provenance` 只是提示，不是失败。

保存本地 `.env` 作为参考，不用于直接覆盖线上 `.env`：

```bash
cp .env "$PKG_DIR/env/onsite.env"
```

## 5. 正确生成 SHA256SUMS

必须用下面方式生成校验文件：

```bash
(
  cd "$PKG_DIR"
  rm -f SHA256SUMS
  find images project env -type f | sort | while IFS= read -r file; do
    if command -v sha256sum >/dev/null 2>&1; then
      sha256sum "$file"
    else
      shasum -a 256 "$file"
    fi
  done > SHA256SUMS
)
```

生成后必须检查内容：

```bash
cat "$PKG_DIR/SHA256SUMS"
```

正确内容应类似：

```text
2dec...  images/smart-lane-business-images-amd64-79245d4-202606162259.tar
abcd...  project/smart-lane-dispatch-system-79245d4-202606162259.tar.gz
ef01...  env/onsite.env
```

错误内容示例：

```text
4936...  -
```

如果看到最后是 `-`，说明生成方式错了。原因通常是执行了下面这种错误命令：

```bash
find images project env -type f | sort | shasum -a 256 > SHA256SUMS
```

这会把文件名列表当作标准输入计算 hash，而不是计算文件内容。服务器执行 `sha256sum -c SHA256SUMS` 时会一直等待标准输入，看起来像“没反应”。

本地查看最终包：

```bash
find "$PKG_DIR" -maxdepth 3 -type f -print
du -sh "$PKG_DIR"
```

## 6. 上传升级包到服务器

本地执行：

```bash
scp -P 6000 -r "$PKG_DIR" supervisor@139.224.203.95:/home/supervisor/
```

服务器登录：

```bash
ssh -p 6000 supervisor@139.224.203.95
```

## 7. 服务器校验升级包

服务器执行：

```bash
VERSION="79245d4-202606162259"
UPGRADE_DIR="/home/supervisor/smart-lane-onsite-upgrade-${VERSION}"
cd "$UPGRADE_DIR"

sha256sum -c SHA256SUMS
```

正常输出应包含：

```text
images/smart-lane-business-images-amd64-79245d4-202606162259.tar: OK
project/smart-lane-dispatch-system-79245d4-202606162259.tar.gz: OK
env/onsite.env: OK
```

如果 `sha256sum -c SHA256SUMS` 没有任何输出，先看文件内容：

```bash
cat SHA256SUMS
```

如果内容是：

```text
xxxx  -
```

不要继续等，按下面方式重新生成：

```bash
rm -f SHA256SUMS
sha256sum images/* project/* env/* > SHA256SUMS
sha256sum -c SHA256SUMS
```

注意：现场重新生成的校验文件只能证明服务器当前文件可读，不能证明上传前后完全一致。严格做法是在本地生成正确的 `SHA256SUMS` 后一起上传。

## 8. 服务器加载 Docker 镜像

普通 `supervisor` 用户可能没有 Docker socket 权限。所有 Docker 命令统一加 `sudo`。

加载镜像：

```bash
sudo docker load -i images/smart-lane-business-images-amd64-${VERSION}.tar
```

如果不加 `sudo`，可能出现：

```text
permission denied while trying to connect to the Docker API at unix:///var/run/docker.sock
```

确认镜像：

```bash
sudo docker images | grep smart-lane-dispatch-system
```

确认架构：

```bash
sudo docker image inspect smart-lane-dispatch-system-server:latest --format '{{.Architecture}}'
sudo docker image inspect smart-lane-dispatch-system-web:latest --format '{{.Architecture}}'
```

正常输出：

```text
amd64
amd64
```

## 9. 服务器备份现场配置和数据库

进入项目目录：

```bash
APP_DIR="/home/supervisor/smart-lane-dispatch-system"
PROJECT_NAME="smart-lane-dispatch-system"
cd "$APP_DIR"
```

确认容器当前状态：

```bash
sudo docker compose -p "$PROJECT_NAME" ps
```

备份 `.env` 和数据库：

```bash
set -o pipefail

sudo mkdir -p backups
sudo cp .env "backups/.env.before-${VERSION}-$(date +%F_%H%M%S)"

set -a
source .env
set +a

DB_NAME="${MYSQL_DATABASE:-smart_lane_dispatch}"

sudo docker compose -p "$PROJECT_NAME" exec -T mysql \
  mysqldump -uroot -p"$MYSQL_ROOT_PASSWORD" "$DB_NAME" \
  | sudo tee "backups/mysql-before-${VERSION}-$(date +%F_%H%M%S).sql" >/dev/null
```

出现下面警告可以忽略：

```text
mysqldump: [Warning] Using a password on the command line interface can be insecure.
```

如果 `mysqldump` 报错或没有生成 SQL 文件，立即停止部署，不要覆盖项目文件。

## 10. 覆盖项目文件

使用 `sudo tar` 解压覆盖。不要使用不带 `sudo` 的 `tar`。

```bash
sudo tar --overwrite --no-same-owner --no-same-permissions \
  -xzf "/home/supervisor/smart-lane-onsite-upgrade-${VERSION}/project/smart-lane-dispatch-system-${VERSION}.tar.gz" \
  -C "$APP_DIR"
```

如果不加 `sudo`，可能出现大量错误：

```text
tar: xxx: Cannot open: File exists
tar: xxx: Cannot utime: Operation not permitted
tar: Exiting with failure status due to previous errors
```

如果只出现下面提示，可以忽略：

```text
tar: Ignoring unknown extended header keyword 'LIBARCHIVE.xattr.com.apple.provenance'
tar: Ignoring unknown extended header keyword 'LIBARCHIVE.xattr.com.apple.quarantine'
tar: Ignoring unknown extended header keyword 'LIBARCHIVE.xattr.com.apple.macl'
```

这些是 macOS 扩展属性，通常不影响文件覆盖。

解压后修正 Mosquitto 密码文件权限：

```bash
sudo chmod 644 deploy/mosquitto/password_file
```

项目包不包含 `.env`。如果这次需要改线上配置，只能人工对比合并：

```bash
diff -u .env "/home/supervisor/smart-lane-onsite-upgrade-${VERSION}/env/onsite.env" || true
```

不要直接执行：

```bash
cp /home/supervisor/smart-lane-onsite-upgrade-${VERSION}/env/onsite.env .env
```

直接覆盖 `.env` 可能导致数据库密码、JWT 密钥、设备 IP、MQTT 密码等现场配置丢失。

## 11. 检查 Compose 配置

```bash
cd "$APP_DIR"
sudo docker compose -p "$PROJECT_NAME" config >/tmp/smart-lane-compose-check.yaml
```

无输出表示 Compose 配置基本可解析。

如果报 `.env`、端口、volume、文件路径错误，先修正后再重启容器。

## 12. 重启业务容器

只重启业务容器：

```bash
sudo docker compose -p "$PROJECT_NAME" up -d --no-build --force-recreate --no-deps server web
```

说明：

- `--no-build` 表示服务器不重新构建，直接使用刚才 `docker load` 导入的镜像。
- `--no-deps` 表示不重启 MySQL、Redis、MQTT、Nginx。
- 本次只升级后端和前端时，不需要重启数据库。

如果本次修改了 `deploy/nginx`、`deploy/mosquitto` 或对应 `.env` 配置，再单独重启相关服务：

```bash
sudo docker compose -p "$PROJECT_NAME" up -d --no-build --force-recreate --no-deps nginx
sudo docker compose -p "$PROJECT_NAME" up -d --no-build --force-recreate --no-deps mqtt
```

## 13. 部署后检查

查看容器状态：

```bash
sudo docker compose -p "$PROJECT_NAME" ps
```

确认 `server`、`web` 为 `Up`。

查看日志：

```bash
sudo docker compose -p "$PROJECT_NAME" logs --tail=100 server
sudo docker compose -p "$PROJECT_NAME" logs --tail=100 web
```

健康检查：

```bash
curl -f http://127.0.0.1:3002/actuator/health
curl -I http://127.0.0.1:3002/
```

后端健康检查正常时通常返回：

```json
{"status":"UP"}
```

页面检查正常时应返回 `HTTP/1.1 200`、`HTTP/1.1 302` 或同类成功响应。

浏览器访问：

```text
http://139.224.203.95:3002/
```

现场功能检查：

- 登录是否正常。
- 车辆流水列表是否正常刷新。
- 车辆流水列表是否展示非白名单、未进场、走错车道等告警类型。
- 大屏是否正常刷新和高亮展示。
- 入口灯、出口灯控制是否正常。
- 如果配置了关闭车道，关闭车道不能参与自动分配，也不能手动打开出入口灯。

## 14. 常见问题与处理

### 14.1 `sha256sum -c SHA256SUMS` 没反应

先执行：

```bash
cat SHA256SUMS
```

如果看到：

```text
xxxx  -
```

说明校验文件生成错了，`sha256sum -c` 正在等待标准输入。重新生成：

```bash
rm -f SHA256SUMS
sha256sum images/* project/* env/* > SHA256SUMS
sha256sum -c SHA256SUMS
```

### 14.2 `docker load` 权限不足

错误：

```text
permission denied while trying to connect to the Docker API at unix:///var/run/docker.sock
```

处理：

```bash
sudo docker load -i images/smart-lane-business-images-amd64-${VERSION}.tar
```

后续所有 `docker` 和 `docker compose` 命令都加 `sudo`。

### 14.3 解压项目包权限不足

错误：

```text
Cannot open: File exists
Cannot utime: Operation not permitted
Exiting with failure status
```

处理：

```bash
sudo tar --overwrite --no-same-owner --no-same-permissions \
  -xzf "$PROJECT_TAR" \
  -C "$APP_DIR"
```

### 14.4 解压出现 `LIBARCHIVE.xattr` 提示

提示：

```text
Ignoring unknown extended header keyword 'LIBARCHIVE.xattr.com.apple.provenance'
```

处理：通常忽略即可。下次本地打包时使用：

```bash
export COPYFILE_DISABLE=1
tar --no-xattrs ...
```

### 14.5 `mysqldump` 密码警告

提示：

```text
mysqldump: [Warning] Using a password on the command line interface can be insecure.
```

处理：可以忽略。只要命令退出成功并生成 `.sql` 文件即可。

### 14.6 前端构建卡在 `npm ci`

处理：先等待。Apple Silicon 上构建 amd64 镜像时，`npm ci` 可能静默数分钟。

如果最终报网络错误，优先重试基础镜像和构建：

```bash
docker pull --platform linux/amd64 docker.m.daocloud.io/library/node:22-slim
docker compose -p smart-lane-dispatch-system build web
```

### 14.7 页面能打开但接口或登录异常

优先检查服务器 `.env`：

```bash
grep -E '^(APP_PUBLIC_HOST|APP_CORS_ALLOWED_ORIGINS|APP_CORS_ALLOWED_ORIGIN_PATTERNS)=' .env
```

如果现场通过公网、路由器、FRP 或内网多个地址访问，需要把所有地址加入 CORS 配置，并重启后端：

```bash
sudo docker compose -p smart-lane-dispatch-system up -d --no-build --force-recreate --no-deps server
```

## 15. 回滚思路

如果新版本启动失败：

1. 先查看日志定位是否是配置问题。
2. 如果只是 `.env` 问题，恢复备份 `.env` 后重启 `server web`。
3. 如果需要回滚代码，重新加载上一个版本镜像包并解压上一个版本项目包。
4. 如果数据库被错误修改，再根据部署前备份 SQL 评估恢复。

恢复 `.env` 示例：

```bash
cd /home/supervisor/smart-lane-dispatch-system
sudo cp backups/.env.before-79245d4-YYYY-MM-DD_HHMMSS .env
sudo docker compose -p smart-lane-dispatch-system up -d --no-build --force-recreate --no-deps server web
```

恢复数据库属于高风险操作，执行前必须先确认影响范围，不要直接覆盖线上库。

## 16. 本次部署踩坑记录

本次升级实际遇到的问题和处理结果：

- `SHA256SUMS` 生成错误，内容为 `hash  -`，导致服务器 `sha256sum -c SHA256SUMS` 一直等待标准输入。
- `docker load` 未加 `sudo`，普通用户无法访问 `/var/run/docker.sock`。
- 解压项目包未加 `sudo`，大量文件因权限不足无法覆盖。
- macOS 打包带入扩展属性，服务器解压出现大量 `LIBARCHIVE.xattr` 提示；该提示可忽略，但建议下次打包加 `COPYFILE_DISABLE=1` 和 `--no-xattrs`。
- 本地 Docker Desktop 曾经未正常连接 Docker daemon，需先确认 `docker info` 正常。
- 前端构建拉取 `node:22-slim` amd64 基础层时出现 `unexpected EOF`，重试 `docker pull --platform linux/amd64 ...` 后解决。
- 前端 `npm ci` 阶段静默时间较长，属于跨架构构建常见现象，不能过早中断。
- 现场项目目录实际是 `/home/supervisor/smart-lane-dispatch-system`，部署文档不能默认写死 `/opt/...`。

下次部署时，优先按照本文档执行。
