# AuthMe × Agent Mail 集成开发心得与技术要点

> 本文档记录了将腾讯 Agent Mail CLI 集成到 AuthMe 登录插件，并实现"先验证邮箱再设密码"两阶段注册功能的完整开发过程、踩坑经验和技术要点。

---

## 一、项目背景

- **目标**：用腾讯 Agent Mail（`@agent.qq.com` 专属邮箱，通过 `agently-cli` 发信）替代传统 SMTP，作为 AuthMe 的邮件发送通道；并新增"注册时先验证邮箱地址，验证通过后才允许设置密码"的流程。
- **项目**：AuthMeReloaded (fork from AuthMe/AuthMeReloaded)
- **构建工具**：Maven (多模块)
- **DI 框架**：ch.jalu:injector (非 Dagger)
- **MC 服务器**：Paper 1.21.x

---

## 二、架构改造：发信通道抽象

### 2.1 设计思路

AuthMe 原有的 `SendMailSsl` 直接依赖 `org.apache.commons.mail.HtmlEmail` 实现 SMTP 发信，`EmailService` 硬编码依赖 `SendMailSsl`。要支持 Agent Mail，需要引入接口抽象。

### 2.2 实现方案

1. **新增 `MailSender` 接口**：
   ```java
   public interface MailSender {
       boolean hasAllInformation();
       boolean sendMail(String recipient, String subject, String htmlContent, File imageFile);
   }
   ```

2. **`SendMailSsl` 实现该接口**：保持原有 SMTP 逻辑不变，新增 `sendMail` 方法封装邮件构造和发送。

3. **新增 `AgentMailSender` 实现**：通过 `ProcessBuilder` 调用 `agently-cli` 命令行工具发信。

4. **`EmailService` 依赖从 `SendMailSsl` 改为 `MailSender` 接口**：所有发信方法（`sendPasswordMail`, `sendVerificationMail`, `sendEmailConfirmationMail`, `sendRecoveryCode`）底层调用 `mailSender.sendMail()`，不关心具体实现。

5. **`MailSenderProvider` 实现 DI 选择**：
   ```java
   public class MailSenderProvider implements Provider<MailSender> {
       public MailSender get() {
           String sender = settings.getProperty(EmailSettings.MAIL_SENDER);
           if ("agent_mail".equalsIgnoreCase(sender)) {
               return agentMailSender;
           }
           return sendMailSsl;
       }
   }
   ```
   在 `AuthMe.java` 中注册：`injector.registerProvider(MailSender.class, MailSenderProvider.class)`

### 2.3 技术要点

- **Jalu Injector 的 Provider 机制**：通过 `registerProvider` 可以让接口绑定到动态选择实现的 Provider，而不是静态绑定到某个类。
- **不破坏现有功能**：默认 `mailSender: smtp` 时走原逻辑，零影响。
- **测试适配**：`EmailServiceTest` 需要从 mock `SendMailSsl` 改为 mock `MailSender` 接口。

---

## 三、Agent Mail CLI 集成的三个坑

### 3.1 两阶段确认机制

**问题**：`agently-cli message +send` 不是一次调用就发送，而是两阶段：
- **Phase 1**：调用发送命令，返回 `confirmation_token` 和邮件摘要
- **Phase 2**：带 `--confirmation-token` 再次调用，才真正发送

**误判**：Phase 1 的 JSON 输出包含 `"ok": true`，最初代码将其当作成功信号，跳过了 Phase 2，导致邮件从未真正发出。

**修复**：
- Phase 1 后提取 `confirmation_token`
- Phase 2 带上 token 再次调用
- 成功判断：`"queued": true` 或 `"ok": true` 且不含 `confirmation_required` 字段

### 3.2 Windows .cmd 包装器导致参数丢失

**问题**：npm 全局安装在 Windows 上生成的是 `agently-cli.cmd` 包装器。Java 的 `ProcessBuilder` 不会自动解析 `.cmd` 扩展名，最初报 `CreateProcess error=2`。

