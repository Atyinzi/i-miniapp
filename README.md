# i某小程序项目

这是匿名化展示版本。初始化、微信 AppID 和历史迁移注意事项见 [PRIVACY.md](PRIVACY.md)。

本仓库是“i某小程序”微信小程序项目的统一交付仓库，包含后端、运营管理端、微信小程序、数据库迁移和项目交付资料。

## 目录

- `iminiapp-backend/`：Spring Boot 4、Java 17、MySQL、Redis、Flyway 后端。
- `iminiapp-admin/`：Vue 3、Vite 运营管理平台。
- `iminiapp-miniprogram-import-v7/`：微信原生小程序。
- 根目录文档：需求、架构、开发规范、联调验收与生产运维资料。

## 本地启动

1. 根据 `iminiapp-backend/application-local.properties.example` 创建仅供本机使用的 `application-local.properties`，真实密码和密钥不得提交。
2. 启动 MySQL 8 和 Redis 7。
3. 在 IDEA 运行后端 `IminiappBackendApplication`。
4. 在 `iminiapp-admin` 执行 `pnpm install` 和 `pnpm run dev`。
5. 使用微信开发者工具导入 `iminiapp-miniprogram-import-v7`。

## 验证命令

```text
后端：mvn clean test
管理端：pnpm run build
小程序：逐个运行 iminiapp-miniprogram-import-v7/tests/*.test.js
```

## 安全要求

- 生产数据库密码、Redis 密码、微信 AppSecret、支付密钥、个人数据加密密钥不得进入 Git。
- 本地配置、IDE 私有配置、微信开发者私有配置和接口令牌均由 `.gitignore` 排除。
- 已执行的 Flyway 迁移禁止改写，数据库变更必须新增更高版本迁移。
- 正式环境必须使用 `prod` Profile、HTTPS、独立数据库账号、Redis 密码和最小权限管理员账号。

详细上线门槛见 `正式交付与联调清单.md`，生产部署与回滚见 `生产部署回滚备份与运维手册.md`。
