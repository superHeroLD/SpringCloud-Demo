# Eureka
---
## Eureka项目工程
**eureka-server**：eurka-server的本质也是一个web应用。server也是依赖client，因为server本身也是一个client，在eurka集群模式时，eurka server也要扮演eurka client的角色，往其他的eurka server上去注册。eurka-server可以打成一个war包，然后扔到一个web容器中就可以使用，比如tomcat，Jetty等。  
**eureka-core**：eurka代码核心，接收别人的服务注册请求，提供服务发现的功能，保持心跳（续约请求），摘除故障服务实例。eurka server依赖eurka core的功能对外暴露接口，提供注册中心功能。  
**eureka-client**：eurka-client负责向eurka-server注册、获取服务列表，发送心跳等。  
**eureka-resources**:就是提供了一些前端相关的页面js，css等文件。eurka的管理端应该就是在这里。其中status.jsp就是用来展示注册服务的信息的。

## Eureka依赖
**Jersey**：Eurka用Jersey实现了Http通信，对外提供了一些restful接口来通信。

---
## Eureka源码

### Eureka-server web.xml结构
从eurka-server的web.xml开始入手，首先是一个Listener**EurekaBootStrap**：负责eurka-server的初始化

#### 四个filter  
+ StatusFilter：
+ ServerRequestAuthFilter：
+ RateLimitingFilter：限流
+ GzipEncodingEnforcingFilter：报文压缩  
通过<filter-mapping>可知StatusFilter和ServerRequestAuthFilter是对所有请求都开放的。RateLimitingFilter，默认是不开启的，如果需要打开eurka-server内置的限流功能，需要自己吧RateLimitingFilter的<filter-mapping>注释打开。GzipEncodingEnforcingFilter拦截/v2/apps相关的请求。Jersery的核心filter是默认拦截所有请求的。
  
### EurekaBootStrap源码
EurekaBootStrap的主要功能就是负责启动、初始化、并配置Eurka。在EurekaBootStrap中监听器执行初始化的方法，是contextInitialized()方法，这个方法就是整个eureka-server启动初始化的一个入口。
在contextInitialized()方法中，initEurekaEnvironment()方法负责初始化eureka-server的环境。在里面会调用ConfigurationManager.getConfigInstance()方法，这个方法的作用就是在初始化ConfigurationManager的实例，ConfigurationManager从字面上就能看出来是一个配置管理器，负责将配置文件中的配置加载进来供后面的Eurka初始化使用（后面会说到，没有配置的话会自动使用默认配置）。
> 这里注意一下ConfigurationManager初始化使用了Double check形式的单例模式（TODO 后面把代码贴上来），一般我看开源项目中，使用内部类单例的比较少，大部分都使用了DoubleCheck形式的单例模式，DoubleCheck的单例模式需要重点注意一点就是使用volatile关键字修饰单例对象，不然在多线程的情况下，有可能初始化多次。  
>
initEurekaEnvironment中ConfigurationManager初始化流程
  1.创建一个ConcurrentCompositeConfiguration实例，这个类就包括了eureka所需的所有配置。初始化的时候调用了clear()方法，该方法的作用就是清理了一下配置Map和Eureka相关的事件的监听器List（map和list都是用的线程安全的类，具体哪个自己想），随后调用了fireEvent()方法发布了一个事件(EVENT_CLEAR).fireEvent()方法是netfilx另一个项目netfiex-config中的源码方法，有时间在研究一下那个项目。
  2.随后就往ConcurrentCompositeConfiguration中又加入了一些别的config，随后返回了这个实例。
  3.初始化数据数据中心的配置，如果没有的话，就使用默认配置Default data center（这里的数据中心是干嘛的？）
  4.初始化eureka的运行环境，如果没有配置指定，那么就设置为test环境（有什么影响吗？）
