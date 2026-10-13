# 测试环境固定配置

集成测试统一使用 Spring `test` profile：

```powershell
$env:TEST_DB_URL='jdbc:mysql://localhost:3306/iminiapp_test?createDatabaseIfNotExist=true&useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai'
$env:TEST_DB_USERNAME='root'
$env:TEST_DB_PASSWORD=''
$env:TEST_REDIS_HOST='127.0.0.1'
$env:TEST_REDIS_PORT='6379'
.\mvnw.cmd "-Dmaven.repo.local=G:\iminiapp\.m2-build-cache" -Pintegration-test verify
```

默认值保存在 `src/test/resources/application-test.properties`，不会把真实密钥提交到仓库。需要更换数据库、Redis 或密钥时，只改上述环境变量。
