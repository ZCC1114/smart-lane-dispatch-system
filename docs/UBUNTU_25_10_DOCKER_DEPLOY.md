# Ubuntu 25.10 Docker 生产部署手册

本文面向一台 Ubuntu 25.10 生产服务器，使用 Docker Compose 部署出租车蓄车道调度系统的完整运行栈:

- `nginx`: 统一 HTTP 入口，默认对外端口 `3002`
- `web`: Next.js 前端页面
- `server`: Spring Boot 后端、WebSocket、设备网关
- `mqtt`: Eclipse Mosquitto Broker，供摄像头、CX 继电器设备和后端通信
- `mysql`: 业务数据库
- `redis`: 仪表盘缓存

> Docker Engine 的 Ubuntu 安装步骤参考 Docker 官方文档: <https://docs.docker.com/engine/install/ubuntu/>。该文档已列出 Ubuntu Questing 25.10 为受支持版本。

## 1. 部署拓扑

推荐现场拓扑:

```text
现场设备网段
  |
  |-- Ubuntu 25.10 服务器，固定 IP: 172.17.2.10
  |     |-- 3002/tcp: Web 前端 + API + WebSocket，给浏览器访问
  |     |-- 1883/tcp: MQTT Broker，给摄像头和 CX 设备连接
  |     |-- 9001/tcp: MQTT WebSocket，硬件调试页需要时开放
  |
  |-- 车牌/计数报警摄像头
  |     |-- MQTT Broker Host: 172.17.2.10
  |     |-- MQTT Broker Port: 1883
  |
  |-- 入口 CX/DIDO 设备 DIDO-ENTRY-01
  |     |-- MQTT Broker Host: 172.17.2.10
  |     |-- MQTT Broker Port: 1883
  |     |-- DO1-DO11: 1-11 号车道入口灯
  |
  |-- 出口 CX/DIDO 设备 DIDO-EXIT-01
  |     |-- MQTT Broker Host: 172.17.2.10
  |     |-- MQTT Broker Port: 1883
  |     |-- DO1-DO11: 1-11 号车道出口灯
  |     |-- IN1-IN11: 1-11 号车道出口地感
```

本文按已确认的现场生产网段 `172.17.2.0/24` 编写，服务器固定 IP 为 `172.17.2.10`。

### 1.1 已确认现场网络

```text
网段: 172.17.2.0/24
掩码: 255.255.255.0
网关: 172.17.2.1
服务器 IP: 172.17.2.10
```

| IP | 设备 | 本系统是否对接 | 当前结论 / 待补充 |
| --- | --- | --- | --- |
| `172.17.2.10` | Ubuntu 服务器 | 是 | Web/API: `3002`; MQTT Broker: `1883`; MQTT WebSocket: `9001` |
| `172.17.2.20` | 硬盘录像机 | 否 | 只做视频系统内部使用，不接入本系统 |
| `172.17.2.21-23` | 大华摄像机 | 否 | 只做视频系统内部使用，不接入本系统 |
| `172.17.2.30` | 大华对讲终端 | 否 | 原始清单写作 `173.172.17.2.30`，按笔误处理；对讲不接入本系统 |
| `172.17.2.31-33` | 大华对讲 | 否 | 对讲系统内部使用，不接入本系统 |
| `172.17.2.40` | 车牌识别一体机 | 是 | 与 `172.17.2.90` 为一套总入口 MF 设备；现场端应为一体机；已确认 `SN=00E02721A3A7`、`groupId=9QHZNII`、`deviceNo=22K5000202407828` |
| `172.17.2.51-61` | 1-11 号车道入口摄像机 | 是 | `.51=L01`、`.52=L02`，依次到 `.61=L11`; MQTT `devId` 已按现场 UID/MAC 配置 |
| `172.17.2.70` | LED 显示屏 | 是 | 需要自动显示引导牌内容；已按 Bx6E、1920x960、2列x6行配置 |
| `172.17.2.80-81` | DIDO 模块 | 是 | 走 MQTT 连接服务器 `172.17.2.10:1883`; 测试页面已调通过；`.80/.81` 分别对应入口/出口待现场最终确认 |
| `172.17.2.90` | 车牌识别终端 | 是 | 与 `172.17.2.40` 为一套总入口 MF 设备；系统按 MF 报文里的 `SN/groupId/deviceNo` 匹配 |

现场继续联调时，优先补齐:

- DIDO: 入口/出口真实设备 ID 和 Topic，如果不是 `DIDO-ENTRY-01` / `DIDO-EXIT-01`
- 总入口 MF: 已按测试上报数据配置 `APP_DEVICE_PARKING_MF_YARD_ENTRY_SN`、`APP_DEVICE_PARKING_MF_YARD_ENTRY_GROUP_ID`、`APP_DEVICE_PARKING_MF_YARD_ENTRY_DEVICE_NO`，现场只需核对实际上报是否仍一致
- 车道入口摄像机: `APP_DEVICE_L01_CAMERA_DEV_ID` 到 `APP_DEVICE_L11_CAMERA_DEV_ID` 已按现场表配置，现场只需核对 MQTT 实际上报 devId 是否一致
- LED: 已按 `172.17.2.70:5005`、`Bx6E`、`1920x960`、`2列x6行` 配置，现场只需联通性和显示效果确认

## 2. 服务器准备

### 2.1 基础要求

建议配置:

- CPU: 4 核或以上
- 内存: 8 GB 或以上
- 磁盘: 100 GB 或以上，建议 SSD
- 系统: Ubuntu Server 25.10 64-bit
- 网络: 与摄像头、CX 继电器设备处于同一局域网或路由可达

### 2.2 设置时区

```bash
sudo timedatectl set-timezone Asia/Shanghai
timedatectl
```

### 2.3 设置固定 IP

先确认网卡名:

```bash
ip addr
```

编辑 Netplan 配置，文件名以现场实际为准:

```bash
sudo nano /etc/netplan/01-netcfg.yaml
```

示例:

```yaml
network:
  version: 2
  renderer: networkd
  ethernets:
    enp3s0:
      dhcp4: false
      addresses:
        - 172.17.2.10/24
      routes:
        - to: default
          via: 172.17.2.1
      nameservers:
        addresses:
          - 223.5.5.5
          - 8.8.8.8
```

应用配置:

```bash
sudo netplan apply
ip addr show enp3s0
ping -c 3 172.17.2.1
```

如果服务器只在内网运行、没有外网 DNS，`nameservers` 可改成现场 DNS。

### 2.4 避免 Docker 网段冲突

现场生产网段为 `172.17.2.0/24` 时，需要避开 Docker 默认的 `172.17.0.0/16`。否则 Linux 服务器上可能出现 `docker0` 路由覆盖现场设备 IP，导致容器或宿主机访问 `172.17.2.x` 设备异常。

本项目的 `compose.yaml` 已固定 Compose 内部网络为 `10.88.0.0/16`。生产服务器还建议同步调整 Docker daemon 默认桥接网段:

```bash
sudo mkdir -p /etc/docker
sudo tee /etc/docker/daemon.json >/dev/null <<'JSON'
{
  "bip": "10.87.0.1/24",
  "default-address-pools": [
    {
      "base": "10.89.0.0/16",
      "size": 24
    }
  ]
}
JSON

sudo systemctl restart docker
ip route | grep -E 'docker|10\\.87|10\\.88|10\\.89|172\\.17' || true
```

如果服务器上已经启动过本项目，调整后重建 Compose 网络即可，保留业务数据不要加 `-v`:

```bash
docker compose down
docker network rm smart-lane-dispatch-net 2>/dev/null || true
docker compose up -d
```

## 3. 安装 Docker Engine 和 Compose

### 3.1 服务器可联网时安装

如果服务器已有旧 Docker 包，先清理冲突包:

```bash
for pkg in docker.io docker-doc docker-compose docker-compose-v2 podman-docker containerd runc; do
  sudo apt-get remove -y "$pkg" || true
done
```

安装 Docker 官方源:

