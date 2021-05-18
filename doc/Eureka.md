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
- GzipEncodingEnforcingFilter：GZIP编码过滤
- ServletContainer：Jersey MVC请求过滤器

通过<filter-mapping>可知StatusFilter和ServerRequestAuthFilter是对所有请求都开放的。RateLimitingFilter，默认是不开启的，如果需要打开eurka-server内置的限流功能，需要自己吧RateLimitingFilter的<filter-mapping>注释打开。GzipEncodingEnforcingFilter拦截/v2/apps相关的请求。Jersery的核心filter是默认拦截所有请求的。

## Eureka源码解析

### EurekaBootStrap源码解析

EurekaBootStrap的主要功能就是负责启动、初始化、并配置Eureka。在EurekaBootStrap中监听器执行初始化的方法，是contextInitialized()方法，这个方法就是整个eureka-server启动初始化的一个入口。initEurekaServerContext()方法负责初始化eureka-server的上下文。

#### 初始化Eureka-server上下文

EurekaServerConfig其实是基于配置文件实现的Eureka-server的配置类，提供了如下相关的配置：

- 请求认证相关
- 请求限流相关
- 获取注册信息请求相关
- 自我保护机制相关（划重点）
- 注册的应用实例的租约过期相关

initEurekaServerContext()方法中，EurekaServerConfig eurekaServerConfig = new DefaultEurekaServerConfig()这一行代码会进行初始化eureka-server的相关配置。initEurekaEnvironment()方法负责初始化eureka-server的环境。在里面会调用ConfigurationManager.getConfigInstance()方法，这个方法的作用就是在初始化ConfigurationManager的实例，ConfigurationManager从字面上就能看出来是一个配置管理器，负责将配置文件中的配置加载进来供后面的Eurka初始化使用（后面会说到，没有配置的话会自动使用默认配置）。

>  这里注意一下ConfigurationManager初始化使用了Double check形式的单例模式，一般我看开源项目中，使用内部类单例的比较少，大部分都使用了DoubleCheck形式的单例模式，DoubleCheck的单例模式需要重点注意一点就是使用volatile关键字修饰单例对象，不然在多线程的情况下，有可能初始化多次。
>
>  ```java
>  static volatile AbstractConfiguration instance = null;
>  public static AbstractConfiguration getConfigInstance() {
>      if (instance == null) {
>          synchronized (ConfigurationManager.class) {
>              if (instance == null) {
>                  instance = getConfigInstance(Boolean.getBoolean(DynamicPropertyFactory.DISABLE_DEFAULT_CONFIG));
>              }
>          }
>      }
>      return instance;
>  }
>  ```

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

**创建Eureka-server集群节点信息**

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

通过ServletContextListener启动了EurekaBootStrap，在EurekaBootStrap中用过读取配置文件、Aws等初始化Eureka-Server的上下文，同时初始化了一个内嵌的Eureka-Client与集群中的其他Eureka-Server交互，最后进行了集群信息同步，同时把自己注册到别的Eureka服务上去。

#### Eureka-Client发起服务注册

Eureka-Client 向 Eureka-Server 发起注册应用实例需要符合如下条件：

- 配置 `eureka.registration.enabled = true`，Eureka-Client 向 Eureka-Server 发起注册应用实例的开关。
- InstanceInfo 在 Eureka-Client 和 Eureka-Server 数据不一致。

每次 InstanceInfo 发生属性变化时，标记 `isInstanceInfoDirty` 属性为 `true`，表示 InstanceInfo 在 Eureka-Client 和 Eureka-Server 数据不一致，需要注册。另外，InstanceInfo 刚被创建时，在 Eureka-Server 不存在，也会被注册。当符合条件时，InstanceInfo 不会立即向 Eureka-Server 注册，而是后台线程定时注册。

当 InstanceInfo 的状态( `status` ) 属性发生变化时，并且配置 `eureka.shouldOnDemandUpdateStatusChange = true` 时，立即向 Eureka-Server 注册。因为状态属性非常重要，一般情况下建议开启，当然默认情况也是开启的。

在DiscoveryClien构造函数中的`initScheduledTask()`方法种有一个应用实例注册器`InstanceInfoReplicator`类负责将应用信息复制到Eureka-Server上，调用start()方法负责应用实例注册。`InstanceInfoReplicator`会把自己作为一个定时线程任务，定时的去检查InstanceInfo的状态是否发生了变化，具体流程如下：

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

各种判断续租是否成功，如果成功了会将应用续租信息进行**集群同步**，这点很重要，Eureka实际上是通过心跳来维系整个集群的信息同步的，最终续租信息也会展示在Eureka的管理端页面上。

续租的最终逻辑就是改变了一个续租的时间戳，把当前时间戳延长一个配置时间，默认是90S。

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

`ApplicationsResource类`是处理**所有**应用的请求操作的 Resource，接收全量获取请求映射到 `ApplicationsResource#getContainers()` 方法.在getContainers方法中，最终会从`ResponseCache`中获取注册信息，根据`shouldUseReadOnlyResponseCache == true`判断是否只从readOnlyCacheMap中获取注册信息，否则就从readWriteCacheMap中获取全量注册信息。

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

读写缓存( `readWriteCacheMap` ) 写入后因为使用guava cache实现的所以可以一段时间自动过期，实现代码如下：

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

- 初始化定时任务。配置 `eureka.responseCacheUpdateIntervalMs`，设置任务执行频率，默认值 ：30 * 1000 毫秒。
- 定时任务逻辑
  - 第 22 行 ：循环 `readOnlyCacheMap` 的缓存键。**为什么不循环 `readWriteCacheMap` 呢**？ `readOnlyCacheMap` 的缓存过期依赖 `readWriteCacheMap`，因此缓存键会更多。
  - 第 28 行 至 33 行 ：对比 `readWriteCacheMap` 和 `readOnlyCacheMap` 的缓存值，若不一致，以前者为主。通过这样的方式，实现了 `readOnlyCacheMap` 的定时过期。

#### Eureka-Client注册实例增量获取

**应用一致性哈希码**

`Applications.appsHashCode` ，应用集合**一致性哈希码**。增量获取注册的应用集合( Applications ) 时，Eureka-Client 会获取到：

1. Eureka-Server 近期变化( 注册、下线 )的应用集合
2. Eureka-Server 应用集合一致性哈希码

Eureka-Client 将**变化**的应用集合和**本地缓存**的应用集合进行合并后进行计算本地的应用集合一致性哈希码。若两个**哈希码**相等，意味着增量获取成功；若不相等，意味着增量获取失败，Eureka-Client 重新和 Eureka-Server **全量**获取应用集合。

计算公式为appsHashCode = ${status}_${count}_

- 使用每个应用实例状态( `status` ) + 数量( `count` )拼接出一致性哈希码。若数量为 0 ，该应用实例状态不进行拼接。**状态以字符串大小排序**。
- 举个例子，8 个 UP ，0 个 DOWN ，则 `appsHashCode = UP_8_` 。8 个 UP ，2 个 DOWN ，则 `appsHashCode = DOWN_2_UP_8_` 。

