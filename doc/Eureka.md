# Eureka学习笔记

## Eureka项目结构

**eureka-server**：eurka-server的本质也是一个web应用。server也是依赖client，因为server本身也是一个client，在eurka集群模式时，eurka server也要扮演eurka client的角色，往其他的eurka server上去注册。eurka-server可以打成一个war包，然后扔到一个web容器中就可以使用，比如tomcat，Jetty等。

**eureka-core**：eurka代码核心，接收别人的服务注册请求，提供服务发现的功能，保持心跳（续约请求），摘除故障服务实例。eurka server依赖eurka core的功能对外暴露接口，提供注册中心功能。

**eureka-client**：eurka-client负责向eurka-server注册、获取服务列表，发送心跳等。

**eureka-resources**:就是提供了一些前端相关的页面js，css等文件。eurka的管理端应该就是在这里。其中status.jsp就是用来展示注册服务的信息的。

## Eureka依赖

**Jersey**：Eurka用Jersey实现了Http通信，对外提供了一些restful接口来通信。

## Eureka源码

### Eureka-server web.xml结构

从eurka-server的web.xml开始入手，首先是一个Listener **EurekaBootStrap**：负责eurka-server的初始化

#### Filter们

包com.netflix.eureka

- StatusFilter：状态过滤器
- ServerRequestAuthFilter：Eureka-Server 请求认证过滤器
- RateLimitingFilterL：实现限流
- GzipEncodingEnforcingFilter：GZIO编码过滤
- ServletContainer：Jersey MVC请求过滤器

通过<filter-mapping>可知StatusFilter和ServerRequestAuthFilter是对所有请求都开放的。RateLimitingFilter，默认是不开启的，如果需要打开eurka-server内置的限流功能，需要自己吧RateLimitingFilter的<filter-mapping>注释打开。GzipEncodingEnforcingFilter拦截/v2/apps相关的请求。Jersery的核心filter是默认拦截所有请求的。

## Eureka源码解析

### EurekaBootStrap源码解析

EurekaBootStrap的主要功能就是负责启动、初始化、并配置Eurka。在EurekaBootStrap中监听器执行初始化的方法，是contextInitialized()方法，这个方法就是整个eureka-server启动初始化的一个入口。initEurekaServerContext()方法负责初始化eureka-server的上下文。

#### 初始化Eureka-server上下文

EurekaServerConfig其实是基于配置文件实现的Eureka-server的配置类，提供了如下相关的配置：

- 请求认证相关
- 请求限流相关
- 获取注册信息请求相关
- 自我保护机制相关（划重点）
- 注册的应用实例的租约过期相关

initEurekaServerContext()方法中，EurekaServerConfig eurekaServerConfig = new DefaultEurekaServerConfig()这一行代码会进行初始化eureka-server的相关配置。initEurekaEnvironment()方法负责初始化eureka-server的环境。在里面会调用ConfigurationManager.getConfigInstance()方法，这个方法的作用就是在初始化ConfigurationManager的实例，ConfigurationManager从字面上就能看出来是一个配置管理器，负责将配置文件中的配置加载进来供后面的Eurka初始化使用（后面会说到，没有配置的话会自动使用默认配置）。

