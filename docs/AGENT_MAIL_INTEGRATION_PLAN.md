# AuthMe × Agent Mail 集成改造方案

> 目标：用腾讯 Agent Mail（`@agent.qq.com` 专属邮箱，通过 `agently-cli` 发信）替代传统 SMTP，作为 AuthMe 的邮件发送通道；并新增"注册时先验证邮箱地址，验证通过后才允许设置密码"的流程。

---

## 一、现状分析

### 1.1 AuthMe 现有邮件架构

- 发信底层：`SendMailSsl` 基于 `org.apache.commons.mail.HtmlEmail` 直连 SMTP 服务器（host + port + 账号密码 / XOAUTH2 token）。
- 邮件服务门面：`EmailService` 提供 4 类发信方法：
  - `sendPasswordMail` — 注册/找回时发送生成的密码
  - `sendVerificationMail` — 邮箱变更前的旧邮箱身份验证码
  - `sendEmailConfirmationMail` — 已登录玩家添加/更换新邮箱时的归属确认码
  - `sendRecoveryCode` — 找回密码的恢复码
- 配置项：`EmailSettings` 中 `SMTP_HOST` / `SMTP_PORT` / `MAIL_ACCOUNT` / `MAIL_PASSWORD` / `OAUTH2_TOKEN` 等。
- 前置校验：`SendMailSsl.hasAllInformation()` 要求 `MAIL_ACCOUNT` 与 `MAIL_PASSWORD` 非空，是所有邮件功能的总开关。

### 1.2 AuthMe 现有注册流程

`RegistrationType` 枚举仅两个值：

| 类型 | 行为 | 邮箱是否验证 |
|---|---|---|
| `PASSWORD`（默认） | 玩家自设密码；若 `REGISTER_SECOND_ARGUMENT = EMAIL_MANDATORY` 则要求 `/register <密码> <邮箱>`，邮箱**直接入库不验证** | 否 |
| `EMAIL` | 玩家只填邮箱，系统生成密码发到邮箱；账号立即创建，**邮箱未做归属验证**（填错邮箱也会建账号） | 否 |

**关键结论**：当前代码**不存在**"先发验证码到邮箱 → 玩家验证 → 再设置密码"的注册模式。注册路径完全不调用 `VerificationCodeManager` 或 `PendingEmailVerificationCache`，邮箱在 `PlayerAuthBuilderHelper.createPlayerAuth` 中直接写入 `PlayerAuth.email` 落库。

### 1.3 AuthMe 现有密码重置流程（已完备，无需重写）

AuthMe 内置完整的"邮箱恢复码 + 自设新密码"机制，且为默认行为：

1. `/email recover <邮箱>` → `RecoverEmailCommand` → `PasswordRecoveryService.createAndSendRecoveryCode`
2. `EmailService.sendRecoveryCode` 发恢复码邮件（`RecoveryCodeService` 生成 8 位十六进制码，4 小时有效，3 次尝试）
3. `/email code <恢复码>` → `ProcessCodeCommand` 校验 → `addSuccessfulRecovery` 标记可改密码（2 分钟窗口 + IP 校验）
4. `/email setpassword <新密码>` → `EmailSetPasswordCommand` → `dataSource.updatePassword`

防滥用齐全：邮件冷却 60 秒、恢复码尝试上限 3 次、改密时间窗 2 分钟、IP 一致性校验。

> **此流程只需替换底层邮件发送通道即可复用，无需改业务逻辑。**

### 1.4 Agent Mail 能力与约束

- 发信地址：`xxx @agent.qq.com`（与个人邮箱隔离，前缀可自定义）
- 发信方式：**仅通过 `agently-cli` 命令行**（npm 包 `@tencent-qqmail/agently-cli`），无公开 SMTP 端点
- 授权方式：`agently-cli auth login` → 微信扫码 OAuth，token 持久化
- 发信命令：`agently-cli message +send --to <收件人> --subject <主题> --body <HTML内容> [--body-format html]`，输出 JSON
- 验证命令：`agently-cli +me` 返回已授权邮箱地址
- 限制：
  - 每个邮箱**每天 50 封**发信上限，收信不限
  - 容量 1GB
  - 必须安装 Node.js + npm
  - 发件人固定为 `@agent.qq.com` 后缀，不能用自有域名

---

## 二、总体架构

### 2.1 设计原则

1. **不破坏现有 SMTP 通道** — 通过配置开关在 SMTP 与 Agent Mail 之间切换，二者并存。
2. **最小侵入** — 抽象出 `MailSender` 接口，`SendMailSsl` 与新增的 `AgentMailSender` 各自实现；`EmailService` 仅依赖接口。
3. **复用现有业务流程** — 密码重置流程完全复用，仅替换发信底层；注册流程新增一种类型。

