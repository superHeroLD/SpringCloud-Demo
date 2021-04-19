# Eureka

\---

## Eureka项目结构

**eureka-server**：eurka-server的本质也是一个web应用。server也是依赖client，因为server本身也是一个client，在eurka集群模式时，eurka server也要扮演eurka client的角色，往其他的eurka server上去注册。eurka-server可以打成一个war包，然后扔到一个web容器中就可以使用，比如tomcat，Jetty等。

**eureka-core**：eurka代码核心，接收别人的服务注册请求，提供服务发现的功能，保持心跳（续约请求），摘除故障服务实例。eurka server依赖eurka core的功能对外暴露接口，提供注册中心功能。

**eureka-client**：eurka-client负责向eurka-server注册、获取服务列表，发送心跳等。

**eureka-resources**:就是提供了一些前端相关的页面js，css等文件。eurka的管理端应该就是在这里。其中status.jsp就是用来展示注册服务的信息的。

\## Eureka依赖

**Jersey**：Eurka用Jersey实现了Http通信，对外提供了一些restful接口来通信。

\---

\## Eureka源码

\### Eureka-server web.xml结构

从eurka-server的web.xml开始入手，首先是一个Listener **EurekaBootStrap**：负责eurka-server的初始化

\#### 四个filter

\+ StatusFilter：

\+ ServerRequestAuthFilter：

\+ RateLimitingFilter：限流

\+ GzipEncodingEnforcingFilter：报文压缩

通过<filter-mapping>可知StatusFilter和ServerRequestAuthFilter是对所有请求都开放的。RateLimitingFilter，默认是不开启的，如果需要打开eurka-server内置的限流功能，需要自己吧RateLimitingFilter的<filter-mapping>注释打开。GzipEncodingEnforcingFilter拦截/v2/apps相关的请求。Jersery的核心filter是默认拦截所有请求的。

## Eureka源码解析

### EurekaBootStrap源码解析

EurekaBootStrap的主要功能就是负责启动、初始化、并配置Eurka。在EurekaBootStrap中监听器执行初始化的方法，是contextInitialized()方法，这个方法就是整个eureka-server启动初始化的一个入口。initEurekaServerContext()方法负责初始化eureka-server的上下文。

#### 初始化Eureka相关配置

initEurekaServerContext()方法中，EurekaServerConfig eurekaServerConfig = new DefaultEurekaServerConfig()这一行代码会进行初始化eureka-server的相关配置。initEurekaEnvironment()方法负责初始化eureka-server的环境。在里面会调用ConfigurationManager.getConfigInstance()方法，这个方法的作用就是在初始化ConfigurationManager的实例，ConfigurationManager从字面上就能看出来是一个配置管理器，负责将配置文件中的配置加载进来供后面的Eurka初始化使用（后面会说到，没有配置的话会自动使用默认配置）。

\> 这里注意一下ConfigurationManager初始化使用了Double check形式的单例模式（TODO 后面把代码贴上来），一般我看开源项目中，使用内部类单例的比较少，大部分都使用了DoubleCheck形式的单例模式，DoubleCheck的单例模式需要重点注意一点就是使用volatile关键字修饰单例对象，不然在多线程的情况下，有可能初始化多次。

![14097005894197ee0f7e2b3a9fed7730.png](evernotecid://62E7E6AC-1793-4D73-AE1C-84E43655EB8F/appyinxiangcom/18219242/ENResource/p870)

\>

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