**Eureka-Client发起增量获取**

调用 `DiscoveryClient#getAndUpdateDelta(...)` 方法，**增量**获取注册信息，并**刷新**本地缓存。代码逻辑如下：

- 请求增量获取注册信息，调用 `AbstractJerseyEurekaHttpClient#getApplicationsInternal(...)` 方法，GET 请求 Eureka-Server 的 `apps/detla` 接口，参数为 `regions` ，返回格式为 JSON ，实现增量获取注册信息。
- 如果增量获取失败，那么就全量获取注册信息，并设置到本地缓存。
- 处理增量获取结果
  - `#updateDelta(...)` 方法，将**变化**的应用集合和本地缓存的应用集合进行合并。
  - 判断一致性哈希值，调用 `#reconcileAndLogDifference()` 方法，全量获取注册信息，并设置到本地缓存，和 `#getAndStoreFullRegistry()` 基本类似。
  - 配置 `eureka.printDeltaFullDiff` ，是否打印增量和全量差异。默认值 ：`false` 。从目前代码实现上来看，暂时没有生效。**注意** ：开启该参数会导致每次**增量**获取后又发起**全量**获取，不要开启。

**合并应用集合**

调用 `#updateDelta(...)` 方法，将变化的应用集合本地缓存的应用集合进行合并。最终放到`Application`类下`Map<String, InstanceInfo> instancesMap`中。

#### Eureka-Server处理增量获取请求

通过`ApplicationsRescoure#getContainerDifferential()`方法处理增量获取请求，`ResponseCacheImpl`会根据不同的key由不同的缓存处理逻辑，对应的代码在``ResponseCacheImpl#ResponseCacheImpl()`的构造方法中，在初始化readWriteCacheMap时有一段代码如下：

```java
.build(new CacheLoader<Key, Value>() {
    @Override
    public Value load(Key key) throws Exception {
        if (key.hasRegions()) {
            Key cloneWithNoRegions = key.cloneWithoutRegions();
            regionSpecificKeys.put(cloneWithNoRegions, key);
        }
        Value value = generatePayload(key);
        return value;
    }
});
```

其中`generatePayload(key)`方法有不同的key对应的缓存的生成逻辑。值得注意的是增量拉取注册信息的时候，Eureka通过维护一个最近租约变更队列维护了增量信息。

**最近租约变更队列**

`AbstractInstanceRegistry.recentlyChangedQueue`，最近租约变更记录队列。ConcurrentLinkedQueue<RecentlyChangedItem> recentlyChangedQueue.`RecentlyChangedItem`中维护了最近租约变更的服务实例信息。具体逻辑如下：

- 当应用实例注册、下线、状态变更时，创建最近租约变更记录( RecentlyChangedItem ) 到队列。
- 后台任务定时**顺序**扫描队列，当 `lastUpdateTime` 超过一定时长（3分钟，可配置）后进行移除。
- 配置 `eureka.deltaRetentionTimerIntervalInMs`， 移除队列里过期的租约变更记录的定时任务执行频率，单位：毫秒。默认值 ：30 * 1000 毫秒。

**读取缓存**

在 `#generatePayload()` 方法里，调用`getApplicationDeltasFromMultipleRegions()`和`getApplicationDeltas()`方法获取近期变化的应用集合。具体的方式就是获取最近租约变化对别中的数据，然后拼装变化的应用集合，然后返回数据。

#### Eureka-Client发起下线

应用实例关闭时，Eureka-Client 向 Eureka-Server 发起下线应用实例。需要满足如下条件才可发起：

- 配置 `eureka.registration.enabled = true` ，应用实例开启注册开关。默认为 `false` 。
- 配置 `eureka.shouldUnregisterOnShutdown = true` ，应用实例开启关闭时下线开关。默认为 `true` 。

调用`DiscoveryClient#shutdown()`方法关闭实例

- 调用 `ApplicationInfoManager#setInstanceStatus(...)` 方法，设置应用实例为关闭( DOWN )。
- 调用 `#unregister()` 方法，在方法中`AbstractJerseyEurekaHttpClient#cancel(...)` 方法，`DELETE` 请求 Eureka-Server 的 `apps/${APP_NAME}/${INSTANCE_INFO_ID}` 接口，实现应用实例信息的下线。

##### Eureka-Server处理下线

`InstanceResource`，处理**单个**应用实例信息的请求操作的 Resource。线应用实例信息的请求，映射 `InstanceResource#cancelLease()` 方法，调用 `PeerAwareInstanceRegistryImpl#cancel(...)` 方法，下线应用实例。

```java
public boolean cancel(final String appName, final String id, final boolean isReplication) {
    if (super.cancel(appName, id, isReplication)) {
        replicateToPeers(Action.Cancel, appName, id, null, null, isReplication);
        return true;
    }
    return false;
}
```

- 调用父类 `AbstractInstanceRegistry#cancel(...)` 方法，下线应用实例信息。
- Eureka-Server集群复制下线操作。
- 减少 `numberOfRenewsPerMinThreshold` 、`expectedNumberOfRenewsPerMin`，自我保护机制相关。

##### 下线应用实例信息

调用 `AbstractInstanceRegistry#cancel(...)` 方法，下线应用实例信息，具体逻辑如下：

- 移除本地租约信息
- 添加到最近下线的调试队列( `recentCanceledQueue` )，用于 Eureka-Server 运维界面的显示，无实际业务逻辑使用。
- 移除应用实例覆盖状态映射。
- 调用 `Lease#cancel()` 方法，取消租约。

```java
public void cancel() {
    if (evictionTimestamp <= 0) {
        evictionTimestamp = System.currentTimeMillis();
    }
}
```

- 设置应用实例信息的操作类型为添加，并添加到最近租约变更记录队列( `recentlyChangedQueue` )。`recentlyChangedQueue` 用于注册信息的增量获取
- 设置响应缓存( ResponseCache )过期，`invalidateCache(appName, vip, svip)`这个方法比较重要。

### Eureka启动从其他节点获取注册信息

在`EurekaBootStrap#initEurekaServerContext()`在服务启动时会与别的Eureka节点进行注册信息同步，从别的Eureka节点同步注册信息。

```java
// Copy registry from neighboring eureka node
int registryCount = registry.syncUp();
```

```java
@Override
public int syncUp() {
    // Copy entire entry from neighboring DS node
    int count = 0;

    for (int i = 0; ((i < serverConfig.getRegistrySyncRetries()) && (count == 0)); i++) {
        if (i > 0) {
            try {
                Thread.sleep(serverConfig.getRegistrySyncRetryWaitMs());
            } catch (InterruptedException e) {
                logger.warn("Interrupted during registry transfer..");
                break;
            }
        }
        Applications apps = eurekaClient.getApplications();
        for (Application app : apps.getRegisteredApplications()) {
            for (InstanceInfo instance : app.getInstances()) {
                try {
                    if (isRegisterable(instance)) {
                        register(instance, instance.getLeaseInfo().getDurationInSecs(), true);
                        count++;
                    }
                } catch (Throwable t) {
                    logger.error("During DS init copy", t);
                }
            }
        }
    }
    return count;
}
```

