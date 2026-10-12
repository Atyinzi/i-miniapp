# i某小程序运营管理平台

基于 Vue 3 + Vite 的运营管理端。与 `iminiapp-backend` 分开部署，通过 `/api/v1` 调用后端接口。

## 启动

```powershell
corepack enable
corepack install --global pnpm@10.17.0
pnpm install
pnpm dev
```

默认访问地址：`http://localhost:5173`。开发环境会将 `/api` 代理到 `http://localhost:8080`。

## 第一阶段模块

- 登录与会话管理
- 运营概览
- 资源/库存管理
- 订单管理
- 电子核销

用户、角色权限、内容运营、数据报表和系统设置已在菜单中预留，待相应管理端 API 完成后逐模块接入。