### 2.2 模块分层

```
EmailService (门面，不变)
    │
    ├── MailSender (新增接口)
    │      ├── SendMailSsl        (现有，SMTP 通道)
    │      └── AgentMailSender    (新增，agently-cli 通道)
    │
    └── 邮件内容模板渲染 (现有，不变)
```

### 2.3 AgentMailSender 工作机制

`AgentMailSender` 在 Java 中通过 `ProcessBuilder` 调用宿主机上的 `agently-cli`：

```
agently-cli message +send \
  --to <收件人> \
  --subject <主题> \
  --body <HTML内容> \
  --body-format html
```

- 读取 stdout 的 JSON 响应判断成功/失败
- 首次调用若检测到未授权（JSON 含认证错误），记录日志并提示管理员执行 `agently-cli auth login`
- 发信调用在**异步线程**执行（AuthMe 的 `BukkitService.runTaskAsynchronously`），避免阻塞主线程
- 加进程级超时（默认 30 秒）

---

## 三、注册流程改造（核心新增）

### 3.1 新增注册类型

在 `RegistrationType` 枚举新增第三种值：

```java
public enum RegistrationType {
    PASSWORD,
    EMAIL,
    EMAIL_VERIFIED_PASSWORD  // 新增：先验证邮箱，验证通过后再设置密码
}
```

配置项：`RegistrationSettings.REGISTRATION_TYPE` 增加可选值 `EMAIL_VERIFIED_PASSWORD`。

### 3.2 新增"待完成注册"缓存

新增 `PendingRegistrationCache`（仿照 `PendingEmailVerificationCache`）：

- 数据结构：玩家名 → `{email, verificationCode, password(待设置), expiry}`
- TTL：10 分钟（与 `VERIFICATION_CODE_EXPIRATION_MINUTES` 对齐）
- 存储在内存（`ExpiringMap`），重启丢失（注册中途状态，可接受）

### 3.3 新增注册执行器 `EmailVerifiedRegisterExecutor`

继承 `AbstractPasswordRegisterExecutor`，分两阶段完成注册：

#### 阶段一：提交邮箱（`/register <邮箱>`）

1. 校验邮箱格式（`ValidationService.validateEmail`）
2. 校验邮箱未被占用（`dataSource.countRowsByEmail`）
3. 校验 `MAX_REG_PER_EMAIL` 未超限
4. 生成 6 位数字验证码（`RandomStringUtils.generateNum(6)`）
5. 调用 `emailService.sendEmailConfirmationMail(name, email, code)` 发送到该邮箱
6. `pendingRegistrationCache.addPending(name, email, code)` 暂存
7. 提示玩家：`REGISTRATION_VERIFY_EMAIL_SENT`（"验证码已发送到 <邮箱>，请用 /register verify <验证码> <密码> 完成注册"）
8. **此时不写数据库**，账号尚未创建

#### 阶段二：验证并设置密码（`/register verify <验证码> <新密码>`）

1. 从 `pendingRegistrationCache` 取条目，过期则 `REGISTRATION_VERIFY_EXPIRED`
2. 比对验证码，错误则 `REGISTRATION_VERIFY_WRONG_CODE`（可加尝试次数限制）
3. 验证通过：
   - `ValidationService.validatePassword` 校验密码强度
   - `passwordSecurity.computeHash(password, name)` 哈希
   - 构造 `PlayerAuth`（含 name、realName、hashedPassword、email、registrationIp、registrationDate、uuid）
   - `database.saveAuth(auth)` 落库
   - `pendingRegistrationCache.removePending(name)`
   - 触发 `RegisterEvent`
   - 发送 `REGISTER_SUCCESS`，可选自动登录（`FORCE_LOGIN_AFTER_REGISTER`）

### 3.4 命令扩展

`RegisterCommand` 需识别 `EMAIL_VERIFIED_PASSWORD` 模式的两种子命令形态：

| 输入 | 阶段 |
|---|---|
| `/register <邮箱>` | 阶段一：发验证码 |
| `/register verify <验证码> <密码>` | 阶段二：验证并落库 |

需在 `RegisterCommand.runCommand` 中根据 `REGISTRATION_TYPE` 分支处理，新增 `handleEmailVerifiedRegistration` 方法。

### 3.5 流程时序图

