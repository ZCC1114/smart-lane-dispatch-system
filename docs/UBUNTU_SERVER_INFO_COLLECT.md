# Ubuntu 服务器信息采集命令

把下面整段命令复制到 Ubuntu 服务器终端执行。它会在当前目录生成一个 `server-info-*.txt` 报告文件，把这个文件内容发回来即可。

脚本只读取系统信息，不修改服务器配置。

```bash
cat > collect-server-info.sh <<'SH'
#!/usr/bin/env bash

REPORT="server-info-$(hostname)-$(date +%Y%m%d-%H%M%S).txt"

section() {
  printf '\n\n========== %s ==========\n' "$1"
}

run_cmd() {
  printf '\n$ %s\n' "$*"
  "$@" 2>&1 || true
}

run_shell() {
  printf '\n$ %s\n' "$1"
  sh -c "$1" 2>&1 || true
}

{
  section "1. Basic System"
  run_cmd hostname
  run_cmd date
  run_cmd cat /etc/os-release
  run_cmd dpkg --print-architecture
  run_cmd uname -a
  run_cmd uname -m

  section "2. CPU Memory Disk"
  run_cmd nproc
  run_cmd lscpu
  run_cmd free -h
  run_cmd lsblk
  run_cmd df -h

  section "3. Network Interfaces And Routes"
  run_cmd ip addr
  run_cmd ip route
  run_cmd ip link

  section "4. DNS Time"
  run_cmd timedatectl
  run_cmd resolvectl status
  run_cmd cat /etc/resolv.conf

  section "5. Docker Status"
  run_cmd docker --version
  run_cmd docker compose version
  run_cmd systemctl status docker --no-pager
  run_shell "ip route | grep -E 'docker|172\\.17|10\\.87|10\\.88|10\\.89' || true"

  section "6. Port Usage"
  run_shell "sudo ss -lntup | grep -E ':22|:80|:1883|:3002|:3306|:6379|:9001|:8080' || true"

  section "7. Firewall"
  run_cmd sudo ufw status verbose
  run_shell "sudo iptables -S DOCKER-USER 2>/dev/null || true"

  section "8. Apt And Installed Docker Packages"
  run_shell "apt-cache policy docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin 2>/dev/null || true"
  run_shell "dpkg -l | grep -E 'docker|containerd|runc' || true"

  section "9. Suggested Deploy Directory Check"
  run_cmd ls -ld /opt /opt/smart-lane /opt/smart-lane/smart-lane-dispatch-system
} 2>&1 | tee "$REPORT"

echo
echo "Report saved to: $REPORT"
SH

chmod +x collect-server-info.sh
./collect-server-info.sh
```

如果服务器提示 `sudo` 需要密码，输入当前登录用户的密码即可。如果当前用户没有 sudo 权限，先把能输出的报告发回来，我再根据缺失项补命令。

执行完成后查看报告:

```bash
ls -lh server-info-*.txt
cat server-info-*.txt
```