```bash
sudo apt update
sudo apt install -y ca-certificates curl
sudo install -m 0755 -d /etc/apt/keyrings
sudo curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
sudo chmod a+r /etc/apt/keyrings/docker.asc

sudo tee /etc/apt/sources.list.d/docker.sources >/dev/null <<'EOF'
Types: deb
URIs: https://download.docker.com/linux/ubuntu
Suites: questing
Components: stable
Architectures: amd64
Signed-By: /etc/apt/keyrings/docker.asc
EOF

sudo apt update
sudo apt install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
```

如果服务器是 ARM64，把 `Architectures: amd64` 改成 `arm64`。也可以用下面命令自动输出架构:

```bash
dpkg --print-architecture
```

启动 Docker:

```bash
sudo systemctl enable --now docker
docker --version
docker compose version
```

可选: 允许当前用户执行 Docker 命令:

```bash
sudo usermod -aG docker "$USER"
newgrp docker
```

生产服务器如果多人运维，建议保留 `sudo docker ...`，不要随意把普通用户加入 `docker` 组。

### 3.2 完全离线部署包准备

如果生产服务器完全不能联网，且运维电脑也不能直连现场内网，需要先在一台可以联网、可以运行 Docker 的电脑上准备离线包，再通过 U 盘拷贝到服务器。

推荐 U 盘目录结构:

```text
smart-lane-offline/
  docker-debs/
    containerd.io_2.2.3-1~ubuntu.25.10~questing_amd64.deb
    docker-buildx-plugin_0.33.0-1~ubuntu.25.10~questing_amd64.deb
    docker-ce-cli_29.4.2-2~ubuntu.25.10~questing_amd64.deb
    docker-ce_29.4.2-2~ubuntu.25.10~questing_amd64.deb
    docker-compose-plugin_5.1.3-1~ubuntu.25.10~questing_amd64.deb
  images/
    smart-lane-offline-images-amd64.tar
  project/
    smart-lane-dispatch-system.tar.gz
  checksums.txt
```

上面 Docker 安装包为 2026-05-20 核验的 Ubuntu 25.10 `questing` / `amd64` 版本。服务器如果是 ARM64，先在服务器执行 `dpkg --print-architecture` 确认架构，再从同目录的 `arm64` 路径下载对应 `_arm64.deb` 文件。

Docker 官方下载目录:

```text
Docker Ubuntu 安装文档:
https://docs.docker.com/engine/install/ubuntu/

Ubuntu 25.10 amd64 包目录:
https://download.docker.com/linux/ubuntu/dists/questing/pool/stable/amd64/

Ubuntu 25.10 arm64 包目录:
https://download.docker.com/linux/ubuntu/dists/questing/pool/stable/arm64/
```

AMD64 服务器需要下载的 5 个 `.deb` 直链:

```text
https://download.docker.com/linux/ubuntu/dists/questing/pool/stable/amd64/containerd.io_2.2.3-1~ubuntu.25.10~questing_amd64.deb
https://download.docker.com/linux/ubuntu/dists/questing/pool/stable/amd64/docker-buildx-plugin_0.33.0-1~ubuntu.25.10~questing_amd64.deb
https://download.docker.com/linux/ubuntu/dists/questing/pool/stable/amd64/docker-ce-cli_29.4.2-2~ubuntu.25.10~questing_amd64.deb
https://download.docker.com/linux/ubuntu/dists/questing/pool/stable/amd64/docker-ce_29.4.2-2~ubuntu.25.10~questing_amd64.deb
https://download.docker.com/linux/ubuntu/dists/questing/pool/stable/amd64/docker-compose-plugin_5.1.3-1~ubuntu.25.10~questing_amd64.deb
```

有网电脑如果是 macOS / Linux，可以用命令下载:

```bash
mkdir -p smart-lane-offline/docker-debs
cd smart-lane-offline/docker-debs

curl -fL -O https://download.docker.com/linux/ubuntu/dists/questing/pool/stable/amd64/containerd.io_2.2.3-1~ubuntu.25.10~questing_amd64.deb
curl -fL -O https://download.docker.com/linux/ubuntu/dists/questing/pool/stable/amd64/docker-buildx-plugin_0.33.0-1~ubuntu.25.10~questing_amd64.deb
curl -fL -O https://download.docker.com/linux/ubuntu/dists/questing/pool/stable/amd64/docker-ce-cli_29.4.2-2~ubuntu.25.10~questing_amd64.deb
curl -fL -O https://download.docker.com/linux/ubuntu/dists/questing/pool/stable/amd64/docker-ce_29.4.2-2~ubuntu.25.10~questing_amd64.deb
curl -fL -O https://download.docker.com/linux/ubuntu/dists/questing/pool/stable/amd64/docker-compose-plugin_5.1.3-1~ubuntu.25.10~questing_amd64.deb
cd -
```

本项目运行时需要导出的 Docker 镜像:

```text
docker.m.daocloud.io/library/mysql:8.4
docker.m.daocloud.io/library/redis:7.4-alpine
docker.m.daocloud.io/library/eclipse-mosquitto:2
docker.m.daocloud.io/library/nginx:1.27-alpine
smart-lane-dispatch-system-server:latest
smart-lane-dispatch-system-web:latest
```

这些镜像的拉取地址就是上面的 Docker registry 地址，可手工拉取:

```bash
docker pull docker.m.daocloud.io/library/mysql:8.4
docker pull docker.m.daocloud.io/library/redis:7.4-alpine
docker pull docker.m.daocloud.io/library/eclipse-mosquitto:2
docker pull docker.m.daocloud.io/library/nginx:1.27-alpine
```

构建 `server`、`web` 时还会在有网电脑上临时拉取下面 3 个基础镜像。它们只用于构建，不需要导出到内网服务器，除非计划在内网服务器上执行 `docker compose build`:

```text
docker.m.daocloud.io/library/maven:3.9.9-eclipse-temurin-21
docker.m.daocloud.io/library/eclipse-temurin:21-jre-jammy
docker.m.daocloud.io/library/node:23-alpine
```

如果有网电脑访问 DaoCloud 镜像代理不稳定，也可以把 `docker.m.daocloud.io/library` 替换成 `docker.io/library` 拉取官方 Docker Hub 镜像。导出的镜像名必须和生产 `.env` 里的 `DOCKER_IMAGE_REGISTRY` 一致；如果按下面命令使用 DaoCloud，生产 `.env` 保持 `DOCKER_IMAGE_REGISTRY=docker.m.daocloud.io/library`。

在有网电脑上进入项目根目录，先准备 `.env`。`APP_PUBLIC_HOST` 要写生产服务器 IP，否则前端构建出的 MQTT WebSocket 地址会不对:

```bash
cp .env.example .env
sed -i.bak 's/^APP_PUBLIC_HOST=.*/APP_PUBLIC_HOST=172.17.2.10/' .env
sed -i.bak 's/^APP_CORS_ALLOWED_ORIGINS=.*/APP_CORS_ALLOWED_ORIGINS=http:\/\/172.17.2.10:3002/' .env
sed -i.bak 's/^DOCKER_IMAGE_REGISTRY=.*/DOCKER_IMAGE_REGISTRY=docker.m.daocloud.io\/library/' .env
```

如果有网电脑是 Apple Silicon Mac，而生产服务器是常见 x86_64 / amd64，必须指定 `linux/amd64`，否则会导出 ARM 镜像，服务器无法运行:

```bash
export DOCKER_DEFAULT_PLATFORM=linux/amd64
```

拉取运行时镜像，并构建本项目 `server`、`web` 镜像:

```bash
docker compose -p smart-lane-dispatch-system pull mysql redis mqtt nginx
docker compose -p smart-lane-dispatch-system build server web
```

确认镜像架构。AMD64 服务器应显示 `amd64`:

```bash
docker image inspect docker.m.daocloud.io/library/mysql:8.4 --format '{{.Architecture}}'
docker image inspect smart-lane-dispatch-system-server:latest --format '{{.Architecture}}'
docker image inspect smart-lane-dispatch-system-web:latest --format '{{.Architecture}}'
```

导出全部运行时镜像:

```bash
mkdir -p smart-lane-offline/images

docker save -o smart-lane-offline/images/smart-lane-offline-images-amd64.tar \
  docker.m.daocloud.io/library/mysql:8.4 \
  docker.m.daocloud.io/library/redis:7.4-alpine \
  docker.m.daocloud.io/library/eclipse-mosquitto:2 \
  docker.m.daocloud.io/library/nginx:1.27-alpine \
  smart-lane-dispatch-system-server:latest \
  smart-lane-dispatch-system-web:latest
```

打包项目文件。离线服务器上不重新构建镜像，但仍需要 `compose.yaml`、`.env.example`、`deploy/`、`docs/` 等配置文件:

```bash
mkdir -p smart-lane-offline/project

tar \
  --exclude='.git' \
  --exclude='server/target' \
  --exclude='web/node_modules' \
  --exclude='web/.next' \
  --exclude='smart-lane-offline' \
  -czf smart-lane-offline/project/smart-lane-dispatch-system.tar.gz \
  .
```

生成校验文件，拷贝到 U 盘前后都可以核对:

```bash
cd smart-lane-offline

# macOS
find docker-debs images project -type f | sort | xargs shasum -a 256 > checksums.txt

# Linux 如果没有 shasum，使用:
# find docker-debs images project -type f | sort | xargs sha256sum > checksums.txt
cd -
```

注意事项:

- U 盘建议使用 exFAT、NTFS 或 ext4。`smart-lane-offline-images-amd64.tar` 可能超过 4 GB，FAT32 放不下。
- 内网服务器上不要执行 `docker compose up --build`，也不要执行 `docker compose pull`。离线启动必须使用 `--no-build`。
- 如果需要完全覆盖极简 Ubuntu 缺失依赖的情况，最好临时准备一台同版本 Ubuntu 25.10 / amd64 虚拟机，按 Docker 官方 apt 源执行 `sudo apt-get --download-only install docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin`，再把 `/var/cache/apt/archives/*.deb` 一并放入 `docker-debs/`。

### 3.3 内网服务器离线安装 Docker

把 U 盘里的 `smart-lane-offline` 拷贝到服务器本地磁盘，例如:

```bash
mkdir -p ~/smart-lane-offline
cp -a /media/$USER/<U盘名>/smart-lane-offline/. ~/smart-lane-offline/
cd ~/smart-lane-offline
sha256sum -c checksums.txt
```

如果服务器已有旧 Docker 包，先清理冲突包:

```bash
for pkg in docker.io docker-doc docker-compose docker-compose-v2 podman-docker containerd runc; do
  sudo apt-get remove -y "$pkg" || true
done
```

安装离线 `.deb` 包:

```bash
cd ~/smart-lane-offline
sudo apt install -y ./docker-debs/*.deb
```

如果 `apt install` 提示缺少依赖，说明服务器系统是极简安装，基础依赖没有随系统带上。按报错包名到 Ubuntu 25.10 官方软件源下载对应 `.deb`，放进 `docker-debs/` 后重新执行:

```bash
sudo apt install -y ./docker-debs/*.deb
```

启动 Docker:

```bash
sudo systemctl enable --now docker
sudo systemctl status docker --no-pager
docker --version
docker compose version
```

如果当前用户没有 Docker 权限，后续命令统一加 `sudo`，或按需加入 `docker` 组:

```bash
sudo usermod -aG docker "$USER"
newgrp docker
```

### 3.4 内网服务器离线启动本系统

先按第 2.4 节写入 Docker daemon 网段配置，避免 Docker 默认 `172.17.0.0/16` 与现场 `172.17.2.0/24` 冲突。然后加载离线镜像:

```bash
cd ~/smart-lane-offline
docker load -i images/smart-lane-offline-images-amd64.tar
docker image ls
```

解压项目到生产目录:

```bash
sudo mkdir -p /opt/smart-lane/smart-lane-dispatch-system
sudo chown -R "$USER":"$USER" /opt/smart-lane

tar -xzf ~/smart-lane-offline/project/smart-lane-dispatch-system.tar.gz -C /opt/smart-lane/smart-lane-dispatch-system
cd /opt/smart-lane/smart-lane-dispatch-system
```

配置 `.env`:

```bash
test -f .env || cp .env.example .env
nano .env
```

至少确认这些值:

```dotenv
APP_HTTP_PORT=3002
APP_PUBLIC_HOST=172.17.2.10
APP_CORS_ALLOWED_ORIGINS=http://172.17.2.10:3002
DOCKER_IMAGE_REGISTRY=docker.m.daocloud.io/library

APP_DEVICE_MQTT_HOST=mqtt
APP_DEVICE_MQTT_PORT=1883
APP_DEVICE_MQTT_USERNAME=jcadmin
APP_DEVICE_MQTT_PASSWORD=jcadmin@12345
```

离线启动时固定 Compose 项目名，确保使用 U 盘里导入的 `smart-lane-dispatch-system-server:latest` 和 `smart-lane-dispatch-system-web:latest`:

```bash
docker compose -p smart-lane-dispatch-system config
docker compose -p smart-lane-dispatch-system up -d --no-build
docker compose -p smart-lane-dispatch-system ps
```

检查服务:

```bash
curl -f http://127.0.0.1:3002/actuator/health
curl -I http://127.0.0.1:3002/
```

浏览器访问:

```text
http://172.17.2.10:3002
```

后续离线升级流程:

```text
1. 在有网电脑重新构建 server/web 镜像并 docker save。
2. 重新打包项目 tar.gz。
3. 用 U 盘拷到服务器。
4. 服务器执行 docker load -i images/smart-lane-offline-images-amd64.tar。
5. 覆盖 /opt/smart-lane/smart-lane-dispatch-system 中的项目文件，保留 .env。
6. 执行 docker compose -p smart-lane-dispatch-system up -d --no-build --force-recreate。
```

MySQL、Redis、Mosquitto 数据保存在 Docker volumes 中，执行上面的升级流程不会删除业务数据。不要执行 `docker compose down -v`，除非明确要清空数据库和 MQTT/Redis 数据。

## 4. 获取项目代码

示例部署目录:

```bash
sudo mkdir -p /opt/smart-lane
sudo chown -R "$USER":"$USER" /opt/smart-lane
cd /opt/smart-lane
```

方式一: Git 拉取:

```bash
git clone <your-repo-url> smart-lane-dispatch-system
cd smart-lane-dispatch-system
```

方式二: 离线包上传:

```bash
tar -xzf smart-lane-dispatch-system.tar.gz -C /opt/smart-lane
cd /opt/smart-lane/smart-lane-dispatch-system
```

检查关键文件:

```bash
ls compose.yaml deploy/mysql/init deploy/mosquitto deploy/nginx server web
```

## 5. 生产环境变量配置

复制样例:

```bash
cp .env.example .env
nano .env
```

生产 `.env` 推荐模板:

```dotenv
APP_HTTP_PORT=3002
APP_PUBLIC_HOST=172.17.2.10
APP_CORS_ALLOWED_ORIGINS=http://172.17.2.10:3002
APP_JWT_SECRET=replace-with-a-long-random-64-byte-secret
APP_JWT_EXPIRE_HOURS=8

MYSQL_PORT=3306
MYSQL_ROOT_PASSWORD=replace-with-strong-root-password
MYSQL_DATABASE=smart_lane_dispatch
MYSQL_USER=smartlane
MYSQL_PASSWORD=replace-with-strong-app-password

REDIS_PORT=6379

MQTT_PORT=1883
MQTT_WS_PORT=9001

# Docker Hub 镜像前缀。国内环境默认走 DaoCloud 镜像代理；
# 如果代理不可用，可改为 docker.io/library 或其他 Docker Hub 镜像代理的 /library 路径。
DOCKER_IMAGE_REGISTRY=docker.m.daocloud.io/library

APP_BOOTSTRAP_ADMIN_ENABLED=false
APP_BOOTSTRAP_ADMIN_USERNAME=
APP_BOOTSTRAP_ADMIN_PASSWORD=
APP_BOOTSTRAP_ADMIN_DISPLAY_NAME=系统超级管理员
APP_BOOTSTRAP_ADMIN_STATION=总控中心

APP_DEVICE_GATEWAY=mqtt
APP_DEVICE_MQTT_ENABLED=true

# 后端容器连接 Compose 内部 mqtt 服务，保持 mqtt 即可。
# 现场硬件设备配置里填写服务器局域网 IP: 172.17.2.10。
APP_DEVICE_MQTT_HOST=mqtt
APP_DEVICE_MQTT_PORT=1883
APP_DEVICE_MQTT_CLIENT_ID=smart-lane-dispatch-system
APP_DEVICE_MQTT_USERNAME=jcadmin
APP_DEVICE_MQTT_PASSWORD=jcadmin@12345

# 总入口 MF 车牌识别摄像头，Topic 为 /{sn}/mf/up。
# 至少填写 SN；如果同一 SN 下有多路相机，再填写 groupId/deviceNo。
APP_DEVICE_PARKING_MF_YARD_ENTRY_SN=00E02721A3A7
APP_DEVICE_PARKING_MF_YARD_ENTRY_GROUP_ID=9QHZNII
APP_DEVICE_PARKING_MF_YARD_ENTRY_DEVICE_NO=22K5000202407828

# 计数报警协议相机 devId；使用 MF 协议时保持为空。
APP_DEVICE_SMART_CAMERA_YARD_ENTRY_CAMERA_DEV_ID=
APP_DEVICE_SMART_CAMERA_ACTIVE_ENTRY_CAMERA_DEV_ID=

# 1-11 号车道入口 Smart Camera devId，来自现场 UID/MAC。
APP_DEVICE_L01_CAMERA_DEV_ID=18030023535D
APP_DEVICE_L02_CAMERA_DEV_ID=1803002352FD
APP_DEVICE_L03_CAMERA_DEV_ID=180300235361
APP_DEVICE_L04_CAMERA_DEV_ID=18030023526B
APP_DEVICE_L05_CAMERA_DEV_ID=180300235302
APP_DEVICE_L06_CAMERA_DEV_ID=180300235265
APP_DEVICE_L07_CAMERA_DEV_ID=1803002353CD
APP_DEVICE_L08_CAMERA_DEV_ID=180300235396
APP_DEVICE_L09_CAMERA_DEV_ID=18030023525B
APP_DEVICE_L10_CAMERA_DEV_ID=18030023526C
APP_DEVICE_L11_CAMERA_DEV_ID=1803002353D4

APP_DEVICE_DIDO_PAYLOAD_MODE=json
APP_DEVICE_DIDO_RELAY_MODE=ordinary
APP_DEVICE_DIDO_ENABLE_REMOTE_CONFIG_ON_CONNECT=false
APP_DEVICE_DIDO_ENABLE_RELAY_UPLOAD_ON_CONNECT=false
APP_DEVICE_SHARED_ENTRY_DIDO_DEVICE_ID=DIDO-ENTRY-01
APP_DEVICE_SHARED_ENTRY_DIDO_HOST=172.17.2.80
APP_DEVICE_SHARED_ENTRY_DIDO_PORT=8080
APP_DEVICE_SHARED_EXIT_DIDO_DEVICE_ID=DIDO-EXIT-01
APP_DEVICE_SHARED_EXIT_DIDO_HOST=172.17.2.81
APP_DEVICE_SHARED_EXIT_DIDO_PORT=8080

APP_LED_GUIDE_ENABLED=true
APP_LED_GUIDE_IP=172.17.2.70
APP_LED_GUIDE_PORT=5005
APP_LED_GUIDE_GENERATION=6
APP_LED_GUIDE_MODEL=Bx6E
APP_LED_GUIDE_SCREEN_WIDTH=1920
APP_LED_GUIDE_SCREEN_HEIGHT=960
APP_LED_GUIDE_COLUMNS=2
APP_LED_GUIDE_ROWS=6
APP_LED_GUIDE_FONT_SIZE=72
APP_LED_GUIDE_COLOR=RED

APP_DISPATCH_ENTRY_LANE_ORDER=1-11
APP_DISPATCH_ENTRY_ENABLED_DEFAULT=true
APP_DISPATCH_EXIT_ENABLED_DEFAULT=false
APP_DISPATCH_ASSIGNMENT_RESERVE_MINUTES=2
```

说明:

- `APP_PUBLIC_HOST` 仅作为部署记录，设备配置时使用这个服务器 IP。
- `APP_DEVICE_MQTT_HOST=mqtt` 是后端容器访问 Mosquitto 容器的地址，不要改成服务器 IP，除非使用外部 MQTT Broker。
- 当前 DIDO 走 MQTT，`APP_DEVICE_SHARED_ENTRY_DIDO_HOST` / `APP_DEVICE_SHARED_EXIT_DIDO_HOST` 只是保留给 TCP DIDO 直连或调试场景；现场设备仍应配置连接 `172.17.2.10:1883`。
- `APP_DEVICE_PARKING_MF_YARD_ENTRY_SN` 如果配置，表示这个 MF 摄像头作为总入口相机，收到 `plateResult` 后生成预分配记录和大屏引导数据。
- `APP_DEVICE_SMART_CAMERA_ACTIVE_ENTRY_CAMERA_DEV_ID` 如果配置，表示这个相机作为“自动流程当前入口车道”的共享入口相机。
- `APP_DEVICE_SMART_CAMERA_YARD_ENTRY_CAMERA_DEV_ID` 如果配置，表示这个相机作为总入口相机，负责生成预分配记录和引导牌数据。
- `APP_JWT_SECRET` 必须在生产环境替换，不能使用样例值。

生成随机 JWT 密钥:

```bash
openssl rand -base64 48
```

## 6. Docker Compose 启动

首次启动:

```bash
docker compose config
docker compose up -d --build
```

如果构建时仍卡在拉取基础镜像，先确认 `.env` 中 `DOCKER_IMAGE_REGISTRY=docker.m.daocloud.io/library` 已生效。可以单独测试:

```bash
docker compose pull mysql redis mqtt nginx
docker compose build web server
```

查看容器状态:

```bash
docker compose ps
```

正常情况下应看到:

```text
smart-lane-mysql    running / healthy
smart-lane-redis    running
smart-lane-mqtt     running
smart-lane-server   running / healthy
smart-lane-web      running
smart-lane-nginx    running
```

健康检查:

```bash
curl -f http://127.0.0.1:3002/actuator/health
```

浏览器访问:

```text
http://172.17.2.10:3002
```

默认初始化账号:

```text
用户名: admin
密码: Admin@123
```

首次登录后应立即修改或替换现场管理员账号。当前初始化 SQL 位于 `deploy/mysql/init/03-seed.sql`。

MySQL 容器首次创建 `mysql-data` volume 时会自动执行 `deploy/mysql/init` 下的 SQL，创建表结构并初始化默认账号、`L01-L11` 车道和基础调度配置。已有 volume 不会重复执行初始化脚本。

## 7. 防火墙和端口

如果使用 `ufw`:

```bash
sudo ufw allow 22/tcp
sudo ufw allow from 172.17.2.0/24 to any port 3002 proto tcp
sudo ufw allow from 172.17.2.0/24 to any port 1883 proto tcp
sudo ufw allow from 172.17.2.0/24 to any port 9001 proto tcp
sudo ufw enable
sudo ufw status
```

生产建议:

- `3002/tcp`: 放给调度台、大屏、运维电脑。
- `1883/tcp`: 放给摄像头、CX 继电器设备、后端容器。
- `9001/tcp`: 仅硬件调试页需要 MQTT WebSocket 时开放。
- `3306/tcp`: MySQL 默认由 Compose 暴露到宿主机，建议只允许运维机访问，或在 `compose.yaml` 中移除 `mysql.ports`。
- `6379/tcp`: Redis 默认由 Compose 暴露到宿主机，建议只允许运维机访问，或在 `compose.yaml` 中移除 `redis.ports`。

注意 Docker 官方文档提醒: Docker 发布容器端口可能绕过 `ufw` 的普通规则。生产环境更严格的做法是在上级防火墙或交换机 ACL 限制访问来源，或使用 Docker `DOCKER-USER` 链加固。

## 8. 硬件设备真实配置

### 8.1 MQTT Broker 参数

