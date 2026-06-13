# SyncmaticaPaper Folia 兼容性计划

## 概述

[Folia](https://github.com/PaperMC/Folia) 是 Paper 的一个分支，采用**区域多线程**架构：每个世界区域（region）拥有独立的线程，玩家实体也在各自线程上处理。这打破了传统 Bukkit/Paper 的"单主线程"模型，因此需要对插件进行适配。

## 现有代码中的不兼容 API 调用分析

### 1. 调度器 API（严重阻塞）

| 文件 | 行号 | 当前代码 | Folia 问题 |
|------|------|----------|------------|
| [`SyncmaticaPlugin.java`](../src/main/java/ch/uemasa/syncmatica/SyncmaticaPlugin.java) | 76 | `getServer().getScheduler().runTaskTimer(...)` | Folia 移除了全局 `BukkitScheduler`，必须使用 `AsyncScheduler`、`RegionScheduler` 或 `EntityScheduler` |
| [`SyncmaticaPlugin.java`](../src/main/java/ch/uemasa/syncmatica/SyncmaticaPlugin.java) | 76 | 返回 `BukkitTask` | Folia 中 `BukkitTask` 不可用，需使用 `ScheduledTask` |

**影响**: 过期交换清理任务（`sweepStaleExchanges`）无法注册，导致内存泄漏风险。

### 2. 线程安全（严重）

| 文件 | 数据结构 | 类型 | 问题 |
|------|----------|------|------|
| [`ServerCommunicationManager.java`](../src/main/java/ch/uemasa/syncmatica/comm/ServerCommunicationManager.java) | `targets` | `HashMap<UUID, ExchangeTarget>` | Folia 中不同玩家的事件可能在各自线程上触发，`HashMap` 非线程安全 |
| [`ServerCommunicationManager.java`](../src/main/java/ch/uemasa/syncmatica/comm/ServerCommunicationManager.java) | `broadcastTargets` | `HashSet<ExchangeTarget>` | 同上，广播操作遍历时可能被并发修改 |
| [`ServerCommunicationManager.java`](../src/main/java/ch/uemasa/syncmatica/comm/ServerCommunicationManager.java) | `modifiers` | `HashMap<UUID, ModifyExchangeServer>` | 同上 |
| [`ServerCommunicationManager.java`](../src/main/java/ch/uemasa/syncmatica/comm/ServerCommunicationManager.java) | `downloading` | `HashMap<UUID, List<PendingShare>>` | 同上 |
| [`PlayerIdentifierProvider.java`](../src/main/java/ch/uemasa/syncmatica/data/PlayerIdentifierProvider.java) | `identifiers` | `HashMap<UUID, PlayerIdentifier>` | 可能被多个区域线程并发访问 |
| [`QuotaService.java`](../src/main/java/ch/uemasa/syncmatica/config/QuotaService.java) | `progress` | `HashMap<String, Long>` | 上传配额统计可能被并发更新 |

### 3. NMS 反射（中等风险）

| 文件 | 问题 |
|------|------|
| [`RawChannel.java`](../src/main/java/ch/uemasa/syncmatica/net/RawChannel.java) | 使用反射访问 `CraftPlayer.getHandle()` 和 `ServerPlayer.connection`。Folia 可能修改了 `CraftPlayer` 的内部包名或字段结构。 |

### 4. Bukkit API 调用（低风险）

| 文件 | 行号 | 调用 | 说明 |
|------|------|------|------|
| [`SyncmaticaPlugin.java`](../src/main/java/ch/uemasa/syncmatica/SyncmaticaPlugin.java) | 82 | `Bukkit.getOnlinePlayers()` | 在 `onEnable()` 中调用，此时运行在"启动线程"，Folia 中仍然可用 |
| [`ConnectionListener.java`](../src/main/java/ch/uemasa/syncmatica/ConnectionListener.java) | 25 | `PlayerJoinEvent` | Folia 中在玩家实体线程上触发，处理器必须线程安全 |
| [`ConnectionListener.java`](../src/main/java/ch/uemasa/syncmatica/ConnectionListener.java) | 37 | `PlayerQuitEvent` | 同上 |
| [`ChannelMessageListener.java`](../src/main/java/ch/uemasa/syncmatica/ChannelMessageListener.java) | 20 | `onPluginMessageReceived` | Folia 中可能在区域线程上触发 |

### 5. 文件 I/O（中等风险）

| 文件 | 操作 | 问题 |
|------|------|------|
| [`SyncmaticManager.java`](../src/main/java/ch/uemasa/syncmatica/data/SyncmaticManager.java) | `save()` / `load()` | JSON 文件读写阻塞线程，在 Folia 区域线程上禁止阻塞操作 |
| [`FileStorage.java`](../src/main/java/ch/uemasa/syncmatica/data/FileStorage.java) | 所有方法 | 文件系统操作阻塞线程 |
| [`DownloadExchange.java`](../src/main/java/ch/uemasa/syncmatica/comm/exchange/DownloadExchange.java) | 文件写入 | 在握手线程上直接写文件 |
| [`UploadExchange.java`](../src/main/java/ch/uemasa/syncmatica/comm/exchange/UploadExchange.java) | 文件读取 | 在握手线程上直接读文件 |
| [`SyncmaticaCommand.java`](../src/main/java/ch/uemasa/syncmatica/command/SyncmaticaCommand.java) | `loadAll()` | 文件枚举和读取在命令线程上执行 |

## 修改方案

### 方案 A：最小化修改（推荐）

采用**运行时检测 + 兼容层**策略，在不破坏现有 Paper 兼容性的前提下支持 Folia。

---

### 步骤 1：添加 Folia API 依赖

**文件**: [`build.gradle.kts`](../build.gradle.kts)

在 `repositories` 中添加 Folia 仓库，在 `dependencies` 中可选添加 Folia API（注：Folia API 实际是 Paper API 的超集，但需要确认坐标）：

实际上，Folia 使用与 Paper 相同的 API 坐标 `io.papermc.paper:paper-api`。Folia 的更改主要体现在实现层面，API 层面通过统一的 Paper API 暴露。但 Folia 新增的调度器 API 在 `io.papermc.paper:paper-api` 中可能已经包含。

> **注意**: MC 26.1 (1.21) 版本的 Paper API 已经包含了 Folia 的调度器接口。因此仅需在运行时检测 Folia 即可。

---

### 步骤 2：创建 Folia 检测与兼容工具类

**新增文件**: [`src/main/java/ch/uemasa/syncmatica/util/FoliaCompat.java`](../src/main/java/ch/uemasa/syncmatica/util/FoliaCompat.java)

功能：
1. **运行时检测 Folia**：通过反射检查 Folia 特有类（如 `io.papermc.paper.threadedregions.RegionizedServer`）
2. **调度器抽象**：提供统一接口，在 Folia 上使用 `AsyncScheduler`/`RegionScheduler`，在 Paper 上使用 `BukkitScheduler`
3. **线程安全判断**：提供辅助方法检查当前是否在区域线程上

```java
public final class FoliaCompat {
    private static final boolean FOLIA;
    
    static {
        boolean folia = false;
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            folia = true;
        } catch (ClassNotFoundException e) {
            // Not Folia
        }
        FOLIA = folia;
    }
    
    public static boolean isFolia() {
        return FOLIA;
    }
}
```

---

### 步骤 3：替换调度器 API

**修改文件**: [`SyncmaticaPlugin.java`](../src/main/java/ch/uemasa/syncmatica/SyncmaticaPlugin.java)

**当前代码** (第 76-78 行):
```java
staleSweepTask = getServer().getScheduler().runTaskTimer(
        this, () -> comms.sweepStaleExchanges(EXCHANGE_TIMEOUT_MILLIS),
        SWEEP_PERIOD_TICKS, SWEEP_PERIOD_TICKS);
```

**修改后**:
```java
if (FoliaCompat.isFolia()) {
    staleSweepTask = getServer().getAsyncScheduler().runAtFixedRate(
            this, scheduledTask -> comms.sweepStaleExchanges(EXCHANGE_TIMEOUT_MILLIS),
            SWEEP_PERIOD_TICKS * 50L, SWEEP_PERIOD_TICKS * 50L, TimeUnit.MILLISECONDS);
} else {
    staleSweepTask = getServer().getScheduler().runTaskTimer(
            this, () -> comms.sweepStaleExchanges(EXCHANGE_TIMEOUT_MILLIS),
            SWEEP_PERIOD_TICKS, SWEEP_PERIOD_TICKS);
}
```

注意：`BukkitTask` 在 Folia 上不可用，需要将 `staleSweepTask` 的类型改为 `Object` 或使用公共接口。

更好的做法是创建一个包装类来统一两种调度器：

```java
// 新增文件：SweepTaskHandle.java
public interface SweepTaskHandle {
    void cancel();
}

// Folia 实现包装 ScheduledTask
// Paper 实现包装 BukkitTask
```

---

### 步骤 4：线程安全改造

**修改文件**: [`ServerCommunicationManager.java`](../src/main/java/ch/uemasa/syncmatica/comm/ServerCommunicationManager.java)

将所有 `HashMap` 替换为 `ConcurrentHashMap`：

| 原来 | 改为 |
|------|------|
| `Map<UUID, ExchangeTarget> targets = new HashMap<>()` | `new ConcurrentHashMap<>()` |
| `Set<ExchangeTarget> broadcastTargets = new HashSet<>()` | `ConcurrentHashMap.newKeySet<>()` |
| `Map<UUID, ModifyExchangeServer> modifiers = new HashMap<>()` | `new ConcurrentHashMap<>()` |
| `Map<UUID, List<PendingShare>> downloading = new HashMap<>()` | `new ConcurrentHashMap<>()` |

此外，`ArrayList` 的迭代操作（如 `new ArrayList<>(target.getExchanges())`）在 Folia 中如果被并发修改会抛出 `ConcurrentModificationException`。需要确保 `ExchangeTarget.getExchanges()` 返回的 `List<Exchange>` 是线程安全的。`ExchangeTarget` 中的 `exchanges` 列表也需要改为 `CopyOnWriteArrayList` 或使用同步机制。

**修改文件**: [`ExchangeTarget.java`](../src/main/java/ch/uemasa/syncmatica/comm/ExchangeTarget.java)

```java
private final List<Exchange> exchanges = new CopyOnWriteArrayList<>();
```

**修改文件**: [`PlayerIdentifierProvider.java`](../src/main/java/ch/uemasa/syncmatica/data/PlayerIdentifierProvider.java)

```java
private final Map<UUID, PlayerIdentifier> identifiers = new ConcurrentHashMap<>();
```

**修改文件**: [`QuotaService.java`](../src/main/java/ch/uemasa/syncmatica/config/QuotaService.java)

```java
private final Map<String, Long> progress = new ConcurrentHashMap<>();
```

---

### 步骤 5：调整 NMS 反射层

**修改文件**: [`RawChannel.java`](../src/main/java/ch/uemasa/syncmatica/net/RawChannel.java)

Folia 可能使用不同的 `CraftPlayer` 包名。需要添加 Folia 的 fallback 路径：

```java
// 在 static 块中，尝试多种可能的 CraftPlayer 路径
// Paper 标准路径: org.bukkit.craftbukkit.entity.CraftPlayer
// Folia 可能路径: 实际测试确认

// 同时缓存 Folia 特有的 Connection 字段名:
// Paper: "connection"
// Folia: 可能需要检查
```

实际上，Folia 1.21 仍然使用 `org.bukkit.craftbukkit` 包名，与 Paper 保持一致。所以 `RawChannel` 可能无需修改。但需要验证 Folia 上 `ServerPlayer.connection` 字段名是否一致。

**安全做法**: 添加 Folia 检测 + fallback 反射路径。如果在标准路径失败，尝试 Folia 路径。

---

### 步骤 6：文件 I/O 异步化

**关键文件**: [`SyncmaticManager.java`](../src/main/java/ch/uemasa/syncmatica/data/SyncmaticManager.java)

`save()` 方法在每次 add/remove 时同步写文件。在 Folia 中，这可能在区域线程上执行，阻塞文件 I/O。

**修改方案 1**（推荐）：在 Folia 上使用 `AsyncScheduler` 异步执行 `save()`

```java
public void save() {
    if (FoliaCompat.isFolia()) {
        saveAsync();
    } else {
        saveSync();
    }
}

private void saveAsync() {
    byte[] jsonBytes = serializeToBytes();
    // 使用 AsyncScheduler 在异步线程写入
    Bukkit.getAsyncScheduler().runNow(plugin, task -> {
        writeToFile(jsonBytes);
    });
}
```

**修改方案 2**：延迟批量保存，将多次写入合并为一次。

对于 `DownloadExchange` 和 `UploadExchange` 中的文件 I/O，由于它们已经是异步协议处理的一部分，在 Folia 上它们运行在区域线程，文件 I/O 会阻塞该区域。建议使用 `AsyncScheduler` 包装文件操作。

---

### 步骤 7：更新插件元数据

**修改文件**: [`paper-plugin.yml`](../src/main/resources/paper-plugin.yml)

添加 Folia 支持声明（Folia 2.0+ 支持通过 `folia-supported: true` 声明）：

```yaml
name: SyncmaticaPaper
version: '${version}'
main: ch.uemasa.syncmatica.SyncmaticaPlugin
api-version: '26.1'
folia-supported: true  # 新增
```

---

### 步骤 8：更新构建配置

**修改文件**: [`build.gradle.kts`](../build.gradle.kts)

添加 Folia API 依赖（如果需要编译 Folia 特有的 API 调用）：

```kotlin
// 可选：如果需要编译 Folia 特有 API
// 注：Paper API 1.21 已包含 Folia 调度器接口，因此无需额外依赖
```

如果需要运行时区分，目前 Paper API (`io.papermc.paper:paper-api`) 已经包含了 `AsyncScheduler`、`RegionScheduler`、`EntityScheduler` 等 Folia 引入的 API。所以编译期不需要额外依赖。

---

## 完整修改清单

| # | 文件 | 修改类型 | 描述 |
|---|------|----------|------|
| 1 | `src/main/java/ch/uemasa/syncmatica/util/FoliaCompat.java` | **新增** | Folia 检测与兼容工具类 |
| 2 | `src/main/java/ch/uemasa/syncmatica/SyncmaticaPlugin.java` | 修改 | 替换调度器 API；支持 Folia 的 AsyncScheduler |
| 3 | `src/main/java/ch/uemasa/syncmatica/comm/ServerCommunicationManager.java` | 修改 | HashMap → ConcurrentHashMap；线程安全迭代 |
| 4 | `src/main/java/ch/uemasa/syncmatica/comm/ExchangeTarget.java` | 修改 | ArrayList → CopyOnWriteArrayList |
| 5 | `src/main/java/ch/uemasa/syncmatica/data/PlayerIdentifierProvider.java` | 修改 | HashMap → ConcurrentHashMap |
| 6 | `src/main/java/ch/uemasa/syncmatica/config/QuotaService.java` | 修改 | HashMap → ConcurrentHashMap |
| 7 | `src/main/java/ch/uemasa/syncmatica/net/RawChannel.java` | 修改 | 添加 Folia NMS fallback |
| 8 | `src/main/resources/paper-plugin.yml` | 修改 | 添加 `folia-supported: true` |
| 9 | `src/main/java/ch/uemasa/syncmatica/data/SyncmaticManager.java` | 修改 | save() 异步化 |

## 影响范围分析

### 不影响的功能
- ✅ 协议层（`SyncByteBuf`、`PacketType`、`SyncmaticaPacket`）- 纯数据处理，无 Bukkit API 依赖
- ✅ 数据模型（`ServerPlacement`、`ServerPosition`、`SubRegionData` 等）- POJO，无 API 依赖
- ✅ 功能协商（`FeatureSet`、`Feature`）- 纯枚举/集合操作
- ✅ 命令注册（`SyncmaticaCommand`）- 使用 `LifecycleEvents.COMMANDS`，Paper API，Folia 兼容
- ✅ 插件消息通道注册 - `Messenger` API，Folia 兼容
- ✅ 配置文件加载 - `FileConfiguration` API，Folia 兼容

### 受影响的功能
- ⚠️ 定时清理任务（`sweepStaleExchanges`）- 调度器替换
- ⚠️ 玩家连接管理 - 线程安全改造
- ⚠️ 文件持久化（`placements.json`、`.litematic` 文件）- 异步化
- ⚠️ NMS 数据包发送 - 反射路径验证

## Folia 工作原理简要

```
┌─────────────────────────────────────────────────────────────┐
│                     Folia Server                             │
│                                                              │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐                   │
│  │ Region 1 │  │ Region 2 │  │ Region 3 │  ...               │
│  │ Thread   │  │ Thread   │  │ Thread   │                    │
│  │          │  │          │  │          │                    │
│  │ Entities │  │ Entities │  │ Entities │                   │
│  │ Chunks   │  │ Chunks   │  │ Chunks   │                   │
│  └──────────┘  └──────────┘  └──────────┘                    │
│                                                              │
│  ┌─────────────────────────────────────────┐                 │
│  │         Global (Async) Threads          │                 │
│  │  - AsyncScheduler tasks                 │                 │
│  │  - Netty I/O                            │                 │
│  │  - File I/O                             │                 │
│  └─────────────────────────────────────────┘                 │
└─────────────────────────────────────────────────────────────┘
```

- 每个世界区域拥有独立线程
- 玩家实体在所属区域线程上处理
- 事件在相应区域/实体线程上触发
- **不存在全局主线程**
- 全局调度器（`BukkitScheduler`）被移除
- 新增 `AsyncScheduler`、`RegionScheduler`、`EntityScheduler`

## 测试策略

1. **在 Paper 服务器上测试**：确保所有修改不破坏现有 Paper 兼容性
2. **在 Folia 服务器上测试**：验证调度器、线程安全、文件 I/O 在 Folia 上正常工作
3. **回归测试**：运行现有单元测试（`SyncByteBufTest`）
4. **负载测试**：模拟多个玩家同时上传/下载，验证线程安全性

## 风险评估

| 风险 | 概率 | 影响 | 缓解措施 |
|------|------|------|----------|
| Folia NMS 内部结构差异 | 中 | 高 | 反射失败时优雅降级（回退到 sendPluginMessage） |
| 并发 bug（数据竞争） | 中 | 高 | 全面的 ConcurrentHashMap 替换 + Code Review |
| 文件 I/O 阻塞区域线程 | 高 | 中 | 异步化文件写入 |
| Paper 侧性能退化 | 低 | 低 | ConcurrentHashMap 在单线程下额外开销很小 |