Eureka在启动的时候会进行注册信息同步，一般只会同步一个节点，因为count肯定不是0了。整个逻辑如下：

- 根据配置判断是否可以进行同步(numberRegistrySyncRetries 5)并且之前没有进行过同步
- 如果i > 0 也就是执行过同步了，那么就等待一下在进行同步(registrySyncRetryWaitMs 30 * 1000 ) 30S
- 随后通过Eureka-client从相邻节点同步应用信息
- 然后遍历应用信息看是否需要注册，如果需要注册就通过`register`方法注册到本地缓存中

```java
public void register(InstanceInfo registrant, int leaseDuration, boolean isReplication) {
    read.lock();
    try {
        Map<String, Lease<InstanceInfo>> gMap = registry.get(registrant.getAppName());
        REGISTER.increment(isReplication);
        if (gMap == null) {
            final ConcurrentHashMap<String, Lease<InstanceInfo>> gNewMap = new ConcurrentHashMap<String, Lease<InstanceInfo>>();
            gMap = registry.putIfAbsent(registrant.getAppName(), gNewMap);
            if (gMap == null) {
                gMap = gNewMap;
            }
        }
        Lease<InstanceInfo> existingLease = gMap.get(registrant.getId());
        // Retain the last dirty timestamp without overwriting it, if there is already a lease
        if (existingLease != null && (existingLease.getHolder() != null)) {
            Long existingLastDirtyTimestamp = existingLease.getHolder().getLastDirtyTimestamp();
            Long registrationLastDirtyTimestamp = registrant.getLastDirtyTimestamp();
            logger.debug("Existing lease found (existing={}, provided={}", existingLastDirtyTimestamp, registrationLastDirtyTimestamp);

            // this is a > instead of a >= because if the timestamps are equal, we still take the remote transmitted
            // InstanceInfo instead of the server local copy.
            if (existingLastDirtyTimestamp > registrationLastDirtyTimestamp) {
                logger.warn("There is an existing lease and the existing lease's dirty timestamp {} is greater" +
                        " than the one that is being registered {}", existingLastDirtyTimestamp, registrationLastDirtyTimestamp);
                logger.warn("Using the existing instanceInfo instead of the new instanceInfo as the registrant");
                registrant = existingLease.getHolder();
            }
        } else {
            // The lease does not exist and hence it is a new registration
            synchronized (lock) {
                if (this.expectedNumberOfClientsSendingRenews > 0) {
                    // Since the client wants to register it, increase the number of clients sending renews
                    this.expectedNumberOfClientsSendingRenews = this.expectedNumberOfClientsSendingRenews + 1;
                  //这里更新了一下自我保护的阈值  
                  updateRenewsPerMinThreshold();
                }
            }
            logger.debug("No previous lease information found; it is new registration");
        }
      //生成租约信息
        Lease<InstanceInfo> lease = new Lease<InstanceInfo>(registrant, leaseDuration);
        if (existingLease != null) {
            lease.setServiceUpTimestamp(existingLease.getServiceUpTimestamp());
        }
      //把注册信息放到缓存中
        gMap.put(registrant.getId(), lease);
      //加入到最近注册的Queue中
        recentRegisteredQueue.add(new Pair<Long, String>(
                System.currentTimeMillis(),
                registrant.getAppName() + "(" + registrant.getId() + ")"));
        // This is where the initial state transfer of overridden status happens
        if (!InstanceStatus.UNKNOWN.equals(registrant.getOverriddenStatus())) {
            logger.debug("Found overridden status {} for instance {}. Checking to see if needs to be add to the "
                            + "overrides", registrant.getOverriddenStatus(), registrant.getId());
            if (!overriddenInstanceStatusMap.containsKey(registrant.getId())) {
                logger.info("Not found overridden id {} and hence adding it", registrant.getId());
                overriddenInstanceStatusMap.put(registrant.getId(), registrant.getOverriddenStatus());
            }
        }
        InstanceStatus overriddenStatusFromMap = overriddenInstanceStatusMap.get(registrant.getId());
        if (overriddenStatusFromMap != null) {
            logger.info("Storing overridden status {} from map", overriddenStatusFromMap);
            registrant.setOverriddenStatus(overriddenStatusFromMap);
        }

        // Set the status based on the overridden status rules
        InstanceStatus overriddenInstanceStatus = getOverriddenInstanceStatus(registrant, existingLease, isReplication);
        registrant.setStatusWithoutDirty(overriddenInstanceStatus);

        // If the lease is registered with UP status, set lease service up timestamp
        if (InstanceStatus.UP.equals(registrant.getStatus())) {
            lease.serviceUp();
        }
        registrant.setActionType(ActionType.ADDED);
      //放到最近变化的Queue中
        recentlyChangedQueue.add(new RecentlyChangedItem(lease));
      //更改了最近注册的时间戳
        registrant.setLastUpdatedTimestamp();
      //过期自己的readWriteCacheMap
        invalidateCache(registrant.getAppName(), registrant.getVIPAddress(), registrant.getSecureVipAddress());
        logger.info("Registered instance {}/{} with status {} (replication={})",
                registrant.getAppName(), registrant.getId(), registrant.getStatus(), isReplication);
    } finally {
        read.unlock();
    }
}
```

具体逻辑看代码注释吧，不写了

### Eureka启动注册信息扫描过期任务和自动保护机制

```java
registry.openForTraffic(applicationInfoManager, registryCount);
```

不得不说Eureka这方法命名真的厉害，这特么openForTraffic什么鬼？

```java
@Override
public void openForTraffic(ApplicationInfoManager applicationInfoManager, int count) {
    // Renewals happen every 30 seconds and for a minute it should be a factor of 2.
    this.expectedNumberOfClientsSendingRenews = count;
  //首先更新了一下自我保护的一个阈值
    updateRenewsPerMinThreshold();
    logger.info("Got {} instances from neighboring DS node", count);
    logger.info("Renew threshold is: {}", numberOfRenewsPerMinThreshold);
    this.startupTime = System.currentTimeMillis();
    if (count > 0) {
        this.peerInstancesTransferEmptyOnStartup = false;
    }
    DataCenterInfo.Name selfName = applicationInfoManager.getInfo().getDataCenterInfo().getName();
  //处理一下AWS相关的逻辑，不细看了
    boolean isAws = Name.Amazon == selfName;
    if (isAws && serverConfig.shouldPrimeAwsReplicaConnections()) {
        logger.info("Priming AWS connections for all replicas..");
        primeAwsReplicas(applicationInfoManager);
    }
    logger.info("Changing status to UP");
  //给自己的服务实例信息变更为UP，让别人可以看到
    applicationInfoManager.setInstanceStatus(InstanceStatus.UP);
  //最主要的方法，这里面启动了一个扫描过期注册信息的任务
    super.postInit();
}
```

