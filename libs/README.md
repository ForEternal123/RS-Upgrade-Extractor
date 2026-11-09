# 第三方依赖（不随仓库提供）

构建前请把下列 jar 放进本目录（4 个都放，否则编译不过）。

| jar | 编译期 | 运行期 | 说明 |
| --- | --- | --- | --- |
| `refinedstorage.jar` | ✅ 必需 | ✅ 必需 | Refined Storage |
| `curios.jar` | ✅ 必需 | ⬜ 可选 | Curios；没有则跳过背饰槽扫描 |
| `sophisticatedbackpacks.jar` | ✅ 必需 | ⬜ 可选 | 精妙背包；没有则跳过背包升级槽 |
| `sophisticatedcore.jar` | ✅ 必需 | ⬜ 可选 | 精妙背包前置 |

> 编译期需要 4 个 jar 都在，因为源码里直接引用了它们的类。
> 运行期是可选的：`CuriosCompat` / `SbCompat` 用 `ModList.get().isLoaded(...)`
> 判断，没装对应 mod 时那个类根本不会被加载，不会报 `NoClassDefFoundError`。

## 为什么不在仓库里

这几个 jar 是**其他作者的 mod**，许可证各不相同（RS 是 MIT、Curios 是 LGPL、
精妙背包是 LGPL-3.0），再分发需要遵守它们的条款，所以本仓库只保留
`compileOnly files(...)` 声明，jar 由使用者自备。

## 怎么拿到

1. 从你的整合包 `mods/` 目录直接复制（最快）
2. 或从 CurseForge / Modrinth 下载对应版本

## 用 CurseForge Maven 替代（可选）

不想手动放 jar 的话，可以改成 Maven 依赖（需要填对应的 file id）：

```groovy
repositories {
    maven { url "https://cursemaven.com" }
}

dependencies {
    compileOnly fg.deobf("curse.maven:refined-storage-243121:<fileId>")
    compileOnly fg.deobf("curse.maven:curios-309927:<fileId>")
    compileOnly fg.deobf("curse.maven:sophisticated-backpacks-422301:<fileId>")
    compileOnly fg.deobf("curse.maven:sophisticated-core-618298:<fileId>")
}
```
