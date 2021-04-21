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