```java
protected void postInit() {
    renewsLastMin.start();
    if (evictionTaskRef.get() != null) {
        evictionTaskRef.get().cancel();
    }
    evictionTaskRef.set(new EvictionTask());
    evictionTimer.schedule(evictionTaskRef.get(),
            serverConfig.getEvictionIntervalTimerInMs(),
            serverConfig.getEvictionIntervalTimerInMs());
}
```

这里就是`openForTraffic`这个方法的执行重点了，其实就是启动了一个`new EvictionTask()`任务，并且延时60S执行，每60S执行一次。

```java
@Override
public void run() {
    try {
      //计算了一个补偿时间
        long compensationTimeMs = getCompensationTimeMs();
        logger.info("Running the evict task with compensationTime {}ms", compensationTimeMs);
        evict(compensationTimeMs);
    } catch (Throwable e) {
        logger.error("Could not run the evict task", e);
    }
}

/**
 * compute a compensation time defined as the actual time this task was executed since the prev iteration,
 * vs the configured amount of time for execution. This is useful for cases where changes in time (due to
 * clock skew or gc for example) causes the actual eviction task to execute later than the desired time
 * according to the configured cycle.
 */
long getCompensationTimeMs() {
    long currNanos = getCurrentTimeNano();
    long lastNanos = lastExecutionNanosRef.getAndSet(currNanos);
    if (lastNanos == 0l) {
        return 0l;
    }

    long elapsedMs = TimeUnit.NANOSECONDS.toMillis(currNanos - lastNanos);
    long compensationTime = elapsedMs - serverConfig.getEvictionIntervalTimerInMs();
    return compensationTime <= 0l ? 0l : compensationTime;
}
```

在`EvictionTask()`任务中，首先计算了一个补偿时间，这个补偿时间的意思是服务由于Linux时钟偏移了或者GC停顿了导致程序计算过期服务时间的间隔变短了，比如30S执行一次，这时候GC停顿了15S，然后网络拥堵了15S，可能Eureka本身都还没有进行心跳检测，所以这时候把一些服务给判定为下线了是不合理的，这里就是计算一下补偿时间。把这部分时间考虑进去。然后带着补偿时间的`run()`方法执行了下面的方法。

```java
public void evict(long additionalLeaseMs) {
    logger.debug("Running the evict task");
		
  //判断是否是自我保护模式，如果是的话就不过期了，自我保护的详细代码写在下面了
    if (!isLeaseExpirationEnabled()) {
        logger.debug("DS: lease expiration is currently disabled.");
        return;
    }
		
  //这里就是说先把所有过期的注册信息都给取出来，
    // We collect first all expired items, to evict them in random order. For large eviction sets,
    // if we do not that, we might wipe out whole apps before self preservation kicks in. By randomizing it,
    // the impact should be evenly distributed across all applications.
    List<Lease<InstanceInfo>> expiredLeases = new ArrayList<>();
    for (Entry<String, Map<String, Lease<InstanceInfo>>> groupEntry : registry.entrySet()) {
        Map<String, Lease<InstanceInfo>> leaseMap = groupEntry.getValue();
        if (leaseMap != null) {
            for (Entry<String, Lease<InstanceInfo>> leaseEntry : leaseMap.entrySet()) {
                Lease<InstanceInfo> lease = leaseEntry.getValue();
                if (lease.isExpired(additionalLeaseMs) && lease.getHolder() != null) {
                    expiredLeases.add(lease);
                }
            }
        }
    }
		
    // To compensate for GC pauses or drifting local time, we need to use current registry size as a base for
    // triggering self-preservation. Without that we would wipe out full registry.
  //获取本地所有注册的实例数量
    int registrySize = (int) getLocalRegistrySize();
  //然后 * 0.85的阈值算出来一个注册阈值，这个值是用来计算最大过期数量的
    int registrySizeThreshold = (int) (registrySize * serverConfig.getRenewalPercentThreshold());
  //用注册服务数量 - 最大可以摘除的数量就是本次可以删除的数量
    int evictionLimit = registrySize - registrySizeThreshold;
		
  //与过期数量比较选个最小值
    int toEvict = Math.min(expiredLeases.size(), evictionLimit);
    if (toEvict > 0) {
        logger.info("Evicting {} items (expired={}, evictionLimit={})", toEvict, expiredLeases.size(), evictionLimit);
			//这里随机选取进行摘除，为了让实例摘除均匀一些
        Random random = new Random(System.currentTimeMillis());
        for (int i = 0; i < toEvict; i++) {
            // Pick a random item (Knuth shuffle algorithm)
            int next = i + random.nextInt(expiredLeases.size() - i);
            Collections.swap(expiredLeases, i, next);
            Lease<InstanceInfo> lease = expiredLeases.get(i);

            String appName = lease.getHolder().getAppName();
            String id = lease.getHolder().getId();
          //这里是监控打点
            EXPIRED.increment();
            logger.warn("DS: Registry: expired lease for {}/{}", appName, id);
          //摘除服务实例的具体方法
            internalCancel(appName, id, false);
        }
    }
}
```

```java
protected boolean internalCancel(String appName, String id, boolean isReplication) {
    read.lock();
    try {
      //这里是个监控打点
        CANCEL.increment(isReplication);
      //从本地缓存中摘除服务实例
        Map<String, Lease<InstanceInfo>> gMap = registry.get(appName);
        Lease<InstanceInfo> leaseToCancel = null;
        if (gMap != null) {
            leaseToCancel = gMap.remove(id);
        }
      //将服务实例信息增加到recentCanceledQueue 中
        recentCanceledQueue.add(new Pair<Long, String>(System.currentTimeMillis(), appName + "(" + id + ")"));
      
      //这里我没弄明白是什么意思
        InstanceStatus instanceStatus = overriddenInstanceStatusMap.remove(id);
        if (instanceStatus != null) {
            logger.debug("Removed instance id {} from the overridden map which has value {}", id, instanceStatus.name());
        }
        if (leaseToCancel == null) {
            CANCEL_NOT_FOUND.increment(isReplication);
            logger.warn("DS: Registry: cancel failed because Lease is not registered for: {}/{}", appName, id);
            return false;
        } else {
            leaseToCancel.cancel();
            InstanceInfo instanceInfo = leaseToCancel.getHolder();
            String vip = null;
            String svip = null;
            if (instanceInfo != null) {
              //这里设置一下服务的状态
                instanceInfo.setActionType(ActionType.DELETED);
              //增加到最近改变队列
                recentlyChangedQueue.add(new RecentlyChangedItem(leaseToCancel));
                instanceInfo.setLastUpdatedTimestamp();
                vip = instanceInfo.getVIPAddress();
                svip = instanceInfo.getSecureVipAddress();
            }
          
          //刷新readWriteCache
            invalidateCache(appName, vip, svip);
            logger.info("Cancelled instance {}/{} (replication={})", appName, id, isReplication);
        }
    } finally {
        read.unlock();
    }

    synchronized (lock) {
        if (this.expectedNumberOfClientsSendingRenews > 0) {
            // Since the client wants to cancel it, reduce the number of clients to send renews.
          //这里减少一下注册服务的数量用于重新计算自我保护机制的阈值
            this.expectedNumberOfClientsSendingRenews = this.expectedNumberOfClientsSendingRenews - 1;
            updateRenewsPerMinThreshold();
        }
    }

    return true;
}
```

