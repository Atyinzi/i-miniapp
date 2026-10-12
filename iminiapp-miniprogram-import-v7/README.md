# i某小程序小程序

## 导入

使用微信开发者工具导入项目根目录；`project.config.json` 的 `miniprogramRoot` 已配置为 `miniprogram/`。

## 协作约束

- 所有接口经 `miniprogram/utils/request.js` 调用，并统一处理 `{ code, message, data }`。
- JSON 字段使用小驼峰；时间用 `yyyy-MM-dd HH:mm:ss`；金额用分整数，展示时除以 100。
- 请求头统一带 `Authorization: Bearer <token>`；环境 URL 仅在 `miniprogram/config/env.js` 切换。
- 创建预约传 `clientRequestId` 实现幂等；接口契约位于 `docs/openapi.yaml`。
- 微信开发者工具默认连接 `http://127.0.0.1:8080`，开发环境也使用真实 `wx.login`；Node 契约测试因没有微信运行时会自动使用 Mock。体验版和正式版同样使用微信登录。
- Node 契约测试没有 `wx.request` 运行时，会自动使用 service 内 Mock，不影响本地后端联调。
