# 推送到 GitHub

仓库地址：https://github.com/ForEternal123/RSupgrade

本目录已经是**可直接推送的状态**（`.gitignore`、`LICENSE`、`README.md`、`libs/README.md` 都写好了）。

---

## 一、先解压到本地

把 `RSupgrade-repo.zip` 解压到你习惯放代码的地方，例如：

```
D:\code\RSupgrade
```

> ⚠️ **不要**在整合包目录里直接 `git init`，那会把整合包的文件也加进去。

---

## 二、初始化并推送

```bash
cd D:\code\RSupgrade

git init
git branch -M main
git add .
git commit -m "init: RS Upgrade Extractor 1.0.0"

git remote add origin https://github.com/ForEternal123/RSupgrade.git
git push -u origin main --force
```

> 仓库如果是刚建的、里面只有 README/LICENSE（GitHub 自动生成的），
> `--force` 会覆盖掉它。如果你已经在网页上手动写了内容，去掉 `--force`，
> 先 `git pull --rebase origin main` 再 push。

推送时会让你登录：

- **HTTPS**：用户名填 `ForEternal123`，密码填 **Personal Access Token**（不是账号密码）
- GitHub 2021 年后不再接受密码，必须用 PAT

### 生成 PAT

1. https://github.com/settings/tokens → **Generate new token (classic)**
2. 勾选 `repo` 权限
3. 有效期按需，生成后**立刻复制**（只显示一次）
4. 推送时当密码用

---

## 三、推送前自查

```bash
# 1. 不该出现在列表里的东西
git status --short

# 应该看不到：
#   libs/*.jar
#   build/
#   .gradle/
#   *.py 临时脚本
#   run/

# 2. 确认 jar 没被加进来
git ls-files | findstr /i ".jar"     # 只应看到 gradle-wrapper.jar

# 3. 确认没有密钥
git ls-files | findstr /i "key token secret"
```

---

## 四、打 Release

推送完成后：

```bash
git tag -a v1.0.0 -m "RS Upgrade Extractor 1.0.0"
git push origin v1.0.0
```

然后到 https://github.com/ForEternal123/RSupgrade/releases/new

- Tag 选 `v1.0.0`
- 标题：`v1.0.0`
- 上传 **`rsupgrade-1.0.0.jar`**（从 `build/libs/` 拿，**不要**提交到仓库）
- 说明里写清：支持 MC 1.20.1 / Forge 47.x / 需要 Refined Storage

---

## 五、以后更新

```bash
git add .
git commit -m "fix: 描述改了什么"
git push
```

发版时改 `gradle.properties` 里的 `mod_version`，重新构建、打新 tag。

---

## 六、还需要你自己做的事

| 事项 | 说明 |
| --- | --- |
| **确认 License** | 现在用的是 MIT。想换 LGPL-3.0 就替换 `LICENSE` 文件 + 改 `gradle.properties` 的 `mod_license` |
| **仓库描述** | GitHub 右上角 About，填一句话简介 + 加 topics：`minecraft` `minecraft-mod` `forge` `refined-storage` `sophisticated-backpacks` |
| **依赖 jar** | 仓库不含 `libs/*.jar`，别人 clone 后需要按 `libs/README.md` 自备 |
| **构建验证** | 我在本地用 4 个依赖 jar 验证过 `./gradlew build` 成功（产物 44.6 KB）|
| **CI（可选）** | 想加 GitHub Actions 自动构建的话，需要先把依赖改成 CurseForge Maven，或者把 jar 上传到 Release 再在 CI 里下载 |
