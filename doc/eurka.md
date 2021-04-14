# Eurka
## Eurka项目工程
**eurka-server**：eurka-server的本质也是一个web应用。server也是依赖client，因为server本身也是一个client，在eurka集群模式时，eurka server也要扮演eurka client的角色，往其他的eurka server上去注册。eurka-server可以打成一个war包，然后扔到一个web容器中就可以使用，比如tomcat，Jetty等。
**eurka-core**：eurka代码核心，接收别人的服务注册请求，提供服务发现的功能，保持心跳（续约请求），摘除故障服务实例。eurka server依赖eurka core的功能对外暴露接口，提供注册中心功能。  
**eurka-client**：eurka-client负责向eurka-server注册、获取服务列表，发送心跳等。  
**eurka-resources**:就是提供了一些前端相关的页面js，css等文件。eurka的管理端应该就是在这里。其中status.jsp就是用来展示注册服务的信息的。

## Eurka依赖
**Jersey**：Eurka用Jersey实现了Http通信，对外提供了一些restful接口来通信。

## Eurka源码
从eurka-server的web.xml开始入手，首先是一个Listener
**EurkaBootStrap**：负责eurka-server的初始化

#### 四个filter  
+ StatusFilter：
+ ServerRequestAuthFilter：
+ RateLimitingFilter：限流
+ GzipEncodingEnforcingFilter：报文压缩  
通过<filter-mapping>可知StatusFilter和ServerRequestAuthFilter是对所有请求都开放的。RateLimitingFilter，默认是不开启的，如果需要打开eurka-server内置的限流功能，需要自己吧RateLimitingFilter的<filter-mapping>注释打开。GzipEncodingEnforcingFilter拦截/v2/apps相关的请求。Jersery的核心filter是默认拦截所有请求的。