所有 MQTT 设备统一连接服务器上的 Mosquitto:

```text
Broker Host: 172.17.2.10
Broker Port: 1883
Username: jcadmin
Password: jcadmin@12345
QoS: 0 或设备默认
Keep Alive: 30 秒左右
```

当前 `deploy/mosquitto/mosquitto.conf` 已启用 Mosquitto 用户名密码认证，并通过 `deploy/mosquitto/password_file` 保存 Broker 密码文件。后端同步使用:

```dotenv
APP_DEVICE_MQTT_USERNAME=jcadmin
APP_DEVICE_MQTT_PASSWORD=jcadmin@12345
```

如需更换 MQTT 账号密码，需要重新生成 `deploy/mosquitto/password_file`，同步修改 `.env` 后重启 `mqtt` 与 `server` 容器。

### 8.2 CX 继电器设备配置

当前业务按 2 台 CX/DIDO 继电器设备设计:

```text
入口设备 ID: DIDO-ENTRY-01
入口上报 Topic: /device/DIDO-ENTRY-01/update
入口下发 Topic: /device/DIDO-ENTRY-01/get

出口设备 ID: DIDO-EXIT-01
出口上报 Topic: /device/DIDO-EXIT-01/update
出口下发 Topic: /device/DIDO-EXIT-01/get
```

现场已确认 DIDO 模块 IP 范围为 `172.17.2.80-81`，且走 MQTT 连接服务器 `172.17.2.10:1883`。当前先按 `.80=入口 DIDO`、`.81=出口 DIDO` 记录；最终以现场配置工具里的设备 ID 和 Topic 为准。

入口 CX/DIDO 在官方配置软件中填写:

```text
服务器地址: 172.17.2.10
服务器端口: 1883
设备 ID: DIDO-ENTRY-01
发布 Topic: /device/DIDO-ENTRY-01/update
订阅 Topic: /device/DIDO-ENTRY-01/get
用户名/密码: 留空，除非 Mosquitto 开启认证
```

出口 CX/DIDO 在官方配置软件中填写:

```text
服务器地址: 172.17.2.10
服务器端口: 1883
设备 ID: DIDO-EXIT-01
发布 Topic: /device/DIDO-EXIT-01/update
订阅 Topic: /device/DIDO-EXIT-01/get
用户名/密码: 留空，除非 Mosquitto 开启认证
```

入口 CX/DIDO 继电器输出映射:

```text
DO1  -> L01 1号车道入口灯
DO2  -> L02 2号车道入口灯
DO3  -> L03 3号车道入口灯
DO4  -> L04 4号车道入口灯
DO5  -> L05 5号车道入口灯
DO6  -> L06 6号车道入口灯
DO7  -> L07 7号车道入口灯
DO8  -> L08 8号车道入口灯
DO9  -> L09 9号车道入口灯
DO10 -> L10 10号车道入口灯
DO11 -> L11 11号车道入口灯
```

系统默认映射:

```text
L01 entry-green-relay=A01
L02 entry-green-relay=A02
L03 entry-green-relay=A03
L04 entry-green-relay=A04
L05 entry-green-relay=A05
L06 entry-green-relay=A06
L07 entry-green-relay=A07
L08 entry-green-relay=A08
L09 entry-green-relay=A09
L10 entry-green-relay=A10
L11 entry-green-relay=A11
```

出口 CX/DIDO 继电器输出映射:

```text
DO1  -> L01 1号车道出口灯
DO2  -> L02 2号车道出口灯
DO3  -> L03 3号车道出口灯
DO4  -> L04 4号车道出口灯
DO5  -> L05 5号车道出口灯
DO6  -> L06 6号车道出口灯
DO7  -> L07 7号车道出口灯
DO8  -> L08 8号车道出口灯
DO9  -> L09 9号车道出口灯
DO10 -> L10 10号车道出口灯
DO11 -> L11 11号车道出口灯
```

系统默认映射:

```text
L01 exit-green-relay=A01
L02 exit-green-relay=A02
L03 exit-green-relay=A03
L04 exit-green-relay=A04
L05 exit-green-relay=A05
L06 exit-green-relay=A06
L07 exit-green-relay=A07
L08 exit-green-relay=A08
L09 exit-green-relay=A09
L10 exit-green-relay=A10
L11 exit-green-relay=A11
```

单灯模式规则:

```text
对应 DO 口吸合 = 绿灯
对应 DO 口断开 = 红灯
```

出口 CX/DIDO 地感入口映射:

```text
IN1  -> L01 1号车道出口地感
IN2  -> L02 2号车道出口地感
IN3  -> L03 3号车道出口地感
IN4  -> L04 4号车道出口地感
IN5  -> L05 5号车道出口地感
IN6  -> L06 6号车道出口地感
IN7  -> L07 7号车道出口地感
IN8  -> L08 8号车道出口地感
IN9  -> L09 9号车道出口地感
IN10 -> L10 10号车道出口地感
IN11 -> L11 11号车道出口地感
```

系统默认把 `B01-B11` 作为出口地感输入键。DIDO 上报从未触发变为触发时，系统认为对应车道有 1 辆车驶出。

如果现场 CX 设备 ID 不是示例值，修改 `.env`:

```dotenv
APP_DEVICE_SHARED_ENTRY_DIDO_DEVICE_ID=<入口真实设备ID>
APP_DEVICE_SHARED_EXIT_DIDO_DEVICE_ID=<出口真实设备ID>
```

并把两台 CX 设备 Topic 分别改为:

```text
上报 Topic: /device/<真实设备ID>/update
下发 Topic: /device/<真实设备ID>/get
```

### 8.3 计数报警相机配置

当前代码处理 `/device/{devId}/update` 上报的 `devAlarm`，入口车牌事件只处理:

```text
alarmType=1
alarmType=49409
inOut=in 或缺省
plateNum 或 plateNumVDC 有车牌值
```

摄像头 MQTT 参数:

```text
服务器地址: 172.17.2.10
服务器端口: 1883
设备 devId: 使用摄像头真实 devId
上报 Topic: /device/{devId}/update
遗嘱 Topic: /device/{devId}/will
下发 Topic: /device/{devId}/get
```

现场已确认 `172.17.2.51-61` 为 1-11 号车道入口摄像机，IP 顺序为 `.51=L01`、`.52=L02`，依次到 `.61=L11`。本系统绑定仍使用 MQTT 报文里的 `devId`，不是 IP；目前已按现场 UID/MAC 填写 `APP_DEVICE_L01_CAMERA_DEV_ID` 到 `APP_DEVICE_L11_CAMERA_DEV_ID`。

| 车道 | 设备名称 | 设备 SN | IP | devId / UID |
| --- | --- | --- | --- | --- |
| L01 | 1号通道摄像机 | 09L1303801025624 | 172.17.2.51 | 18030023535D |
| L02 | 2号通道摄像机 | 09L1303801025645 | 172.17.2.52 | 1803002352FD |
| L03 | 3号通道摄像机 | 09L1303801025647 | 172.17.2.53 | 180300235361 |
| L04 | 4号通道摄像机 | 09L1303801025634 | 172.17.2.54 | 18030023526B |
| L05 | 5号通道摄像机 | 09L1303801025640 | 172.17.2.55 | 180300235302 |
| L06 | 6号通道摄像机 | 09L1303801025648 | 172.17.2.56 | 180300235265 |
| L07 | 7号通道摄像机 | 09L1303801025626 | 172.17.2.57 | 1803002353CD |
| L08 | 8号通道摄像机 | 09L1303801025644 | 172.17.2.58 | 180300235396 |
| L09 | 9号通道摄像机 | 09L1303801025642 | 172.17.2.59 | 18030023525B |
| L10 | 10号通道摄像机 | 09L1303801025643 | 172.17.2.60 | 18030023526C |
| L11 | 11号通道摄像机 | 09L1303801025641 | 172.17.2.61 | 1803002353D4 |

生产有三种常见配置方式。

方式 A: 单个共享入口相机，按自动流程当前入口车道入道。适合当前联调模式:

