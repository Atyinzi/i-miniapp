# i某小程序

匿名化的微信小程序全栈项目，包含 Java 后端、Vue 运营管理端和原生微信小程序。仓库名称和代码包名使用 `iminiapp`。本仓库用于代码展示与独立开发；生产配置、原始交付文档和真实微信 AppID 不在公开版本中。迁移说明见 [PRIVACY.md](PRIVACY.md)。

## 项目结构

| 路径 | 内容 |
| --- | --- |
| `iminiapp-backend/` | Spring Boot 4、Java 17、MySQL、Redis、Flyway REST API |
| `iminiapp-admin/` | Vue 3、Vite 管理端 |
| `iminiapp-miniprogram-import-v7/` | 微信原生小程序及 Node.js 测试 |
| `compose.test.yml` | 仅用于本地验证的 MySQL 和 Redis |

## 本地运行

需要 JDK 17、Node.js 20、pnpm 10。完整集成验证还需要 Docker。以下命令从仓库根目录执行。

1. 启动测试用依赖：

   ```bash
   docker compose -f compose.test.yml up -d --wait
   ```

2. 配置后端。本地启动请根据 `iminiapp-backend/application-local.properties.example` 创建被 Git 忽略的 `application-local.properties`，并填写自己的数据库、Redis 和应用密钥。`test` profile 只供测试使用，不应用于部署。
3. 在 `iminiapp-backend` 中运行 `./mvnw spring-boot:run`，Windows 使用 `mvnw.cmd spring-boot:run`。按本地配置设置 Spring profile 和环境变量。
4. 在 `iminiapp-admin` 中运行 `pnpm install --frozen-lockfile` 和 `pnpm dev`。
5. 用微信开发者工具导入 `iminiapp-miniprogram-import-v7`。仓库中的 `touristappid` 是匿名化占位值；真实调试请使用自己的 AppID，并通过本地私有配置管理。

## 验证

后端单元测试（不要求 MySQL/Redis）：

```bash
cd iminiapp-backend
./mvnw test
```

后端完整集成测试（先启动 `compose.test.yml` 中的服务）：

```bash
cd iminiapp-backend
TEST_DB_URL='jdbc:mysql://127.0.0.1:3306/iminiapp_test?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai' TEST_DB_USERNAME=root TEST_DB_PASSWORD=root ./mvnw -Pintegration-test verify
```

Windows PowerShell 可先设置 `$env:TEST_DB_URL`、`$env:TEST_DB_USERNAME`、`$env:TEST_DB_PASSWORD`，再执行 `.\mvnw.cmd -Pintegration-test verify`。以上 `root` 仅是本地测试容器的示例密码，请勿用于生产。

管理端构建：

```bash
cd iminiapp-admin
pnpm install --frozen-lockfile
pnpm run build
```

小程序测试：

```bash
cd iminiapp-miniprogram-import-v7
for file in tests/*.test.js; do node "$file"; done
```

GitHub Actions 的 [Verify project](.github/workflows/verify.yml) 在推送和 PR 时分别执行后端单元及集成测试、管理端构建、小程序测试。失败时上传后端测试报告。

## 安全与部署

- 不要提交数据库密码、Redis 密码、微信 AppSecret、支付密钥或真实用户数据。
- 匿名化后的迁移文件校验和已变化。必须在**新数据库**初始化，不能直接覆盖旧项目数据库。
- 上线前需使用独立的生产账号、HTTPS、真实 AppID、外部服务配置和经审核的图片素材；参见 [PRIVACY.md](PRIVACY.md) 与 [正式交付与联调清单.md](正式交付与联调清单.md)。
