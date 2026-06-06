#!/usr/bin/env bash
# 项目一键管理脚本
# 用法: ./project.sh {start|stop|restart|status|logs}
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
BACKEND_LOG="$LOG_DIR/backend.log"
FRONTEND_LOG="$LOG_DIR/frontend.log"
BACKEND_JAR="$BACKEND_DIR/target/backen-0.0.1-SNAPSHOT.jar"

BACKEND_PORT=8080
FRONTEND_PORT=3000

# 超时时间（秒），可通过环境变量覆盖
BACKEND_TIMEOUT="${BACKEND_TIMEOUT:-120}"
FRONTEND_TIMEOUT="${FRONTEND_TIMEOUT:-60}"
# Maven 打包超时（秒）
MVN_TIMEOUT="${MVN_TIMEOUT:-180}"

# Zotero 凭证：从 .env.local 读取，避免提交到 git
if [[ -f "$ROOT/.env.local" ]]; then
  set -a; source "$ROOT/.env.local"; set +a
fi
export ZOTERO_API_KEY="${ZOTERO_API_KEY:-}"
export ZOTERO_USER_ID="${ZOTERO_USER_ID:-}"
export ADMIN_KEY="${ADMIN_KEY:-change-me}"
export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17}"
export PATH="$JAVA_HOME/bin:$PATH"

if [[ -z "$ZOTERO_API_KEY" || -z "$ZOTERO_USER_ID" ]]; then
  echo "[!] 未检测到 Zotero 凭证，请在 $ROOT/.env.local 中设置 ZOTERO_API_KEY 和 ZOTERO_USER_ID"
fi
if [[ "$ADMIN_KEY" == "change-me" ]]; then
  echo "[!] ADMIN_KEY 仍为开发默认值，请在 $ROOT/.env.local 中设置 ADMIN_KEY"
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

start_backend() {
  if is_running "$BACKEND_PID"; then
    warn "后端已在运行 (pid=$(cat "$BACKEND_PID"))"
    return 0
  fi
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
    # 找到 src 目录下最近修改的文件时间
    local newest_src
    newest_src=$(find src -type f \( -name '*.java' -o -name '*.xml' -o -name '*.properties' -o -name '*.yml' \) -exec stat -f '%m' {} \; 2>/dev/null | sort -rn | head -1)
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
  nohup "$JAVA_HOME/bin/java" -jar "$BACKEND_JAR" >"$BACKEND_LOG" 2>&1 &
  echo $! >"$BACKEND_PID"
  disown "$!" 2>/dev/null || true
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
  nohup npm run dev -- --host >"$FRONTEND_LOG" 2>&1 &
  echo $! >"$FRONTEND_PID"
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
      all|"")      start_backend && start_frontend ;;
      *) err "未知目标: $target"; exit 1 ;;
    esac
    status
    ;;
  stop)
    case "$target" in
      backend|be)  stop_backend ;;
      frontend|fe) stop_frontend ;;
      all|"")      stop_frontend; stop_backend ;;
      *) err "未知目标: $target"; exit 1 ;;
    esac
    ;;
  restart)
    case "$target" in
      backend|be)  stop_backend; start_backend ;;
      frontend|fe) stop_frontend; start_frontend ;;
      all|"")      stop_frontend; stop_backend; start_backend; start_frontend ;;
      *) err "未知目标: $target"; exit 1 ;;
    esac
    status
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
  $0 status             查看运行状态
  $0 logs               实时跟随两个日志
  $0 logs backend       只看后端日志

环境变量:
  ZOTERO_API_KEY  / ZOTERO_USER_ID   覆盖默认 Zotero 凭证
  ADMIN_KEY                          后台管理密钥
  JAVA_HOME                          覆盖 JDK 路径（默认 /opt/homebrew/opt/openjdk@17）
EOF
    ;;
esac