```dotenv
APP_DEVICE_SMART_CAMERA_ACTIVE_ENTRY_CAMERA_DEV_ID=<共享入口相机devId>
```

方式 B: 每条车道一个固定入口相机。分别配置:

```dotenv
APP_DEVICE_L01_CAMERA_DEV_ID=18030023535D
APP_DEVICE_L02_CAMERA_DEV_ID=1803002352FD
APP_DEVICE_L03_CAMERA_DEV_ID=180300235361
APP_DEVICE_L04_CAMERA_DEV_ID=18030023526B
APP_DEVICE_L05_CAMERA_DEV_ID=180300235302
APP_DEVICE_L06_CAMERA_DEV_ID=180300235265
APP_DEVICE_L07_CAMERA_DEV_ID=1803002353CD
APP_DEVICE_L08_CAMERA_DEV_ID=180300235396
APP_DEVICE_L09_CAMERA_DEV_ID=18030023525B
APP_DEVICE_L10_CAMERA_DEV_ID=18030023526C
APP_DEVICE_L11_CAMERA_DEV_ID=1803002353D4
```

方式 C: 单独总入口相机，只负责进入蓄车池后的预分配:

```dotenv
APP_DEVICE_SMART_CAMERA_YARD_ENTRY_CAMERA_DEV_ID=<总入口相机devId>
```

注意:

- 同一个 `devId` 不建议同时配置为总入口相机和固定车道入口相机，除非只是临时联调。
- 使用方式 A 时，车牌会进入“自动流程当前入口车道”，不会因为手动打开其他车道绿灯而改变分配目标。
- 使用方式 B 时，每个相机固定代表对应车道入口。

### 8.4 MF 车牌识别设备配置

现场只有总入口使用 `/{sn}/mf/up`、`/{sn}/mf/down` 协议的 MF 车牌识别设备。1-11 车道入口使用 Smart Camera，在 8.3 的 `APP_DEVICE_Lxx_CAMERA_DEV_ID` 中配置。

已确认 `172.17.2.40` 车牌识别一体机和 `172.17.2.90` 车牌识别终端是一套设备，一个在机房、一个在现场；现场端应为车牌识别一体机。系统侧最终按 MF 报文里的 `SN/groupId/deviceNo` 匹配，不按 IP 直接匹配。

总入口 MF 摄像头配置:

```dotenv
APP_DEVICE_PARKING_MF_YARD_ENTRY_SN=<总入口MF设备SN，例如00E02721A3A7>
APP_DEVICE_PARKING_MF_YARD_ENTRY_GROUP_ID=<总入口MF报文data.groupId，可为空>
APP_DEVICE_PARKING_MF_YARD_ENTRY_DEVICE_NO=<总入口MF报文data.deviceNo，例如22K5000202407828>
```

当前已按测试通过的上报数据配置:

```dotenv
APP_DEVICE_PARKING_MF_YARD_ENTRY_SN=00E02721A3A7
APP_DEVICE_PARKING_MF_YARD_ENTRY_GROUP_ID=9QHZNII
APP_DEVICE_PARKING_MF_YARD_ENTRY_DEVICE_NO=22K5000202407828
```

对应 Topic:

```text
上报 Topic: /{sn}/mf/up
下发 Topic: /{sn}/mf/down
```

摄像头 MQTT 测试页面:

```text
http://172.17.2.10:3002/camera-test
```

该页面同时支持 1-11 号车道 Smart Camera 和总入口 MF。总入口 MF 区域填写 `SN/groupId/deviceNo` 后，可以直接模拟 `plateResult` 上报到 `/{sn}/mf/up`，并查看后台发回的 `/{sn}/mf/down` 确认。

如果现场 Topic 不同，需要覆盖:

```dotenv
APP_DEVICE_PARKING_MF_UP_TOPIC_FILTER=/+/mf/up
APP_DEVICE_PARKING_MF_DOWN_TOPIC_TEMPLATE=/{mfSn}/mf/down
```

### 8.5 LED 显示屏配置

现场 LED 显示屏 IP 已确认为 `172.17.2.70`。该屏需要显示总入口摄像头抓拍后生成的引导内容，即车牌号和推荐车道。

当前测试页 `/led-test` 已按现场显示格式预置:

- 屏幕尺寸: `1920 x 960`
- 屏幕拼接: `6 x 6` 块 `320 x 160` 小屏
- 显示布局: `2` 列 `6` 行，共 `12` 条
- 文案格式: `车牌-车道`，例如 `苏B12345-1车道`
- 数据来源: `/api/screen/board` 的总入口引导数据

业务自动下发也已按现场参数配置: `APP_LED_GUIDE_IP=172.17.2.70`、`APP_LED_GUIDE_PORT=5005`、`APP_LED_GUIDE_GENERATION=6`、`APP_LED_GUIDE_MODEL=Bx6E`。总入口生成推荐车道后，后端会把最近 12 条引导数据自动刷新到 LED；同时还有 5 秒定时兜底刷新。

测试页面访问地址:

```text
http://172.17.2.10:3002/led-test
```

如果服务器实际 IP 不是 `172.17.2.10`，把地址中的 IP 替换为 `APP_PUBLIC_HOST`。

## 9. 设备联调验收

### 9.1 验证 MQTT Broker

在服务器上监听所有设备消息:

```bash
docker compose exec mqtt mosquitto_sub -h 127.0.0.1 -p 1883 -u jcadmin -P 'jcadmin@12345' -t '#' -v
```

看到摄像头或 CX 设备周期性上报，即 Broker 网络正常。

### 9.2 验证 CX 继电器下发

手动下发 A01 吸合:

```bash
docker compose exec mqtt mosquitto_pub \
  -h 127.0.0.1 \
  -p 1883 \
  -u jcadmin \
  -P 'jcadmin@12345' \
  -t '/device/DIDO-ENTRY-01/get' \
  -m '{"A01":110000,"res":"manual-on"}'
```

手动下发 A01 断开:

```bash
docker compose exec mqtt mosquitto_pub \
  -h 127.0.0.1 \
  -p 1883 \
  -u jcadmin \
  -P 'jcadmin@12345' \
  -t '/device/DIDO-ENTRY-01/get' \
  -m '{"A01":100000,"res":"manual-off"}'
```

当前现场 DIDO 已按 JSON 控制调通，系统应保持 `APP_DEVICE_DIDO_PAYLOAD_MODE=json`。只有在现场明确验证 HEX payload 也可控制继电器时，才改为 `hex-a1` 或 `hex-a3`。

### 9.3 验证总入口 MF 抓拍预分配

可以先用测试页面模拟总入口抓拍:

```text
http://172.17.2.10:3002/camera-test
```

在“总入口 MF”区域填写 `SN/groupId/deviceNo/测试车牌`，点击“抓拍上报”。后台收到后会按 `.env` 中的 `APP_DEVICE_PARKING_MF_YARD_ENTRY_*` 判断是否为总入口设备。

监听 MF 上报:

```bash
docker compose exec mqtt mosquitto_sub -h 127.0.0.1 -p 1883 -u jcadmin -P 'jcadmin@12345' -t '/+/mf/up' -v
```

让总入口摄像头抓拍一辆车，应看到类似:

```json
{
  "cmd": "plateResult",
  "sn": "00E02721A3A7",
  "data": {
    "deviceNo": "22K5000202407828",
    "groupId": "9QHZNII",
    "plateNo": "苏B3R89T",
    "parkingTime": "2026-05-12 14:53:36"
  }
}
```

然后在页面检查:

```text
司机大屏 -> 待入道车辆出现该车牌和推荐车道
调度后台 -> 当前入口开放车道或预留数随推荐结果更新
```

如果摄像头已上报但页面没有推荐记录，重点检查 `.env` 中:

```dotenv
APP_DEVICE_PARKING_MF_YARD_ENTRY_SN=00E02721A3A7
APP_DEVICE_PARKING_MF_YARD_ENTRY_GROUP_ID=9QHZNII
APP_DEVICE_PARKING_MF_YARD_ENTRY_DEVICE_NO=22K5000202407828
APP_DISPATCH_ENTRY_ENABLED_DEFAULT=true
```

### 9.4 验证车道入口摄像头入道

