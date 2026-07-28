#!/usr/bin/env bash
# 项目一键管理脚本
# 用法: ./project.sh {start|stop|restart|status|logs|mysql}
#       ./project.sh start backend|frontend     只启动其中一个
#       ./project.sh logs backend|frontend       只看其中一个的日志

set -u

ROOT="$(cd "$(dirname "$0")" && pwd)"
BACKEND_DIR="$ROOT/backen"
FRONTEND_DIR="$ROOT/front"
RUN_DIR="$ROOT/.run"
LOG_DIR="$ROOT/.run/logs"
mkdir -p "$RUN_DIR" "$LOG_DIR"

BACKEND_PID="$RUN_DIR/backend.pid"
FRONTEND_PID="$RUN_DIR/frontend.pid"
MYSQL_STARTED_MARKER="$RUN_DIR/mysql.started"
BACKEND_LOG="$LOG_DIR/backend.log"
FRONTEND_LOG="$LOG_DIR/frontend.log"
BACKEND_JAR="$BACKEND_DIR/target/backen-0.0.1-SNAPSHOT.jar"

BACKEND_PORT=8080
FRONTEND_PORT=3000

# 超时时间（秒），可通过环境变量覆盖
BACKEND_TIMEOUT="${BACKEND_TIMEOUT:-120}"
FRONTEND_TIMEOUT="${FRONTEND_TIMEOUT:-60}"
# Maven 打包超时（秒）
MVN_TIMEOUT="${MVN_TIMEOUT:-300}"
# 本地数据库模式：auto（默认，按 DB_URL 自动管理 loopback MySQL）、mysql（强制 MySQL）、h2（强制 H2）
PROJECT_DB_MODE="${PROJECT_DB_MODE:-auto}"
PROJECT_MYSQL_SERVICE="${PROJECT_MYSQL_SERVICE:-mysql}"
MYSQL_TIMEOUT="${MYSQL_TIMEOUT:-30}"

# Zotero 凭证：从 .env.local 读取，避免提交到 git
if [[ -f "$ROOT/.env.local" ]]; then
  set -a; source "$ROOT/.env.local"; set +a
fi
export ZOTERO_API_KEY="${ZOTERO_API_KEY:-}"
export ZOTERO_USER_ID="${ZOTERO_USER_ID:-}"
export ADMIN_KEY="${ADMIN_KEY:-change-me}"
export ROOT_USERNAME="${ROOT_USERNAME:-root}"
export ROOT_PASSWORD="${ROOT_PASSWORD:-}"
export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17}"
export PATH="$JAVA_HOME/bin:$PATH"

if [[ -z "$ZOTERO_API_KEY" || -z "$ZOTERO_USER_ID" ]]; then
  echo "[!] 未检测到 Zotero 凭证，请在 $ROOT/.env.local 中设置 ZOTERO_API_KEY 和 ZOTERO_USER_ID"
fi
if [[ "$ADMIN_KEY" == "change-me" ]]; then
  echo "[!] ADMIN_KEY 仍为开发默认值，请在 $ROOT/.env.local 中设置 ADMIN_KEY"