**第一次修复**：自动追加 `.cmd` 扩展名。但发现 Phase 2 仍然失败——带 `--confirmation-token` 的调用又返回了新的 `confirmation_required`。

**根因**：`.cmd` 包装器通过 cmd.exe 执行，而邮件 HTML body 中的 `<html>`、`<body>` 等 `<` `>` 字符被 cmd.exe 当作重定向操作符处理，导致参数截断，`--confirmation-token` 参数丢失。

**最终修复**：绕过 `.cmd` 包装器，解析出 JS 入口文件路径，直接用 `node` 执行：
```
node "C:\...\npm\node_modules\@tencent-qqmail\agently-cli\scripts\run.js" message +send --to ... --body <html>... --confirmation-token <token>
```
`ProcessBuilder` 直接调 `node` 不会经过 cmd.exe，HTML 中的特殊字符安全传递。

### 3.3 agently-cli 授权过期

**问题**：运行时报认证失败。

**解决**：在服务器终端执行 `agently-cli auth login`，微信扫码重新授权。token 持久化后无需重复操作。

---

## 四、两阶段邮箱验证注册

### 4.1 设计挑战

AuthMe 原有的 `AsyncRegister.register()` 假设一次调用完成"构造 auth → saveAuth → 后置动作"。但"先验证邮箱再设密码"需要两个阶段：
- **阶段一**：提交邮箱，发验证码，**不落库**
- **阶段二**：验证码 + 密码，验证通过后**才落库**

原架构无法直接套用。

### 4.2 实现方案

1. **新增 `RegistrationType.EMAIL_VERIFIED_PASSWORD` 枚举值**

2. **新增 `PendingRegistrationCache`**：内存缓存待完成注册，存储 `{email, code, expiresAt}`，TTL 10 分钟。仿照已有的 `PendingEmailVerificationCache` 实现。

3. **`RegisterCommand` 命令层分流**：
   - `/register <邮箱>` → 阶段一：校验邮箱、生成 6 位码、发邮件、存入 cache。**不进入 `AsyncRegister`**。
   - `/register verify <码> <密码>` → 阶段二：从 cache 取条目、校验码、通过后调用 `management.performRegister(EMAIL_VERIFIED_PASSWORD_REGISTRATION, params)` 进入 `AsyncRegister` 完成落库。

4. **新增 `EmailVerifiedRegisterExecutor`**：继承 `AbstractPasswordRegisterExecutor`，复用密码校验和自动登录逻辑，构造含已验证邮箱的 `PlayerAuth`。

5. **`RegistrationMethod` 新增常量**：
   ```java
   public static final RegistrationMethod<EmailVerifiedRegisterParams> EMAIL_VERIFIED_PASSWORD_REGISTRATION =
       new RegistrationMethod<>(EmailVerifiedRegisterExecutor.class);
   ```
   Jalu Injector 的 `SingletonStore` 会按 Class 自动创建执行器单例，无需手动注册工厂。

### 4.3 技术要点

- **阶段一不走 AsyncRegister**：避免在"不落库"的情况下强行实现 `buildPlayerAuth`。
- **复用现有模板**：`EmailService.sendEmailConfirmationMail` 已使用 `email_confirmation_email.html` 模板，含 `<generatedcode />` 占位符，直接复用。
- **多语言消息键**：在 `MessageKey.java` 枚举中新增 `REGISTRATION_VERIFY_EMAIL_SENT`、`USAGE_REGISTER_VERIFY`、`REGISTRATION_VERIFY_WRONG_CODE`、`REGISTRATION_VERIFY_EXPIRED`，并在 `messages_*.yml` 中补充翻译。
- **Javadoc 转义**：`MessageKey.java` 的注释中 `<code>` `<password>` 需要用 `{@literal <code>}` 转义，否则 Javadoc 编译失败。

---

## 五、邮件模板自定义

AuthMe 的邮件模板是 HTML 文件，位于插件数据目录 `plugins/AuthMe/`：

| 文件 | 用途 |
|---|---|
| `email.html` | 密码邮件（注册/找回） |
| `email_confirmation_email.html` | 邮箱确认/注册验证码 |
| `recovery_code_email.html` | 密码恢复码 |