让摄像头抓拍一辆车，监听:

```bash
docker compose exec mqtt mosquitto_sub -h 127.0.0.1 -p 1883 -u jcadmin -P 'jcadmin@12345' -t '/device/+/update' -v
```

应看到类似:

```json
{
  "cmd": "devAlarm",
  "devId": "18030023526b",
  "content": {
    "alarmType": 1,
    "plateNum": "苏BFE9999",
    "inOut": "in"
  }
}
```

然后在页面检查:

```text
车道总览 -> 对应车道出现车牌
车辆流水 -> 生成车辆进出流水
```

### 9.5 验证地感出场

短接或触发对应 IN 口，例如 `IN8`:

```text
出口 CX/DIDO 上报 /device/DIDO-EXIT-01/update 中 B08 从 0 变为 1
系统认为 L08 有 1 辆车出场
车辆流水中对应记录写入出场时间
车道总览中 L08 车牌减少
```

如果触发后没有出场，检查:

```bash
docker compose logs -f server
docker compose exec mqtt mosquitto_sub -h 127.0.0.1 -p 1883 -u jcadmin -P 'jcadmin@12345' -t '/device/DIDO-EXIT-01/update' -v
```

重点确认上报 JSON 中是否有 `B08`，以及设备 ID 是否与 `.env` 中 `APP_DEVICE_SHARED_EXIT_DIDO_DEVICE_ID` 一致。

## 10. 日常运维

查看状态:

```bash
docker compose ps
```

查看日志:

```bash
docker compose logs -f server
docker compose logs -f web
docker compose logs -f mqtt
docker compose logs -f nginx
```

停止:

```bash
docker compose down
```

启动:

```bash
docker compose up -d
```

重启后端:

```bash
docker compose restart server
```

修改 `.env` 后重启后端:

```bash
docker compose up -d --force-recreate server
```

如果修改了前端或后端代码，重新构建:

```bash
docker compose up -d --build
```

国内网络构建失败时，优先检查 `.env` 中 `DOCKER_IMAGE_REGISTRY` 是否仍指向可用镜像代理。

## 11. 数据备份与恢复

备份 MySQL:

```bash
mkdir -p backups
docker compose exec -T mysql mysqldump \
  -uroot \
  -p"$MYSQL_ROOT_PASSWORD" \
  --single-transaction \
  --routines \
  --triggers \
  smart_lane_dispatch > backups/smart_lane_dispatch_$(date +%F_%H%M%S).sql
```

如果当前 shell 没有加载 `.env`，先执行:

```bash
set -a
source .env
set +a
```

恢复 MySQL:

```bash
docker compose exec -T mysql mysql -uroot -p"$MYSQL_ROOT_PASSWORD" smart_lane_dispatch < backups/your-backup.sql
```

备份 Docker volumes:

```bash
docker volume ls | grep smart-lane
```

正式恢复前应先停服务并确认备份完整。

## 12. 升级发布

### 12.1 Electerm SFTP 上传离线升级包

当前这次现场升级包在本机项目目录下:

```text
deploy-artifacts/smart-lane-onsite-upgrade-c297364-20260522/
```

本次需要上传的文件:

```text
deploy-artifacts/smart-lane-onsite-upgrade-c297364-20260522/images/smart-lane-business-images-amd64-c297364.tar
deploy-artifacts/smart-lane-onsite-upgrade-c297364-20260522/project/smart-lane-dispatch-system-c297364.tar.gz
deploy-artifacts/smart-lane-onsite-upgrade-c297364-20260522/env/onsite.env
deploy-artifacts/smart-lane-onsite-upgrade-c297364-20260522/SHA256SUMS
```

说明:

- `smart-lane-business-images-amd64-c297364.tar`: 新的 `server` 和 `web` 镜像，已按 `linux/amd64` 构建。
- `smart-lane-dispatch-system-c297364.tar.gz`: 新的项目配置、部署文件、前后端源码，不包含 `.env`，不会直接覆盖服务器现场 `.env`。
- `onsite.env`: 现场参数参考文件，已把 `APP_PUBLIC_HOST` 调整为 `172.17.2.10`，总入口摄像头、1-11 号入口摄像头、LED、DIDO 参数也已填好。
- `SHA256SUMS`: 上传后校验文件完整性。

用 Electerm SFTP 上传:

1. 连接 `supervisor@172.17.2.10`。
2. 在服务器侧进入 `/home/supervisor`。
3. 新建目录:

```text
/home/supervisor/smart-lane-upgrade-c297364
```

4. 把本机 `deploy-artifacts/smart-lane-onsite-upgrade-c297364-20260522/` 里的 `images/`、`project/`、`env/` 三个目录和 `SHA256SUMS` 文件上传到:

```text
/home/supervisor/smart-lane-upgrade-c297364/
```

上传后服务器上应类似:

```text
/home/supervisor/smart-lane-upgrade-c297364/
  SHA256SUMS
  images/smart-lane-business-images-amd64-c297364.tar
  project/smart-lane-dispatch-system-c297364.tar.gz
  env/onsite.env
```

### 12.2 服务器上执行升级

以下命令在 Electerm SSH 终端执行。

进入上传目录并校验:

```bash
cd /home/supervisor/smart-lane-upgrade-c297364
sha256sum -c SHA256SUMS
```

全部显示 `OK` 后继续。先加载新的前后端镜像:

```bash
sudo docker load -i images/smart-lane-business-images-amd64-c297364.tar
sudo docker image ls | grep smart-lane-dispatch-system
```

进入项目目录:

```bash
APP_DIR=/opt/smart-lane/smart-lane-dispatch-system
cd "$APP_DIR"
```

升级前建议备份数据库:

```bash
set -a
source .env
set +a
mkdir -p backups
sudo docker compose -p smart-lane-dispatch-system exec -T mysql \
  mysqldump -uroot -p"$MYSQL_ROOT_PASSWORD" smart_lane_dispatch \
  > backups/pre_upgrade_$(date +%F_%H%M%S).sql
```

备份服务器现有 `.env`:

```bash
sudo cp .env backups/.env.$(date +%F_%H%M%S)
```

覆盖项目文件。这个包不包含 `.env`，所以不会覆盖现场 `.env`:

```bash
sudo tar -xzf /home/supervisor/smart-lane-upgrade-c297364/project/smart-lane-dispatch-system-c297364.tar.gz -C "$APP_DIR"
```

把这次现场关键参数合并进服务器 `.env`。这一步保留服务器原来的数据库密码、JWT 等配置，只更新本次设备联调需要的项:

```bash
sudo bash -c '
set -e
cd /opt/smart-lane/smart-lane-dispatch-system
ENV_FILE=.env

set_env() {
  key="$1"
  val="$2"
  if grep -q "^${key}=" "$ENV_FILE"; then
    sed -i "s|^${key}=.*|${key}=${val}|" "$ENV_FILE"
  else
    printf "\n%s=%s\n" "$key" "$val" >> "$ENV_FILE"
  fi
}

set_env APP_PUBLIC_HOST 172.17.2.10
set_env APP_CORS_ALLOWED_ORIGINS http://172.17.2.10:3002

set_env APP_DEVICE_PARKING_MF_YARD_ENTRY_SN 00E02721A3A7
set_env APP_DEVICE_PARKING_MF_YARD_ENTRY_GROUP_ID 9QHZNII
set_env APP_DEVICE_PARKING_MF_YARD_ENTRY_DEVICE_NO 22K5000202407828

set_env APP_DEVICE_DIDO_PAYLOAD_MODE json
set_env APP_DEVICE_SHARED_ENTRY_DIDO_DEVICE_ID DIDO-ENTRY-01
set_env APP_DEVICE_SHARED_ENTRY_DIDO_HOST 172.17.2.80
set_env APP_DEVICE_SHARED_ENTRY_DIDO_PORT 8080
set_env APP_DEVICE_SHARED_EXIT_DIDO_DEVICE_ID DIDO-EXIT-01
set_env APP_DEVICE_SHARED_EXIT_DIDO_HOST 172.17.2.81
set_env APP_DEVICE_SHARED_EXIT_DIDO_PORT 8080

set_env APP_LED_GUIDE_ENABLED true
set_env APP_LED_GUIDE_IP 172.17.2.70
set_env APP_LED_GUIDE_PORT 5005
set_env APP_LED_GUIDE_GENERATION 6
set_env APP_LED_GUIDE_MODEL Bx6E
set_env APP_LED_GUIDE_SCREEN_WIDTH 1920
set_env APP_LED_GUIDE_SCREEN_HEIGHT 960
set_env APP_LED_GUIDE_COLUMNS 2
set_env APP_LED_GUIDE_ROWS 6
set_env APP_LED_GUIDE_FONT_SIZE 72
set_env APP_LED_GUIDE_COLOR RED

set_env APP_DISPATCH_ENTRY_LANE_ORDER 1-11
set_env APP_DISPATCH_ENTRY_ENABLED_DEFAULT true
set_env APP_DISPATCH_EXIT_ENABLED_DEFAULT false
set_env APP_DISPATCH_ASSIGNMENT_RESERVE_MINUTES 2
'
```