具体的逻辑在看上面的代码的注释吧，我发现直接在代码里写注释挺好的，其实比写大段的文字更加直观。以后我就都这么写了，而且还省事。捏哈哈哈

#### Eureka自我保护机制

自我保护机制定义如下：

> 默认情况下，如果Eureka Server在一定时间内没有接收到某个微服务实例的心跳，Eureka Server将会注销该实例（默认90秒）。但是当网络分区故障发生时，微服务与Eureka Server之间无法正常通信，以上行为可能变得非常危险了——因为微服务本身其实是健康的，此时本不应该注销这个微服务。
>
> Eureka通过“自我保护模式”来解决这个问题——当Eureka Server节点在短时间内丢失过多客户端时（可能发生了网络分区故障），那么这个节点就会进入自我保护模式。一旦进入该模式，Eureka Server就会保护服务注册表中的信息，不再删除服务注册表中的数据（也就是不会注销任何微服务）。当网络故障恢复后，该Eureka Server节点会自动退出自我保护模式。
>
> 综上，自我保护模式是一种应对网络异常的安全保护措施。它的架构哲学是宁可同时保留所有微服务（健康的微服务和不健康的微服务都会保留），也不盲目注销任何健康的微服务。使用自我保护模式，可以让Eureka集群更加的健壮、稳定。
>
> 在Spring Cloud中，可以使用`eureka.server.enable-self-preservation = false` 禁用自我保护模式。

```java
@Override
public boolean isLeaseExpirationEnabled() {
    if (!isSelfPreservationModeEnabled()) {
        // The self preservation mode is disabled, hence allowing the instances to expire.
        return true;
    }
    return numberOfRenewsPerMinThreshold > 0 && getNumOfRenewsInLastMin() > numberOfRenewsPerMinThreshold;
}
```

```java
protected void updateRenewsPerMinThreshold() {
    this.numberOfRenewsPerMinThreshold = (int) (this.expectedNumberOfClientsSendingRenews
            * (60.0 / serverConfig.getExpectedClientRenewalIntervalSeconds())
            * serverConfig.getRenewalPercentThreshold());
}
```

```java
@Override
public long getNumOfRenewsInLastMin() {
    return renewsLastMin.getCount();
}
```

上面是有关自我保护的两段代码，首先`numberOfRenewsPerMinThreshold`就是每分钟期望的心跳数量，翻译一下计算逻辑就是Eureka中注册的实例数量 * 每分钟2次心跳 * 0.85的阈值就是这个数。

是否开启自动保护机制的逻辑就是用上一分钟接收到的心跳数量`getNumOfRenewsInLastMin()`与期望的每分钟接收到的心跳数量做比较`numberOfRenewsPerMinThreshold`。如果没收到，那么就会认为我Eureka自己本身除了问题，那么我就不下线实例了。

### Eureka批任务处理

Eureka-Server 集群通过任务批处理同步应用实例注册实例。

- **不同于**一般情况下，任务提交了**立即**同步或异步执行，任务的执行拆分了**三层队列**：

  - 第一层，接收队列( `acceptorQueue` )，重新处理队列( `reprocessQueue` )。

    - 分发器在收到任务执行请求后，提交到接收队列，**任务实际未执行**。
    - 执行器的工作线程处理任务失败，将符合条件的失败任务提交到重新执行队列。

    - 第二层，待执行队列( `processingOrder` )
      - 粉线：接收线程( Runner )将重新执行队列，接收队列提交到待执行队列。
    - 第三层，工作队列( `workQueue` )
      - 粉线：接收线程( Runner )将待执行队列的任务根据参数( `maxBatchingSize` )将任务合并成**批量任务**，调度( 提交 )到工作队列。
      - 黄线：执行器的工作线程**池**，一个工作线程可以拉取一个**批量任务**进行执行。

- **三层队列的好处**：

  - 接收队列，避免处理任务的阻塞等待。
  - 接收线程( Runner )合并任务，将相同任务编号( **是的，任务是带有编号的** )的任务合并，只执行一次。
  - Eureka-Server 为集群同步提供批量操作**多个**应用实例的**接口**，一个**批量任务**可以一次调度接口完成，避免多次调用的开销。当然，这样做的前提是合并任务，这也导致 Eureka-Server 集群之间对应用实例的注册和下线带来更大的延迟。**毕竟，Eureka 是在 CAP 之间，选择了 AP**。

  这里的细节还是挺多的，这里有时间要好好看看，因为批量任务处理这种在实际业务场景中使用的地方很多，各种设计思想应该借鉴一下。

  ### Eureka-Server集群同步

- Eureka-Server 集群不区分**主从节点**或者 **Primary & Secondary 节点**，所有节点**相同角色( 也就是没有角色 )，完全对等**。

- Eureka-Client 可以向**任意** Eureka-Client 发起任意**读写**操作，Eureka-Server 将操作复制到另外的 Eureka-Server 以达到**最终一致性**。注意，Eureka-Server 是选择了 AP 的组件。

在`EurekaBootStrap#initEurekaServerContext()`方法中,构建了`PeerEurekaNodes`这里面就是同步Eureka-Server集群信息。

```java
PeerEurekaNodes peerEurekaNodes = getPeerEurekaNodes(
        registry,
        eurekaServerConfig,
        eurekaClient.getEurekaClientConfig(),
        serverCodecs,
        applicationInfoManager
);
```

在`PeerEurekaNodes#start()`方法中进行了集群同步。

```java
public void start() {
  //启动一个单线程的线程池做任务执行线程
    taskExecutor = Executors.newSingleThreadScheduledExecutor(
            new ThreadFactory() {
                @Override
                public Thread newThread(Runnable r) {
                    Thread thread = new Thread(r, "Eureka-PeerNodesUpdater");
                    thread.setDaemon(true);
                    return thread;
                }
            }
    );
    try {
      //获取所有集群Url并且依次通过这些URL请求同步集群信息
        updatePeerEurekaNodes(resolvePeerUrls());
      //声明一个集群信息同步任务
        Runnable peersUpdateTask = new Runnable() {
            @Override
            public void run() {
                try {
                    updatePeerEurekaNodes(resolvePeerUrls());
                } catch (Throwable e) {
                    logger.error("Cannot update the replica Nodes", e);
                }

            }
        };
      //启动定时任务，每10分钟同步一次(10 * 60 * 1000) Eureka集群
        taskExecutor.scheduleWithFixedDelay(
                peersUpdateTask,
                serverConfig.getPeerEurekaNodesUpdateIntervalMs(),
                serverConfig.getPeerEurekaNodesUpdateIntervalMs(),
                TimeUnit.MILLISECONDS
        );
    } catch (Exception e) {
        throw new IllegalStateException(e);
    }
    for (PeerEurekaNode node : peerEurekaNodes) {
        logger.info("Replica node URL:  {}", node.getServiceUrl());
    }
}
```

下面开始对上面的代码中的一些方法进行详细的解析,在`resolvePeerUrls()`方法中获取了所有的Eureka机器Url