```
玩家                AuthMe                    Agent Mail
 │                   │                           │
 │ /register a@b.com │                           │
 │──────────────────>│                           │
 │                   │ 生成6位码 → sendEmailConfirmationMail
 │                   │──────────────────────────>│
 │                   │                           │ 发邮件到 a@b.com
 │  "验证码已发送"    │                           │
 │<──────────────────│                           │
 │                   │                           │
 │  (查收邮件得到 123456)                        │
 │                   │                           │
 │ /register verify 123456 myPass               │
 │──────────────────>│                           │
 │                   │ 校验码 → 哈希密码 → saveAuth
 │  "注册成功"        │                           │
 │<──────────────────│                           │
```

---

## 四、密码重置流程（复用现有，仅换发信通道）

无需改业务逻辑，`EmailService.sendRecoveryCode` 底层切换到 `AgentMailSender` 后自动生效：

1. `/email recover <邮箱>` → 发恢复码（`AgentMailSender` 通过 agently-cli 发出）
2. `/email code <恢复码>` → 校验
3. `/email setpassword <新密码>` → 重置

> 登录失败时若邮箱可用且玩家有邮箱，`AsynchronousLogin.handleWrongPassword` 已会提示 `FORGOT_PASSWORD_MESSAGE` 引导玩家使用 `/email recover`。

---

## 五、配置项设计

### 5.1 新增 `EmailSettings` 配置

```yaml
Email:
  # 邮件发送通道：smtp 或 agent_mail
  mailSender: smtp
  # === Agent Mail 配置（mailSender=agent_mail 时生效）===
  # agently-cli 可执行文件路径（默认从 PATH 查找）
  agentMailCliPath: agently-cli
  # 调用超时秒数
  agentMailTimeoutSeconds: 30
  # 发件人显示名（实际地址由 agently-cli 授权账号决定）
  agentMailSenderName: "AuthMe Server"
```

### 5.2 `RegistrationSettings` 扩展

```yaml
Registration:
  # 注册类型：password / email / email_verified_password
  type: email_verified_password
```

### 5.3 邮件模板

复用现有 `email_confirmation_email.html` 模板（`sendEmailConfirmationMail` 已用），包含 `<generatedcode />` 与 `<minutesvalid />` 占位符，无需新增模板文件。

---

## 六、代码改动清单

### 6.1 新增文件

| 文件 | 作用 |
|---|---|
| `mail/MailSender.java` | 邮件发送接口，定义 `send(to, subject, htmlContent) -> boolean` |
| `mail/AgentMailSender.java` | Agent Mail 实现，通过 ProcessBuilder 调 agently-cli |
| `process/register/EmailVerifiedRegisterExecutor.java` | 两阶段注册执行器 |
| `data/PendingRegistrationCache.java` | 待完成注册的内存缓存 |

### 6.2 修改文件

| 文件 | 改动 |
|---|---|
| `mail/SendMailSsl.java` | 实现 `MailSender` 接口（保持现有 SMTP 逻辑） |
| `mail/EmailService.java` | 依赖从 `SendMailSsl` 改为 `MailSender`；按配置注入实现 |
| `settings/properties/EmailSettings.java` | 新增 `MAIL_SENDER` / `AGENT_MAIL_CLI_PATH` / `AGENT_MAIL_TIMEOUT` 配置 |
| `settings/properties/RegistrationSettings.java` | `REGISTRATION_TYPE` 注释补充新值 |
| `process/register/RegistrationType.java` | 新增 `EMAIL_VERIFIED_PASSWORD` 枚举值 |
| `command/executable/register/RegisterCommand.java` | 新增 `handleEmailVerifiedRegistration` 分支处理两阶段命令 |
| `process/register/executors/RegistrationExecutorFactory.java` 或对应工厂 | 注册 `EmailVerifiedRegisterExecutor` |
| `messages/messages_en.yml` 等多语言文件 | 新增消息键：`REGISTRATION_VERIFY_EMAIL_SENT` / `REGISTRATION_VERIFY_EXPIRED` / `REGISTRATION_VERIFY_WRONG_CODE` / `REGISTRATION_VERIFY_USAGE` |
| `dependency injection 配置`（`AuthMe.java` 或 `BukkitServiceInitializer`） | 按 `MAIL_SENDER` 配置选择注入 `SendMailSsl` 或 `AgentMailSender` |

### 6.3 不改动的文件（复用）

- `service/PasswordRecoveryService.java` — 密码重置业务逻辑不变
- `service/RecoveryCodeService.java` — 恢复码逻辑不变
- `command/executable/email/RecoverEmailCommand.java` — 命令入口不变
- `command/executable/email/ProcessCodeCommand.java` — 不变
- `command/executable/email/EmailSetPasswordCommand.java` — 不变