确认 Compose 配置能解析:

```bash
sudo docker compose -p smart-lane-dispatch-system config >/tmp/smart-lane-compose-check.yaml
```

重新创建需要更新的服务。不要执行 `docker compose down -v`:

```bash
sudo docker compose -p smart-lane-dispatch-system up -d --no-build --force-recreate --no-deps mqtt
sudo docker compose -p smart-lane-dispatch-system up -d --no-build --force-recreate --no-deps server
sudo docker compose -p smart-lane-dispatch-system up -d --no-build --force-recreate --no-deps web
sudo docker compose -p smart-lane-dispatch-system up -d --no-build --force-recreate --no-deps nginx
```

查看运行状态:

```bash
sudo docker compose -p smart-lane-dispatch-system ps
curl -f http://127.0.0.1:3002/actuator/health
curl -I http://127.0.0.1:3002/
```

查看日志:

```bash
sudo docker compose -p smart-lane-dispatch-system logs --tail=100 server
sudo docker compose -p smart-lane-dispatch-system logs --tail=100 web
sudo docker compose -p smart-lane-dispatch-system logs --tail=100 mqtt
sudo docker compose -p smart-lane-dispatch-system logs --tail=100 nginx
```

浏览器验证:

```text
http://172.17.2.10:3002
http://172.17.2.10:3002/camera-test
http://172.17.2.10:3002/led-test
```

总入口摄像头 MQTT 验证:

```bash
sudo docker compose -p smart-lane-dispatch-system exec mqtt \
  mosquitto_sub -h 127.0.0.1 -p 1883 \
  -u jcadmin -P 'jcadmin@12345' \
  -t '/+/mf/up' -v
```

看到 `/00E02721A3A7/mf/up` 的 `plateResult` 后，后台应生成推荐车道，LED 会自动刷新。

### 12.3 Git 在线升级方式

拉取新代码:

```bash
cd /opt/smart-lane/smart-lane-dispatch-system
git pull
```

备份数据库:

```bash
set -a
source .env
set +a
mkdir -p backups
docker compose exec -T mysql mysqldump -uroot -p"$MYSQL_ROOT_PASSWORD" smart_lane_dispatch > backups/pre_upgrade_$(date +%F_%H%M%S).sql
```

重新构建并启动:

```bash
docker compose up -d --build
docker compose ps
curl -f http://127.0.0.1:3002/actuator/health
```

当前后端 `APP_JPA_DDL_AUTO=validate`，不会自动改数据库结构。如果后续版本新增表或字段，必须先执行随版本提供的 SQL 迁移脚本，再启动新后端。

## 13. 常见问题

### 13.1 页面打不开

检查:

```bash
docker compose ps
docker compose logs --tail=200 nginx
curl -I http://127.0.0.1:3002
```

确认服务器防火墙允许 `3002/tcp`，浏览器访问的是服务器固定 IP。

### 13.2 后端不健康

检查:

```bash
docker compose logs --tail=300 server
docker compose logs --tail=300 mysql
```

常见原因:

- MySQL 密码 `.env` 与已有 volume 中初始化密码不一致。
- 数据库表结构缺失或版本不匹配。
- `.env` 中设备配置格式错误导致启动失败。

如果是首次部署且允许清空数据，可重建 volume:

```bash
docker compose down -v
docker compose up -d --build
```

注意: `down -v` 会删除 MySQL、Redis、MQTT 持久化数据，生产环境不要随意执行。

### 13.3 设备连不上 MQTT

检查服务器端口:

```bash
ss -lntp | grep -E '1883|9001'
docker compose logs --tail=200 mqtt
```

从同网段电脑测试:

```bash
telnet 172.17.2.10 1883
```

或:

```bash
nc -vz 172.17.2.10 1883
```

确认设备里填写的是服务器 IP `172.17.2.10`，不是容器内部主机名 `mqtt`。

### 13.4 车牌上报了但页面没有进车道

检查:

- 摄像头上报 Topic 是否是 `/device/{devId}/update`。
- `devId` 是否配置到 `.env` 的 `APP_DEVICE_SMART_CAMERA_ACTIVE_ENTRY_CAMERA_DEV_ID` 或 `APP_DEVICE_Lxx_CAMERA_DEV_ID`。
- `cmd` 是否为 `devAlarm`。
- `content.alarmType` 是否为 `1` 或 `49409`。
- `content.inOut` 是否为 `in` 或为空。
- `content.plateNum` 是否有车牌号。

查看后端日志:

```bash
docker compose logs -f server
```

### 13.5 CX 灯不动作

检查:

- 入口 CX/DIDO 设备 ID 是否与 `APP_DEVICE_SHARED_ENTRY_DIDO_DEVICE_ID` 一致。
- 出口 CX/DIDO 设备 ID 是否与 `APP_DEVICE_SHARED_EXIT_DIDO_DEVICE_ID` 一致。
- CX 订阅 Topic 是否分别为 `/device/DIDO-ENTRY-01/get`、`/device/DIDO-EXIT-01/get`。
- 后端是否连接 MQTT 成功。
- `APP_DEVICE_DIDO_PAYLOAD_MODE` 是否符合设备能力，当前现场 DIDO 已按 `json` 调通。
- DO 口接线是否与 A01-A11 映射一致。

监听下发:

```bash
docker compose exec mqtt mosquitto_sub -h 127.0.0.1 -p 1883 -u jcadmin -P 'jcadmin@12345' -t '/device/+/get' -v
```

点击页面信号灯控制，应该能看到后端下发消息。

### 13.6 地感触发不出场

检查:

- 出口 CX/DIDO 上报 Topic 是否为 `/device/DIDO-EXIT-01/update`。
- 上报 payload 是否包含 `B01-B11`。
- 触发是否产生从 `0` 到 `1` 的变化。
- 对应车道是否已有在场车辆。

监听:

```bash
docker compose exec mqtt mosquitto_sub -h 127.0.0.1 -p 1883 -u jcadmin -P 'jcadmin@12345' -t '/device/DIDO-EXIT-01/update' -v
```

## 14. 交付检查清单

上线前逐项确认:

- Ubuntu 服务器固定 IP 已设置。
- Docker 和 Docker Compose 正常。
- `.env` 已替换所有默认密码和 `APP_JWT_SECRET`。
- `docker compose ps` 全部运行，后端健康检查通过。
- 浏览器能访问 `http://服务器IP:3002`。
- 默认管理员密码已修改或替换。
- 摄像头 MQTT 能上报到 `/device/{devId}/update`。
- 入口 CX/DIDO 能接收 `/device/DIDO-ENTRY-01/get` 下发。
- 出口 CX/DIDO 能接收 `/device/DIDO-EXIT-01/get` 下发。
- 入口 CX/DIDO 的 DO1-DO11 与 1-11 号车道入口灯映射正确。
- 出口 CX/DIDO 的 DO1-DO11 与 1-11 号车道出口灯映射正确。
- IN1-IN11 与 1-11 号车道出口地感映射正确。
- 车牌抓拍能生成车辆流水。
- 地感触发能生成出场时间。
- 数据库备份命令已验证。
