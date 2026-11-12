-- =============================================================================
--  Docker Compose 首次启动时的库与账号初始化（开发环境固定口令）
--
--  生产请改为由 KMS/Vault 注入，并分别使用最小权限账号：
--    dorm  只拥有 dorm_db
--    agent 只拥有 agent_db
-- =============================================================================

CREATE DATABASE IF NOT EXISTS `dorm_db`
  DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE IF NOT EXISTS `agent_db`
  DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

CREATE USER IF NOT EXISTS 'dorm'@'%' IDENTIFIED BY 'dorm_dev_password';
CREATE USER IF NOT EXISTS 'agent'@'%' IDENTIFIED BY 'agent_dev_password';

GRANT ALL PRIVILEGES ON `dorm_db`.* TO 'dorm'@'%';
GRANT ALL PRIVILEGES ON `agent_db`.* TO 'agent'@'%';
FLUSH PRIVILEGES;