fi
if [[ -z "$ROOT_PASSWORD" || ${#ROOT_PASSWORD} -lt 6 ]]; then
  echo "[!] ROOT_PASSWORD 未配置或长度不足 6 位。由于当前后端会在首次启动时自动初始化 root，project.sh start backend 会直接失败，而不是等到健康检查超时。"
  echo "[!] 请先在 $ROOT/.env.local 中设置 ROOT_PASSWORD（建议同时设置 ROOT_USERNAME），再重试。"
fi

# 颜色
GREEN="\033[32m"; RED="\033[31m"; YELLOW="\033[33m"; CYAN="\033[36m"; RESET="\033[0m"

info()  { echo -e "${CYAN}[i]${RESET} $*"; }
ok()    { echo -e "${GREEN}[✓]${RESET} $*"; }
warn()  { echo -e "${YELLOW}[!]${RESET} $*"; }
err()   { echo -e "${RED}[x]${RESET} $*"; }

is_running() {
  local pid_file="$1"
  [[ -f "$pid_file" ]] || return 1
  local pid
  pid="$(cat "$pid_file" 2>/dev/null)"
  [[ -n "$pid" ]] && kill -0 "$pid" 2>/dev/null
}

kill_port() {
  local port="$1"
  local pids
  pids="$(lsof -ti:"$port" 2>/dev/null || true)"
  if [[ -n "$pids" ]]; then
    echo "$pids" | xargs kill -9 2>/dev/null || true
  fi
}

wait_http() {
  local url="$1" name="$2" max="${3:-60}"
  for ((i=1;i<=max;i++)); do
    if curl -fs -o /dev/null "$url" 2>/dev/null; then
      ok "$name 就绪 (${i}s)"
      return 0
    fi
    sleep 1
  done
  err "$name 启动超时（${max}s）"
  return 1
}

check_tcp_port() {
  local host="$1" port="$2"
  command -v python3 >/dev/null 2>&1 || return 1
  python3 - "$host" "$port" <<'PY'
import socket
import sys

try:
    with socket.create_connection((sys.argv[1], int(sys.argv[2])), timeout=1.0):
        pass
except OSError:
    raise SystemExit(1)
PY
}

is_loopback_host() {
  [[ "$1" == "127.0.0.1" || "$1" == "localhost" || "$1" == "::1" ]]
}

start_mysql_service() {
  local host="${1:-127.0.0.1}" port="${2:-3306}"
  if check_tcp_port "$host" "$port"; then
    ok "MySQL $host:$port 已在运行"
    return 0
  fi
  if ! is_loopback_host "$host"; then
    err "MySQL $host:$port 不在本机，project.sh 不会替你启动远程数据库。"
    return 1
  fi
  if ! command -v brew &>/dev/null; then
    err "未找到 Homebrew，无法自动启动 MySQL 服务 $PROJECT_MYSQL_SERVICE。"
    err "请安装/启动 MySQL，或设置 PROJECT_DB_MODE=h2 使用本地 H2。"
    return 1
  fi

  info "启动 MySQL 服务 ($PROJECT_MYSQL_SERVICE) ..."
  if ! brew services start "$PROJECT_MYSQL_SERVICE"; then
    err "MySQL 服务启动失败：$PROJECT_MYSQL_SERVICE"
    return 1
  fi
  for ((i=1;i<=MYSQL_TIMEOUT;i++)); do
    if check_tcp_port "$host" "$port"; then
      printf '%s\n' "$PROJECT_MYSQL_SERVICE" >"$MYSQL_STARTED_MARKER"
      ok "MySQL $host:$port 就绪 (${i}s)"
      return 0
    fi
    sleep 1
  done
  err "MySQL $host:$port 启动超时（${MYSQL_TIMEOUT}s），查看 brew services list 或 MySQL 日志。"
  return 1
}

stop_mysql_service() {
  local force="${1:-false}"
  if [[ "$force" != "true" && ! -f "$MYSQL_STARTED_MARKER" ]]; then
    return 0
  fi
  if ! command -v brew &>/dev/null; then
    warn "未找到 Homebrew，无法停止 MySQL 服务 $PROJECT_MYSQL_SERVICE。"
    rm -f "$MYSQL_STARTED_MARKER"
    return 1
  fi
  info "停止 MySQL 服务 ($PROJECT_MYSQL_SERVICE) ..."
  local service_log="$LOG_DIR/mysql-service.log"
  if [[ "$(uname -s)" == "Darwin" ]] && command -v launchctl &>/dev/null; then
    # brew services stop 在部分 macOS/Homebrew 组合下会让调用它的脚本会话收到 HUP。
    # 直接 bootout 当前用户的 launch agent，行为等价但不会带走 project.sh。
    local launch_label="homebrew.mxcl.$PROJECT_MYSQL_SERVICE"
    python3 - "$service_log" "gui/$(id -u)/$launch_label" <<'PY'
import subprocess
import sys

log_path, domain_target = sys.argv[1:]
with open(log_path, "wb") as output:
    result = subprocess.run(
        ["launchctl", "bootout", domain_target],
        stdout=output,
        stderr=subprocess.STDOUT,
        start_new_session=True,
    )
# launch agent 已不存在时也视为已停止；随后由端口检查确认最终状态。
raise SystemExit(0)
PY
  else
    brew services stop "$PROJECT_MYSQL_SERVICE" >"$service_log" 2>&1 &
    local brew_pid="$!"
    if ! wait "$brew_pid"; then
      cat "$service_log"
      err "MySQL 服务停止失败：$PROJECT_MYSQL_SERVICE"
      return 1
    fi
  fi
  cat "$service_log"
  for ((i=1;i<=MYSQL_TIMEOUT;i++)); do
    if ! check_tcp_port "127.0.0.1" "3306"; then
      break
    fi
    sleep 1
  done
  if check_tcp_port "127.0.0.1" "3306"; then
    err "MySQL 端口 3306 停止超时，请检查 brew services 或 launchctl 状态。"
    return 1
  fi
  rm -f "$MYSQL_STARTED_MARKER"
  ok "MySQL 服务已停止"
}

prepare_local_database() {
  case "$PROJECT_DB_MODE" in
    h2)
      info "本地数据库模式：H2（PROJECT_DB_MODE=h2）"
      unset DB_URL DB_DRIVER DB_USERNAME DB_PASSWORD
      return 0
      ;;
    auto|mysql) ;;
    *)
      err "PROJECT_DB_MODE 只能是 auto、mysql 或 h2（当前：$PROJECT_DB_MODE）"
      return 1
      ;;
  esac

  local configured_url="${DB_URL:-}"
  if [[ "$PROJECT_DB_MODE" == "mysql" && "$configured_url" != jdbc:mysql://* ]]; then
    err "PROJECT_DB_MODE=mysql 需要 DB_URL 配置为 jdbc:mysql://..."
    return 1
  fi
  [[ "$configured_url" == jdbc:mysql://* ]] || return 0

  local endpoint host port
  endpoint="${configured_url#jdbc:mysql://}"
  endpoint="${endpoint%%\?*}"
  endpoint="${endpoint%%/*}"
  host="${endpoint%%:*}"
  port="${endpoint#*:}"
  [[ "$port" == "$endpoint" ]] && port=3306

  if check_tcp_port "$host" "$port"; then
    info "本地数据库：MySQL $host:$port 可连接"
    return 0
  fi

  if [[ "$PROJECT_DB_MODE" == "auto" || "$PROJECT_DB_MODE" == "mysql" ]]; then
    start_mysql_service "$host" "$port"
    return $?
  fi

  return 0
}

start_backend() {
  if is_running "$BACKEND_PID"; then
    warn "后端已在运行 (pid=$(cat "$BACKEND_PID"))"
    return 0
  fi
  if [[ -z "$ROOT_PASSWORD" || ${#ROOT_PASSWORD} -lt 6 ]]; then
    err "后端首次启动需要 ROOT_PASSWORD 至少 6 位；当前未配置或过短，无法完成 root 初始化。"
    err "请先在 $ROOT/.env.local 中设置 ROOT_PASSWORD 后再执行 ./project.sh start backend"
    return 1
  fi
  prepare_local_database || return 1
  info "启动后端 ..."
  kill_port "$BACKEND_PORT"

  # JAVA_HOME 有效性检查
  if [[ ! -x "$JAVA_HOME/bin/java" ]]; then
    err "找不到 Java: $JAVA_HOME/bin/java"
    return 1
  fi
  ok "Java: $("$JAVA_HOME/bin/java" -version 2>&1 | head -1)"

  cd "$BACKEND_DIR"

  # 只在源码有更新时才重新打包（跳过 tests）
  local need_build=true
  if [[ -f "$BACKEND_JAR" ]]; then
    # 找到 src/scripts 目录下最近修改的文件时间。scripts 也会被打进 jar，
    # 例如 PPT renderer / template-fill 脚本变更必须触发重打包。
    local newest_src
    newest_src=$(find src scripts -type f \( -name '*.java' -o -name '*.xml' -o -name '*.properties' -o -name '*.yml' -o -name '*.py' -o -name '*.json' \) -exec stat -f '%m' {} \; 2>/dev/null | sort -rn | head -1)
    local jar_time
    jar_time=$(stat -f '%m' "$BACKEND_JAR" 2>/dev/null)
    if [[ -n "$newest_src" && -n "$jar_time" ]] && [[ "$jar_time" -ge "$newest_src" ]]; then
      ok "JAR 已是最新，跳过打包"
      need_build=false
    fi
  fi

  if $need_build; then
    info "打包后端 (超时: ${MVN_TIMEOUT}s) ..."
    # 用 timeout 限制打包时长，去掉 -q 以便看到进度
    if command -v timeout &>/dev/null; then
      timeout "$MVN_TIMEOUT" mvn -DskipTests package || { err "Maven 打包失败或超时"; cd - >/dev/null; return 1; }
    else
      mvn -DskipTests package || { err "Maven 打包失败"; cd - >/dev/null; return 1; }
    fi
    ok "打包完成"
  fi

  # 启动 Spring Boot
  info "启动 Java 进程 ..."
  local java_cmd=("$JAVA_HOME/bin/java" -jar "$BACKEND_JAR")
  local spawned_pid=""
  if command -v setsid &>/dev/null; then
    nohup setsid "${java_cmd[@]}" >"$BACKEND_LOG" 2>&1 < /dev/null &
    spawned_pid="$!"
    echo "$spawned_pid" >"$BACKEND_PID"
  elif command -v python3 &>/dev/null; then
    JAVA_BIN="${java_cmd[0]}" BACKEND_JAR="$BACKEND_JAR" BACKEND_LOG="$BACKEND_LOG" BACKEND_PID="$BACKEND_PID" python3 - <<'PY'
import os
import sys

java_bin = os.environ["JAVA_BIN"]
jar_path = os.environ["BACKEND_JAR"]
log_path = os.environ["BACKEND_LOG"]
pid_path = os.environ["BACKEND_PID"]

pid = os.fork()
if pid:
    with open(pid_path, "w", encoding="utf-8") as handle:
        handle.write(str(pid))
    sys.exit(0)

os.setsid()
with open(os.devnull, "rb", buffering=0) as stdin, open(log_path, "wb", buffering=0) as log:
    os.dup2(stdin.fileno(), 0)
    os.dup2(log.fileno(), 1)
    os.dup2(log.fileno(), 2)
os.execv(java_bin, [java_bin, "-jar", jar_path])
PY
  else
    nohup "${java_cmd[@]}" >"$BACKEND_LOG" 2>&1 < /dev/null &
    spawned_pid="$!"
    echo "$spawned_pid" >"$BACKEND_PID"
  fi
  if [[ -n "$spawned_pid" ]]; then
    disown "$spawned_pid" 2>/dev/null || true
  fi
  cd - >/dev/null

  info "等待后端就绪 (超时: ${BACKEND_TIMEOUT}s) ..."
  wait_http "http://localhost:$BACKEND_PORT/api/health" "后端" "$BACKEND_TIMEOUT" || {
    warn "后端可能仍在启动中，查看日志: tail -f $BACKEND_LOG"
    return 1
  }
}

start_frontend() {
  if is_running "$FRONTEND_PID"; then
    warn "前端已在运行 (pid=$(cat "$FRONTEND_PID"))"
    return 0
  fi
  info "启动前端 ..."
  kill_port "$FRONTEND_PORT"
  cd "$FRONTEND_DIR"

  if [[ ! -d node_modules ]]; then
    info "首次运行，安装依赖 ..."
    npm install || { err "npm install 失败"; cd - >/dev/null; return 1; }
  fi

  # 检查依赖是否完整（vite 可执行）
  if [[ ! -x node_modules/.bin/vite ]]; then
    warn "vite 未找到，重新安装依赖 ..."
    npm install || { err "npm install 失败"; cd - >/dev/null; return 1; }
  fi

  # 启动 vite
  info "启动 Vite 开发服务器 ..."
  local spawned_pid=""
  if command -v setsid &>/dev/null; then
    nohup setsid npm run dev -- --host >"$FRONTEND_LOG" 2>&1 < /dev/null &
    spawned_pid="$!"
  else
    nohup npm run dev -- --host >"$FRONTEND_LOG" 2>&1 < /dev/null &
    spawned_pid="$!"
  fi
  echo "$spawned_pid" >"$FRONTEND_PID"
  disown "$spawned_pid" 2>/dev/null || true
  cd - >/dev/null

  info "等待前端就绪 (超时: ${FRONTEND_TIMEOUT}s) ..."
  wait_http "http://localhost:$FRONTEND_PORT" "前端" "$FRONTEND_TIMEOUT" || {
    warn "前端可能仍在启动中，查看日志: tail -f $FRONTEND_LOG"
    return 1
  }
}

stop_backend() {
  if is_running "$BACKEND_PID"; then
    local pid; pid="$(cat "$BACKEND_PID")"
    info "停止后端 (pid=$pid) ..."
    kill "$pid" 2>/dev/null || true
    sleep 2
    kill -9 "$pid" 2>/dev/null || true
  fi
  kill_port "$BACKEND_PORT"
  rm -f "$BACKEND_PID"
  ok "后端已停止"
}

stop_frontend() {
  if is_running "$FRONTEND_PID"; then
    local pid; pid="$(cat "$FRONTEND_PID")"
    info "停止前端 (pid=$pid) ..."
    # vite 是 npm 的子进程，要带子进程组一起杀
    pkill -P "$pid" 2>/dev/null || true
    kill "$pid" 2>/dev/null || true
    sleep 1
    kill -9 "$pid" 2>/dev/null || true
  fi
  kill_port "$FRONTEND_PORT"
  rm -f "$FRONTEND_PID"
  ok "前端已停止"
}

status_mysql() {
  if check_tcp_port "127.0.0.1" "3306"; then
    local ownership=""
    [[ -f "$MYSQL_STARTED_MARKER" ]] && ownership="（本次由 project.sh 启动）"
    ok "MySQL: 运行中 (127.0.0.1:3306) $ownership"
  else
    warn "MySQL: 未运行 (127.0.0.1:3306)"
  fi
}

status() {
  echo "----- 项目状态 -----"
  if is_running "$BACKEND_PID"; then
    ok "后端: 运行中 (pid=$(cat "$BACKEND_PID"), http://localhost:$BACKEND_PORT)"
  else
    warn "后端: 未运行"
  fi
  if is_running "$FRONTEND_PID"; then
    ok "前端: 运行中 (pid=$(cat "$FRONTEND_PID"), http://localhost:$FRONTEND_PORT)"
  else
    warn "前端: 未运行"
  fi
  status_mysql
  echo "日志目录: $LOG_DIR"
}

logs() {
  local target="${1:-all}"
  case "$target" in
    backend|be) tail -f "$BACKEND_LOG" ;;
    frontend|fe) tail -f "$FRONTEND_LOG" ;;
    all|"") tail -f "$BACKEND_LOG" "$FRONTEND_LOG" ;;
    *) err "未知目标: $target"; exit 1 ;;
  esac
}

cmd="${1:-}"
target="${2:-all}"

case "$cmd" in
  start)
    case "$target" in
      backend|be)  start_backend ;;
      frontend|fe) start_frontend ;;
      mysql|db)    start_mysql_service ;;
      all|"")      start_backend && start_frontend ;;
      *) err "未知目标: $target"; exit 1 ;;
    esac
    status
    ;;
  stop)
    case "$target" in
      backend|be)  stop_backend ;;
      frontend|fe) stop_frontend ;;
      mysql|db)    stop_mysql_service true ;;
      all|"")      stop_frontend; stop_backend; stop_mysql_service ;;
      *) err "未知目标: $target"; exit 1 ;;
    esac
    ;;
  restart)
    case "$target" in
      backend|be)  stop_backend; start_backend ;;
      frontend|fe) stop_frontend; start_frontend ;;
      mysql|db)    stop_mysql_service true; start_mysql_service ;;
      all|"")      stop_frontend; stop_backend; stop_mysql_service; start_backend; start_frontend ;;
      *) err "未知目标: $target"; exit 1 ;;
    esac
    status
    ;;
  mysql)
    case "$target" in
      start|"") start_mysql_service ;;
      stop)     stop_mysql_service true ;;
      restart)  stop_mysql_service true; start_mysql_service ;;
      status)   status_mysql ;;
      *) err "用法: $0 mysql {start|stop|restart|status}"; exit 1 ;;
    esac
    ;;
  status) status ;;
  logs)   logs "$target" ;;
  *)
    cat <<EOF
用法: $0 {start|stop|restart|status|logs} [backend|frontend|all]

示例:
  $0 start              一键启动前后端
  $0 stop               一键停止
  $0 restart            一键重启
  $0 restart backend    只重启后端
  $0 mysql start        启动本地 MySQL 服务
  $0 mysql stop         停止本地 MySQL 服务
  $0 status             查看运行状态
  $0 logs               实时跟随两个日志
  $0 logs backend       只看后端日志

环境变量:
  ZOTERO_API_KEY  / ZOTERO_USER_ID   覆盖默认 Zotero 凭证
  ADMIN_KEY                          后台管理密钥
  JAVA_HOME                          覆盖 JDK 路径（默认 /opt/homebrew/opt/openjdk@17）
  PROJECT_DB_MODE                    auto（默认）/ mysql / h2；auto/mysql 会自动启动本地 MySQL
  PROJECT_MYSQL_SERVICE              Homebrew 服务名（默认 mysql）
  MYSQL_TIMEOUT                      MySQL 启动等待秒数（默认 30）
EOF
    ;;
esac
