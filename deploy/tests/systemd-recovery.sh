#!/usr/bin/env bash
# Destructive fault exercise ONLY for the dedicated disposable QA virtual machine.
set -euo pipefail
[[ "$(id -u)" == 0 && -f /etc/web-architecture-qa ]] || { echo 'Dedicated QA VM marker required' >&2; exit 1; }
INSTALL_DIR=/opt/web-qa
CONFIG_DIR=/etc/web-qa
SERVICE_NAME=web-qa
PACKAGE=/opt/qa-release/web-homepage
EVIDENCE=/opt/qa-evidence
mkdir -p "$EVIDENCE"
export INSTALL_DIR CONFIG_DIR SERVICE_NAME
export NGINX_SITE_NAME=web-qa DOMAIN=qa.local FORCE_NGINX_CONFIG=0

install_case() {
  local name="$1" expected="$2" destination="$3" result=0
  RECOVERY_BACKUP="$destination" bash "$PACKAGE/install-linux.sh" > "$EVIDENCE/$name.log" 2>&1 || result=$?
  [[ "$result" == "$expected" ]] || { echo "$name: expected $expected, got $result" >&2; return 1; }
}
health() {
  local attempt
  for attempt in $(seq 1 60); do
    if curl -fsS http://127.0.0.1:8080/api/health >/dev/null; then return 0; fi
    sleep 1
  done
  return 1
}
jar_hash() { sha256sum "$INSTALL_DIR/backen/backen.jar" | cut -d' ' -f1; }
lock="$INSTALL_DIR/.run/deployment.lock"
install_case first-install 0 "$EVIDENCE/first-backup"
health
[[ -f "$lock" ]]
bash "$PACKAGE/resume-admission.sh"
old_hash=$(jar_hash)
old_pid=$(systemctl show "$SERVICE_NAME" -p MainPID --value)

mkdir -p "$INSTALL_DIR/.run/image-generation-tasks/qa-fixture"
fixture="$INSTALL_DIR/.run/image-generation-tasks/qa-fixture/task.json"
printf '{"status":"generating"}' > "$fixture"
install_case active-task 42 "$EVIDENCE/unused-active"
[[ "$(systemctl show "$SERVICE_NAME" -p MainPID --value)" == "$old_pid" ]]
printf '{invalid' > "$fixture"
install_case corrupt-task 42 "$EVIDENCE/unused-corrupt"
python3 -c 'from pathlib import Path; Path("/opt/web-qa/.run/image-generation-tasks/qa-fixture/task.json").unlink()'
mysql web_qa -e "INSERT INTO matchmaking_tasks(id,user_id,payload) SELECT 'qa-pending',id,'{\"status\":\"error\",\"compensationPending\":true}' FROM users LIMIT 1"
install_case pending-refund 42 "$EVIDENCE/unused-pending"
mysql web_qa -e "DELETE FROM matchmaking_tasks WHERE id='qa-pending'"

install_case backup-failure 43 /proc/web-qa-forbidden
health
[[ ! -e "$lock" && "$(jar_hash)" == "$old_hash" ]]

mv "$PACKAGE/backen/backen.jar" "$PACKAGE/backen/backen.jar.saved"
install_case install-failure 1 "$EVIDENCE/failed-install-backup"
[[ -f "$lock" && "$(jar_hash)" == "$old_hash" ]]
[[ -f "$EVIDENCE/failed-install-backup/manifest.json" && ! -e "$EVIDENCE/failed-install-backup/INCOMPLETE" ]]
# This injected failure happened before changing any application file or schema.
mv "$PACKAGE/backen/backen.jar.saved" "$PACKAGE/backen/backen.jar"
systemctl start "$SERVICE_NAME"
health
[[ -f "$lock" ]]
bash "$PACKAGE/resume-admission.sh"
[[ ! -e "$lock" ]]
printf 'first install, active, corrupt, pending refund, backup failure, install failure and compatible restart: PASS\n' | tee "$EVIDENCE/result.txt"
