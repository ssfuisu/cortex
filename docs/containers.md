# Containers (Podman, Docker) on Cortex

Short version: **containers need root mode.** In normal mode Cortex is an ordinary app, and Android doesn't let an app create namespaces, mount file systems or touch cgroups, which is exactly what a container runtime does. No amount of library tricks changes that, so `podman run` or `dockerd` won't work there. If you just want to run a program, install it with `apt` and run it directly, it's the same Ubuntu.

On a rooted phone, root mode (*Settings > Root mode*) gives you a real chroot with real root, and from 1.27.7 on it also sets up `/sys/fs/cgroup` the way Ubuntu does. That's enough for Podman on many kernels. Android kernels differ a lot between phones though, so treat this as "should work, here's what to try" and not as a promise.

## 1. Does your kernel have what containers need?

```bash
zcat /proc/config.gz | grep -E 'CONFIG_(NAMESPACES|PID_NS|NET_NS|USER_NS|CGROUPS|CGROUP_PIDS|OVERLAY_FS|VETH|BRIDGE|NF_NAT|IP_NF_NAT|NFT_NAT|NF_TABLES_IPV4)='
```

You want `NAMESPACES`, `PID_NS`, `NET_NS` and `CGROUPS` at `=y`. `OVERLAY_FS` makes images fast, without it use the `vfs` driver below. `VETH` and `BRIDGE` are needed for container networking, without them use `--network=host`. If `/proc/config.gz` doesn't exist your kernel doesn't expose its config, just try.

## 2. Podman

```bash
apt install podman
```

Tell it there is no systemd:

```bash
mkdir -p /etc/containers
cat > /etc/containers/containers.conf <<'EOF'
[engine]
cgroup_manager = "cgroupfs"
events_logger = "file"
EOF
```

**Networking.** Many Android kernels have the old iptables NAT (`CONFIG_IP_NF_NAT=y`) but not the nftables one (`CONFIG_NFT_NAT` not set). Ubuntu's `iptables` uses nftables by default, and then Netavark fails with `TABLE_ADD failed (Address family not supported by protocol): table nat`. Switch to the legacy backend:

```bash
update-alternatives --set iptables /usr/sbin/iptables-legacy
update-alternatives --set ip6tables /usr/sbin/ip6tables-legacy
```

Then:

```bash
podman run --rm docker.io/library/hello-world
```

If networking still fails, `--network=host` skips it entirely and the container uses the phone's network directly:

```bash
podman run --rm --network=host docker.io/library/alpine wget -qO- https://example.com
```

**Storage.** If pulling an image fails with an overlay error, use the slower but always working `vfs` driver. Changing the driver needs a reset first, and **`podman system reset` deletes every container, image, network and volume Podman has**, so only do this on a fresh setup or after saving what you need. Reset with the old setting, then switch:

```bash
podman system reset          # asks before it deletes anything
cat > /etc/containers/storage.conf <<'EOF'
[storage]
driver = "vfs"
EOF
```

## 3. Docker

Docker works the same way, it's just heavier. There's no systemd to start it, so start the daemon yourself in one tab and use it in another:

```bash
apt install docker.io
update-alternatives --set iptables /usr/sbin/iptables-legacy
update-alternatives --set ip6tables /usr/sbin/ip6tables-legacy
dockerd > /var/log/dockerd.log 2>&1 &
docker run --rm hello-world
```

If `dockerd` stops right away, the end of `/var/log/dockerd.log` says which kernel feature it misses.

## About the warnings

- **"PIDs limit discarded"** means the `pids` cgroup controller isn't there. Before 1.27.7 root mode had no cgroups at all. Now it mounts every controller the kernel has, so if you still see this, your kernel was built without `CONFIG_CGROUP_PIDS`.
- **Capabilities.** `podman info` lists the capabilities a *container* gets by default (`CAP_CHOWN`, `CAP_KILL`, `CAP_NET_BIND_SERVICE` and so on). `CAP_NET_ADMIN` and `CAP_SYS_ADMIN` aren't on that list on any Linux. It's Podman's default, not a Cortex limit. Add them per container with `--cap-add` when you need them.

If something here doesn't match what you see on your phone, open an issue with your phone model, kernel version (`uname -r`) and the error. That's how this page gets better. See [#72](https://github.com/ssfuisu/cortex/issues/72) for the first report.
