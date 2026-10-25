// A static Go program (built without cgo) that asks for Ubuntu's paths the way real ones do. It
// prints one "key=value" line for each thing it tries; tests/scripts/test_go_programs.sh runs it,
// with Cortex's patch, on a root file system of its own and compares the lines.
package main

import (
	"fmt"
	"net"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"time"
)

func show(key string, v interface{}, err error) {
	if err != nil {
		fmt.Printf("%s=ERR %v\n", key, err)
		return
	}
	fmt.Printf("%s=%v\n", key, v)
}

func first(b []byte) string {
	s := string(b)
	if i := strings.IndexByte(s, '\n'); i >= 0 {
		s = s[:i]
	}
	return s
}

func main() {
	if len(os.Args) > 1 && os.Args[1] == "ubuntu" {
		ubuntu()
		return
	}
	b, err := os.ReadFile("/etc/os-release")
	show("os-release", first(b), err)

	fi, err := os.Stat("/usr/bin/hello")
	if err == nil {
		show("stat-hello", fi.Mode().Perm()&0o100 != 0, nil)
	} else {
		show("stat-hello", nil, err)
	}

	entries, err := os.ReadDir("/")
	has := map[string]bool{}
	for _, e := range entries {
		has[e.Name()] = true
	}
	show("readdir-root", fmt.Sprintf("etc=%v usr=%v proc=%v", has["etc"], has["usr"], has["proc"]), err)

	_, err = os.Stat("/definitely/not/here")
	show("stat-missing", os.IsNotExist(err), nil)

	// A host path is left alone
	_, err = os.Stat("/proc/self/status")
	show("proc-status", err == nil, nil)

	// The pure Go resolver reads /etc/hosts
	addrs, err := net.LookupHost("fake.example")
	show("lookup-hosts", addrs, err)

	// Files made by the program are in the rootfs
	err = os.WriteFile("/tmp/go-wrote", []byte("data"), 0o600)
	show("write", err == nil, err)
	fi, err = os.Stat("/tmp/go-wrote")
	show("stat-written", fi != nil && fi.Size() == 4, err)
	show("glob", func() []string { m, _ := filepath.Glob("/tmp/go-w*"); return m }(), nil)
	err = os.Rename("/tmp/go-wrote", "/tmp/go-renamed")
	show("rename", err == nil, err)
	err = os.Remove("/tmp/go-renamed")
	show("remove", err == nil, err)
	err = os.MkdirAll("/tmp/go-dir/a/b", 0o755)
	show("mkdirall", err == nil, err)
	err = os.Symlink("/etc/os-release", "/tmp/go-dir/link")
	show("symlink", err == nil, err)
	target, err := os.Readlink("/tmp/go-dir/link")
	show("readlink", target, err)
	err = os.RemoveAll("/tmp/go-dir")
	show("removeall", err == nil, err)

	err = os.Chdir("/etc")
	show("chdir", err == nil, err)
	wd, err := os.Getwd()
	show("getwd", wd, err)

	// The Unix socket of a server is where the C library puts it
	_ = os.Remove("/tmp/go.sock")
	l, err := net.Listen("unix", "/tmp/go.sock")
	show("listen-unix", err == nil, err)
	if err == nil {
		go func() {
			c, e := l.Accept()
			if e == nil {
				c.Write([]byte("pong"))
				c.Close()
			}
		}()
		c, e := net.Dial("unix", "/tmp/go.sock")
		if e == nil {
			buf := make([]byte, 4)
			n, _ := c.Read(buf)
			show("dial-unix", string(buf[:n]), nil)
			c.Close()
		} else {
			show("dial-unix", nil, e)
		}
		l.Close()
		os.Remove("/tmp/go.sock")
	}

	exe, err := os.Executable()
	show("executable-is-absolute", filepath.IsAbs(exe), err)

	// Programs of the rootfs: by path, by name through PATH, with an argv[0] of their own, with an
	// environment of their own, in a directory of their own, and one that is not there
	run("exec-path", exec.Command("/usr/bin/hello", "one", "two words"))

	os.Setenv("PATH", "/usr/local/bin:/usr/bin:/bin")
	run("exec-lookpath", exec.Command("hello", "viapath"))

	cmd := exec.Command("/usr/bin/hello", "argv0")
	cmd.Args[0] = "renamed"
	run("exec-argv0", cmd)

	cmd = exec.Command("/usr/bin/hello", "ownenv")
	cmd.Env = []string{"HOME=/home/user"}
	run("exec-own-env", cmd)

	cmd = exec.Command("/usr/bin/hello")
	cmd.Dir = "/etc"
	run("exec-dir", cmd)

	run("exec-sh", exec.Command("/bin/sh", "-c", "echo from-sh; exit 4"))

	_, err = exec.Command("/usr/bin/not-a-program").Output()
	show("exec-missing", err != nil, nil)

	// A program of Android's is started as it is, without the hook in its environment
	if host := os.Getenv("GOTEST_HOST_PROGRAM"); host != "" {
		cmd = exec.Command(host)
		out, err := cmd.CombinedOutput()
		show("exec-host", fmt.Sprintf("%q", string(out)), err)
	}
}

// What a real Ubuntu root file system answers (the emulator test, tests/e2e/in-terminal.sh):
// the things Go programs of every kind do first
func ubuntu() {
	b, err := os.ReadFile("/etc/os-release")
	show("os-release", first(b), err)
	b, err = os.ReadFile("/etc/passwd")
	show("passwd-root", strings.HasPrefix(string(b), "root:"), err)
	b, err = os.ReadFile("/etc/resolv.conf")
	show("resolv-conf", strings.Contains(string(b), "nameserver"), err)
	_, err = os.Stat("/usr/bin/bash")
	show("stat-bash", err == nil, err)
	_, err = os.Stat("/usr/share/zoneinfo/UTC")
	show("stat-zoneinfo", err == nil, err)
	os.Chdir("/etc")
	wd, err := os.Getwd()
	show("getwd", wd, err)
	run("exec-uname", exec.Command("uname", "-m"))
	run("exec-env", exec.Command("/usr/bin/env", "echo", "hi"))
	run("exec-sh", exec.Command("/bin/sh", "-c", "echo $0; exit 3"))
	cmd := exec.Command("/usr/bin/env", "sh", "-c", "echo $0")
	cmd.Args[0] = "named"
	run("exec-argv0", cmd)
	// The name server of resolv.conf, and certificates, as Android's network has them
	addrs, err := net.LookupHost("archive.ubuntu.com")
	show("dns", len(addrs) > 0, err)
	client := &http.Client{Timeout: 30 * time.Second}
	resp, err := client.Get("https://archive.ubuntu.com/ubuntu/dists/noble/InRelease")
	if err == nil {
		resp.Body.Close()
		show("https", resp.StatusCode, nil)
	} else {
		show("https", nil, err)
	}
}

func run(key string, cmd *exec.Cmd) {
	out, err := cmd.Output()
	code := 0
	if ee, ok := err.(*exec.ExitError); ok {
		code = ee.ExitCode()
		err = nil
	}
	if err != nil {
		fmt.Printf("%s=ERR %v\n", key, err)
		return
	}
	fmt.Printf("%s=%s exit=%d\n", key, strings.TrimSpace(string(out)), code)
}