可用占位符：
- `<playername />` — 玩家名
- `<servername />` — 服务器名
- `<generatedcode />` — 验证码
- `<minutesvalid />` — 有效分钟数
- `<generatedpass />` — 生成的密码
- `<recoverycode />` — 恢复码
- `<hoursvalid />` — 恢复码有效小时数

首次启动时插件会从 jar 包解压模板文件到插件目录，之后直接编辑文件即可修改邮件样式。

---

## 六、使用指南

### 6.1 配置

```yaml
Email:
  mailSender: agent_mail              # 使用 Agent Mail CLI
  agentMailCliPath: agently-cli       # 或绝对路径
  agentMailTimeoutSeconds: 30

settings:
  registration:
    type: email_verified_password     # 先验证邮箱再设密码
```

### 6.2 部署前置

1. 安装 Node.js LTS
2. `npm install -g @tencent-qqmail/agently-cli`
3. `agently-cli auth login` 微信扫码授权
4. `agently-cli +me` 验证

### 6.3 玩家指令

| 操作 | 命令 |
|---|---|
| 注册（提交邮箱） | `/register <邮箱>` |
| 注册（验证并设密码） | `/register verify <验证码> <密码>` |
| 登录 | `/login <密码>` |
| 忘记密码 | `/email recover <邮箱>` |
| 输入恢复码 | `/email code <恢复码>` |
| 重置密码 | `/email setpassword <新密码>` |

### 6.4 限制

- Agent Mail 每邮箱每天 50 封发信上限（适合小服/测试）
- 必须安装 Node.js + agently-cli 并完成微信扫码授权
- 发件人固定为 `@agent.qq.com` 后缀

---

## 七、代码结构总览

### 新增文件

| 文件 | 作用 |
|---|---|
| `mail/MailSender.java` | 发信接口 |
| `mail/AgentMailSender.java` | Agent Mail CLI 实现 |
| `initialization/MailSenderProvider.java` | DI Provider 选择实现 |
| `service/PendingRegistrationCache.java` | 待注册内存缓存 |
| `process/register/executors/EmailVerifiedRegisterParams.java` | 阶段二参数 |
| `process/register/executors/EmailVerifiedRegisterExecutor.java` | 阶段二执行器 |

### 修改文件

| 文件 | 改动 |
|---|---|
| `mail/SendMailSsl.java` | 实现 MailSender 接口 |
| `mail/EmailService.java` | 依赖改为 MailSender 接口 |
| `command/executable/register/RegisterCommand.java` | 新增两阶段处理 |
| `process/register/RegistrationType.java` | 新增枚举值 |
| `process/register/executors/RegistrationMethod.java` | 新增常量 |
| `settings/properties/EmailSettings.java` | 新增配置项 |
| `settings/properties/RegistrationSettings.java` | 注释补充 |
| `AuthMe.java` | 注册 MailSenderProvider |
| `message/MessageKey.java` | 新增消息键 |
| `messages_*.yml` (en/zhcn/zhtw/zhhk/zhmc) | 新增翻译 |
| `command/executable/authme/debug/TestEmailSender.java` | 改用 MailSender |

---

## 八、心得总结

1. **抽象优于硬编码**：通过 `MailSender` 接口抽象，新旧实现共存，配置切换，零侵入。
2. **理解外部工具的行为模式**：agently-cli 的两阶段确认和 Windows .cmd 包装器的参数解析问题是纯静态分析无法发现的，需要运行时日志排查。
3. **复用现有架构**：两阶段注册没有强行改造 `AsyncRegister`，而是在命令层分流，阶段二才进入原有流程，最大化复用现有代码。
4. **DI 框架的深入理解**：Jalu Injector 的 `Provider` 和 `SingletonStore` 机制让我们能动态选择实现而无需修改工厂类。
5. **细节决定成败**：Javadoc 中的 `<code>` 转义、messages 文件不会被自动覆盖、Windows PATH 解析差异等小问题都可能导致编译失败或功能异常。