```java
protected List<String> resolvePeerUrls() {
  //从ApplicationInfoManager中获取自己的实例信息
    InstanceInfo myInfo = applicationInfoManager.getInfo();
    String zone = InstanceInfo.getZone(clientConfig.getAvailabilityZones(clientConfig.getRegion()), myInfo);
  //这里就获取了所有的Eureka集群信息，其实就是从配置文件中读取了所有的serverUrl，用逗号分隔的  
  List<String> replicaUrls = EndpointUtils
            .getDiscoveryServiceUrls(clientConfig, zone, new EndpointUtils.InstanceInfoBasedUrlRandomizer(myInfo));

    int idx = 0;
  //这里把自己从同步信息里去掉，不用同步自己的信息
    while (idx < replicaUrls.size()) {
        if (isThisMyUrl(replicaUrls.get(idx))) {
            replicaUrls.remove(idx);
        } else {
            idx++;
        }
    }
    return replicaUrls;
}
```

在`updatePeerEurekaNodes`方法中创建了集群实例`PeerEurekaNode`，并且做了一些`PeerEurekaNode`的初始化工作，创建了一下批量任务执行器等操作

```java
protected void updatePeerEurekaNodes(List<String> newPeerUrls) {
    if (newPeerUrls.isEmpty()) {
        logger.warn("The replica size seems to be empty. Check the route 53 DNS Registry");
        return;
    }
		//下面这一些逻辑没弄太懂，有一些URL不会进行同步
    Set<String> toShutdown = new HashSet<>(peerEurekaNodeUrls);
    toShutdown.removeAll(newPeerUrls);
    Set<String> toAdd = new HashSet<>(newPeerUrls);
    toAdd.removeAll(peerEurekaNodeUrls);

    if (toShutdown.isEmpty() && toAdd.isEmpty()) { // No change
        return;
    }

    // Remove peers no long available
    List<PeerEurekaNode> newNodeList = new ArrayList<>(peerEurekaNodes);

    if (!toShutdown.isEmpty()) {
        logger.info("Removing no longer available peer nodes {}", toShutdown);
        int i = 0;
        while (i < newNodeList.size()) {
            PeerEurekaNode eurekaNode = newNodeList.get(i);
            if (toShutdown.contains(eurekaNode.getServiceUrl())) {
                newNodeList.remove(i);
                eurekaNode.shutDown();
            } else {
                i++;
            }
        }
    }

    // Add new peers
    if (!toAdd.isEmpty()) {
        logger.info("Adding new peer nodes {}", toAdd);
        for (String peerUrl : toAdd) {
          //这里创建了一个PeerEurekaNode节点
            newNodeList.add(createPeerEurekaNode(peerUrl));
        }
    }

    this.peerEurekaNodes = newNodeList;
    this.peerEurekaNodeUrls = new HashSet<>(newPeerUrls);
}
```

在`createPeerEurekaNode()`进行了Eureka集群节点信息同步，并且进行了相关初始化。

```java
protected PeerEurekaNode createPeerEurekaNode(String peerEurekaNodeUrl) {
  //这里明显初始化了一个Http客户端，用于后面与其他Client进行节点信息同步的
    HttpReplicationClient replicationClient = JerseyReplicationClient.createReplicationClient(serverConfig, serverCodecs, peerEurekaNodeUrl);
    String targetHost = hostFromUrl(peerEurekaNodeUrl);
    if (targetHost == null) {
        targetHost = "host";
    }
  //创建了一个PeerEurekaNode节点，并完成初始化
    return new PeerEurekaNode(registry, targetHost, peerEurekaNodeUrl, replicationClient, serverConfig);
}
```

```java
PeerEurekaNode(PeerAwareInstanceRegistry registry, String targetHost, String serviceUrl,
                                 HttpReplicationClient replicationClient, EurekaServerConfig config,
                                 int batchSize, long maxBatchingDelayMs,
                                 long retrySleepTimeMs, long serverUnavailableSleepTimeMs) {
    this.registry = registry;
    this.targetHost = targetHost;
    this.replicationClient = replicationClient;

    this.serviceUrl = serviceUrl;
    this.config = config;
    this.maxProcessingDelayMs = config.getMaxTimeForReplication();
		
  //下面创建了跟批量任务处理相关的东西
    String batcherName = getBatcherName();
    ReplicationTaskProcessor taskProcessor = new ReplicationTaskProcessor(targetHost, replicationClient);
  //批量任务分发器 
  this.batchingDispatcher = TaskDispatchers.createBatchingTaskDispatcher(
            batcherName,
            config.getMaxElementsInPeerReplicationPool(),
            batchSize,
            config.getMaxThreadsForPeerReplication(),
            maxBatchingDelayMs,
            serverUnavailableSleepTimeMs,
            retrySleepTimeMs,
            taskProcessor
    );
  //单任务分发器
    this.nonBatchingDispatcher = TaskDispatchers.createNonBatchingTaskDispatcher(
            targetHost,
            config.getMaxElementsInStatusReplicationPool(),
            config.getMaxThreadsForStatusReplication(),
            maxBatchingDelayMs,
            serverUnavailableSleepTimeMs,
            retrySleepTimeMs,
            taskProcessor
    );
}
```

### Eureka同步注册实例变更信息

- Eureka-Server 接收到 Eureka-Client 的 Register、Heartbeat、Cancel、StatusUpdate、DeleteStatusOverride 操作，固定间隔( 默认值 ：500 毫秒，可配 )向 Eureka-Server 集群内其他节点同步( **准实时，非实时** )。

集群同步注册信息的方式是`PeerAwareInstanceRegistryImpl#replicateToPeers()`方法，这个方法的调用分别在

- `PeerAwareInstanceRegistryImpl#cancel()`下线
- `PeerAwareInstanceRegistryImpl#register()`注册
- `PeerAwareInstanceRegistryImpl#renew()`心跳
- `PeerAwareInstanceRegistryImpl#statusUpdate()`状态更新
- `PeerAwareInstanceRegistryImpl#deleteStatusOverride()`这我都不知道是干嘛的

```java
private void replicateToPeers(Action action, String appName, String id,
                              InstanceInfo info /* optional */,
                              InstanceStatus newStatus /* optional */, boolean isReplication) {
    Stopwatch tracer = action.getTimer().start();
    try {
      //增加一个统计
        if (isReplication) {
            numberOfReplicationsLastMin.increment();
        }
      //看是否是同步，如果是同步就不给自己同步了
        // If it is a replication already, do not replicate again as this will create a poison replication
        if (peerEurekaNodes == Collections.EMPTY_LIST || isReplication) {
            return;
        }
			
     //遍历每个同步节点PeerEurekaNode，向每个节点同步变化信息
        for (final PeerEurekaNode node : peerEurekaNodes.getPeerEurekaNodes()) {
            // If the url represents this host, do not replicate to yourself.
            if (peerEurekaNodes.isThisMyUrl(node.getServiceUrl())) {
                continue;
            }
          //执行同步任务，详情见下面的源码
            replicateInstanceActionsToPeers(action, appName, id, info, newStatus, node);
        }
    } finally {
        tracer.stop();
    }
}
```