---

## 七、运维前置条件（部署须知）

在 MC 服务器上必须完成：

1. **安装 Node.js**（LTS 版本，含 npm）
2. **安装 agently-cli**：`npm install -g @tencent-qqmail/agently-cli`
3. **OAuth 授权**：在服务器终端执行 `agently-cli auth login`，微信扫码完成授权（一次性）
4. **验证**：`agently-cli +me` 确认返回 `@agent.qq.com` 邮箱地址
5. **配置 AuthMe**：`Email.mailSender: agent_mail`，并确保 `agently-cli` 在 MC 服务器进程的 PATH 中（或配置 `agentMailCliPath` 为绝对路径）

> ⚠️ token 过期需重新扫码授权；服务器重启不影响已持久化的 token。

---

## 八、限制与风险

| 限制 | 影响 | 缓解措施 |
|---|---|---|
| **每天 50 封发信上限** | 中大型服玩家注册/找回密集时会触顶 | 仅适合小服/测试；生产环境建议用腾讯云 SES 或 SMTP |
| 必须装 Node.js + agently-cli | 运维负担 | 文档说明前置条件；提供 `agentMailCliPath` 配置绝对路径 |
| 微信扫码授权 | token 过期需人工介入 | 监控发信失败日志，及时提醒管理员重扫 |
| 发件人 `@agent.qq.com` | 玩家看到的不是自有域名 | 邮件模板中注明服务器名 |
| 子进程调用开销 | 每次发信 fork 进程，延迟较高 | 异步线程执行 + 30 秒超时；高并发场景不适用 |
| 待注册缓存内存态 | 服务器重启丢失中途注册 | 可接受（玩家重新发起 `/register <邮箱>` 即可） |

---

## 九、测试计划

### 9.1 单元测试

- `AgentMailSender`：mock `ProcessBuilder`，验证命令构造、JSON 解析、超时、错误处理
- `PendingRegistrationCache`：验证 TTL 过期、存取
- `EmailVerifiedRegisterExecutor`：mock 依赖，验证两阶段状态机

### 9.2 集成测试（需真实 agently-cli 授权环境）

- 阶段一：`/register test@example.com` → 收到验证码邮件
- 阶段二：`/register verify <码> <密码>` → 注册成功，可用 `/login` 登录
- 密码重置：`/email recover` → `/email code` → `/email setpassword` → `/login`
- 边界：验证码过期、错误码、邮箱已占用、密码强度不足、每日 50 封上限触发

### 9.3 回归测试

- `mailSender: smtp` 模式下，原有 SMTP 注册/找回/邮箱变更全流程不受影响
- `RegistrationType: PASSWORD` / `EMAIL` 模式行为不变

---

## 十、实施步骤建议

1. **第一步**：实现 `MailSender` 接口 + `AgentMailSender`，完成发信通道切换（最小可用，密码重置即可用）
2. **第二步**：实现 `PendingRegistrationCache` + `EmailVerifiedRegisterExecutor` + `RegisterCommand` 扩展
3. **第三步**：新增配置项与多语言消息
4. **第四步**：单元测试 + 集成测试
5. **第五步**：部署文档与运维说明

---

## 附：关键代码位置速查

| 关注点 | 文件路径 |
|---|---|
| 邮件门面 | `authme-core/src/main/java/fr/xephi/authme/mail/EmailService.java` |
| SMTP 发信 | `authme-core/src/main/java/fr/xephi/authme/mail/SendMailSsl.java` |
| 注册命令 | `authme-core/src/main/java/fr/xephi/authme/command/executable/register/RegisterCommand.java` |
| 注册执行器 | `authme-core/src/main/java/fr/xephi/authme/process/register/executors/` |
| 注册类型枚举 | `authme-core/src/main/java/fr/xephi/authme/process/register/RegistrationType.java` |
| 邮箱确认缓存（可仿照） | `authme-core/src/main/java/fr/xephi/authme/service/PendingEmailVerificationCache.java` |
| 密码重置服务 | `authme-core/src/main/java/fr/xephi/authme/service/PasswordRecoveryService.java` |
| 恢复码服务 | `authme-core/src/main/java/fr/xephi/authme/service/RecoveryCodeService.java` |
| 邮箱设置 | `authme-core/src/main/java/fr/xephi/authme/settings/properties/EmailSettings.java` |
| 注册设置 | `authme-core/src/main/java/fr/xephi/authme/settings/properties/RegistrationSettings.java` |
| 多语言消息 | `authme-core/src/main/resources/messages/messages_en.yml` |