>  这里注意一下ConfigurationManager初始化使用了Double check形式的单例模式（TODO 后面把代码贴上来），一般我看开源项目中，使用内部类单例的比较少，大部分都使用了DoubleCheck形式的单例模式，DoubleCheck的单例模式需要重点注意一点就是使用volatile关键字修饰单例对象，不然在多线程的情况下，有可能初始化多次。
>
>  ![14097005894197ee0f7e2b3a9fed7730.png](evernotecid://62E7E6AC-1793-4D73-AE1C-84E43655EB8F/appyinxiangcom/18219242/ENResource/p870)

**initEurekaEnvironment中ConfigurationManager初始化流程**

1. 创建一个ConcurrentCompositeConfiguration实例，这个类就包括了eureka所需的所有配置。初始化的时候调用了clear()方法，该方法的作用就是清理了一下配置Map和Eureka相关的事件的监听器List（map和list都是用的线程安全的类，具体哪个自己想），随后调用了fireEvent()方法发布了一个事件(EVENT_CLEAR),fireEvent()方法是netfilx另一个项目netfiex-config中的源码方法，有时间在研究一下那个项目。

2. 判断一下配置中需要不需要通过JMX注册一下配置，如果有相关的配置，那么就调用registerConfigBean()方法把相关的配置发送到JMX，JMX是一个Java平台的管理和监控相关的接口。

3. 随后就往ConcurrentCompositeConfiguration中又加入了一些别的config，随后返回了这个实例

4. 初始化数据数据中心的配置，如果没有的话，就使用默认配置Default data center（这里的数据中心是干嘛的？）

5. 初始化eureka的运行环境，如果没有配置指定，那么就设置为test环境（有什么影响吗？）

加载eureka-server.properties(默认，非默认的是eureka.server.props)中的配置

在eureka-server的resources下面有个eureka-server.properties配置（但是源码中这个文件中的配置都是被注释掉的），这里的配置会加载到DefaultEurekaServerConfig中（通过init()方法），这个类是EurekaServerConfig的实现类，这里面有很多的接口比如getXXXX()方法，包含了所有eureka server所需要的所有配置，都可以通过这个类中的接口来获取。

这里加载有个过程，首先会将eureka-server.properties中的配置都加载到Properties中去；然后会记载eureka-server-环境.properties加载到另一个Properties中，覆盖之前那个老的Properties属性。随后将加载出来的Properties都放入ConfigurationManager中去，实际上放入的是DynamicPropertyFactory类中，这个类是ConfigurationManager中的一个成员变量，由ConfigurationManager进行管理。

**加载eureka-server.properties的过程**

1. 创建一个DefaultEurekaServerConfig对象，执行init()方法。

2. 会读取环境相关的配置，判断当前环境，因为后面有加载对应环境的配置。

3. 通过loadCascadedProperties()(加载级联属性)方法将eureka-server.properties中的配置加载到一个Propertie对象中，然后将Properties对象中的配置放到ConfigurationManager中去，此时ConfigurationManager中就有了所有的配置了，这样配置就管理起来了。

**DynamicPropertyFactory初始化**

DynamicPropertyFactory是在new DefaultEurekaServerConfig对象的时候就初始化成功了。DynamicPropertyFactory的特点是可以在运行时改变属性。

在初始化的时候会通过initWithConfigurationSource()方法把自身与单例的ConfigurationManager关联在一起，在ConfigurationManager完成加载配置的时候，配置也会加载到DynamicPropertyFactory之中。

**总结**

在eureka-server初始化时，会从相关的配置文件中读取对应的配置供后面使用，这里的核心类就是ConfigurationManager，所有的配置最终都会放在这里类中的DynamicPropertyFactory中，DynamicPropertyFactory提供了一些getXXX接口可以直接读取相关的配置。

#### Eureka客户端初始化

加载完配置后会进行客户端初始化，客户端初始化流程如下：

1. 首先加载相关配置，这里Eureka会判断是否是云环境也就是AWS环境或者是本地环境，加载会加载到对应的两个类中CloudInstanceConfig和MyDataCenterInstanceConfig。

2. 相关配置初始化完成后会加载Eureka实例本身的信息到ApplicationInfoManager中，这些信息主要是为了进行服务注册和组成Eureka集群所需要的信息，这些信息都是通过CloudInstanceConfig和MyDataCenterInstanceConfig这两个类提供的，同时这个Eureka也提供了一个拓展点，就是使用更基础的类AbstractInstanceConfig自己去实现其他的加载信息方式。

3. 随后会构建EurekaClientConfig，这个类保存了做了eureka-client的相关配置，加载的方式也是去加载了eureka-client.properties中的配置，跟eureka-server.properties的配置文件为空这个不同，我看到这个版本里eureka-client.properties配置文件里有很多的配置。EurekaClientConfig也是一个接口类，提供了很多getXXX()这种直接读取必要配置的方法。

4. 接下来就会使用ApplicationInfoManager获取服务实例信息和EurekaClientConfig的客户端初始化信息进行客户端的初始化过程，实现类就是DiscoveryClient(applicationInfoManager, eurekaClientConfig)

这里简单的总结一下：

- EurekaInstanceConfig，重在**应用实例**，例如，应用名、应用的端口等等。
- EurekaClientConfig，重在 **Eureka-Client**，例如， 连接的 Eureka-Server 的地址、获取服务提供者列表的频率、注册自身为服务提供者的频率等等。

**DiscoveryClient源码**

在看DiscoveryClient之前，首先整理一下与这个类相关的类。EurekaClient是一个接口，声明了如下方法：

- 提供**多种**方法获取应用集合(`com.netflix.discovery.shared.Applications`) 和 应用实例信息集合( `com.netflix.appinfo.InstanceInfo` )。
- 提供方法获取**本地**客户端信息，例如，应用管理器( `com.netflix.appinfo.ApplicationInfoManager` )和 Eureka-Client 配置( `com.netflix.discovery.EurekaClientConfig` )。
- 提供方法**注册**本地客户端的健康检查和 Eureka 事件监听器。

EurekaClient继承了LookupService接口，LookupService接口是查找服务接口，提供简单单一的方式获取应用集合(`com.netflix.discovery.shared.Applications`) 和 应用实例信息集合( `com.netflix.appinfo.InstanceInfo` )。

DiscoveryClient是EurekaClient的具体实现类，用于与eureka-server进行交互，实现了如下的方法：

- 向 Eureka-Server **注册**自身服务
- 向 Eureka-Server **续约**自身服务
- 向 Eureka-Server **取消**自身服务，当关闭时
- 从 Eureka-Server **查询**应用集合和应用实例信息

> *简单理解，就是实现了对 Eureka-Server 服务的增删改查操作*

DiscoveryClient的完整构造方法如下

```java
DiscoveryClient(ApplicationInfoManager applicationInfoManager, EurekaClientConfig config, AbstractDiscoveryClientOptionalArgs args,
                Provider<BackupRegistry> backupRegistryProvider, EndpointRandomizer endpointRandomizer) 
```

- 其中backupRegistryProvider，`com.netflix.discovery.BackupRegistry`，备份注册中心接口。当 Eureka-Client 启动时，无法从 Eureka-Server 读取注册信息（可能挂了），从备份注册中心读取注册信息。默认实现是`com.netflix.discovery.NotImplementedRegistryImpl` 可以看出，目前 Eureka-Client 未提供合适的默认实现。

- `com.netflix.discovery.AbstractDiscoveryClientOptionalArgs`，DiscoveryClient 可选参数抽象基类。不同于上面三个必填参数，该参数是选填参数，实际生产下使用较少。这里有一个HealthCheckCallback的健康检查回调接口，目前已经废弃，使用 HealthCheckHandler 替可以不再关注。HealthCheckHandler健康检查处理器接口，目前暂未提供合适的默认实现，唯一提供的 `com.netflix.appinfo.HealthCheckCallbackToHandlerBridge`，用于将 HealthCheckCallback 桥接成 HealthCheckHandler。（这里可以学习一下桥接模式的使用）在 Spring-Cloud-Eureka-Client，提供了默认实现 [`org.springframework.cloud.netflix.eureka.EurekaHealthCheckHandler`](https://github.com/spring-cloud/spring-cloud-netflix/blob/82991a7fc2859b6345b7f67e2461dbf5d7663836/spring-cloud-netflix-eureka-client/src/main/java/org/springframework/cloud/netflix/eureka/EurekaHealthCheckHandler.java)，需要结合 [`spirng-boot-actuate`](https://github.com/spring-projects/spring-boot/tree/c79568886406662736dcdce78f65e7f46dd62696/spring-boot-actuator/) 使用
- 这里有一个拓展点`com.netflix.discovery.PreRegistrationHandler`，向 Eureka-Server 注册之前的处理器接口，目前暂未提供默认实现。通过实现该接口，可以在注册前做一些自定义的处理。

DiscoveryClient构造步骤

1. 赋值AbstractDiscoveryClientOptionalArgs
2. 赋值ApplicationInfoManager、EurekaClientConfig
3. 初始化Applications在本地的缓存
4. 获取那些Region集合的注册信息
5. 初始化拉取、心跳的监控，这里有两个时间戳在每次从eureka-server拉取心跳或者拉取注册信息后都会更新
6. 如果配置了shouldRegisterWithEureka或者shouldFetchRegistry参数，那么这里都会和eureka-server进行交互
7. 初始化3个线程池，分别是scheduler负责执行（updating service urls、scheduling a TimedSuperVisorTask）、heartbeatExecutor（心跳执行器）、cacheRefreshExecutor（刷新执行器）
8. 初始化网络通信相关eurekaTransport = new EurekaTransport();
9. 从Eureka-Server 拉取注册信息，调用fetchRegistry方法拉取注册信息，如果失败了则走fetchRegistryFromBackup方法从备份注册中心拉取，但是BackupRegistry目前没有默认实现，所以这里是个扩展点，需要用户自己实现。
10. 执行前面说的PreRegistrationHandler注册前的处理器（拓展点）
11. 初始化定时任务
12. 向Netflix Servo注册监控

**PeerAwareInstanceRegistry应用实例注册表（应该是）**

分为亚马逊环境和非亚马逊环境两套逻辑

**创建Eureka-server集群节点集合**

```java
PeerEurekaNodes peerEurekaNodes = getPeerEurekaNodes(
        registry,
        eurekaServerConfig,
        eurekaClient.getEurekaClientConfig(),
        serverCodecs,
        applicationInfoManager
);
```

**创建Eureka-server上下文**

```java
serverContext = new DefaultEurekaServerContext(
        eurekaServerConfig,
        serverCodecs,
        registry,
        peerEurekaNodes,
        applicationInfoManager
);
```

com.netflix.eureka.EurekaServerContext是上下文接口，提供了Eureka-Server内部各个组件对象的初始化、关闭、获取等方法

**初始化EurekaServerContextHolder和上下文**

```java
EurekaServerContextHolder.initialize(serverContext);
serverContext.initialize();
```

通过EurekaServerContextHolder可以很方便的提取Eureka-Server的上下文信息

**从其他Eureka-Server拉取注册信息**

```java
// Copy registry from neighboring eureka node
int registryCount = registry.syncUp();
registry.openForTraffic(applicationInfoManager, registryCount);
```

简单的说就是进行集群同步（这里要好好看看）

**注册监控**

```java
EurekaMonitors.registerAllStats();
```

注册Netflix Servo实现监控信息采集

#### 总结

通过ServletContextListener启动了EurekaBootStrap，在EurekaBootStrap中用过读取配置文件、Aws等初始化Eureka-Server的上下文，同时初始化了一个内嵌的Eureka-Client与集群中的其他Eureka-Server交互，最后进行了集群信息同步。

#### Eureka-Client发起服务注册

Eureka-Client 向 Eureka-Server 发起注册应用实例需要符合如下条件：

- 配置 `eureka.registration.enabled = true`，Eureka-Client 向 Eureka-Server 发起注册应用实例的开关。
- InstanceInfo 在 Eureka-Client 和 Eureka-Server 数据不一致。

每次 InstanceInfo 发生属性变化时，标记 `isInstanceInfoDirty` 属性为 `true`，表示 InstanceInfo 在 Eureka-Client 和 Eureka-Server 数据不一致，需要注册。另外，InstanceInfo 刚被创建时，在 Eureka-Server 不存在，也会被注册。当符合条件时，InstanceInfo 不会立即向 Eureka-Server 注册，而是后台线程定时注册。

当 InstanceInfo 的状态( `status` ) 属性发生变化时，并且配置 `eureka.shouldOnDemandUpdateStatusChange = true` 时，立即向 Eureka-Server 注册。因为状态属性非常重要，一般情况下建议开启，当然默认情况也是开启的。

在DiscoveryClien构造函数中的`initScheduledTask()`方法种有一个应用实例注册器InstanceInfoReplicator类负责将应用信息复制到Eureka-Server上，调用start()方法负责应用实例注册。InstanceInfoReplicator会把自己作为一个定时线程任务，定时的去检查InstanceInfo的状态是否发生了变化，具体流程如下：

- 调用 `DiscoveryClient#refreshInstanceInfo()` 方法，刷新应用实例信息。此处可能导致应用实例信息数据不一致。
- 调用 `DiscoveryClient#register()` 方法，Eureka-Client 向 Eureka-Server 注册应用实例。
- 调用 `ScheduledExecutorService#schedule(...)` 方法，再次延迟执行任务，并设置 `scheduledPeriodicRef`。通过这样的方式，不断循环定时执行任务。（注意看这里的代码实现也可以借鉴一下，形成一个时间轮）

```java
   try {
       // 刷新 应用实例信息
       discoveryClient.refreshInstanceInfo();
       // 判断 应用实例信息 是否数据不一致
       Long dirtyTimestamp = instanceInfo.isDirtyWithTime();
       if (dirtyTimestamp != null) {
           // 发起注册
           discoveryClient.register();
           // 设置 应用实例信息 数据一致
           instanceInfo.unsetIsDirty(dirtyTimestamp);
       }
   } catch (Throwable t) {
       logger.warn("There was a problem with the instance info replicator", t);
   } finally {
       // 提交任务，并设置该任务的 Future 注意这里实际上形成了一个时间轮，以后有类似的需求可以通过这种方法实现时间轮
       Future next = scheduler.schedule(this, replicationIntervalSeconds, TimeUnit.SECONDS);
       scheduledPeriodicRef.set(next);
   }
```

InstanceInfo的status改变发生在调用 `ApplicationInfoManager#setInstanceStatus(...)` 方法，设置应用实例信息的状态，从而通知 `InstanceInfoReplicator#onDemandUpdate()` 方法的调用,在这份方法中最终会调用InstanceInfoReplicator类的InstanceInfoReplicator.this.run();

> Eureka这里有个Pair类，有点意思。可以学习一下这种不固定的多类型参数传递可以自己实现一个类似的类。

##### 刷新应用实例

通过调用 `DiscoveryClient#refreshInstanceInfo()`刷新应用实例，最终都会调用到ApplicationInfoManager中的方法。然后就会刷新一些属性等等等等等。

##### 发起实例注册

调用 `DiscoveryClient#register()` 方法，Eureka-Client 向 Eureka-Server 注册应用实例。最终调用调用 `AbstractJerseyEurekaHttpClient#register(...)` 方法，POST 请求 Eureka-Server 的 apps/${APP_NAME} 接口，参数为 InstanceInfo ，实现注册实例信息的注册。

##### Eureka-server接收注册

在eureka-core项目中，有个resource包，里面全是一些XXXResource类，这些类其实类似于Spring中的Controller，就是Jersey实现的MVC。其中ApplicationResource是处理单个应用注册的Resource。注册应用实例信息的请求，映射 `ApplicationResource#addInstance()` 方法。注册的相关代码很长，涉及到了`PeerAwareInstanceRegistryImpl#register()`、最终的注册逻辑在`AbstractInstanceRegistry#register()`方法中实现。逻辑比较多，简单的概括一下都做了什么事情。

- 用读写锁控制注册，提供了并发性能。
- 增加了监控
- 添加到最近注册的调试队列( `recentRegisteredQueue` )这个是干嘛的？
- 修改了一些租约信息什么的
- **最终把实例信息放入了Map<String, Lease<InstanceInfo>>  gMap中**
- 还有很重要的一点就是`invalidateCache()`方法清除本地缓存readWriteCacheMap

#### Eureka-Client发起服务续约

Eureka-Client 向 Eureka-Server 发起注册应用实例成功后获得租约 ( Lease )。Eureka-Client **固定间隔**向 Eureka-Server 发起续租( renew )，避免租约过期。

默认情况下，租约有效期为 90 秒，续租频率为 30 秒。两者比例为 1 : 3 ，保证在网络异常等情况下，有三次重试的机会。

Eureka-Client通过心跳任务`HeartbeatThread()`续约。

```java
private class HeartbeatThread implements Runnable {
   public void run() {
       if (renew()) {
           lastSuccessfulHeartbeatTimestamp = System.currentTimeMillis();
       }
   }
}
```

在`renew()`方法中最终通过发送PUT请求 Eureka-Server 的 `apps/${APP_NAME}/${INSTANCE_INFO_ID}` 接口，参数为 `status`、`lastDirtyTimestamp`、`overriddenstatus`，实现续租。

##### TimedSupervisorTask

`com.netflix.discovery.TimedSupervisorTask`，监管定时任务的任务。

##### Eureka-Server接收续租

`InstanceResource`，处理**单个**应用实例信息的请求操作的 Resource 。续租应用实例信息的请求，映射 `renewLease()` 方法。续租方法的核心逻辑如下：

各种判断续租是否成功，如果成功了会将应用续租信息进行集群同步，最终续租信息也会展示在Eureka的管理端页面上。

#### Eureka-Client获取注册信息

**Applications**封装由Eureka服务器返回的所有注册表信息的类，在获取后会进行混洗，然后根据`InstanceStatus＃UP`状态进行过滤。

**Application**类包含了特定实例的应用信息(**InstanceInfo**)列表。

**InstanceInfo**类包含了一个服务实例被其他服务发现所需要的必须信息。

Applications 与 InstanceInfo 类关系如下：**Applications** 1:N **Application** 1:N **InstanceInfo**

> 后面所说的Eureka-Client拉取的注册信息都是Applications

##### Eureka-Client初始化时全量获取注册信息

Eureka-Client 获取注册信息，分成全量获取和增量获取。默认配置下，Eureka-Client 启动时，首先执行一次全量获取进行本地缓存注册信息，而后每 **30** 秒增量获取刷新本地缓存( 非正常情况下会是全量获取，对比注册信息哈希值不一致)，Eureka-Client在启动时会执行下面这段代码，根据`shouldFetchRegistry == true`配置判断是否拉取注册信息，通过fetchRegistry(false)方法获取全量注册信息。

```java
if (clientConfig.shouldFetchRegistry()) {
    try {
        boolean primaryFetchRegistryResult = fetchRegistry(false);
        if (!primaryFetchRegistryResult) {
            logger.info("Initial registry fetch from primary servers failed");
        }
        boolean backupFetchRegistryResult = true;
        if (!primaryFetchRegistryResult && !fetchRegistryFromBackup()) {
            backupFetchRegistryResult = false;
            logger.info("Initial registry fetch from backup servers failed");
        }
        if (!primaryFetchRegistryResult && !backupFetchRegistryResult && clientConfig.shouldEnforceFetchRegistryAtInit()) {
            throw new IllegalStateException("Fetch registry error at startup. Initial fetch failed.");
        }
    } catch (Throwable th) {
        logger.error("Fetch registry error at startup: {}", th.getMessage());
        throw new IllegalStateException(th);
    }
}
```

这个方法最终会通过jerseyClient使用Get请求到`ApplicationsResource#getContainers()`获取Applications信息。

**定时获取**

Eureka-Client 在初始化过程中，创建获取注册信息线程，固定间隔30S向 Eureka-Server 发起获取注册信息( fetch )，刷新本地注册信息缓存。具体实现在`initScheduledTasks()`方法中创建了`CacheRefreshThread()`任务，`client.refresh.interval`配置为具体的执行时间间隔，默认为30S

```java
if (clientConfig.shouldFetchRegistry()) {
    // registry cache refresh timer
    int registryFetchIntervalSeconds = clientConfig.getRegistryFetchIntervalSeconds();
    int expBackOffBound = clientConfig.getCacheRefreshExecutorExponentialBackOffBound();
    cacheRefreshTask = new TimedSupervisorTask(
            "cacheRefresh",
            scheduler,
            cacheRefreshExecutor,
            registryFetchIntervalSeconds,
            TimeUnit.SECONDS,
            expBackOffBound,
            new CacheRefreshThread()
    );
    scheduler.schedule(
            cacheRefreshTask,
            registryFetchIntervalSeconds, TimeUnit.SECONDS);
}
```

在`CacheRefreshThread()`中调用`fetchRegistry（）`方法获取注册信息。获取注册信息后回更新注册信息的应用实例数，最后回去注册信息的时间。

**fetchRegistry()方法**

调用 `#fetchRegistry(false)` 方法，从 Eureka-Server 获取注册信息( 根据条件判断，可能是**全量**，也可能是**增量** )。fetchRegistry()方法中的逻辑我觉得比较重要，所有没有节省篇幅把代码都贴上来了。

```java
private boolean fetchRegistry(boolean forceFullRegistryFetch) {
    Stopwatch tracer = FETCH_REGISTRY_TIMER.start();

    try {
        // If the delta is disabled or if it is the first time, get all
        // applications
        Applications applications = getApplications();

        if (clientConfig.shouldDisableDelta()
                || (!Strings.isNullOrEmpty(clientConfig.getRegistryRefreshSingleVipAddress()))
                || forceFullRegistryFetch
                || (applications == null)
                || (applications.getRegisteredApplications().size() == 0)
                || (applications.getVersion() == -1)) //Client application does not have latest library supporting delta
        {
            logger.info("Disable delta property : {}", clientConfig.shouldDisableDelta());
            logger.info("Single vip registry refresh property : {}", clientConfig.getRegistryRefreshSingleVipAddress());
            logger.info("Force full registry fetch : {}", forceFullRegistryFetch);
            logger.info("Application is null : {}", (applications == null));
            logger.info("Registered Applications size is zero : {}",
                    (applications.getRegisteredApplications().size() == 0));
            logger.info("Application version is -1: {}", (applications.getVersion() == -1));
            getAndStoreFullRegistry();
        } else {
            getAndUpdateDelta(applications);
        }
        applications.setAppsHashCode(applications.getReconcileHashCode());
        logTotalInstances();
    } catch (Throwable e) {
        logger.info(PREFIX + "{} - was unable to refresh its cache! This periodic background refresh will be retried in {} seconds. status = {} stacktrace = {}",
                appPathIdentifier, clientConfig.getRegistryFetchIntervalSeconds(), e.getMessage(), ExceptionUtils.getStackTrace(e));
        return false;
    } finally {
        if (tracer != null) {
            tracer.stop();
        }
    }
    // Notify about cache refresh before updating the instance remote status
    onCacheRefreshed();
    // Update remote status based on refreshed data held in the cache
    updateInstanceRemoteStatus();
    // registry was fetched successfully, so return true
    return true;
}
```

具体逻辑如下：

- 从本地缓存AtomicReference<Applications> localRegionApps中获取Applications

- 根据条件判断是全量获取还增量获取，**通过getAndStoreFullRegistry()获取全量注册信息并设置到本地缓存AtomicReference<Applications> localRegionApps**（这里只看全量获取的逻辑）

- `setAppsHashCode()`方法计算应用集合的hashcode

- `onCacheRefreshed()`触发 CacheRefreshedEvent 事件，事件监听器执行。目前 Eureka 未提供默认的该事件监听器。可以实现自定义的事件监听器监听 CacheRefreshedEvent 事件，以达到**持久化**最新的注册信息到存储器( 例如，本地文件 )，通过这样的方式，配合实现 BackupRegistry 接口读取存储器。BackupRegistry 接口调用如下:

  ```java
  if (clientConfig.shouldFetchRegistry() && !fetchRegistry(false)) {
      fetchRegistryFromBackup();
  }
  ```

- `updateInstanceRemoteStatus()`更新本地缓存的当前应用实例在Eureka-Server的状态，对比**本地缓存**和**最新的**的当前应用实例在 Eureka-Server 的状态，若不同，更新**本地缓存**( **注意，只更新该缓存变量，不更新本地当前应用实例的状态( `instanceInfo.status` )** )，触发 StatusChangeEvent 事件，事件监听器执行。目前 Eureka 未提供默认的该事件监听器。

#### Eureka-Server处理全量获取请求

`ApplicationsResource类`是处理**所有**应用的请求操作的 Resource，接收全量获取请求映射到 `ApplicationsResource#getContainers()` 方法.在getContainers方法中，最终会从ResponseCache中获取注册信息，根据`shouldUseReadOnlyResponseCache == true`判断是否只从readOnlyCacheMap中获取注册信息，否则就从readWriteCacheMap中获取全量注册信息。

#### Eureka缓存结构

Eureka作为注册中心采用了AP模式，所以可以使用缓存。

Eureka中的缓存结构，`ResponseCache`，响应缓存**接口**，其实现类`com.netflix.eureka.registry.ResponseCacheImpl`，响应缓存实现类。在 ResponseCacheImpl 里，将缓存拆分成两层 ：

- **只读缓存**( `readOnlyCacheMap` )
- **固定过期** + **固定大小**的**读写缓存**( `readWriteCacheMap` )，最大缓存数量为1000，通过Guava Cache实现的。

默认配置下，**缓存读取策略**如下：

![image-20210424153953413](/Users/lidong/Library/Application Support/typora-user-images/image-20210424153953413.png)

**缓存过期策略**如下：

- 应用实例注册、下线、过期时，**只**过期 `readWriteCacheMap` 。
- `readWriteCacheMap` 写入一段时间( 可配置 )后自动过期。
- 定时任务对比 `readWriteCacheMap` 和 `readOnlyCacheMap` 的缓存值，若不一致，以前者为主。通过这样的方式，实现了 `readOnlyCacheMap` 的定时过期。

> **注意**：应用实例注册、下线、过期时，不会很快刷新到 `readWriteCacheMap` 缓存里。默认配置下，最大延迟在 30 秒。

**主动过期读写缓存**

应用实例注册、下线、过期时，调用 `ResponseCacheImpl#invalidate()` 方法，主动过期读写缓存( `readWriteCacheMap` )

**被动过期读写缓存**

读写缓存( `readWriteCacheMap` ) 写入后，一段时间自动过期，实现代码如下：

```
expireAfterWrite(serverConfig.getResponseCacheAutoExpirationInSeconds())
```

- 配置 `eureka.responseCacheAutoExpirationInSeconds` ，设置写入过期时长。默认值 ：180 秒

**定时任务刷新只读缓存**

定时任务对比 `readWriteCacheMap` 和 `readOnlyCacheMap` 的缓存值，若不一致，以前者为主。通过这样的方式，实现了 `readOnlyCacheMap` 的定时过期。实现代码如下：

```java
private TimerTask getCacheUpdateTask() {
    return new TimerTask() {
        @Override
        public void run() {
            logger.debug("Updating the client cache from response cache");
            for (Key key : readOnlyCacheMap.keySet()) {
                if (logger.isDebugEnabled()) {
                    logger.debug("Updating the client cache from response cache for key : {} {} {} {}",
                            key.getEntityType(), key.getName(), key.getVersion(), key.getType());
                }
                try {
                    CurrentRequestVersion.set(key.getVersion());
                    Value cacheValue = readWriteCacheMap.get(key);
                    Value currentCacheValue = readOnlyCacheMap.get(key);
                    if (cacheValue != currentCacheValue) {
                        readOnlyCacheMap.put(key, cacheValue);
                    }
                } catch (Throwable th) {
                    logger.error("Error while updating the client cache from response cache for key {}", key.toStringCompact(), th);
                } finally {
                    CurrentRequestVersion.remove();
                }
            }
        }
    };
}
```

- 第 7 至 12 行 ：初始化定时任务。配置 `eureka.responseCacheUpdateIntervalMs`，设置任务执行频率，默认值 ：30 * 1000 毫秒。
- 第 17 至 39 行 ：创建定时任务。
  - 第 22 行 ：循环 `readOnlyCacheMap` 的缓存键。**为什么不循环 `readWriteCacheMap` 呢**？ `readOnlyCacheMap` 的缓存过期依赖 `readWriteCacheMap`，因此缓存键会更多。
  - 第 28 行 至 33 行 ：对比 `readWriteCacheMap` 和 `readOnlyCacheMap` 的缓存值，若不一致，以前者为主。通过这样的方式，实现了 `readOnlyCacheMap` 的定时过期。