```java
private void replicateInstanceActionsToPeers(Action action, String appName,
                                             String id, InstanceInfo info, InstanceStatus newStatus,
                                             PeerEurekaNode node) {
    try {
        InstanceInfo infoFromRegistry;
        CurrentRequestVersion.set(Version.V2);
      //根据不同的action执行不同的逻辑，具体都干嘛了呢，其实就是创建了一个相对应的批量任务扔到队列里去执行
        switch (action) {
            case Cancel:
                node.cancel(appName, id);
                break;
            case Heartbeat:
                InstanceStatus overriddenStatus = overriddenInstanceStatusMap.get(id);
                infoFromRegistry = getInstanceByAppAndId(appName, id, false);
                node.heartbeat(appName, id, infoFromRegistry, overriddenStatus, false);
                break;
            case Register:
                node.register(info);
                break;
            case StatusUpdate:
                infoFromRegistry = getInstanceByAppAndId(appName, id, false);
                node.statusUpdate(appName, id, newStatus, infoFromRegistry);
                break;
            case DeleteStatusOverride:
                infoFromRegistry = getInstanceByAppAndId(appName, id, false);
                node.deleteStatusOverride(appName, id, infoFromRegistry);
                break;
        }
    } catch (Throwable t) {
        logger.error("Cannot replicate information to {} for action {}", node.getServiceUrl(), action.name(), t);
    } finally {
        CurrentRequestVersion.remove();
    }
}
```

随便找一个以Cancel为例

```java
public void cancel(final String appName, final String id) throws Exception {
    long expiryTime = System.currentTimeMillis() + maxProcessingDelayMs;
    batchingDispatcher.process(
            taskId("cancel", appName, id),
      //注意这里实现了InstanceReplicationTask的execute方法
            new InstanceReplicationTask(targetHost, Action.Cancel, appName, id) {
                @Override
                public EurekaHttpResponse<Void> execute() {
                  //执行具体操作
                    return replicationClient.cancel(appName, id);
                }

                @Override
                public void handleFailure(int statusCode, Object responseEntity) throws Throwable {
                    super.handleFailure(statusCode, responseEntity);
                    if (statusCode == 404) {
                        logger.warn("{}: missing entry.", getTaskName());
                    }
                }
            },
            expiryTime
    );
}
```

所有的任务都实现了`InstanceReplicationTask#execute`方法，里面写了具体的操作，比如Cancel就是调用Eureka-Client执行了cancel逻辑。`batchingDispatcher.process`最终就会把任务扔到acceptorQueue任务队列里去

```java
void process(ID id, T task, long expiryTime) {
    acceptorQueue.add(new TaskHolder<ID, T>(id, task, expiryTime));
    acceptedTasks++;
}
```

批量任务的处理逻辑在`ReplicationTaskProcessor#process()`方法中

```java
@Override
public ProcessingResult process(List<ReplicationTask> tasks) {
    ReplicationList list = createReplicationListOf(tasks);
    try {
      //其实就是调用了一个批量提交任务的app
        EurekaHttpResponse<ReplicationListResponse> response = replicationClient.submitBatchUpdates(list);
        int statusCode = response.getStatusCode();
        if (!isSuccess(statusCode)) {
            if (statusCode == 503) {
                logger.warn("Server busy (503) HTTP status code received from the peer {}; rescheduling tasks after delay", peerId);
                return ProcessingResult.Congestion;
            } else {
                // Unexpected error returned from the server. This should ideally never happen.
                logger.error("Batch update failure with HTTP status code {}; discarding {} replication tasks", statusCode, tasks.size());
                return ProcessingResult.PermanentError;
            }
        } else {
          //批量处理批量请求
            handleBatchResponse(tasks, response.getEntity().getResponseList());
        }
    } catch (Throwable e) {
      ...省略一些异常代码
    }
    return ProcessingResult.Success;
}
```

### 批量处理同步任务

Eureka-Server处理批量同步任务的逻辑在`PeerReplicationResource#batchReplication()`方法中

```java
@Path("batch")
@POST
public Response batchReplication(ReplicationList replicationList) {
    try {
        ReplicationListResponse batchResponse = new ReplicationListResponse();
        for (ReplicationInstance instanceInfo : replicationList.getReplicationList()) {
            try {
              //逐个同步任务依次处理并将结果放到ReplicationListResponse中
                batchResponse.addResponse(dispatch(instanceInfo));
            } catch (Exception e) {
                batchResponse.addResponse(new ReplicationInstanceResponse(Status.INTERNAL_SERVER_ERROR.getStatusCode(), null));
                logger.error("{} request processing failed for batch item {}/{}",
                        instanceInfo.getAction(), instanceInfo.getAppName(), instanceInfo.getId(), e);
            }
        }
        return Response.ok(batchResponse).build();
    } catch (Throwable e) {
        logger.error("Cannot execute batch Request", e);
        return Response.status(Status.INTERNAL_SERVER_ERROR).build();
    }
}

private ReplicationInstanceResponse dispatch(ReplicationInstance instanceInfo) {
    ApplicationResource applicationResource = createApplicationResource(instanceInfo);
    InstanceResource resource = createInstanceResource(instanceInfo, applicationResource);

    String lastDirtyTimestamp = toString(instanceInfo.getLastDirtyTimestamp());
    String overriddenStatus = toString(instanceInfo.getOverriddenStatus());
    String instanceStatus = toString(instanceInfo.getStatus());

    Builder singleResponseBuilder = new Builder();
  //依次处理每种任务类型，具体的处理逻辑就是我们之前看到的各种方法
    switch (instanceInfo.getAction()) {
        case Register:
            singleResponseBuilder = handleRegister(instanceInfo, applicationResource);
            break;
        case Heartbeat:
            singleResponseBuilder = handleHeartbeat(serverConfig, resource, lastDirtyTimestamp, overriddenStatus, instanceStatus);
            break;
        case Cancel:
            singleResponseBuilder = handleCancel(resource);
            break;
        case StatusUpdate:
            singleResponseBuilder = handleStatusUpdate(instanceInfo, resource);
            break;
        case DeleteStatusOverride:
            singleResponseBuilder = handleDeleteStatusOverride(instanceInfo, resource);
            break;
    }
    return singleResponseBuilder.build();
}
```

`dispatch`中负责处理不同类型的任务，以Register任务为例，最终底层调用了`ApplicationResource#addInstance`方法。

当批量任务都处理完之后，会把处理结果返回给发送方，发送方收到结果后会在`ReplicationTaskProcessor#process()`中进行处理。具体的处理逻辑在`ReplicationTaskProcessor#handleBatchResponse`方法中

