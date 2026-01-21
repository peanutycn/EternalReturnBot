# EternalReturn Bot
From [onebot-lomu](https://github.com/LuoRenMu/onebot-lomu)
## Function

__Search__
![search2.png](docs/images/search2.png)

__Rank Search__
![search1.png](docs/images/search1.png)   

__Tier Distribution Or Eternal Score__
![tier.png](docs/images/tier.png)

## Commands

- 玩家战绩图片（模板渲染）：`查询玩家 <名称> [模式数字]`（也支持 `/search <名称> [模式数字]`）
- 玩家战绩网页截图：`网页查询玩家 <名称>`
- 实验体网页截图（支持中文/英文/拼音谐音；可选武器 0/1/2/3）：`查询角色 <名称> [0/1/2/3]`（也支持 `查询实验体 ...`）
- 路线网页截图：`查询路线 <路线ID>` 或 `routes <路线ID>`
- 实验体统计网页截图：`实验体统计` / `角色统计` / `英雄统计` / `statistics`
- 官网更新截图：直接发送 `https://playeternalreturn.com/posts/news/12345` 形式链接（或 `官网更新截图 <新闻ID>`）
- 帮助：`帮助`（或 `help`）
- 反馈（转发给 superAdmins）：`反馈 <内容>`
  - 例：`反馈 查询玩家截图异常`

## Configuration

This project loads runtime configuration from a HOCON file.

Search order:
1. System property: `-Dlomu.config.file=...`
2. Env var: `LOMU_CONFIG_FILE=...`
3. `<jarDir>/config/application.conf`
4. `<jarDir>/application.conf`
5. Classpath `application.conf` inside the jar

Required (for player/match queries):
- `lomu.bser.openApiKey` (or `lomu.bser.openApiKeyFile`, or env `BSER_OPEN_API_KEY`)

OneBot endpoints (defaults):
- `lomu.onebot.mode` = `auto` (`ws` | `simbot`)
- `lomu.onebot.eventServerHost` = `ws://127.0.0.1:3001`
- `lomu.onebot.apiServerHost` = `` (optional; required only when `mode=simbot`)
- Command ack emoji (best-effort):
  - `lomu.onebot.ackEmoji.enable` = `true`
  - `lomu.onebot.ackEmoji.emojiId` = `"124"`

HTTP proxy (optional):
- `lomu.http.proxy.url` = `http://127.0.0.1:7890`

Render:
- `lomu.render.maxMatches` = `20`

Resources:
- `lomu.resources.downloadConcurrency` = `16`

Alias (player/character, personal/group/global):
- Command: `/alias help`
- Persisted file: `<jarDir>/data/aliases.json`
- Personal scope: anyone can use `/alias player set ...` (effective for the sender only, in the current group)
- Group scope: `/alias player gset ...` requires group owner/admin (ONE_BOT), group managers, or `lomu.alias.superAdmins`
- Global scope: `/alias player aset ...` requires `lomu.alias.superAdmins` or group owner/admin (ONE_BOT)
- List behavior: `list/glist/alist` outputs “名称：别名1,别名2...”, and supports `list <名称>` to filter

Alerts & forwarding:
- Requires: `lomu.alias.superAdmins` configured (QQ user ids)
- Forward exceptions to superAdmins: `lomu.alert.enable=true`
- Forward private messages (WS-only): `lomu.alert.forwardPrivate.enable=false`
- Allow users to apply for GLOBAL alias changes (forward to superAdmins): `lomu.alert.aliasRequest.enable=true`
- In chat:
  - `玩家别名 申请全局设置 <别名> <玩家名>`
  - `玩家别名 申请全局删除 <别名>`
  - `角色别名 ...` 同理

## Build

Prerequisites:
- JDK 17 (JRE only is not enough)

Build OneBot fat-jar:
- `bash ./gradlew :onebot:jar`

Artifacts:
- `onebot/build/libs/ERBot.jar` (override with `-PerbotJarName=xxx.jar`)

## Troubleshooting

- Too many logs: default level is INFO. Override with `LOMU_LOG_LEVEL=DEBUG`.
- `426 Upgrade Required`: you are calling HTTP APIs with a `ws://...` URL. If `mode=simbot`, set `lomu.onebot.apiServerHost=http(s)://...` and `lomu.onebot.eventServerHost=ws(s)://...`, or switch to `mode=ws`.
- First run: if no config file is found, the bot will generate a template at `<jarDir>/config/application.conf`.
- `Cannot find a Java installation ... toolchains`: ensure `JAVA_HOME` points to a JDK 17 (e.g. `/usr/lib/jvm/java-17-openjdk-amd64`), then retry.
- `Could not find or load main class org.gradle.wrapper.GradleWrapperMain`: `gradle/wrapper/gradle-wrapper.jar` is missing/corrupted. Run `bash scripts/bootstrap-gradle-wrapper.sh` to regenerate it.
