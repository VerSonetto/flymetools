# FlymeTool 迁移到 libxposed API 101 文档

> 本文档基于以下权威来源整理，用于指导本模块从经典 Xposed API 82（`de.robv.android.xposed` + 本地 `api-82.jar`）全面迁移到 libxposed API 101（`io.github.libxposed:api`），**不使用任何兼容层**。
>
> 来源：
> - [libxposed/api 源码](https://github.com/libxposed/api)（`api/src/main/java/io/github/libxposed/api/*.java`，已本地浅克隆通读）
> - [LSPosed 官方 Wiki：Develop Xposed Modules Using Modern Xposed API](https://github.com/LSPosed/LSPosed/wiki/Develop-Xposed-Modules-Using-Modern-Xposed-API)
> - [libxposed/api Javadoc](https://libxposed.github.io/api/) 与 [libxposed/service 文档](https://github.com/libxposed/service)

---

## 1. 目标与判定标准

- **目标**：`minApiVersion=101`、`targetApiVersion=101` 的纯 Modern Xposed 模块。
- **禁止**：任何 `de.robv.android.xposed.*` 引用；LSPosed 对 API 101+ 目标模块**不再翻译**经典调用形态，`compat` 依赖直接不生效。
- **验收**：项目内 `Grep "de.robv"` 结果为空；`./gradlew installSignedRelease --no-daemon` 构建通过；真机（LSPosed ≥ 2.x）各作用域功能行为与迁移前一致。

### 版本注意
- LSPosed 对 Modern API 的支持：Remote Preferences 自 **v1.9.0** 起；模块声明改造（resources/META-INF）与完整生命周期自 **v2.x** 起。本项目真机环境为 LSPosed 2.1.1，满足要求。
- API 101 与 102 的差异：102 额外提供热重载（Hot Reload）、hook 原子替换（`setId`/`replaceHook`）、`detach()` 等。本迁移只针对 **101**，不涉及热重载；`module.prop` 里 `targetApiVersion=101` 即可明确不启用 102 行为（102 行为下还会被禁止调用 legacy API，101 目标虽无此限制，但本项目本就全部使用新 API）。

---

## 2. Modern API 核心理念（与经典 API 的对应）

### 2.1 模块声明：不再使用 metadata / assets

| 经典 API 82 | Modern API 101 |
|---|---|
| `assets/xposed_init` | `src/main/resources/META-INF/xposed/java_init.list` |
| Manifest `xposedmodule` / `xposeddescription` / `xposedminversion` / `xposedscope` metadata | 一律不用：名称=`android:label`，描述=`android:description`，作用域=`META-INF/xposed/scope.list`（每行一个包名），API 版本=`META-INF/xposed/module.prop` |
| `@array/xposed_scope` 资源 | 同上，`scope.list` 替代 |
| `assets/scope` | 同上 |

`module.prop` 必须字段（Properties 格式）：

```properties
minApiVersion=101
targetApiVersion=101
staticScope=false
```

`staticScope=true` 表示用户不得在作用域外应用模块（本项目保持 `false`）。

### 2.2 入口：`XposedModule`（无构造参数注入）

经典：`class XposedInit : IXposedHookLoadPackage { handleLoadPackage(lpparam) }`
现代：`class XposedInit : XposedModule()`，框架在实例化后自动 `attachFramework(base)`；**框架没调用 `onModuleLoaded` 之前禁止做任何初始化**（此时尚未 attach，调用 `hook/getRemotePreferences` 会抛 `IllegalStateException`）。

### 2.3 生命周期回调（`XposedModuleInterface`）

| 回调 | 参数 | 语义 | 对应经典 |
|---|---|---|---|
| `onModuleLoaded(ModuleLoadedParam)` | `isSystemServer()`、`getProcessName()` | 模块进入目标进程时调用一次，先于一切包加载事件 | ≈ 入口静态初始化时机 |
| `onPackageLoaded(PackageLoadedParam)` | `getPackageName()`、`getApplicationInfo()`、`isFirstPackage()`、`getDefaultClassLoader()` | 每个有 code 的包加载进进程时调用一次；默认类加载器就绪、`AppComponentFactory` 实例化之前 | ≈ `handleLoadPackage`（主挂载点） |
| `onPackageReady(PackageReadyParam)` | 继承上述 + `getClassLoader()`、`getAppComponentFactory()` | `AppComponentFactory` 已实例化自定义加载器后 | 有自定义 AppComponentFactory 的包才需要 |
| `onSystemServerStarting(SystemServerStartingParam)` | `getClassLoader()` | system_server 启动关键服务前；**取代 system_server 里的第一个 package 回调** | ≈ `com.android.systemui`/`android` 作用域的 zygote 阶段 |

要点：
- **system_server 中 `isFirstPackage()` 恒为 false**，且 `android` 作用域必须走 `onSystemServerStarting`（原来 `"android"` 包的 `handleLoadPackage` 即对应这里）。
- API 101 行为变更：**模块不再注入 zygote**，只在被选中（scope）的进程内加载。
- 包回调对每个包名进程内只触发一次；多包（sharedUserId / createPackageContext）会触发多次。

### 2.4 Hook 模型：OkHttp 式拦截链（替代 `XC_MethodHook`）

经典是 `XC_MethodHook` 的 `beforeHookedMethod` / `afterHookedMethod`；现代是：

```java
HookHandle handle = hook(method)                 // method: java.lang.reflect.Method
    .setPriority(PRIORITY_DEFAULT)               // 默认 50，可省略
    .setExceptionMode(ExceptionMode.PROTECTIVE)  // 默认 DEFAULT（跟随 module.prop 的 exceptionMode）；PROTECTIVE=挂钩异常被吞掉并继续走原方法
    .intercept(chain -> {
        // —— before 段 ——
        List<Object> args = chain.getArgs();     // 不可变！改参必须用 proceed(args)
        Object thisObject = chain.getThisObject();
        // 调用原方法（等价 invokeOriginalMethod）
        Object result = chain.proceed();
        // —— after 段 ——
        return result;                           // 返回新值 = 改写结果（void 方法忽略返回值）
    });
```

`Chain` 关键方法：

| 方法 | 说明 |
|---|---|
| `getExecutable()` | 被 hook 的 Method/Constructor |
| `getThisObject()` | this，静态方法为 null |
| `getArgs()` | 不可变 List；**改参数必须走 `proceed(Object[] args)` / `proceedWith(this, args)`**（经典 `param.args[i]=x` 无对应直接写法） |
| `getArg(int)` | 取单参数（`ClassCastException` 语义同 cast） |
| `proceed()` | 原参数继续链 / 原方法 |
| `proceed(Object[] args)` | 改参后继续 |
| `proceedWith(Object thisObject[, Object[] args])` | 换 this 继续（静态方法禁用）——对应部分 `invokeOriginalMethod` 换 this 的场景 |
| `getResult()` / `setResult()` | 不存在。改写结果 = `intercept` 的返回值 |

异常优先级测试结论（来自源码注释）：
- `PROTECTIVE`：hooker 抛的异常被框架捕获并记日志，当做本 hook 不存在继续；`proceed()` 抛出的异常始终向上传播。
- `PASSTHROUGH`：hooker 异常直接抛给调用方（调试用）。
- 建议全部 hook 显式 `setExceptionMode(PROTECTIVE)`，与经典 `try-catch` 语义一致且防系统崩溃。

### 2.5 调用任意方法 / 构造器：`Invoker` 体系（替代 `callMethod`）

```java
Invoker<?, Method> invoker = getInvoker(method);
invoker.setType(Type.ORIGIN);              // 跳过所有 hook 直接调原实现
Object r = invoker.invoke(thisObject, args...);        // 等价 callMethod/callStaticMethod
Object r2 = invoker.invokeSpecial(thisObject, args...); // 等价 super.xxx() / 非虚分派

CtorInvoker<T> ctor = getInvoker(constructor);
T obj = ctor.newInstance(args...);                    // 等价 newInstance(findClass, args)
```

**注意**：`getInvoker` 只接受**已经拿到的 `java.lang.reflect.Method/Constructor`**，即它替换的是经典「`callMethod`」的**调用**环节，不负责**查找**环节。Java 反射查找（`findClass`/`findMethod`/`getDeclaredMethod`）需要自建工具或官方 helper 提供——见 §3.3 自建 `Reflect` 工具层。

### 2.6 日志（替代 `XposedBridge.log`）

```java
api.log(Log.INFO, tag, msg);            // 只有优先级/tag/msg 三参
api.log(Log.ERROR, tag, msg, throwable);
```

- 与经典 `XposedBridge.log` 双通道差别：logcat 与框架日志双通道仍由 Logger 控制，仅把底层 `XposedBridge.log(...)` 替换为注入的 `api.log(priority, tag, msg[, tr])`。

### 2.7 偏好设置（替代 `XSharedPreferences`）

| 能力 | 经典 | Modern 101 |
|---|---|---|
| Hook 侧读配置 | `XSharedPreferences(modulePkg, name)` 读模块私有 xml | `api.getRemotePreferences("flymetool_prefs")` → **只读** `SharedPreferences`（存于 LSPosed 数据库，支持跨进程共享与 `registerOnSharedPreferenceChangeListener` 变化监听） |
| App 侧写配置 | 本地 SP + chmod | 经 **libxposed/service**：`XposedServiceHelper.registerListener { onServiceBind(service) -> service.getRemotePreferences("flymetool_prefs") }` 获得**可写**的代理 `SharedPreferences` |
| 组不存在 | — | 读/写都会自动创建组 |

- Modern API 的远程偏好存于 **LSPosed 数据库**（`/data/adb/lspd/db/` 系列），**不会自动迁移旧本地 SP 数据**。存量用户配置需要 App 第一次启动时本地 SP → 远程偏好全量同步一次（幂等）。
- API 101 侧的 `getRemotePreferences` 在 embedded 框架下会抛 `UnsupportedOperationException`，真机 LSPosed 无此问题。

### 2.8 资源 Hook、字段 Hook、`XposedHelpers`

- **资源 Hook：Modern API 整体移除**，本项目未使用，无影响。
- **字段操作没有框架 API**：`getObjectField`/`setBooleanField` 等只能回到 `java.lang.reflect.Field`（`setAccessible(true)` 后 get/set）。需要自建工具封装（含父类回溯、静态字段、基本类型去装箱）。
- **框架不再提供 `XposedHelpers`**。官方推荐两条路：
  1. 官方开发套件 `io.github.libxposed:helper`（还在演进，且需核对与 101 的兼容版本）；
  2. 自建小型反射工具。
  - 本项目决策见 §3 的**方案 A（自建）**，理由：零新依赖、对 `callMethod(instance, "name", args...)` 按名字查找的语义 1:1 保留、迁移工程量机械可控。

---

## 3. 本项目用法盘点与迁移方案

### 3.1 全量用法统计（`app/src/main/java/com/karen/flymetool/hook`）

| 经典 API | 次数 | 现代替代 |
|---|---|---|
| `XposedHelpers.callMethod(obj, name, ...)` | 229 | 自建 `Reflect.callMethod(api, obj, name, ...)`（Invoker 缓存） |
| `XposedHelpers.findClass(name, cl)` | 163 | 自建 `Reflect.findClass(name, cl)` |
| `XposedHelpers.findAndHookMethod(clazz, name, ...args + hook)` | 130 | 自建 `Reflect.findMethod(...)` + `api.hook(method).intercept(chain)` |
| `XposedHelpers.getObjectField(obj, name)` | 78 | 自建 `Reflect.getObjectField(obj, name)` |
| `XposedBridge.hookMethod(method, hook)` | 68 | `api.hook(method).setExceptionMode(PROTECTIVE).intercept(chain)` |
| `XposedBridge.hookAllMethods(clazz, name, hook)` | 30 | 自建 `Reflect.hookAllMethods(api, clazz, name) { chain -> ... }` |
| `XposedHelpers.setBooleanField(obj, name, v)` | 28 | 自建 `Reflect.setBooleanField(obj, name, v)` |
| `XposedHelpers.callStaticMethod(clazz, name, ...)` | 21 | 自建 `Reflect.callStaticMethod(api, clazz, name, ...)` |
| `XposedHelpers.getIntField(obj, name)` | 19 | 自建（`getField` + cast） |
| `XposedHelpers.getFloatField(obj, name)` | 10 | 自建 |
| `XposedHelpers.setObjectField(obj, name, v)` | 9 | 自建 |
| `XposedHelpers.getBooleanField(obj, name)` | 9 | 自建 |
| `XposedHelpers.findAndHookConstructor(clazz, ...)` | 9 | `Reflect.findConstructor(...)` + `api.hook(ctor).intercept(chain)` |
| `XposedHelpers.setIntField(obj, name, v)` | 6 | 自建 |
| `XposedBridge.invokeOriginalMethod(method, this, args)` | 5 | `chain.proceed()` / `chain.proceedWith(this, args)`（必须在链内） |
| `XposedHelpers.setFloatField(obj, name, v)` | 5 | 自建 |
| `XposedBridge.log(...)` | 4 | `api.log(priority, tag, msg[, tr])` |
| `XposedHelpers.getStaticObjectField(clazz, name)` | 4 | 自建 |
| `XposedBridge.hookAllConstructors(clazz, hook)` | 3 | 自建 `Reflect.hookAllConstructors(api, clazz) { chain -> ... }` |
| `XposedHelpers.getSurroundingThis(obj)` | 1 | 无对等：匿名/内部类用 `obj.javaClass.getField("this\$0")` 自取（见 §4.7） |
| `XposedHelpers.getStaticIntField(clazz, name)` | 1 | 自建 |
| `XposedHelpers.newInstance(clazz, args)` | 1 | `api.getInvoker(constructor).newInstance(args)` |
| `XSharedPreferences`（`XposedPrefs.kt`） | — | `api.getRemotePreferences("flymetool_prefs")` |
| `XC_MethodHook.MethodHookParam`（`args`/`thisObject`/`result`） | 面广 | `Chain`（§2.4） |
| `XC_LoadPackage.LoadPackageParam`（入口/接口签名） | 面广 | `PackageLoadedParam` / `PackageReadyParam` / `SystemServerStartingParam` |

### 3.2 方案决策：自建 `Reflect` 工具（不依赖 `io.github.libxposed:helper`）

理由：
1. 对"按方法/字段名 + 参数值"的既有调用语义（`callMethod`、`getObjectField` 等）1:1 保留，60+ Hook 文件只需改**回调外壳**，不动查找/取值逻辑——迁移风险最低。
2. 无新第三方依赖，符合本项目"最少依赖"原则。
3. helper 的 API 仍随版本演进，与 api 101 的精确兼容版本需要额外验证；自建~200 行即可覆盖全表。

自建工具层设计（`hook/base/Reflect.kt`，全部静态、异常策略与经典一致：找不到即抛，由各 Hook 的 try-catch 兜底）：

```kotlin
object Reflect {
    // 类
    fun findClass(name: String, classLoader: ClassLoader?): Class<*>

    // 方法（含父类回溯、私有，经典语义：按名字 + 可选参数列表）
    fun findMethod(clazz: Class<*>, name: String, vararg paramTypes: Class<*>): Method?

    // 调用（缓存 Method，内部使用 api.getInvoker(m).setType(ORIGIN).invoke(...)）
    fun callMethod(api: XposedInterface, obj: Any, name: String, vararg args: Any?): Any?
    fun callStaticMethod(api: XposedInterface, clazz: Class<*>, name: String, vararg args: Any?): Any?

    // 字段（父类回溯 + setAccessible）
    fun getObjectField(obj: Any, name: String): Any?
    fun getIntField(obj: Any, name: String): Int
    fun getFloatField(obj: Any, name: String): Float
    fun getBooleanField(obj: Any, name: String): Boolean
    fun getStaticObjectField(clazz: Class<*>, name: String): Any?
    fun getStaticIntField(clazz: Class<*>, name: String): Int
    fun setBooleanField(obj: Any, name: String, value: Boolean)
    fun setObjectField(obj: Any, name: String, value: Any?)
    fun setIntField(obj: Any, name: String, value: Int)
    fun setFloatField(obj: Any, name: String, value: Float)

    // hook 入口（统一 console 形态，全部 PROTECTIVE）
    fun hookMethod(api: XposedInterface, method: Method, onIntercept: (Chain) -> Any?)
    fun hookAllMethods(api: XposedInterface, clazz: Class<*>, name: String, ...) // 遍历 declaredMethods 匹配名字（含父类）
    fun hookAllConstructors(api: XposedInterface, clazz: Class<*>, ...)
    fun findConstructor(clazz: Class<*>, vararg paramTypes: Class<*>): Constructor<*>?

    // 辅助：基本类型参数匹配（Int::class.java 等），如 needed
    private fun matchParams(m: Method, paramTypes: List<Class<*>>): Boolean
}
```

**性能纪律**：`callMethod`/字段查找必须做「类+名字 → Member」的缓存（普通 `ConcurrentHashMap`），避免逐帧反射查找（本项目有此前的卡顿教训：热路径上禁止深栈/重复反射）。

### 3.3 Hook 回调形态对照（最关键改写规则）

| 经典 | 现代 |
|---|---|
| `object : XC_MethodHook() { override fun beforeHookedMethod(param) { ... } }` | `hook(m).intercept(chain -> { /* before 代码 */ return chain.proceed() })` |
| `override fun afterHookedMethod(param) { param.setResult(v) }` | `val r = chain.proceed(); return v` |
| `param.args[i]` 读 | `chain.getArg(i)` / `chain.getArgs()[i]` |
| `param.args[i] = x` 写 | 重新组数组，`chain.proceed(newArgs)`（注意：后续链中 `getArgs()` 即新值） |
| `param.thisObject` | `chain.getThisObject()` |
| `param.method` | `chain.getExecutable()` |
| `param.result` / `setResult` | `intercept` 返回值 |
| `param.setResult(null)`（提前截断，不再 proceed） | 不调 `proceed()`，直接 `return null` |

---

## 4. 逐模式改写示例（迁移时按此机械套用）

### 4.1 `findClass` + `findAndHookMethod`（beforeHookedMethod）→ 拦截链

```kotlin
// ============ API 82 ============
val clazz = XposedHelpers.findClass("android.media.AudioSystem", classLoader)
XposedHelpers.findAndHookMethod(
    clazz, "setParameters", String::class.java,
    object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            val kv = param.args[0] as? String ?: return
            param.args[0] = kv.replace("x=false", "x=true")   // 改参
        }
    })

// ============ API 101 ============
val clazz = Reflect.findClass("android.media.AudioSystem", classLoader)   // 不存在抛 ClassNotFound，沿用 try-catch
val method = Reflect.findMethod(clazz, "setParameters", String::class.java) ?: return
hook(method)
    .setExceptionMode(ExceptionMode.PROTECTIVE)
    .intercept(chain -> {
        val kv = chain.getArg(0) as? String ?: return@intercept chain.proceed()
        val newArgs = arrayOf<Any>(kv.replace("x=false", "x=true"))
        chain.proceed(newArgs)
    })
```

### 4.2 afterHookedMethod + 改写结果

```kotlin
// 82：override fun afterHookedMethod(param) { param.result = newValue }
// 101：
hook(method).setExceptionMode(ExceptionMode.PROTECTIVE).intercept(chain -> {
    chain.proceed()          // 先跑原方法
    newValue                 // 作为结果返回；void 方法返回任意值被忽略
})
```

### 4.3 `hookAllMethods` / `hookMethod(method, hook)` / `hookAllConstructors`

```kotlin
// 82：XposedBridge.hookAllMethods(clazz, "onActivityResult", hook)
// 101：
Reflect.findMethods(clazz, "onActivityResult").forEach { m ->
    hook(m).setExceptionMode(ExceptionMode.PROTECTIVE).intercept(chain -> { /* ... */ })
}
// 已拿到 Method 的就用 api.hook(m) 直挂；构造器同理传入 ctor。
```

### 4.4 `callMethod` / `callStaticMethod` / `newInstance` → Invoker

```kotlin
// 82：XposedHelpers.callMethod(activity, "findPreference", "xxx")
// 101：
Reflect.callMethod(api, activity, "findPreference", "xxx")

// 82：XposedHelpers.callStaticMethod(helper, "matting", source)
// 101：
Reflect.callStaticMethod(api, helper, "matting", source)

// 82：XposedHelpers.newInstance(helperClass, context)
// 101：
val ctor = Reflect.findConstructor(helperClass, Context::class.java) ?: return
api.getInvoker(ctor).newInstance(context)
```

### 4.5 字段读写（含静态字段、私有字段、父类字段）

```kotlin
// 82：setBooleanField(thisObject, "mIsVirusCheckFinish", true) /
//     getObjectField(parseResult, "mContent") /
//     getStaticIntField(drawableClass, "ic_share_chooser")
// 101：Reflect 同名工具，内部逻辑：
val field = findField(clazz, name)   // getDeclaredField + 逐父类回溯 + setAccessible(true)
field.get/et(obj)                    // 基本类型经 Field#getInt/getFloat/getBoolean 取（去装箱）
```

### 4.6 `invokeOriginalMethod`（5 处）

```kotlin
// 82：XposedBridge.invokeOriginalMethod(param.method, param.thisObject, param.args)
// 101：可直接参与链时才可用：
chain.proceed()                                  // 同参
chain.proceed(newArgs)                           // 改参
chain.proceedWith(thisObject)                    // 换 receiver（静态方法禁用）
chain.proceedWith(thisObject, newArgs)
// 脱离链、拿到独立 Method 的场景 → api.getInvoker(method).setType(ORIGIN).invoke(this, args)
```

### 4.7 `getSurroundingThis`（EdgeBackVibrateHook 1 处）

经典语义：取其闭包外层对象（Java 匿名/内部类强制带 `合成字段 this$0`）。101 无对等 API，直接自取：

```kotlin
// 等价实现
val outer = runCatching {
    obj.javaClass.getDeclaredField("this\$0").apply { isAccessible = true }.get(obj)
}.getOrNull() ?: handler
```

---

## 5. 基础设施改造清单

### 5.1 `XposedInit.kt`（入口重写）

```kotlin
class XposedInit : XposedModule() {
    override fun onModuleLoaded(param: ModuleLoadedParam) {
        // 除日志横幅/预读偏好外的轻量初始化；不能在这里 hook（还没到包回调）
    }
    override fun onPackageLoaded(param: PackageLoadedParam) {
        val pkg = param.packageName
        initLoggerOnce(param)                       // 用远程偏好读 __debug__，Logger.attach(this)
        if (!FlymeVersionUtils.isScopeAvailable(pkg)) return
        entryFactories[pkg]?.let { loadEntry(pkg, it).initHooks(param, this) }
    }
    override fun onSystemServerStarting(param: SystemServerStartingParam) {
        // “android” 作用域入口在这里挂载（进 system_server）
        AndroidEntry.initHooks(param, this)
    }
}
// 保留两段惰性结构：entryFactories 仍是 lambda supplier，保证单个 Entry <clinit> 失败不影响其他作用域
```

基座接口改造建议（保持现有分层，仅换类型）：

```kotlin
// 统一的钩子上下文，替代 XC_LoadPackage.LoadPackageParam
class HookContext(
    val api: XposedInterface,          // XposedModule 实例本身即 XposedInterface
    val packageName: String,
    val classLoader: ClassLoader,      // getDefaultClassLoader()（onPackageLoaded）或 getClassLoader()（onPackageReady）
)

interface FeatureHook { fun handle(ctx: HookContext) }
interface HookEntry {
    val targetPackage: String
    fun initHooks(param: PackageLoadedParam, api: XposedInterface)   // Entry 层仍拿原始 param 兜底
}
```

（也可给 `PackageLoadedParam`/`PackageReadyParam` 写一个 `toContext()` 扩展统一取 classLoader。）

### 5.2 `Logger.kt`

- 新增 `fun attach(api: XposedInterface)`：记录 api 引用，把 `XposedBridge.log(...)` 两处替换为 `api.log(Log.INFO/ERROR, TAG, text[, throwable])`。
- `XposedBridge` 越权访问在 101 下编译不过，改后依赖完全消失。
- `logcat` 命令热切换监听逻辑不变（走 `android.util.Log`）。

### 5.3 `XposedPrefs.kt` → 远程偏好

```kotlin
object XposedPrefs {
    private var api: XposedInterface? = null
    @Volatile private var prefs: SharedPreferences? = null

    fun attach(api: XposedInterface) { this.api = api; prefs = null }

    private fun p(): SharedPreferences =
        prefs ?: synchronized(this) { prefs ?: api!!.getRemotePreferences(PREFS_NAME).also { prefs = it } }

    fun isFeatureEnabled(packageName: String, featureKey: String): Boolean = p().getBoolean("$packageName:$featureKey", false)
    fun getFeatureValue(packageName: String, featureKey: String, defaultValue: Int): Int = p().getInt("$packageName:$featureKey:value", defaultValue)
    fun getFeatureExtraValue(packageName: String, featureKey: String, suffix: String, default: Int): Int = p().getInt("$packageName:$featureKey:$suffix", default)
    fun getFeatureStringSet(packageName: String, featureKey: String, default: Set<String>): Set<String> = p().getStringSet("$packageName:$featureKey:values", default) ?: default
    fun getFeatureString(packageName: String, featureKey: String, default: String): String = p().getString("$packageName:$featureKey:value", default) ?: default
    fun isDebugEnabled(): Boolean = p().getBoolean("__debug__", false)
}
```

- 签名变化：去掉 `lpparam` 入参（各 Hook 调用处同步删参数）。
- 保留 `isFeatureEnabled(lpparam, pkg, key)` 兼容重载可直接删除——本项目由入口统一注入 api。
- **数据迁移**：App 侧（见 §5.5）连接 service 后把本地 SP 全量镜像到远程组"flymetool_prefs"（幂等），解决存量用户配置从零开始的问题。

### 5.4 `generateScope` 任务与 `module.prop` 生成（build.gradle.kts）

- 依赖改为 `compileOnly("io.github.libxposed:api:101.0.0")`，删除 `compileOnly(files("libs/api-82.jar"))` 与 `app/libs/api-82.jar`。
- `features.json` → `META-INF/xposed/scope.list`：
  ```kotlin
  // 生成产物打到 src/main/resources/META-INF/xposed/scope.list
  // （每行一个包名；注意含 "android" 系统进程作用域）
  ```
- 新增 `src/main/resources/META-INF/xposed/module.prop`：
  ```properties
  minApiVersion=101
  targetApiVersion=101
  staticScope=false
  ```
- 新增 `src/main/resources/META-INF/xposed/java_init.list`：一行 `com.karen.flymetool.XposedInit`。
- 删除：`assets/xposed_init`、生成的 `xposed_scope` 资源与 manifest 的 4 组 metadata（`xposedmodule`/`xposeddescription`/`xposedminversion`/`xposedscope`）。
- `MODULE_SETTINGS` 的 `MainActivity` intent-filter 保留（LSPosed 仍靠它进设置页，且需配 `assetPack` 类无要求）。

### 5.5 App 侧配置写入（PrefsHelper + MainActivity）

引入 `io.github.libxposed:service` 依赖，在 App 进程：

```kotlin
// Application/MainActivity.onCreate 中
XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {
    override fun onServiceBind(service: XposedService) {
        RemoteConfig.init(service.getRemotePreferences("flymetool_prefs"))   // 可写代理
        RemoteConfig.migrateFromLocal()     // 存量本地 SP → 远程，幂等
        // 之后所有 UI 读写走 RemoteConfig（RemotePreferences 是 SharedPreferences 子类，双写本地镜像可留作兜底/复用现有逻辑）
    }
})
```

- UI 侧 `PrefsHelper` 改造为「本地镜像 + 远程真值」：远程可用时读远程（`registerOnSharedPreferenceChangeListener` 实现 Hook 侧/UI 侧双向同步），远程不可用时退回本地 SP。
- 权限/`warmup()`（chmod）逻辑在 Modern API 下不再需要（读的是框架数据库而非文件），可删除。

### 5.6 ProGuard（proguard-rules.pro）

按官方 README 添加（迁移后编译时 R8 报 shrink 前必须加上）：

```proguard
-dontwarn io.github.libxposed.annotation.**
-adaptresourcefilecontents META-INF/xposed/java_init.list
-keep,allowoptimization,allowobfuscation public class * extends io.github.libxposed.api.XposedModule { public <init>(); }
```

模块类不混淆、保留行号（Logger 调用源定位依赖）的既有规则保持不变。

---

## 6. 涉及文件清单

| 文件 | 改动 |
|---|---|
| `app/build.gradle.kts` | 依赖 `api:101`、`service`；`generateScope` 输出 `scope.list`；删除 xposed_scope 资源与 xposed_init asset 接入 |
| `app/libs/api-82.jar` | 删除 |
| `app/src/main/AndroidManifest.xml` | 删除 4 组 xposed metadata（保留 MODULE_SETTINGS activity） |
| `app/src/main/assets/xposed_init` | 删除（由 `resources/META-INF/xposed/java_init.list` 替代） |
| `app/src/main/resources/META-INF/xposed/{java_init.list,module.prop}` | 新增 |
| `XposedInit.kt` | 重写为 `XposedModule`，含 `onPackageLoaded`/`onSystemServerStarting` 路由 |
| `hook/base/FeatureHook.kt`、`hook/base/Logger.kt`、`hook/base/XposedPrefs.kt` | 见 §5 |
| `hook/entry/HookEntry.kt`、14 个 `*Entry.kt` | `LoadPackageParam` → `PackageLoadedParam`/`SystemServerStartingParam` + `api` |
| `hook/base/Reflect.kt`（新增） | §3.2 自建反射工具 |
| 全部 `hook/feature/**`（60+ 文件） | §4 逐模式改写 |
| `data/PrefsHelper.kt`、`MainActivity.kt` | service 绑定 + 远程偏好写入/迁移 |
| `proguard-rules.pro` | 补 Modern API keep 规则 |

---

## 7. 风险与注意事项

1. **system_server 时序**：`onSystemServerStarting` 里要 hook 的类加载器 = `param.getClassLoader()`。系统服务启动极快，所有挂载必须在该回调内同步完成，失败即失去本次机会。
2. **类加载器语义差异**：经典 `lpparam.classLoader` ≈ Modern `getDefaultClassLoader()`（P 之后稳定）；有自定义 `AppComponentFactory` 的进程需 `onPackageReady`。
3. **配置从零开始**：存量用户旧本地 SP 不会自动进入 LSPosed 数据库。务必实现并优先测试「App 打开一次 → 自动同步」的迁移路径（这是老用户开关大面积失效的头号风险，参考本项目此前 XSharedPreferences→远程偏好的同类踩坑）。
4. **热路径性能**：`callMethod`/字段访问走 `Reflect` 时**必须缓存解析结果**；禁止在 60fps 热路径做 `getDeclaredMethods` 全扫或 `Throwable().stackTrace`（本项目已有 1.9ms→5ms 的实测教训）。
5. **PROTECTIVE 模式**：framework 的 `exceptionMode` 默认从 module.prop 读；建议断言每个挂载点显式 `setExceptionMode(PROTECTIVE)`，防 hook 异常直接打崩系统进程（救砖考虑不变）。
6. **API 102 不误入**：勿把 `setId`/`replaceHook`/`detach`/`HotReload` 等 102-only 方法编进去（`@SinceApi(102)`，编译期可发现），保持 target=101。
7. **LSPosed 安装门槛**：`minApiVersion=101` 后，低于 2.x 的 LSPosed 不会加载本模块；需与真机确认版本（当前 2.1.1 OK）。
8. **编译期残留检查**：全仓 `Grep -rn "de.robv"` 必须为零（含注释、字符串）；构建产物 APK 内不应再有 `xposed_init`。

---

## 8. 落地顺序建议

1. 部署基座：`build.gradle.kts` 依赖/产物 + `Reflect.kt` + `XposedInit`/`Logger`/`XposedPrefs`/`HookEntry`/`FeatureHook` 全部就位（此时不编译 feature，先让主干编译通过）。
2. 迁移 App 侧配置通道（service + 远程偏好双写 + 本地→远程迁移）。
3. 按作用域逐批改写 feature Hook（SystemUI → Settings → Launcher → 其余），每批 `assembleRelease` 编译验证。
4. 真机冒烟：LSPosed 模块日志 + 各功能开关逐项回归；重点验证 system_server（android 域）与偏好同步。
5. 全仓 `de.robv` 归零检查后提测。