```java
private void handleBatchResponse(List<ReplicationTask> tasks, List<ReplicationInstanceResponse> responseList) {
    if (tasks.size() != responseList.size()) {
        // This should ideally never happen unless there is a bug in the software.
        logger.error("Batch response size different from submitted task list ({} != {}); skipping response analysis", responseList.size(), tasks.size());
        return;
    }
    for (int i = 0; i < tasks.size(); i++) {
        handleBatchResponse(tasks.get(i), responseList.get(i));
    }
}

private void handleBatchResponse(ReplicationTask task, ReplicationInstanceResponse response) {
    int statusCode = response.getStatusCode();
  //处理成功情况  
  if (isSuccess(statusCode)) {
        task.handleSuccess();
        return;
    }
  //处理失败情况
    try {
        task.handleFailure(response.getStatusCode(), response.getResponseEntity());
    } catch (Throwable e) {
        logger.error("Replication task {} error handler failure", task.getTaskName(), e);
    }
}
```

下面引入一段我觉得比较好的对于Eureka集群同步的分析

> 本文的重要头戏来啦！Last But Very Importment ！！！
>
> Eureka-Server 是允许**同一时刻**允许在任意节点被 Eureka-Client 发起**写入**相关的操作，网络是不可靠的资源，Eureka-Client 可能向一个 Eureka-Server 注册成功，但是网络波动，导致 Eureka-Client 误以为失败，此时恰好 Eureka-Client 变更了应用实例的状态，重试向另一个 Eureka-Server 注册，那么两个 Eureka-Server 对该应用实例的状态产生冲突。
>
> 再例如…… 我们不要继续举例子，网络波动真的很复杂。我们来看看 Eureka 是怎么处理的。
>
> 应用实例( InstanceInfo ) 的 `lastDirtyTimestamp` 属性，使用**时间戳**，表示应用实例的**版本号**，当请求方( 不仅仅是 Eureka-Client ，也可能是同步注册操作的 Eureka-Server ) 向 Eureka-Server 发起注册时，若 Eureka-Server 已存在拥有更大 `lastDirtyTimestamp` 该实例( **相同应用并且相同应用实例编号被认为是相同实例** )，则请求方注册的应用实例( InstanceInfo ) 无法覆盖注册此 Eureka-Server 的该实例( 见 `AbstractInstanceRegistry#register(...)` 方法 )。例如我们上面举的例子，第一个 Eureka-Server 向 第二个 Eureka-Server 同步注册应用实例时，不会注册覆盖，反倒是第二个 Eureka-Server 同步注册应用到第一个 Eureka-Server ，注册覆盖成功，因为 `lastDirtyTimestamp` ( 应用实例状态变更时，可以设置 `lastDirtyTimestamp` 为当前时间，见 `ApplicationInfoManager#setInstanceStatus(status)` 方法 )。
>
> 但是光靠**注册**请求判断 `lastDirtyTimestamp` 显然是不够的，因为网络异常情况下时，同步操作任务多次执行失败到达过期时间后，此时在 Eureka-Server 集群同步起到最终一致性**最最最**关键性出现了：Heartbeat 。因为 Heartbeat 会周期性的执行，通过它一方面可以判断 Eureka-Server 是否存在心跳对应的应用实例，另外一方面可以比较应用实例的 `lastDirtyTimestamp` 。当满足下面任意条件，Eureka-Server 返回 404 状态码：
>
> - 1）Eureka-Server 应用实例不存在，点击 [链接](https://github.com/YunaiV/eureka/blob/69993ad1e80d45c43ac8585921eca4efb88b09b9/eureka-core/src/main/java/com/netflix/eureka/registry/AbstractInstanceRegistry.java#L438) 查看触发条件代码位置。
> - 2）Eureka-Server 应用实例状态为 `UNKNOWN`，点击 [链接](https://github.com/YunaiV/eureka/blob/69993ad1e80d45c43ac8585921eca4efb88b09b9/eureka-core/src/main/java/com/netflix/eureka/registry/AbstractInstanceRegistry.java#L450) 查看触发条件代码位置。为什么会是 `UNKNOWN` ，在 [《Eureka 源码解析 —— 应用实例注册发现（八）之覆盖状态》「 4.3 续租场景」](http://www.iocoder.cn/Eureka/instance-registry-override-status/?self) 有详细解析。
> - **3）**请求的 `lastDirtyTimestamp` 更大，点击 [链接](https://github.com/YunaiV/eureka/blob/69993ad1e80d45c43ac8585921eca4efb88b09b9/eureka-core/src/main/java/com/netflix/eureka/resources/InstanceResource.java#L306) 查看触发条件代码位置。
>
> 请求方接收到 404 状态码返回后，**认为 Eureka-Server 应用实例实际是不存在的**，重新发起应用实例的注册。以本文的 Heartbeat 为例子，代码如下：
>
> ```
> // PeerEurekaNode#heartbeat(...)
>   1: @Override
>   2: public void handleFailure(int statusCode, Object responseEntity) throws Throwable {
>   3:     super.handleFailure(statusCode, responseEntity);
>   4:     if (statusCode == 404) {
>   5:         logger.warn("{}: missing entry.", getTaskName());
>   6:         if (info != null) {
>   7:             logger.warn("{}: cannot find instance id {} and hence replicating the instance with status {}",
>   8:                     getTaskName(), info.getId(), info.getStatus());
>   9:             register(info);
>  10:         }
>  11:     } else if (config.shouldSyncWhenTimestampDiffers()) {
>  12:         InstanceInfo peerInstanceInfo = (InstanceInfo) responseEntity;
>  13:         if (peerInstanceInfo != null) {
>  14:             syncInstancesIfTimestampDiffers(appName, id, info, peerInstanceInfo);
>  15:         }
>  16:     }
>  17: }
> ```
>
> - 第 4 至 10 行 ：接收到 404 状态码，调用 `#register(...)` 方法，向该被心跳同步操作失败的 Eureka-Server 发起注册**本地的应用实例**的请求。
>   - 上述 **3）** ，会使用请求参数 `overriddenStatus` 存储到 Eureka-Server 的应用实例覆盖状态集合( `AbstractInstanceRegistry.overriddenInstanceStatusMap` )，点击 [链接](https://github.com/YunaiV/eureka/blob/69993ad1e80d45c43ac8585921eca4efb88b09b9/eureka-core/src/main/java/com/netflix/eureka/resources/InstanceResource.java#L123) 查看触发条件代码位置。
> - 第 11 至 16 行 ：恰好是 **3）** 反过来的情况，本地的应用实例的 `lastDirtyTimestamp` 小于 Eureka-Server 该应用实例的，此时 Eureka-Server 返回 409 状态码，点击 [链接](https://github.com/YunaiV/eureka/blob/69993ad1e80d45c43ac8585921eca4efb88b09b9/eureka-core/src/main/java/com/netflix/eureka/resources/InstanceResource.java#L314) 查看触发条件代码位置。调用 `#syncInstancesIfTimestampDiffers()` 方法，覆盖注册本地应用实例，点击 [链接](https://github.com/YunaiV/eureka/blob/7f868f9ca715a8862c0c10cac04e238bbf371db0/eureka-core/src/main/java/com/netflix/eureka/cluster/PeerEurekaNode.java#L387) 查看方法。
>
> OK，撒花！记住：Eureka 通过 Heartbeat 实现 Eureka-Server 集群同步的最终一致性。
